# AEGIS-EBS — Mô hình bảo mật & quyền riêng tư (SECURITY)

**Học phần:** CMP180 — Lập trình mạng máy tính · **Đồ án:** AEGIS Emergency Broadcast System (Showcase Edition)
**Loại tài liệu:** Thiết kế bảo mật + ánh xạ hiện thực + checklist OWASP.
**Nguồn:** Blueprint `Plan/AEGIS_MASTER_BLUEPRINT.md` §9 (Security & privacy), §7 (EBSP/2.0), §3 (NFR); `docs/PROTOCOL.md` (EBSP/2.0 wire contract); OWASP Cheat Sheet Series (Authorization, Session Management, Password Storage).

> ⚠️ **SIMULATION / TRAINING.** Tài liệu này mô tả thiết kế bảo mật cho một hệ thống **mô phỏng học thuật**, không phải hệ thống báo cháy/sơ tán được chứng nhận, không thay thế thiết bị an toàn sinh mạng. Mọi số liệu kiểm thử phải là **bằng chứng thật** (runId, commit SHA, cấu hình máy, timestamp) theo NFR-018; tài liệu này **không** điền số liệu đo đạc giả định.

---

## 0. Quy ước trạng thái hiện thực

Để bảo đảm tính trung thực học thuật, mỗi biện pháp được gắn nhãn trạng thái tại thời điểm soạn tài liệu:

| Nhãn | Ý nghĩa |
|---|---|
| ✅ **Đã hiện thực** | Có mã nguồn trong reactor và kiểm thử đơn vị/tích hợp tương ứng. |
| 🟡 **Một phần / demo** | Đã có mã nhưng tham số hoặc phạm vi còn ở mức demo, cần hiệu chỉnh khi triển khai thật. |
| 🔧 **Cần hardening** | Đã có mã ở mức demo nhưng cần cấu hình/kiểm thử thêm cho production (rate limit, TTL tuyệt đối, masking log tập trung, dual approval…). Không còn mang nghĩa "chưa có gateway". |

**Ghi chú hiện trạng:** `aegis-server` hiện có `TlsGateway` + `MessageRouter` + `ServerMain`: auth gate trước mọi lệnh ứng dụng, RBAC nạp lại từ DB mỗi lệnh có quyền (revoke có hiệu lực ngay), ACK_USER được kiểm tra `userId` có thật và phát riêng lên status feed (`userAcknowledged`). Các module lõi đã kiểm thử (hiện tại 57 + 76 + 59 test). Các mục 🔧 bên dưới là **khoảng cách cần hardening cho production**, không phải tuyên bố chưa hiện thực.

---

## 1. Phạm vi, ranh giới tin cậy & giả định

### 1.1. Bốn mặt phẳng (blueprint §6.1) và ranh giới tin cậy

| Mặt phẳng | Kênh | Mức tin cậy | Ghi chú bảo mật |
|---|---|---|---|
| Command plane | EBSP/2.0 qua **TCP/TLS** cổng 9443 | **Tin cậy** sau xác thực | Dashboard/operator đăng nhập, kiểm tra scope, tạo/sửa/hủy incident & alert. |
| Delivery plane | EBSP/2.0 qua **TCP/TLS** 9443 | **Tin cậy** sau xác thực | Phiên thiết bị đã xác thực, hàng đợi bounded, retry, ACK. |
| Persistence/observability | SQLite WAL (cục bộ) | Tin cậy nội bộ | Single-writer; audit append-only; metrics; replay chỉ đọc. |
| Discovery/experiment | **UDP 9999** + **Multicast 239.10.10.10:9998** | **KHÔNG tin cậy** | Chỉ là *hint/advisory*; không bao giờ kích hoạt hiển thị cảnh báo chính thức. |

**Nguyên tắc ranh giới tin cậy (blueprint §0.2, §7.7; PROTOCOL.md):**

1. **Chỉ TCP/TLS đã xác thực** mới được kích hoạt hiển thị cảnh báo. UDP discovery và multicast hint **không** phải nguồn tin đã xác thực.
2. Client **không bao giờ** coi multicast/UDP là lệnh operator (bất biến §5).
3. Server **suy ra scope được phép từ DB**, không bao giờ từ metadata role/floor do client gửi lên (§7.2).
4. Trường `simulation` trong payload **không** phải ranh giới bảo mật; chế độ demo của server + allowlist thiết bị riêng mới là kiểm soát (§7.2).
5. `sessionId` **không** tự nó là bằng chứng phân quyền bên ngoài kết nối đã xác thực (§7.4).
6. Môi trường demo chạy trên **LAN cô lập**; không phát cảnh báo giả lên mạng công cộng hoặc môi trường người dùng thật.

### 1.2. Giả định cứng

- Một node server duy nhất → **single point of failure**; crash recovery ≠ high availability (không tuyên bố HA).
- TLS dùng chứng chỉ **self-signed** cho demo (xem §6); không phải CA công cộng.
- Thiết bị demo dùng credential dùng chung (`device-demo-secret`) — **chỉ cho lab** (xem §6.5).
- Không điều khiển còi, thang máy, cửa, khóa thật; kịch bản chỉ tác động client demo được cấp quyền.

---

## 2. Mô hình mối đe dọa (Threat Model)

Phương pháp: liệt kê tác nhân/vector → tài sản bị đe dọa → tác động → biện pháp đối phó → ánh xạ kiểm thử. Ký hiệu STRIDE: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege.

