// Unit tests for the dashboard's pure logic (node --test).
// Run: npm test  — no DOM, no network, no Java modules involved.

import test from 'node:test';
import assert from 'node:assert/strict';

import { makeMockSnapshot, makeMockDelta, applyDelta, deriveSeverity } from '../src/data.js';
import {
  makeEvent,
  createEventStore,
  eventsFromDelta,
  makeMockIncident,
  EVENT_KINDS,
} from '../src/events.js';
import { applyEventToState, createReplay } from '../src/replay.js';
import { createChaosEngine } from '../src/chaos.js';
import { createFunnelTracker } from '../src/funnel.js';
import { createMetricsHistory, toCSV, sparklineSVG } from '../src/reports.js';
import { isStatusSnapshot } from '../src/ws.js';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// --- status/1 contract regression (data.js must stay intact) ------------------

test('deriveSeverity implements the normative rules', () => {
  assert.equal(deriveSeverity({ devicesTotal: 5, online: 0, received: 3, acknowledged: 3, failed: 0 }), 'offline');
  assert.equal(deriveSeverity({ devicesTotal: 5, online: 5, received: 10, acknowledged: 5, failed: 3 }), 'critical');
  assert.equal(deriveSeverity({ devicesTotal: 5, online: 4, received: 10, acknowledged: 10, failed: 0 }), 'warning');
  assert.equal(deriveSeverity({ devicesTotal: 5, online: 5, received: 10, acknowledged: 10, failed: 0 }), 'online');
});

test('applyDelta merges partial zones, recomputes severity, replaces alerts', () => {
  const snap = makeMockSnapshot(42);
  const zone = snap.floors[0].zones[0];
  const delta = {
    version: 1,
    type: 'STATUS_DELTA',
    buildingId: snap.buildingId,
    ts: '2026-11-21T10:00:02Z',
    floors: [{ floorNumber: 1, zones: [{ zoneCode: zone.zoneCode, online: 0 }] }],
    activeAlerts: [],
    metrics: { connected: 1 },
  };
  const next = applyDelta(snap, delta);
  const merged = next.floors[0].zones[0];
  assert.equal(merged.online, 0);
  assert.equal(merged.severity, 'offline');
  assert.equal(merged.received, zone.received); // absent fields kept
  assert.deepEqual(next.activeAlerts, []);
  assert.equal(next.metrics.connected, 1);
  assert.equal(next.metrics.p50, snap.metrics.p50); // field-wise merge
  assert.equal(applyDelta(snap, { ...delta, buildingId: 'OTHER' }), snap);
});

test('human ACK delta updates userAcknowledged without changing receipt count', () => {
  const snap = makeMockSnapshot(42);
  const zone = snap.floors[0].zones[0];
  const delta = {
    type: 'STATUS_DELTA',
    floors: [{ floorNumber: 1, zones: [{ zoneCode: zone.zoneCode,
      acknowledged: zone.acknowledged, userAcknowledged: 3 }] }],
  };
  const merged = applyDelta(snap, delta).floors[0].zones[0];
  assert.equal(merged.userAcknowledged, 3);
  assert.equal(merged.acknowledged, zone.acknowledged);
});

// --- events ---------------------------------------------------------------------

test('event store assigns seq, caps size, notifies subscribers', () => {
  const store = createEventStore({ limit: 3 });
  const seen = [];
  store.subscribe((e) => seen.push(e));
  for (let i = 0; i < 5; i++) {
    store.append(makeEvent({ incidentId: 'INC-X', eventKind: 'ACK_RECEIVED', payload: { count: i } }));
  }
  assert.equal(store.size, 3);
  assert.equal(store.all[0].seq, 3);
  assert.equal(store.all[2].seq, 5);
  assert.equal(seen.length, 5);
  assert.equal(store.append({ type: 'STATUS_DELTA' }), null); // only INCIDENT_EVENT
});

