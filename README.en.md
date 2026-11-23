Language: English | Vietnamese original: README.md

# AEGIS-EBS — Advanced Emergency Governance & Intelligent Signaling

**Emergency Broadcast System for office buildings** — capstone project for **CMP180 · Computer Network Programming** (HUTECH).

> ⚠️ **SIMULATION / TRAINING ONLY.** This is an academic prototype; it does **not** replace certified fire alarm systems, evacuation public-address systems, or life-safety warning devices. Do not send fake alerts to people who are not participating in the demo.

A command center broadcasts emergency alerts to hundreds of clients on a LAN, **with priorities, two-layer acknowledgements, idempotent retries, crash recovery, scoped RBAC, and an audit trail**, together with a **3D digital twin**, a **network fault lab (chaos lab)** and a **real multicast experiment** — all built with **self-implemented Java Socket protocols**, with no broker or cloud push as the primary channel.

---

## 1. Features

**Reliability core (P0):**
- Broadcast alerts by building / floor / zone (immutable target snapshot).
- 4 priority levels `CRITICAL > EMERGENCY > WARNING > INFO`, with anti-starvation fairness scheduling.
- **Separated two-layer ACK:** `ACK_RECEIVED` (client persisted durably) ≠ `ACK_USER` (a human pressed acknowledge).
- Idempotent retries (3s/6s/12s + jitter, at most 3 attempts) with a stable `deliveryId` and a new `messageId` per attempt; duplicate suppression + cancel/expire tombstones.
- Reconnect + SYNC (latest revision only; stale messages are not resurrected); crash recovery from SQLite WAL.
- Scoped RBAC (building/floor) enforced on the server for every command; append-only audit; p50/p95/p99 metrics.

**Showcase (P1):**
- **3D digital twin** (Three.js): 10 floors x 4 zones change color with real status; incident timeline/replay; chaos panel; reports/export; delivery funnel.
- **Network Chaos Lab:** drop/delay/duplicate ACK, disconnect, server restart — reproducible measurements with a fixed seed.
- **Multicast lab (Lesson 8):** real multicast `239.10.10.10:9998` + UDP discovery (Lesson 7) + TCP fanout vs multicast comparison (measured: multicast reduces ~87.5% of packets / ~97.8% of bytes on the source link).

## 2. Architecture

Java modular monolith with 4 planes: **command** (dashboard/operator) · **delivery** (TCP/TLS fanout + ACK/retry) · **persistence/observability** (SQLite WAL + audit + metrics) · **discovery/experiment** (UDP/multicast, untrusted).

| Module | Role | Tests |
|---|---|---:|
| `aegis-protocol` | EBSP/2.0: length-prefix framing + DTO + validation | 56 |
| `aegis-core` | AckLedger, RetryPolicy, DedupIndex, ScopedRBAC, TargetResolver, PriorityScheduler | 73 |
| `aegis-persistence` | SQLite WAL, 26-table migration, transactional publish (outbox), recovery | 52 |
| `aegis-server` | TLS gateway, auth/RBAC, incident/alert, fanout, ACK/retry, sync, recovery, audit/metrics, WS status | 63 |
| `aegis-client` | JavaFX desktop, durable inbox, two-layer ACK, reconnect, popup | 43 |
| `aegis-simulator` | Headless 100–200 agent TLS + deterministic fault injection | 20 |
| `aegis-network-lab` | UDP discovery + trust gate, multicast ALERT_HINT, TCP vs multicast benchmark | 31 |
| `aegis-dashboard` | Web Vite + Three.js 3D twin, timeline/chaos/reports/funnel, VI/EN i18n (Node) | 20 (14 logic + 6 i18n) |

**Current total: 347 Java test executions + 20 dashboard tests = 367** (338 Java + 14 dashboard was the historical baseline before H1–H4 fixes and i18n; counted from surefire XML + `npm test`, see `docs/BUILD.md`). Ports: TLS `9443` · status WS `9444` · Operator bridge `9445` · UDP discovery `9999` · multicast `239.10.10.10:9998` · Vite `5173`.

