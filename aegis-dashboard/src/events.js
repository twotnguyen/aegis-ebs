// ---------------------------------------------------------------------------
// Aegis EBS — INCIDENT_EVENT contract + event store + mock incident generator.
//
// INCIDENT_EVENT is an EBSP/2.0 MessageType (aegis-protocol MessageType.java).
// The status/1 WS bridge forwards incident timeline events to the dashboard
// using the SAME envelope as STATUS_* messages, plus fields mirroring the
// persistence table `incident_events` (incident_id, occurred_at, event_kind,
// actor_id, correlation_id, payload_json). Human spec: docs/status-contract.md.
//
// Mock-first: `makeMockIncident()` produces a deterministic scripted incident;
// `eventsFromDelta()` derives events from STATUS_DELTA counter changes so the
// timeline is alive in MOCK mode (and works with live deltas too).
// ---------------------------------------------------------------------------

import { BUILDING_ID } from './data.js';
import { t } from './i18n.js';

export const EVENT_KINDS = [
  'INCIDENT_OPENED',
  'INCIDENT_TRANSITION',
  'ALERT_PUBLISHED',
  'ALERT_UPDATED',
  'ALERT_CANCELLED',
  'DELIVERY_SENT',
  'ACK_RECEIVED',
  'ACK_USER',
  'RETRY_SCHEDULED',
  'DELIVERY_FAILED',
  'DEVICE_OFFLINE',
  'DEVICE_ONLINE',
  'CHAOS_COMMAND',
];

// incidents.state CHECK constraint (aegis-persistence 002_showcase_expansion.sql)
export const INCIDENT_STATES = [
  'DRAFT',
  'OPEN',
  'INVESTIGATING',
  'CONTAINED',
  'RESOLVED',
  'CLOSED',
  'ABORTED',
];

export const EVENT_CONTRACT = {
  feed: 'status/1',
  envelope: {
    version: 'number — 1',
    type: '"INCIDENT_EVENT"',
    buildingId: 'string',
    ts: 'string — UTC ISO-8601 (incident_events.occurred_at)',
  },
  fields: {
    incidentId: 'string — incidents.id (mock: INC-<alertId> / INC-DEMO-xxx / INC-CHAOS)',
    seq: 'number — client-assigned arrival order (not on the wire)',
    eventKind: `string — one of EVENT_KINDS (${EVENT_KINDS.join(', ')})`,
    actorId: 'string | null — user id, "device:<code>", "operator", or "system"',
    correlationId: 'string | null — requestId / command id linking related events',
    payload:
      'object — kind-specific. Replay convention: {zoneCode, floorNumber, counters:{online,received,acknowledged,failed}} lets the dashboard repaint the 3D twin deterministically.',
  },
};

/** Build one INCIDENT_EVENT frame (wire shape; `seq` is added by the store). */
export function makeEvent({
  incidentId,
  eventKind,
  actorId = 'system',
  correlationId = null,
  payload = {},
  ts,
  buildingId = BUILDING_ID,
}) {
  return {
    version: 1,
    type: 'INCIDENT_EVENT',
    buildingId,
    ts: ts ?? new Date().toISOString(),
    incidentId,
    eventKind,
    actorId,
    correlationId,
    payload,
  };
}

/** Capped, subscribable event log (arrival order). */
export function createEventStore({ limit = 1000 } = {}) {
  let events = [];
  let seq = 0;
  const subs = new Set();
  const notify = (e) => subs.forEach((fn) => fn(e));
  return {
    append(evt) {
      if (!evt || evt.type !== 'INCIDENT_EVENT') return null;
      const e = { ...evt, seq: ++seq };
      events.push(e);
      if (events.length > limit) events.splice(0, events.length - limit);
      notify(e);
      return e;
    },
    appendMany(list) {
      const out = [];
      for (const evt of list ?? []) {
        const e = this.append(evt);
        if (e) out.push(e);
      }
      return out;
    },
    clear() {
      events = [];
      seq = 0;
      subs.forEach((fn) => fn(null));
    },
    subscribe(fn) {
      subs.add(fn);
      return () => subs.delete(fn);
    },
    get all() {
      return events;
    },
    get size() {
      return events.length;
    },
  };
}

// --- derive INCIDENT_EVENTs from a status transition -------------------------

const zoneIndexOf = (state) =>
  new Map(state.floors.flatMap((f) => f.zones.map((z) => [z.zoneCode, { z, floorNumber: f.floorNumber }])));

const countersOf = (z) => ({
  online: z.online,
  received: z.received,
  acknowledged: z.acknowledged,
  failed: z.failed,
});

/**
 * Pure: compare two states (before/after applyDelta) and emit the incident
 * events implied by the change. Same ts for all events of one delta.
 */