test('eventsFromDelta derives delivery/ack/failure/alert events', () => {
  const prev = makeMockSnapshot(42);
  const next = structuredClone(prev);
  const z = next.floors[6].zones[2]; // F07-C (critical showcase zone)
  z.received += 4;
  z.acknowledged += 2;
  z.failed += 1;
  z.severity = deriveSeverity(z);
  next.ts = '2026-11-21T10:00:04Z';
  next.activeAlerts = [{ alertId: 'AL-NEW', severity: 'warning', floorNumber: 1, zoneCode: 'F01-A', message: 'm', ts: next.ts }];

  const events = eventsFromDelta(prev, next);
  const kinds = events.map((e) => e.eventKind);
  assert.ok(kinds.includes('DELIVERY_SENT'));
  assert.ok(kinds.includes('ACK_RECEIVED'));
  assert.ok(kinds.includes('RETRY_SCHEDULED'));
  assert.ok(kinds.includes('DELIVERY_FAILED'));
  assert.ok(kinds.includes('ALERT_PUBLISHED'));
  const failed = events.find((e) => e.eventKind === 'DELIVERY_FAILED');
  assert.equal(failed.payload.count, 1);
  assert.equal(failed.payload.zoneCode, 'F07-C');
  assert.deepEqual(failed.payload.counters, {
    online: z.online, received: z.received, acknowledged: z.acknowledged, failed: z.failed,
  });
  const retries = events.find((e) => e.eventKind === 'RETRY_SCHEDULED');
  assert.equal(retries.payload.count, 3); // EBSP: 3 retries per failed delivery
  for (const e of events) {
    assert.equal(e.type, 'INCIDENT_EVENT');
    assert.equal(e.version, 1);
    assert.equal(e.buildingId, prev.buildingId);
  }

  // alert removal ⇒ resolved transition + cancelled tombstone
  const cleared = structuredClone(next);
  cleared.activeAlerts = [];
  const closeEvents = eventsFromDelta(next, cleared);
  assert.ok(closeEvents.some((e) => e.eventKind === 'INCIDENT_TRANSITION' && e.payload.state === 'RESOLVED'));
  assert.ok(closeEvents.some((e) => e.eventKind === 'ALERT_CANCELLED'));
});

test('makeMockIncident is deterministic, monotonic and contract-shaped', () => {
  const a = makeMockIncident({ seed: 7, startTs: 1763700000000 });
  const b = makeMockIncident({ seed: 7, startTs: 1763700000000 });
  assert.deepEqual(a, b);
  assert.ok(a.length >= 15);
  let lastTs = 0;
  let lastAck = -1;
  let lastFailed = -1;
  let lastReceived = -1;
  for (const e of a) {
    assert.equal(e.type, 'INCIDENT_EVENT');
    assert.ok(EVENT_KINDS.includes(e.eventKind));
    const t = Date.parse(e.ts);
    assert.ok(t >= lastTs);
    lastTs = t;
    const c = e.payload.counters;
    assert.ok(c, 'every demo event carries replay counters');
    assert.ok(c.acknowledged >= lastAck, 'acknowledged monotonic');
    assert.ok(c.failed >= lastFailed, 'failed monotonic');
    assert.ok(c.received >= lastReceived, 'received monotonic');
    assert.ok(c.acknowledged <= c.received);
    lastAck = c.acknowledged;
    lastFailed = c.failed;
    lastReceived = c.received;
  }
  assert.equal(a[0].eventKind, 'INCIDENT_OPENED');
  assert.equal(a[a.length - 1].payload.state, 'CLOSED');
});

// --- replay ---------------------------------------------------------------------

test('applyEventToState patches zone counters without mutating input', () => {
  const base = makeMockSnapshot(42);
  const before = JSON.stringify(base);
  const evt = makeEvent({
    incidentId: 'INC-1',
    eventKind: 'DELIVERY_FAILED',
    payload: { zoneCode: 'F07-C', counters: { online: 9, received: 40, acknowledged: 18, failed: 12 } },
  });
  const next = applyEventToState(base, evt);
  const z = next.floors[6].zones[2];
  assert.equal(z.failed, 12);
  assert.equal(z.severity, 'critical');
  assert.equal(JSON.stringify(base), before);
  assert.equal(applyEventToState(base, makeEvent({ incidentId: 'I', eventKind: 'INCIDENT_TRANSITION', payload: { state: 'OPEN' } })), base);
});

test('replay controller: load/step/seek/stop are coherent', () => {
  const base = makeMockSnapshot(42);
  const events = makeMockIncident({ seed: 3, startTs: 1763700000000 });
  const paints = [];
  const replay = createReplay({ tickMs: 5 });
  replay.onPaint = (s, e) => paints.push([s, e]);
  replay.load(events, base);
  assert.equal(replay.index, -1);
  replay.stepForward();
  assert.equal(replay.index, 0);
  assert.equal(replay.state.floors[4].zones[1].zoneCode, 'F05-B');
  replay.seek(5);
  assert.equal(replay.index, 5);
  replay.stepBack();
  assert.equal(replay.index, 4);
  replay.stop();
  assert.equal(replay.index, -1);
  assert.equal(paints[paints.length - 1][0], null); // restore-live signal
});

test('replay play() advances to the end and fires onEnd', async () => {
  const base = makeMockSnapshot(42);
  const events = makeMockIncident({ seed: 3, startTs: 1763700000000 }).slice(0, 4);
  const replay = createReplay({ tickMs: 5 });
  let ended = false;
  replay.onEnd = () => { ended = true; };
  replay.load(events, base);
  replay.play();
  await sleep(120);
  assert.equal(replay.index, events.length - 1);
  assert.equal(replay.playing, false);
  assert.equal(ended, true);
});

