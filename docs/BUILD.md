# BUILD — Hướng dẫn build tái lập (fresh install)

AEGIS-EBS · CMP180 capstone · **SIMULATION/TRAINING only**

Tài liệu này mô tả cách build lại toàn bộ hệ thống từ một máy sạch: Maven reactor
7 module (Java 24) + dashboard `aegis-dashboard` (Node/Vite/Three.js) + SQLite 26 bảng.
CI chạy đúng các lệnh này trên `ubuntu-latest` — xem [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

**NFR-015: fresh install < 30 phút.** Ngân sách tham khảo (máy sạch, mạng bình thường):

| Bước | Thời gian ước tính |
|---|---|
| Cài JDK 24+ + Maven + Node 26 + sqlite3 | 5–10 phút |
| `mvn -B -ntp verify` lần đầu (tải dependency + 338 test toàn reactor) | 5–10 phút |
| `npm ci && npm run build && npm test` (dashboard, 14 test) | 1–2 phút |
| `scripts/initdb.sh` (26 bảng) + `scripts/gen-server-cert.sh` | < 5 giây |
| Smoke test server (seed thế giới demo 480 device) | < 1 phút |

CI đặt `timeout-minutes: 30` để bám NFR-015; các lần build sau nhanh hơn nhờ cache
Maven `~/.m2` và npm `~/.npm` (GitHub Actions cache theo `pom.xml` / `package-lock.json`).

## 1. Yêu cầu môi trường

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| JDK | **24+** (Temurin khuyến nghị, CI dùng Temurin 24) | `maven.compiler.release=24` — JDK mới hơn (vd 27) vẫn build được |
| Maven | 3.9+ | `brew install maven` (máy tham chiếu: 3.10.1) |
| Node.js | **26** (CI dùng Node 26) | dashboard: Vite + Three.js; cần `package-lock.json` cho `npm ci` |
| sqlite3 CLI | bất kỳ | cho `scripts/initdb.sh` (macOS có sẵn; Linux: `apt-get install sqlite3`) |
| git | bất kỳ | clone repo |

### macOS (Homebrew)

```bash
brew install maven            # kéo theo OpenJDK (hiện là JDK 27, release=24 vẫn hợp lệ)
brew install --cask temurin@24  # tuỳ chọn: đúng JDK 24 như CI
brew install node             # hiện là Node 26.x; hoặc: nvm install 26
# sqlite3 có sẵn trên macOS
```

### Linux (Ubuntu/Debian — môi trường CI)

```bash
sudo apt-get update && sudo apt-get install -y maven sqlite3 git
# JDK 24 Temurin: https://adoptium.net/ (CI dùng actions/setup-java)
# Node 26: https://nodejs.org/ hoặc nvm install 26 (CI dùng actions/setup-node)
```

## 2. Checklist fresh-install (< 30 phút)

Làm lần lượt; mỗi bước có **cổng kiểm chứng** — chỉ bước tiếp khi cổng đạt.

- [ ] **Bước 0 — Toolchain** (~5–10 phút nếu phải cài):
  ```bash
  java -version     # ≥ 24 (máy tham chiếu: 24.0.1 Temurin; Maven có thể chạy trên JDK 27)
  mvn -version      # ≥ 3.9
  node --version    # v26.x
  sqlite3 --version
  ```
- [ ] **Bước 1 — Clone:** `git clone <repo-url> aegis-ebs && cd aegis-ebs`
- [ ] **Bước 2 — Reactor build + test** (~5–10 phút lần đầu):
  ```bash
  mvn -B -ntp verify
  ```
  **Cổng:** `BUILD SUCCESS`; **338 test Java pass, 0 failure/error** (bảng §3).
  Toàn bộ test chạy headless — không cần màn hình/X server.
- [ ] **Bước 3 — Dashboard** (~1–2 phút):
  ```bash
  cd aegis-dashboard && npm ci && npm run build && npm test && cd ..
  ```
  **Cổng:** `dist/` có index.html + assets (cảnh báo chunk > 500 kB của Three.js là benign);
  `node --test` báo **pass 14 / fail 0**. (CI chỉ chạy `npm ci && npm run build`; `npm test` chạy local.)
- [ ] **Bước 4 — DB demo** (< 1 giây):
  ```bash
  ./scripts/initdb.sh        # tạo aegis.db ở gốc repo (đã gitignore)
  ```
  **Cổng:** in `TABLES=26`; `PRAGMA foreign_key_check` không output = sạch.
  **Lưu ý:** script `rm -f` file DB đích trước khi tạo — đừng trỏ vào DB thật.
  Có thể chỉ định đường dẫn: `./scripts/initdb.sh /duong/dan/aegis.db`.
- [ ] **Bước 5 — Chứng chỉ TLS demo** (< 5 giây, LAB ONLY):
  ```bash
  ./scripts/gen-server-cert.sh   # certs/aegis-server.p12 + aegis-truststore.p12, password aegis-demo
  ```
  **Cổng:** `certs/aegis-server.p12`, `certs/aegis-server.crt`, `certs/aegis-truststore.p12` tồn tại.
- [ ] **Bước 6 — Smoke test server** (< 1 phút):
  ```bash
  mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
  java -cp "aegis-server/target/classes:aegis-core/target/classes:aegis-persistence/target/classes:aegis-protocol/target/classes:$(cat aegis-server/cp.txt)" \
    aegis.server.ServerMain --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
  ```
  **Cổng:** in `AEGIS EBS server ready` + `EBSP/2.0 TLS : localhost:9443` + `status feed : ws://localhost:9444`;
  thế giới demo tự seed (kiểm chứng nhanh ở terminal khác):
  ```bash
  sqlite3 aegis.db "SELECT count(*) FROM devices;"   # 480
  ```
  Ctrl-C để dừng. **Vì sao classpath dài:** `cp.txt` nằm trong `aegis-server/` (module dir),
  và jar `aegis-*` trong `~/.m2` có thể **cũ hơn working tree** — phải kèm `target/classes`
  của các module anh em, nếu không client nối vào sẽ gặp `NoClassDefFoundError` (vd `aegis/core/Grants`).
  Cách khác: `mvn -q -DskipTests install` một lần để làm mới `~/.m2` rồi bỏ các thư mục classes.
- [ ] **Bước 7 — Smoke test tùy chọn** (demo path):
  - Dashboard: `cd aegis-dashboard && npm run dev` → http://localhost:5173 (chưa có server thì MOCK FEED).
  - Client JavaFX (cần màn hình): lệnh trong README §5 — device `DEV-A-F05-A-01`, secret `device-demo-secret`.
  - Simulator 100–200 agent: **phải đăng ký fleet trước** — mục §2.2 dưới đây.

Lệnh tắt (tương đương README): `./scripts/dev.sh` = `mvn -q -DskipTests package`;
`./scripts/test.sh` = `mvn -q test`.

### 2.1. Thế giới demo & 2 chế độ seed (ĐỌC TRƯỚC KHI NẠP DỮ LIỆU)

Nguồn sự thật: `aegis-server/src/main/java/aegis/server/ServerStore.java` → `seedWorld`,
chạy tự động khi `ServerMain` khởi động (trừ `--no-seed`). Đã đối chiếu trực tiếp với
một server chạy thật (2026-11-21):

| Thực thể | Giá trị |
|---|---|
| Building | id `AEGIS-BLD-A` (mặc định `ServerConfig.buildingId`), code `A`, name `AEGIS Tower A` |
| Topology | 10 tầng × 4 zone (`F01-A`…`F10-D`) × **12 device/zone** (`devicesPerZone=12`) = **480 device** |
| Device code | `DEV-A-F{nn}-{A..D}-{01..12}` (vd `DEV-A-F05-A-01`) |
| Users | `admin`/`admin-demo-secret` (SYSTEM_ADMIN) · `secops`/`secops-demo-secret` (SECURITY_OPERATOR) · `floor7`/`floor7-demo-secret` (FLOOR_MANAGER) · `employee`/`employee-demo-secret` (EMPLOYEE) |
| Grants | `secops` → building-wide `PUBLISH`; `floor7` → floor 7 `PUBLISH`. SYSTEM_ADMIN publish mọi nơi qua ScopedRBAC (không cần grant row) |
| Credential device | `device-demo-secret` — **một hash Argon2id chung** cho cả fleet (lab only), tham số `m=16384,t=2,p=1` |

`seed_demo.sql` (aegis-persistence) đã được **đồng bộ đúng thế giới trên** (cùng building id/code,
480 device code, 4 user, 2 grant; kiểm chứng từng hàng khớp server thật, `foreign_key_check` sạch).
Khác biệt có chủ đích: hash trong file là **PLACEHOLDER**, và file thêm nội dung showcase
(display_x/y cho zone, alert_templates FIRE/DRILL vi/en, demo_scenarios FIRE_DRILL).

**Hai chế độ — KHÔNG trộn trên cùng một DB:**

- **Chế độ 1 — demo thật (khuyến nghị):** `initdb.sh` → chạy `ServerMain`. Server tự seed
  hash Argon2id thật; đăng nhập/device auth hoạt động ngay với bảng trên.
  **Không nạp `seed_demo.sql` vào DB này.**
- **Chế độ 2 — dataset offline:** `./scripts/initdb.sh demo.db` rồi
  `sqlite3 demo.db < aegis-persistence/src/main/resources/db/seed_demo.sql`
  (file tự bật `PRAGMA foreign_keys=ON` cho sqlite3 CLI; kiểm tra: `sqlite3 demo.db 'PRAGMA foreign_key_check;'` phải sạch).
  Dùng để xem ERD/SQL/dashboard mock. **Auth sẽ失败 cho tới khi thay placeholder bằng hash thật** (recipe §2.2).

> ⚠️ **Bẫy trộn chế độ:** `seedWorld` idempotent — nó **BỎ QUA** nếu building `AEGIS-BLD-A`
> đã tồn tại. Nạp `seed_demo.sql` trước rồi chạy server ⇒ server không seed lại ⇒
> placeholder chặn mọi đăng nhập. Đã kiểm chứng: server vẫn khởi động sạch trên DB nạp
> seed (không đụng UNIQUE), nhưng auth cần hash thật.

### 2.2. Simulator fleet — đăng ký device (recipe đã kiểm chứng)

Simulator đặt device code `<prefix>-0001…<prefix>-NNNN` (`--device-prefix`, mặc định `SIM-DEV`).
Server **không auto-enroll**: `AuthService.authenticateDevice` chỉ chấp nhận device đã có trong
bảng `devices` với credential hash khớp. Fleet `SIM-DEV-*` không nằm trong server seed ⇒
chạy ngay sẽ bị `auth rejections (permanent)` (đã quan sát thật: 2/2 agent bị từ chối).

Đăng ký fleet dùng chung credential `device-demo-secret` (cùng secret với demo fleet):

**1) Sinh hash Argon2id bằng jshell** (dùng chính `Argon2idHasher` của server — không cần sửa code):

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

