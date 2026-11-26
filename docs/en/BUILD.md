Language: English | Vietnamese original: docs/BUILD.md

# BUILD — Reproducible build guide (fresh install)

AEGIS-EBS · CMP180 capstone · **SIMULATION/TRAINING only**

This document describes how to rebuild the entire system from a clean machine: the Maven reactor with 7 modules (Java 24) + the `aegis-dashboard` dashboard (Node/Vite/Three.js) + 26 SQLite tables.
CI runs exactly these commands on `ubuntu-latest` — see [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml).

**NFR-015: fresh install < 30 minutes.** Reference budget (clean machine, normal network):

| Step | Estimated time |
|---|---|
| Install JDK 24+ + Maven + Node 26 + sqlite3 | 5–10 minutes |
| First `mvn -B -ntp verify` (dependency download + 361 tests across the reactor) | 5–10 minutes |
| `npm ci && npm run build && npm test` (dashboard, 22 tests) | 1–2 minutes |
| `scripts/initdb.sh` (26 tables) + `scripts/gen-server-cert.sh` | < 5 seconds |
| Server smoke test (demo world seeded with 480 devices) | < 1 minute |

CI sets `timeout-minutes: 30` to match NFR-015; subsequent builds are faster thanks to the Maven `~/.m2` and npm `~/.npm` caches (GitHub Actions cache keyed on `pom.xml` / `package-lock.json`).

## 1. Environment requirements

| Component | Version | Notes |
|---|---|---|
| JDK | **24+** (Temurin recommended, CI uses Temurin 24) | `maven.compiler.release=24` — newer JDKs (e.g. 27) still build |
| Maven | 3.9+ | `brew install maven` (reference machine: 3.10.1) |
| Node.js | **26** (CI uses Node 26) | dashboard: Vite + Three.js; `package-lock.json` required for `npm ci` |
| sqlite3 CLI | any | for `scripts/initdb.sh` (preinstalled on macOS; Linux: `apt-get install sqlite3`) |
| git | any | clone the repo |

### macOS (Homebrew)

```bash
brew install maven            # pulls in OpenJDK (currently JDK 27, release=24 still valid)
brew install --cask temurin@24  # optional: exactly JDK 24 as in CI
brew install node             # currently Node 26.x; alternatively: nvm install 26
# sqlite3 is preinstalled on macOS
```

### Linux (Ubuntu/Debian — CI environment)

```bash
sudo apt-get update && sudo apt-get install -y maven sqlite3 git
# JDK 24 Temurin: https://adoptium.net/ (CI uses actions/setup-java)
# Node 26: https://nodejs.org/ or nvm install 26 (CI uses actions/setup-node)
```

## 2. Fresh-install checklist (< 30 minutes)

Work through the steps in order; each has a **verification gate** — proceed only when the gate passes.

- [ ] **Step 0 — Toolchain** (~5–10 minutes if installation is needed):
  ```bash
  java -version     # ≥ 24 (reference machine: 24.0.1 Temurin; Maven may run on JDK 27)
  mvn -version      # ≥ 3.9
  node --version    # v26.x
  sqlite3 --version
  ```
- [ ] **Step 1 — Clone:** `git clone <repo-url> aegis-ebs && cd aegis-ebs`
- [ ] **Step 2 — Reactor build + test** (~5–10 minutes the first time):
  ```bash
  mvn -B -ntp verify
  ```
  **Gate:** `BUILD SUCCESS`; **361 Java tests, 0 failures/errors, 1 skipped** (table in §3).
  All tests run headless — no display/X server needed.
- [ ] **Step 3 — Dashboard** (~1–2 minutes):
  ```bash
  cd aegis-dashboard && npm ci && npm run build && npm test && cd ..
  ```
  **Gate:** `dist/` contains index.html + assets (the Three.js > 500 kB chunk warning is benign);
  `npm test` reports **pass 22 / fail 0** (16 logic + 6 i18n). (CI only runs `npm ci && npm run build`; `npm test` runs locally.)
- [ ] **Step 4 — Demo DB** (< 1 second):
  ```bash
  ./scripts/initdb.sh        # creates aegis.db at the repo root (gitignored)
  ```
  **Gate:** prints `TABLES=26`; `PRAGMA foreign_key_check` produces no output = clean.
  **Note:** the script `rm -f`s the target DB file before creating it — do not point it at a real DB.
  A custom path can be specified: `./scripts/initdb.sh /duong/dan/aegis.db`.
- [ ] **Step 5 — Demo TLS certificates** (< 5 seconds, LAB ONLY):
  ```bash
  ./scripts/gen-server-cert.sh   # certs/aegis-server.p12 + aegis-truststore.p12, password aegis-demo
  ```
  **Gate:** `certs/aegis-server.p12`, `certs/aegis-server.crt`, `certs/aegis-truststore.p12` exist.