## 3. Technology

Java 24 (Maven 3.10) · `Socket`/`ServerSocket`/`SSLSocket`/`SSLServerSocket` · `DatagramSocket`/`MulticastSocket` · `ExecutorService`/virtual threads · SQLite WAL (`sqlite-jdbc`) · Jackson (JSON) · Argon2id (`argon2-jvm`) · JavaFX 24 · Three.js + Vite (Node 26) · JUnit 5.

## 4. Directory structure

```
aegis-ebs/
├── pom.xml                  # Maven reactor (7 Java modules)
├── aegis-protocol/  aegis-core/  aegis-persistence/  aegis-server/
├── aegis-client/    aegis-simulator/  aegis-network-lab/
├── aegis-dashboard/         # Node + Vite + Three.js (3D twin)
├── scripts/                 # dev.sh, test.sh, initdb.sh, gen-server-cert.sh, demo-web.sh
├── docs/                    # PROTOCOL, ARCHITECTURE, ERD, SECURITY, SLIDES, BUILD,
│   ├── BAO-CAO.md           #   report in the HUTECH MauBCMH template
│   ├── TEST-PLAN.md  DEMO-RUNBOOK.md
│   └── reviews/             #   independent peer-review reports per module
└── .github/workflows/ci.yml
```

## 5. Quickstart

### Web-only demo — recommended for the defense

One command starts the server + bridge + dashboard; everything else is done in the browser:

```bash
bash scripts/demo-web.sh
```

- Open `http://localhost:5173` → **Operator** tab (the sixth tab; HTTP bridge at `http://localhost:9445`).
- **Fleet Start (20)** → **Publish FIRE_DRILL F05** (red zone + ACKs on the LIVE dashboard) → **Retry demo** (1 silent device, `FAILED` after 3s/6s/12s) → **Cancel**.
- Press `Ctrl+C` to stop. The manual setup commands below are only needed for a real studio/chaos setup; see `docs/USER-GUIDE.md` section 0 for details.

### Three-role demo model

The demo is presented through three distinct roles (details: `docs/en/DEMO-3ROLES.md`):

| Role | Screen | Action in the demo |
|---|---|---|
| **3D twin** | Three.js dashboard (`http://localhost:5173`) | Viewing screen: zones change color by severity, timeline/replay, delivery funnel. Observe only; it issues no commands. |
| **Zone client** | JavaFX desktop (`DEV-A-F05-B-01`) | Receives `ALERT_PUSH` for zone `F05-B` only; stores a durable inbox then sends `ACK_RECEIVED`; a person presses **Acknowledge** to send `ACK_USER`. |
| **Admin console** | Admin tab on the dashboard | Monitors fleet/status; approves or edits `PUBLISH` grants in the `role_grants` table; reads the `audit_events` log through the extended bridge. |

Five-step flow: admin approves grant -> operator publishes `F05-B` -> JavaFX client popup + Acknowledge -> twin changes color -> admin reads audit. Tailscale works for TCP/TLS/dashboard over `100.x` addresses, **not** for Lesson 8 multicast (TTL=1, L3 overlay) - use a physical LAN or the unicast fallback.

### Prerequisites
```bash
brew install maven          # Java 24+ (Temurin), Maven 3.10
# Node 26 + npm for the dashboard (already installed or install via brew/node)
```

### Build + test + DB
```bash
./scripts/dev.sh            # mvn -DskipTests package
./scripts/test.sh           # mvn test  (338 Java tests)
./scripts/initdb.sh         # creates aegis.db: 26 tables, clean foreign_key_check
```

