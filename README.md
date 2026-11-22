# AEGIS-EBS — Advanced Emergency Governance & Intelligent Signaling

**Hệ thống thông báo khẩn cấp cho tòa nhà văn phòng (Emergency Broadcast System)** — đồ án học phần **CMP180 · Lập trình mạng máy tính** (HUTECH).

> ⚠️ **SIMULATION / TRAINING ONLY.** Đây là nguyên mẫu học thuật, **không** thay thế hệ thống báo cháy, phát thanh sơ tán hay thiết bị cảnh báo an toàn sinh mạng đã được chứng nhận. Không phát cảnh báo giả tới người không tham gia demo.

Một trung tâm chỉ huy phát cảnh báo khẩn cấp tới hàng trăm client trong LAN, **có ưu tiên, xác nhận 2 tầng, gửi lại idempotent, khôi phục sau sự cố, phân quyền RBAC, nhật ký truy vết**, kèm **digital twin 3D**, **phòng thí nghiệm lỗi mạng (chaos lab)** và **thí nghiệm multicast thật** — tất cả xây bằng **Java Socket tự cài đặt giao thức**, không dùng broker/cloud push làm kênh chính.

---

## 1. Tính năng

**Lõi tin cậy (P0):**
- Broadcast cảnh báo theo tòa nhà / tầng / zone (target snapshot bất biến).
- 4 mức ưu tiên `CRITICAL > EMERGENCY > WARNING > INFO`, lập lịch fairness chống starvation.
- **ACK 2 tầng tách biệt:** `ACK_RECEIVED` (client lưu bền vững) ≠ `ACK_USER` (người bấm xác nhận).
- Retry idempotent (3s/6s/12s + jitter, tối đa 3 lần) với `deliveryId` ổn định, `messageId` mới mỗi attempt; chống trùng lặp + tombstone hủy/hết hạn.
- Reconnect + SYNC (latest revision, không hồi sinh bản tin cũ); crash recovery từ SQLite WAL.
- RBAC theo scope (building/floor) kiểm ở server mỗi lệnh; audit append-only; metrics p50/p95/p99.

**Showcase (P1):**
- **Digital twin 3D** (Three.js): 10 tầng × 4 zone đổi màu theo trạng thái thật; timeline/replay sự cố; chaos panel; reports/export; delivery funnel.
- **Network Chaos Lab:** drop/delay/duplicate ACK, disconnect, server restart — đo lường tái lập bằng seed.
- **Multicast lab (Bài 8):** multicast thật `239.10.10.10:9998` + UDP discovery (Bài 7) + so sánh TCP fanout vs multicast (đo thật: multicast giảm ~87.5% gói / ~97.8% byte trên link nguồn).

## 2. Kiến trúc

Modular monolith Java, 4 plane: **command** (dashboard/operator) · **delivery** (TCP/TLS fanout + ACK/retry) · **persistence/observability** (SQLite WAL + audit + metrics) · **discovery/experiment** (UDP/multicast, không tin cậy).

| Module | Vai trò | Test |
|---|---|---:|
| `aegis-protocol` | EBSP/2.0: framing length-prefix + DTO + validation | 56 |
| `aegis-core` | AckLedger, RetryPolicy, DedupIndex, ScopedRBAC, TargetResolver, PriorityScheduler | 73 |
| `aegis-persistence` | SQLite WAL, migration 26 bảng, transactional publish (outbox), recovery | 52 |
| `aegis-server` | TLS gateway, auth/RBAC, incident/alert, fanout, ACK/retry, sync, recovery, audit/metrics, WS status | 63 |
| `aegis-client` | JavaFX desktop, durable inbox, ACK 2 tầng, reconnect, popup | 43 |
| `aegis-simulator` | Headless 100–200 agent TLS + fault injection deterministic | 20 |
| `aegis-network-lab` | UDP discovery + trust gate, multicast ALERT_HINT, benchmark TCP vs multicast | 31 |
| `aegis-dashboard` | Web Vite + Three.js 3D twin, timeline/chaos/reports/funnel (Node) | 14 |

