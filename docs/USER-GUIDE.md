# HƯỚNG DẪN VẬN HÀNH — AEGIS-EBS

> SIMULATION/TRAINING ONLY. Mọi lệnh chạy từ gốc repo `aegis-ebs/`.

## 1. Chuẩn bị
```bash
brew install maven            # Java 24 (Temurin) + Maven 3.10
# Node 26 + npm cho dashboard (đã có hoặc brew install node)
./scripts/dev.sh              # mvn -DskipTests package  → BUILD_OK
./scripts/test.sh             # mvn test → 338 test Java
./scripts/initdb.sh           # tạo aegis.db: 26 bảng, foreign_key_check sạch
sqlite3 aegis.db < aegis-persistence/src/main/resources/db/seed_demo.sql   # dữ liệu demo
./scripts/gen-server-cert.sh  # certs/aegis-server.p12 + aegis-truststore.p12 (password: aegis-demo)
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
Tự nối `ws://localhost:9444`; chưa có server → MOCK FEED. Phím 1–5: Twin · Incidents · Chaos · Reports · Funnel. Tòa nhà 3D đổi màu zone theo severity; click tầng để fly-to/cutaway/explode.

## 6. Phát cảnh báo FIRE_DRILL (operator)
Dùng operator client hoặc harness `target/e2e-dev/ebsp.py` (Operator.publish). Quy trình quan sát được:
1. Operator `ALERT_CREATE`/`ALERT_PUBLISH` (requestId idempotent) → server kiểm RBAC + snapshot target.
2. Fanout `ALERT_PUSH` tới thiết bị target (priority CRITICAL ưu tiên).
3. Client lưu durable inbox → `ACK_RECEIVED`; người bấm → `ACK_USER`.
4. Mất ACK → server retry 3s/6s/12s (tối đa 3) cùng deliveryId, messageId mới.
5. Client disconnect → reconnect `SYNC_REQUEST` → nhận alert active + tombstone hủy.
6. Server restart → `RecoveryScanner` khôi phục delivery dở từ SQLite.
7. Floor manager phát trái scope → FORBIDDEN + audit.

## 7. Chaos / benchmark / demo tái lập
```bash
./scripts/bench-load.sh     # benchmark 100 client → docs/evidence/benchmark/<runId>/
./scripts/chaos-run.sh      # chaos scenarios → docs/evidence/chaos/
./scripts/demo-e2e.sh       # demo end-to-end → docs/evidence/demo/
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