| ID | Mối đe dọa | STRIDE | Vector / tác nhân | Tài sản & tác động | Biện pháp đối phó (mục §3) | Trạng thái | Kiểm thử |
|---|---|---|---|---|---|---|---|
| **T1** | **Rogue LAN spoof discovery** — kẻ xấu trong LAN giả mạo phản hồi `DISCOVER_EBS`/`ALERT_HINT` để chuyển hướng client tới server giả | S, T | UDP unicast/broadcast 9999; multicast 239.10.10.10:9998 không xác thực | Client kết nối nhầm server attacker → lộ credential, hiển thị cảnh báo giả | Hint chỉ advisory; client bắt buộc xác thực TLS qua truststore pinned + hostname/SAN + `CN==serverId` + nonce echo (`DiscoveryClient.discoverTrusted()`); nội dung authoritative chỉ lấy qua TLS | ✅ (network-lab) | T-24, T-25; C13, C14; `DiscoveryTrustTest`, `DiscoveryNonceEchoTest`, `VerifiedAlertSubscriberTest` |
| **T2** | **Stolen device credential** — đánh cắp credential thiết bị (file inbox, bộ nhớ, log, mạng) để mạo danh thiết bị | S, I | Lộ `device-secret`; đọc trộm DB `credential_hash` | Mạo danh thiết bị nhận/gửi ACK sai; leo thang sang phiên operator | Argon2id (memory-hard, salt duy nhất) thay vì plaintext/SHA nhanh; wipe mảng ký tự; tài khoản `enabled=0` không xác thực được; thu hồi/rotate credential; không log secret; TLS chống nghe lén | ✅ 🟡 | T-05, T-06; `AuthService`, `Argon2idHasher`; C09 |
| **T3** | **Session replay** — bắt và phát lại frame/sessionId để tái sử dụng phiên hoặc ACK cũ | S, R | Nghe lén TCP (nếu không TLS); phát lại `ALERT_PUSH`/ACK; đoán sessionId | Tái diễn cảnh báo cũ; ACK giả; mạo danh phiên | TLS chống nghe lén/replay tầng transport; sessionId UUIDv4 (122-bit entropy, CSPRNG); hết hạn phiên (idle + absolute); `deviceOnline()` đóng phiên cũ khi thiết bị kết nối lại; ACK chỉ tiến trạng thái (rank guard); dedup `(alertId,revision,deviceId)`; tombstone chống hồi sinh alert cũ | ✅ 🔧 | T-13..17; C03, C05, C12; `AckLedgerTest`, `DedupIndexTest`, `InboxStoreTest` |
| **T4** | **Role escalation** — operator/device tự nâng quyền (sửa role/grant trong payload, gọi lệnh vượt vai trò) | E, T | Client gửi role/floor tự khai; gọi `ALERT_PUBLISH` khi là EMPLOYEE/DEVICE | Phát cảnh báo trái phép; sửa quyền chính mình | Server suy scope từ DB (`RbacService.grantsFor`), bỏ qua role client gửi; `ScopedRBAC.canPublish` deny-by-default; router kiểm tra type message theo role đã xác thực; EMPLOYEE/DEVICE không bao giờ publish; không tự đổi quyền | ✅ 🔧 | T-07; C08; `ScopedRBACTest` |
| **T5** | **Out-of-scope broadcast** — Floor Manager/Security Operator phát tới tầng/tòa ngoài phạm vi được cấp | E, T | Payload target tùy ý mở rộng floor/zone | Cảnh báo tới thiết bị ngoài scope → nhiễu loạn, lộ thông tin khu vực | `Grants` bất biến (defensive copy); `canPublish` kiểm tra building/floor; `TargetResolver` snapshot bất biến từ DB; target rỗng → lỗi rõ; audit mọi lần DENIED | ✅ | T-07, T-08; C08; `ScopedRBACTest`, `TargetResolverTest`, `TransactionalPublisherTest` |
| **T6** | **Malformed frame** — frame oversize, UTF-8 lỗi, JSON sâu/lỗi, sai version/type để gây crash hoặc cạn bộ nhớ | T, D | Length prefix > 64KiB; JSON lồng sâu; UTF-8 không hợp lệ; trailing tokens | Tràn bộ nhớ, crash server, từ chối dịch vụ | `FrameCodecImpl` chặn oversize **TRƯỚC** cấp phát; `readFully` xử lý partial/coalesced; strict UTF-8 (REPORT); chỉ JSON object, cấm trailing tokens; `version==2`; type thuộc enum; giới hạn độ sâu JSON (Jackson `StreamReadConstraints`); read timeout + quota | ✅ 🟡 | T-01..04; C10; `FrameCodecStreamTest`, `FrameCodecValidationTest` |
| **T7** | **Slow-client exhaustion** — client đọc chậm/không đọc làm đầy hàng đợi, kéo chậm toàn hệ thống | D | Client không drain socket; flood INFO | Cạn bộ nhớ hàng đợi; chặn phân phối tới client khác | `BoundedWriterQueue` capacity 256/phiên; chính sách eviction theo priority (CRITICAL không bị INFO chặn); `REJECTED_SLOW` → ngắt client chậm; một writer thread/phiên (không trộn frame); metrics queue depth | ✅ | T-23; C16, C04; `BoundedWriterQueue` (bounded queue test) |
| **T8** | **Log exposure** — secret/password/token/raw PII lọt vào log hoặc báo cáo | I | Log lệnh auth, payload, exception chứa credential | Lộ credential/PII qua log, báo cáo incident | Không log password/credential; `Argon2idHasher.wipeArray`; audit chỉ ghi actor/action/scope/outcome/correlationId; masking filter (thiết kế); báo cáo incident phân quyền; T-28 log scan | 🟡 🔧 | T-28; NFR-016 |
| **T9** | **Privileged operator misuse** — operator hợp lệ lạm dụng quyền (phát/hủy sai, xóa dấu vết) | R, E | SYSTEM_ADMIN/SECURITY_OPERATOR thao tác ác ý | Cảnh báo sai; chối bỏ trách nhiệm | Audit append-only mọi thao tác rủi ro cao; dual-approval (demo, tùy chọn); hash-chain tamper-evidence (stretch, có giới hạn §3.10); least *** | 🔧 | FR-020; C08 (audit) |
| **T10** | **Database & log data exposure** — đọc trộm file SQLite/backup chứa hash, PII, audit | I | Truy cập file `aegis.db`, WAL, backup | Lộ hash credential, PII, lịch sử incident | Argon2id (hash không đảo ngược); PII minimization; phân quyền file DB; retention bounded (NFR-012); pepper (tùy chọn, defense-in-depth) | 🟡 🔧 | NFR-012, NFR-016 |

---

## 3. Biện pháp kiểm soát (Controls)

### 3.1. Xác thực server bằng TLS truststore (chống T1, T2, T3)

- **Client xác thực server, không trust-all.** `aegis.client.TlsSupport.clientContext(truststore, password)` nạp `KeyStore` → `TrustManagerFactory` → `SSLContext("TLS")`, **chỉ tin đúng chứng chỉ trong truststore** (pinned). Không có `X509TrustManager` rỗng / trust-all trong mặc định demo (NFR-009).
- **Kiểm tra hostname/SAN**: chứng chỉ demo có `SAN=dns:localhost,ip:127.0.0.1`; client phải khớp host kết nối với SAN (xem §6 về demo 2 máy).
- **Ràng buộc danh tính server trong lab** (`aegis-network-lab`, ✅): `DiscoveryClient.discoverTrusted()` chỉ trả `TrustedServer` khi đủ 4 điều kiện:
  1. nonce trong phản hồi discovery khớp nonce đã gửi;
  2. handshake TLS với truststore pinned thành công + hostname/SAN verification;
  3. `CN` của chứng chỉ == `serverId` được quảng cáo;
  4. `SERVER_HELLO` echo đúng nonce discovery **qua chính phiên TLS đó**.