**Tổng: 338 test Java + 14 test dashboard = 352** (đếm từ surefire XML + `node --test`, xem `docs/BUILD.md`). Cổng: TLS `9443` · WS status `9444` · bridge Operator `9445` · UDP discovery `9999` · multicast `239.10.10.10:9998` · Vite `5173`.

## 3. Công nghệ

Java 24 (Maven 3.10) · `Socket`/`ServerSocket`/`SSLSocket`/`SSLServerSocket` · `DatagramSocket`/`MulticastSocket` · `ExecutorService`/virtual threads · SQLite WAL (`sqlite-jdbc`) · Jackson (JSON) · Argon2id (`argon2-jvm`) · JavaFX 24 · Three.js + Vite (Node 26) · JUnit 5.

## 4. Cấu trúc thư mục

```
aegis-ebs/
├── pom.xml                  # Maven reactor (7 module Java)
├── aegis-protocol/  aegis-core/  aegis-persistence/  aegis-server/
├── aegis-client/    aegis-simulator/  aegis-network-lab/
├── aegis-dashboard/         # Node + Vite + Three.js (3D twin)
├── scripts/                 # dev.sh, test.sh, initdb.sh, gen-server-cert.sh, demo-web.sh
├── docs/                    # PROTOCOL, ARCHITECTURE, ERD, SECURITY, SLIDES, BUILD,
│   ├── BAO-CAO.md           #   báo cáo theo mẫu HUTECH MauBCMH
│   ├── TEST-PLAN.md  DEMO-RUNBOOK.md
│   └── reviews/             # báo cáo peer-review độc lập từng module
└── .github/workflows/ci.yml
```

## 5. Quickstart

### Demo web-only — khuyến nghị cho bảo vệ

Một lệnh khởi động server + bridge + dashboard; phần còn lại thao tác hoàn toàn trên web:

```bash
bash scripts/demo-web.sh
```

- Mở `http://localhost:5173` → tab **Operator** (tab thứ sáu; bridge HTTP `http://localhost:9445`).
- **Fleet Start (20)** → **Publish FIRE_DRILL F05** (zone đỏ + ACK trên dashboard LIVE) → **Retry demo** (1 device im lặng, `FAILED` sau 3s/6s/12s) → **Cancel**.
- `Ctrl+C` để dừng. Các lệnh thiết lập thủ công bên dưới chỉ cần khi muốn studio/chaos thật; chi tiết xem `docs/USER-GUIDE.md` mục 0.

### Prerequisites
```bash
brew install maven          # Java 24+ (Temurin), Maven 3.10
# Node 26 + npm cho dashboard (đã có sẵn hoặc cài qua brew/node)
```

### Build + test + DB
```bash
./scripts/dev.sh            # mvn -DskipTests package
./scripts/test.sh           # mvn test  (338 test Java)
./scripts/initdb.sh         # tạo aegis.db: 26 bảng, foreign_key_check sạch
```

**Dữ liệu demo — 2 chế độ, KHÔNG trộn trên cùng 1 DB** (chi tiết: `docs/BUILD.md`):
- **Chế độ 1 — demo thật (khuyến nghị):** chỉ cần `initdb.sh` rồi chạy server. `ServerStore.seedWorld` tự nạp thế giới demo với hash Argon2id thật: building `AEGIS-BLD-A` (code A), 10 tầng × 4 zone × 12 device = **480 device** `DEV-A-F01-A-01`…`DEV-A-F10-D-12`. **Không** nạp `seed_demo.sql` vào DB này (seedWorld sẽ bỏ qua vì building đã tồn tại, và hash placeholder trong file sẽ chặn mọi đăng nhập).
- **Chế độ 2 — dataset offline (xem ERD/SQL/dashboard mock):** dùng file DB riêng:
```bash
./scripts/initdb.sh demo.db
sqlite3 demo.db < aegis-persistence/src/main/resources/db/seed_demo.sql
sqlite3 demo.db 'PRAGMA foreign_key_check;'   # phải sạch (không output)
```
`seed_demo.sql` đã đồng bộ **đúng thế giới server** (cùng building/device code/user/grant) nhưng hash là PLACEHOLDER có chủ đích — muốn đăng nhập trên DB này phải thay hash bằng output PasswordHasher (recipe trong `docs/BUILD.md`).

