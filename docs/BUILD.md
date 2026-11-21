# BUILD — Hướng dẫn build tái lập (fresh install)

AEGIS-EBS · CMP180 capstone · **SIMULATION/TRAINING only**

Tài liệu này mô tả cách build lại toàn bộ hệ thống từ một máy sạch: Maven reactor
7 module (Java 24) + dashboard `aegis-dashboard` (Node/Vite/Three.js) + SQLite 26 bảng.
CI chạy đúng các lệnh này trên `ubuntu-latest` — xem [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

**NFR-015: fresh install < 30 phút.** Ngân sách tham khảo (máy sạch, mạng bình thường):

| Bước | Thời gian ước tính |
|---|---|
| Cài JDK 24 + Maven + Node 26 + sqlite3 | 5–10 phút |
| `mvn -B -ntp verify` lần đầu (tải dependency + test toàn reactor) | 5–10 phút |
| `npm ci && npm run build` (dashboard) | 1–2 phút |
| `scripts/initdb.sh` (26 bảng) | < 1 giây |

CI đặt `timeout-minutes: 30` để bám NFR-015; các lần build sau nhanh hơn nhờ cache
Maven `~/.m2` và npm `~/.npm` (GitHub Actions cache theo `pom.xml` / `package-lock.json`).

## 1. Yêu cầu môi trường

| Thành phần | Phiên bản | Ghi chú |
|---|---|---|
| JDK | **24** (Temurin khuyến nghị, CI dùng Temurin 24) | `maven.compiler.release=24` — JDK mới hơn (vd 27) vẫn build được |
| Maven | 3.9+ | `brew install maven` |
| Node.js | **26** (CI dùng Node 26) | dashboard: Vite 8 + Three.js; cần `package-lock.json` cho `npm ci` |
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

## 2. Build tái lập từ máy sạch

```bash
git clone <repo-url> aegis-ebs && cd aegis-ebs

# (1) Toàn reactor: compile + test + verify 7 module
mvn -B -ntp verify

# (2) Dashboard: cài đúng lockfile + build production vào dist/
cd aegis-dashboard
npm ci
npm run build
cd ..

# (3) Tạo DB demo 26 bảng (001_base_schema + 002_showcase_expansion)
./scripts/initdb.sh                 # tạo aegis.db ở thư mục gốc repo (đã gitignore)
# hoặc chỉ định đường dẫn: ./scripts/initdb.sh /duong/dan/aegis.db
```

Kết quả mong đợi:

- `mvn -B -ntp verify` → `BUILD SUCCESS`. Test hiện có (đã kiểm chứng):
  **aegis-protocol 56 · aegis-core 73 · aegis-persistence 35 · aegis-client 36 = 200 test**,
  tất cả pass headless (không cần màn hình/X server).
- `npm run build` → `aegis-dashboard/dist/` (index.html + assets; cảnh báo chunk > 500 kB
  của Three.js là benign).
- `scripts/initdb.sh` → in `TABLES=26` và `PRAGMA foreign_key_check` sạch (không có output = không lỗi).
  **Lưu ý:** script `rm -f` file DB đích trước khi tạo — đừng trỏ vào DB thật.

Lệnh tắt (tương đương README): `./scripts/dev.sh` = `mvn -q -DskipTests package`;
`./scripts/test.sh` = `mvn -q test`.

## 3. Chạy test từng module

Từ thư mục gốc repo. Dùng `-am` (also-make) để Maven tự build module thượng nguồn mà nó phụ thuộc;
hoặc `mvn -DskipTests install` một lần rồi bỏ `-am`.

| Module | Lệnh | Test |
|---|---|---|
| aegis-protocol | `mvn -B -ntp -pl aegis-protocol test` | 56 (FrameCodec fixture/stream/validation) |
| aegis-core | `mvn -B -ntp -pl aegis-core -am test` | 73 (AckLedger, RetryPolicy, DedupIndex, RBAC, TargetResolver, PriorityScheduler) |
| aegis-persistence | `mvn -B -ntp -pl aegis-persistence -am test` | 35 (Db pragmas/WAL, MigrationRunner 26 bảng, TransactionalPublisher, RecoveryScanner, repos) |
| aegis-server | `mvn -B -ntp -pl aegis-server -am test` | chưa có test |
| aegis-client | `mvn -B -ntp -pl aegis-client -am test` | 36 (AlertRecord, ClientConfig, InboxStore, ClientEngine, ClientTlsSmoke) |
| aegis-simulator | `mvn -B -ntp -pl aegis-simulator -am test` | chưa có test |
| aegis-network-lab | `mvn -B -ntp -pl aegis-network-lab -am test` | chưa có test |
| Toàn reactor | `mvn -B -ntp verify` (hoặc `./scripts/test.sh`) | 200 |

Đếm test chính xác: `-q` ẩn số liệu — đọc XML trong `<module>/target/surefire-reports/`:

```bash
grep -h "tests=" aegis-persistence/target/surefire-reports/*.xml | head
```

Dashboard không có test runner; kiểm chứng bằng build: `cd aegis-dashboard && npm ci && npm run build`.
Chạy thử dashboard: `npm run dev` (Vite dev server) hoặc `npm run preview` sau khi build;
contract WS: [`aegis-dashboard/docs/status-contract.md`](../aegis-dashboard/docs/status-contract.md).

## 4. JavaFX và môi trường headless (CI)

`aegis-client` phụ thuộc JavaFX 24 (`javafx-controls`, `javafx-graphics`) để chạy GUI
(`mvn -pl aegis-client javafx:run` — cần màn hình thật). **Toàn bộ test hiện tại của
aegis-client không khởi động JavaFX toolkit** (chỉ test logic/config/store/engine/TLS),
nên `mvn verify` chạy tốt trên `ubuntu-latest` headless — đã kiểm chứng, không cần X server.

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
| `npm ci` lỗi EUSAGE/lockfile | `package-lock.json` lệch `package.json` — chạy `npm install` để đồng bộ rồi commit lockfile |
| Cảnh báo `native access` của sqlite-jdbc | Benign (JDK mới cảnh báo JNI); không ảnh hưởng test |
| Cảnh báo chunk > 500 kB khi vite build | Benign — Three.js bundle lớn; có thể code-split sau |
| File `aegis.db-wal`, `aegis.db-shm` | SQLite WAL mode — đã gitignore; đừng commit |
| Test persistence fail khi chạy song song | Mỗi test dùng `@TempDir` DB riêng; đừng trỏ chung 1 file DB |

## 6. Tài liệu liên quan

- [`docs/PROTOCOL.md`](PROTOCOL.md) — contract wire EBSP/2.0 (bất biến).
- [`docs/DEMO-RUNBOOK.md`](DEMO-RUNBOOK.md) — kịch bản demo 12 phút, chạy server/client/simulator.
- [`docs/TEST-PLAN.md`](TEST-PLAN.md) — 30 acceptance + 16 chaos.
- [`scripts/gen-server-cert.sh`](../scripts/gen-server-cert.sh) — cert TLS cho server demo.