- **Nội dung authoritative chỉ qua TLS**: `VerifiedAlertSubscriber` nhận hint → fetch qua TLS → sink chỉ thấy revision thật từ server, **bỏ qua revision trong hint**.
- **Server keystore**: `TlsSupport.serverContext(keystore, password)` (PKCS12/JKS theo đuôi file) dùng cho gateway và test fixture.
- 🔧 Gateway phải bật `SSLServerSocket` với `setNeedClientAuth(false)` (xác thực ứng dụng qua `AUTH_DEVICE`/`AUTH_OPERATOR` sau TLS) và **từ chối mọi message ứng dụng trước khi xác thực** (FR-001).

### 3.2. RBAC + scope tại MỖI lệnh (chống T4, T5)

- **Deny-by-default, least *** (OWASP Authorization). `aegis.core.ScopedRBAC.canPublish(role, grants, buildingId, floorId)` ✅:
  - `SYSTEM_ADMIN` → mọi nơi;
  - `SECURITY_OPERATOR` → trong building được cấp (mọi tầng của building đó);
  - `FLOOR_MANAGER` → chỉ tầng được cấp thuộc building được cấp;
  - `EMPLOYEE`, `DEVICE` → **không bao giờ** publish.
- **Grants bất biến** (`aegis.core.Grants`): defensive copy ở constructor, accessor trả unmodifiable view → sửa collection nguồn sau khi tạo không ảnh hưởng scope.
- **Scope lấy từ DB, không từ client** (`aegis.server.RbacService.grantsFor(userId)` ✅): nạp `role_grants` → `Grants`. `BUILDING_WIDE_FLOOR = "*"` là placeholder cho scope toàn building (không khớp floor grant cụ thể).
- **Target snapshot bất biến** (`TargetResolver` + `TransactionalPublisher` ✅): resolve thiết bị từ DB tại thời điểm activate; không device ngoài scope; snapshot không đổi sau activate (FR-009, T-08).
- 🔧 **Router thực thi theo từng message** (blueprint §7.1: "Strictly validate API message types against authenticated role"): gateway phải kiểm tra `MessageType` so với `ClientSession.role()`/`kind()` **trước khi** gọi service; `AlertService` giả định "RBAC is enforced by the router BEFORE these methods run". Mọi lần từ chối → `ACCESS_DENIED`/`INVALID_SCOPE` + audit (FR-006, FR-020, NFR-008).
- **Không tự nâng quyền**: operator không đổi được role/grant của chính mình (persona Security Operator, blueprint §1.1); quản trị grant chỉ thuộc SYSTEM_ADMIN.

### 3.3. Lưu trữ mật khẩu Argon2id (chống T2, T10)

- **Thuật toán**: `aegis.server.Argon2idHasher` ✅ dùng `de.mkammerer:argon2-jvm:2.11`, `Argon2Factory.Argon2Types.ARGON2id` (native libs bundled linux/win/darwin kể cả aarch64).
- **Tham số injectable** (`ServerConfig.Builder`): mặc định demo `iterations=2`, `memoryKib=16384` (16 MiB), `parallelism=1`.
  - 🟡 **Lưu ý OWASP**: Password Storage Cheat Sheet khuyến nghị Argon2id **tối thiểu m=19 MiB (19456 KiB), t=2, p=1** (RFC 9106). Mặc định demo 16 MiB **thấp hơn** ngưỡng khuyến nghị; khi triển khai thật phải nâng `memoryKib ≥ 19456` (hoặc bộ tham số tương đương OWASP). Tham số injectable nên việc hiệu chỉnh không cần sửa mã.
- **Salt duy nhất mỗi mật khẩu**: Argon2id tự sinh salt ngẫu nhiên nhúng trong chuỗi mã hóa `$argon2id$...` → chống rainbow table.
- **Không plaintext, không hash nhanh**: SHA-256 chỉ dùng cho `payload_hash` idempotency (không phải mật khẩu). Mật khẩu/credential luôn qua Argon2id.
- **Wipe bộ nhớ**: `hash()`/`verify()` dùng `char[]` và `argon2.wipeArray(chars)` trong `finally` → giảm vết credential trong RAM.
- **Verify an toàn**: chỉ chấp nhận chuỗi bắt đầu `$argon2id$`; hash malformed → `false` (không crash auth); `raw==null` → `false`.
- **Tài khoản vô hiệu hóa**: `AuthService.authenticateOperator/authenticateDevice` ✅ kiểm tra `enabled()`; tài khoản/thiết bị `enabled=0` **không bao giờ** xác thực (FR-003, T-06).
- **Pepper** (tùy chọn, defense-in-depth — OWASP): có thể thêm pepper khóa ngoài DB; tự nó không tăng đặc tính bảo mật nếu dùng đơn lẻ.
- 🟡 **Demo**: `ServerStore.seedWorld` hash credential demo (`admin-demo-secret`, `secops-demo-secret`, `floor7-demo-secret`, `employee-demo-secret`, `device-demo-secret` dùng chung cả fleet). Các giá trị này **công khai trong mã nguồn** → chỉ cho lab, tuyệt đối không dùng production (xem §6.5).

### 3.4. Idempotency theo requestId (chống T3, replay lệnh)

- **CREATE/PUBLISH**: `TransactionalPublisher.publish(Request)` ✅ ghi `alert_operations(request_id UNIQUE, state DONE, payload_hash sha256)` trong **một** transaction `BEGIN IMMEDIATE`; enqueue chỉ **sau** commit (transactional outbox).
- **Replay cùng requestId** → trả về alert GỐC, không fanout lần hai (`AlertService.publish` ✅: `findOperationByRequestId` → `CommandOutcome.ok(..., duplicate=true)` + audit `PUBLISH_DUPLICATE`).
- **Race đồng thời**: va chạm `UNIQUE request_id` → đọc lại người thắng và trả kết quả idempotent (✅ bắt `SQLException duplicateRace`).
- **UPDATE/CANCEL**: `ServerStore.insertOperation` ghi `request_id` + `payload_hash` (sha256 canonical) ✅; replay trả kết quả gốc.
- **Phân biệt CONFLICT** (blueprint UC-01/§5): requestId trùng nhưng **payload khác** → `CONFLICT`. 🟡 Hiện `payload_hash` đã được lưu để phát hiện; việc trả `CONFLICT` khi fingerprint khác là hợp đồng router/service cần hoàn thiện (mã hiện tại ưu tiên trả bản gốc khi trùng requestId).
- **Delivery idempotent**: mỗi delivery có `deliveryId` ổn định + `alertId` + `revision`; mỗi lần thử transport dùng `messageId` mới; ACK chỉ tiến trạng thái (PROTOCOL.md, `DeliveryRepo.recordAck` rank guard).