### Chứng chỉ TLS demo (self-signed, LAB ONLY)
```bash
./scripts/gen-server-cert.sh   # sinh certs/aegis-server.p12 + aegis-truststore.p12 (mật khẩu: aegis-demo)
```

### Chạy server
```bash
mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
# Lưu ý: cp.txt nằm trong aegis-server/ (module dir). Phải kèm target/classes của
# các module anh em trong working tree — jar aegis-* cài trong ~/.m2 có thể CŨ
# (thiếu class mới → NoClassDefFoundError khi client kết nối).
java -cp "aegis-server/target/classes:aegis-core/target/classes:aegis-persistence/target/classes:aegis-protocol/target/classes:$(cat aegis-server/cp.txt)" \
  aegis.server.ServerMain --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
# TLS :9443 · WS status :9444
# tùy chọn: --tls-port --ws-port --building --devices-per-zone --no-seed --heartbeat-ms --retry-base-ms
```
Demo logins (chế độ 1 — server tự seed, in ra lúc khởi động): `admin/admin-demo-secret` (SYSTEM_ADMIN), `secops/secops-demo-secret` (building), `floor7/floor7-demo-secret` (floor 7), `employee/employee-demo-secret` (EMPLOYEE). Device credential: `device-demo-secret` (shared fleet hash, lab only); device code `DEV-A-F{nn}-{A..D}-{01..12}`.

### Chạy client JavaFX
```bash
mvn -pl aegis-client javafx:run -Djavafx.args="--host localhost --port 9443 \
  --device-id DEV-A-F05-A-01 --device-secret device-demo-secret \
  --truststore ../certs/aegis-truststore.p12 \
  --truststore-password aegis-demo --inbox client-inbox.db --sound"
# javafx:run fork với working dir = aegis-client/ → truststore dùng ../certs/…
# --device-id phải là device CÓ THẬT trong server seed (DEV-A-F{nn}-{A..D}-{01..12})
# và --device-secret phải khớp; mặc định DEV-SIM-0001/simulation-secret sẽ bị từ chối.
```

### Chạy simulator (100–200 agent + fault injection)
```bash
mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:exec \
  -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain \
  --agents 150 --scenario mixed --seed 42 --host localhost --port 9443 \
  --device-prefix SIM-DEV --device-secret device-demo-secret \
  --truststore ../certs/aegis-truststore.p12 --truststore-password aegis-demo --duration 30"
# scenarios: clean | lossy | delayed | duplicate | flaky | mixed
# exec:exec fork với working dir = aegis-simulator/ → truststore dùng ../certs/…
```
> ⚠️ **Fleet phải được đăng ký trước.** Simulator đặt device code `<prefix>-0001…<prefix>-NNNN`;
> server chỉ xác thực device đã có trong bảng `devices` (không auto-enroll). Fleet `SIM-DEV-*`
> KHÔNG nằm trong server seed → chạy ngay sẽ bị `auth rejections (permanent)`.
> Đăng ký fleet bằng hash Argon2id của `device-demo-secret` — recipe đã kiểm chứng
> (jshell + SQL, kết quả nối tiếp 2/2 thành công) trong `docs/BUILD.md` mục "Simulator fleet".

### Chạy dashboard 3D
```bash
cd aegis-dashboard && npm install && npm run dev   # http://localhost:5173
# tự nối ws://localhost:9444; chưa có server thì chạy MOCK FEED
```

