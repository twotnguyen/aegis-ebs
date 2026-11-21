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

## Views (tabs, keys 1–5)

The 3D twin keeps rendering behind every panel.

| Tab | What it does |
| --- | --- |
| **Twin** | Classic HUD-only view (metrics, active alerts, legend). Click an alert to jump to its funnel. |
| **Incidents** | Incident timeline fed by `INCIDENT_EVENT` frames (mock-first: scripted FIRE_DRILL demo + events derived from every delta). Replay with play/pause/step/scrub at 1×/2×/4× — the twin is repainted from event payload counters and the touched zone is highlighted (badge `REPLAY`). Export events as CSV/JSON. |
| **Chaos** | C01 Drop ACK · C02 Delay ACK (ms slider) · C04 Disconnect (% slider). Commands are **mock-only** (status/1 has no chaos opcode): they rewrite mock deltas, log as `CHAOS_COMMAND` incident events with a mock command-ack, and pin the feed to MOCK; “Return to live feed” resumes probing the server. |
| **Reports** | Rolling window stats (current/min/avg/max) for p50/p95/p99 + connected/retryCount, sparklines, CSV/JSON export of all samples. |
| **Funnel** | Per-alert delivery funnel `targeted → received → acknowledged (+failed)` derived from zone counters since the alert opened, with pending/unacked/ack-rate/fail-rate and CSV/JSON export. |

Run the unit tests (pure logic, no DOM): `npm test`.

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
index.html            HUD shell + canvas + tab bar + panel views
src/main.js           Three.js scene (10 floors × 4 zones), orbit camera, HUD, tooltip, wiring
src/data.js           CONTRACT definition, severity rules, applyDelta(), mock data
src/ws.js             WebSocket client: backoff reconnect + mock fallback + INCIDENT_EVENT routing
src/events.js         INCIDENT_EVENT contract, event store, delta→events, scripted demo incident
src/replay.js         incident replay engine (play/pause/step/seek/speed, twin repaint)
src/chaos.js          chaos engine: DROP_ACK / DELAY_ACK / DISCONNECT mock commands
src/funnel.js         per-alert delivery funnel (targeted/received/acknowledged/failed)
src/reports.js        metrics history, window stats, CSV/JSON export, sparklines
src/panels.js         tabbed UI: Incidents · Chaos · Reports · Funnel
src/styles.css        dark HUD theme, responsive
test/logic.test.js    node --test unit tests for the pure logic (npm test)
docs/status-contract.md  full status/1 JSON contract + INCIDENT_EVENT + chaos/report/funnel notes
```

Java modules are untouched; this package is self-contained.
