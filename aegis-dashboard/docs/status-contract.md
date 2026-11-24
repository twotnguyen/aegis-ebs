# Aegis EBS — Status feed JSON contract (`status/1`)

Transport: **WebSocket**, text frames, strict JSON, endpoint **`ws://localhost:9444`**.
The Java server (`aegis-server`) bridges EBSP/2.0 device/alert state into this feed.
The dashboard (`src/data.js` → `CONTRACT`, `src/ws.js`) is the reference client.

> This contract is separate from the EBSP/2.0 TCP wire envelope
> (`{version:2, type, messageId, correlationId, sentAt, payload}`, see `../docs/PROTOCOL.md`).
> The WS bridge translates EBSP state into the flat shapes below.

## Envelope (every message)

| Field        | Type   | Notes                                        |
| ------------ | ------ | -------------------------------------------- |
| `version`    | number | Status-feed contract version, currently `1`  |
| `type`       | string | `"STATUS_SNAPSHOT"` or `"STATUS_DELTA"`      |
| `buildingId` | string | e.g. `"AEGIS-BLD-A"`                         |
| `ts`         | string | UTC ISO-8601, e.g. `2026-11-21T10:00:00Z`    |

## `STATUS_SNAPSHOT` — full state

Sent **immediately after a client connects** (and after any resync).

```json
{
  "version": 1,
  "type": "STATUS_SNAPSHOT",
  "buildingId": "AEGIS-BLD-A",
  "ts": "2026-11-21T10:00:00Z",
  "floors": [
    {
      "floorNumber": 3,
      "zones": [
        {
          "zoneCode": "F03-A",
          "devicesTotal": 12,
          "online": 10,
          "received": 30,
          "acknowledged": 27,
          "userAcknowledged": 21,
          "failed": 1,
          "severity": "warning"
        }
      ]
    }
  ],
  "activeAlerts": [
    {
      "alertId": "AL-F07-C",
      "severity": "critical",
      "floorNumber": 7,
      "zoneCode": "F07-C",
      "message": "9 delivery FAILED after 3 retries · 22 unacked",
      "ts": "2026-11-21T10:00:00Z"
    }
  ],
  "metrics": {
    "connected": 431,
    "p50": 42,
    "p95": 181,
    "p99": 356,
    "retryCount": 27
  }
}
```

### Fields

- `floors[]` — exactly floors 1..10 for building A.
  - `floorNumber` — number, 1-based.
  - `zones[]` — 4 zones per floor, `zoneCode` = `F{floor:02d}-{A|B|C|D}` (unique per building).
    - `devicesTotal` — provisioned devices in the zone.
    - `online` — devices currently connected, `0..devicesTotal`.
    - `received` — transport pushes written for the zone (current window).
    - `acknowledged` — **ACK_RECEIVED** count: the device durably stored the alert; `0..received`, **monotonic**. It is NOT a human acknowledgement.
    - `userAcknowledged` — **ACK_USER** count: a person pressed Acknowledge; `0..acknowledged`, monotonic. The funnel's "acknowledged" stage uses `acknowledged` (receipt); read this field for human confirmation.
    - `failed` — deliveries FAILED after max retries (EBSP demo: 3 retries, 3s/6s/12s + jitter).
    - `severity` — `"online" | "warning" | "critical" | "offline"` (rules below).
- `activeAlerts[]` — current non-expired alerts (EBSP tombstones must be honored: cancelled/expired alerts never appear).
  - `alertId` (stable), `severity` (`warning|critical`), `floorNumber`, `zoneCode`, `message`, `ts`.
- `metrics`
  - `connected` — device sockets currently connected.
  - `p50`, `p95`, `p99` — end-to-end delivery latency percentiles, **milliseconds**.
  - `retryCount` — transport retries since server start.

### Severity rules (normative)

```
offline  ⇔ online === 0
critical ⇔ failed > 0 && (failed / max(received,1) >= 0.2 || acknowledged / max(received,1) < 0.6)
warning  ⇔ online < devicesTotal || failed > 0 || acknowledged < received
online   ⇔ otherwise
```