**2) Nạp fleet vào DB của server đang chạy** (thay `HASH_THẬT` bằng output trên; ví dụ 150 agent,
gán tất cả vào zone F01-A để publish tầng 1 tới được fleet):

```bash
sqlite3 aegis.db <<EOF
PRAGMA foreign_keys=ON;
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 150)
INSERT INTO devices(id, floor_id, device_code, credential_hash, enabled, enrolled_at)
SELECT 'sim-dev-' || printf('%04d', i),
       (SELECT id FROM floors WHERE floor_number = 1),
       'SIM-DEV-' || printf('%04d', i),
       'HASH_THẬT', 1, '2026-01-01T00:00:00.000000000Z'
FROM n;
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 150)
INSERT INTO zone_devices(zone_id, device_id, assigned_at)
SELECT (SELECT z.id FROM zones z JOIN floors f ON f.id = z.floor_id
        WHERE f.floor_number = 1 AND z.zone_code = 'F01-A'),
       'sim-dev-' || printf('%04d', i), '2026-01-01T00:00:00.000000000Z'
FROM n;
EOF
sqlite3 aegis.db "PRAGMA foreign_key_check;"   # phải sạch
```

**3) Chạy simulator** (lệnh đầy đủ trong README §5; lưu ý `../certs/…` vì `exec:exec` fork
với working dir = `aegis-simulator/`):