// --- chaos ------------------------------------------------------------------------

function zoneDelta(prev, zoneCode, patch, metrics = {}) {
  const floor = prev.floors.find((f) => f.zones.some((z) => z.zoneCode === zoneCode));
  const z = floor.zones.find((x) => x.zoneCode === zoneCode);
  return {
    version: 1,
    type: 'STATUS_DELTA',
    buildingId: prev.buildingId,
    ts: '2026-11-21T10:00:02Z',
    floors: [{ floorNumber: floor.floorNumber, zones: [{ zoneCode, ...{ online: z.online, received: z.received, acknowledged: z.acknowledged, failed: z.failed, severity: z.severity }, ...patch }] }],
    metrics: { connected: prev.metrics.connected, p50: 40, p95: 180, p99: 350, retryCount: prev.metrics.retryCount, ...metrics },
  };
}

test('chaos DROP_ACK freezes acknowledged and fails deliveries after sustained unacked', () => {
  const prev = makeMockSnapshot(42);
  const z = prev.floors[0].zones[0];
  const chaos = createChaosEngine({ ackDelayMs: 5 });
  const emitted = [];
  const chaos2 = createChaosEngine({ emit: (e) => emitted.push(e), ackDelayMs: 5 });
  chaos2.command('DROP_ACK', { enabled: true });
  assert.equal(emitted.length, 1);
  assert.equal(emitted[0].eventKind, 'CHAOS_COMMAND');
  assert.equal(emitted[0].correlationId, chaos2.log[0].id);

  const d1 = zoneDelta(prev, z.zoneCode, { received: z.received + 5, acknowledged: z.acknowledged + 5 });
  const t1 = chaos2.transformDelta(prev, d1);
  const z1 = t1.floors[0].zones[0];
  assert.equal(z1.acknowledged, z.acknowledged); // ACK dropped
  assert.equal(z1.failed, z.failed); // streak 1: not yet failed

  const prev2 = applyDelta(prev, t1);
  const d2 = zoneDelta(prev2, z.zoneCode, { received: z.received + 10, acknowledged: z.acknowledged + 10 });
  const t2 = chaos2.transformDelta(prev2, d2);
  const z2 = t2.floors[0].zones[0];
  assert.equal(z2.acknowledged, prev2.floors[0].zones[0].acknowledged);
  assert.equal(z2.failed, z.failed + 1); // max retries exhausted
  assert.equal(t2.metrics.retryCount, d2.metrics.retryCount + 3);
  assert.equal(chaos.transformDelta(prev, d1), d1); // all-disabled ⇒ identity
});

test('chaos DELAY_ACK inflates percentiles; DISCONNECT drops devices; prev untouched', () => {
  const prev = makeMockSnapshot(42);
  const before = JSON.stringify(prev);
  const z = prev.floors[2].zones[0]; // F03-A
  const chaos = createChaosEngine({ ackDelayMs: 5 });
  chaos.command('DELAY_ACK', { enabled: true, ms: 500 });
  chaos.command('DISCONNECT', { enabled: true, pct: 20 });
  const d = zoneDelta(prev, z.zoneCode, {});
  const t = chaos.transformDelta(prev, d);
  assert.equal(t.metrics.p50, 240); // 40 + 0.4*500
  assert.equal(t.metrics.p95, 680); // 180 + 500
  assert.equal(t.metrics.p99, 1150); // 350 + 1.6*500
  const tz = t.floors[0].zones[0];
  assert.equal(tz.online, Math.floor(z.online * 0.8));
  assert.equal(tz.severity, deriveSeverity({ ...z, online: tz.online }));
  assert.equal(JSON.stringify(prev), before);
  chaos.allOff();
  assert.equal(chaos.anyEnabled, false);
});

test('chaos command log reaches acknowledged (mock COMMAND_RESULT)', async () => {
  const chaos = createChaosEngine({ ackDelayMs: 10 });
  const rec = chaos.command('DISCONNECT', { enabled: true, pct: 25 });
  assert.equal(rec.status, 'sent');
  await sleep(40);
  assert.equal(rec.status, 'acknowledged');
  assert.equal(chaos.state.disconnectPct, 25);
});

// --- funnel ---------------------------------------------------------------------