### 3.5. Frame tối đa 64KiB + giới hạn độ sâu (chống T6)

- **Hard cap**: `Protocol.MAX_FRAME_BYTES = 65536`, `PREFIX_BYTES = 4` ✅. `FrameCodecImpl.checkLength` từ chối `L < 1 || L > 65536` **TRƯỚC khi cấp phát body** (cả `decode`, `readFrame`, `encode`) → không cấp phát bộ nhớ không giới hạn (NFR-010).
- **Đọc đúng byte contract**: `uint32 BE length L` + `L bytes strict UTF-8 JSON`; `DataInputStream.readFully` cho 4 byte header rồi body → xử lý partial read; coalesced frames được tiêu thụ đúng, không over-read (✅ `FrameCodecStreamTest`).
- **Strict UTF-8**: `CharsetDecoder.onMalformedInput(REPORT).onUnmappableCharacter(REPORT)` → UTF-8 lỗi bị từ chối (✅).
- **JSON chặt**: chỉ chấp nhận JSON object; cấm trailing tokens sau object; `version` phải là integer == 2; `type` thuộc `MessageType` enum; `messageId`/`sentAt` bắt buộc non-blank; `payload` bắt buộc (✅ `FrameCodecValidationTest`).
- **Giới hạn độ sâu (max depth)**:
  - 🟡 Jackson `2.17.2` áp `StreamReadConstraints` mặc định: `maxNestingDepth = 1000`, `maxStringLength = 20_000_000`. Vì frame bị chặn ở 64KiB, độ dài chuỗi/độ sâu thực tế đã bị giới hạn gián tiếp bởi kích thước frame.
  - 🔧 **Khuyến nghị**: cấu hình `StreamReadConstraints` tường minh chặt hơn (ví dụ `maxNestingDepth` nhỏ, `maxStringLength` ≤ 64KiB) trên `ObjectMapper` dùng cho decode để đúng tinh thần blueprint §7.1 ("too-deep ... JSON") và không phụ thuộc mặc định thư viện. Hiện `FrameCodecImpl`/`Json.MAPPER` chưa đặt ràng buộc tường minh.
- **Read timeout + quota mỗi kết nối** (blueprint §7.1): 🔧 gateway phải đặt `setSoTimeout`, giới hạn số kết nối và quota frame/giây để chống flood (kèm `RATE_LIMITED`, `SERVER_BUSY`).

### 3.6. Hàng đợi bounded + chính sách slow-client (chống T7)

- **`BoundedWriterQueue`** ✅ capacity mặc định **256**/phiên (`ServerConfig.writerQueueCapacity`), `TreeSet` sắp theo priority (cao trước) rồi FIFO trong tier:
  - chưa đầy → `ACCEPTED`;
  - đầy & frame mới priority cao hơn frame thấp nhất → `EVICTED_LOWER` (evict frame thấp; CRITICAL không bị INFO flood chặn);
  - đầy & không evict được → `REJECTED_SLOW` → caller **ngắt client chậm** (`ClientSession.onSlowClient`).
- **Một writer thread/phiên** (`ClientSession.writerLoop` ✅): mọi frame server→client đi qua một hàng đợi + một writer → **không trộn byte của các frame khác nhau** trên cùng Socket (blueprint §6.4).
- **Priority scheduling toàn cục** (`PriorityScheduler` + `FanoutEngine` ✅): `CRITICAL > EMERGENCY > WARNING > INFO` (enum `Priority` ordinal tăng dần), bounded burst + fairness (xử lý tối đa 8 item tier cao rồi xét tier thấp — blueprint §7.6); không drop thầm lặng job critical; INFO có thể delay/coalesce theo chính sách ghi rõ.
- **Metrics**: queue depth theo priority/phiên, high-water mark, `evictedCount()` (NFR-011, NFR-014).
- **Network I/O ngoài DB transaction** (blueprint §7.6, §8.3): enqueue chỉ sau commit; retry scheduling idempotent.

### 3.7. Hết hạn phiên & quản lý session (chống T3)

- **Heartbeat/presence** ✅ (`SessionRegistry`): heartbeat 10s, suspect sau **3 miss** (`heartbeatIntervalMs=10000`, `heartbeatMaxMissed=3`); `sweep(now, interval, maxMissed)` thuần logic trên `Clock` inject → test deterministic; caller audit + đóng suspect.
- **Đóng phiên cũ khi reconnect** ✅: `deviceOnline(session)` thay phiên cũ của cùng `deviceId` và `close()` phiên trước → chống phiên stale song song.
- **sessionId ngẫu nhiên mạnh**: `Json.uuid()` = `UUID.randomUUID()` (UUIDv4, **122-bit entropy từ CSPRNG `SecureRandom`**) → đạt OWASP Session Management (entropy ≥ 64 bit, chiều dài ≥ 128 bit, nội dung vô nghĩa). `messageId`/`correlationId` cũng UUIDv4.
- **sessionId không là bằng chứng phân quyền ngoài kết nối xác thực** (PROTOCOL §7.4): mọi lệnh vẫn phải qua kiểm tra role/scope trên phiên đã xác thực.
- 🔧 **Hết hạn idle + absolute** (OWASP Session Management; FR-004 "session expiry", "session revoke works"): gateway cần bổ sung TTL idle và TTL tuyệt đối cho phiên operator, thu hồi phiên khi logout/đổi quyền, và renew sessionId khi privilege thay đổi (chống session fixation). Hiện `ClientSession` mới có heartbeat/suspect, **chưa** có trường expiry tuyệt đối.
- **LOGOUT** hủy phiên; reconnect tạo phiên xác thực mới + `SYNC_REQUEST`/`SYNC_RESULT` (UC-03).

### 3.8. Che secret trong log (chống T8, T10)

- **Không log credential** ✅: `AuthService` không log password/credential; `Argon2idHasher` wipe `char[]`; `AlertService`/`FanoutEngine` chỉ log `requestId`/`deliveryId`/`alertId` (định danh nghiệp vụ, không phải secret).
- **Audit tối thiểu PII** ✅ (`AuditService`): ghi `actorId, action, scope, outcome, correlationId, payload` — payload là metadata nghiệp vụ (requestId/alertId/priority/targets), không chứa mật khẩu.
- 🟡 **Masking filter tập trung** (NFR-016): 🔧 cần bộ lọc log (logback pattern/layout) che mọi trường nhạy cảm (password, token, credential, secret) trước khi ghi; hiện chưa có filter tập trung — việc không log secret đang dựa vào kỷ luật từng call-site.
- **T-28 log scan** (TEST-PLAN): quét log tìm secret/password/raw token phải cho kết quả sạch; bằng chứng thật cần lưu runId.
- **Báo cáo incident phân quyền** (NFR-016): chỉ role được phép mới xem báo cáo chứa thông tin nhạy cảm.