### Multicast / UDP lab + benchmark
```bash
mvn -q -pl aegis-network-lab compile exec:java \
  -Dexec.mainClass=aegis.networklab.LabMain -Dexec.args="bench --subscribers 8 --messages 200"
# lệnh khác: probe | serve | discover | publish | subscribe  (xem aegis-network-lab/src/main/resources/lab-guide.md)
```

## 6. Kiểm thử & bằng chứng

- `mvn verify` chạy toàn reactor; số liệu chính xác từ `target/surefire-reports/*.xml`.
- Test plan: `docs/TEST-PLAN.md` (30 acceptance + 16 chaos scenario C01–C16).
- Peer-review độc lập từng module: `docs/reviews/` (verdict + issues + khuyến nghị).
- Metrics đo thật (server monotonic p50/p95/p99, tách timeout); benchmark multicast đo thật 2026-11-21 (8 subscriber × 200 message, macOS loopback): **−87.5% gói / −97.8% byte trên link nguồn** — `target/lab-report/fanout-report.{json,md}`.
- Bằng chứng đo đạc lưu tại `docs/evidence/`: `verify/` (347 test Java + 51 XML), `benchmark/` (100 client), `chaos/` (5 preset C01–C05 + runs), `dashboard/` (ảnh LIVE + `web-only-demo-live.png` và metadata luồng demo 100% trên web), `video/aegis-ebs-demo.mp4` (video thuyết trình 12 phút, 720,05 s). Mỗi bộ kèm runId/commit SHA/máy đo; `scripts/make-demo-video.py` tái tạo video từ evidence thật.
- **Demo web-only (`scripts/demo-web.sh`):** bằng chứng `docs/evidence/dashboard/web-only-demo-live.{png}` + `web-only-demo-meta.json` — Fleet Start 20 → publish F05 (12 delivery, ACK) → Retry F01-A (11 RECEIVED + 1 FAILED attempt_count=4) → Cancel, thao tác 100% trên tab Operator.
- **Không bịa số liệu** — mọi kết quả báo cáo đều từ lần chạy thật kèm runId/commit SHA/máy đo.

## 7. Bảo mật (demo)

TLS self-signed + pinned truststore (không trust-all); RBAC scope kiểm ở server; Argon2id; frame giới hạn 64 KiB; bounded writer queue 256 + slow-client eviction; requestId idempotency; audit append-only; không log secret. **Giới hạn:** chứng chỉ tự ký (không phải CA công cộng), mật khẩu demo công khai, single-node (không HA). Chi tiết: `docs/SECURITY.md` (threat model + RBAC matrix + OWASP checklist).

## 8. Tài liệu

| File | Nội dung |
|---|---|
| `docs/PROTOCOL.md` | EBSP/2.0 wire contract (framing, envelope, message catalog, ACK/retry) |
| `docs/ARCHITECTURE.md` | Sơ đồ 4 plane + deployment + concurrency (mermaid) |
| `docs/ERD.md` | ERD 26 bảng + 36 quan hệ FK |
| `docs/SECURITY.md` | Threat model, controls, RBAC matrix, truststore guide, OWASP |
| `docs/BAO-CAO.md` | Báo cáo đồ án theo mẫu HUTECH MauBCMH (4 chương) |
| `docs/SLIDES.md` | Dàn ý slide bảo vệ 12 phút + Q&A |
| `docs/BUILD.md` | Build tái lập, fresh-install <30 phút |
| `docs/DEMO-RUNBOOK.md` | Kịch bản demo 12 phút |
| `Agents.md` | Quy trình điều phối đa agent + peer-review đã dùng để xây dự án |

## 9. Học thuật

Đồ án CMP180 — Lập trình mạng máy tính, HUTECH. GVHD: ThS. Nguyễn Văn Cẩn. Năm học 2025–2026. Tham khảo: giáo trình CMP180 (Bài 1–9), OASIS CAP 1.2, RFC 8085, OWASP, SQLite WAL, Three.js. Xem `docs/BAO-CAO.md` mục Tài liệu tham khảo.
