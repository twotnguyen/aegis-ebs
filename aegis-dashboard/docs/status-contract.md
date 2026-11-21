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
    - `received` — messages received from the zone (current window).
    - `acknowledged` — messages ACKed, `0..received`, **monotonic** (EBSP: ACK only advances state).
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
