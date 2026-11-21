# AEGIS-EBS — Architecture Decision Records (ADR)

**Học phần:** CMP180 — Lập trình mạng máy tính · **Đồ án:** AEGIS Emergency Broadcast System (Showcase Edition)
**Loại tài liệu:** Hồ sơ quyết định kiến trúc (Architecture Decision Records) — đồ án capstone.
**Phạm vi:** Ghi lại các quyết định kiến trúc nền tảng của repository `aegis-ebs`, mỗi quyết định gồm bốn phần *Bối cảnh (Context) — Quyết định (Decision) — Hệ quả (Consequences) — Phương án thay thế (Alternatives)*, kèm ánh xạ vào mã nguồn hiện hành theo cú pháp `đường/dẫn/file.java:dòng`.

> ⚠️ **SIMULATION / TRAINING.** Hệ thống là nền tảng mô phỏng học thuật, không phải hệ thống báo cháy/sơ tán được chứng nhận, không thay thế thiết bị an toàn sinh mạng. Mọi tham số demo được ghi nhận trung thực; không tuyên bố đạt chuẩn công nghiệp khi chưa có bằng chứng kiểm định.

**Quy ước trạng thái:** `Chấp nhận (Accepted)` — quyết định đã được hiện thực trong mã nguồn và kiểm thử. Tài liệu này chỉ mô tả quyết định; đặc tả wire contract xem [`docs/PROTOCOL.md`](PROTOCOL.md), kiến trúc tổng thể xem [`docs/ARCHITECTURE.md`](ARCHITECTURE.md), mô hình bảo mật xem [`docs/SECURITY.md`](SECURITY.md).

**Nguồn quyết định:** `Plan/AEGIS_MASTER_BLUEPRINT.md` (blueprint v2.0) §0, §5–§10, §16; mã nguồn các module `aegis-protocol`, `aegis-core`, `aegis-persistence`, `aegis-server`, `aegis-client`, `aegis-network-lab`, `aegis-simulator`, `aegis-dashboard`.

---

## Mục lục

