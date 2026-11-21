# AUDIT CHUYÊN SÂU BACKEND — AEGIS-EBS (`aegis-server` · `aegis-core` · `aegis-persistence`)

| | |
|---|---|
| **Ngày audit** | 2026-11-21 (UTC+07) |
| **Hình thức** | Audit tĩnh chuyên sâu (static depth audit) — CHỈ đọc mã nguồn, không chạy `mvn`, không sửa code, không `git commit` |
| **HEAD tham chiếu** | `b4b8a4e` (working tree tại thời điểm đọc; các agent khác có thể commit song song — số dòng dẫn chiếu theo bản đã đọc) |
| **Phạm vi** | `aegis-server` (FanoutEngine, MessageRouter, ServerStore, SessionRegistry, BoundedWriterQueue, TlsGateway, ClientSession, AlertService, IncidentService, SyncService, StatusFeedService/Server, WsConnection, AegisServer, RbacService, AuthService, AuditService, MetricsRegistry, Json, ServerConfig, ZoneStatus, ServerMain, Argon2idHasher); `aegis-core` (AckLedger, RetryPolicy, DedupIndex, PriorityScheduler, ScopedRBAC, Grants, TargetResolver, DeviceLocation); `aegis-persistence` (DeliveryRepo, TransactionalPublisher, RecoveryScanner, Db, Sql, AlertRepo, AuditRepo, MigrationRunner, DeliveryState, DeliveryEnqueuer) + schema `db/001_base_schema.sql`, `db/002_showcase_expansion.sql` + `aegis-protocol` (Priority, Protocol, FrameCodecImpl) đối chiếu |
| **Trọng tâm** | (1) Concurrency correctness — race ACK/dispatch, rank guard, bounded-queue eviction, thread-safety; (2) Idempotency & exactly-once UI; (3) Recovery đúng ngân sách retry + state; (4) Transaction atomicity (update/cancel/incident); (5) Backpressure / slow-client; (6) lỗi tiềm ẩn kèm khuyến nghị |
| **Phương pháp** | Đọc toàn bộ source có đánh số dòng; dựng ma trận chuyển trạng thái delivery; truy vết từng đường ghi DB theo thread (reader/dispatcher/timer); đối chiếu bất biến "ACK chỉ tiến trạng thái" và hợp đồng S-01/S-02/S-09/S-10; kiểm tra ràng buộc UNIQUE/PK trong schema; suy luận lịch biểu (interleaving) cho các race. **Không tái lập bằng kiểm thử** (ràng buộc không chạy mvn) — severity phản ánh *hệ quả tiềm tàng × khả năng với tới*, mỗi finding ghi rõ cơ chế để có thể viết test đối kháng xác nhận |
| **Tiền đề** | Kế thừa các vòng peer-review trước (`docs/reviews/server-review.md`, `persistence-review.md`) — nhiều lỗi S-01…S-10 đã được sửa; audit này phân biệt rõ **đã-đạt** (xác nhận bằng đọc code hiện tại) và **rủi ro còn lại / mới phát hiện** |

---

## 1. Tổng hợp điều hành

**Kết luận chung.** Kiến trúc backend có nền tảng đúng đắn và nhiều bất biến cốt lõi **đã được hiện thực chắc chắn**: ACK đơn điệu có rank guard + ownership guard trong một transaction (`recordAck`), chuyển trạng thái dispatch có guard (`updateDispatchState`) và commit-trước-khi-offer, publish/update/cancel/incident gói trong `BEGIN IMMEDIATE` đơn nhất với enqueue/fanout sau commit, recovery chỉ-đọc tôn trọng ngân sách retry bền vững, hàng đợi writer bounded có chính sách slow-client tất định, và các cấu trúc pure-logic của `aegis-core` thread-safe/bất biến.

**Tuy nhiên**, audit tĩnh phát hiện **4 lỗ hổng mức HIGH** phá vỡ bất biến "ACK chỉ tiến trạng thái" hoặc gây treo/ rò rỉ định danh, **13 rủi ro MEDIUM** (chủ yếu ở recovery ngữ nghĩa, atomicity suy biến dưới đồng thời, backpressure mặt phẳng WS, và khoảng trống reconciliation), cùng **18 quan sát LOW**. Đáng chú ý nhất: hai đường ghi *không* được guard (`scheduleRetry`, `updateState` trong `markFailed`) có thể đẩy delivery **lùi** từ RECEIVED/ACKNOWLEDGED về RETRY_SCHEDULED/FAILED; tái xác thực trên cùng kết nối làm hỏng chỉ mục `byDeviceId` gây gửi push chéo thiết bị; và một subscriber WS chậm (mặt phẳng không xác thực) có thể đình trệ toàn bộ broadcast delta và chiếm vĩnh viễn một thread timer.

| Severity | Số lượng | Mã |
|---|---:|---|
| **HIGH** | 4 | H1–H4 |
| **MEDIUM** | 13 | M1–M13 |
| **LOW** | 18 | L1–L18 |
| **Tổng finding actionable** | **35** | |
| Bất biến/hạng mục **đã-đạt** (xác nhận tĩnh) | 23 | A1–A23 |

> Phân bố theo trụ cột: Concurrency (H1, H2, H3, M2, M8, M12, L2, L3, L8) · Idempotency/exactly-once (A1, A5, L1, L5, L6, M5) · Recovery (A7, A8, M1, M7, M10, L8) · Atomicity (A6, M3, M4, M5, L7) · Backpressure/slow-client (A3, A13, A14, H4, M6, M9, M13, L9, L10).

---

## 2. Các bất biến ĐÃ-ĐẠT (xác nhận bằng đọc code hiện tại)

Mỗi mục nêu cơ chế + dẫn chiếu `file:dòng`. Đây là phần "đã-đạt" cần giữ nguyên khi sửa các finding bên dưới (không đưa lại các pattern đã bị cấm trong skill `aegis-ebs-server`).

### 2.1. Idempotency & đơn điệu ACK
- **A1 — `recordAck` nguyên tử + ownership + idempotent + rank guard (S-01/M1).** `DeliveryRepo.java:150–224`: một `BEGIN IMMEDIATE` gồm (i) SELECT xác nhận delivery thuộc `(deviceId, alertId, revision)` — sai khớp → `SQLException` + ROLLBACK, không ghi ack (`:167–182`); (ii) INSERT ack `ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING`, `executeUpdate()==1 ⇒ inserted=true` (`:184–197`); (iii) UPDATE state có CASE-rank guard `PENDING0 < SENT/RETRY1 < RECEIVED2 < ACK/FAILED3 < EXPIRED/CANCELLED4` kèm ownership trong WHERE (`:198–211`); 0 dòng = no-op lành tính. Ack row + state đổi trong cùng transaction. Router ánh xạ `!inserted → duplicate=true` + audit DUPLICATE (`MessageRouter.java:247–252`). Khớp UNIQUE schema `001:14`.
- **A2 — Guard dispatch + commit-before-offer (S-02).** `DeliveryRepo.updateDispatchState` chỉ chuyển khi `state IN ('PENDING','SENT','RETRY_SCHEDULED')` (`DeliveryRepo.java:235–247`); `FanoutEngine.sendPush` commit `recordAttempt` + `updateDispatchState(SENT)` **trước** khi offer frame vào writer queue (`FanoutEngine.java:246–252`) — ACK đồng thời không thể bị lùi RECEIVED→SENT bởi commit dispatch muộn.
- **A5 — Publish nguyên tử + idempotent theo requestId + payload_hash v2.** `TransactionalPublisher.java:52–113`: `BEGIN IMMEDIATE` → alert + revision + targets snapshot + deliveries PENDING + `alert_operations(request_id UNIQUE)` → COMMIT → `enqueuer.enqueue` **chỉ sau commit** (`:111`); mọi lỗi → ROLLBACK, không enqueue. `deviceIds` khử trùng lặp qua `LinkedHashSet` + null-check trước `copyOf` (`:62–67`). `payloadHash` v2 canonical JSON khóa-alpha, escape RFC-8259, phủ đủ trường, loại requestId (`:144–215`). UNIQUE `request_id` tại `002:78`. Replay đồng thời bắt `SQLException` → trả winner (`AlertService.java:140–149`).

### 2.2. Atomicity vòng đời alert/incident
- **A6 — Update/cancel/incident một transaction (S-09).** `ServerStore.applyUpdate` (`:320–357`): revision mới + bump `current_revision` + target/delivery mới + supersede delivery cũ + operation, một `BEGIN IMMEDIATE`, ROLLBACK khi lỗi; enqueue sau commit (`AlertService.java:189–192`). `applyCancel` (`:364–391`): tombstone alert + deliveries + operation một transaction; CANCEL_PUSH fanout sau commit (`AlertService.java:227–238`). `createIncidentAtomic` (`:404–434`) và `transitionIncidentAtomic` (`:437–461`): incident + timeline event một transaction (không orphan). Khớp schema `alert_revisions PK(alert_id,revision)` (`002:55`), `deliveries UNIQUE(alert_id,revision,device_id)` (`001:10`).

