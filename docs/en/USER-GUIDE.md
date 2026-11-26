Language: English | Vietnamese original: docs/USER-GUIDE.md

# OPERATION GUIDE — AEGIS-EBS

> SIMULATION/TRAINING ONLY. Run all commands from the repository root `aegis-ebs/`.

> **Recommended for the defense: web-only.** Use the one-command flow in section 0. Sections 1–9 remain for anyone who wants a real studio/chaos setup (JavaFX client, simulator, fault injection, benchmark).

## 0. Web-only demo — recommended for the defense

One command starts the server + bridge + dashboard; no JavaFX, simulator or manual TLS configuration is needed.

```bash
bash scripts/demo-web.sh
```

1. Open `http://localhost:5173` (dashboard) — the Operator tab's HTTP bridge runs at `http://localhost:9445`. Wait for the status feed to turn green (`LIVE`).
2. Select the **Operator** tab — the sixth tab on the navigation bar (Twin · Incidents · Chaos · Reports · Funnel · Operator).
3. Click **Fleet Start (20)** — 20 virtual devices start; the fleet list on the Operator tab turns ONLINE.
4. Click **Publish FIRE_DRILL for F05** — zone F05 turns red on the digital twin; ACKs (`ACK_RECEIVED` then `ACK_USER`) appear on the LIVE dashboard and counters increase.
5. Run **Retry demo** — this button automatically selects F01-A (12 real devices in the fleet, 1 silent device): the server retries after 3s, 6s, 12s (same `deliveryId`, new `messageId`); after 3 attempts that device becomes `FAILED`, while the other 11 still ACK. Do not use ALL/ALL because 460 devices outside the fleet would create many FAILED results that obscure the retry case.
6. Click **Cancel** — cancel the alert; the cancellation tombstone is synchronized and the zone returns to normal on the LIVE dashboard.

Stop the demo with `Ctrl+C` in the terminal running `scripts/demo-web.sh`.

## 1. Preparation
```bash
brew install maven            # Java 24 (Temurin) + Maven 3.10
# Node 26 + npm for the dashboard (already installed or: brew install node)
./scripts/dev.sh              # mvn -DskipTests package  → BUILD_OK
./scripts/test.sh             # mvn test → 361 Java tests
./scripts/initdb.sh           # creates aegis.db: 26 tables, clean foreign_key_check
sqlite3 aegis.db < aegis-persistence/src/main/resources/db/seed_demo.sql   # demo data
./scripts/gen-server-cert.sh  # certs/aegis-server.p12 + aegis-truststore.p12 (password: aegis-demo)
```

## 2. Run the server
```bash
mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "aegis-server/target/classes:$(cat cp.txt)" aegis.server.ServerMain \
  --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
```
Expected output:
```
AEGIS EBS server ready
  EBSP/2.0 TLS : localhost:9443
  status feed  : ws://localhost:9444/ (status/1)
  demo operators: admin/admin-demo-secret (SYSTEM_ADMIN),
    secops/secops-demo-secret (building), floor7/floor7-demo-secret (floor 7)
  demo devices: DEV-A-F01-A-01.. (credential device-demo-secret)
```

## 3. Run the JavaFX client (employee machine)
```bash
mvn -pl aegis-client javafx:run -Djavafx.args="--host localhost --port 9443 \
  --device-id DEV-A-F01-A-01 --truststore certs/aegis-truststore.p12 \
  --truststore-password aegis-demo --inbox client-inbox.db --sound"
```
The window shows the SIMULATION banner, status CONNECTING→ONLINE; on an alert → popup + beep; the **Acknowledge** button sends ACK_USER.

## 4. Run the simulator (headless fleet + fault injection)
```bash
mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:exec \
  -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain \
  --agents 100 --scenario mixed --seed 42 --host localhost --port 9443 \
  --truststore certs/aegis-truststore.p12 --truststore-password aegis-demo --duration 30"
```
Scenarios: `clean | lossy | delayed | duplicate | flaky | mixed`. Prints SUMMARY (agents, connects, alerts, ACK sent/dropped/delayed, disconnects, SYNC, heartbeats).