- [ ] **Step 6 — Server smoke test** (< 1 minute):
  ```bash
  mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
  java -cp "aegis-server/target/classes:aegis-core/target/classes:aegis-persistence/target/classes:aegis-protocol/target/classes:$(cat aegis-server/cp.txt)" \
    aegis.server.ServerMain --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
  ```
  **Gate:** prints `AEGIS EBS server ready` + `EBSP/2.0 TLS : localhost:9443` + `status feed : ws://localhost:9444`;
  the demo world is seeded automatically (quick check in another terminal):
  ```bash
  sqlite3 aegis.db "SELECT count(*) FROM devices;"   # 480
  ```
  Ctrl-C to stop. **Why the long classpath:** `cp.txt` is in `aegis-server/` (the module dir),
  and the `aegis-*` jars in `~/.m2` may be **older than the working tree** — you must include `target/classes`
  of the sibling modules, otherwise a connecting client will hit `NoClassDefFoundError` (e.g. `aegis/core/Grants`).
  Alternative: run `mvn -q -DskipTests install` once to refresh `~/.m2` and then omit the class directories.
- [ ] **Step 7 — Optional smoke tests** (demo path):
  - Dashboard: `cd aegis-dashboard && npm run dev` → http://localhost:5173 (MOCK FEED if no server is running).
  - JavaFX client (needs a display): command in README §5 — device `DEV-A-F05-A-01`, secret `device-demo-secret`.
  - Simulator with 100–200 agents: **you must register the fleet first** — see §2.2 below.

Shortcut commands (equivalent to the README): `./scripts/dev.sh` = `mvn -q -DskipTests package`;
`./scripts/test.sh` = `mvn -q test`.

### 2.1. Demo world & two seed modes (READ BEFORE LOADING DATA)

Source of truth: `aegis-server/src/main/java/aegis/server/ServerStore.java` → `seedWorld`,
which runs automatically when `ServerMain` starts (unless `--no-seed`). Checked directly against
a running server (2026-11-21):

| Entity | Value |
|---|---|
| Building | id `AEGIS-BLD-A` (default `ServerConfig.buildingId`), code `A`, name `AEGIS Tower A` |
| Topology | 10 floors x 4 zones (`F01-A`…`F10-D`) x **12 devices/zone** (`devicesPerZone=12`) = **480 devices** |
| Device codes | `DEV-A-F{nn}-{A..D}-{01..12}` (e.g. `DEV-A-F05-A-01`) |
| Users | `admin`/`admin-demo-secret` (SYSTEM_ADMIN) · `secops`/`secops-demo-secret` (SECURITY_OPERATOR) · `floor7`/`floor7-demo-secret` (FLOOR_MANAGER) · `employee`/`employee-demo-secret` (EMPLOYEE) |
| Grants | `secops` → building-wide `PUBLISH`; `floor7` → floor 7 `PUBLISH`. SYSTEM_ADMIN publishes everywhere via ScopedRBAC (no grant row needed) |
| Device credential | `device-demo-secret` — **one shared Argon2id hash** for the whole fleet (lab only), parameters `m=16384,t=2,p=1` |

`seed_demo.sql` (in aegis-persistence) has been **synchronized with the world above** (same building id/code,
480 device codes, 4 users, 2 grants; every row checked against the real server, `foreign_key_check` clean).
Intentional difference: the hashes in the file are **PLACEHOLDER**, and the file adds showcase content
(display_x/y for zones, alert_templates FIRE/DRILL vi/en, demo_scenarios FIRE_DRILL).

**Two modes — do NOT mix them in the same DB:**

- **Mode 1 — real demo (recommended):** `initdb.sh` → run `ServerMain`. The server seeds itself
  with real Argon2id hashes; logins/device auth work immediately with the table above.
  **Do NOT load `seed_demo.sql` into this DB.**
- **Mode 2 — offline dataset:** `./scripts/initdb.sh demo.db` then
  `sqlite3 demo.db < aegis-persistence/src/main/resources/db/seed_demo.sql`
  (the file enables `PRAGMA foreign_keys=ON` for the sqlite3 CLI; check with: `sqlite3 demo.db 'PRAGMA foreign_key_check;'` must be clean).
  Use it for viewing ERD/SQL/dashboard mock. **Auth will fail until placeholders are replaced with real hashes** (recipe in §2.2).

> ⚠️ **Mode-mixing trap:** `seedWorld` is idempotent — it **SKIPS** seeding if building `AEGIS-BLD-A`
> already exists. Loading `seed_demo.sql` first and then starting the server ⇒ the server does not seed again ⇒
> placeholders block every login. Verified: the server still starts cleanly on a seeded DB (no UNIQUE conflict),
> but authentication needs real hashes.