### 2.3. Recovery & ngân sách retry
- **A7 — Recovery chỉ-đọc, FAILED là terminal cho ngân sách (S-10).** `RecoveryScanner.scan()=findActive()` gồm PENDING/SENT/RETRY_SCHEDULED/FAILED, `ORDER BY created_at,id` (`RecoveryScanner.java:25–27`; `DeliveryRepo.java:76–88`); `recover` enqueue đúng một lần (`:34–41`). `FanoutEngine.settled()` gồm FAILED nên delivery FAILED phục hồi bị untrack **không** tốn attempt mới (`FanoutEngine.java:230–236, 184`). Recovery chạy **trước** khi mở cổng (`AegisServer.java:110–117`).
- **A8 — Ngân sách retry đúng PROTOCOL + guard thế hệ timer.** `retriesConsumed = attemptCount-1` nạp cho `shouldRetry` ⇒ initial + 3 retries (`FanoutEngine.java:316–317`); cửa sổ `windowMs` 3s/6s/12s + jitter, cap bậc cuối (`:289–293`; `RetryPolicy.delayMs` bão hòa chống overflow `RetryPolicy.java:60–74`). `generations.merge` + so khớp thế hệ loại timer cũ (`:295–308`). `attemptCount` bền vững trong DB nên tổng attempt bị chặn kể cả qua restart.

### 2.4. Backpressure & slow-client (mặt phẳng TLS)
- **A3 — BoundedWriterQueue ưu tiên + eviction đúng.** `BoundedWriterQueue.java:31–77`: TreeSet khóa `-ordinal` rồi `seq`; với `Priority{INFO=0,WARNING=1,EMERGENCY=2,CRITICAL=3}` (`Priority.java:2`) ⇒ `first()`=CRITICAL (take ưu tiên cao trước), `last()`=INFO; offer khi đầy: frame mới ordinal cao hơn `last()` → evict frame thấp nhất (`:50–57`), ngược lại REJECTED_SLOW. FIFO trong bậc (seq tăng). Mọi thao tác `synchronized`; `close()` đánh thức writer, `take()` trả null sau close+drain.
- **A13 — Chính sách slow-client tất định.** `ClientSession.send` → REJECTED_SLOW gọi `onSlowClient` (`ClientSession.java:71–78`); `TlsGateway.onSlowClient` tăng metric + audit SLOW_CLIENT_DISCONNECT + close (`TlsGateway.java:174–181`). `sendPush` gặp REJECTED_SLOW **tái dùng** attempt đã ghi (RETRY_SCHEDULED, không đếm đôi) (`FanoutEngine.java:253–266`).
- **A14 — Một writer thread/session, không xen kẽ frame.** `ClientSession.writerLoop` là thread duy nhất gọi `codec.writeFrame` (`:80–94`); `queue.take` chỉ giữ monitor lúc lấy phần tử, không giữ khi ghi socket → offer không bị chặn bởi I/O chậm; `close()` đóng socket giải phóng writer đang kẹt (`:117–126`).
- **A12 — Pre-auth soTimeout + TTL tuyệt đối (biên frame) + cap kết nối (S-07).** `TlsGateway.java:93–98` cap `maxConnections` tại accept; `:103` soTimeout pre-auth; `:131–141` xóa soTimeout sau xác thực và kiểm TTL tuyệt đối (keepalive không gia hạn). *Giới hạn còn lại xem M6.*

### 2.5. Thread-safety & pure-logic core
- **A23 — Cấu trúc core an toàn.** `AckLedger` mọi phương thức `synchronized`, open một lần, expected defensive-copy (`AckLedger.java:41–82`); `DedupIndex.seen` dùng `compute` nguyên tử trên key — concurrent first-sight cho đúng một `false` (`DedupIndex.java:21–34`); `Grants` record bất biến, `Set.copyOf`/`Map.copyOf` (`Grants.java:19–29`); `TargetResolver` freeze snapshot, sorted/dedup/unmodifiable (`TargetResolver.java:28–91`); `PriorityScheduler` `synchronized`, MAX_BURST=8 xoay vòng relief chống starvation (`PriorityScheduler.java:45–107`).
- **A4 — PriorityScheduler công bằng.** Tối đa 8 dispatch liên tiếp bậc cao nhất rồi 1 relief bậc dưới, xoay vòng descending; bậc mới cao hơn preempt ngay (`PriorityScheduler.java:53–107`).
- **A17 — MetricsRegistry thread-safe, reservoir bounded.** Counter `AtomicLong`; latency FIFO 4096 mẫu dưới `synchronized(latencies)`, percentile nearest-rank trên bản copy (`MetricsRegistry.java:17–49`).
- **A18 — Zone counter đơn điệu + coalesce rebuild.** `Counters` AtomicLong, `counters` ConcurrentHashMap (`StatusFeedService.java:33–37, 73–100`); `alertsDirty` volatile gom rebuild về ≤1 lần/statusBatchMs — publish flood không biến command path thành O(N²) (`:103–107, 250–259`).
- **A22 — SessionRegistry chỉ mục có điều kiện.** `unregister` dùng `byDeviceId.remove(deviceId, session)` (chỉ xóa nếu đúng session) và `deviceOnline` thay session cũ + close (`SessionRegistry.java:26–40`) — reconnect không xóa nhầm session mới.

### 2.6. Hạ tầng persistence & protocol
- **A9 — Db pragma đúng thứ tự + khóa mở theo file + retry WAL BUSY (L1).** `Db.open`: `foreign_keys=ON` → `busy_timeout=5000` → `journal_mode=WAL` dưới khóa per-url JVM-wide; `switchToWal` retry BUSY backoff trong ngân sách 5s (`Db.java:45–104`).
- **A10 — Timestamp fixed-width 9 nano (S-08/L2).** `Sql.TS` và `Json.TS` đều `appendInstant(9)`; đọc `Instant.parse` chấp nhận cả legacy (`Sql.java:20, 36–39`; `Json.java:22–23`) — ORDER BY/expiry TEXT đúng thứ tự thời gian.
- **A19 — MigrationRunner idempotent, một transaction/script.** Sentinel `buildings`/`demo_scenarios`, `BEGIN IMMEDIATE` mỗi script, split tôn trọng quote/`--` (`MigrationRunner.java:23–139`).
- **A20 — FrameCodec chặn oversize trước cấp phát + UTF-8 strict.** `readFrame` đọc header, `checkLength` 1..65536 trước `new byte[len]`, decode REPORT malformed (`FrameCodecImpl.java:64–85`; `Protocol.java:4`).
- **A21 — Argon2id verify an toàn.** Hash lỗi encoding → false (không crash auth), wipe char array (`Argon2idHasher.java:38–50`).

### 2.7. Bảo mật đường truyền & phân quyền (đã-đạt trong phạm vi audit)
- **A11 — Auth gate + device/operator split + ScopedRBAC mỗi lệnh + STATUS_SUBSCRIBE scope + ACK_USER userId (S-05/S-06).** `MessageRouter.java:84–113` (chỉ HELLO/AUTH_* trước xác thực), `:207–261` (requireDevice, ownership precheck, xác minh userId enabled), `:427–467` (từ chối EMPLOYEE/DEVICE, lọc snapshot FLOOR_MANAGER theo floor grant). `RbacService` dùng placeholder `"*"` cho building-wide nên `ScopedRBAC.requireNonNull(floorId)` không NPE và FLOOR_MANAGER bị chặn đúng (`RbacService.java:26, 51–54`; `ScopedRBAC.java:22–33`).
- **A15 — HEARTBEAT_ACK được xử lý; sweep pure-logic Clock tiêm.** `MessageRouter.java:89`; `SessionRegistry.sweep` (`:65–80`).
- **A16 — INTERNAL khử chi tiết (NFR-017/S-04); SecurityException→FORBIDDEN; phân biệt COMMAND_RESULT/ERROR.** `MessageRouter.java:114–127, 479–506`.

---

## 3. FINDINGS — HIGH

### H1 · `scheduleRetry` không guard: ACK đồng thời bị lùi RECEIVED → RETRY_SCHEDULED
- **Severity:** HIGH · **Trụ cột:** concurrency / rank guard / recovery
- **Vị trí:** `aegis-persistence/.../DeliveryRepo.java:104–113` (`scheduleRetry`); gọi từ `aegis-server/.../FanoutEngine.java:276–287` (`offlineAttempt`, đặc biệt `:282`).
- **Cơ chế.** `scheduleRetry` là UPDATE **vô điều kiện**:
  `UPDATE deliveries SET state='RETRY_SCHEDULED', next_retry_at=?, attempt_count=attempt_count+1 WHERE id=?` — không có mệnh đề guard trạng thái/rank như `updateDispatchState` (`:235–247`) hay `recordAck` (`:198–211`). `offlineAttempt` chạy khi dispatcher thấy `session==null||isClosed()` (`FanoutEngine.java:210–214`). Giữa thời điểm đọc session và lúc `scheduleRetry` commit, thiết bị có thể reconnect (session mới) và ACK push trước đó → `recordAck` commit RECEIVED. `scheduleRetry` sau đó **ghi đè RECEIVED thành RETRY_SCHEDULED** và tăng `attempt_count`, vi phạm bất biến "ACK chỉ tiến trạng thái".
- **Hệ quả.** Delivery đã ACK bị hồi sinh vào chu kỳ retry; dispatch kế tiếp qua guard `updateDispatchState` (RETRY_SCHEDULED ∈ tập guard) → SENT → push trùng. Nếu client không re-ACK (đã coi là xong), ngân sách cạn → `markFailed` → **FAILED sau khi đã RECEIVED/ACKNOWLEDGED**; UI/SYNC hiển thị FAILED cho alert đã nhận, zone `failed` tăng sai, audit DELIVERY_FAILED giả. Với tới được trong vận hành bình thường (reconnect churn, heartbeat suspect rồi reconnect), không cần ý đồ đối kháng.
- **Khuyến nghị.** Thêm guard cho `scheduleRetry`: `... WHERE id=? AND state IN ('PENDING','SENT','RETRY_SCHEDULED')` (hoặc dùng `updateDispatchState` với `attempt_count+1` đọc-nguyên-tử trong SQL). Gói `recordAttempt(OFFLINE)` + chuyển trạng thái trong **một** transaction có guard (xem L8). Viết test đối kháng: ACK commit giữa `deviceSession()==null` và `scheduleRetry`.

