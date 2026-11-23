# MÔ HÌNH DEMO 3 VAI TRÒ — AEGIS-EBS

> 🌐 [English version](en/DEMO-3ROLES.md) · [Hướng dẫn song ngữ](I18N.md)

> SIMULATION/TRAINING ONLY. Đây là nguyên mẫu học thuật, không thay thế hệ thống báo cháy hay phát thanh sơ tán đã được chứng nhận. Mọi lệnh chạy từ gốc repo `aegis-ebs/`.

## 1. Tổng quan

Demo được chiếu đồng thời trên ba màn hình, mỗi màn hình một vai trò, để phân biệt rõ ai quan sát, ai nhận cảnh báo, ai điều hành. Ba vai trò dùng chung một server TLS (`9443`), một status feed (`ws://localhost:9444`) và một HTTP bridge (`http://localhost:9445`); khởi động bằng `bash scripts/demo-web.sh` hoặc các lệnh thủ công trong `docs/USER-GUIDE.md`.

Các số liệu đã công bố giữ nguyên, không đổi trong tài liệu này: 347 test Java + 20 test dashboard, CI (`.github/workflows/ci.yml`), benchmark 100 client, chaos C01–C05, digital twin footprints 72×50 m, i18n 299 key. Không đưa số liệu mới vào luồng demo.

## 2. Ba vai trò

| Vai trò | Màn hình | Trách nhiệm trong demo | Không làm |
|---|---|---|---|
| **3D twin** | Dashboard Three.js, `http://localhost:5173`, tab **Twin** | Màn hình nhìn: zone đổi màu theo severity, timeline/replay, chaos panel, reports, delivery funnel; click tầng để fly-to/cutaway/explode. | Không phát, duyệt hay hủy lệnh. |
| **Zone client** | JavaFX desktop, ví dụ thiết bị `DEV-A-F05-B-01` | Nhận `ALERT_PUSH` đúng zone đích; lưu durable inbox (SQLite WAL) rồi gửi `ACK_RECEIVED`; popup + beep; người bấm **Acknowledge** gửi `ACK_USER` kèm `userId`. | Không nhận alert của zone khác; không tự suy `ACK_USER` từ `ACK_RECEIVED`; không publish. |
| **Admin console** | Tab **Admin** trên dashboard (HTTP bridge mở rộng `http://localhost:9445`) | Giám sát fleet/trạng thái; duyệt hoặc chỉnh grant `PUBLISH` trong bảng `role_grants`; đọc log `audit_events` (actor, action, scope, outcome, correlationId) qua bridge mở rộng. | Không sửa bản tin đang phân phối; scope luôn lấy từ DB, không tin payload client. |

Ghi chú: grant `PUBLISH` và log audit dùng đúng hai bảng có sẵn `role_grants` và `audit_events` (xem `docs/ERD.md`). Việc "duyệt/chỉnh grant" và "đọc audit qua bridge mở rộng" là lựa chọn của mô hình demo, không phải bảng hay cột mới. Mọi mutation Admin bắt buộc có `operator` là `SYSTEM_ADMIN` đang bật; `actor_id` trong audit là ID người thật, không phải chuỗi do client tự khai. Bridge mặc định chỉ bind loopback; nếu mở rộng ra Tailscale phải giới hạn bằng ACL và chuyển sang API có xác thực đầy đủ trước khi dùng ngoài lab.

## 3. Luồng demo 5 bước

**Bước 1 — Admin duyệt grant PUBLISH.** Đăng nhập `admin/admin-demo-secret` (SYSTEM_ADMIN), mở tab **Admin**. Trong bảng `role_grants`, duyệt hoặc chỉnh grant `PUBLISH` cho `secops` (building) và/hoặc `floor7` (floor 7). Scope được suy từ DB. Nếu chưa có grant hợp lệ, lệnh publish của operator trả `DENIED` / `INVALID_SCOPE` và vẫn ghi audit.

**Bước 2 — Operator publish F05-B.** Operator gửi `ALERT_CREATE` rồi `ALERT_PUBLISH` với `requestId` idempotent; target snapshot là zone `F05-B` (tầng 5, zone B), bất biến sau khi activate. Server kiểm RBAC từ `role_grants`, rồi fanout `ALERT_PUSH` tới các device trong zone theo priority (CRITICAL trước).

**Bước 3 — JavaFX client `DEV-A-F05-B-01` popup + Acknowledge.** Thiết bị đích nhận push, ghi inbox bền vững **trước** khi gửi `ACK_RECEIVED`; cửa sổ hiện banner SIMULATION, popup + beep. Người bấm **Acknowledge** → `ACK_USER` (kèm `userId`, thời điểm riêng). Thiết bị ngoài zone `F05-B` không nhận alert này; retry (3s/6s/12s, tối đa 3) chỉ nhắm đúng delivery chưa ACK.

**Bước 4 — 3D twin đổi màu.** Dashboard nhận `status/1` qua WS `9444`; zone `F05-B` trên twin (10 tầng × 4 zone, footprints 72×50 m) đổi màu theo severity; delivery funnel tiến `SENT → RECEIVED → ACK_USER`. Twin chỉ quan sát: màu trên màn hình là hệ quả của ACK thật từ client, không phải lệnh phát từ twin.

**Bước 5 — Admin đọc audit.** Trở lại tab **Admin**, đọc bảng `audit_events` qua bridge mở rộng: các dòng có action `PUBLISH`, `PUBLISH_DUPLICATE`, `DENIED`, `AUTH_OPERATOR_SUCCESS` / `AUTH_OPERATOR_REJECTED` (cùng dòng ghi thao tác duyệt grant do bridge tạo ra) đều append-only, kèm actor/scope/outcome/correlationId. Replay incident chỉ đọc, không phát lại alert.

## 4. Tailscale: dùng được và không dùng được

- **Dùng được:** Tailscale là overlay L3 với địa chỉ `100.x`. Mọi kênh TCP/TLS/HTTP/WS đi qua overlay bình thường: TLS `9443`, status WS `9444`, bridge `9445`, Vite `5173`. Từ máy khác trong tailnet có thể mở `http://100.x.y.z:5173` hoặc cho client JavaFX kết nối tới `100.x.y.z:9443` (vẫn dùng đúng truststore của cert self-signed).
- **Không dùng được:** multicast Bài 8 (`239.10.10.10:9998`, TTL=1). TTL=1 giữ gói trong một đoạn LAN; overlay L3 của Tailscale không chuyển tiếp multicast giữa các máy. Không chạy lab multicast qua tailnet.
- **Cách thay thế:** dùng LAN vật lý (từ 2 máy) cho demo multicast thật; trên một host, dùng fallback unicast (`UnicastHintPublisher` / `UnicastHintSubscriber`) — network-lab tự fallback khi multicast không khả dụng (xem `docs/USER-GUIDE.md` mục 9 và `docs/ARCHITECTURE.md`).

## 5. Liên kết

- Bản tiếng Anh: [`docs/en/DEMO-3ROLES.md`](en/DEMO-3ROLES.md)
- Hướng dẫn vận hành: [`docs/USER-GUIDE.md`](USER-GUIDE.md) · Kịch bản 12 phút: [`docs/DEMO-RUNBOOK.md`](DEMO-RUNBOOK.md)
