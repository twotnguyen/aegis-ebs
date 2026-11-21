# aegis-dashboard

Web digital-twin dashboard for **AEGIS-EBS** (CMP180 capstone — simulation/training only).
Vite + Three.js: a 10-floor building rendered as stacked zone boxes, colored live by
device/delivery status, fed over WebSocket from `aegis-server` (EBSP/2.0 bridge).

## Run

Requires Node ≥ 20.19 (or ≥ 22.12); developed on Node 26.

```bash
npm install
npm run dev      # http://localhost:5173  (hot reload)
npm run build    # production bundle → dist/
npm run preview  # serve the production build locally
```

No Java server yet? The dashboard automatically falls back to **MOCK mode**
(badge `MOCK FEED`): a local snapshot + status deltas every 2s. When a server
appears at `ws://localhost:9444`, it switches to **LIVE** automatically.

Override the endpoint: `http://localhost:5173/?ws=ws://host:port`.

## Status colors

| Severity   | Color  | Meaning                                   |
| ---------- | ------ | ----------------------------------------- |
| `online`   | green  | all devices online, everything acked      |
| `warning`  | yellow | degraded: devices offline / unacked / failed |
| `critical` | red (pulses) | heavy failures or low ack rate      |
| `offline`  | gray   | no devices online in the zone             |

Controls: drag to orbit, scroll to zoom, hover a zone box for its counters.
HUD shows connection state, metrics (`connected`, `p50/p95/p99`, `retryCount`)
and active alerts.

## JSON contract (server must match)

Full spec: **[docs/status-contract.md](docs/status-contract.md)** — machine-readable
definition lives in `src/data.js` (`CONTRACT`). Summary:

- Transport: WebSocket text frames (JSON) at `ws://localhost:9444`.
- Every message: `{version: 1, type, buildingId, ts}`.
- **`STATUS_SNAPSHOT`** (full state, sent on connect):
  `{buildingId, floors: [{floorNumber, zones: [{zoneCode, devicesTotal, online, received, acknowledged, failed, severity}]}], activeAlerts: [{alertId, severity, floorNumber, zoneCode, message, ts}], metrics: {connected, p50, p95, p99, retryCount}}`
- **`STATUS_DELTA`** (partial update): same envelope; `floors` contains only changed
  floors/zones with only changed fields; `activeAlerts` replaces wholesale when
  present; `metrics` merges field-wise. Merge by `floorNumber` + `zoneCode`.
- `severity ∈ {online, warning, critical, offline}`; normative derivation rules in the doc.
- Latency metrics in **ms**; `acknowledged` is monotonic (EBSP: ACK only advances state).

## Layout

```
index.html            HUD shell + canvas
src/main.js           Three.js scene (10 floors × 4 zones), orbit camera, HUD, tooltip
src/data.js           CONTRACT definition, severity rules, applyDelta(), mock data
src/ws.js             WebSocket client: backoff reconnect + mock fallback
src/styles.css        dark HUD theme, responsive
docs/status-contract.md  full status/1 JSON contract
```

Java modules are untouched; this package is self-contained.