### H2 · `markFailed` dùng `updateState` vô điều kiện: FAILED đè RECEIVED/ACKNOWLEDGED + lùi `attempt_count`
- **Severity:** HIGH · **Trụ cột:** concurrency / rank guard
- **Vị trí:** `FanoutEngine.java:328–342` (`markFailed`, `:330` gọi `updateState`); `DeliveryRepo.java:90–101` (`updateState` vô điều kiện); cửa sổ race tại `checkDelivery` `FanoutEngine.java:305–326`.
- **Cơ chế.** `checkDelivery` (thread timer) đọc `d` (state SENT/RETRY_SCHEDULED, `attemptCount=4`), `shouldRetry(3)=false` → `markFailed(t,d)` → `deliveries.updateState(d.id(), FAILED, null, d.attemptCount())`. `updateState` không guard. Nếu ACK commit RECEIVED **sau** bước đọc `d` nhưng **trước** UPDATE FAILED, trạng thái bị lùi RECEIVED→FAILED. Dưới tranh chấp ghi, `updateState` có thể chờ khóa tới `busy_timeout=5000` (`Db.java:28`) — cửa sổ race giãn từ vài ms lên vài giây, ACK dễ dàng chen vào. Thêm nữa `d.attemptCount()` là giá trị đọc cũ: nếu dispatcher đang `sendPush` tăng count đồng thời (xem M2), `markFailed` ghi count cũ → **lùi attempt_count**. Lưu ý rank guard của `recordAck` đặt FAILED=3 ngang ACKNOWLEDGED=3 và trên RECEIVED=2, nên kể cả guard rank cũng không chặn RECEIVED→FAILED; phải guard theo *tập trạng thái dispatch-owned*.
- **Hệ quả.** Alert đã ACK hiển thị FAILED; metric/zone counter/audit sai; `attempt_count` có thể lùi (phá vỡ ngân sách bền vững). Cùng lớp bất biến với H1.
- **Khuyến nghị.** `markFailed` chỉ chuyển từ tập dispatch-owned: thêm `updateDispatchState(id, FAILED, null, attemptCount)` (guard sẵn có) thay `updateState`; đọc lại `attemptCount` trong cùng transaction hoặc dùng `attempt_count` hiện tại của DB. Cân nhắc tách rank FAILED xuống dưới RECEIVED để ACK muộn luôn thắng (liên quan L6). Test: ACK chen giữa `findById` và `updateState` của `markFailed`, có mô phỏng busy-wait.

### H3 · Tái xác thực trên cùng kết nối: hỏng chỉ mục `byDeviceId`, gửi push chéo thiết bị / bỏ rơi delivery
- **Severity:** HIGH · **Trụ cột:** concurrency / thread-safety / idempotency danh tính
- **Vị trí:** `MessageRouter.java:86–87` (AUTH_OPERATOR/AUTH_DEVICE **không** chắn sau xác thực), `:165–196` (`authDevice`); `ClientSession.java:96–111` (`bindOperator`/`bindDevice` không guard rebind, `bindOperator` không xóa `deviceId`); `SessionRegistry.java:26–31` (`unregister` chỉ xóa `deviceId` hiện tại), `:34–40` (`deviceOnline`).
- **Cơ chế.** Một session DEVICE đã xác thực có thể gửi `AUTH_DEVICE` lần nữa với `deviceCode` khác (demo dùng **chung** credential `device-demo-secret` cho toàn fleet — `ServerStore.java:637` — nên mọi thiết bị đều tái xác thực được thành thiết bị khác). `bindDevice(B)` ghi đè `deviceId=B`; `deviceOnline` `put(B, session)` nhưng **không xóa khóa A cũ** (`byDeviceId[A]` vẫn trỏ session này). `unregister` khi đóng chỉ `remove(session.deviceId()=B, session)` → khóa A rò rỉ vĩnh viễn. `deviceSession(A)` trả session (chưa closed) → dispatcher gửi ALERT_PUSH của A vào socket của B. Tương tự `AUTH_OPERATOR` trên session DEVICE: `bindOperator` đặt kind=OPERATOR nhưng giữ `deviceId=A` và `byDeviceId[A]` → push của A gửi vào socket operator, còn `ack()` bị `requireDevice` chặn (kind OPERATOR) → delivery A không bao giờ ACK được → FAILED.
- **Hệ quả.** Rò rỉ nội dung alert chéo thiết bị/khu vực; delivery của danh tính cũ bị bỏ rơi (retry vô ích rồi FAILED); trạng thái session lai (vừa OPERATOR vừa mang deviceId). Với tới trivially trong demo (shared secret), và là lỗi logic bất kể credential.
- **Khuyến nghị.** Chắn `AUTH_*` khi `session.isAuthenticated()` (trả COMMAND_RESULT/ERROR `ALREADY_AUTHENTICATED`), hoặc quy trình rebind đầy đủ: `sessions.unregister` danh tính cũ + xóa mọi khóa chỉ mục cũ trước khi bind mới, và `bindOperator` xóa `deviceId/location/floor/zone`. Không cho phép một session mang hai danh tính. Test: device A auth → AUTH_DEVICE B → kiểm tra `deviceSession(A)` không trả session B và push A không tới B.

### H4 · Subscriber WS chậm đình trệ toàn bộ broadcast delta + chiếm vĩnh viễn thread timer
- **Severity:** HIGH · **Trụ cột:** backpressure / slow-client / availability
- **Vị trí:** `WsConnection.java:31–55` (`sendText` ghi blocking dưới `writeLock`, không timeout); `StatusFeedService.java:235–238` (vòng lặp sequential gọi `sendText` từng subscriber trong `broadcastDelta`); `AegisServer.java:84` (`timers = newScheduledThreadPool(2)`), `:122–123` (`broadcastDelta` chạy trên pool timer chung); `StatusFeedServer.java:74–137` (không `setSoTimeout`).
- **Cơ chế.** `broadcastDelta` chạy trên pool timer 2 thread, duyệt subscriber **tuần tự** và gọi `conn.sendText(text)` — ghi socket blocking. Một subscriber không đọc (TCP zero-window) khiến `out.write` chặn **vô hạn** (Java blocking socket không có timeout ghi; `SO_RCVTIMEO`/`soTimeout` chỉ áp dụng đọc). Hệ quả: (i) vòng lặp kẹt ở subscriber đầu → **mọi** subscriber khác ngừng nhận delta; (ii) một thread timer bị chiếm vĩnh viễn (scheduleAtFixedRate không chạy chồng cùng task, nên thread còn lại gánh sweep/broadcast/retry/expiry/metrics → quá tải, delay dây chuyền). Mặt phẳng WS **không xác thực** (trusted-LAN, `StatusFeedServer.java:25–31`) nên bất kỳ client LAN nào cũng gây được. Thread timer còn lại phải gánh toàn bộ sweep/broadcast/retry/expiry/metrics; dưới tải retry lớn kèm tranh chấp SQL (busy chờ tới 5s mỗi tác vụ), mặt phẳng timer suy giảm dây chuyền dù chỉ cần **một** subscriber chậm.
- **Hệ quả.** DoS mặt phẳng trạng thái + suy giảm retry/heartbeat/expiry toàn server chỉ từ kết nối WS không xác thực. Khác với mặt phẳng TLS đã có hàng đợi bounded + chính sách disconnect (A13/A14), mặt phẳng WS **không** có backpressure.
- **Khuyến nghị.** Áp dụng cùng mô hình TLS cho WS: hàng đợi writer bounded per-connection + thread writer riêng + chính sách drop/disconnect khi đầy; hoặc NIO/ghi bất đồng bộ có timeout; tách `broadcastDelta` khỏi pool timer heartbeat/retry (executor riêng); đặt `soTimeout`/deadline cho handshake và ghi. Giới hạn thời gian ghi mỗi subscriber để vòng lặp không kẹt. Test: một WS client không đọc → các client khác vẫn nhận delta và heartbeat sweep vẫn chạy.

---

## 4. FINDINGS — MEDIUM

### M1 · Không có reconciliation runtime + nuốt lỗi SQL → delivery committed có thể lặng lẽ kẹt tới restart
- **Severity:** MEDIUM · **Trụ cột:** recovery / durability
- **Vị trí:** `FanoutEngine.java:137–139` (`track` bắt `SQLException` chỉ LOG.warn), `:246–251` (`sendPush` nuốt lỗi rồi **vẫn gửi frame**), `:323–325` (`checkDelivery` chỉ bắt `SQLException`); `TransactionalPublisher.java:111` (enqueue sau commit); `AegisServer.java:120–125` (không timer reconciliation nào).
- **Cơ chế.** Sau commit, bàn giao vào scheduler in-memory là *lossy*: `track` gặp `SQLException` (vd `alerts.findById` busy quá 5s) → bỏ qua, delivery nằm PENDING trong DB nhưng không được track/offer → không dispatch, không retry, không FAILED cho tới lần recovery kế tiếp (restart). `sendPush` nếu cả hai ghi DB lỗi vẫn offer frame (attempt không bền vững). `checkDelivery` nếu `attemptCount=0` (hai ghi đều lỗi) → `retriesConsumed=-1` → `RetryPolicy.requireNonNegative` ném `IllegalArgumentException` **không** được bắt (chỉ bắt SQLException) → task timer one-shot chết lặng, chu kỳ retry dừng. Không có cơ chế quét đối chiếu PENDING/SENT quá hạn ↔ tracked khi đang chạy.
- **Hệ quả.** Mất giao hàng tạm thời/lặng lẽ dưới tranh chấp DB; delivery kẹt trạng thái; ngân sách retry không được thực thi đúng ca lỗi. Chỉ tự lành khi restart.
- **Khuyến nghị.** (i) Timer reconciliation định kỳ: quét delivery `isActive()` quá hạn (vd `next_retry_at < now` hoặc PENDING lâu) không có trong `tracked` → re-enqueue. (ii) `track`/`sendPush` retry ghi DB hoặc đánh dấu để reconciliation; không gửi frame khi ghi attempt bền vững thất bại. (iii) `checkDelivery` bắt `RuntimeException`; clamp `retriesConsumed ≥ 0`. (iv) Cân nhắc outbox bền vững thay cho bàn giao in-memory thuần.