### 3.9. Lỗi đã sanitize (chống T8, information disclosure)

- **Typed error codes** (PROTOCOL §7.3): `AUTH_REQUIRED, ACCESS_DENIED, INVALID_SCOPE, INVALID_FRAME, INVALID_VERSION, MESSAGE_TOO_LARGE, RATE_LIMITED, CONFLICT, EXPIRED, SERVER_BUSY, INTERNAL_ERROR` — kèm `correlationId` để trace mà không lộ nội bộ (NFR-017).
- **Payload lỗi chặt** ✅ (`Json.BadPayload`): thiếu/sai kiểu trường → `BadPayload` với tên trường, map sang `ERROR/BAD_PAYLOAD` trên wire.
- 🟡 **Khoảng trống cần xử lý**: `AlertService.publish/update/cancel` hiện trả `CommandOutcome.error("INTERNAL", "publish failed: " + e.getMessage())` — **lộ message exception nội bộ** (có thể chứa chi tiết SQL/đường dẫn). 🔧 Gateway phải **sanitize**: map sang `INTERNAL_ERROR` + thông điệp chung + `correlationId`, ghi chi tiết đầy đủ **chỉ vào log server** (không gửi client), đúng NFR-017.
- **Không lộ stack trace/đường dẫn/SQL ra client**; lỗi xác thực không phân biệt "user không tồn tại" vs "sai mật khẩu" (tránh user enumeration) — `AuthService` trả `Optional.empty()` cho cả hai.

### 3.10. Audit, tamper-evidence & các kiểm soát bổ sung

- **Audit append-only** ✅ (`AuditService` + `audit_events`): mọi thao tác rủi ro cao (PUBLISH, UPDATE, CANCEL, DENIED, RETRY, DELIVERY_FAILED, SUPERSEDED, DEDUP_SUPPRESS, ALERT_FULLY_ACKED) ghi actor/scope/outcome/correlationId. Best-effort: lỗi ghi audit được log nhưng **không phá đường lệnh** (cần cân nhắc đánh đổi này khi triển khai thật — xem §7).
- **Dual operator approval** (blueprint §9, 🔧 tùy chọn): cho workflow training không khẩn cấp; demo có thể yêu cầu 2 phê duyệt trước khi publish kịch bản mô phỏng tới tập target rộng. **Không** phải quy tắc cho cảnh báo khẩn cấp thật nếu chưa có quy trình an toàn được phê duyệt.
- **Tamper-evidence hash chain** (blueprint §9, stretch 🔧): thêm `previous_hash` + `event_hash` vào incident event log; phát hiện sửa đổi trái phép khi **chain head được neo ngoài DB**. **Giới hạn**: hash chain *đơn lẻ* không phải immutable, không chống được administrator viết lại toàn bộ DB.
- **Connection limits / rate limiting** (blueprint §9, 🔧): giới hạn kết nối đồng thời, quota frame/giây, `RATE_LIMITED`/`SERVER_BUSY` chống DoS.
- **PII minimization** (blueprint §9): chỉ thu thập dữ liệu cá nhân tối thiểu cần cho quy trách nhiệm ACK_USER; retention bounded (NFR-012).
- **Credential rotation/revocation** (FR-003): thiết bị/user có thể rotate credential và disable; `enabled=0` chặn xác thực ngay.

---

## 4. Ma trận RBAC (role × thao tác × scope)

### 4.1. Vai trò trong mã nguồn

`aegis.protocol.Role` ✅ gồm **5** giá trị: `SYSTEM_ADMIN, SECURITY_OPERATOR, FLOOR_MANAGER, EMPLOYEE, DEVICE`.

**Ánh xạ persona blueprint §1.1 → Role:**

| Persona (blueprint) | Role trong mã | Ghi chú |
|---|---|---|
| System Administrator | `SYSTEM_ADMIN` | Quản lý tòa nhà/thiết bị/user/grant/retention/policy. |
| Emergency Operations Controller | `SECURITY_OPERATOR` (grant toàn building) hoặc `SYSTEM_ADMIN` | Điều phối incident/alert theo tòa/tầng. |
| Security Operator | `SECURITY_OPERATOR` | Phát/thu hồi trong phạm vi được cấp; không tự đổi quyền. |
| Floor Manager | `FLOOR_MANAGER` | Chỉ phát tới tầng thuộc scope; không mở rộng target bằng payload tùy ý. |
| Employee | `EMPLOYEE` | Nhận cảnh báo + ACK_USER; xác thực cá nhân nếu quy trách nhiệm. |
| Device Agent | `DEVICE` | Định danh thiết bị, lưu cục bộ, ACK_RECEIVED, heartbeat, sync. |
| Instructor/Observer | *(không phải Role enum)* | Chế độ demonstration **chỉ đọc**, không phát alert thật. |

### 4.2. Ma trận thao tác

Quy ước ô: **✓** = được phép; **✗** = từ chối (deny-by-default); **S** = được phép *trong scope* (xem §4.3). Các lệnh server→client (`ALERT_PUSH`, `CANCEL_PUSH`, `COMMAND_RESULT`, `SYNC_RESULT`, `SERVER_HELLO`, `AUTH_RESULT`, `HEARTBEAT_ACK`, `STATUS_*`) không phải lệnh client khởi tạo nên không liệt kê như quyền client.