## 5. Open the 3D dashboard
```bash
cd aegis-dashboard && npm install && npm run dev   # http://localhost:5173
```
It connects automatically to `ws://localhost:9444`; without a server → MOCK FEED. Keys 1–6: Twin · Incidents · Chaos · Reports · Funnel · **Operator**. The Operator tab uses the HTTP bridge `http://localhost:9445` to publish/cancel real alerts and start/stop the device fleet (see section 0). The 3D building changes zone colors by severity; click a floor to fly-to/cutaway/explode.

## 6. Publish a FIRE_DRILL alert (operator)
For the web-only demo use the **Operator** tab on the dashboard (section 0); for a real studio/chaos setup use the operator client or the `target/e2e-dev/ebsp.py` harness (Operator.publish). Observable flow:
1. Operator `ALERT_CREATE`/`ALERT_PUBLISH` (idempotent requestId) → server checks RBAC + target snapshot.
2. Fanout `ALERT_PUSH` to target devices (CRITICAL priority first).
3. Client stores the durable inbox → `ACK_RECEIVED`; a person presses the button → `ACK_USER`.
4. Missing ACK → server retries 3s/6s/12s (at most 3) with the same deliveryId and a new messageId.
5. Client disconnect → reconnect `SYNC_REQUEST` → receives active alerts + cancellation tombstones.
6. Server restart → `RecoveryScanner` resumes interrupted deliveries from SQLite.
7. Floor manager publishes outside scope → FORBIDDEN + audit.

## 7. Chaos / benchmark / reproducible demo
```bash
./scripts/bench-load.sh     # 100-client benchmark → docs/evidence/benchmark/<runId>/
./scripts/chaos-run.sh      # chaos scenarios → docs/evidence/chaos/
./scripts/demo-e2e.sh       # end-to-end demo → docs/evidence/demo/
```
Every run includes a `summary.json` (runId, commit SHA, measurement machine, p50/p95/p99, ACK rate, retries).

## 8. Permissions
| Role | Can do |
|---|---|
| admin (SYSTEM_ADMIN) | all buildings, manage users/roles, publish/cancel |
| secops (SECURITY_OPERATOR) | publish/cancel in the granted building |
| floor7 (FLOOR_MANAGER) | publish/cancel only on floor 7 |
| employee | receive alerts + ACK_USER, cannot publish |

## 9. Troubleshooting
- **Port in use**: change `--port`/the port or kill the old process.
- **Bad certificate**: re-run `gen-server-cert.sh`; the client must use the matching truststore.
- **Multicast not working (macOS lo0/Tailscale)**: network-lab falls back to unicast automatically; run the multicast demo on a real 2-machine LAN.
- **JavaFX headless**: use the simulator + dashboard instead of the GUI client.

## 10. Three-role demo model

Three roles are shown together (details: `docs/en/DEMO-3ROLES.md`):

- **3D twin** - Three.js dashboard: the viewing screen, zones change color by severity; timeline/replay, chaos, reports, delivery funnel. It issues no commands.
- **Zone client** - JavaFX desktop (e.g. `DEV-A-F05-B-01`): receives alerts for its own zone, stores a durable inbox, sends `ACK_RECEIVED`; a person presses **Acknowledge** -> `ACK_USER`.
- **Admin console** - Admin tab on the dashboard: monitors fleet/status; approves or edits `PUBLISH` grants in the `role_grants` table; reads `audit_events` through the extended bridge.

Tailscale: works for TCP/TLS/dashboard over `100.x` addresses; **not** for Lesson 8 multicast (TTL=1, L3 overlay) - use a physical LAN or the unicast fallback (see section 9). For the five-step demo flow see `docs/en/DEMO-3ROLES.md`.