```bash
mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:exec \
  -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain \
  --agents 150 --scenario clean --seed 42 --host localhost --port 9443 \
  --device-prefix SIM-DEV --device-secret device-demo-secret \
  --truststore ../certs/aegis-truststore.p12 --truststore-password aegis-demo --duration 30"
```

**Cổng kiểm chứng:** summary in `auth rejections (permanent): 0` và `connects ok` > 0.
Đã kiểm chứng thật (2026-11-21, máy tham chiếu): trước đăng ký 2/2 agent bị từ chối;
sau đăng ký `connects ok/failed = 2/0`, `heartbeats answered = 2`, exit 0; fleet 150 device
nạp bằng đúng SQL trên, `foreign_key_check` sạch. `[TODO: lưu capture run 150 agent vào docs/evidence/simulator/]`

Muốn reset fleet: `./scripts/initdb.sh` tạo DB mới (server sẽ seed lại thế giới gốc).

## 3. Chạy test từng module

Từ thư mục gốc repo. Dùng `-am` (also-make) để Maven tự build module thượng nguồn mà nó phụ thuộc;
hoặc `mvn -DskipTests install` một lần rồi bỏ `-am`. **Không chạy `-pl aegis-server` thiếu `-am`
khi jar `~/.m2` cũ** — kết quả sẽ âm thầm lệch working tree.