### 2.2. Simulator fleet — device registration (verified recipe)

The simulator assigns device codes `<prefix>-0001…<prefix>-NNNN` (`--device-prefix`, default `SIM-DEV`).
The server does **not auto-enroll**: `AuthService.authenticateDevice` accepts only devices already in the
`devices` table with a matching credential hash. The `SIM-DEV-*` fleet is not in the server seed ⇒
running immediately yields `auth rejections (permanent)` (actually observed: 2/2 agents rejected).

Register the fleet with the shared credential `device-demo-secret` (same secret as the demo fleet):

**1) Generate an Argon2id hash with jshell** (uses the server's own `Argon2idHasher` — no code changes needed):

```bash
CP="aegis-server/target/classes:aegis-core/target/classes:aegis-persistence/target/classes:aegis-protocol/target/classes:$(cat aegis-server/cp.txt)"
jshell --class-path "$CP" - <<'EOF'
var h = new aegis.server.Argon2idHasher(2, 16384, 1);
String hash = h.hash("device-demo-secret");
System.out.println("HASH=" + hash);
System.out.println("VERIFY=" + h.verify("device-demo-secret", hash));
/exit
EOF
# → HASH=$argon2id$v=19$m=16384,t=2,p=1$...  VERIFY=true
```

**2) Load the fleet into the running server's DB** (replace `HASH_REAL` with the output above; example for 150 agents,
all assigned to zone F01-A so a floor-1 publish reaches the fleet):

```bash
sqlite3 aegis.db <<EOF
PRAGMA foreign_keys=ON;
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 150)
INSERT INTO devices(id, floor_id, device_code, credential_hash, enabled, enrolled_at)
SELECT 'sim-dev-' || printf('%04d', i),
       (SELECT id FROM floors WHERE floor_number = 1),
       'SIM-DEV-' || printf('%04d', i),
       'HASH_REAL', 1, '2026-01-01T00:00:00.000000000Z'
FROM n;
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 150)
INSERT INTO zone_devices(zone_id, device_id, assigned_at)
SELECT (SELECT z.id FROM zones z JOIN floors f ON f.id = z.floor_id
        WHERE f.floor_number = 1 AND z.zone_code = 'F01-A'),
       'sim-dev-' || printf('%04d', i), '2026-01-01T00:00:00.000000000Z'
FROM n;
EOF
sqlite3 aegis.db "PRAGMA foreign_key_check;"   # must be clean
```

**3) Run the simulator** (full command in README §5; note `../certs/…` because `exec:exec` forks
with working dir = `aegis-simulator/`):

```bash
mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:exec \
  -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain \
  --agents 150 --scenario clean --seed 42 --host localhost --port 9443 \
  --device-prefix SIM-DEV --device-secret device-demo-secret \
  --truststore ../certs/aegis-truststore.p12 --truststore-password aegis-demo --duration 30"
```

**Verification gate:** summary prints `auth rejections (permanent): 0` and `connects ok` > 0.
Actually verified (2026-11-21, reference machine): before registration 2/2 agents were rejected;
after registration `connects ok/failed = 2/0`, `heartbeats answered = 2`, exit 0; the 150-device fleet
was loaded with exactly the SQL above and `foreign_key_check` was clean. Real capture: runId `sim150-1795689017` — 150/150 agents started and finished, 0 permanent auth rejections, 450 heartbeats, seed 42; see `docs/evidence/simulator/sim150-1795689017/summary.json`. 88 pre-TLS-handshake failures were retried successfully; no alert was published in this capture.

To reset the fleet: `./scripts/initdb.sh` creates a new DB (the server will re-seed the original world).

## 3. Run tests per module

From the repository root. Use `-am` (also-make) so Maven automatically builds upstream modules it depends on;
or run `mvn -DskipTests install` once and then omit `-am`. **Do not run `-pl aegis-server` without `-am`
when the `~/.m2` jars are old** — results will silently diverge from the working tree.

| Module | Command | Tests (surefire XML, 2026-11-21) |
|---|---|---|
| aegis-protocol | `mvn -B -ntp -pl aegis-protocol test` | 57 (FrameCodec fixture/stream/validation + timestamp) |
| aegis-core | `mvn -B -ntp -pl aegis-core -am test` | 73 (AckLedger, RetryPolicy, DedupIndex, RBAC, TargetResolver, PriorityScheduler) |
| aegis-persistence | `mvn -B -ntp -pl aegis-persistence -am test` | 52 (Db pragmas/WAL, MigrationRunner 26 tables, TransactionalPublisher, RecoveryScanner, repos, peer-review fixes) |
| aegis-server | `mvn -B -ntp -pl aegis-server -am test` | 73 (unit + FanoutEngine + TlsLoopbackIntegration + ServerSecurityFixes) |
| aegis-client | `mvn -B -ntp -pl aegis-client -am test` | 45 (AlertRecord, ClientConfig, InboxStore, AlertClientEngine, TLS smoke) |
| aegis-simulator | `mvn -B -ntp -pl aegis-simulator -am test` | 20 (SimulatorConfig, deterministic FaultInjector, SimAgent, stats) |
| aegis-network-lab | `mvn -B -ntp -pl aegis-network-lab -am test` | 31 (UDP discovery trust gate, multicast hint, benchmark) |
| **Whole reactor** | `mvn -B -ntp verify` (or `./scripts/test.sh`) | **361** (1 skipped; archived evidence: 347) |
| aegis-dashboard | `cd aegis-dashboard && npm test` | 22 (`node --test test/logic.test.js test/i18n.test.js`) |