| ADR | Tiêu đề | Trạng thái |
|---|---|---|
| [ADR-001](#adr-001) | TCP/TLS làm kênh truyền chính thay vì MQTT/broker | Chấp nhận |
| [ADR-002](#adr-002) | JSON length-prefix thay vì Java Serializable | Chấp nhận |
| [ADR-003](#adr-003) | SQLite WAL + transactional outbox | Chấp nhận |
| [ADR-004](#adr-004) | ACK hai tầng RECEIVED / USER | Chấp nhận |
| [ADR-005](#adr-005) | Multicast advisory hint + xác minh TLS | Chấp nhận |
| [ADR-006](#adr-006) | Modular monolith | Chấp nhận |
| [ADR-007](#adr-007) | Argon2id cho lưu trữ credential | Chấp nhận |
| [ADR-008](#adr-008) | Three.js 3D digital twin | Chấp nhận |

---

<a id="adr-001"></a>
## ADR-001 — TCP/TLS làm kênh truyền chính thay vì MQTT/broker

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §0.2, §6.1, §7, §16 (câu "Why not MQTT/Firebase?"); ADR-002, ADR-005.

### Bối cảnh (Context)

Trục đánh giá của học phần CMP180 là **tự xây dựng giao thức tầng ứng dụng trên TCP/UDP** bằng `Socket`/`ServerSocket`, `DatagramSocket`, `MulticastSocket` (blueprint §0, §17 "Course-derived"). Một nền tảng phân phối cảnh báo khẩn cấp đồng thời đòi hỏi kênh truyền **có xác thực, tin cậy, kiểm soát được thứ tự và ngữ nghĩa nhận**. Các lựa chọn phổ biến trong công nghiệp — MQTT broker, Firebase Cloud Messaging, hay message broker nói chung — cung cấp sẵn độ tin cậy nhưng **che khuất cơ chế socket** mà môn học yêu cầu sinh viên tự hiện thực, đồng thời tạo phụ thuộc vào một thành phần hạ tầng thứ ba.

Hệ thống chạy trên **LAN demo cô lập, một node server duy nhất** (`docs/ARCHITECTURE.md` §1, §3). Không có yêu cầu tích hợp cloud push hay liên mạng diện rộng.

### Quyết định (Decision)

Chọn **TCP + TLS làm kênh xác thực duy nhất** cho toàn bộ command plane và delivery plane, với giao thức ứng dụng tự định nghĩa **EBSP/2.0** chạy trên cổng `9443`:

- Gateway dùng `SSLServerSocket` tạo từ `SSLContext` của keystore PKCS12 (`aegis-server/src/main/java/aegis/server/TlsGateway.java:74-78`); mỗi kết nối được chấp nhận giao cho một reader chạy trên **virtual thread** (`TlsGateway.java:108`, executor `Executors.newVirtualThreadPerTaskExecutor()` tại `aegis-server/src/main/java/aegis/server/AegisServer.java:85`).
- Cổng TLS mặc định `tlsPort=9443` (`aegis-server/src/main/java/aegis/server/ServerConfig.java:56`).
- **Auth gate bắt buộc:** mọi message ứng dụng ngoài `HELLO`/`AUTH_*`/`HEARTBEAT`/`LOGOUT` bị từ chối `NOT_AUTHENTICATED` khi phiên chưa xác thực (`aegis-server/src/main/java/aegis/server/MessageRouter.java:94-98`).
- Hằng số giao thức `VERSION=2` (`aegis-protocol/src/main/java/aegis/protocol/Protocol.java:3`).
- UDP discovery (`9999`) và multicast hint (`239.10.10.10:9998`) **không phải kênh chính**; chúng chỉ mang tính gợi ý và phải được xác minh lại qua TLS (xem ADR-005).

Không sử dụng MQTT broker, Firebase, hay bất kỳ message broker nào làm kênh truyền tải chính.

### Hệ quả (Consequences)

**Tích cực:**
- Toàn bộ cơ chế framing, xác thực, ACK, retry, dedup, recovery do nhóm tự hiện thực → đáp ứng trực tiếp mục tiêu học tập và tiêu chí chấm "Java Socket/TCP/UDP fundamentals demonstrably self-implemented" (blueprint §15).
- Kiểm soát trọn vẹn ngữ nghĩa độ tin cậy: ACK hai tầng (ADR-004), retry có giới hạn, transactional outbox (ADR-003) — những thứ broker mặc định không bộc lộ theo đúng mô hình nghiệp vụ.
- TLS cung cấp xác thực server + bảo mật kênh; truststore pinned chống giả mạo LAN (ADR-005, `docs/SECURITY.md` §3.1).
- Virtual thread cho phép hàng nghìn kết nối đồng thời mà không cần pool luồng nền tảng lớn.

**Tiêu cực / đánh đổi:**
- Phải tự xây dựng và kiểm thử độ tin cậy (retry, dedup, recovery) — khối lượng công việc và rủi ro sai sót cao hơn dùng broker.
- **Single point of failure** được thừa nhận tường minh: crash recovery trên một node **không tương đương high availability** (blueprint §0.2; `docs/ARCHITECTURE.md` §1 nguyên tắc 2).
- Chi phí handshake TLS và quản lý chứng chỉ self-signed (xem ADR-005, `docs/SECURITY.md` §6.5).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn làm kênh chính |
|---|---|
| **MQTT broker** (Mosquitto/EMQX) | Che khuất cơ chế socket tự xây dựng; QoS 1/2 của MQTT không ánh xạ trực tiếp sang ACK nghiệp vụ hai tầng; thêm phụ thuộc hạ tầng. Giữ làm **tài liệu tham khảo** (blueprint §16). |
| **Firebase / cloud push** | Phụ thuộc dịch vụ ngoài, không tự hiện thực giao thức; không phù hợp LAN cô lập và mục tiêu môn học. |
| **TCP thuần không TLS** | Không xác thực server, không bảo mật kênh → dễ nghe lén/giả mạo (vi phạm NFR-009). |
| **UDP làm kênh chính** | Không tin cậy, không thứ tự, không xác thực → không phù hợp phân phối cảnh báo khẩn cấp (RFC 8085). |

---

<a id="adr-002"></a>
## ADR-002 — JSON length-prefix thay vì Java Serializable

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §7.1; `docs/PROTOCOL.md`; ADR-001.

### Bối cảnh (Context)

EBSP/2.0 cần một **wire contract** rõ ràng ở mức byte, độc lập ngôn ngữ (server/client Java, dashboard JavaScript), có thể validate chặt và **chặn tài nguyên** trước dữ liệu dị dạng. Java `Serializable`/`ObjectOutputStream` là lựa chọn "nhanh" trong hệ sinh thái Java nhưng: (i) chỉ đọc được bởi JVM, (ii) mang rủi ro bảo mật nghiêm trọng khi deserialize gadget chain, (iii) không có schema tường minh để validate, (iv) khó kiểm soát kích thước trước khi cấp phát.

Môn học yêu cầu hiểu **I/O byte stream** và framing thủ công (`DataInputStream.readFully`, xử lý partial/coalesced read).

### Quyết định (Decision)

Định nghĩa framing **`uint32 BE length L` + `L bytes strict UTF-8 JSON`**, với `L ∈ [1, 65536]`:

- Hằng số: `MAX_FRAME_BYTES = 65536`, `PREFIX_BYTES = 4` (`aegis-protocol/src/main/java/aegis/protocol/Protocol.java:4-5`).
- **Từ chối oversize TRƯỚC khi cấp phát body:** `checkLength(len)` được gọi ngay sau khi đọc 4 byte header, trước `readFully(body)` (`aegis-protocol/src/main/java/aegis/protocol/FrameCodecImpl.java:69-71`, hàm `checkLength` tại `:83-88`; phía encode/decode cũng kiểm tại `:39`, `:53`).
- `readFully` cho header và body → xử lý partial read; coalesced frames được tiêu thụ đúng, không over-read (`FrameCodecImpl.java:64-73`).
- **Strict UTF-8:** `CharsetDecoder.onMalformedInput(REPORT).onUnmappableCharacter(REPORT)` (`FrameCodecImpl.java:105-106`).
- **JSON chặt:** chỉ chấp nhận object, cấm trailing tokens (`FrameCodecImpl.java:120`); `version` phải là integer == 2 (`:128-131`); `type` thuộc enum `MessageType`.
- Envelope: `{version:2, type, messageId, correlationId, sentAt(UTC ISO-8601), payload}` (`docs/PROTOCOL.md:5`).

### Hệ quả (Consequences)

**Tích cực:**
- Độc lập ngôn ngữ: dashboard Node/JS đọc được cùng contract (`aegis-dashboard/src/data.js`).
- Validate tường minh từng tầng (length → UTF-8 → JSON → version → type → role) → chống malformed frame (threat T6, `docs/SECURITY.md` §3.5).
- Bộ nhớ bị chặn: frame quá khổ không bao giờ được cấp phát (NFR-010).
- Framing thủ công minh họa đúng kỹ năng I/O byte stream của môn học.

**Tiêu cực / đánh đổi:**
- JSON text tốn băng thông hơn binary (Protobuf); chi phí parse cao hơn deserialize nhị phân.
- Giới hạn độ sâu JSON hiện dựa vào mặc định Jackson `StreamReadConstraints` (maxNestingDepth=1000) + hard cap 64 KiB; **chưa cấu hình tường minh chặt hơn** — ghi nhận là khoảng trống 🟡 (`docs/SECURITY.md` §3.5, §8 mục 4).
- Phải tự xử lý partial/coalesced read và strict UTF-8 (đã hiện thực + kiểm thử `FrameCodecStreamTest`, `FrameCodecValidationTest`).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **Java `ObjectOutputStream`/Serializable** | Chỉ JVM đọc được; rủi ro deserialization gadget; không schema tường minh; khó chặn kích thước trước cấp phát. |
| **Protobuf / FlatBuffers** | Hiệu quả nhị phân nhưng thêm toolchain/codegen, lệch trọng tâm tự xây dựng giao thức của môn học. |
| **Newline-delimited JSON** | Mơ hồ khi payload chứa newline; không có length tường minh để chặn oversize trước cấp phát. |
| **Fixed-size struct** | Kém linh hoạt cho payload nghiệp vụ đa dạng (alert, incident, sync). |

---

<a id="adr-003"></a>
## ADR-003 — SQLite WAL + transactional outbox

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §8.1, §8.3, §16 (câu "Why SQLite?"); ADR-004; `docs/ERD.md`.

### Bối cảnh (Context)

Nguyên tắc **durable-first**: mọi trạng thái alert/target/delivery phải bền vững **trước khi** phát ra mạng, để server crash không làm mất cảnh báo đã commit. Hệ thống một node, tải demo (100–500 client), cần transaction đáng tin cậy mà không muốn vận hành một DB server riêng. Đồng thời cần cơ chế **recovery sau crash** dựng lại lịch phân phối mà không tạo alert trùng.

SQLite là DB nhúng, không cần tiến trình server, hỗ trợ transaction ACID; chế độ **WAL** cho phép đọc song song với ghi (nhưng chỉ một writer commit tại một thời điểm — điểm nghẽn được thừa nhận, blueprint §8.1).

### Quyết định (Decision)

1. **SQLite WAL + pragma kỷ luật mỗi kết nối** (`aegis-persistence/src/main/java/aegis/persistence/Db.java`):
   - Thứ tự `PRAGMA foreign_keys=ON` → `busy_timeout=5000` → `journal_mode=WAL` (`Db.java:54-57`, `:83-90`); WAL switch được xác minh, ném lỗi nếu bị từ chối (`:87`).
   - Khóa mở file theo URL toàn JVM (`OPEN_LOCKS`, `Db.java:31`, `:50`) + retry `SQLITE_BUSY` khi chuyển WAL (`:72-90`) để tránh race first-open của file mới.
   - Kết nối ngắn sống, try-with-resources; giao dịch ghi dùng `BEGIN IMMEDIATE`/`COMMIT`/`ROLLBACK` thủ công.
2. **Transactional outbox — bảng `deliveries` chính là hàng đợi dispatch bền vững**, không cần bảng outbox riêng:
   - `TransactionalPublisher.publish(Request)`: `BEGIN IMMEDIATE` → ghi `alerts`(ACTIVE, rev1) + `alert_revisions` + `alert_targets`(snapshot) + `deliveries`(PENDING) + `alert_operations`(idempotency) trong **một transaction** → `COMMIT` (`aegis-persistence/src/main/java/aegis/persistence/TransactionalPublisher.java:85-99`).
   - **Chỉ sau COMMIT** mới gọi `DeliveryEnqueuer.enqueue(batch)` (`TransactionalPublisher.java:110-111`). Mọi thất bại → `ROLLBACK`, không gì được enqueue (`:100-107`).
   - Ràng buộc `UNIQUE(alert_id,revision,device_id)` trên `deliveries` (`aegis-persistence/src/main/resources/db/001_base_schema.sql:10`).
3. **Recovery sau restart:** `RecoveryScanner.scan()` truy vấn delivery còn thuộc sở hữu scheduler (`PENDING/SENT/RETRY_SCHEDULED/FAILED`, `DeliveryRepo.findActive` tại `aegis-persistence/src/main/java/aegis/persistence/DeliveryRepo.java:80`), thứ tự `created_at,id`; `recover(enqueuer)` dựng lại lịch (`aegis-persistence/src/main/java/aegis/persistence/RecoveryScanner.java:25-41`). `AegisServer` gọi recovery **trước khi** mở gateway (`AegisServer.java:108-114`).
4. **Schema 26 bảng, migration idempotent bằng sentinel:** `MigrationRunner` áp `001_base_schema.sql` (12 bảng) rồi `002_showcase_expansion.sql` (14 bảng), mỗi script một `BEGIN IMMEDIATE`; phát hiện đã áp dụng qua bảng sentinel `buildings`/`demo_scenarios`, không thêm bảng bookkeeping (`aegis-persistence/src/main/java/aegis/persistence/MigrationRunner.java:24-25`, `:49`).

### Hệ quả (Consequences)

**Tích cực:**
- Không mất alert đã commit khi crash; enqueuer lỗi sau commit vẫn được recovery dựng lại (`docs/ARCHITECTURE.md` §6.1).
- Đơn giản, không phụ thuộc DB server ngoài; transaction ACID đáng tin ở tải demo.
- Outbox tích hợp vào `deliveries` → không thêm thành phần poller/bảng riêng.
- Recovery bao gồm cả `FAILED` nhưng `FAILED` là terminal cho retry budget: delivery FAILED được untrack không tốn attempt mới (`FanoutEngine.settled`, `aegis-server/src/main/java/aegis/server/FanoutEngine.java:230-236`, ghi chú S-10).

**Tiêu cực / đánh đổi:**
- **Một writer commit tại một thời điểm** → điểm nghẽn ghi; giảm thiểu bằng giao dịch ngắn, `busy_timeout`, không I/O mạng trong transaction (`docs/ARCHITECTURE.md` §5.5).
- Không HA; một file DB trên một host (`docs/SECURITY.md` §1.2).
- WAL sinh file phụ (`-wal`, `-shm`); cần checkpoint khi đóng (`InboxStore` phía client checkpoint WAL).
- Timestamp phải fixed-width 9 chữ số nano để `ORDER BY` TEXT đúng thứ tự thời gian (`aegis-persistence/src/main/java/aegis/persistence/Sql.java:20`; `aegis-server/src/main/java/aegis/server/Json.java:22-23`).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **PostgreSQL/MySQL** | Vận hành DB server riêng, overhead không justified ở tải demo; chỉ chuyển khi có **bằng chứng bottleneck thật** (blueprint §16). |
| **Hàng đợi in-memory + persist bất đồng bộ** | Mất công việc khi crash trước khi persist → vi phạm durable-first. |
| **Bảng outbox riêng + poller** | Thêm thành phần chuyển động; `deliveries` đã đóng vai trò outbox bền vững. |
| **File-per-alert / log append-only** | Khó transaction nguyên tử đa bảng, khó truy vấn trạng thái delivery. |

---

<a id="adr-004"></a>
## ADR-004 — ACK hai tầng RECEIVED / USER

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §5, §7.5, §16 (câu "Why TCP if application ACK is needed?"); ADR-003; `docs/PROTOCOL.md:7`.

### Bối cảnh (Context)

ACK mức transport của TCP chỉ chứng minh **byte đã tới ngăn xếp giao vận**, không chứng minh ứng dụng đã **lưu bền vững** cảnh báo, càng không chứng minh **con người đã nhìn nhận**. Trong phân phối cảnh báo khẩn cấp cần phân biệt rạch ròi:
- *machine receipt* — thiết bị đã nhận và ghi vào kho cục bộ;
- *human acknowledgement* — người dùng đã thao tác xác nhận (quy trách nhiệm).

Retry theo mô hình at-least-once khiến ACK có thể được gửi lại nhiều lần → ACK phải **idempotent** và trạng thái delivery **chỉ tiến, không thoái lui**.

### Quyết định (Decision)

Định nghĩa hai loại ACK tách biệt (`aegis-persistence/src/main/java/aegis/persistence/AckKind.java`: `RECEIVED`, `USER`, `CANCEL`):

1. **`ACK_RECEIVED` — durable-first phía client:** client commit inbox cục bộ (SQLite WAL, `synchronous=FULL`) **trước khi** gửi ACK (`aegis-client/src/main/java/aegis/client/AlertClientEngine.java:339-353`, `:400`; pragma tại `aegis-client/src/main/java/aegis/client/InboxStore.java:384-385`). Message trùng được re-ACK idempotent nhưng không popup lần hai (dedup seed từ inbox, `AlertClientEngine.java:109`).
2. **`ACK_USER` — cử chỉ người dùng:** ghi bền vững `markUserAck` trước, gửi khi online, nếu offline thì giữ pending và gửi lại sau reconnect (`AlertClientEngine.java:160-174`, `:412`; `InboxStore.markUserAck` tại `:159-162`). `ACK_USER` **không bao giờ suy ra từ** `ACK_RECEIVED`.
3. **Server xác minh ownership + idempotency:** router kiểm ACK đúng thiết bị đã xác thực (`MessageRouter.java:220-223`), đúng alert/revision/delivery (`:224-231`); `ACK_USER` nếu kèm `userId` phải là user tồn tại và enabled (`:233-243`, ghi chú S-06).
4. **Ghi ACK nguyên tử + rank guard:** `DeliveryRepo.recordAck` trả về **boolean inserted**; xác minh delivery thuộc `(deviceId,alertId,revision)` trong `BEGIN IMMEDIATE`, rồi `INSERT ... ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING` (`aegis-persistence/src/main/java/aegis/persistence/DeliveryRepo.java:150-196`); trạng thái chỉ tiến theo rank SQL `PENDING0 < SENT/RETRY1 < RECEIVED2 < ACK/FAILED3 < EXPIRED/CANCELLED4` (`:200-209`, hàm `rank` tại `:249-257`). Ràng buộc `UNIQUE(device_id,alert_id,revision,ack_kind)` (`001_base_schema.sql:14`).
5. **Duplicate trên wire:** `!inserted` → `duplicate=true` + audit `DUPLICATE` (`MessageRouter.java:245-256`).

### Hệ quả (Consequences)

**Tích cực:**
- Ngữ nghĩa rõ ràng: không bao giờ báo "người dùng đã xác nhận" khi mới chỉ có machine receipt.
- ACK idempotent chịu được traffic at-least-once (re-ACK không ném UNIQUE, không NACK nhầm).
- Trạng thái monotonic chống thoái lui RECEIVED→SENT do retry đến muộn (phối hợp ADR-003/S-02).
- Pending ACK bền vững được gửi lại sau crash/reconnect (`AlertClientEngine.java:423-431`).

**Tiêu cực / đánh đổi:**
- Nhiều trạng thái và logic hơn (rank guard, ownership bind, inserted-flag contract).
- `ACK_USER` cho phép `userId=null` (ẩn danh) nhưng nếu có thì phải xác minh → cần UI quy trách nhiệm rõ.
- Phụ thuộc inbox cục bộ bền vững phía client; mất inbox = mất bằng chứng receipt.

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **Một ACK duy nhất** | Nhập nhằng machine/human; không quy trách nhiệm người xác nhận được. |
| **Chỉ dựa TCP ACK** | Không chứng minh lưu bền vững nghiệp vụ, không định danh target. |
| **NACK (negative ack)** | Không chứng minh đã nhận; khó phân biệt "chưa tới" và "mất ACK". |
| **Exactly-once transport** | Không thể bảo đảm tổng quát trên mạng mất gói; dùng at-least-once + dedup (xem DEFENSE-QA Q3). |

---

<a id="adr-005"></a>
## ADR-005 — Multicast advisory hint + xác minh TLS

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §7.7, §16 (câu "Why multicast only hints?"); `docs/SECURITY.md` T1, §3.1; ADR-001.

### Bối cảnh (Context)

Môn học yêu cầu thực hành `MulticastSocket`/`DatagramSocket` (Bài 7–8). Multicast cho phép "đánh thức" nhiều client cùng lúc bằng một datagram — hấp dẫn cho cảnh báo khẩn cấp. Tuy nhiên **UDP multicast không tin cậy, không xác thực, không phù hợp diện rộng** (RFC 8085); trong LAN demo, kẻ xấu có thể giả mạo `ALERT_HINT`/`DISCOVER_EBS` để chuyển hướng client tới server giả hoặc hiển thị cảnh báo giả (threat T1, `docs/SECURITY.md` §2).

Cần dung hòa: minh họa kỹ thuật multicast của môn học **và** giữ bất biến "client không bao giờ tin multicast/UDP như lệnh operator" (blueprint §5).

### Quyết định (Decision)

Multicast/UDP chỉ là **advisory hint**, nội dung authoritative luôn lấy qua TLS đã xác minh:

1. **Tham số lab:** nhóm administratively-scoped `239.10.10.10`, cổng `9998`, `TTL=1` (link-local), discovery `9999` (`aegis-network-lab/src/main/java/aegis/networklab/LabDefaults.java:5-9`).
2. **Publisher/subscriber thật:** `MulticastSocket` → `joinGroup(group, interface)` → `setOption(IP_MULTICAST_TTL)` → `send` một datagram/hint (`aegis-network-lab/src/main/java/aegis/networklab/MulticastHintPublisher.java:33-41`, `:53`); subscriber bind → joinGroup → receive loop → leaveGroup (`MulticastHintSubscriber.java:33-40`, `:56`, `:87`).
3. **Hint chỉ là trigger, không phải content:** `VerifiedAlertSubscriber.onHint` nhận hint → **fetch nội dung authoritative qua phiên TLS đã xác minh** → sink chỉ thấy revision thật từ server, **bỏ qua revision trong hint**; hint stale/duplicate (≤ last verified) bị bỏ qua không fetch (`aegis-network-lab/src/main/java/aegis/networklab/VerifiedAlertSubscriber.java:44-82`).
4. **Discovery phải xác minh lại:** `DiscoveryClient.discoverUntrusted` kiểm nonce echo (`DiscoveryClient.java:93-99`); `discoverTrusted` mở TLS với **truststore pinned**, yêu cầu `CN == serverId`, và `HELLO/SERVER_HELLO` echo đúng nonce **qua chính phiên TLS đó** (`DiscoveryClient.java:105-112`).
5. **Client xác thực server, không trust-all:** `TlsSupport.clientContext` nạp `KeyStore` → `TrustManagerFactory` → `SSLContext("TLS")`, chỉ tin đúng cert trong truststore (`aegis-client/src/main/java/aegis/client/TlsSupport.java:19-27`).
6. Trên một host macOS, multicast có thể thất bại → fallback unicast (`UnicastHintPublisher`/`UnicastHintSubscriber`, `docs/ARCHITECTURE.md` §3.3).

### Hệ quả (Consequences)

**Tích cực:**
- Hint giả mạo (sai revision/alertId) cùng lắm gây một lần fetch; **không bao giờ hiển thị nội dung giả** vì sink chỉ nhận content đã xác minh TLS.
- Minh họa đúng kỹ thuật multicast + UDP discovery của môn học mà không phá bất biến bảo mật.
- Coalescing hint trùng/giả giảm fetch thừa (`VerifiedAlertSubscriber.java:49-54`).

**Tiêu cực / đánh đổi:**
- Thêm một round-trip fetch qua TLS sau hint → tăng latency hiển thị so với "tin thẳng hint".
- Multicast cần LAN thật hỗ trợ (TTL/interface, rate limit); qua tunnel kiểu Tailscale thường thất bại (`docs/ARCHITECTURE.md` §3.3).
- Một hint giả mạo revision cao có thể **chặn một hint thật cùng số revision** sau đó (giới hạn được ghi nhận tường minh tại `VerifiedAlertSubscriber.java:51-52`) — nhưng không tạo nội dung giả.
- Self-signed truststore không phải CA công cộng, không OCSP/CRL (`docs/SECURITY.md` §6.5).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **Multicast làm kênh phân phối chính** | Không tin cậy, không xác thực → vi phạm bất biến và RFC 8085. |
| **Ký số payload multicast** | Phân phối khóa phức tạp; vẫn không giải quyết độ tin cậy (mất gói) của UDP. |
| **Bỏ hẳn multicast** | Mất mục tiêu thực hành `MulticastSocket` của môn học. |
| **UDP broadcast** | Phạm vi hẹp, không kiểm soát nhóm, vẫn không xác thực. |

---

<a id="adr-006"></a>
## ADR-006 — Modular monolith

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §6.3, §6.4; `docs/ARCHITECTURE.md` §1.1, §4.

### Bối cảnh (Context)

Đồ án capstone do nhóm sinh viên xây dựng, triển khai **một node server trong LAN demo**. Cần ranh giới module rõ ràng để phân công và kiểm thử, nhưng **không cần** chi phí vận hành hệ phân tán (service discovery, distributed transaction, network partition). Trọng tâm môn học là lập trình mạng, không phải vận hành microservice.

Blueprint §6.3 đề xuất cây package tham chiếu; một số module đề xuất được gộp vào `aegis-core` (logic thuần) và `aegis-server` (dịch vụ ứng dụng).

### Quyết định (Decision)

Xây dựng **modular monolith**: một Maven reactor gồm 7 module Java + dashboard Node độc lập, chạy trong **một tiến trình server** (`AegisServer`):

- Khai báo module: `aegis-protocol`, `aegis-core`, `aegis-persistence`, `aegis-server`, `aegis-client`, `aegis-simulator`, `aegis-network-lab` (`pom.xml:10-16`); compiler release 24 (`pom.xml:19`).
- Quan hệ phụ thuộc: `protocol ← core ← {persistence, server, client, simulator}`; `server ← {protocol, core, persistence}`; `network-lab ← protocol`; dashboard độc lập, chỉ giao tiếp qua WebSocket `status/1` (`docs/ARCHITECTURE.md` §1.1).
- `AegisServer` wires tất cả: migrate → seedWorld → RecoveryScanner → WS feed + TLS gateway + timers (`aegis-server/src/main/java/aegis/server/AegisServer.java:57-125`).
- **Hợp đồng dùng chung bất biến:** `aegis-protocol` (EBSP/2.0) và schema 26 bảng là nền tảng chung; đổi wire contract phải version bump, đổi schema phải migration additive (`docs/ARCHITECTURE.md` §1 nguyên tắc 6, §4.2).
- Logic thuần không I/O tách vào `aegis-core` (`ScopedRBAC`, `PriorityScheduler`, `RetryPolicy`, `DedupIndex`, `TargetResolver`, `AckLedger`, `Grants`) → kiểm thử deterministic, dùng chung hai phía.

### Hệ quả (Consequences)

**Tích cực:**
- Triển khai/gỡ lỗi đơn giản: một tiến trình, một file DB; gọi hàm in-process, không network partition nội bộ.
- Ranh giới module enforced bằng phụ thuộc Maven + kỷ luật package; hợp đồng bất biến giữ các module khớp nhau.
- Logic thuần trong `aegis-core` kiểm thử không cần I/O → test nhanh, deterministic (Clock tiêm được).
- Có thể tách thành service sau này vì hợp đồng đã tách bạch (protocol/persistence độc lập).

**Tiêu cực / đánh đổi:**
- Không scale độc lập từng phần; toàn bộ server là một đơn vị triển khai.
- **SPOF** một tiến trình (thừa nhận, không tuyên bố HA).
- Một module lỗi có thể ảnh hưởng cả tiến trình; cần kỷ luật bounded resource để cô lập (ADR-003, hàng đợi bounded).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **Microservices** | Overhead vận hành (discovery, distributed transaction, observability phân tán) không justified ở tải demo một node. |
| **Một package phẳng (big ball of mud)** | Không ranh giới, khó phân công/kiểm thử, hợp đồng dễ vỡ. |
| **OSGi / Java module system phức tạp** | Thêm complexity không cần thiết cho capstone. |

---

<a id="adr-007"></a>
## ADR-007 — Argon2id cho lưu trữ credential

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §9; `docs/SECURITY.md` §3.3, §7.3 (OWASP Password Storage); ADR-001.

### Bối cảnh (Context)

Server lưu mật khẩu operator (`users.password_hash`) và credential thiết bị (`devices.credential_hash`). Threat T2 (đánh cắp credential) và T10 (đọc trộm file DB) đòi hỏi hash **memory-hard**, salt duy nhất, chống rainbow table và GPU/ASIC cracking. OWASP Password Storage Cheat Sheet khuyến nghị **Argon2id** (RFC 9106) với tham số tối thiểu `m=19 MiB (19456 KiB), t=2, p=1`.

Đồng thời demo cần xác thực đủ nhanh cho 100–500 client thiết bị → tham số phải **injectable** để cân bằng tốc độ demo và an toàn production.

### Quyết định (Decision)

Dùng **Argon2id** qua `de.mkammerer:argon2-jvm:2.11` (native libs bundled kể cả darwin-aarch64):

- `Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)` (`aegis-server/src/main/java/aegis/server/Argon2idHasher.java:13`).
- Tham số injectable qua constructor (`Argon2idHasher.java:18-24`); mặc định demo `iterations=2`, `memoryKib=16384` (16 MiB), `parallelism=1` (`aegis-server/src/main/java/aegis/server/ServerConfig.java:70-72`).
- **Salt duy nhất** mỗi mật khẩu do Argon2id tự sinh, nhúng trong chuỗi `$argon2id$...`.
- **Wipe bộ nhớ:** `hash()`/`verify()` dùng `char[]` và `argon2.wipeArray(chars)` trong `finally` (`Argon2idHasher.java:28-33`, `:38-48`).
- **Verify an toàn:** chỉ chấp nhận chuỗi bắt đầu `$argon2id$`; hash malformed/null → `false`, không crash auth (`Argon2idHasher.java:39`).
- **Tài khoản vô hiệu hóa:** `AuthService` kiểm `enabled()` trước khi verify; `enabled=0` không bao giờ xác thực (`aegis-server/src/main/java/aegis/server/AuthService.java:28-39`).
- SHA-256 **chỉ** dùng cho `payload_hash` idempotency, không cho mật khẩu (`docs/SECURITY.md` §3.3).

**Ghi nhận trung thực 🟡:** mặc định demo 16 MiB **thấp hơn** ngưỡng OWASP 19 MiB; khi production phải nâng `memoryKib ≥ 19456` (tham số injectable nên không cần sửa mã). Credential demo (`*-demo-secret`, `device-demo-secret` dùng chung fleet) công khai trong mã nguồn → **chỉ cho lab** (`aegis-server/src/main/java/aegis/server/ServerStore.java:631-637`, `:697-700`; `docs/SECURITY.md` §6.5).

### Hệ quả (Consequences)

**Tích cực:**
- Memory-hard → kháng GPU/ASIC cracking tốt hơn hash nhanh; salt duy nhất chống rainbow table.
- Wipe `char[]` giảm vết credential trong RAM; verify không crash với hash lỗi.
- `enabled=0` chặn xác thực ngay → hỗ trợ revoke/rotate (FR-003).
- Tham số injectable: hiệu chỉnh production không cần sửa mã.

**Tiêu cực / đánh đổi:**
- Tham số demo dưới ngưỡng OWASP (đánh đổi tốc độ cho lab, ghi nhận 🟡).
- Shared device secret phá vỡ danh tính riêng từng thiết bị (T2) — chỉ lab; production phải cấp credential riêng + rotate (`docs/SECURITY.md` §6.5 mục 7).
- Chi phí CPU/RAM mỗi lần verify cao hơn SHA → cần cân nhắc tham số dưới tải lớn.
- Pepper (defense-in-depth) chưa dùng (`docs/SECURITY.md` §7.3 P8).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **SHA-256 / MD5 (hash nhanh)** | GPU/ASIC crack cực nhanh; không memory-hard; chỉ dùng cho payload_hash, không mật khẩu. |
| **bcrypt** | Memory-hard ở mức giới hạn (4 KiB), kém linh hoạt tham số hơn Argon2id. |
| **PBKDF2** | Chỉ CPU-hard, không memory-hard → dễ song song hóa trên GPU. |
| **Plaintext** | Không thể chấp nhận (vi phạm P1 OWASP). |
| **scrypt** | Tương tự Argon2id nhưng Argon2id là khuyến nghị hiện hành của OWASP/RFC 9106. |

---

<a id="adr-008"></a>
## ADR-008 — Three.js 3D digital twin

**Trạng thái:** Chấp nhận · **Liên quan:** blueprint §10 (Digital Twin UI), §1.2; `docs/ARCHITECTURE.md` §7; `aegis-dashboard/docs/status-contract.md`.

### Bối cảnh (Context)

Blueprint §10 yêu cầu **digital twin trực quan**: sơ đồ tòa nhà 10 tầng, thiết bị/khu vực đổi màu từ **sự kiện thật của server**, không phải animation vô nghĩa (blueprint §0.1 mục 1). Dashboard phải là **client tham chiếu của feed trạng thái**, không phải backend thứ hai. Blueprint đề xuất JavaFX, nhưng ứng dụng web dễ chia sẻ/chạy đa nền tảng không cần cài đặt, phù hợp demo trước hội đồng.

Feed trạng thái `status/1` qua WebSocket `9444` là **read plane** trên LAN tin cậy; cần giới hạn tài nguyên và suy severity chuẩn tắc từ bộ đếm delivery thật.

### Quyết định (Decision)

Xây dashboard bằng **Vite + Three.js** (web), render 3D twin từ feed `status/1`:

- **3D twin:** `WebGLRenderer`, scene 10 tầng × 4 zone (2×2), mỗi zone box tô màu theo severity (`aegis-dashboard/src/main.js:2-9`, `:13`, `:74`, `:117`, `:167`, `:223-233`). Zone code `F%02d-{A..D}` (`main.js:167`).
- **Chỉ đọc feed, không backend riêng:** client WebSocket `status/1` (`aegis-dashboard/src/ws.js`), hợp đồng snapshot + delta trong `aegis-dashboard/src/data.js` (`:113-136` merge delta thuần).
- **Severity chuẩn tắc từ bộ đếm thật:** `deriveSeverity` (offline/critical/warning/online) mirror giữa server và client (`aegis-server/src/main/java/aegis/server/ZoneStatus.java:22-30` ↔ `aegis-dashboard/src/data.js:99-100`); **không dùng số client online làm mẫu số ACK**.
- **Server feed:** `StatusFeedService` gom batch 500 ms, snapshot khi connect, delta chỉ phần thay đổi (`aegis-server/src/main/java/aegis/server/StatusFeedService.java:116`, `:155`, `:179-233`, `:247`); `StatusFeedServer` giới hạn **64 subscriber**, vượt → HTTP 503 (`aegis-server/src/main/java/aegis/server/StatusFeedServer.java:36`, `:100-103`).
- **Reconnect + MOCK fallback:** backoff mũ 1 s → 15 s + jitter; sau **3 lần thất bại** chuyển MOCK (badge `MOCK FEED`), tự về LIVE khi nối lại (`aegis-dashboard/src/ws.js:6-7`, `:19-22`, `:74`, `:82-86`).
- **Graceful degradation:** WebGL không khả dụng → panels vẫn chạy, twin tắt (`main.js:317-324`).

### Hệ quả (Consequences)

**Tích cực:**
- Twin 3D sống động, đổi màu từ trạng thái thật → minh họa trực quan phân phối cảnh báo, gây ấn tượng khi bảo vệ.
- Đa nền tảng, chạy trong browser, không cài đặt; dễ chia sẻ link demo.
- Read plane tách biệt: dashboard không phải backend thứ hai, không ghi dữ liệu nghiệp vụ.
- MOCK fallback + badge giữ demo chạy được khi server chết, **có nhãn rõ ràng** tránh nhầm dữ liệu giả.

**Tiêu cực / đánh đổi:**
- WS feed là **read plane không xác thực** trên LAN tin cậy (ghi nhận tường minh, `StatusFeedServer.java:27-30`); phải giới hạn subscriber (64) và coi là trusted-LAN.
- Quy tắc severity phải **mirror hai ngôn ngữ** (Java `ZoneStatus` ↔ JS `data.js`) → nguy cơ lệch nếu sửa một phía.
- MOCK mode nếu không nhãn rõ có thể gây hiểu nhầm số liệu thật (đã gắn badge).
- Three.js/WebGL phụ thuộc GPU browser; máy yếu có thể chậm (đã có degradation).

### Phương án thay thế (Alternatives)

| Phương án | Lý do không chọn |
|---|---|
| **JavaFX 2D/3D thuần** | Cài đặt friction, khó chia sẻ; JavaFX đã dùng cho operator UI nên dashboard web tách biệt rõ hơn. |
| **2D canvas/SVG** | Ít ấn tượng hơn 3D twin; blueprint ưu tiên trực quan tòa nhà. |
| **Grafana/dashboard generic** | Không mô hình hóa tòa nhà/zone; không phải digital twin đúng nghĩa. |
| **Server-rendered HTML** | Không live 3D tương tác; khó đổi màu theo sự kiện thời gian thực. |

---

## Tài liệu tham khảo

1. `Plan/AEGIS_MASTER_BLUEPRINT.md` — AEGIS EBS Command Center / Showcase Edition, CMP180 Capstone Blueprint v2.0 (§0, §5–§10, §15–§17).
2. `docs/PROTOCOL.md` — EBSP/2.0 wire contract.
3. `docs/ARCHITECTURE.md` — Kiến trúc hệ thống AEGIS-EBS.
4. `docs/SECURITY.md` — Mô hình bảo mật & quyền riêng tư.
5. `docs/ERD.md` — Đặc tả quan hệ schema 26 bảng.
6. `aegis-dashboard/docs/status-contract.md` — Hợp đồng feed `status/1`.
7. OWASP Cheat Sheet Series (Authorization, Session Management, Password Storage); IETF RFC 9106 (Argon2), RFC 8085 (UDP Usage Guidelines); SQLite WAL (sqlite.org/wal.html); OASIS CAP 1.2.
8. Mã nguồn đối chiếu: các file `.java`/`.js`/`.sql` được trích dẫn theo `đường/dẫn:dòng` trong từng ADR.