**Demo data — 2 modes, NEVER mix them in the same DB** (details: `docs/BUILD.md`):
- **Mode 1 — real demo (recommended):** only run `initdb.sh`, then start the server. `ServerStore.seedWorld` automatically loads the demo world with real Argon2id hashes: building `AEGIS-BLD-A` (code A), 10 floors x 4 zones x 12 devices = **480 devices** `DEV-A-F01-A-01`…`DEV-A-F10-D-12`. **Do NOT** load `seed_demo.sql` into this DB (seedWorld will skip because the building already exists, and the placeholder hashes in that file will block every login).
- **Mode 2 — offline dataset (for viewing ERD/SQL/dashboard mock):** use a separate DB file:
```bash
./scripts/initdb.sh demo.db
sqlite3 demo.db < aegis-persistence/src/main/resources/db/seed_demo.sql
sqlite3 demo.db 'PRAGMA foreign_key_check;'   # must be clean (no output)
```
`seed_demo.sql` is synchronized with the **actual server world** (same building/device codes/users/grants) but its hashes are intentionally PLACEHOLDER values — to log in on this DB you must replace them with PasswordHasher output (recipe in `docs/BUILD.md`).

### Demo TLS certificate (self-signed, LAB ONLY)
```bash
./scripts/gen-server-cert.sh   # generates certs/aegis-server.p12 + aegis-truststore.p12 (password: aegis-demo)
```

### Run the server
```bash
mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
# Note: cp.txt is in aegis-server/ (the module dir). The classpath must also include
# target/classes of the sibling modules in the working tree — aegis-* jars installed
# in ~/.m2 may be OLD (missing new classes → NoClassDefFoundError when a client connects).
java -cp "aegis-server/target/classes:aegis-core/target/classes:aegis-persistence/target/classes:aegis-protocol/target/classes:$(cat aegis-server/cp.txt)" \
  aegis.server.ServerMain --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
# TLS :9443 · status WS :9444
# optional: --tls-port --ws-port --building --devices-per-zone --no-seed --heartbeat-ms --retry-base-ms
```
Demo logins (mode 1 — seeded by the server, printed at startup): `admin/admin-demo-secret` (SYSTEM_ADMIN), `secops/secops-demo-secret` (building), `floor7/floor7-demo-secret` (floor 7), `employee/employee-demo-secret` (EMPLOYEE). Device credential: `device-demo-secret` (shared fleet hash, lab only); device code `DEV-A-F{nn}-{A..D}-{01..12}`.

### Run the JavaFX client
```bash
mvn -pl aegis-client javafx:run -Djavafx.args="--host localhost --port 9443 \
  --device-id DEV-A-F05-A-01 --device-secret device-demo-secret \
  --truststore ../certs/aegis-truststore.p12 \
  --truststore-password aegis-demo --inbox client-inbox.db --sound"
# javafx:run forks with working dir = aegis-client/ → the truststore uses ../certs/…
# --device-id must be a REAL device in the server seed (DEV-A-F{nn}-{A..D}-{01..12})
# and --device-secret must match; the default DEV-SIM-0001/simulation-secret is rejected.
```

### Run the simulator (100–200 agents + fault injection)
```bash
mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:exec \
  -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain \
  --agents 150 --scenario mixed --seed 42 --host localhost --port 9443 \
  --device-prefix SIM-DEV --device-secret device-demo-secret \
  --truststore ../certs/aegis-truststore.p12 --truststore-password aegis-demo --duration 30"
# scenarios: clean | lossy | delayed | duplicate | flaky | mixed
# exec:exec forks with working dir = aegis-simulator/ → the truststore uses ../certs/…
```
> ⚠️ **The fleet must be registered first.** The simulator assigns device codes `<prefix>-0001…<prefix>-NNNN`;
> the server only authenticates devices already present in the `devices` table (no auto-enroll). The `SIM-DEV-*`
> fleet is **NOT** in the server seed → running immediately produces `auth rejections (permanent)`.
> Register the fleet with the Argon2id hash of `device-demo-secret` — verified recipe
> (jshell + SQL, 2/2 successful consecutively) in `docs/BUILD.md`, section "Simulator fleet".

### Run the 3D dashboard
```bash
cd aegis-dashboard && npm install && npm run dev   # http://localhost:5173
# automatically connects to ws://localhost:9444; without a server it runs a MOCK FEED
```

