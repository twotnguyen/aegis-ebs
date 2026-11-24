# HƯỚNG DẪN VẬN HÀNH — AEGIS-EBS

> 🌐 [English version](en/USER-GUIDE.md) · [Danh mục song ngữ](I18N.md)

> SIMULATION/TRAINING ONLY. Mọi lệnh chạy từ gốc repo `aegis-ebs/`.

> **Khuyến nghị cho bảo vệ: web-only.** Dùng luồng một lệnh ở mục 0. Các mục 1–9 giữ nguyên cho người muốn studio/chaos thật (client JavaFX, simulator, fault injection, benchmark).

## 0. Demo web-only — khuyến nghị cho bảo vệ

Một lệnh khởi động server + bridge + dashboard; không cần JavaFX, simulator hay cấu hình TLS thủ công.

```bash
bash scripts/demo-web.sh
```

1. Mở `http://localhost:5173` (dashboard) — bridge HTTP của tab Operator chạy tại `http://localhost:9445`. Đợi status feed chuyển xanh (`LIVE`).
2. Chọn tab **Operator** — tab thứ sáu trên thanh điều hướng (Twin · Incidents · Chaos · Reports · Funnel · Operator).
3. Bấm **Fleet Start (20)** — khởi động 20 device ảo; danh sách fleet trên tab Operator chuyển sang trạng thái ONLINE.
4. Bấm **Publish FIRE_DRILL cho F05** — zone F05 chuyển đỏ trên digital twin; các ACK (`ACK_RECEIVED` rồi `ACK_USER`) hiện trên dashboard LIVE và bộ đếm tăng dần.
5. Chạy **Retry demo** — nút này tự chọn F01-A (12 device có thật trong fleet, 1 device im lặng): server gửi lại sau 3s, 6s, 12s (cùng `deliveryId`, `messageId` mới); sau 3 attempt device đó chuyển `FAILED`, 11 device còn lại vẫn ACK. Không dùng ALL/ALL vì 460 device chưa có fleet sẽ tạo nhiều FAILED làm lu mờ ca retry.
6. Bấm **Cancel** — hủy alert; tombstone hủy được đồng bộ, zone trở lại trạng thái bình thường trên dashboard LIVE.

Dừng demo bằng `Ctrl+C` tại terminal đang chạy `scripts/demo-web.sh`.

## 1. Chuẩn bị
```bash
brew install maven            # Java 24 (Temurin) + Maven 3.10
# Node 26 + npm cho dashboard (đã có hoặc brew install node)
bash scripts/dev.sh              # mvn -DskipTests package  → BUILD_OK
bash scripts/test.sh             # mvn test → 360 test Java
bash scripts/initdb.sh           # tạo aegis.db: 26 bảng, foreign_key_check sạch
sqlite3 aegis.db < aegis-persistence/src/main/resources/db/seed_demo.sql   # dữ liệu demo
bash scripts/gen-server-cert.sh  # certs/aegis-server.p12 + aegis-truststore.p12 (password: aegis-demo)
```

## 2. Chạy server
```bash
mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "aegis-server/target/classes:$(cat cp.txt)" aegis.server.ServerMain \
  --db aegis.db --keystore certs/aegis-server.p12 --storepass aegis-demo
```
Output mong đợi:
```
AEGIS EBS server ready
  EBSP/2.0 TLS : localhost:9443
  status feed  : ws://localhost:9444/ (status/1)
  demo operators: admin/admin-demo-secret (SYSTEM_ADMIN),
    secops/secops-demo-secret (building), floor7/floor7-demo-secret (floor 7)
  demo devices: DEV-A-F01-A-01.. (credential device-demo-secret)
```

## 3. Chạy client JavaFX (máy nhân viên)
```bash
mvn -pl aegis-client javafx:run -Djavafx.args="--host localhost --port 9443 \
  --device-id DEV-A-F01-A-01 --truststore certs/aegis-truststore.p12 \
  --truststore-password aegis-demo --inbox client-inbox.db --sound"
```
Cửa sổ hiện banner SIMULATION, trạng thái CONNECTING→ONLINE; khi có cảnh báo → popup + beep; nút **Acknowledge** gửi ACK_USER.