| Module | Lệnh | Test (surefire XML, 2026-11-21) |
|---|---|---|
| aegis-protocol | `mvn -B -ntp -pl aegis-protocol test` | 56 (FrameCodec fixture/stream/validation) |
| aegis-core | `mvn -B -ntp -pl aegis-core -am test` | 73 (AckLedger, RetryPolicy, DedupIndex, RBAC, TargetResolver, PriorityScheduler) |
| aegis-persistence | `mvn -B -ntp -pl aegis-persistence -am test` | 52 (Db pragmas/WAL, MigrationRunner 26 bảng, TransactionalPublisher, RecoveryScanner, repos, peer-review fixes) |
| aegis-server | `mvn -B -ntp -pl aegis-server -am test` | 63 (unit + FanoutEngine + TlsLoopbackIntegration 10 + ServerSecurityFixes 10) |
| aegis-client | `mvn -B -ntp -pl aegis-client -am test` | 43 (AlertRecord, ClientConfig, InboxStore, AlertClientEngine, TLS smoke) |
| aegis-simulator | `mvn -B -ntp -pl aegis-simulator -am test` | 20 (SimulatorConfig, FaultInjector deterministic, SimAgent, stats) |
| aegis-network-lab | `mvn -B -ntp -pl aegis-network-lab -am test` | 31 (UDP discovery trust gate, multicast hint, benchmark) |
| **Toàn reactor** | `mvn -B -ntp verify` (hoặc `./scripts/test.sh`) | **338** |
| aegis-dashboard | `cd aegis-dashboard && npm test` | 14 (`node --test test/logic.test.js`) |

Đếm test chính xác: `-q` ẩn số liệu — đọc XML trong `<module>/target/surefire-reports/`:

```bash
grep -h "tests=" aegis-persistence/target/surefire-reports/*.xml | head
```

Chạy thử dashboard: `npm run dev` (Vite dev server, cổng 5173) hoặc `npm run preview` sau khi build;
contract WS: [`aegis-dashboard/docs/status-contract.md`](../aegis-dashboard/docs/status-contract.md).

## 4. JavaFX và môi trường headless (CI)

