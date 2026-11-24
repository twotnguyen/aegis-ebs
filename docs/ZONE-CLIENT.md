# Client JavaFX nhận cảnh báo theo zone

> **SIMULATION/TRAINING ONLY.** Đây là demo lab, không phải hệ thống báo động khẩn cấp thật. Mọi banner, popup và dữ liệu đều mang tính mô phỏng.

Tài liệu này dùng cho `aegis-client` (desktop JavaFX có sẵn trong repo). Không có web client cho device trong tòa nhà.

## 1. Luồng nhận alert theo zone

1. Operator publish cảnh báo kèm scope (building/floor/zone), ví dụ floor 5, zone A.
2. Server `resolveScope(...)` chụp danh sách device thuộc scope đó và kiểm tra RBAC của operator.
3. Server fanout `ALERT_PUSH` **chỉ tới các session device nằm trong target đã resolve**. Client không tự lọc theo tầng/zone.
4. Device đăng nhập bằng `AUTH_DEVICE` với payload `{deviceCode, credential}`. CLI gọi là `--device-id`, nhưng giá trị đó đi vào payload với tên `deviceCode`; server dùng nó để tra cứu device -> building/floor/zone rồi ghi `AUTH_DEVICE_SUCCESS`. `AUTH_RESULT.deviceId` là **UUID canonical** do server trả về; mọi frame sau đó (`ACK_*`, `SYNC_REQUEST`, heartbeat) dùng UUID này, code chỉ dùng để login.
5. `AlertRecord` gửi tới client **không chứa floor/zone** — client hiển thị tiêu đề, nội dung, mức ưu tiên, v.v.; việc "ai nhận alert" hoàn toàn do server quyết.
6. Nếu mất ACK, server retry theo lịch demo (3s/6s/12s) với cùng `deliveryId`, `messageId` mới. Nếu device ngắt kết nối, khi reconnect gửi `SYNC_REQUEST` (client gửi SYNC ngay cả lần đầu kết nối): server trả alert còn active + tombstone hủy (`cancels`). Alert trùng/stale được ACK lại nhưng không hiển thị lại popup.

## 2. Durable inbox, ACK_RECEIVED và ACK_USER

- Inbox SQLite (`--inbox`, mỗi device nên dùng một file riêng) lưu alert durable theo `alertId` + `revision` (revision tăng dần), kèm tombstone hủy và trạng thái ACK. Dùng WAL + `synchronous=FULL`.
- Thứ tự bắt buộc: **ghi inbox xuống đĩa xong mới gửi `ACK_RECEIVED`**, rồi mới đánh dấu đã gửi ACK. Nếu gửi ACK hỏng, dòng inbox vẫn còn pending và được gửi lại sau reconnect — không mất alert khi crash.
- `ACK_RECEIVED` = máy đã nhận và lưu bền alert. **Không** có nghĩa là con người đã đọc.
- `ACK_USER` = người thật bấm nút **Acknowledge** trên cửa sổ JavaFX. Đây là cú chỉ nhân viên, được lưu bền trong inbox (`userAcked`, `userId`, `ackUserAt`). Client chỉ đánh dấu "đã gửi" khi nhận `COMMAND_RESULT{ok:true}` tương ứng; nếu không có xác nhận, ACK_USER vẫn ở hàng đợi và được gửi lại sau reconnect. Launcher tự truyền UUID user `employee` qua `--user-id` (server từ chối `USER-SIM-0001`).

## 3. Chạy client

Yêu cầu: server đang chạy, cert demo đã có (`certs/aegis-truststore.p12`), máy có màn hình thật (JavaFX không headless).

```bash
bash scripts/demo-zone-client.sh
```

Mặc định: `DEVICE_CODE=DEV-A-F05-A-01`, `SERVER_HOST=127.0.0.1`, `TLS_PORT=9443`, truststore `certs/aegis-truststore.p12`, password `aegis-demo`, inbox `client-inbox-DEV-A-F05-A-01.db`.

Đổi device/host/cổng (truyền theo thứ tự, có thể bỏ đuôi để lấy mặc định):

```bash
bash scripts/demo-zone-client.sh DEV-A-F05-B-02 127.0.0.1 9443
bash scripts/demo-zone-client.sh --help
```

Lệnh Maven tương đương (script tự chuyển path sang tuyệt đối vì `javafx:run` fork với working dir = `aegis-client/`):