Exact test count: `-q` hides the figures — read the XML in `<module>/target/surefire-reports/`:

```bash
grep -h "tests=" aegis-persistence/target/surefire-reports/*.xml | head
```

Try the dashboard: `npm run dev` (Vite dev server, port 5173) or `npm run preview` after build;
WS contract: [`aegis-dashboard/docs/status-contract.md`](../../aegis-dashboard/docs/status-contract.md).

## 4. JavaFX and headless environments (CI)

`aegis-client` depends on JavaFX 24 (`javafx-controls`, `javafx-graphics`) to run the GUI
(`mvn -pl aegis-client javafx:run` — needs a real display; the plugin forks with working dir =
`aegis-client/`, so the truststore path in the run command is `../certs/…`).
**All 45 current aegis-client tests do not start the JavaFX toolkit**
(only logic/config/store/engine/TLS tests), so `mvn verify` runs fine on headless
`ubuntu-latest` — verified, no X server needed.

If JavaFX UI tests are added later, choose one of two approaches:

1. **Monocle headless** (recommended for CI): add the test dependency
   `org.testfx:openjfx-monocle` and run surefire with
   `-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw -Djava.awt.headless=true`.
2. **Xvfb**: wrap the Maven step with `xvfb-run -a mvn -B -ntp verify`
   (install `xvfb` + JavaFX's GTK/GL libraries on the runner).

## 5. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `invalid target release: 24` | JDK < 24 — install Temurin 24+, check `mvn -version` (Maven may run on a different JDK from `java -version`) |
| `NoClassDefFoundError: aegis/core/…` when a client connects | `aegis-*` jars in `~/.m2` older than the working tree — add sibling modules' `target/classes` to the classpath (Step 6) or run `mvn -DskipTests install` |
| `cat: cp.txt: No such file or directory` | `dependency:build-classpath -pl aegis-server` writes `aegis-server/cp.txt` (module dir), not the repo root |
| `NoSuchFileException: certs/aegis-truststore.p12` (simulator/client) | `exec:exec`/`javafx:run` forks with working dir = module dir — use `../certs/…` or an absolute path |
| `BindException: Address already in use` (9443/9444) | An old server is still alive (including orphans) — `lsof -nP -iTCP:9443 -sTCP:LISTEN`, kill the right PID and rerun; if the port is occupied the client will talk to the OLD server/DB |
| Simulator `auth rejections (permanent)` | Fleet `<prefix>-NNNN` not registered in `devices` or secret does not match the hash — follow the recipe in §2.2 |
| Demo login fails on a DB loaded with `seed_demo.sql` | Placeholder hashes + seedWorld skipped because building exists (§2.1) — use mode 1, or replace the hashes with PasswordHasher |
| `npm ci` EUSAGE/lockfile error | `package-lock.json` out of sync with `package.json` — run `npm install` to sync and commit the lockfile |
| sqlite-jdbc `native access` warning | Benign (newer JDKs warn about JNI); does not affect tests |
| > 500 kB chunk warning from vite build | Benign — large Three.js bundle; code-splitting can come later |
| Files `aegis.db-wal`, `aegis.db-shm` | SQLite WAL mode — already gitignored; do not commit |
| Persistence tests fail when run in parallel | Each test uses its own `@TempDir` DB; do not point them all at one DB file |

## 6. Related documentation

- [`docs/PROTOCOL.md`](PROTOCOL.md) — EBSP/2.0 wire contract (immutable).
- [`docs/DEMO-RUNBOOK.md`](DEMO-RUNBOOK.md) — 12-minute demo script, running server/client/simulator.
- [`docs/TEST-PLAN.md`](TEST-PLAN.md) — 30 acceptance tests + 16 chaos scenarios.
- [`docs/SLIDES.md`](SLIDES.md) — defense outline; `[TODO]` cells point to `docs/evidence/`.
- [`scripts/gen-server-cert.sh`](../../scripts/gen-server-cert.sh) — TLS certificates for the demo server.
