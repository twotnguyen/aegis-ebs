// ---------------------------------------------------------------------------
// Aegis EBS — status feed contract (status/1) + mock data.
//
// The Java server (aegis-server) will bridge EBSP/2.0 device state onto
// ws://localhost:9444 using EXACTLY the JSON shapes defined in CONTRACT below.
// Human-readable spec: docs/status-contract.md
// ---------------------------------------------------------------------------

export const WS_URL = 'ws://localhost:9444';
export const BUILDING_ID = 'AEGIS-BLD-A';
export const FLOOR_COUNT = 10;
export const ZONE_LETTERS = ['A', 'B', 'C', 'D']; // 2x2 grid per floor

export const SEVERITIES = ['online', 'warning', 'critical', 'offline'];

// xanh = online/acked · vàng = warning · đỏ = critical · xám = offline
export const SEVERITY_COLORS = {
  online: 0x22c55e,
  warning: 0xfacc15,
  critical: 0xef4444,
  offline: 0x64748b,
};

export const CONTRACT = {
  feed: 'status/1',
  transport: `WebSocket text frames (strict JSON) at ${WS_URL}`,
  envelope: {
    version: 'number — status-feed contract version, currently 1',
    type: '"STATUS_SNAPSHOT" | "STATUS_DELTA"',
    buildingId: 'string',
    ts: 'string — UTC ISO-8601, e.g. 2026-11-21T10:00:00Z',
  },
  STATUS_SNAPSHOT: {
    version: 1,
    type: 'STATUS_SNAPSHOT',
    buildingId: 'string',
    ts: 'ISO-8601',
    floors: [
      {
        floorNumber: 'number — 1..10',
        zones: [
          {
            zoneCode: 'string — unique per building, e.g. "F03-B"',
            devicesTotal: 'number — provisioned devices in zone',
            online: 'number — devices currently connected, 0..devicesTotal',
            received: 'number — messages received from zone (current window)',
            acknowledged: 'number — messages ACKed, 0..received (monotonic)',
            userAcknowledged: 'number — human ACK_USER count, 0..acknowledged',
            failed: 'number — deliveries FAILED after max retries (3)',
            severity: '"online" | "warning" | "critical" | "offline"',
          },
        ],
      },
    ],
    activeAlerts: [
      {
        alertId: 'string — stable id (EBSP alertId when bridged)',
        severity: '"warning" | "critical"',
        floorNumber: 'number',
        zoneCode: 'string',
        message: 'string — human-readable headline',
        ts: 'ISO-8601',
      },
    ],
    metrics: {
      connected: 'number — device sockets currently connected',
      p50: 'number — end-to-end delivery latency p50, milliseconds',
      p95: 'number — latency p95, milliseconds',
      p99: 'number — latency p99, milliseconds',
      retryCount: 'number — transport retries since server start',
    },
  },
  STATUS_DELTA: {
    version: 1,
    type: 'STATUS_DELTA',
    buildingId: 'string',
    ts: 'ISO-8601',
    floors:
      'optional — same shape as snapshot but ONLY changed floors/zones; zone fields may be partial',
    activeAlerts: 'optional — FULL replacement list when present',
    metrics: 'optional — partial; present fields overwrite',
  },
  severityRules: [
    'offline  ⇔ online === 0',
    'critical ⇔ failed > 0 && (failed / max(received,1) >= 0.2 || acknowledged / max(received,1) < 0.6)',
    'warning  ⇔ online < devicesTotal || failed > 0 || acknowledged < received',
    'online   ⇔ otherwise (all devices online, all received messages acked, no failures)',
  ],
  deltaMerge:
    'Match floors by floorNumber and zones by zoneCode. Scalar fields present in the delta overwrite; absent fields keep their previous value. If severity is omitted, the client recomputes it from counters via severityRules. activeAlerts replaces wholesale when present; metrics merges field-wise. Unknown floors/zones are ignored. Deltas for a different buildingId are ignored.',
  serverObligations: [
    'Send a full STATUS_SNAPSHOT immediately after a client connects (and after any resync).',
    'Then send STATUS_DELTA whenever state changes (batched, e.g. every 500ms–2s).',
    'Counters are monotonic where EBSP requires it: acknowledged never decreases; ACK only advances state.',
  ],
};

// --- severity derivation (shared reference for server + client) -------------