| Nhóm thao tác (MessageType) | SYSTEM_ADMIN | SECURITY_OPERATOR | FLOOR_MANAGER | EMPLOYEE | DEVICE | Scope / điều kiện |
|---|:--:|:--:|:--:|:--:|:--:|---|
| Quản trị user/device enrolment, role grants, retention/policy | ✓ | ✗ | ✗ | ✗ | ✗ | Toàn hệ thống; không tự nâng quyền chính mình |
| `INCIDENT_CREATE`, `INCIDENT_TRANSITION` | ✓ | S | S | ✗ | ✗ | Trong building (SECOPS) / tầng (FM) được cấp — *thiết kế* |
| `ALERT_CREATE`, `ALERT_PUBLISH` | ✓ | S | S | ✗ | ✗ | **`ScopedRBAC.canPublish` ✅**; scope building/floor từ DB |
| `ALERT_UPDATE`, `ALERT_CANCEL` | ✓ | S | S | ✗ | ✗ | Phải đúng scope của alert đang sửa/hủy — *router kiểm tra* |
| `STATUS_SUBSCRIBE` (dashboard đọc) | ✓ | S | S | ✗ | ✗ | Đọc trong scope; Instructor/Observer chế độ demo chỉ đọc |
| `ACK_RECEIVED` | ✗ | ✗ | ✗ | ✗ | ✓ | Chỉ delivery của **chính thiết bị** đã xác thực |
| `ACK_USER` | ✗ | ✗ | ✗ | ✓ | ✓ | Cử chỉ người dùng; identity người thật nếu quy trách nhiệm |
| `ACK_CANCEL` | ✗ | ✗ | ✗ | ✗ | ✓ | Xác nhận hủy tới đúng thiết bị |
| `SYNC_REQUEST` | ✗ | ✗ | ✗ | ✗ | ✓ | Chỉ trạng thái của chính thiết bị |
| `HEARTBEAT` | ✓ | ✓ | ✓ | ✗ | ✓ | Keepalive phiên đã xác thực |
| `AUTH_OPERATOR` | ✓ | ✓ | ✓ | ✓ | ✗ | Xác thực operator (Argon2id) |
| `AUTH_DEVICE` | ✗ | ✗ | ✗ | ✗ | ✓ | Xác thực thiết bị (Argon2id) |
| `HELLO`, `LOGOUT` | ✓ | ✓ | ✓ | ✓ | ✓ | Vòng đời phiên |

> **Trạng thái thực thi:** `MessageRouter` là auth gate duy nhất: từ chối mọi message ứng dụng trước `AUTH_*`, tách device/operator, gọi `ScopedRBAC.canPublish` với grant **nạp lại từ DB mỗi lệnh có quyền** (revoke có hiệu lực ngay). `RbacService` chỉ nạp grant `PUBLISH`. Ma trận dưới đây là chính sách đã chạy end-to-end cho các ô publish/status; các tính năng stretch (dual approval, hash-chain) vẫn là thiết kế.

### 4.3. Quy tắc scope theo role (nguồn: DB `role_grants`)

| Role | Scope publish | Cơ chế |
|---|---|---|
| `SYSTEM_ADMIN` | Mọi building/floor/zone | `canPublish → true` |
| `SECURITY_OPERATOR` | Building được cấp (mọi tầng của building đó) | `Grants.coversBuilding(buildingId)`; grant có `floor_id=NULL` → toàn building |
| `FLOOR_MANAGER` | Chỉ tầng được cấp thuộc building được cấp | `Grants.coversFloor(buildingId, floorId)`; grant có `floor_id` cụ thể |
| `EMPLOYEE` | Không publish | `canPublish → false` |
| `DEVICE` | Không publish | `canPublish → false` |

- `Grants.none()` (rỗng) → mọi role scoped bị từ chối.
- `RbacService.canPublish` thay `floorId=null` bằng `BUILDING_WIDE_FLOOR="*"` (không khớp floor grant cụ thể) → chỉ SYSTEM_ADMIN hoặc grant toàn building mới qua.
- **Target snapshot** resolve từ DB (`TargetResolver.resolveBuilding/resolveFloor/resolveZone`), không từ payload client; tập target rỗng → `NO_TARGETS` (lỗi rõ, không phát thầm lặng).

---

## 5. Ánh xạ kiểm soát ↔ NFR/FR ↔ kiểm thử

| Kiểm soát | NFR/FR | Test (TEST-PLAN) | Chaos |
|---|---|---|---|
| TLS truststore verify, no trust-all | NFR-009, FR-001 | T-05, T-06 | C09 |
| RBAC scope mỗi lệnh, deny-by-default | NFR-008, FR-006 | T-07 | C08 |
| Target snapshot bất biến | FR-009 | T-08 | — |
| Argon2id, disabled account | FR-003 | T-06 | C09 |
| requestId idempotency | FR-016 | T-09, T-10 | C03 |
| Frame 64KiB + depth + strict parse | NFR-010, FR-002 | T-01..04 | C10 |
| Bounded queue + slow-client | NFR-011 | T-23 | C16, C04 |
| Session expiry / reconnect / tombstone | FR-004, FR-015, FR-017 | T-15..17 | C05, C12 |
| Mask secrets in logs | NFR-016 | T-28 | — |
| Sanitized errors | NFR-017 | T-01..04 (ERROR) | C10 |
| UDP spoof / multicast untrusted | FR-031, FR-032 | T-24, T-25 | C13, C14 |
| Audit append-only | FR-020 | T-07 (deny audited) | C08 |

---

## 6. Hướng dẫn tạo truststore self-signed bằng `keytool` (demo)

> Mục đích: tạo danh tính TLS self-signed cho gateway EBSP (cổng 9443) và truststore để client xác thực server. **Chỉ dùng cho demo/lab.** Script tương đương đã có: `scripts/gen-server-cert.sh`.

### 6.1. Cách nhanh (script có sẵn)

```bash
cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs
# tham số: [output-dir=certs] [store-password=aegis-demo]
bash scripts/gen-server-cert.sh certs aegis-demo
```

Sinh ra:
- `certs/aegis-server.p12` — PKCS12 **keystore** (private key + cert server, `SAN=localhost,127.0.0.1`);
- `certs/aegis-server.crt` — chứng chỉ **PEM** (để client import);
- `certs/aegis-truststore.p12` — PKCS12 **truststore** chỉ chứa cert server.

Script bỏ qua bước tạo key nếu `aegis-server.p12` đã tồn tại (muốn tạo lại thì xóa file).

### 6.2. Thủ công bằng `keytool` (3 bước, đúng tham số script)

**Bước 1 — Sinh cặp khóa + chứng chỉ self-signed (keystore server):**

```bash
keytool -genkeypair \
  -alias aegis-server \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=localhost, OU=AEGIS-EBS, O=CMP180, L=HoChiMinh, C=VN" \
  -ext "SAN=dns:localhost,ip:127.0.0.1" \
  -keystore certs/aegis-server.p12 -storetype PKCS12 \
  -storepass aegis-demo -keypass aegis-demo
```

**Bước 2 — Xuất chứng chỉ ra PEM (để phân phối cho client):**

```bash
keytool -exportcert -alias aegis-server \
  -keystore certs/aegis-server.p12 -storetype PKCS12 -storepass aegis-demo \
  -rfc -file certs/aegis-server.crt
```

**Bước 3 — Import cert vào truststore phía client (chỉ tin cert này):**

```bash
keytool -importcert -noprompt -alias aegis-server \
  -file certs/aegis-server.crt \
  -keystore certs/aegis-truststore.p12 -storetype PKCS12 -storepass aegis-demo
```