```bash
mvn -pl aegis-client javafx:run -Djavafx.args="--host 127.0.0.1 --port 9443 \
  --device-id DEV-A-F05-A-01 --device-secret device-demo-secret \
  --truststore $PWD/certs/aegis-truststore.p12 --truststore-password aegis-demo \
  --inbox $PWD/client-inbox-DEV-A-F05-A-01.db"
```

`--device-id` phải là device có thật trong seed của server (`DEV-A-F{nn}-{A..D}-{01..12}`) và credential phải khớp; mặc định `simulation-secret` trong `ClientConfig` sẽ bị server từ chối, nên script dùng `device-demo-secret` của seed demo. Có thể đổi bằng biến môi trường `DEVICE_SECRET`.

## 4. Demo một ca alert theo zone

Terminal 1 — server + bridge + dashboard:

```bash
bash scripts/demo-web.sh
```

Terminal 2 — device trong zone cần nhận (ở đây F05-A):

```bash
bash scripts/demo-zone-client.sh
```

Terminal 3 — publish FIRE_DRILL đúng zone F05-A qua bridge:

```bash
curl -sS -X POST http://127.0.0.1:9445/api/publish \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin-demo-secret","floor":5,"zone":"A","category":"FIRE_DRILL","priority":"CRITICAL","title":"Zone drill","instruction":"Simulation drill.","simulation":true}'
```

Quan sát: cửa sổ JavaFX chuyển `CONNECTING -> ONLINE`, popup hiện kèm banner SIMULATION; client gửi `ACK_RECEIVED` ngay sau khi lưu inbox; bấm **Acknowledge** để gửi `ACK_USER`. Để publish toàn tòa nhà, **xóa cả `floor` lẫn `zone`** (đặt riêng `"zone":"ALL"` mà vẫn giữ `floor:5` chỉ phát tới tầng 5). Dừng toàn demo bằng `Ctrl+C` ở terminal 1.

## 5. Demo qua Tailscale

Trên máy server lấy IP tailnet (`tailscale ip -4`). Trên máy client (đã join cùng tailnet, mở port TLS 9443 tới server):

```bash
bash scripts/demo-zone-client.sh DEV-A-F05-A-01 <IP-tailnet-cua-server> 9443
```

Cert demo hiện do `scripts/gen-server-cert.sh` sinh với `SAN=dns:localhost,ip:127.0.0.1`. Client hiện **pin vào truststore** (`certs/aegis-truststore.p12`) và không bật hostname/endpoint identification nên vẫn kết nối được tới IP tailnet bằng cert đó. Nếu bật kiểm tra hostname, kết nối tới `<IP-tailnet-cua-server>` sẽ không khớp SAN: phải sinh lại cert server với SAN chứa IP/hostname tailnet và import lại vào truststore trước khi chạy. Đây là cert tự ký cho lab — không dùng cho production.

Không dùng multicast qua Tailscale; luồng này là TLS unicast trực tiếp tới `SERVER_HOST:TLS_PORT`.

## 6. Xem log ACK trong audit_events

Server ghi mỗi ACK vào bảng `audit_events` (`aegis.db`): `actor_id` = **UUID canonical** của device (không phải deviceCode), `action` = `ACK_RECEIVED` / `ACK_USER` / `ACK_CANCEL`, `scope` = `alertId`, `outcome` = `OK` hoặc `DUPLICATE`, `payload_json` chứa `deliveryId` và `revision`. Với ACK_USER, `correlation_id` là `messageId` của **push frame** mà client đang ACK — không phải correlationId của lệnh publish trên browser.

```bash
sqlite3 -header -column aegis.db \
  "SELECT a.occurred_at, a.action, a.scope, a.outcome, a.payload_json \
   FROM audit_events a JOIN devices d ON d.id=a.actor_id \
   WHERE d.device_code='DEV-A-F05-A-01' AND a.action LIKE 'ACK_%' \
   ORDER BY a.occurred_at, a.rowid;"
```

Để thấy đúng ca ACK của một alert (mọi revision, mọi device), lọc theo `scope` = alertId rồi ghép `deliveryId` trong `payload_json`:

```bash
sqlite3 -header -column aegis.db \
  "SELECT occurred_at, actor_id, action, outcome, payload_json FROM audit_events \
   WHERE scope='<alert-id>' AND action LIKE 'ACK_%' ORDER BY occurred_at, rowid;"
```

`ACK_RECEIVED` xuất hiện ngay khi client commit inbox; `ACK_USER` chỉ xuất hiện sau khi bấm nút Acknowledge. Nếu thấy `DUPLICATE`, đó là ACK lặp do retry/SYNC (idempotent, server vẫn ghi vết).