Dashboard colors: **green** `#22c55e` = online/acked · **yellow** `#facc15` = warning ·
**red** `#ef4444` = critical (pulsing) · **gray** `#64748b` = offline.

## `STATUS_DELTA` — partial update

Same envelope; **only changed parts** are included.

```json
{
  "version": 1,
  "type": "STATUS_DELTA",
  "buildingId": "AEGIS-BLD-A",
  "ts": "2026-11-21T10:00:02Z",
  "floors": [
    {
      "floorNumber": 7,
      "zones": [
        { "zoneCode": "F07-C", "online": 8, "failed": 10, "severity": "critical" }
      ]
    }
  ],
  "metrics": { "connected": 429, "retryCount": 30 }
}
```

### Merge semantics (client behavior, `applyDelta` in `src/data.js`)

1. Match floors by `floorNumber`, zones by `zoneCode`; unknown floors/zones are ignored.
2. Zone scalar fields present in the delta overwrite; absent fields keep their previous value.
3. If `severity` is omitted, the client recomputes it from counters using the rules above.
   Servers SHOULD still send `severity` explicitly.
4. `activeAlerts`, when present, **replaces the whole list** (send `[]` to clear).
5. `metrics`, when present, merges field-wise.
6. Deltas with a different `buildingId` are ignored.
7. A delta received before any snapshot is dropped — the server must snapshot first.

## Client reconnect / mock behavior (`src/ws.js`)

- Exponential backoff: 1s → 2s → 4s → 8s → cap **15s**, plus 0–250ms jitter.
- After **3 consecutive failures** the client enters **MOCK mode** (local snapshot +
  deltas every 2s, badge `MOCK FEED`) so the UI works before the server exists.
- The client keeps probing the real endpoint while mocking; on a successful live
  connection the mock feed stops and live data takes over (badge `LIVE`).
- Endpoint override for testing: `npm run dev` then open `http://localhost:5173/?ws=ws://host:port`.

## `INCIDENT_EVENT` — incident timeline feed

`INCIDENT_EVENT` is an EBSP/2.0 `MessageType` (see `aegis-protocol`); the WS bridge
may push incident timeline events on the **same socket** using the standard
envelope plus fields mirroring the `incident_events` table
(`incident_id, occurred_at, event_kind, actor_id, correlation_id, payload_json`).
The dashboard reference client lives in `src/events.js` (`EVENT_CONTRACT`);
the timeline/replay UI is the **Incidents** tab.

```json
{
  "version": 1,
  "type": "INCIDENT_EVENT",
  "buildingId": "AEGIS-BLD-A",
  "ts": "2026-11-21T10:00:00Z",
  "incidentId": "INC-AL-F07-C",
  "eventKind": "DELIVERY_FAILED",
  "actorId": "system",
  "correlationId": "req-123",
  "payload": {
    "zoneCode": "F07-C",
    "floorNumber": 7,
    "count": 2,
    "counters": { "online": 9, "received": 40, "acknowledged": 18, "failed": 11 }
  }
}
```

### Fields

- `incidentId` — `incidents.id`. Mock convention: `INC-<alertId>` (one incident
  per alert), `INC-DEMO-xxx` (scripted demo), `INC-CHAOS` (chaos commands).
- `eventKind` — one of: `INCIDENT_OPENED`, `INCIDENT_TRANSITION`,
  `ALERT_PUBLISHED`, `ALERT_UPDATED`, `ALERT_CANCELLED`, `DELIVERY_SENT`,
  `ACK_RECEIVED`, `ACK_USER`, `RETRY_SCHEDULED`, `DELIVERY_FAILED`,
  `DEVICE_OFFLINE`, `DEVICE_ONLINE`, `CHAOS_COMMAND`.