### M2 · Double-dispatch: `resumeDevice`/`checkDelivery`/`recoverEnqueue` offer trùng Dispatch, không có in-flight guard
- **Severity:** MEDIUM · **Trụ cột:** concurrency / ngân sách retry
- **Vị trí:** `FanoutEngine.java:318–319` (checkDelivery offer), `:352–365` (resumeDevice offer), `:113–115` (recoverEnqueue), `:176–228` (dispatch không kiểm "đang bay"); `PriorityScheduler.java:45–50` (offer không khử trùng theo deliveryId).
- **Cơ chế.** Nhiều nguồn cùng offer một `Dispatch(deliveryId)` (timer retry + reconnect resume + recovery). Dispatcher xử lý tuần tự nhưng **không** có cờ in-flight: dispatch thứ nhất `sendPush` (attempt n+1, state SENT), dispatch thứ hai đọc lại thấy SENT (chưa settled) → `sendPush` lần nữa (attempt n+2) **ngay lập tức**, bỏ qua cửa sổ ACK. `generations` chỉ chặn timer cũ, không chặn dispatch trùng trong hàng đợi.
- **Hệ quả.** Push trùng liên tiếp (client phải dedup), đốt sớm ngân sách retry (có thể FAILED trước khi thiết bị kịp ACK qua đủ cửa sổ 3/6/12s), tăng tải DB/socket. Không phá bất biến trạng thái nhưng sai ngữ nghĩa thời gian retry.
- **Khuyến nghị.** Khử trùng trong scheduler theo deliveryId, hoặc cờ `inFlight`/`dispatching` per-delivery (CAS) bỏ qua dispatch trùng; `resumeDevice` chỉ offer delivery chưa có dispatch pending. Test: reconnect + timer trùng lúc → đếm attempt thực tế.

### M3 · ALERT_UPDATE đồng thời: `newRevision` tính ngoài transaction → va PK → INTERNAL thay vì CONFLICT; không re-check ACTIVE trong transaction
- **Severity:** MEDIUM · **Trụ cột:** atomicity / concurrency
- **Vị trí:** `AlertService.java:177–191` (`newRevision = alert.currentRevision()+1` đọc ngoài txn; `applyUpdate`); `ServerStore.java:320–357` (không kiểm `state='ACTIVE'` trong txn, UPDATE bump vô điều kiện `:330–335`); schema `002:55` `PRIMARY KEY(alert_id,revision)`.
- **Cơ chế.** Hai UPDATE khác requestId trên cùng alert cùng đọc `currentRevision=1` → cùng tính `newRevision=2`. T1 commit revision 2; T2 `insertRevisionOn(alertId,2)` va PK → ROLLBACK → `operationWinner(requestId2)` rỗng → trả **INTERNAL** "update failed" (không phải CONFLICT). Không có optimistic-lock (`WHERE current_revision=expected`). Ngoài ra `applyUpdate` không re-check alert còn ACTIVE trong txn: một CANCEL đồng thời commit giữa pre-check (`AlertService.java:180`) và applyUpdate → bump revision + tạo delivery PENDING cho alert đã CANCELLED (fanout bị `track` chặn nhưng DB nhất thời bất nhất, `current_revision` tăng trên alert chết).
- **Hệ quả.** Lỗi ngữ nghĩa (INTERNAL giả) dưới đồng thời; cập nhật mất (lost update) không được báo đúng; revision/delivery mồ côi trên alert đã hủy. Không hỏng dữ liệu nhờ PK/UNIQUE nhưng hành vi sai hợp đồng.
- **Khuyến nghị.** Tính `newRevision` **trong** `BEGIN IMMEDIATE` (đọc `current_revision` trên cùng connection) hoặc bump có guard `UPDATE alerts SET current_revision=? WHERE id=? AND state='ACTIVE' AND current_revision=?` rồi kiểm số dòng → CONFLICT khi 0. Re-check ACTIVE trong txn. Phân biệt lỗi PK thành CONFLICT.

### M4 · INCIDENT_TRANSITION: state-machine kiểm ngoài transaction, UPDATE vô điều kiện → lost update + timeline bất nhất
- **Severity:** MEDIUM · **Trụ cột:** atomicity / concurrency
- **Vị trí:** `IncidentService.java:69–81` (đọc `row.state()`, kiểm `TRANSITIONS` ngoài txn rồi mới gọi atomic write); `ServerStore.java:443–451` (`UPDATE incidents SET state=? ... WHERE id=?` vô điều kiện).
- **Cơ chế.** Hai transition đồng thời cùng đọc state OPEN, cả hai hợp lệ theo ma trận (vd OPEN→CONTAINED và OPEN→INVESTIGATING), cả hai commit; UPDATE vô điều kiện → last-writer-wins. Timeline ghi `from=OPEN` cho cả hai event dù state cuối chỉ một → chuỗi event không khớp state thực. `transitionIncidentAtomic` tuy nguyên tử (state+event) nhưng **không** mang điều kiện `state=expected`.
- **Hệ quả.** Mất chuyển trạng thái, timeline/audit bất nhất dưới nhiều operator. Không phá tính nguyên tử của từng write nhưng phá tính đúng đắn của state-machine dưới đồng thời.
- **Khuyến nghị.** `UPDATE incidents SET state=?, closed_at=? WHERE id=? AND state=?` (expected) trong txn; 0 dòng → CONFLICT. Đưa kiểm tra transition vào cùng transaction (đọc state trên connection txn).

### M5 · Publish: side-effect incident sau commit lỗi → INTERNAL giả dù alert đã phát; không tiền kiểm incidentId
- **Severity:** MEDIUM · **Trụ cột:** atomicity / idempotency
- **Vị trí:** `AlertService.java:150–154` (`linkIncidentAlert` + `insertIncidentEvent` sau commit), `:161–165` (bắt SQLException → INTERNAL); `ServerStore.java:253–261` (link FK tới incidents).
- **Cơ chế.** Sau khi publish COMMIT + enqueue, nếu `incidentId` không tồn tại (FK) hoặc ghi incident event lỗi → `SQLException` bị bắt → trả `COMMAND_RESULT{ok:false, code:INTERNAL}` **dù alert đã bền vững và đã fanout**. Không tiền kiểm incidentId tồn tại/ thuộc building trước publish. Client thấy lỗi nhưng alert tồn tại; replay cùng requestId trả duplicate (alert gốc) — idempotency cứu nhất quán nhưng ngữ nghĩa lỗi sai, và `metrics.incPublished`/audit PUBLISH bị bỏ qua.
- **Hệ quả.** Thông báo lỗi giả, thiếu audit/metric cho publish thành công; lệch incident timeline.
- **Khuyến nghị.** Tiền kiểm `incidentId` (tồn tại + cùng building) trước publish → BAD_PAYLOAD/NOT_FOUND sớm. Coi side-effect incident sau commit là best-effort: lỗi chỉ LOG + audit cảnh báo, không đổi kết quả publish thành INTERNAL; hoặc đưa link vào cùng transaction publish khi incidentId hiện diện.

### M6 · Pre-auth slowloris: TTL tuyệt đối chỉ kiểm ở biên frame; handshake WS không timeout, cap chỉ đếm sau upgrade
- **Severity:** MEDIUM · **Trụ cột:** backpressure / slow-client / security
- **Vị trí:** `TlsGateway.java:116–141` (TTL kiểm **sau** `router.route`, tức sau frame hoàn chỉnh; `readFrame` blocking trong `FrameCodecImpl.java:64–73` với soTimeout theo từng `read`); `StatusFeedServer.java:74–108` (không `setSoTimeout`, `BufferedReader.readLine` chờ vô hạn; cap `subscriberCount()` chỉ đếm kết nối đã upgrade).
- **Cơ chế.** Kết nối pre-auth nhỏ giọt byte (mỗi <10s một byte) khiến mỗi `read` thành công trong soTimeout → không hết hạn; frame chưa hoàn chỉnh → `router.route` chưa chạy → TTL tuyệt đối không được kiểm → kết nối sống vô hạn (tới khi chạm cap 512). Mặt phẳng WS: handshake không timeout → kết nối nửa chừng giữ fd/thread vô hạn; cap 64 không áp cho giai đoạn handshake → mở vô số kết nối chờ.
- **Hệ quả.** Chiếm chỗ kết nối/fd (DoS trong giới hạn cap), làm loãng tài nguyên; WS không bị cap ở pha handshake.
- **Khuyến nghị.** Lên lịch timer đóng kết nối tại `createdAt+preAuthTimeoutMs` ngay khi accept (độc lập biên frame); đặt `soTimeout` cho handshake WS + deadline tuyệt đối; đếm cả kết nối đang handshake vào cap.

