// ---------------------------------------------------------------------------
// Aegis EBS — Chaos control engine (MOCK commands).
//
// status/1 has no chaos opcode, so commands are executed against the local
// mock simulation (src/ws.js mock feed) and logged as INCIDENT_EVENT frames
// with eventKind CHAOS_COMMAND (actor "operator", correlationId = command id).
// Scenarios mirror docs/TEST-PLAN.md: C01 drop ACK · C02 delay ACK ·
// C04 disconnect a share of devices.
//
// transformDelta(prev, delta) post-processes each mock STATUS_DELTA:
//  * DROP_ACK   — acknowledged frozen at its previous value; once a zone has
//                 ≥3 unacked for 2+ ticks, deliveries FAIL (retryCount += 3).
//  * DELAY_ACK  — latency percentiles inflated by the configured extra ms.
//  * DISCONNECT — online reduced by pct% on every touched zone.
// Severity is recomputed with the normative rules; `prev` is never mutated.
// ---------------------------------------------------------------------------

import { applyDelta, deriveSeverity } from './data.js';

export const CHAOS_COMMANDS = ['DROP_ACK', 'DELAY_ACK', 'DISCONNECT'];

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export function createChaosEngine({ emit, onLog, ackDelayMs = 250 } = {}) {
  const state = {
    dropAck: false,
    delayEnabled: false,
    delayMs: 500,
    disconnectEnabled: false,
    disconnectPct: 20,
  };
  const log = [];
  const unackedStreak = new Map(); // zoneCode -> consecutive ticks with unacked
  let cmdSeq = 0;

  function command(name, params = {}) {
    if (!CHAOS_COMMANDS.includes(name)) return null;
    const id = `CHAOS-${String(++cmdSeq).padStart(3, '0')}`;
    const ts = new Date().toISOString();
    let enabled;
    if (name === 'DROP_ACK') {
      state.dropAck = params.enabled ?? !state.dropAck;
      enabled = state.dropAck;
      if (!enabled) unackedStreak.clear();
    } else if (name === 'DELAY_ACK') {
      if (typeof params.ms === 'number') state.delayMs = clamp(Math.round(params.ms), 100, 2000);
      state.delayEnabled = params.enabled ?? !state.delayEnabled;
      enabled = state.delayEnabled;
    } else {
      if (typeof params.pct === 'number') state.disconnectPct = clamp(Math.round(params.pct), 5, 50);
      state.disconnectEnabled = params.enabled ?? !state.disconnectEnabled;
      enabled = state.disconnectEnabled;
    }
    const rec = {
      id,
      ts,
      command: name,
      params: { enabled, ...(name === 'DELAY_ACK' ? { ms: state.delayMs } : {}), ...(name === 'DISCONNECT' ? { pct: state.disconnectPct } : {}) },
      status: 'sent',
      result: null,
    };
    log.unshift(rec);
    if (log.length > 50) log.pop();
    emit?.({
      incidentId: 'INC-CHAOS',
      eventKind: 'CHAOS_COMMAND',
      actorId: 'operator',
      correlationId: id,
      payload: { command: name, ...rec.params },
      ts,
    });
    // Mock COMMAND_RESULT: the "server" acknowledges the command shortly after.
    setTimeout(() => {
      rec.status = 'acknowledged';
      rec.result = 'OK (mock)';
      onLog?.();
    }, ackDelayMs);
    onLog?.();
    return rec;
  }

  function allOff() {
    const recs = [];
    if (state.dropAck) recs.push(command('DROP_ACK', { enabled: false }));
    if (state.delayEnabled) recs.push(command('DELAY_ACK', { enabled: false }));
    if (state.disconnectEnabled) recs.push(command('DISCONNECT', { enabled: false }));
    return recs;
  }

  function transformDelta(prev, delta) {
    if (!prev || !delta || delta.type !== 'STATUS_DELTA') return delta;
    if (!state.dropAck && !state.delayEnabled && !state.disconnectEnabled) return delta;
    const d = structuredClone(delta);
    const prevZones = new Map(prev.floors.flatMap((f) => f.zones.map((z) => [z.zoneCode, z])));
    let extraFailed = 0;

    for (const f of d.floors ?? []) {
      for (const z of f.zones ?? []) {
        const p = prevZones.get(z.zoneCode);
        if (!p) continue;
        if (state.dropAck) {
          // ACKs are dropped on the wire: acknowledged must not advance.
          z.acknowledged = p.acknowledged;
          const received = z.received ?? p.received;
          const unacked = received - z.acknowledged;
          const streak = (unackedStreak.get(z.zoneCode) ?? 0) + 1;
          unackedStreak.set(z.zoneCode, streak);
          if (unacked >= 3 && streak >= 2 && (z.online ?? p.online) > 0) {
            z.failed = (z.failed ?? p.failed) + 1; // max retries exhausted
            extraFailed += 1;
          }
        } else {
          unackedStreak.delete(z.zoneCode);
        }
        if (state.disconnectEnabled) {
          const online = z.online ?? p.online;
          z.online = Math.max(0, Math.floor(online * (1 - state.disconnectPct / 100)));
        }
        z.severity = deriveSeverity({ ...p, ...z });
      }
    }

    const merged = applyDelta(prev, d);
    const connected = merged.floors.reduce((s, f) => s + f.zones.reduce((a, z) => a + z.online, 0), 0);
    d.metrics = { ...(d.metrics ?? {}), connected };
    if (extraFailed > 0) {
      d.metrics.retryCount = (d.metrics.retryCount ?? prev.metrics.retryCount) + extraFailed * 3;
    }
    if (state.delayEnabled) {
      const m = d.metrics;
      m.p50 = clamp(Math.round((m.p50 ?? prev.metrics.p50) + state.delayMs * 0.4), 20, 5000);
      m.p95 = clamp(Math.round((m.p95 ?? prev.metrics.p95) + state.delayMs), 60, 8000);
      m.p99 = clamp(Math.round((m.p99 ?? prev.metrics.p99) + state.delayMs * 1.6), 100, 12000);
    }
    return d;
  }

  return {
    command,
    allOff,
    transformDelta,
    get state() {
      return { ...state };
    },
    get log() {
      return log;
    },
    get anyEnabled() {
      return state.dropAck || state.delayEnabled || state.disconnectEnabled;
    },
  };
}