**Kiểm tra truststore:**

```bash
keytool -list -v -keystore certs/aegis-truststore.p12 -storetype PKCS12 -storepass aegis-demo
# Kỳ vọng: 1 entry, alias aegis-server, Owner CN=localhost, SAN dns:localhost + ip:127.0.0.1
```

### 6.3. Cấu hình client dùng truststore

`aegis.client.ClientConfig` ✅ nhận cờ:

```bash
java ... aegis.client.ClientApp \
  --host localhost --port 9443 \
  --device-id DEV-A-F07-C-01 --device-secret <credential> \
  --truststore certs/aegis-truststore.p12 --truststore-password aegis-demo \
  --inbox aegis-client-inbox.db
```

> ⚠️ **Mật khẩu phải khớp.** Mặc định `ClientConfig.defaults()` là `truststore.p12` / `"changeit"`, trong khi script sinh cert dùng `"aegis-demo"`. Phải truyền `--truststore` và `--truststore-password` đúng file/mật khẩu đã tạo, nếu không handshake sẽ fail. `TlsSupport.storeType` chọn `JKS` nếu đuôi `.jks`/`.keystore`, ngược lại `PKCS12`.

### 6.4. Demo 2 máy LAN thật (multicast)

Chứng chỉ mặc định chỉ có `SAN=dns:localhost,ip:127.0.0.1` → **không** xác thực được khi client máy khác kết nối qua IP LAN. Phải sinh lại cert với SAN chứa hostname/IP thật của server:

```bash
keytool -genkeypair -alias aegis-server \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=aegis-server.local, OU=AEGIS-EBS, O=CMP180, L=HoChiMinh, C=VN" \
  -ext "SAN=dns:aegis-server.local,ip:192.168.1.10" \
  -keystore certs/aegis-server.p12 -storetype PKCS12 \
  -storepass <mật-khẩu-mạnh> -keypass <mật-khẩu-mạnh>
# rồi exportcert + importcert như §6.2, phân phối aegis-server.crt cho client máy 2
```

Client máy 2: `--host 192.168.1.10 --truststore <file đã import> --truststore-password <mật-khẩu>`. Trong lab, `DiscoveryClient.discoverTrusted()` còn kiểm tra `CN == serverId` và nonce echo (§3.1).

### 6.5. Giới hạn của truststore self-signed (BẮT BUỘC nêu khi bảo vệ)

1. **Không phải CA công cộng.** Chứng chỉ self-signed không được browser/OS tin mặc định; chỉ hoạt động vì client **chủ động pin** cert vào truststore riêng. Không có chuỗi tin cậy tới root CA công cộng.
2. **Không thu hồi công khai.** Không OCSP/CRL; thu hồi = xóa cert khỏi truststore + sinh lại. Không có cơ chế revocation phân tán.
3. **Mật khẩu demo công khai.** `aegis-demo` (script) và các secret demo (`*-demo-secret`, `device-demo-secret` dùng chung fleet) nằm trong mã nguồn → **chỉ cho lab**, tuyệt đối không dùng production. Production phải dùng mật khẩu mạnh, secret riêng từng thiết bị, quản lý khóa tập trung (KMS/HSM).
4. **CN/SAN demo hẹp.** Mặc định `CN=localhost`, SAN loopback → không dùng được cho LAN thật nếu không sinh lại (§6.4).
5. **Validity ngắn hạn demo** (365 ngày) và RSA 2048 là mức tối thiểu chấp nhận được cho lab; production nên xem xét khóa mạnh hơn/ECDSA và quy trình rotate.
6. **Không thay thế hạ tầng PKI thật.** Đây là mô phỏng cơ chế *xác thực server bằng truststore* để minh họa NFR-009 và chống T1; không tuyên bố hệ thống đạt chuẩn an toàn công nghiệp.
7. **Shared device secret là đánh đổi demo.** `ServerStore.seedWorld` hash một credential dùng chung cho toàn fleet để tiện lab; điều này **phá vỡ** tính danh tính riêng từng thiết bị (T2) — production phải cấp credential riêng + rotate/revoke từng thiết bị (FR-003).

---

## 7. Checklist OWASP

Trạng thái: **Đạt** ✅ · **Một phần/demo** 🟡 · **Thiết kế (chờ gateway)** 🔧 · **Chưa** ✗.

### 7.1. OWASP Authorization Cheat Sheet

| # | Khuyến nghị OWASP | Hiện trạng AEGIS | Trạng thái | Bằng chứng |
|---|---|---|---|---|
| A1 | Enforce least *** | Role scoped; EMPLOYEE/DEVICE không publish; grant tối thiểu | ✅ | `ScopedRBAC`, `Grants` |
| A2 | Deny by default | `Grants.none()` → từ chối; role không khớp → false | ✅ | `ScopedRBAC.canPublish` |
| A3 | Kiểm tra phân quyền tại MỖI request/command | Router kiểm type theo role trước service | 🔧 | `AlertService` giả định router; gateway chưa có |
| A4 | Chống leo thang ngang/dọc (horizontal/vertical) | Scope building/floor từ DB; không tự đổi quyền | ✅ 🔧 | `RbacService.grantsFor`; router |
| A5 | Không tin client-supplied role/scope | Server suy scope từ DB, bỏ qua metadata client | ✅ | blueprint §7.2; `TargetResolver` |
| A6 | Ghi log vi phạm phân quyền (attributable) | Audit DENIED + actor/scope/correlationId | ✅ 🔧 | `AuditService`; router ghi DENIED |
| A7 | Fail-safe khi kiểm tra lỗi (terminate safely) | `Objects.requireNonNull`; null grants → none | ✅ | `ScopedRBAC`, `Grants` |
| A8 | Test deny-by-default + abnormal conditions | `ScopedRBACTest` (T-07, C08) | ✅ | 73 test core |
| A9 | Tách authentication ≠ authorization | `AuthService` (identity) ≠ `RbacService` (scope) | ✅ | hai service riêng |

### 7.2. OWASP Session Management Cheat Sheet