### M7 · Recovery đốt ngân sách retry khi offline ngay sau restart; bỏ qua `next_retry_at`; hai lần rút jitter lệch nhau
- **Severity:** MEDIUM · **Trụ cột:** recovery / ngân sách retry
- **Vị trí:** `AegisServer.java:110` (recover trước khi mở cổng), `FanoutEngine.java:113–115, 176–214, 276–287` (recoverEnqueue → dispatch ngay → offlineAttempt), `:257` và `:297` (hai lần `windowMs` rút jitter khác nhau), `RecoveryScanner.java:34–41` (không xét `next_retry_at`).
- **Cơ chế.** Delivery phục hồi được offer/dispatch **ngay** trước khi thiết bị reconnect → `offlineAttempt` đốt attempt (OFFLINE) + scheduleRetry; chuỗi 3/6/12s có thể đưa delivery tới FAILED trong ~30s sau restart dù thiết bị reconnect muộn hơn. `next_retry_at` bền vững bị bỏ qua khi recovery (dispatch tức thì). Ngoài ra `windowMs(attempt)` được gọi hai lần (đường REJECTED_SLOW/offline ghi `next_retry_at` một giá trị jitter, `scheduleCheck` rút giá trị khác) → lịch bền vững và timer in-memory lệch nhau trong cùng bậc.
- **Đã-đạt vs rủi ro.** *Ngân sách bền vững đúng* (attemptCount không vượt 4, A7/A8); *ngữ nghĩa thời gian* suy giảm: FAILED hàng loạt cho thiết bị offline sau restart, SYNC vẫn thấy alert (deliveryState FAILED) nhưng dashboard `failed` tăng vọt.
- **Khuyến nghị.** Recovery nên tôn trọng `next_retry_at` (lên lịch theo đó thay vì dispatch ngay) và/hoặc có grace-period chờ reconnect trước khi đốt attempt OFFLINE; rút jitter **một lần** rồi truyền cho cả `next_retry_at` lẫn timer. Cân nhắc không đếm attempt OFFLINE ngay sau recovery tới lần online đầu tiên.

### M8 · Pool timer 2 thread gánh retry + heartbeat + rebuild status N+1 + expiry + metrics → head-of-line blocking
- **Severity:** MEDIUM · **Trụ cột:** concurrency / backpressure / scalability
- **Vị trí:** `AegisServer.java:84, 120–125`; `StatusFeedService.java:276–316` (`rebuildAlertCache` N+1: `activeAlerts` findById từng alert + findRevision + targets), `ServerStore.java:465–481, 502–528` (N+1); `FanoutEngine.java:299` (mọi checkDelivery chạy trên pool này).
- **Cơ chế.** Mọi tác vụ định kỳ + mọi retry check dùng chung 2 thread. `rebuildAlertCache` tuy coalesce ≤1 lần/500ms nhưng mỗi lần chạy O(N) truy vấn (N alert active) trên thread timer; dưới flood alert + nhiều delivery retry, hàng đợi task timer dồn ứ → heartbeat sweep/broadcast trễ → suspect giả/disconnect hàng loạt, retry trễ. Một tác vụ chặn (SQL busy 5s, hoặc H4) kéo trễ toàn bộ.
- **Hệ quả.** Suy giảm dây chuyền dưới tải; heartbeat không đúng hạn gây disconnect oan; retry không đúng cửa sổ.
- **Khuyến nghị.** Tách executor: heartbeat/sweep riêng, retry-check riêng (hoặc virtual-thread scheduler), status rebuild riêng; gộp `rebuildAlertCache` thành truy vấn bulk JOIN alerts+revisions+targets (một query) thay N+1; giới hạn số retry check đồng thời.

### M9 · Hàng đợi writer bounded theo SỐ frame, không theo BYTE → trần bộ nhớ lớn
- **Severity:** MEDIUM · **Trụ cột:** backpressure / resource
- **Vị trí:** `BoundedWriterQueue.java:30–59` (capacity đếm phần tử); `Protocol.java:4` (`MAX_FRAME_BYTES=65536`); `TlsGateway.java:93` (`maxConnections=512`); `MessageRouter.java:508–512` (reply CRITICAL gồm SYNC_RESULT/STATUS_SNAPSHOT lớn).
- **Cơ chế.** 256 frame × tối đa 64KiB ≈ 16MiB/session; × 512 session ≈ 8GiB worst-case (chưa kể envelope/Jackson overhead). Frame lớn (SYNC_RESULT nhiều alert, STATUS_SNAPSHOT) ở bậc CRITICAL chiếm chỗ dài. Không có ngân sách byte hay giới hạn kích thước frame riêng cho hàng đợi.
- **Hệ quả.** Áp lực bộ nhớ/OOM dưới flood frame lớn từ operator/device đã xác thực; slow-client policy chỉ kích hoạt khi đủ 256 frame regardless byte.
- **Khuyến nghị.** Thêm ngân sách byte per-session (evict/reject khi vượt), hoặc giảm capacity cho frame lớn; chặn kích thước payload hợp lý cho từng loại reply; theo dõi `writerQueue` byte ước lượng.

### M10 · Counter zone + metric + AckLedger chỉ in-memory, không rebuild từ DB khi restart → severity sai, mất ALERT_FULLY_ACKED
- **Severity:** MEDIUM · **Trụ cột:** recovery / observability
- **Vị trí:** `StatusFeedService.java:66–71` (`init` chỉ nạp topology + alertCache, không nạp counter), `:142–157` (`zoneNode` đọc counter in-memory), `ZoneStatus.java:22–31` (severity phụ thuộc received/acknowledged/failed); `FanoutEngine.java:80–81` (receivedCounted/failedCounted in-memory), `:142–152` (`openLedger` expected từ deliveries, không nạp ack đã có), `:390–392` (ALERT_FULLY_ACKED).
- **Cơ chế.** Sau restart, received/acknowledged/failed = 0 → `deriveSeverity` trả "online"/"offline" thay vì phản ánh lịch sử failed/ack thật; AckLedger mở với expected gồm thiết bị **đã ACK trước crash** nhưng `acked` rỗng → `complete()` không bao giờ đúng trừ khi mọi thiết bị re-ACK (duplicate ACK có gọi `onAck` nhưng client có thể không gửi lại) → mất audit ALERT_FULLY_ACKED.
- **Hệ quả.** Dashboard sai severity sau recovery; thiếu sự kiện fully-acked; metric reset (đã biết là in-memory) nhưng không được tái tạo từ trạng thái bền vững.
- **Khuyến nghị.** Khi `init`, rebuild counter zone từ DB (COUNT delivery_attempts/acks/failed deliveries theo zone) và seed AckLedger.acked từ `device_acks` hiện có; hoặc bền vững hóa counter. Phân biệt rõ "monotonic trong phiên" vs "monotonic vĩnh viễn" trong tài liệu.

### M11 · INCIDENT_EVENT broadcast tới MỌI operator, không lọc building/floor theo grant
- **Severity:** MEDIUM · **Trụ cột:** security scope / concurrency
- **Vị trí:** `IncidentService.java:93–108` (`broadcastEvent` duyệt `sessions.all()`, gửi mọi OPERATOR, không kiểm grant).
- **Cơ chế.** Khác với STATUS_SUBSCRIBE có lọc scope (A11), INCIDENT_EVENT gửi tới mọi operator đã xác thực bất kể building/floor grant. Trong triển khai đa building (config `buildingId`), operator building này nhận sự cố building khác. Demo một building nên hệ quả thực tế hạn chế, nhưng là khoảng trống phân quyền nhất quán.
- **Hệ quả.** Rò rỉ thông tin sự cố chéo phạm vi grant (đa building); gửi thừa.
- **Khuyến nghị.** Lọc người nhận theo `rbac.canPublish`/grant building (hoặc scope read tương ứng) trước khi gửi; cân nhắc bậc ưu tiên và hàng đợi (event WARNING có thể bị evict — xem L4).

### M12 · `Db.open` tuần tự hóa MỌI kết nối trên mutex per-file + không pooling; busy_timeout 5s chặn reader thread
- **Severity:** MEDIUM · **Trụ cột:** concurrency / scalability / backpressure
- **Vị trí:** `Db.java:31, 45–69` (`OPEN_LOCKS` per-url, mọi `open()` lấy khóa + connect + 3 pragma); repo mở kết nối ngắn hạn mỗi lời gọi (vd `DeliveryRepo` mỗi phương thức một `db.open`); `FanoutEngine.dispatch` ~6 open/delivery; `Db.java:28` busy 5000.
- **Cơ chế.** Mọi truy vấn DB đi qua một mutex toàn cục per-file lúc mở kết nối (connect + pragma). Dưới tải cao (512 session × lệnh, dispatcher ~6 open/delivery, audit mỗi lệnh một open) đây là trần throughput; tranh chấp ghi `BEGIN IMMEDIATE` chờ tới 5s chặn reader virtual-thread (router), trễ reply HEARTBEAT/ACK. Không có connection pool/tái sử dụng.
- **Hệ quả.** Nghẽn cổ chai throughput; trễ dây chuyền dưới flood; heartbeat suspect giả khi busy kéo dài. (JDK 24 `synchronized` không pin carrier — JEP 491 — nên không chết carrier, nhưng vẫn tuần tự.)
- **Khuyến nghị.** Pool kết nối (vd HikariCP tối thiểu) hoặc tái sử dụng kết nối per-thread; giảm số open/delivery (gộp truy vấn dispatch); cân nhắc `busy_timeout` thấp hơn + retry có kiểm soát cho đường reader để không chặn heartbeat; tách kết nối đọc/ghi.