- `actorId` — user id, `device:<code>`, `operator`, or `system`; nullable.
- `correlationId` — requestId / command id linking related events; nullable.
- `payload` — kind-specific JSON. **Replay convention:** events carrying
  `{zoneCode, counters:{online,received,acknowledged,failed}}` let the dashboard
  repaint the 3D twin deterministically. `INCIDENT_TRANSITION` carries
  `state ∈ {DRAFT, OPEN, INVESTIGATING, CONTAINED, RESOLVED, CLOSED, ABORTED}`
  (the `incidents.state` CHECK constraint). Counters must stay monotonic where
  EBSP requires it (`acknowledged`, `failed`, `received` never decrease).
- `seq` is **client-assigned** arrival order (not on the wire).

### Client behavior (mock-first)

- Live `INCIDENT_EVENT` frames are appended to the timeline as-is (`src/ws.js`
  routes them to `onEvent`).
- In MOCK mode the timeline is seeded with a deterministic scripted incident
  (`makeMockIncident`, FIRE_DRILL floor 5 per `docs/DEMO-RUNBOOK.md`), and every
  `STATUS_DELTA` additionally derives events from counter/alert changes
  (`eventsFromDelta`): failed↑ ⇒ `RETRY_SCHEDULED` (3×) + `DELIVERY_FAILED`,
  acknowledged↑ ⇒ `ACK_RECEIVED`, received↑ ⇒ `DELIVERY_SENT`, online↓/↑ ⇒
  `DEVICE_OFFLINE`/`DEVICE_ONLINE`, alert added ⇒ `ALERT_PUBLISHED`, alert
  removed ⇒ `INCIDENT_TRANSITION(RESOLVED)` + `ALERT_CANCELLED` (tombstone).
- **Replay** (`src/replay.js`) re-applies event payload counters onto a base
  snapshot: play/pause, step, scrub, 1×/2×/4× speed; the twin is repainted from
  the replay state (badge `REPLAY`) and the touched zone is highlighted.
  Stopping replay restores the live state.

## Chaos commands (mock-only)

`status/1` defines **no chaos opcode**. The Chaos tab executes commands against
the local mock simulation and logs them as `INCIDENT_EVENT` frames with
`eventKind: "CHAOS_COMMAND"`, `incidentId: "INC-CHAOS"`, `actorId: "operator"`,
`correlationId: <command id>`; the mock "server" acknowledges each command
(`status: sent → acknowledged`, result `OK (mock)`). Scenarios mirror
`docs/TEST-PLAN.md`:

| Command      | Scenario | Effect on mock deltas                                        |
| ------------ | -------- | ------------------------------------------------------------ |
| `DROP_ACK`   | C01      | `acknowledged` frozen; ≥3 unacked for 2+ ticks ⇒ `failed`+1, `retryCount`+3 |
| `DELAY_ACK`  | C02      | p50 +0.4·ms, p95 +ms, p99 +1.6·ms (ms slider 100–2000)        |
| `DISCONNECT` | C04      | `online` reduced by pct% (5–50) on touched zones; severity recomputed |

Enabling a scenario pins the feed to MOCK (`feed.forceMock()`); “Return to live
feed” (`releaseMock()`) resumes probing `ws://localhost:9444`. A future server
 MAY accept the same command shapes over the socket; until then they never
leave the browser.

## Reports & delivery funnel (derived, no new wire fields)

- **Reports** tab: every snapshot/delta is sampled into a rolling window
  (≤900 samples); table of current/min/avg/max for `connected`, `p50/p95/p99`,
  `retryCount` + sparklines; CSV/JSON export of the raw samples and stats.
- **Funnel** tab: per-alert `targeted/received/acknowledged/failed` derived from
  zone counters since the alert opened (`src/funnel.js`): `targeted` =
  `devicesTotal` of the alert zone (alert_targets snapshot size in EBSP terms);
  the other stages are counter increments clamped to a valid funnel
  (`received ≤ targeted`, `acknowledged + failed ≤ received`). Closed alerts
  are kept as history. The detail line also shows the cumulative zone totals
  (`zoneTotals`) so the funnel increments reconcile with the alert headline.
  CSV/JSON export included. When the server exposes exact
  per-alert delivery counts, only `src/funnel.js` needs to change.