| # | Khuyến nghị OWASP | Hiện trạng AEGIS | Trạng thái | Bằng chứng |
|---|---|---|---|---|
| S1 | Session ID entropy ≥ 64 bit, CSPRNG | UUIDv4 = 122-bit entropy (`SecureRandom`) | ✅ | `Json.uuid()` |
| S2 | Session ID length ≥ 128 bit, nội dung vô nghĩa | UUID 128-bit, không chứa PII | ✅ | `Json.uuid()` |
| S3 | Session ID không là bằng chứng phân quyền ngoài kết nối xác thực | Kiểm role/scope mỗi lệnh trên phiên | ✅ 🔧 | PROTOCOL §7.4; router |
| S4 | Hết hạn idle + absolute | Heartbeat suspect (10s/3 miss) ✅; TTL tuyệt đối chưa có | 🟡 🔧 | `SessionRegistry.sweep`; FR-004 |
| S5 | Renew session ID khi đổi quyền / chống fixation | Reconnect tạo phiên mới; renew khi privilege change chưa rõ | 🔧 | UC-03; gateway |
| S6 | Thu hồi phiên (logout/revoke) | `LOGOUT`, `closeAll`, `deviceOnline` đóng phiên cũ | ✅ 🔧 | `SessionRegistry`; FR-004 |
| S7 | Bảo vệ session store khỏi lộ | Phiên trong RAM server; không persist raw token | ✅ | `SessionRegistry` (ConcurrentHashMap) |
| S8 | Không lộ session ID qua URL/log | sessionId không log; kênh TLS | 🟡 🔧 | masking filter §3.8 |
| S9 | TLS bảo vệ kênh trao đổi session | TLS 9443, truststore verify | ✅ | `TlsSupport`; NFR-009 |

### 7.3. OWASP Password Storage Cheat Sheet

| # | Khuyến nghị OWASP | Hiện trạng AEGIS | Trạng thái | Bằng chứng |
|---|---|---|---|---|
| P1 | Không lưu plaintext | Chỉ lưu Argon2id hash | ✅ | `users.password_hash`, `devices.credential_hash` |
| P2 | Dùng Argon2id (memory-hard) | `Argon2Factory.Argon2Types.ARGON2id` | ✅ | `Argon2idHasher` |
| P3 | Tham số tối thiểu m=19 MiB, t=2, p=1 | Demo m=16 MiB, t=2, p=1 → **dưới ngưỡng** | 🟡 | `ServerConfig` (nâng ≥19456 KiB khi production) |
| P4 | Salt duy nhất mỗi mật khẩu | Argon2id tự sinh salt nhúng `$argon2id$` | ✅ | `Argon2idHasher.hash` |
| P5 | Không dùng hash nhanh (SHA-256) cho mật khẩu | SHA-256 chỉ cho payload_hash idempotency | ✅ | `AlertService.sha256` (không phải password) |
| P6 | Wipe secret khỏi bộ nhớ | `wipeArray(char[])` trong finally | ✅ | `Argon2idHasher` |
| P7 | Verify an toàn, không crash với hash lỗi | `$argon2id$` prefix check; malformed → false | ✅ | `Argon2idHasher.verify` |
| P8 | Pepper (defense-in-depth, tùy chọn) | Chưa dùng | ✗ | khuyến nghị thêm khóa ngoài DB |
| P9 | Rotate/revoke credential | `enabled=0` chặn auth; rotate qua cập nhật hash | ✅ 🟡 | `AuthService`; FR-003 |
| P10 | Không log mật khẩu | Không log credential; wipe | ✅ 🟡 | §3.8; T-28 |

---

## 8. Hạn chế bảo mật & việc chưa hoàn thiện (trung thực)

1. 🔧 **Rate limiting/quota frame và read timeout ở tầng wire chưa hiện thực đầy đủ** (`TlsGateway` có auth gate + pre-auth TTL; chưa có quota frame/giây hoặc `RATE_LIMITED`).
2. 🔧 **Hết hạn phiên tuyệt đối (absolute TTL)** và renew sessionId khi đổi quyền chưa hiện thực (mới có heartbeat/suspect).
3. 🟡 **Tham số Argon2id demo (16 MiB) dưới khuyến nghị OWASP (19 MiB).** Phải nâng khi production.
4. ✅ **`StreamReadConstraints` đã cấu hình tường minh** trong `FrameCodecImpl` (độ sâu 32, chuỗi tối đa 64 KiB) cộng hard cap frame 64 KiB.
5. ✅ **Lỗi `INTERNAL` đã sanitize** tại `MessageRouter`: chi tiết chỉ vào log server, wire nhận thông điệp chung + correlation id.
6. 🟡 **Chưa có masking filter log tập trung**; việc không lộ secret dựa vào kỷ luật call-site + T-28 scan.
7. ✅ **CONFLICT khi requestId trùng nhưng payload khác** đã trả về: `AlertService` so sánh `payload_hash` canonical trên cả đường lookup và unique-race.
8. 🟡 **Credential demo công khai + device secret dùng chung** → chỉ lab, không production.
9. 🔧 **Dual-approval, hash-chain tamper-evidence, pepper, connection/rate limits** là thiết kế/stretch, chưa hiện thực.
10. **Self-signed TLS** không phải CA công cộng, không OCSP/CRL (§6.5).
11. **Single-node** → không HA; audit best-effort có thể mất sự kiện nếu ghi audit lỗi (đánh đổi cần xem lại).
12. **Không phải hệ thống an toàn sinh mạng được chứng nhận**; mọi kịch bản là SIMULATION/TRAINING.

---

## 9. Tài liệu tham khảo

1. `Plan/AEGIS_MASTER_BLUEPRINT.md` — §9 (Security & privacy), §7 (EBSP/2.0), §3 (NFR-008..018), §5 (invariants), §6 (architecture), §12 (chaos), §16 (Q&A).
2. `docs/PROTOCOL.md` — EBSP/2.0 wire contract (framing, envelope, idempotency, message catalog, TLS primary / multicast advisory).
3. OWASP **Authorization** Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
4. OWASP **Session Management** Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
5. OWASP **Password Storage** Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
6. IETF **RFC 9106** — Argon2 Inputs and Outputs (tham số Argon2id).
7. IETF **RFC 8085** — UDP Usage Guidelines (multicast/discovery không tin cậy).
8. Oracle Java SE — `SSLSocket` / `SSLServerSocket` / `TrustManagerFactory` / `KeyStore`.
9. OASIS **CAP 1.2** — metadata severity/urgency/certainty (tách biệt priority broadcast).
10. Mã nguồn đối chiếu: `aegis.protocol.{Protocol,FrameCodecImpl,Role,MessageType,Priority}`, `aegis.core.{ScopedRBAC,Grants,AckLedger,DedupIndex,RetryPolicy,PriorityScheduler,TargetResolver}`, `aegis.server.{AuthService,Argon2idHasher,RbacService,SessionRegistry,ClientSession,BoundedWriterQueue,FanoutEngine,AlertService,AuditService,ServerStore}`, `aegis.client.{TlsSupport,ClientConfig,InboxStore}`, `aegis-network-lab` (lab-guide.md), `scripts/gen-server-cert.sh`.