### M13 · `acceptLoop` chết vĩnh viễn khi IOException thoáng qua → server ngừng nhận kết nối
- **Severity:** MEDIUM · **Trụ cột:** availability / backpressure
- **Vị trí:** `TlsGateway.java:109–112` (IOException khi `running` → LOG rồi `return`); `StatusFeedServer.java:67–70` (tương tự).
- **Cơ chế.** Một IOException accept thoáng qua (vd EMFILE khi cạn fd do slowloris M6, hoặc lỗi mạng tạm) khiến vòng accept `return` hẳn trong khi `running=true` và server vẫn chạy → không nhận kết nối mới cho tới restart. Không phân biệt lỗi "socket đã đóng khi stop" (hợp lệ) vs lỗi thoáng qua (nên tiếp tục).
- **Hệ quả.** Mất khả năng phục vụ kết nối mới (availability) sau một lỗi tạm.
- **Khuyến nghị.** Chỉ thoát khi `!running`/serverSocket đã đóng; với lỗi thoáng qua thì LOG + backoff + `continue`; đóng socket lỗi cụ thể thay vì chết cả vòng.

---

## 5. FINDINGS — LOW

### L1 · DedupIndex gần như dư thừa trong luồng hiện tại + không được mark bởi đường non-fresh
- **Severity:** LOW · **Trụ cột:** idempotency
- **Vị trí:** `FanoutEngine.java:215–226` (chỉ gọi `dedup.seen` khi `t.fresh() && attemptCount==0`), `:113–115` (recoverEnqueue fresh=false), `AlertService.java:192` (update dùng recoverEnqueue); `DedupIndex.java:21–34`.
- **Cơ chế.** `seen()` vừa kiểm vừa mark, nhưng chỉ được gọi ở dispatch đầu của delivery *fresh*. Delivery revision mới qua `recoverEnqueue` (update/recovery) **không** gọi `seen` → `highestRevision` không cập nhật theo revision mới. Gate vì thế không chặn được "fresh rev1 muộn sau khi rev2 đã gửi qua recoverEnqueue" như javadoc tuyên bố; thực tế chính **supersede check** (`FanoutEngine.java:197–205`) và UNIQUE(alert_id,revision,device_id) mới là guard. Với fresh delivery, attemptCount==0 + cùng revision chỉ xảy ra một lần (putIfAbsent + UNIQUE) nên gate hầu như không kích hoạt.
- **Khuyến nghị.** Hoặc mark `dedup.seen` ở **mọi** dispatch thành công (để gate có ý nghĩa monotonic thật), hoặc lược bỏ gate và dựa supersede + UNIQUE (giảm phức tạp). Bổ sung test chứng minh gate thực sự chặn được ca stale.

### L2 · Chỉ mục in-memory không prune + orphan `generations` do race track/untrack
- **Severity:** LOW · **Trụ cột:** resource / concurrency
- **Vị trí:** `FanoutEngine.java:80–81` (receivedCounted/failedCounted không xóa), `:133` (generations.putIfAbsent sau tracked.putIfAbsent), `:344–347` (untrack); `AckLedger.java:38` (entries không prune); `DedupIndex.java:18` (highestRevision không prune).
- **Cơ chế.** Các set/map tăng đơn điệu theo tổng delivery/alert suốt uptime → rò rỉ bộ nhớ chậm. Race: `track` putIfAbsent tracked xong, ACK tới `untrack` (xóa tracked+generations), `track` tiếp tục `generations.putIfAbsent` → tạo entry mồ côi không ai dọn (dispatch sau đó thấy tracked null → return).
- **Khuyến nghị.** Prune định kỳ các key đã settled; dọn generations trong dispatch khi tracked null; giới hạn kích thước hoặc TTL cho DedupIndex/AckLedger.

### L3 · SLOW_CLIENT_DISCONNECT giả khi offer đua với close
- **Severity:** LOW · **Trụ cột:** backpressure / observability
- **Vị trí:** `ClientSession.java:71–78` (kiểm `closed` rồi mới `queue.offer`), `BoundedWriterQueue.java:44` (offer khi closed → REJECTED_SLOW).
- **Cơ chế.** `send` thấy closed=false, sau đó `close()` chạy (closed=true, queue.close), rồi `offer` trả REJECTED_SLOW vì queue đã closed → `onSlowClient` tăng metric + audit SLOW_CLIENT_DISCONNECT cho một session đóng bình thường (không chậm).
- **Khuyến nghị.** Phân biệt REJECTED_SLOW do *đầy* vs do *closed*: kiểm `queue.isClosed()`/`session.isClosed()` trước khi gọi onSlowClient; chỉ audit/metric khi thật sự tràn.

### L4 · `fanoutControl` bỏ qua OfferResult; CANCEL_PUSH trùng deviceIds; `simulation` hardcode true
- **Severity:** LOW · **Trụ cột:** idempotency / backpressure
- **Vị trí:** `FanoutEngine.java:405–412` (không kiểm kết quả send); `ServerStore.java:376–382` (deviceIds gom mọi revision → trùng thiết bị); `AlertService.java:235` (`payload.put("simulation", true)` bất kể alert thật).
- **Cơ chế.** CANCEL_PUSH gửi ở priority alert; nếu bị evict (EVICTED_LOWER) hoặc REJECTED_SLOW thì mất, không retry (chỉ SYNC tombstone bù). `findByAlert` trả delivery mọi revision nên một thiết bị nhận nhiều CANCEL_PUSH trùng. Payload cancel luôn `simulation=true` kể cả alert thật → sai metadata.
- **Khuyến nghị.** Kiểm OfferResult + audit frame control bị mất; khử trùng deviceIds; truyền `simulation` thật từ alert/revision metadata.

### L5 · Thứ tự CANCEL_PUSH vs ALERT_PUSH đang bay không đảm bảo → client có thể hiển thị lại alert đã hủy
- **Severity:** LOW · **Trụ cột:** exactly-once UI / concurrency
- **Vị trí:** `AlertService.java:227–238` (cancelAlert rồi fanoutControl sau commit); `FanoutEngine.java:189–227` (dispatch kiểm ACTIVE trước sendPush, cancel có thể commit giữa kiểm và offer).
- **Cơ chế.** Dispatch vượt qua kiểm alert-ACTIVE rồi cancel commit + CANCEL_PUSH offer trước; push của alert cũ offer **sau** → thiết bị nhận CANCEL_PUSH rồi ALERT_PUSH → UI tối thượng có thể hiển thị lại alert đã hủy tức thời. DB delivery đã CANCELLED nên SYNC không hiển thị lại, nhưng đường live không có hàng rào thứ tự per-device.
- **Khuyến nghị.** Client phải chắn tombstone/revision (bỏ qua push cho alert đã hủy/cũ hơn) — kiểm chứng `AlertClientEngine`; server có thể gắn sequence per-alert hoặc re-check sau offer. Tài liệu hóa hợp đồng "client đơn điệu theo revision/tombstone".

### L6 · ACK muộn sau FAILED được ghi nhưng state giữ FAILED → lệch server/client
- **Severity:** LOW · **Trụ cột:** idempotency / rank guard
- **Vị trí:** `DeliveryRepo.java:198–211, 249–257` (FAILED rank 3 = ACKNOWLEDGED rank 3; RECEIVED rank 2 < 3 nên ACK muộn không nâng FAILED→RECEIVED); `FanoutEngine.java:230–236` (FAILED settled).
- **Cơ chế.** Delivery FAILED (cạn ngân sách) nhưng thiết bị thực nhận và ACK muộn: ack row được insert (kind mới) nhưng rank guard không cho FAILED→RECEIVED/ACKNOWLEDGED (3<3 sai). SYNC trả deliveryState FAILED trong khi client hiển thị đã nhận/đã ack.
- **Khuyến nghị.** Cho ACK muộn nâng FAILED→RECEIVED/ACKNOWLEDGED (đặt rank FAILED dưới RECEIVED, hoặc guard riêng cho phép ACK thắng FAILED) **đồng thời** giữ FAILED terminal cho ngân sách retry (không attempt mới). Phân biệt "terminal cho retry" vs "terminal cho state".

### L7 · `expireOverdue` select-rồi-update không nguyên tử; không tombstone delivery khi expiry
- **Severity:** LOW · **Trụ cột:** atomicity
- **Vị trí:** `ServerStore.java:484–499` (SELECT ids rồi `alerts.updateState` từng id, connection riêng); `AlertRepo.java:111–118` (updateState vô điều kiện); `AegisServer.java:162–175`.
- **Cơ chế.** Giữa SELECT và UPDATE, một CANCEL đồng thời có thể commit → `updateState(EXPIRED)` đè CANCELLED (đều tombstone nhưng đổi nhãn). Delivery của alert expired không bị tombstone ngay tại expiry (chỉ dựa guard dispatch `FanoutEngine.java:192–196` hủy khi dispatch kế tiếp) → nhất thời lệch.
- **Khuyến nghị.** Expiry một transaction `UPDATE alerts SET state='EXPIRED' WHERE state='ACTIVE' AND expires_at<=?` (guard ACTIVE) + tombstone delivery kèm theo; trả ids bằng `RETURNING` hoặc đọc trong txn.

