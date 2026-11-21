// ---------------------------------------------------------------------------
// Aegis EBS — per-alert delivery funnel (targeted/received/acknowledged/failed).
//
// Derived from EXISTING status/1 data (no new wire fields): when an alert
// first appears we snapshot its zone counters as a baseline; the funnel shows
// the increments since the alert opened:
//   targeted     = zone.devicesTotal (provisioned devices in the alert zone —
//                  the alert_targets snapshot size in EBSP terms)
//   received     = Δzone.received     (clamped to targeted)
//   acknowledged = Δzone.acknowledged (clamped to received; monotonic per EBSP)
//   failed       = Δzone.failed       (clamped to received − acknowledged)
// When the server later exposes exact per-alert delivery counts, only this
// module needs to change. Closed alerts are kept as history (capped).
// ---------------------------------------------------------------------------

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const SEVERITY_RANK = { warning: 1, critical: 2 };

export function createFunnelTracker({ limit = 100 } = {}) {
  const records = new Map(); // alertId -> record

  function zoneOf(state, zoneCode) {
    for (const f of state?.floors ?? []) {
      const z = f.zones.find((x) => x.zoneCode === zoneCode);
      if (z) return z;
    }
    return null;
  }

  /** Observe a full state (snapshot or merged delta). */
  function update(state) {
    if (!state) return;
    const ts = state.ts;
    const active = new Set();
    for (const a of state.activeAlerts ?? []) {
      active.add(a.alertId);
      const z = zoneOf(state, a.zoneCode);
      let r = records.get(a.alertId);
      if (!r) {
        r = {
          alertId: a.alertId,
          zoneCode: a.zoneCode,
          floorNumber: a.floorNumber,
          openedTs: a.ts ?? ts,
          baseline: {
            received: z?.received ?? 0,
            acknowledged: z?.acknowledged ?? 0,
            failed: z?.failed ?? 0,
          },
          targeted: z?.devicesTotal ?? 0,
          closedTs: null,
        };
        records.set(a.alertId, r);
      }
      r.severity = a.severity;
      r.message = a.message;
      r.lastSeenTs = ts;
      r.active = true;
      r.closedTs = null;
      if (z) r.targeted = Math.max(r.targeted, z.devicesTotal);
    }
    for (const r of records.values()) {
      if (r.active && !active.has(r.alertId)) {
        r.active = false;
        r.closedTs = ts;
      }
    }
    if (records.size > limit) {
      const closed = [...records.values()]
        .filter((r) => !r.active)
        .sort((a, b) => String(a.closedTs).localeCompare(String(b.closedTs)));
      while (records.size > limit && closed.length) records.delete(closed.shift().alertId);
    }
  }

  /** Compute funnel stages for one alert against the current state. */
  function funnelFor(alertId, state) {
    const r = records.get(alertId);
    if (!r) return null;
    const z = zoneOf(state, r.zoneCode);
    const targeted = Math.max(r.targeted, z?.devicesTotal ?? 0, 1);
    const received = clamp((z?.received ?? r.baseline.received) - r.baseline.received, 0, targeted);
    const acknowledged = clamp(
      (z?.acknowledged ?? r.baseline.acknowledged) - r.baseline.acknowledged,
      0,
      received,
    );
    const failed = clamp((z?.failed ?? r.baseline.failed) - r.baseline.failed, 0, received - acknowledged);
    return {
      alertId: r.alertId,
      zoneCode: r.zoneCode,
      floorNumber: r.floorNumber,
      severity: r.severity ?? 'warning',
      message: r.message ?? '',
      active: !!r.active,
      openedTs: r.openedTs,
      closedTs: r.closedTs,
      targeted,
      received,
      acknowledged,
      failed,
      pending: targeted - received,
      unacked: received - acknowledged - failed,
      reachRate: received / targeted,
      ackRate: received > 0 ? acknowledged / received : 0,
      failRate: received > 0 ? failed / received : 0,
      // cumulative zone counters (current window) — reconcile with alert headline
      zoneTotals: z
        ? { received: z.received, acknowledged: z.acknowledged, failed: z.failed, online: z.online, devicesTotal: z.devicesTotal }
        : null,
    };
  }

  /** All funnels: active first (critical before warning), then closed history. */
  function all(state) {
    return [...records.values()]
      .map((r) => funnelFor(r.alertId, state))
      .filter(Boolean)
      .sort(
        (a, b) =>
          Number(b.active) - Number(a.active) ||
          (SEVERITY_RANK[b.severity] ?? 0) - (SEVERITY_RANK[a.severity] ?? 0) ||
          String(b.openedTs).localeCompare(String(a.openedTs)),
      );
  }

  return {
    update,
    funnelFor,
    all,
    get size() {
      return records.size;
    },
  };
}