function funnelState({ received = 20, acknowledged = 20, failed = 0, alerts = true } = {}) {
  return {
    version: 1,
    type: 'STATUS_SNAPSHOT',
    buildingId: 'AEGIS-BLD-A',
    ts: '2026-11-21T10:00:00Z',
    floors: [{ floorNumber: 1, zones: [{ zoneCode: 'F01-A', devicesTotal: 10, online: 10, received, acknowledged, failed, severity: 'warning' }] }],
    activeAlerts: alerts ? [{ alertId: 'AL-F01-A', severity: 'warning', floorNumber: 1, zoneCode: 'F01-A', message: 'm', ts: '2026-11-21T10:00:00Z' }] : [],
    metrics: { connected: 10, p50: 40, p95: 180, p99: 350, retryCount: 0 },
  };
}

test('funnel tracks increments since alert opened and clamps to targeted', () => {
  const tracker = createFunnelTracker();
  const s1 = funnelState();
  tracker.update(s1);
  const s2 = funnelState({ received: 25, acknowledged: 23, failed: 1 });
  tracker.update(s2);
  const f = tracker.funnelFor('AL-F01-A', s2);
  assert.equal(f.targeted, 10);
  assert.equal(f.received, 5);
  assert.equal(f.acknowledged, 3);
  assert.equal(f.failed, 1);
  assert.equal(f.pending, 5);
  assert.equal(f.unacked, 1);
  assert.ok(Math.abs(f.ackRate - 0.6) < 1e-9);

  const s3 = funnelState({ received: 100, acknowledged: 90, failed: 5 });
  tracker.update(s3);
  const g = tracker.funnelFor('AL-F01-A', s3);
  assert.equal(g.received, 10); // clamped to targeted
  assert.equal(g.acknowledged, 10); // clamped to received
  assert.equal(g.failed, 0); // clamped to received − acknowledged
  assert.ok(g.acknowledged + g.failed <= g.received);

  tracker.update(funnelState({ alerts: false }));
  const closed = tracker.funnelFor('AL-F01-A', funnelState({ alerts: false }));
  assert.equal(closed.active, false);
  assert.ok(closed.closedTs);
  assert.equal(tracker.all(s3)[0].alertId, 'AL-F01-A');
});

// --- reports ----------------------------------------------------------------------

test('metrics history computes window stats; CSV escapes; sparkline renders', () => {
  const h = createMetricsHistory({ limit: 3 });
  for (const [i, p95] of [100, 200, 300, 400].entries()) {
    h.sample({ ts: `2026-11-21T10:00:0${i}Z`, metrics: { connected: 10, p50: 40, p95, p99: 350, retryCount: i } });
  }
  assert.equal(h.samples.length, 3); // capped
  const s = h.stats();
  assert.equal(s.metrics.p95.min, 200);
  assert.equal(s.metrics.p95.max, 400);
  assert.equal(s.metrics.p95.avg, 300);
  assert.equal(s.metrics.p95.current, 400);
  assert.equal(s.from, '2026-11-21T10:00:01Z');

  const csv = toCSV([{ a: 'x,y', b: 'say "hi"', c: null }], [{ key: 'a' }, { key: 'b' }, { key: 'c' }]);
  assert.equal(csv, 'a,b,c\r\n"x,y","say ""hi""",');
  assert.ok(sparklineSVG([1, 2, 3]).includes('<polyline'));
  assert.equal(sparklineSVG([]), '');
});

// --- mock feed coherence (chaos transform plugs into makeMockDelta output) --------

test('mock delta + chaos transform keeps the world coherent', () => {
  let rngState = 12345;
  const rng = () => {
    rngState = (rngState * 1103515245 + 12345) % 2147483648;
    return rngState / 2147483648;
  };
  const chaos = createChaosEngine({ ackDelayMs: 5 });
  chaos.command('DROP_ACK', { enabled: true });
  let state = makeMockSnapshot(42);
  for (let i = 0; i < 6; i++) {
    const delta = chaos.transformDelta(state, makeMockDelta(state, rng));
    assert.equal(delta.type, 'STATUS_DELTA');
    const next = applyDelta(state, delta);
    for (const f of next.floors) {
      for (const z of f.zones) {
        assert.ok(z.acknowledged <= z.received);
        assert.ok(z.online >= 0 && z.online <= z.devicesTotal);
        assert.equal(z.severity, deriveSeverity(z));
      }
    }
    state = next;
  }
});

test('LIVE requires a valid flat status/1 snapshot, not just an open socket', () => {
  assert.equal(isStatusSnapshot({ type: 'SERVER_HELLO' }), false);
  assert.equal(isStatusSnapshot({ type: 'STATUS_DELTA', floors: [] }), false);
  assert.equal(isStatusSnapshot({ type: 'STATUS_SNAPSHOT' }), false);
  assert.equal(isStatusSnapshot({ type: 'STATUS_SNAPSHOT', floors: [], activeAlerts: [], metrics: {} }), true);
});