## 4. Chạy simulator (fleet headless + fault injection)
```bash
mvn -pl aegis-simulator org.codehaus.mojo:exec-maven-plugin:3.2.0:exec \
  -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain \
  --agents 100 --scenario mixed --seed 42 --host localhost --port 9443 \
  --truststore certs/aegis-truststore.p12 --truststore-password aegis-demo --duration 30"
```
Scenarios: `clean | lossy | delayed | duplicate | flaky | mixed`. In SUMMARY (agents, connects, alerts, ACK sent/dropped/delayed, disconnects, SYNC, heartbeats).

## 5. Mở dashboard 3D
```bash
cd aegis-dashboard && npm install && npm run dev   # http://localhost:5173
```
Tự nối `ws://localhost:9444`; chưa có server → MOCK FEED. Phím 1–6: Twin · Incidents · Chaos · Reports · Funnel · **Operator**. Tab Operator dùng bridge HTTP `http://localhost:9445` để publish/cancel alert thật và start/stop fleet device (xem mục 0). Tòa nhà 3D đổi màu zone theo severity; click tầng để fly-to/cutaway/explode.

## 6. Phát cảnh báo FIRE_DRILL (operator)
Với demo web-only dùng tab **Operator** trên dashboard (mục 0); với studio/chaos thật dùng operator client hoặc harness `target/e2e-dev/ebsp.py` (Operator.publish). Quy trình quan sát được:
1. Operator `ALERT_CREATE`/`ALERT_PUBLISH` (requestId idempotent) → server kiểm RBAC + snapshot target.
2. Fanout `ALERT_PUSH` tới thiết bị target (priority CRITICAL ưu tiên).
3. Client lưu durable inbox → `ACK_RECEIVED`; người bấm → `ACK_USER`.
4. Mất ACK → server retry 3s/6s/12s (tối đa 3) cùng deliveryId, messageId mới.
5. Client disconnect → reconnect `SYNC_REQUEST` → nhận alert active + tombstone hủy.
6. Server restart → `RecoveryScanner` khôi phục delivery dở từ SQLite.
7. Floor manager phát trái scope → FORBIDDEN + audit.

## 7. Chaos / benchmark / demo tái lập
```bash
bash scripts/bench-load.sh     # benchmark 100 client → docs/evidence/benchmark/<runId>/
bash scripts/chaos-run.sh      # chaos scenarios → docs/evidence/chaos/
bash scripts/demo-e2e.sh       # demo end-to-end → docs/evidence/demo/
```
Mỗi run kèm `summary.json` (runId, commit SHA, máy đo, p50/p95/p99, ACK rate, retry).

## 8. Phân quyền
| Role | Làm được |
|---|---|
| admin (SYSTEM_ADMIN) | mọi tòa nhà, quản lý user/role, publish/cancel |
| secops (SECURITY_OPERATOR) | publish/cancel trong building được cấp |
| floor7 (FLOOR_MANAGER) | publish/cancel chỉ floor 7 |
| employee | nhận cảnh báo + ACK_USER, không publish |

## 9. Khắc phục sự cố
- **Port bận**: đổi `--port`/cổng hoặc kill process cũ.
- **Cert lỗi**: chạy lại `gen-server-cert.sh`; client phải dùng đúng truststore.
- **Multicast không chạy (macOS lo0/Tailscale)**: network-lab tự fallback unicast; demo multicast dùng LAN thật 2 máy.
- **JavaFX headless**: dùng simulator + dashboard thay client GUI.

## 10. Mô hình demo 3 vai trò

Ba vai trò chiếu cùng lúc (chi tiết: `docs/DEMO-3ROLES.md`):

- **3D twin** — dashboard Three.js: màn hình nhìn, zone đổi màu theo severity; timeline/replay, chaos, reports, delivery funnel. Không phát lệnh.
- **Zone client** — JavaFX desktop (ví dụ `DEV-A-F05-B-01`): nhận alert đúng zone, lưu inbox bền vững, gửi `ACK_RECEIVED`; người bấm **Acknowledge** → `ACK_USER`.
- **Admin console** — tab Admin trên dashboard: giám sát fleet/trạng thái; duyệt/chỉnh grant `PUBLISH` trong bảng `role_grants`; đọc `audit_events` qua bridge mở rộng.

Tailscale: dùng được cho TCP/TLS/dashboard qua IP `100.x`; **không** dùng cho multicast Bài 8 (TTL=1, overlay L3) — dùng LAN vật lý hoặc fallback unicast (xem mục 9). Luồng demo 5 bước xem `docs/DEMO-3ROLES.md`.