### Multicast / UDP lab + benchmark
```bash
mvn -q -pl aegis-network-lab compile exec:java \
  -Dexec.mainClass=aegis.networklab.LabMain -Dexec.args="bench --subscribers 8 --messages 200"
# other commands: probe | serve | discover | publish | subscribe  (see aegis-network-lab/src/main/resources/lab-guide.md)
```

## 6. Testing & evidence

- `mvn verify` runs the whole reactor; exact figures come from `target/surefire-reports/*.xml`.
- Test plan: `docs/TEST-PLAN.md` (30 acceptance tests + 16 chaos scenarios C01–C16).
- Independent per-module peer reviews: `docs/reviews/` (verdict + issues + recommendations).
- Measured metrics (server monotonic p50/p95/p99, timeouts separated); multicast benchmark measured 2026-11-21 (8 subscribers x 200 messages, macOS loopback): **−87.5% packets / −97.8% bytes on the source link** — `target/lab-report/fanout-report.{json,md}`.
- Measurement evidence is stored in `docs/evidence/`: `verify/` (347 Java tests + 51 XML files), `benchmark/` (100 clients), `chaos/` (5 presets C01–C05 + runs), `dashboard/` (LIVE screenshots + `web-only-demo-live.png` and metadata for the 100% web-based demo flow), `video/aegis-ebs-demo.mp4` (12-minute presentation video, 720,05 s). Each set includes runId/commit SHA/measurement machine; `scripts/make-demo-video.py` regenerates the video from real evidence.
- **Web-only demo (`scripts/demo-web.sh`):** evidence `docs/evidence/dashboard/web-only-demo-live.{png}` + `web-only-demo-meta.json` — Fleet Start 20 → publish F05 (12 deliveries, ACKs) → Retry F01-A (11 RECEIVED + 1 FAILED with attempt_count=4) → Cancel, all actions performed in the Operator tab.
- **No invented figures** — every reported result comes from an actual run with runId/commit SHA/measurement machine.

## 7. Security (demo)

Self-signed TLS + pinned truststore (no trust-all); server-side scoped RBAC; Argon2id; 64 KiB frame cap; bounded writer queue of 256 + slow-client eviction; requestId idempotency; append-only audit; no secrets in logs. **Limitations:** self-signed certificate (not a public CA), public demo passwords, single-node (no HA). Details: `docs/SECURITY.md` (threat model + RBAC matrix + OWASP checklist).

## 8. Documentation

| File | Contents |
|---|---|
| `docs/PROTOCOL.md` | EBSP/2.0 wire contract (framing, envelope, message catalog, ACK/retry) |
| `docs/ARCHITECTURE.md` | 4-plane diagrams + deployment + concurrency (mermaid) |
| `docs/ERD.md` | ERD with 26 tables + 36 FK relationships |
| `docs/SECURITY.md` | Threat model, controls, RBAC matrix, truststore guide, OWASP |
| `docs/BAO-CAO.md` | Project report in the HUTECH MauBCMH template (4 chapters) |
| `docs/SLIDES.md` | 12-minute defense slide outline + Q&A |
| `docs/BUILD.md` | Reproducible build, fresh install < 30 minutes |
| `docs/DEMO-RUNBOOK.md` | 12-minute demo script |
| `docs/en/DEMO-3ROLES.md` | Three-role demo model (3D twin / JavaFX zone client / admin console) + 5-step flow + Tailscale |
| `Agents.md` | Multi-agent orchestration + peer-review process used to build the project |

English parallel documentation: [`README.en.md`](README.en.md) and [`docs/en/README.md`](docs/en/README.md); language switching is described in [`docs/I18N.md`](docs/I18N.md).

## 9. Academic context

CMP180 project — Computer Network Programming, HUTECH. Advisor: Nguyen Van Can (ThS.). Academic year 2025–2026. References: CMP180 textbook (Lessons 1–9), OASIS CAP 1.2, RFC 8085, OWASP, SQLite WAL, Three.js. See the References section of `docs/BAO-CAO.md`.