export function deriveSeverity(zone) {
  const { devicesTotal = 0, online = 0, received = 0, acknowledged = 0, failed = 0 } = zone;
  if (online <= 0) return 'offline';
  const r = Math.max(received, 1);
  if (failed > 0 && (failed / r >= 0.2 || acknowledged / r < 0.6)) return 'critical';
  if (online < devicesTotal || failed > 0 || acknowledged < received) return 'warning';
  return 'online';
}

// --- delta merge (pure: returns a new state, never mutates input) -----------

const COUNTER_FIELDS = ['devicesTotal', 'online', 'received', 'acknowledged', 'userAcknowledged', 'failed'];
const METRIC_FIELDS = ['connected', 'p50', 'p95', 'p99', 'retryCount'];

export function applyDelta(state, delta) {
  if (!state || !delta || delta.type !== 'STATUS_DELTA') return state;
  if (delta.buildingId && delta.buildingId !== state.buildingId) return state;
  const next = structuredClone(state);

  for (const f of delta.floors ?? []) {
    const floor = next.floors.find((x) => x.floorNumber === f.floorNumber);
    if (!floor) continue;
    for (const z of f.zones ?? []) {
      const zone = floor.zones.find((x) => x.zoneCode === z.zoneCode);
      if (!zone) continue;
      for (const key of COUNTER_FIELDS) {
        if (typeof z[key] === 'number') zone[key] = z[key];
      }
      zone.severity = SEVERITIES.includes(z.severity) ? z.severity : deriveSeverity(zone);
    }
  }
  if (Array.isArray(delta.activeAlerts)) next.activeAlerts = structuredClone(delta.activeAlerts);
  if (delta.metrics) {
    for (const key of METRIC_FIELDS) {
      if (typeof delta.metrics[key] === 'number') next.metrics[key] = delta.metrics[key];
    }
  }
  if (delta.ts) next.ts = delta.ts;
  return next;
}

// --- mock data ---------------------------------------------------------------

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

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const nowIso = () => new Date().toISOString();
export const zoneCodeFor = (floorNumber, letter) =>
  `F${String(floorNumber).padStart(2, '0')}-${letter}`;

const SEVERITY_RANK = { online: 0, offline: 1, warning: 2, critical: 3 };

function alertsFromZones(state) {
  const alerts = [];
  for (const f of state.floors) {
    for (const z of f.zones) {
      if (z.severity !== 'critical' && z.severity !== 'warning') continue;
      const unacked = Math.max(0, z.received - z.acknowledged);
      const message =
        z.severity === 'critical'
          ? `${z.failed} delivery FAILED after 3 retries · ${unacked} unacked`
          : `${z.devicesTotal - z.online} device(s) offline · ${unacked} unacked`;
      alerts.push({
        alertId: `AL-${z.zoneCode}`,
        severity: z.severity,
        floorNumber: f.floorNumber,
        zoneCode: z.zoneCode,
        message,
        ts: state.ts,
      });
    }
  }
  alerts.sort(
    (a, b) => SEVERITY_RANK[b.severity] - SEVERITY_RANK[a.severity] || a.zoneCode.localeCompare(b.zoneCode),
  );
  return alerts;
}

const alertSignature = (alerts) =>
  JSON.stringify((alerts ?? []).map((a) => [a.alertId, a.severity]));