### L8 · `recordAttempt` + chuyển state là hai transaction riêng; cửa sổ crash ghi attempt không tăng count
- **Severity:** LOW · **Trụ cột:** recovery / atomicity
- **Vị trí:** `FanoutEngine.java:246–251` (recordAttempt rồi updateDispatchState, hai `db.open`), `:280–285` (recordAttempt OFFLINE rồi scheduleRetry); `DeliveryRepo.java:115–130` (recordAttempt chỉ INSERT, không tăng attempt_count).
- **Cơ chế.** Crash giữa hai ghi → attempt row tồn tại nhưng `attempt_count` chưa tăng → recovery cho thêm một attempt ngoài ngân sách dự kiến. `markFailed` ghi `d.attemptCount()` đọc cũ có thể lùi count (xem H2). `recordAttempt` không tự tăng count nên count phụ thuộc ghi thứ hai thành công.
- **Khuyến nghị.** Gói attempt + chuyển state trong một transaction (insertOn + update có guard trên cùng connection); hoặc để `recordAttempt` tăng `attempt_count` nguyên tử và state suy ra từ count.

### L9 · WsConnection: vài điểm chưa tuân thủ RFC6455 + BufferedReader có thể nuốt frame đầu
- **Severity:** LOW · **Trụ cột:** protocol / backpressure
- **Vị trí:** `WsConnection.java:97, 108–114` (chấp nhận frame client **không mask** — RFC yêu cầu server đóng), `:115–124` (bỏ qua continuation/fragment), `:57–69` (ping payload >125 → pong ghi độ dài 1 byte sai), `:133–141` (`close()` check-then-act không nguyên tử); `StatusFeedServer.java:78–89` (`BufferedReader` có thể đọc trước (read-ahead) nuốt frame WS đầu nếu client pipeline).
- **Khuyến nghị.** Đóng kết nối khi client frame không mask; hỗ trợ fragment hoặc đóng rõ ràng; chặn ping>125; `close()` dùng CAS; đọc header HTTP byte-by-byte trên raw stream (không buffer) hoặc chia sẻ buffered stream cho WsConnection.

### L10 · Cap subscriber WS kiểm-rồi-thêm không nguyên tử → cap mềm
- **Severity:** LOW · **Trụ cột:** backpressure
- **Vị trí:** `StatusFeedServer.java:100–120` (`subscriberCount()>=64` kiểm trước, `feed.subscribe` thêm sau; nhiều handle chạy song song trên virtual thread).
- **Khuyến nghị.** Dùng semaphore/atomic counter cho số subscriber (acquire trước upgrade, release khi unsubscribe) để cap cứng.

### L11 · `grantsFor` gộp building từ grant floor → SECURITY_OPERATOR grant-floor có thể lọt coversBuilding
- **Severity:** LOW · **Trụ cột:** security scope (latent)
- **Vị trí:** `RbacService.java:39–48` (grant có floorId vẫn `buildings.add(buildingId)`), `ScopedRBAC.java:29` (SECURITY_OPERATOR chỉ kiểm `coversBuilding`).
- **Cơ chế.** Một row grant floor-only gán cho SECURITY_OPERATOR sẽ cho `coversBuilding=true` → publish building-wide dù grant chỉ floor. Hiện không có lệnh quản trị grant qua protocol (chỉ seed DB) nên latent, nhưng là nhập nhằng ngữ nghĩa building-wide vs floor-derived.
- **Khuyến nghị.** Tách tập building-wide grant (floorId null) khỏi building suy ra từ floor grant; SECURITY_OPERATOR kiểm đúng loại grant.

### L12 · `resolveScope`: zoneCode không kèm floorNumber bị lặng lẽ bỏ qua → fanout building-wide
- **Severity:** LOW · **Trụ cột:** idempotency / ngữ nghĩa lệnh
- **Vị trí:** `AlertService.java:88–92` (chuẩn hóa zone chỉ khi floorNumber!=null), `:95–103` (resolveTargets: floorId null → resolveBuilding, zone bị bỏ qua).
- **Cơ chế.** Publish với `zoneCode="A"` nhưng thiếu `floorNumber` → floorId null → resolveBuilding → gửi toàn tòa nhà thay vì zone (RBAC vẫn chắn theo building/floor nên không vượt quyền, nhưng ngữ nghĩa mục tiêu sai bất ngờ).
- **Khuyến nghị.** BAD_PAYLOAD khi zoneCode hiện diện mà thiếu floorNumber; hoặc resolve zone xuyên floor. Tài liệu hóa yêu cầu cặp (floorNumber, zoneCode).

### L13 · Validation payload nhẹ: title/instruction rỗng được chấp nhận; expiresAt quá khứ không chặn
- **Severity:** LOW · **Trụ cột:** idempotency / correctness
- **Vị trí:** `Json.java:42–47` (`optText` chấp nhận chuỗi rỗng), `MessageRouter.java:330–331` (title/instruction qua `Json.text` chỉ chặn blank — *đúng*, nhưng update dùng optText cho phép rỗng `:369`); `TransactionalPublisher.java:52–70` (không kiểm expiresAt>now); `AlertService.java:109`.
- **Cơ chế.** ALERT_UPDATE với title="" ghi đè tiêu đề rỗng; publish với expiresAt quá khứ tạo alert ACTIVE tới kỳ sweep 1s (có thể push alert đã hết hạn trong cửa sổ nhỏ).
- **Khuyến nghị.** Chặn title/instruction rỗng ở update; từ chối expiresAt ≤ now tại publish (BAD_PAYLOAD) hoặc kiểm expiry tại dispatch.

### L14 · `SERVER_HELLO.statusFeed` hardcode `ws://localhost`
- **Severity:** LOW · **Trụ cột:** correctness vận hành
- **Vị trí:** `MessageRouter.java:139` (`"ws://localhost:" + config.wsPort()`).
- **Cơ chế.** Client ở xa nhận URL localhost → không kết nối được status feed; cũng không phản ánh host advertise thực.
- **Khuyến nghị.** Cấu hình host advertise (hoặc suy từ địa chỉ kết nối) thay vì localhost; cân nhắc wss nếu bảo mật hóa mặt phẳng WS.

### L15 · Hợp đồng RetryPolicy lệch javadoc + kẹp `maxRetries=0` thành 1 retry
- **Severity:** LOW · **Trụ cột:** ngân sách retry / contract
- **Vị trí:** `RetryPolicy.java:9–14, 54–57` (javadoc: maxAttempts = TỔNG attempt kể cả lần đầu; `shouldRetry(attemptsSoFar)=attemptsSoFar<maxAttempts`); `FanoutEngine.java:316–317` (nạp `retriesConsumed=attemptCount-1` ⇒ dùng maxAttempts như maxRetries); `AegisServer.java:87` (`Math.max(1, config.maxRetries())`); `ServerConfig.java:43` (cho phép maxRetries=0).
- **Cơ chế.** Với maxRetries=3 (default) hành vi đúng PROTOCOL (initial+3). Nhưng javadoc core nói maxAttempts là tổng attempt — mâu thuẫn cách engine dùng; ai "sửa" engine nạp attemptCount theo javadoc sẽ co ngân sách còn 3 tổng. Riêng maxRetries=0 bị kẹp thành 1 → `shouldRetry(0)=0<1=true` → vẫn 1 retry (2 attempt) thay vì 0 retry (1 attempt).
- **Khuyến nghị.** Thống nhất hợp đồng: hoặc RetryPolicy cho phép maxAttempts=0 và engine nạp retriesConsumed với maxRetries; hoặc đổi tên tham số engine-side cho khớp javadoc. Sửa clamp để maxRetries=0 ⇒ không retry. Thêm test biên maxRetries=0.

### L16 · Audit best-effort nuốt lỗi, không cùng transaction với lệnh
- **Severity:** LOW · **Trụ cột:** atomicity / compliance
- **Vị trí:** `AuditService.java:24–29` (bắt SQLException chỉ LOG); audit ghi connection riêng sau lệnh.
- **Cơ chế.** Lệnh thành nhưng audit append lỗi (busy) → mất dấu vết; audit không nguyên tử với thay đổi trạng thái. Đây là đánh đổi thiết kế (best-effort) nhưng cần lưu ý cho yêu cầu compliance.
- **Khuyến nghị.** Nếu cần audit bền vững đảm bảo: ghi audit trong cùng transaction lệnh (insertOn) hoặc hàng đợi audit bền vững + retry; tối thiểu tăng metric audit-failure.

### L17 · Topology snapshot tĩnh lúc khởi động (TargetResolver + StatusFeedService)
- **Severity:** LOW · **Trụ cột:** correctness vận hành
- **Vị trí:** `AegisServer.java:93` (`TargetResolver.of(store.deviceLocations())` một lần); `StatusFeedService.java:66–71` (deviceInfo/zonesByFloor/devicesTotalByZone nạp một lần).
- **Cơ chế.** Thiết bị thêm/bật/tắt hoặc đổi zone sau khởi động không phản ánh vào resolve target / dashboard (demo thế giới tĩnh nên chấp nhận). `broadcastDelta` so floors theo chỉ số (`:202–204`) giả định topology bất biến.
- **Khuyến nghị.** Tài liệu hóa giả định topology tĩnh; nếu hỗ trợ động, refresh snapshot theo sự kiện thay đổi topology + rebuild chỉ mục an toàn.