export function eventsFromDelta(prev, next) {
  if (!prev || !next || !Array.isArray(next.floors)) return [];
  const out = [];
  const ts = next.ts;
  const zones = zoneIndexOf(next);
  const prevZones = zoneIndexOf(prev);
  const nextAlerts = new Map((next.activeAlerts ?? []).map((a) => [a.alertId, a]));
  const prevAlerts = new Map((prev.activeAlerts ?? []).map((a) => [a.alertId, a]));
  const alertsByZone = new Map((next.activeAlerts ?? []).map((a) => [a.zoneCode, a]));
  const incidentFor = (zoneCode) => {
    const a = alertsByZone.get(zoneCode);
    return a ? `INC-${a.alertId}` : `INC-${zoneCode}`;
  };
  const push = (incidentId, eventKind, payload, actorId = 'system') =>
    out.push(makeEvent({ incidentId, eventKind, actorId, payload, ts, buildingId: next.buildingId }));

  for (const [alertId, a] of nextAlerts) {
    if (prevAlerts.has(alertId)) continue;
    const z = zones.get(a.zoneCode)?.z;
    push(`INC-${alertId}`, 'ALERT_PUBLISHED', {
      alertId,
      zoneCode: a.zoneCode,
      floorNumber: a.floorNumber,
      severity: a.severity,
      message: a.message,
      ...(z ? { counters: countersOf(z) } : {}),
    });
  }
  for (const [alertId, a] of prevAlerts) {
    if (nextAlerts.has(alertId)) continue;
    // EBSP tombstone: alert gone ⇒ incident resolved + alert cancelled/expired.
    push(`INC-${alertId}`, 'INCIDENT_TRANSITION', {
      state: 'RESOLVED',
      alertId,
      zoneCode: a.zoneCode,
      reason: 'alert no longer active',
    });
    push(`INC-${alertId}`, 'ALERT_CANCELLED', { alertId, zoneCode: a.zoneCode, tombstone: true });
  }

  for (const [zoneCode, { z, floorNumber }] of zones) {
    const p = prevZones.get(zoneCode)?.z;
    if (!p) continue;
    const incidentId = incidentFor(zoneCode);
    const base = { zoneCode, floorNumber, counters: countersOf(z) };
    if (z.received > p.received)
      push(incidentId, 'DELIVERY_SENT', { ...base, count: z.received - p.received });
    if (z.acknowledged > p.acknowledged)
      push(incidentId, 'ACK_RECEIVED', { ...base, count: z.acknowledged - p.acknowledged });
    if (z.failed > p.failed) {
      const d = z.failed - p.failed;
      push(incidentId, 'RETRY_SCHEDULED', {
        ...base,
        count: d * 3,
        note: 'EBSP demo: 3 retries per failed delivery (3s/6s/12s + jitter)',
      });
      push(incidentId, 'DELIVERY_FAILED', { ...base, count: d });
    }
    if (z.online < p.online) push(incidentId, 'DEVICE_OFFLINE', { ...base, count: p.online - z.online });
    if (z.online > p.online) push(incidentId, 'DEVICE_ONLINE', { ...base, count: z.online - p.online });
  }
  return out;
}

// --- scripted demo incident (deterministic, mock-first) -----------------------

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a += 0x6d2b79f5;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * FIRE_DRILL on floor 5 (mirrors docs/DEMO-RUNBOOK.md "Incident launch").
 * Counters in payloads are monotonic where EBSP requires it (acknowledged /
 * failed / received never decrease), so replay paints a coherent twin.
 */