export function makeMockSnapshot(seed = 42) {
  const rng = mulberry32(seed);
  const ts = nowIso();
  const floors = [];

  for (let floorNumber = 1; floorNumber <= FLOOR_COUNT; floorNumber++) {
    const zones = ZONE_LETTERS.map((letter) => {
      const devicesTotal = 8 + Math.floor(rng() * 8); // 8..15
      const received = 10 + Math.floor(rng() * 30);
      const zone = {
        zoneCode: zoneCodeFor(floorNumber, letter),
        devicesTotal,
        online: devicesTotal,
        received,
        acknowledged: received,
        userAcknowledged: received,
        failed: 0,
      };
      if (rng() < 0.08) {
        // scattered degraded zones
        zone.online = devicesTotal - 1;
        zone.acknowledged = received - 1;
        zone.userAcknowledged = received - 1;
      }
      return zone;
    });
    floors.push({ floorNumber, zones });
  }

  // Showcase zones so every color is visible on first load.
  const setZone = (floorNumber, letter, patch) => {
    const zone = floors
      .find((f) => f.floorNumber === floorNumber)
      .zones.find((z) => z.zoneCode === zoneCodeFor(floorNumber, letter));
    Object.assign(zone, patch);
  };
  setZone(3, 'A', { online: 10, received: 30, acknowledged: 27, failed: 1 }); // warning
  setZone(5, 'B', { online: 11, received: 25, acknowledged: 24, failed: 0 }); // warning
  setZone(7, 'C', { online: 9, received: 40, acknowledged: 18, failed: 9 }); // critical
  setZone(10, 'D', { online: 0, received: 12, acknowledged: 12, failed: 0 }); // offline

  for (const f of floors) for (const z of f.zones) z.severity = deriveSeverity(z);

  const connected = floors.reduce((s, f) => s + f.zones.reduce((a, z) => a + z.online, 0), 0);
  const failedTotal = floors.reduce((s, f) => s + f.zones.reduce((a, z) => a + z.failed, 0), 0);

  const snapshot = {
    version: 1,
    type: 'STATUS_SNAPSHOT',
    buildingId: BUILDING_ID,
    ts,
    floors,
    activeAlerts: [],
    metrics: {
      connected,
      p50: Math.round(35 + rng() * 15),
      p95: Math.round(150 + rng() * 60),
      p99: Math.round(300 + rng() * 120),
      retryCount: failedTotal * 3, // EBSP: 3 retries per failed delivery
    },
  };
  snapshot.activeAlerts = alertsFromZones(snapshot);
  return snapshot;
}

/** Build a partial STATUS_DELTA from the current (mock) state. */
export function makeMockDelta(state, rng = Math.random) {
  const all = state.floors.flatMap((f) => f.zones.map((zone) => ({ floorNumber: f.floorNumber, zone })));
  const picked = new Set();
  const n = 1 + Math.floor(rng() * 3); // 1..3 zones per tick
  while (picked.size < n) picked.add(Math.floor(rng() * all.length));

  const floorsMap = new Map();
  let extraRetries = 0;

  for (const idx of picked) {
    const { floorNumber, zone } = all[idx];
    const z = { ...zone };

    const roll = rng();
    let incoming = 0;
    if (roll < 0.04) {
      z.online = 0; // zone goes dark
    } else if (roll < 0.1 && zone.online === 0) {
      z.online = z.devicesTotal; // recovery
      incoming = Math.floor(rng() * 4);
    } else {
      z.online = clamp(z.online + (rng() < 0.5 ? -1 : 1), 0, z.devicesTotal);
      incoming = z.online > 0 ? Math.floor(rng() * 6) : 0;
    }
    z.received += incoming;
    // ACK only advances state (EBSP idempotency rule)
    z.acknowledged = Math.min(z.received, z.acknowledged + Math.max(0, incoming - (rng() < 0.2 ? 1 : 0)));
    if (z.online > 0 && rng() < 0.12) {
      z.failed += 1;
      extraRetries += 3;
    }
    z.severity = deriveSeverity(z);

    if (!floorsMap.has(floorNumber)) floorsMap.set(floorNumber, { floorNumber, zones: [] });
    floorsMap.get(floorNumber).zones.push({
      zoneCode: z.zoneCode,
      online: z.online,
      received: z.received,
      acknowledged: z.acknowledged,
      failed: z.failed,
      severity: z.severity,
    });
  }

  const floors = [...floorsMap.values()].sort((a, b) => a.floorNumber - b.floorNumber);
  const ts = nowIso();
  const merged = applyDelta(state, { type: 'STATUS_DELTA', buildingId: state.buildingId, ts, floors });

  const connected = merged.floors.reduce((s, f) => s + f.zones.reduce((a, z) => a + z.online, 0), 0);
  const delta = {
    version: 1,
    type: 'STATUS_DELTA',
    buildingId: state.buildingId,
    ts,
    floors,
    metrics: {
      connected,
      p50: clamp(Math.round(state.metrics.p50 + (rng() * 8 - 4)), 20, 200),
      p95: clamp(Math.round(state.metrics.p95 + (rng() * 30 - 15)), 60, 600),
      p99: clamp(Math.round(state.metrics.p99 + (rng() * 50 - 25)), 100, 1200),
      retryCount: state.metrics.retryCount + extraRetries,
    },
  };
  const alerts = alertsFromZones(merged);
  if (alertSignature(alerts) !== alertSignature(state.activeAlerts)) delta.activeAlerts = alerts;
  return delta;
}