### L18 · Tư thế bảo mật demo: credential thiết bị chia sẻ, storepass mặc định, TLS mặc định
- **Severity:** LOW (demo có chủ đích, tài liệu hóa) · **Trụ cột:** security posture
- **Vị trí:** `ServerStore.java:637` (một hash Argon2 cho `device-demo-secret` toàn fleet); `ServerMain.java:25, 78–80` (storepass `aegis-demo` mặc định + in credential demo); `TlsGateway.java:60–76` (`SSLContext.getInstance("TLS")` theo mặc định JDK, không pin protocol/cipher, `setNeedClientAuth(false)`); `ServerConfig.java:70–72` (Argon2 t=2/m=16MiB/p=1 — dưới ngưỡng OWASP 19MiB).
- **Cơ chế.** Mô hình demo trusted-LAN: mọi thiết bị chung secret (liên quan H3), TLS chỉ xác thực server, tham số Argon2 nhẹ cho test. Không phải lỗi production-ready nhưng cần nêu rõ giới hạn.
- **Khuyến nghị.** Production: credential per-device, pin TLSv1.3/cipher, bật client-auth nếu cần, Argon2 ≥ OWASP (m≥19MiB), không in secret, quản lý storepass qua biến môi trường/két. Giữ nguyên cho demo nhưng ghi rõ trong SECURITY.md.

---

## 6. Phân tích chuyên đề (tổng hợp chéo)

### 6.1. Ma trận race ACK ↔ dispatch (bất biến "ACK chỉ tiến trạng thái")

| Đường ghi | Thread | Guard? | Kết luận |
|---|---|---|---|
| `recordAck` (ACK_RECEIVED/USER/CANCEL) | reader (router) | ownership + rank CASE trong BEGIN IMMEDIATE (`DeliveryRepo:198–211`) | **Đạt** (A1) — ACK không bị lùi bởi ACK khác |
| `updateDispatchState(SENT/RETRY_SCHEDULED)` | dispatcher | `state IN (PENDING,SENT,RETRY_SCHEDULED)` (`:235–247`) + commit-before-offer (`FanoutEngine:246–252`) | **Đạt** (A2) — dispatch muộn không lùi RECEIVED→SENT |
| `scheduleRetry` (offlineAttempt) | dispatcher | **KHÔNG** (`DeliveryRepo:104–113`) | **H1** — lùi RECEIVED→RETRY_SCHEDULED |
| `updateState(FAILED)` (markFailed) | timer | **KHÔNG** (`DeliveryRepo:90–101`; `FanoutEngine:330`) | **H2** — lùi RECEIVED/ACKNOWLEDGED→FAILED + lùi count |
| `updateState(CANCELLED)` (supersede/tombstone) | dispatcher | không guard, nhưng CANCELLED rank 4 (cao nhất) | Đơn điệu rank OK; chỉ lùi `attempt_count` đọc cũ (L8) |
| `cancelDeliveryOn` (applyCancel/applyUpdate) | reader, trong txn | rank 4 + BEGIN IMMEDIATE | **Đạt** (A6) — tombstone thắng ACK |

→ Hai đường dispatch-owned **thiếu guard** (H1, H2) là khoảng trống chính của bất biến đơn điệu; mọi đường khác đã đúng. Khuyến nghị chung: mọi chuyển trạng thái *không phải ACK* chỉ được phép từ tập `{PENDING, SENT, RETRY_SCHEDULED}` (dispatch-owned) hoặc là tombstone rank-4; FAILED nên xếp dưới RECEIVED để ACK muộn luôn thắng (L6).

### 6.2. Idempotency & exactly-once UI
- **Server bảo đảm:** publish/update/cancel idempotent theo `requestId` (A5, UNIQUE `002:78`); ACK idempotent theo `(device,alert,revision,kind)` + cờ `duplicate` (A1); state đơn điệu (A1/A2, trừ H1/H2); supersede revision cũ (A6, `FanoutEngine:197–205`); SYNC trả latest-revision + tombstone (`SyncService.java:32–72`, `ServerStore:502–546`).
- **Thực chất giao hàng là at-least-once:** retry, double-dispatch (M2), recovery (M7), eviction đều có thể tạo push trùng trên wire. **Exactly-once UI chỉ đạt nếu client dedup theo `(alertId, revision, deliveryId)` và tôn trọng tombstone/revision đơn điệu** (L5). Server không tự đảm bảo exactly-once hiển thị; cần kiểm chứng `AlertClientEngine` (ngoài phạm vi audit này) và tài liệu hóa hợp đồng.
- **Khoảng trống:** dedup gate gần như dư thừa (L1); ACK muộn sau FAILED không nâng state (L6); publish lỗi giả sau commit (M5).

### 6.3. Recovery đúng ngân sách retry + state
- **Đạt:** ngân sách bền vững qua `attempt_count` (A8), FAILED terminal không attempt mới (A7/S-10), recovery chỉ-đọc trước accept (A7), thứ tự created_at,id fixed-width (A10).
- **Rủi ro:** đốt attempt OFFLINE ngay sau restart + bỏ qua next_retry_at (M7); không reconciliation runtime + nuốt lỗi SQL (M1); attempt+state hai transaction (L8); counter/ledger không rebuild (M10). → Ngân sách *số lượng* đúng, nhưng *ngữ nghĩa thời gian* và *độ trung thực quan trắc* sau recovery cần củng cố.

### 6.4. Transaction atomicity (update/cancel/incident)
- **Đạt:** mỗi thao tác composite một BEGIN IMMEDIATE + ROLLBACK + enqueue/fanout sau commit (A6).
- **Rủi ro:** kiểm điều kiện (ACTIVE/state-machine/newRevision) nằm **ngoài** transaction → suy biến dưới đồng thời (M3, M4); side-effect incident sau commit lỗi gây INTERNAL giả (M5); expiry select-rồi-update (L7). Nguyên tắc: đưa *điều kiện tiên quyết* vào trong transaction (guard UPDATE / đọc trên cùng connection) để atomicity đi kèm correctness.

### 6.5. Backpressure / slow-client
- **Mặt phẳng TLS đạt:** hàng đợi bounded ưu tiên + eviction + REJECTED_SLOW disconnect (A3/A13/A14), một writer thread không xen kẽ (A14), pre-auth TTL+cap (A12).
- **Rủi ro:** mặt phẳng WS **không** backpressure — subscriber chậm đình trệ broadcast + chiếm thread timer (H4), handshake không timeout/cap (M6); queue bounded theo frame không theo byte (M9); pool timer 2 thread nghẽn (M8); Db.open mutex + busy 5s (M12); acceptLoop chết khi IOException (M13). → Cần mở rộng mô hình backpressure TLS sang WS và tách tài nguyên timer/DB.

---

## 7. Khuyến nghị ưu tiên (roadmap đề xuất)

1. **Ngay (HIGH):** thêm guard cho `scheduleRetry` (H1) và `markFailed`→`updateDispatchState` (H2); chắn/handle tái xác thực + dọn chỉ mục `byDeviceId` (H3); backpressure WS (hàng đợi writer per-connection + timeout ghi + executor riêng) (H4). Viết test đối kháng cho từng race (ACK chen giữa các bước; reconnect+timer trùng; WS client không đọc).
2. **Ngắn hạn (MEDIUM cốt lõi):** reconciliation runtime + bắt RuntimeException trong checkDelivery (M1); in-flight guard chống double-dispatch (M2); đưa điều kiện ACTIVE/newRevision/state-machine vào transaction (M3, M4); tiền kiểm incidentId + best-effort side-effect (M5); timer pre-auth độc lập biên frame + timeout/cap handshake WS (M6).
3. **Trung hạn (MEDIUM vận hành):** recovery tôn trọng next_retry_at + grace-period (M7); tách pool timer + bulk rebuild status (M8); ngân sách byte queue (M9); rebuild counter/ledger từ DB (M10); lọc scope INCIDENT_EVENT (M11); pool kết nối DB (M12); acceptLoop bền lỗi (M13).
4. **Dài hạn (LOW):** thống nhất hợp đồng RetryPolicy + biên maxRetries=0 (L15); prune chỉ mục in-memory (L2); làm rõ dedup gate (L1); ACK muộn thắng FAILED (L6); hoàn thiện RFC6455 (L9); tư thế bảo mật production (L18).

---

## 8. Phụ lục — ràng buộc & phạm vi đã đọc

- **Ràng buộc tuân thủ:** CHỈ tạo file này; không sửa code/file khác; không `git commit`; không chạy `mvn`/kiểm thử — toàn bộ kết luận là **suy luận tĩnh** từ source có đánh số dòng + schema. Chưa tái lập race bằng thực nghiệm; severity là đánh giá *hệ quả × khả năng với tới*, mỗi finding kèm cơ chế để viết test xác nhận.
- **Đã đọc (main):** toàn bộ class liệt kê ở mục Phạm vi; schema `001_base_schema.sql`, `002_showcase_expansion.sql`; `Priority.java`, `Protocol.java`, `FrameCodecImpl.java` (đối chiếu). Đã tham chiếu (không audit sâu) test hiện có: `FanoutEngineTest` (8 ca, gồm `recoveredFailedDeliveryGetsNoNewAttempts`, `ackReceivedStopsRetryCycle`, `doubleEnqueueBeforeDispatchDeliversOnce`), `AckInsertedContractTest`, `PeerReviewFixesTest`, `ServerSecurityFixesTest`, `TlsLoopbackIntegrationTest` — làm bằng chứng củng cố cho các mục "đã-đạt", **không** phải kết quả chạy mới.
- **Ngoài phạm vi:** `aegis-client`, `aegis-simulator`, `aegis-dashboard`, `aegis-network-lab` (chỉ nhắc khi hợp đồng client liên quan exactly-once UI — L5). Các file working-tree thay đổi bởi agent khác (weather module, docs/BAO-CAO.md, scripts) không thuộc phạm vi và bị bỏ qua.
- **Ghi chú provenance:** HEAD dịch chuyển trong lúc audit (agent khác commit song song); số dòng dẫn chiếu theo bản working tree đã đọc tại thời điểm audit. Nếu source thay đổi, cần kiểm chứng lại số dòng trước khi áp dụng khuyến nghị.