export function makeMockIncident({
  seed = 7,
  startTs = Date.now() - 180000,
  buildingId = BUILDING_ID,
  incidentId = 'INC-DEMO-001',
  alertId = 'AL-F05-B',
  zoneCode = 'F05-B',
  floorNumber = 5,
} = {}) {
  const rng = mulberry32(seed);
  const events = [];
  let cursor = startTs;
  const c = { online: 12, received: 20, acknowledged: 20, failed: 0 };
  const push = (eventKind, payload, actorId = 'system', gapMs = 6000) => {
    cursor += gapMs + Math.floor(rng() * 4000);
    events.push(
      makeEvent({
        incidentId,
        eventKind,
        actorId,
        correlationId: `demo-${incidentId}`,
        payload: { zoneCode, floorNumber, ...payload },
        ts: new Date(cursor).toISOString(),
        buildingId,
      }),
    );
  };

  push('INCIDENT_OPENED', { kind: 'FIRE_DRILL', state: 'OPEN', summary: t('event.mock.summary'), counters: { ...c } }, 'operator-1', 0);
  push('ALERT_PUBLISHED', { alertId, severity: 'critical', message: 'FIRE_DRILL · evacuate floor 5', counters: { ...c } }, 'operator-1');
  c.received += 12;
  push('DELIVERY_SENT', { count: 12, targeted: 12, counters: { ...c } });
  c.acknowledged += 7;
  push('ACK_RECEIVED', { count: 7, counters: { ...c } }, 'device:F05-B');
  c.online -= 2;
  push('DEVICE_OFFLINE', { count: 2, counters: { ...c } });
  push('RETRY_SCHEDULED', { count: 9, note: '3 retries × 3 unacked (3s/6s/12s + jitter)', counters: { ...c } });
  c.failed += 2;
  push('DELIVERY_FAILED', { count: 2, counters: { ...c } });
  c.acknowledged += 2;
  push('ACK_USER', { count: 2, note: 'operator confirmed on scene', counters: { ...c } }, 'operator-1');
  push('INCIDENT_TRANSITION', { state: 'INVESTIGATING', counters: { ...c } }, 'operator-1');
  c.online += 2;
  push('DEVICE_ONLINE', { count: 2, counters: { ...c } });
  c.received += 3;
  push('DELIVERY_SENT', { count: 3, counters: { ...c } });
  c.acknowledged += 3;
  push('ACK_RECEIVED', { count: 3, counters: { ...c } }, 'device:F05-B');
  push('INCIDENT_TRANSITION', { state: 'CONTAINED', counters: { ...c } }, 'operator-1');
  push('ALERT_UPDATED', { alertId, severity: 'warning', message: 'FIRE_DRILL contained · 2 failed deliveries under review', counters: { ...c } }, 'operator-1');
  push('INCIDENT_TRANSITION', { state: 'RESOLVED', counters: { ...c } }, 'operator-1');
  push('ALERT_CANCELLED', { alertId, tombstone: true, counters: { ...c } }, 'operator-1');
  push('INCIDENT_TRANSITION', { state: 'CLOSED', counters: { ...c } }, 'operator-1');
  return events;
}

// --- UI helpers ----------------------------------------------------------------

export const KIND_CATEGORY = {
  INCIDENT_OPENED: 'incident',
  INCIDENT_TRANSITION: 'incident',
  ALERT_PUBLISHED: 'alert',
  ALERT_UPDATED: 'alert',
  ALERT_CANCELLED: 'alert',
  DELIVERY_SENT: 'delivery',
  ACK_RECEIVED: 'ack',
  ACK_USER: 'ack',
  RETRY_SCHEDULED: 'retry',
  DELIVERY_FAILED: 'failed',
  DEVICE_OFFLINE: 'device',
  DEVICE_ONLINE: 'device',
  CHAOS_COMMAND: 'chaos',
};

export function summarizeEvent(e) {
  const p = e?.payload ?? {};
  const z = p.zoneCode ? ` · ${p.zoneCode}` : '';
  switch (e?.eventKind) {
    case 'INCIDENT_OPENED':
      return t('event.opened', { kind: p.kind ?? t('event.fallback.kind'), summary: p.summary ? ` — ${p.summary}` : '' });
    case 'INCIDENT_TRANSITION':
      return t('event.state', { state: p.state ?? t('event.fallback.state'), reason: p.reason ? ` (${p.reason})` : '' });
    case 'ALERT_PUBLISHED':
      return t('event.published', { id: p.alertId ?? t('event.fallback.alertId'), severity: p.severity ?? t('event.fallback.severity'), message: p.message ? ` — ${p.message}` : '' });
    case 'ALERT_UPDATED':
      return t('event.updated', { id: p.alertId ?? t('event.fallback.alertId'), severity: p.severity ?? t('event.fallback.severity') });
    case 'ALERT_CANCELLED':
      return t('event.cancelled', { id: p.alertId ?? t('event.fallback.alertId') });
    case 'DELIVERY_SENT':
      return t('event.deliverySent', { count: p.count, zone: z });
    case 'ACK_RECEIVED':
      return t('event.ackReceived', { count: p.count, zone: z });
    case 'ACK_USER':
      return t('event.ackUser', { count: p.count, zone: z });
    case 'RETRY_SCHEDULED':
      return t('event.retriesScheduled', { count: p.count, zone: z });
    case 'DELIVERY_FAILED':
      return t('event.deliveryFailed', { count: p.count, zone: z });
    case 'DEVICE_OFFLINE':
      return t('event.deviceOffline', { count: p.count, zone: z });
    case 'DEVICE_ONLINE':
      return t('event.deviceOnline', { count: p.count, zone: z });
    case 'CHAOS_COMMAND':
      return `${p.command} ${p.enabled ? t('event.enabled') : t('event.disabled')}${p.ms ? ` (${p.ms} ms)` : ''}${p.pct ? ` (${p.pct}%)` : ''}`;
    default:
      return e?.eventKind ?? 'event';
  }
}