`aegis-client` phụ thuộc JavaFX 24 (`javafx-controls`, `javafx-graphics`) để chạy GUI
(`mvn -pl aegis-client javafx:run` — cần màn hình thật; plugin fork với working dir =
`aegis-client/` nên đường dẫn truststore trong lệnh chạy là `../certs/…`).
**Toàn bộ 43 test hiện tại của aegis-client không khởi động JavaFX toolkit**
(chỉ test logic/config/store/engine/TLS), nên `mvn verify` chạy tốt trên
`ubuntu-latest` headless — đã kiểm chứng, không cần X server.

Nếu sau này thêm test UI JavaFX, chọn 1 trong 2 cách:

1. **Monocle headless** (khuyến nghị cho CI): thêm test dependency
   `org.testfx:openjfx-monocle` và chạy surefire với
   `-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw -Djava.awt.headless=true`.
2. **Xvfb**: bọc bước Maven bằng `xvfb-run -a mvn -B -ntp verify`
   (cài `xvfb` + thư viện GTK/GL của JavaFX trên runner).

## 5. Troubleshooting

| Triệu chứng | Nguyên nhân / cách xử lý |
|---|---|
| `invalid target release: 24` | JDK < 24 — cài Temurin 24+, kiểm tra `mvn -version` (Maven có thể chạy trên JDK khác `java -version`) |
| `NoClassDefFoundError: aegis/core/…` khi client nối vào server | Jar `aegis-*` trong `~/.m2` cũ hơn working tree — kèm `target/classes` các module anh em vào classpath (Bước 6) hoặc `mvn -DskipTests install` |
| `cat: cp.txt: No such file or directory` | `dependency:build-classpath -pl aegis-server` ghi `aegis-server/cp.txt` (module dir), không phải gốc repo |
| `NoSuchFileException: certs/aegis-truststore.p12` (simulator/client) | `exec:exec`/`javafx:run` fork với working dir = module dir — dùng `../certs/…` hoặc đường dẫn tuyệt đối |
| `BindException: Address already in use` (9443/9444) | Server cũ còn sống (kể cả orphan) — `lsof -nP -iTCP:9443 -sTCP:LISTEN`, kill đúng PID rồi chạy lại; client sẽ nói chuyện với server/DB CŨ nếu cổng bị chiếm |
| Simulator `auth rejections (permanent)` | Fleet `<prefix>-NNNN` chưa đăng ký trong `devices` hoặc secret không khớp hash — làm recipe §2.2 |
| Đăng nhập demo fail trên DB đã nạp `seed_demo.sql` | Hash placeholder + seedWorld bỏ qua vì building tồn tại (§2.1) — dùng chế độ 1, hoặc thay hash bằng PasswordHasher |
| `npm ci` lỗi EUSAGE/lockfile | `package-lock.json` lệch `package.json` — chạy `npm install` để đồng bộ rồi commit lockfile |
| Cảnh báo `native access` của sqlite-jdbc | Benign (JDK mới cảnh báo JNI); không ảnh hưởng test |
| Cảnh báo chunk > 500 kB khi vite build | Benign — Three.js bundle lớn; có thể code-split sau |
| File `aegis.db-wal`, `aegis.db-shm` | SQLite WAL mode — đã gitignore; đừng commit |
| Test persistence fail khi chạy song song | Mỗi test dùng `@TempDir` DB riêng; đừng trỏ chung 1 file DB |

## 6. Tài liệu liên quan

- [`docs/PROTOCOL.md`](PROTOCOL.md) — contract wire EBSP/2.0 (bất biến).
- [`docs/DEMO-RUNBOOK.md`](DEMO-RUNBOOK.md) — kịch bản demo 12 phút, chạy server/client/simulator.
- [`docs/TEST-PLAN.md`](TEST-PLAN.md) — 30 acceptance + 16 chaos.
- [`docs/SLIDES.md`](SLIDES.md) — dàn ý bảo vệ; ô `[TODO]` trỏ tới `docs/evidence/`.
- [`scripts/gen-server-cert.sh`](../scripts/gen-server-cert.sh) — cert TLS cho server demo.
