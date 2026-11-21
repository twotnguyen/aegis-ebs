# PERFORMANCE — Phân tích hiệu năng & đề xuất tối ưu AEGIS-EBS

> Phạm vi: `aegis-server`, `aegis-persistence`, `aegis-simulator`, `aegis-dashboard/src/three` (và `src/main.js`).
> Phương pháp: **phân tích tĩnh mã nguồn** (đọc code, suy luận cấu trúc). Tài liệu này **không chứa số liệu đã đo**;
> mọi con số nêu ra là (a) giá trị cấu hình mặc định trích từ mã, (b) ước tính tĩnh theo cấu trúc mã (được ghi nhãn rõ),
> hoặc (c) ô kết quả trống trong kế hoạch benchmark. Mục §6 định nghĩa cách đo để lấp đầy các ô đó bằng số thật.
> Trích dẫn dạng `file:dòng`. Không đề xuất nào được phép phá vỡ các bất biến an toàn đã nghiệm thu (S-01, S-02, S-05, S-07, S-09, S-10 — xem `docs/SECURITY.md`, `docs/reviews/`).

---

## 1. Hiện trạng kiến trúc thực thi (những gì ĐÃ làm đúng)

Phần này ghi nhận các cơ chế hiệu năng đã hiện hữu để các đề xuất không "phát minh lại" chúng.

### 1.1 Backend

| Cơ chế | Hiện trạng (file:dòng) |
|---|---|
| Virtual threads cho mọi kết nối | `AegisServer.java:85` (`Executors.newVirtualThreadPerTaskExecutor()`); reader mỗi kết nối TLS `TlsGateway.java:108`; writer mỗi phiên `ClientSession.java:67`; accept loop `TlsGateway.java:78`; WS handler `StatusFeedServer.java:66`; dispatcher `AegisServer.java:90`. Simulator: 1 virtual thread/agent `SimulatorMain.java:67,74`. |
| Nền tảng Java 24 | `pom.xml:19` (`maven.compiler.release=24`) → JEP 491: `synchronized` không còn pin virtual thread; các khối `synchronized` của `BoundedWriterQueue`/`Db` an toàn về mặt pinning trên JDK 24+. |
| SQLite WAL + busy_timeout + FK đúng thứ tự | `Db.java:54-58`: `foreign_keys=ON` → `busy_timeout=5000` (`Db.java:28`) → `journal_mode=WAL` (có retry BUSY, `Db.java:79-104`); khởi tạo serialized per-file (`Db.java:50-51`). |
| Giao dịch ngắn, ghi nguyên tử | `BEGIN IMMEDIATE … COMMIT` trong `TransactionalPublisher.java:85-108`, `DeliveryRepo.recordAck` (`DeliveryRepo.java:161-222`), `ServerStore.applyUpdate/applyCancel/createIncidentAtomic/transitionIncidentAtomic` (`ServerStore.java:320-461`). Enqueue fanout CHỈ sau commit (`TransactionalPublisher.java:111`). |
| Backpressure hàng đợi bounded | `BoundedWriterQueue` dung lượng 256 (`ServerConfig.java:66`), TreeSet ưu tiên+FIFO (`BoundedWriterQueue.java:31-33`), evict frame ưu tiên thấp khi đầy (`BoundedWriterQueue.java:50-57`), `REJECTED_SLOW` → ngắt client chậm (`ClientSession.java:71-78`, `TlsGateway.java:174-181`). |
| Status delta đã batch/coalesce | `alertsDirty` chỉ rebuild tối đa 1 lần/chu kỳ (`StatusFeedService.java:102-107,250-259`); chu kỳ `statusBatchMs=500` (`ServerConfig.java:67`, `AegisServer.java:122-123`); delta chỉ chứa zone/metrics/alerts thay đổi (`StatusFeedService.java:199-230`); thống kê alert dùng 1 truy vấn GROUP BY (`ServerStore.java:589-606`). |
| Bộ đếm monotonic + percentile cửa sổ bounded | `MetricsRegistry.java:17` (4096 mẫu), nearest-rank p50/p95/p99 (`MetricsRegistry.java:38-53`); counters chỉ tăng (`MetricsRegistry.java:55-61`); flush vào `metrics_samples` mỗi 30s (`AegisServer.java:125,177-186`). |
| Chống đói ưu tiên | `PriorityScheduler.MAX_BURST=8` + luân phiên tier dưới (`PriorityScheduler.java:27,53-75`). |
| Giới hạn tài nguyên đầu vào | `maxConnections=512` (`ServerConfig.java:74`, thực thi `TlsGateway.java:93-98`); pre-auth TTL 10s (`ServerConfig.java:73`, `TlsGateway.java:103,135-141`); WS cap 64 subscriber (`StatusFeedServer.java:36,100-108`); frame ≤ 64KiB (`FrameCodecImpl.java:84`), WS ≤ 1MiB (`WsConnection.java:16`). |
| Ghi frame TLS một lần | `FrameCodecImpl.writeFrame` ghi 1 mảng byte + flush (`FrameCodecImpl.java:76-79`) → 1 TLS record/frame; `TCP_NODELAY` (`TlsGateway.java:100`). |

### 1.2 Dashboard 3D

| Cơ chế | Hiện trạng (file:dòng) |
|---|---|
| InstancedMesh cho phần tử lặp | `structure.js:58-74` (cột/dầm mỗi tầng), `facade.js:60-68,118-120` (kính/spandrel/mullion mỗi tầng), `interior.js:108-118,228-244` (16 batch nội thất toàn cục), `environment.js:71-92` (24 InstancedMesh), `safety.js:35-53` (bậc thang, bình chữa cháy, sprinkler…), `lighting.js:145-199` (panel đèn, cột đèn…), `statusViz.js:323-343` (marker thiết bị mỗi zone). |
| Tái sử dụng geometry | Unit box dùng chung: `structure.js:38`, `facade.js:42`, `environment.js:54`, `safety.js:70`; geometry hợp nhất bằng `mergeGeometries` cho bàn ghế: `interior.js:8,30,62-103`. |
| Vật liệu dùng chung + clone một lần | Thư viện `materials.js:4-24`; statusViz clone vật liệu zone đúng 1 lần khi bind rồi chỉ mutate (`statusViz.js:159-161,214-218`). |
| Không cấp phát mỗi frame | Scratch objects `statusViz.js:118-127`; particle pool 512 phần tử, 1 draw call `Points` (`statusViz.js:44,82-105`); màu cache `lighting.js:206-216`. |
| Trần light thật | `MAX_POINT_LIGHTS=12` + kiểm tra cứng (`lighting.js:13,125-128`); phần còn lại là emissive-only (`lighting.js:130-199`). |
| Ngân sách tam giác | Mục tiêu < 300k tam giác toàn cảnh (`CONTRACT.md:25`); đếm tam giác lúc build: `environment.js:414-430`, `interior.js:247-253`. |
| Pixel ratio giới hạn | `main.js:75` (`min(devicePixelRatio, 2)`). |

**Lưu ý tích hợp quan trọng:** `main.js` hiện dựng twin đơn giản (40 box zone + 11 slab + 10 sprite nhãn, `main.js:117-207`) và **chưa import các module `src/three`** — việc ráp nối thuộc "assembler" theo `CONTRACT.md:28-29`. Vì vậy phân tích 3D bên dưới cover cả (a) twin đơn giản đang chạy và (b) cảnh module khi được tích hợp; benchmark phải đo cả hai (§6.4).

---

## 2. Đề xuất tối ưu backend (B1–B12)

Mỗi đề xuất: **Hiện trạng → Vấn đề → Giải pháp → Lợi ích kỳ vọng → Cách đo**. Không có số liệu đo sẵn; "lợi ích kỳ vọng" là suy luận định tính/định lượng từ cấu trúc mã, sẽ được kiểm chứng bằng §6.

### B1. Loại bỏ O(N²) đọc DB khi enqueue batch fanout (ưu tiên CAO nhất)

- **Hiện trạng:** `FanoutEngine.enqueue` lặp từng delivery gọi `track` (`FanoutEngine.java:108-110`); mỗi `track` gọi `openLedger` (`FanoutEngine.java:134`), và `openLedger` luôn chạy `deliveries.findByAlert(alertId)` — tải TOÀN BỘ delivery của alert (mọi revision) — trước khi `ledger.open` ném `IllegalStateException` vì ledger đã mở (`FanoutEngine.java:142-152`). `findByAlert` mở connection riêng mỗi lần (`DeliveryRepo.java:68-70,259-270`).
- **Vấn đề:** Publish tới N thiết bị (ví dụ toàn tòa nhà: 480 thiết bị với `devicesPerZone=12`, `ServerConfig.java:69`) tạo N lời gọi `findByAlert`, mỗi lời gọi đọc N hàng → **O(N²) hàng đọc + N connection open** ngay trên luồng reader của operator (đường lệnh đồng bộ: `MessageRouter.alertPublish` → `AlertService.publish` → `TransactionalPublisher.publish` → `enqueue`, `TransactionalPublisher.java:111`). Với N=480: ~480 truy vấn × 480 hàng ≈ 230k hàng map/lần publish (ước tính tĩnh). Đây là dạng stall O(N²) từng được ghi nhận trong lịch sử review (đã sửa phía stats bằng `alertsDirty`; phía ledger vẫn còn).
- **Giải pháp:** Mở ledger **một lần cho cả batch** trong `enqueue`/`recoverEnqueue`: gom `expected` từ chính batch (các delivery cùng alert/revision đã có sẵn `deviceId`), hoặc gọi `findByAlert` đúng 1 lần rồi truyền xuống; `track` không gọi `openLedger` nữa. Giữ nguyên semantics `AckLedger.open` (per-alert, idempotent).
- **Lợi ích kỳ vọng:** Publish toàn tòa nhà giảm từ ~N truy vấn xuống ~1; độ trễ lệnh ALERT_PUBLISH và thời gian chiếm luồng reader giảm bậc thang; loại nguy cơ heartbeat-suspect dây chuyền khi operator flood publish.
- **Cách đo:** Đếm số lần `Db.open`/truy vấn trong 1 lệnh publish (bọc đếm tạm trong harness test, không sửa production); đo wall-time `publish()` với N = 12/48/480 (§6.2); so sánh trước/sau bằng JFR method profiling. Kiểm chứng bất biến: test `FanoutEngineTest` + `TlsLoopbackIntegrationTest` vẫn xanh.

### B2. Index `tracked` theo deviceId cho `resumeDevice`/`cancelAlert`

- **Hiện trạng:** `resumeDevice` duyệt TOÀN BỘ `tracked.values()` và `findById` từng delivery (`FanoutEngine.java:352-365`); được gọi mỗi lần device auth (`MessageRouter.java:182`) và mỗi SYNC (`SyncService.java:70`). `cancelAlert` cũng duyệt toàn bộ (`FanoutEngine.java:396-402`).
- **Vấn đề:** Reconnect storm (kịch bản `flaky`/`mixed`, `FaultProfile.java:25-26`): M thiết bị reconnect × T delivery đang tracked → O(M×T) lần `findById`, mỗi lần mở connection. Với 500 agent và 1 alert toàn tòa nhà chưa ack: 500 × 480 ≈ 240k truy vấn (ước tính tĩnh).
- **Giải pháp:** Thêm `Map<String, Set<String>> byDevice` (deviceId → deliveryIds) cập nhật trong `track`/`untrack`; `resumeDevice` chỉ duyệt tập của device đó. `cancelAlert` tương tự có thể index theo alertId. Giữ nguyên dedup/retry semantics (recovery bypass dedup, `FanoutEngine.java:112-115`).
- **Lợi ích kỳ vọng:** Chi phí reconnect còn O(delivery của chính device); SYNC/auth không phụ thuộc tổng tải hệ thống; giảm contention writer SQLite trong bão reconnect.
- **Cách đo:** Kịch bản flaky 500 agent (§6.2): đo p95 độ trễ AUTH_RESULT→SYNC_RESULT, tổng truy vấn `findById`/phút (đếm qua wrapper), tỷ lệ `HEARTBEAT_SUSPECT` trong audit.

### B3. Pool/reuse connection SQLite thay vì open-mỗi-thao-tác

- **Hiện trạng:** Mọi repo method mở connection mới: `DeliveryRepo.findById` (`DeliveryRepo.java:58-66`), `recordAttempt` (`:115-130`), `updateDispatchState` (`:235-247`), `AuditRepo.append` (`AuditRepo.java:26-34`), toàn bộ `ServerStore`. Mỗi `Db.open` đi qua khóa toàn cục per-file (`Db.java:50-51`) rồi `DriverManager.getConnection` + 3 PRAGMA + truy vấn `journal_mode=WAL` (`Db.java:52-58`).
- **Vấn đề:** (a) Chi phí cố định mỗi thao tác DB (open + pragma) nhân với tần suất rất cao của đường ACK/dispatch; (b) khóa `OPEN_LOCKS` **serialize mọi lần open trong JVM** — điểm contention khi hàng trăm virtual thread cùng ghi/đọc; (c) mỗi connection mới là một file handle + bộ nhớ đệm riêng. Lưu ý: trên Java 24 (`pom.xml:19`) `synchronized` không pin virtual thread, nên vấn đề ở đây là **thông lượng nối tiếp**, không phải pinning.
- **Giải pháp:** Pool connection nhỏ (ví dụ hàng đợi blocking các connection đã khởi tạo pragma sẵn, hoặc HikariCP `maximumPoolSize` ≈ số nhân CPU cho writer + vài reader). WAL cho phép nhiều reader song song với 1 writer (`Db.java:20-21`), nên pool reader giúp tận dụng đọc đồng thời. Giữ `OPEN_LOCKS` cho khởi tạo file mới; giữ nguyên thứ tự pragma. Connection sqlite-jdbc không thread-safe → pool phải cho mượn độc quyền.
- **Lợi ích kỳ vọng:** Loại chi phí open/pragma khỏi đường nóng; giảm serialization tại khóa per-file; throughput ACK/dispatch tăng khi số client lớn (kiểm chứng bằng §6, không hứa trước con số).
- **Cách đo:** Microbenchmark `Db.open` vs lấy-từ-pool (JMH hoặc harness đo wall-time); đếm open/giây dưới tải 500 agent; JFR `jdk.JavaMonitorEnter` tại `Db.open`; so sánh p99 publish→ACK trước/sau.

### B4. Giảm round-trip SQL trong dispatcher đơn luồng

- **Hiện trạng:** Một dispatcher duy nhất (`FanoutEngine.java:156-174`). Mỗi `dispatch` đọc lại delivery (`:181`) và alert (`:190`) dù `Tracked` đã giữ alert+revision (`:60,130`); `sendPush` ghi `recordAttempt` rồi `updateDispatchState` — **2 connection/2 giao dịch riêng** (`:247-248`) trước khi offer frame (`:252`). `checkDelivery` lại `findById` (`:312`).
- **Vấn đề:** ~4+ lần mở connection mỗi lượt push, nối tiếp trên 1 luồng. Thông lượng fanout bị chặn bởi độ trễ SQL × số delivery; với alert toàn tòa nhà 480 đích, thời gian phát hết frame tăng tuyến tính theo chi phí SQL mỗi delivery.
- **Giải pháp:** (a) Gộp `recordAttempt + updateDispatchState` vào **một giao dịch trên một connection** (thêm method composite trong `DeliveryRepo`, giữ đúng thứ tự S-02: commit attempt+SENT TRƯỚC khi offer frame — `FanoutEngine.java:242-251`); (b) dùng alert/revision đã cache trong `Tracked` cho kiểm tra supersede, chỉ đọc lại khi có tín hiệu thay đổi (ALERT_UPDATE/CANCEL đã đi qua `AlertService`/`engine.cancelAlert`); (c) tùy chọn nâng cao: song song hóa khâu chuẩn bị I/O bằng vài worker nhưng giữ **một chủ sở hữu mỗi delivery** (tracked map) để không phá guard thế hệ (`:295-303`).
- **Lợi ích kỳ vọng:** Giảm ~1/2 số giao dịch ghi mỗi push; rút ngắn thời gian chiếm dispatcher; p99 publish→ACK giảm khi fanout rộng. Bất biến S-02/S-10 giữ nguyên.
- **Cách đo:** Đếm giao dịch/push (wrapper), đo độ sâu `PriorityScheduler.size()` (`PriorityScheduler.java:81-83`) và thời gian drain sau publish toàn tòa nhà; test `FanoutEngineTest` (kể cả `recoveredFailedDeliveryGetsNoNewAttempts`) phải xanh.

### B5. Giới hạn đồng thời Argon2id trong bão reconnect

- **Hiện trạng:** Mỗi AUTH_DEVICE chạy `hasher.verify` Argon2id trên luồng reader của kết nối (`AuthService.java:35-40`); tham số mặc định production `iterations=2, memory=16384 KiB (16 MiB), parallelism=1` (`ServerConfig.java:70-72`).
- **Vấn đề:** 500 agent reconnect đồng thời (flaky/mixed) → tới 500 phép verify song song, mỗi phép cấp phát ~16 MiB bộ nhớ tạm + CPU → đỉnh ~8 GiB áp lực bộ nhớ tức thời (ước tính tĩnh từ tham số), GC pause và trễ auth tăng vọt; các kết nối khác (kể cả operator) bị ảnh ứng dây chuyền.
- **Giải pháp:** Semaphore giới hạn số verify đồng thời (≈ số nhân CPU), xếp hàng phần còn lại trên virtual thread (rẻ); tùy chọn bổ sung: cache kết quả verify theo (deviceCode, credentialHash) TTL ngắn cho demo lab — **đánh đổi bảo mật phải ghi rõ** (credential bị lộ trong TTL vẫn dùng được) và chỉ bật ở chế độ SIMULATION. Không giảm tham số Argon2 mặc định.
- **Lợi ích kỳ vọng:** Đỉnh bộ nhớ/CPU khi reconnect storm bị chặn ở mức cấu hình được; p95 auth ổn định; không ảnh hưởng đường truyền alert.
- **Cách đo:** Kịch bản flaky 500 agent: đo p50/p95/p99 thời gian AUTH_DEVICE→AUTH_RESULT (timestamp audit `AUTH_DEVICE_SUCCESS`), RSS/GC pause (JFR `jdk.GCPhasePause`), đếm verify đồng thời.

### B6. Diff status delta bằng số học thay vì dựng snapshot đầy đủ mỗi chu kỳ

- **Hiện trạng:** `broadcastDelta` (chu kỳ 500ms) dựng `snapshot()` đầy đủ: `floorsNode` duyệt 40 zone (`StatusFeedService.java:128-140`), `onlineByZone` duyệt mọi device session (`:159-166`, `SessionRegistry.java:51-57`), `metricsNode` tính 3 percentile (`:168-176`), `alertCache.deepCopy` (`:123`); sau đó so sánh từng zone bằng `JsonNode.equals` (`:202-218`). Hook `connectivityChanged` hiện là no-op (`:109-112`).
- **Vấn đề:** Mỗi chu kỳ cấp phát cây JSON đầy đủ + deepCopy + so sánh sâu ngay cả khi không gì thay đổi; chi phí O(zones + sessions + alerts) và rác GC đều đặn 2 lần/giây, tăng theo số kết nối. Coalescing `alertsDirty` đã tốt (`:102-107`) nhưng phần diff vẫn đắt.
- **Giải pháp:** Giữ trạng thái broadcast cuối dưới dạng **số** (mỗi zone: received/acknowledged/failed/online/severity đã gửi) và so sánh số học; chỉ dựng node JSON cho zone thực sự đổi. Duy trì `onlineByZone` tăng giảm trong `connectivityChanged`/bind/close (hook đã có sẵn) thay vì quét lại mỗi chu kỳ. Giữ nguyên contract status/1 (hình dạng delta không đổi — `aegis-dashboard/docs/status-contract.md`).
- **Lợi ích kỳ vọng:** CPU + allocation mỗi chu kỳ giảm mạnh khi hệ thống tĩnh; chu kỳ 500ms giữ được ngay cả khi 500 kết nối; độ trễ delta không đổi.
- **Cách đo:** JFR allocation rate + wall-time `broadcastDelta` trước/sau ở 100/200/500 client; kích thước byte delta (đếm trong `Json.write`, `:233`); đối chiếu ảnh chụp contract bằng test dashboard (`npm test`).

### B7. Cách ly subscriber WS chậm khỏi luồng timer chung (head-of-line blocking)

- **Hiện trạng:** `broadcastDelta` ghi tuần tự từng subscriber bằng `WsConnection.sendText` — ghi socket **blocking** trong `synchronized(writeLock)` (`StatusFeedService.java:233-239`, `WsConnection.java:31-55`). Chu kỳ này chạy trên `timers` gồm **2 luồng platform** dùng chung cho heartbeat sweep/broadcast, expiry, metrics flush (`AegisServer.java:84,120-125`). Socket WS không có timeout ghi (java.net.Socket không hỗ trợ write timeout).
- **Vấn đề:** Một dashboard ngừng đọc (TCP send buffer đầy) sẽ chặn `sendText` vô hạn → chặn toàn bộ vòng broadcast → **chậm heartbeat sweep/broadcast của mọi kết nối TLS** → nguy cơ suspect hàng loạt (3×10s, `ServerConfig.java:62-63`). Đây là rủi ro ổn định hệ thống, không chỉ hiệu năng.
- **Giải pháp:** Áp dụng đúng pattern đã có phía TLS: mỗi subscriber một hàng đợi bounded + writer virtual thread riêng (tái sử dụng `BoundedWriterQueue`); đầy → ngắt subscriber đó (chính sách slow-client tương tự `TlsGateway.java:174-181`), không ảnh hưởng người khác. Giới hạn dung lượng hàng đợi theo byte để chặn delta lớn.
- **Lợi ích kỳ vọng:** Loại bỏ điểm chết chéo giữa mặt phẳng đọc WS và mặt phẳng lệnh TLS; heartbeat đúng nhịp bất kể hành vi dashboard; tài nguyên subscriber bị chặn (cap 64, `StatusFeedServer.java:36`).
- **Cách đo:** Test tích hợp: 1 subscriber không đọc + 1 subscriber bình thường; đo nhịp heartbeat (audit/log), độ trễ delta của subscriber tốt, và thời gian phát hiện/ngắt subscriber chậm. Đối chiếu test slow-client hiện có của TLS.

### B8. Tinh chỉnh WAL: `synchronous=NORMAL` (chế độ lab) + giám sát checkpoint

- **Hiện trạng:** WAL + `busy_timeout=5000` đã đặt đúng thứ tự (`Db.java:54-58`); giao dịch đã ngắn (§1.1). Không nơi nào đặt `synchronous` → mặc định **FULL**: mỗi commit fsync WAL. Không đặt `wal_autocheckpoint`/`journal_size_limit` → mặc định SQLite (checkpoint mỗi ~1000 trang).
- **Vấn đề:** Với tần suất commit rất cao (mỗi ACK một giao dịch `recordAck`, mỗi attempt một giao dịch), fsync mỗi commit là chi phí I/O trội trên đường nóng; WAL phình khi ghi bền bỉ và checkpoint có thể gây stall ngắn.
- **Giải pháp:** Thêm cấu hình `PRAGMA synchronous=NORMAL` **chỉ cho chế độ demo/lab** (đánh đổi rõ ràng: vẫn bền vững trước crash tiến trình, có thể mất commit cuối khi mất điện — chấp nhận được cho SIMULATION, không dùng cho dữ liệu thật). Giám sát kích thước `-wal`, chủ động `wal_checkpoint(PASSIVE)` trong timer rảnh; đặt `journal_size_limit`. Giữ FULL làm mặc định an toàn.
- **Lợi ích kỳ vọng:** Độ trễ commit giảm (bớt fsync), throughput ACK/dispatch tăng; không đổi semantics giao dịch.
- **Cách đo:** Đo p50/p95/p99 độ trễ `recordAck` (wall-time quanh lời gọi) với FULL vs NORMAL ở 500 agent; theo dõi kích thước WAL + số lần BUSY trong log; kiểm chứng recovery sau restart vẫn đúng (`RecoveryScanner`, test crash-recovery hiện có).

### B9. triệt tiêu N+1 trong `ServerStore` + gom audit theo lô

- **Hiện trạng (các chuỗi truy vấn lặp):**
  - `activeAlerts`: SELECT danh sách id rồi `alerts.findById` từng id (`ServerStore.java:465-481`).
  - `activeAlertsForDevice` (đường SYNC): mỗi alert → `findById` + `findRevision` + `deliveryFor`; bản thân `deliveryFor` lại SELECT id rồi `findById` (`ServerStore.java:502-528,548-561`) — 2 truy vấn cho 1 hàng.
  - `expireOverdue` (chạy mỗi 1s, `AegisServer.java:124`): SELECT id rồi `updateState` từng id, mỗi lần một connection (`ServerStore.java:484-499`).
  - `rebuildAlertCache`: `findRevision` từng alert + `targets` cho alert toàn tòa nhà (`StatusFeedService.java:279-280,299-300`).
  - `AuditRepo.append` mở connection mỗi sự kiện (`AuditRepo.java:26-34`); đường ACK ghi audit mỗi ACK (`MessageRouter.java:250-252`) → flood ACK = flood giao dịch ghi nhỏ cạnh tranh writer duy nhất của SQLite.
- **Vấn đề:** SYNC/reconnect storm và chu kỳ status tạo hàng loạt truy vấn/connection dư thừa; audit trở thành tải ghi đáng kể dưới flood.
- **Giải pháp:** (a) Truy vấn JOIN trả đủ hàng (alert+revision+delivery) trong 1 lần cho `activeAlertsForDevice`/`activeAlerts`; (b) `expireOverdue` dùng 1 `UPDATE … RETURNING id` (sqlite-jdbc 3.46.1.0, `pom.xml:23`, hỗ trợ RETURNING của SQLite ≥3.35) trong 1 giao dịch; (c) audit: buffer append-only + flush 1 giao dịch mỗi chu kỳ status (500ms) — **giữ ghi đồng bộ cho sự kiện an ninh quan trọng** (DENIED/FORBIDDEN/SLOW_CLIENT_DISCONNECT) để không giảm tính tức thời của dấu vết bảo mật; ghi rõ đánh đổi độ trễ hiển thị audit ≤500ms cho sự kiện thường.
- **Lợi ích kỳ vọng:** SYNC còn O(1) truy vấn/alert thay vì O(3–4); expiry sweep 1 giao dịch/giây; giảm số giao dịch ghi tranh chấp writer trong flood ACK.
- **Cách đo:** `EXPLAIN QUERY PLAN` các truy vấn mới; đếm connection/lệnh SYNC; p95 SYNC_RESULT dưới bão reconnect 500 agent; đối chiếu test `SyncService`/reconnect hiện có.

### B10. Quan sát được backpressure: nối `EVICTED_LOWER` vào metrics + ngân sách byte

- **Hiện trạng:** `offer` trả `EVICTED_LOWER` khi đuổi frame ưu tiên thấp (`BoundedWriterQueue.java:50-57`) và đếm `evicted` (`:35,76`), nhưng `ClientSession.send` chỉ xử lý `REJECTED_SLOW` (`ClientSession.java:71-78`), `FanoutEngine.sendPush` cũng chỉ nhánh `REJECTED_SLOW` (`FanoutEngine.java:253-266`). `MetricsRegistry.incSlowClientDrops()` tồn tại (`MetricsRegistry.java:60`) nhưng **không có caller nào trong production** (đã kiểm tra toàn bộ `aegis-server/src/main`). Dung lượng hàng đợi tính theo **số frame** (256), không theo byte.
- **Vấn đề:** Frame bị đuổi (mất dữ liệu tới client chậm nhưng chưa ngắt) là vô hình với vận hành/benchmark; khó phân biệt "hệ thống khỏe" với "hệ thống đang silently drop". Frame kích thước khác nhau (ALERT_PUSH ~vài trăm byte vs frame 8KB trong test slow-client) khiến ngân sách bộ nhớ mỗi phiên dao động lớn.
- **Giải pháp:** Khi `EVICTED_LOWER`: gọi `metrics.incSlowClientDrops()` + audit có throttle (ví dụ 1 sự kiện/phiên/chu kỳ) + đưa `queueDepth/evicted` vào mặt phẳng quan sát (trường bổ sung additive trong metrics của status contract, không phá contract hiện có). Tùy chọn: thêm ngưỡng byte song song ngưỡng frame.
- **Lợi ích kỳ vọng:** Backpressure đo được (điều kiện tiên quyết của benchmark §6); phát hiện sớm client chậm trước khi bị ngắt; không đổi chính sách ngắt hiện hành.
- **Cách đo:** Flood client chậm (tái hiện điều kiện test 9 của `TlsLoopbackIntegrationTest`): đối chiếu `evictedCount` với metrics mới; xác nhận không có disconnect nhầm trong kịch bản clean.

### B11. Percentile O(1) bằng histogram + làm rõ ngữ nghĩa độ trễ

- **Hiện trạng:** `percentile` copy + sort tới 4096 mẫu mỗi lần gọi (`MetricsRegistry.java:38-49`); được gọi 3 lần/chu kỳ delta 500ms (`StatusFeedService.java:171-173`), mỗi snapshot khi subscribe (`:247`), và 3 lần/flush 30s (`AegisServer.java:179-181`). Mẫu độ trễ = `ackedAt − alert.createdAt` (`FanoutEngine.java:377-382`).
- **Vấn đề:** (a) O(n log n) mỗi lần gọi dưới khóa copy — chi phí nhỏ nhưng lặp 6+ lần/giây và tăng khi cửa sổ đầy; (b) ngữ nghĩa mẫu là **end-to-end publish→ACK_RECEIVED đầu tiên**, bao gồm cả cửa sổ retry (3s/6s/12s) và delay do fault injection phía simulator — không phải độ trễ vận chuyển thuần; đọc p99 mà không hiểu ngữ nghĩa sẽ kết luận sai.
- **Giải pháp:** Thay reservoir+sort bằng histogram bucket cố định (ví dụ bucket 1ms tới 60s, hoặc HdrHistogram) cho truy vấn percentile O(1), giữ nearest-rank và cửa sổ tương đương; giữ counters monotonic. Tài liệu hóa 2 chỉ số: `delivery_latency_*` (end-to-end, hiện có) và `transport_latency` (ACK − attempt SENT cuối, tính từ DB, §6.3).
- **Lợi ích kỳ vọng:** Chu kỳ status nhẹ hơn; percentile ổn định khi tải cao; báo cáo không nhập nhằng giữa trễ hệ thống và trễ mạng.
- **Cách đo:** Unit test so sánh percentile histogram vs nearest-rank trên cùng tập mẫu (sai số bucket chấp nhận được, ghi rõ); JFR CPU trong `broadcastDelta`; đối chiếu `metrics_samples` trước/sau.

### B12. Simulator: delay-ACK không được chặn heartbeat + thêm đo độ trễ phía client

- **Hiện trạng:** `SimAgent.handleAlert` `Thread.sleep` ngay trên luồng đọc khi delay ACK (`SimAgent.java:202-208`); heartbeat chỉ gửi khi `readFrame` ném `SocketTimeoutException` (soTimeout 2000ms, `SimAgent.java:86,155-157,243-252`). Preset `mixed` delay 300ms + jitter 1200ms (`FaultProfile.java:26`). `SimStats` không có trường độ trễ push→ACK.
- **Vấn đề:** (a) Khi sleep dài (đặc biệt override `--delay-ms` lớn), agent không gửi HEARTBEAT → server suspect sau 3×10s (`ServerConfig.java:62-63`) → disconnect "giả" làm bẩn kết quả benchmark; với preset mặc định (≤1.5s) an toàn nhưng tài liệu phải nêu giới hạn. (b) Không có số đo độ trễ phía client → chỉ dựa vào đồng hồ server.
- **Giải pháp:** (a) Tách gửi ACK delay sang virtual thread khác hoặc chặn trần delay < `heartbeatIntervalMs × maxMissed`; (b) thêm ghi nhận `pushReceivedAt → ackSentAt` vào `SimStats` (percentile phía client) để đối chiếu chéo với server.
- **Lợi ích kỳ vọng:** Benchmark fault-injection không tạo artifact disconnect; có 2 nguồn đo độc lập (client/server) cho p50/p95/p99.
- **Cách đo:** Chạy `delayed` với delay vượt ngưỡng: xác nhận 0 audit `HEARTBEAT_SUSPECT` do injection sau khi sửa; so sánh percentile client vs server (§6.3) — lệch hệ thống phải giải thích được (đồng hồ, hàng đợi writer).

---

## 3. Đề xuất tối ưu 3D (F1–F7)

### 3.1 Ước tính tĩnh draw call của cảnh module (CHƯA ĐO — suy từ cấu trúc mã)

Đếm số mesh/InstancedMesh mỗi module tạo ra (mỗi mesh = 1 draw call trong pass chính, chưa kể pass bóng/pass transmission):

| Module | Cách đếm (file:dòng) | Draw call ước tính |
|---|---|---|
| structure | 11 slab (`structure.js:81-85`) + 4 batch×10 tầng (`:116-134`) + 8 podium ring + 2 batch podium (`:142-169`) + 4 tường lõi + 2 batch lõi + cap + 2 plinth (`:181-230`) | ~70 |
| facade | 3 batch×10 tầng (`facade.js:118-120`) + ~46 mesh sảnh (`:123-187`) + 3 biển (`:235-238`) + 8 parapet/coping (`:245-254`) + 3 beacon (`:263-290`) | ~90 |
| interior | 10 hành lang + 40 plate zone (`interior.js:137-160`) + ≤16 batch (`:108-118,228-244`) | ≤66 |
| safety | 5 batch thang (`safety.js:108-112`) + 22 biển×3 mesh (`:144-173`) + 4 batch chữa cháy/sprinkler (`:202-228`) + 1 batch đế strobe + **40 dome strobe riêng** (`:236-254`) + **44 ống tuyến thoát hiểm** (`:281-297,348-349`) + ~9 mesh điểm tập kết (`:303-346`) | ~169 |
| environment | 24 InstancedMesh + ~41 mesh thường (đếm từ `environment.js:107-411`) | ~65 |
| lighting | 5 InstancedMesh emissive (`lighting.js:145-199`) | 5 |
| statusViz | 40 heatmap + 40 marker batch + 1 Points (`statusViz.js:219-227,330-342,95-99`) + beacon 3 mesh×(0..40 zone) (`:385-412`) | 81…201 |
| **Tổng pass chính** | | **~546…666** |

Chưa kể: pass bóng (các mesh `castShadow=true` bị vẽ lại vào shadow map — `structure.js:51-52,62-63`, `interior.js:240`, `safety.js:46-47`, `environment.js:63,87`) và pass transmission của `MeshPhysicalMaterial` (`materials.js:10-11,17`). Twin đơn giản hiện tại của `main.js` khoảng ~66 mesh (40 zone + 11 slab + roof + beacon + ground + grid + highlight + 10 sprite, `main.js:107-215`) — ước tính tĩnh.

### F1. Hợp nhất batch instanced xuyên tầng có điều kiện (giảm phân mảnh draw call)

- **Hiện trạng:** structure tạo 4 batch/tầng × 10 (`structure.js:116-134`), facade 3 batch/tầng × 10 (`facade.js:79-121`) — tổng ~70 draw call cho các phần tử lặp giống hệt nhau về geometry/material. Lý do tách tầng: camera cutaway/explode/isolate (`camera.js:8-19`).
- **Vấn đề:** Ở chế độ overview (không isolate), 70 draw call này có thể chỉ là ~6–10 batch toàn cục; chi phí CPU submit lệnh vẽ lặp lại không cần thiết.
- **Giải pháp:** Hai biểu diễn: batch toàn cục (overview) và batch per-floor (khi cutaway/explode/isolate kích hoạt) — chuyển đổi khi đổi chế độ xem; hoặc giữ per-floor nhưng bật frustum culling (F2) để tầng ngoài khung hình không tốn submit. Geometry đã dùng chung unit box (`structure.js:38`, `facade.js:42`) nên hợp nhất chỉ là gộp ma trận instance.
- **Lợi ích kỳ vọng:** Draw call pass chính giảm ~60 ở overview (ước tính tĩnh từ bảng §3.1); giữ nguyên khả năng isolate tầng.
- **Cách đo:** `renderer.info.render.calls` ở overview vs isolate trước/sau; chụp ảnh đối chiếu thị giác; đo frame time (§6.4).

### F2. Frustum culling cho nội thất & marker (đang tắt có chủ đích)

- **Hiện trạng:** `interior.js:241` đặt `im.frustumCulled = false` cho MỌI batch nội thất (chúng trải toàn tháp); `statusViz.js:332` tắt culling cho marker từng zone; `statusViz.js:97` tắt cho Points (hợp lý: particle "đậu" ở y=−1e4, `:45`).
- **Vấn đề:** Khi zoom gần 1 tầng, toàn bộ tam giác nội thất của 10 tầng vẫn được submit mỗi frame (ước tính hàng chục nghìn tam giác — `interior.js:6` đặt mục tiêu <100k). Marker 40 zone cũng luôn submit dù zone khuất.
- **Giải pháp:** Chia batch nội thất theo tầng (group `fg` đã có sẵn, `interior.js:131-134`) + `computeBoundingSphere()` + bật `frustumCulled`; marker: bounding sphere nhỏ theo zone anchor → bật culling. Lưu ý bẫy đã biết: `Box3.setFromObject` trên InstancedMesh trả về HỢP của mọi instance — kiểm tra footprint từng instance phải lặp `getMatrixAt(i)` + `geometry.boundingBox.applyMatrix4`. Đánh đổi: số draw call tăng (16→tối đa 160) nhưng tam giác submit giảm khi zoom; đo để chọn điểm cân bằng, hoặc giữ batch toàn cục + LOD theo khoảng cách camera.
- **Lợi ích kỳ vọng:** Tam giác submit giảm theo mức zoom; GPU đỡ việc ở chế độ xem gần (chế độ tương tác chính).
- **Cách đo:** `renderer.info.render.triangles` + frame time ở overview vs zoom tầng, trước/sau; xác nhận không mất hình khi isolate/cutaway.

### F3. Gom exit-sign / strobe / tuyến thoát hiểm thành batch (giảm ~150 draw call)

- **Hiện trạng:** 22 biển EXIT, mỗi biển 3 mesh rời (frame/panel/text) = 66 draw call (`safety.js:144-173`); 40 strobe dome, mỗi đèn 1 mesh + 1 material riêng để nhấp nháy độc lập (`safety.js:236-254`), update lặp 40 đèn mỗi frame (`:373-385`); 44 ống `TubeGeometry` dùng chung 1 material nhưng 44 mesh (`safety.js:268-297,348-349`).
- **Vấn đề:** ~150 draw call cho các phần tử tĩnh hoặc chỉ đổi màu; đây là cụm draw call lớn nhất của cảnh module (bảng §3.1).
- **Giải pháp:** (a) Biển EXIT → 3 InstancedMesh (frame/panel/text) với ma trận từng biển; (b) strobe → 1 InstancedMesh + `instanceColor` nhấp nháy (MaterialBasic/`onBeforeCompile` cho emissive per-instance; chấp nhận đổi màu thay đổi cường độ nếu giữ nguyên liệu hiện tại là không thể per-instance); (c) tuyến thoát hiểm tĩnh → `mergeGeometries` thành 1 mesh (pattern đã có sẵn trong repo: `interior.js:8,30`). Giữ `userData.instances` cho tooltip (`safety.js:48-50`) bằng cách lưu metadata song song.
- **Lợi ích kỳ vọng:** ~150 draw call → ~5 (ước tính tĩnh); giảm cả chi phí CPU update strobe (1 buffer màu thay vì 40 material).
- **Cách đo:** `renderer.info.render.calls` trước/sau; kiểm tra nhấp nháy strobe theo zone critical vẫn đúng hành vi (`safety.js:360-386`); chụp ảnh đối chiếu.

### F4. Texture atlas cho biển báo canvas & nhãn tầng

- **Hiện trạng:** Mỗi biển/nhãn một canvas texture riêng: biển mái 2048×128 (`facade.js:190-213`), EXIT 128×48 (`safety.js:138`), điểm tập kết 256×128 (`safety.js:327`), 10 nhãn tầng 128×64 trong twin đơn giản (`main.js:138-153,185`). Mỗi texture kéo theo material riêng → bind riêng, và chặn instancing (F3a cần chung material).
- **Vấn đề:** Nhiều texture nhỏ = nhiều bind/đổi material, tốn bộ nhớ và không gộp batch được; chữ render lại mỗi lần build.
- **Giải pháp:** Một atlas canvas (ví dụ 1024×1024, lũy thừa 2, mipmap, anisotropy 4 như hiện tại `facade.js:209`) vẽ tất cả nhãn một lần lúc build; chung 1 `MeshBasicMaterial`; mỗi biển dùng UV offset (sửa UV geometry hoặc instance attribute). dispose atlas một lần (quy tắc sở hữu tài nguyên hiện hành: `facade.js:304-315`, `safety.js:388-395`).
- **Lợi ích kỳ vọng:** Giảm số texture/material bind; mở đường cho F3a; giảm bộ nhớ texture (ước tính tĩnh: từ ~15 texture rời về 1).
- **Cách đo:** `renderer.info.memory.textures`, `renderer.info.programs.length`, draw call trước/sau; kiểm tra chữ đọc được ở khoảng cách xa (mipmap).

### F5. Kính transmission + shadow map: LOD vật liệu và bóng tĩnh

- **Hiện trạng:** `glass`/`glassDark` là `MeshPhysicalMaterial` với `transmission 0.6/0.4` (`materials.js:10-11`), nước `transmission 0.3` (`materials.js:17`). Three.js phải render thêm **pass transmission** (vẽ cảnh opaque vào render target) khi vật liệu transmission hiện diện. Bóng: 1 DirectionalLight, map 2048², PCFSoft, ortho ±65m (`lighting.js:75-83`, `main.js:77-78,99`); gần như mọi mesh đặc đều `castShadow=true` (`structure.js:51-52,62-63`, `interior.js:240`, `safety.js:46-47`). Mặt trời chỉ di chuyển khi cross-fade ngày/đêm (`lighting.js:224-250,260-267`).
- **Vấn đề:** Ở overview, kính chiếm phần lớn mặt tiền (30 batch kính + 10 spandrel kính đậm, `facade.js:118-119`) → pass transmission gần như vẽ lại cả cảnh mỗi frame; shadow map vẽ lại toàn bộ mesh castShadow mỗi frame dù cảnh tĩnh (ước tính: pass bóng + pass transmission có thể nâng tổng submit từ ~550–670 lên trên 1000 draw call/frame — suy từ cấu trúc, chưa đo).
- **Giải pháp:** (a) LOD vật liệu: overview/đêm dùng biến thể kính `MeshStandardMaterial` trong suốt opacity-only (không transmission); chỉ bật transmission khi zoom gần sảnh; (b) `renderer.shadowMap.autoUpdate=false` + `needsUpdate=true` chỉ khi cross-fade ngày/đêm hoặc explode/cutaway đang animation (cảnh còn lại tĩnh); (c) thu gọn shadow camera về footprint tháp (~±35m) hoặc 2 cascade để tăng mật độ texel hữu ích; (d) giữ trần 12 point light (đã có, `lighting.js:13,125-128`).
- **Lợi ích kỳ vọng:** Loại 1 pass cảnh đầy đủ ở chế độ thường + phần lớn pass bóng ở trạng thái tĩnh → giảm mạnh draw call/frame và thời gian GPU (định lượng bằng §6.4, không hứa trước).
- **Cách đo:** Frame time + `renderer.info.render.calls` với transmission bật/tắt, shadow autoUpdate bật/tắt; đối chiếu thị giác chế độ ngày/đêm; đo ở DPR 1 và 2.

### F6. Giảm CPU mỗi frame: dirty-flag biến換 world & vòng lặp zone/strobe

- **Hiện trạng:** `statusViz.update` lặp 40 zone mỗi frame: `syncEntry` gọi `mesh.updateWorldMatrix(true,false)` + đi bộ cây cha kiểm tra visible + copy transform anchor (`statusViz.js:547-568,596-621`); `safety.update` dựng lại `criticalSet` từ status + lặp 40 strobe mỗi frame (`safety.js:360-386`); pulse beacon mái mỗi frame (`facade.js:298-302`).
- **Vấn đề:** Phần lớn công việc này chỉ cần khi (a) status đổi hoặc (b) camera đang animate explode/cutaway/fly-to (`camera.js:658-668`); chạy lại mỗi frame là lãng phí CPU trên máy yếu/integrated GPU, gây jitter frame time.
- **Giải pháp:** Dirty-flag: chỉ `syncEntry` khi transform nguồn đổi (bắt sự kiện từ camera module hoặc so sánh ma trận) hoặc khi `setStatus`; cache visibility; `criticalSet` chỉ cập nhật trong `setStatus`; strobe nhấp nháy bằng 1 buffer `instanceColor` (cùng F3b) thay vì 40 material. Giữ particle integration mỗi frame (chúng chuyển động thật, `statusViz.js:455-483`).
- **Lợi ích kỳ vọng:** CPU frame giảm khi cảnh tĩnh; frame time p95 ổn định hơn; không đổi hành vi thị giác.
- **Cách đo:** `performance.now()` quanh các vòng lặp update (Chrome Performance panel), so sánh frame time p50/p95 trước/sau ở chế độ tĩnh và khi explode.

### F7. Ngân sách draw call + đo đạc `renderer.info` (và tích hợp assembler)

- **Hiện trạng:** `CONTRACT.md:25` chỉ đặt ngân sách tam giác (<300k), chưa có ngân sách draw call; đếm tam giác lúc build đã có (`environment.js:414-430`, `interior.js:247-253`), `statusViz.getStats`暴露 số zone/beacon/particle (`statusViz.js:637-650`). `main.js` chưa ráp module (§1.2).
- **Vấn đề:** Không có công cụ quan sát draw call/frame time thường trực → mọi tối ưu F1–F6 không kiểm chứng được; khi assembler tích hợp, cảnh module (~550–670 draw call ước tính, §3.1) sẽ thay twin ~66 draw call — cần ngân sách để không vượt khả năng máy đích.
- **Giải pháp:** (a) Thêm HUD/overlay đo `renderer.info.render.calls/triangles`, `memory.textures/geometries`, `programs.length` + ring buffer frame time p50/p95/p99 (tái sử dụng pattern ring buffer của `reports.js`); (b) đặt ngân sách draw call pass chính (đề xuất khởi điểm ≤200 ở overview — **con số mục tiêu, phải hiệu chỉnh bằng đo đạc**, không phải kết quả đo); (c) benchmark song song twin đơn giản và cảnh module (§6.4) trước khi quyết định tích hợp chính thức.
- **Lợi ích kỳ vọng:** Mọi đề xuất 3D có số liệu kiểm chứng; phát hiện hồi quy hiệu năng ngay khi thêm module.
- **Cách đo:** Chính HUD đó; quy trình §6.4; ghi kết quả vào bảng trống §6.5.

---

## 4. Bảng tổng hợp đề xuất & thứ tự ưu tiên

| Mã | Đề xuất | Tác động kỳ vọng | Công sức | Rủi ro phá bất biến |
|---|---|---|---|---|
| B1 | Hết O(N²) openLedger khi enqueue | Rất cao (publish rộng) | Thấp | Thấp (ledger semantics giữ nguyên) |
| B2 | Index tracked theo device | Cao (reconnect storm) | Thấp | Thấp |
| B7 | Cách ly WS subscriber chậm | Cao (ổn định hệ thống) | Trung bình | Thấp (thêm chính sách, không sửa TLS) |
| B4 | Gộp giao dịch dispatch | Cao (fanout) | Trung bình | **S-02: commit-before-offer phải giữ** |
| B3 | Pool connection SQLite | Cao (mọi đường DB) | Trung bình | Trung bình (thread-safety connection) |
| B9 | Hết N+1 + audit theo lô | Trung bình–cao | Trung bình | Audit an ninh vẫn đồng bộ |
| B5 | Trần Argon2 đồng thời | Cao khi storm | Thấp | Không giảm tham số hash |
| B6 | Diff delta số học | Trung bình | Trung bình | Contract status/1 không đổi |
| B8 | synchronous=NORMAL (lab) | Trung bình | Thấp | Đánh đổi bền vững khi mất điện — ghi rõ |
| B10 | Quan sát EVICTED_LOWER | Trung bình (đo đạc) | Thấp | Không đổi chính sách ngắt |
| B11 | Histogram percentile | Thấp–trung bình | Thấp | Giữ nearest-rank + monotonic |
| B12 | Simulator delay/heartbeat + đo client | Trung bình (chất lượng đo) | Thấp | Không đổi fault semantics |
| F3 | Gom biển/strobe/tuyến | Rất cao (3D, ~150 calls) | Trung bình | Giữ tooltip metadata |
| F5 | Kính LOD + bóng tĩnh | Rất cao (3D, pass phụ) | Trung bình | Thị giác ngày/đêm phải đối chiếu |
| F2 | Frustum culling nội thất/marker | Cao khi zoom | Trung bình | Bẫy bounding hợp InstancedMesh |
| F1 | Batch xuyên tầng có điều kiện | Trung bình–cao | Trung bình | Giữ isolate/cutaway |
| F4 | Texture atlas | Trung bình | Thấp | Quy tắc dispose tài nguyên |
| F6 | Dirty-flag update | Trung bình | Thấp | Không đổi hành vi |
| F7 | Ngân sách + HUD đo | Điều kiện tiên quyết đo đạc | Thấp | Additive |

Thứ tự thực hiện khuyến nghị: **F7/B10 (quan trắc trước) → B1 → B2 → B7 → B4/B3 → B9/B5/B6/B8/B11/B12 → F3 → F5 → F2/F1/F4/F6**. Nguyên tắc: đo trước khi sửa, sửa từng đề xuất một, chạy lại bộ test nghiệm thu của module (không chạy trong phạm vi tài liệu này).

---

## 5. Bất biến phải giữ khi tối ưu (tóm tắt từ các vòng review)

1. **S-02:** attempt + SENT commit TRƯỚC khi frame vào hàng đợi writer; `updateDispatchState` có guard `state IN (PENDING,SENT,RETRY_SCHEDULED)` (`FanoutEngine.java:242-251`, `DeliveryRepo.java:235-247`).
2. **S-01:** `recordAck` trả cờ inserted; `ON CONFLICT DO NOTHING`; ack phải khớp (deviceId, alertId, revision) (`DeliveryRepo.java:150-224`).
3. **S-10:** FAILED là terminal cho ngân sách retry; recovery không tiêu attempt mới (`FanoutEngine.java:230-236`).
4. **Thứ tự pragma:** FK → busy_timeout → WAL, serialized per-file khi khởi tạo (`Db.java:54-58,50-51`); không đảo thứ tự.
5. **Monotonic:** counters zone và delivery state chỉ tiến (`StatusFeedService.java:33-37`, rank guard `DeliveryRepo.java:198-211`); dashboard dựa vào tính đơn điệu để spawn particle (`statusViz.js:536-541`).
6. **Enqueue sau commit** (`TransactionalPublisher.java:110-111`); fanout chỉ sau commit của UPDATE/CANCEL (`ServerStore.java:312-318,359-363`).
7. **Giới hạn tài nguyên:** maxConnections 512, pre-auth TTL, WS cap 64, frame size guard (§1.1) — không nới lỏng vì hiệu năng.
8. **3D:** dispose chỉ tài nguyên module sở hữu, không dispose thư viện materials chung (`facade.js:304-315`, `environment.js:434-447`, `safety.js:388-395`); tên mesh tầng đệm `F05` để khớp zoneCode.

---

## 6. Kế hoạch benchmark 100 → 200 → 500 client

### 6.1 Môi trường & cấu hình

- Một máy macOS (server + simulator + dashboard cùng máy — ghi rõ hạn chế §6.6), JDK ≥24 (`pom.xml:19`), build bằng `mvn -q -DskipTests package` (người thực hiện chạy; tài liệu này không chạy build).
- Server mặc định (`ServerConfig.Builder`, `ServerConfig.java:56-74`): TLS 9443, WS 9444, heartbeat 10s/3 missed, retry 3s/6s/12s tối đa 3, writer queue 256, statusBatch 500ms, devicesPerZone 12 (→ 480 device seed), Argon2 t=2/m=16MiB/p=1, preAuth TTL 10s, **maxConnections 512**.
- Lưu ý CLI: `ServerMain` chỉ expose `--db --keystore --storepass --tls-port --ws-port --building --devices-per-zone --no-seed --heartbeat-ms --retry-base-ms` (`ServerMain.java:35-52`); các knob `statusBatchMs/maxConnections/argon2` chỉ chỉnh qua Builder → benchmark dùng mặc định, hoặc harness test riêng nếu cần biến thiên.
- **500 agent + operator nằm sát trần 512**; churn reconnect có thể tạo phiên pre-auth chồng lấn → nếu chạm trần, giảm còn 480 agent hoặc nâng trần qua harness (ghi rõ cấu hình đã dùng trong kết quả).

### 6.2 Provisioning & kịch bản tải

**Provisioning thiết bị simulator (bắt buộc):** `SimAgent` gửi `deviceCode = SIM-DEV-0001…` (`SimulatorMain.java:71`, `SimAgent.java:137`) và `AuthService.authenticateDevice` tra theo `device_code` (`AuthService.java:37`); seed mặc định chỉ tạo `DEV-A-F%02d-%s-%02d` (`ServerStore.java:675`). Phải tạo trước N device có mã khớp, dùng chung hash của `device-demo-secret` (mọi device demo chia sẻ 1 hash, `ServerStore.java:637`), gán floor/zone để status feed đếm đúng:

```sql
-- Ví dụ tạo 500 device SIM-DEV-0001..0500, phân bố đều 10 tầng × 4 zone,
-- sao chép credential_hash từ device demo đã seed (chạy sau khi server seed lần đầu).
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 500)
INSERT INTO devices(id, floor_id, device_code, credential_hash, enabled, enrolled_at)
SELECT lower(hex(randomblob(16))),
       (SELECT id FROM floors ORDER BY floor_number LIMIT 1 OFFSET (i-1)/50),
       'SIM-DEV-' || printf('%04d', i),
       (SELECT credential_hash FROM devices WHERE device_code='DEV-A-F01-A-01'),
       1, strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM n;
-- Gán zone_devices tương ứng để deviceInfoMap/zonesByFloor đếm đúng (StatusFeedService.init).
```

Chạy simulator với `--device-secret device-demo-secret` (khớp hash đã sao chép), `--truststore certs/aegis-truststore.p12 --truststore-password aegis-demo` (`scripts/gen-server-cert.sh`). Fleet: `--agents 100|200|500 --seed 42 --stagger-ms 5` (mặc định, `SimulatorConfig.java:33-34`), `--duration 300` cho cửa sổ đo.

**Kịch bản lỗi** (`FaultProfile.java:21-26`): `clean` (baseline), `mixed` (drop 0.15, delay 300+jitter1200ms, dup 0.3×2, disconnect 0.2), `lossy` (drop 0.3), `flaky` (disconnect 0.3 — bão reconnect).

**Tải publish** (operator thật hoặc harness dùng `aegis-client`/test client):
- F1: publish theo zone (12 đích), tần suất 1 lệnh/10s.
- F2: publish theo tầng (48 đích), 1 lệnh/10s.
- F3: publish toàn tòa nhà (480 đích), 1 lệnh/30s + burst 3 lệnh liên tiếp (đo B1/B4).
- F4: ALERT_UPDATE/CANCEL xen kẽ (đo supersede + tombstone, `FanoutEngine.java:197-205`).

**Quy trình mỗi bậc 100/200/500:** DB mới (`scripts/initdb.sh` + seed) → khởi server → ramp fleet (stagger 5ms) → warm-up 60s → cửa sổ đo 300s → lặp 3 lần/kịch bản → ghi medoid + khoảng biến thiên. Không trộn kịch bản trong cùng cửa sổ đo.

### 6.3 Chỉ số & cách thu thập (không bịa số — bảng kết quả để trống)

**Phía server:**
- `metrics_samples`: `delivery_latency_p50/p95/p99`, `transport_retries` (flush 30s, `AegisServer.java:177-186`; schema `002_showcase_expansion.sql:121`). Ngữ nghĩa end-to-end publish→ACK_RECEIVED (`FanoutEngine.java:377-382`) — ghi kèm chú giải B11.
- Counters monotonic: published/ackReceived/ackUser/failed/retry/slowClientDisconnects (`MetricsRegistry.java:63-69`) — dump định kỳ qua harness (getter public).
- Độ trễ vận chuyển thuần (SQL, không cần sửa code): so `device_acks.acked_at` với `delivery_attempts.attempted_at` của attempt SENT cuối cùng (schema `001_base_schema.sql:13-14`; timestamp fixed-width 9 chữ số nano nên so sánh TEXT đúng thứ tự thời gian):

```sql
SELECT d.id,
       (julianday(a.acked_at) - julianday(t.attempted_at)) * 86400000 AS transport_ms
FROM device_acks a
JOIN deliveries d ON d.id = a.delivery_id
JOIN delivery_attempts t ON t.delivery_id = d.id AND t.outcome = 'SENT'
WHERE a.ack_kind = 'RECEIVED'
  AND t.attempted_at = (SELECT MAX(attempted_at) FROM delivery_attempts
                        WHERE delivery_id = d.id AND outcome = 'SENT');
-- p50/p95/p99 tính ngoài (Python/SQL percentile) trên cửa sổ đo.
```

- Audit: đếm `SLOW_CLIENT_DISCONNECT`, `HEARTBEAT_SUSPECT`, `DELIVERY_FAILED`, `RETRY`, `DUPLICATE` theo cửa sổ (`audit_events`, `AuditRepo.java:54-67`).
- Hàng đợi: độ sâu scheduler (`PriorityScheduler.size()`), `evictedCount`/`size` mỗi phiên (`BoundedWriterQueue.java:75-76`) — trước B10 chỉ đọc qua harness/test.
- SQLite: kích thước file `-wal`, số lần BUSY trong log, (sau B8) thống kê checkpoint.
- Hệ thống: JFR (`jdk.CPULoad`, `jdk.GCPhasePause`, `jdk.VirtualThreadPinned`), RSS, async-profiler wall/CPU nếu cần.

**Phía simulator:** summary `SimStats` in cuối phiên (`SimulatorMain.java:50`): connectSuccess/connectFailures/authRejections/reconnects/syncRequests/alertsReceived/duplicatePushes/acksSent/acksDropped/acksDelayed/duplicateAcks/disconnects/heartbeats/agentsFinished. (Sau B12: thêm percentile push→ACK phía client.)

**Phía dashboard:** nhịp + kích thước byte STATUS_DELTA (đếm trong `ws.js`), số subscriber; 3D theo §6.4.

### 6.4 Benchmark 3D (song song với benchmark backend)

- Hai cấu hình: (a) twin đơn giản `main.js` hiện tại; (b) cảnh module `src/three` ráp qua assembler (khi có) — nếu chưa tích hợp, đo module bằng harness trang riêng theo `CONTRACT.md:28-29`.
- Góc nhìn cố định: `overview`, zoom 1 tầng (`flyToFloor`), `explode`, `isolateFloor` (`camera.js:8-19`); DPR 1 và 2 (`main.js:75`); ngày và đêm (`lighting.js:252-257`).
- Mỗi cấu hình: 60s quay camera theo quỹ đạo cố định; thu `renderer.info.render.calls/triangles`, `memory.textures/geometries`, `programs.length` sau mỗi frame; frame time bằng `performance.now()` → p50/p95/p99 (ring buffer, pattern `reports.js`).
- Biến thể A/B cho F5: transmission bật/tắt, shadow `autoUpdate` bật/tắt; cho F2: culling bật/tắt; cho F3/F4: trước/sau khi gom batch/atlas.
- Mục tiêu tham chiếu (KHÔNG phải kết quả đo): frame time p95 ≤ 16.7ms ở DPR1 trên máy đích cho chế độ overview; ngân sách draw call khởi điểm ≤200 (F7) — hiệu chỉnh bằng số thật.

### 6.5 Bảng kết quả (TRỐNG — điền sau khi chạy thật)

| Bậc | Kịch bản | p50 (ms) | p95 (ms) | p99 (ms) | retry/100 delivery | FAILED | slow-client disc. | heartbeat suspect | CPU server % | RSS (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | clean | | | | | | | | | |
| 100 | mixed | | | | | | | | | |
| 200 | clean | | | | | | | | | |
| 200 | mixed | | | | | | | | | |
| 500 | clean | | | | | | | | | |
| 500 | mixed | | | | | | | | | |
| 500 | flaky | | | | | | | | | |

| Cấu hình 3D | Góc nhìn | DPR | draw calls | triangles | frame p50 (ms) | frame p95 (ms) | frame p99 (ms) | textures |
|---|---|---|---|---|---|---|---|---|
| twin đơn giản | overview | 1 | | | | | | |
| twin đơn giản | overview | 2 | | | | | | |
| cảnh module | overview | 1 | | | | | | |
| cảnh module | zoom tầng | 1 | | | | | | |
| cảnh module | explode | 1 | | | | | | |

### 6.6 Tiêu chí đạt & mối đe dọa giá trị

**Tiêu chí đạt (ngưỡng suy từ thiết kế, không phải số đo):**
- `clean`: 0 `SLOW_CLIENT_DISCONNECT`, 0 `FAILED`, 0 retry (mọi ACK trong cửa sổ đầu 3s — `RetryPolicy` base 3000, `ServerConfig.java:64`); p99 publish→ACK < 3s.
- `mixed`: tỷ lệ retry nhất quán với dropRate 0.15 (sai số lấy mẫu); không có `HEARTBEAT_SUSPECT` do delay injection (B12); duplicate ACK được xử lý idempotent (đếm `DUPLICATE` khớp `duplicateAcks` phía simulator).
- Tăng bậc 100→200→500: p99 tăng dưới tuyến tính so với số client; hàng đợi scheduler drain hết giữa các publish; không chạm trần kết nối ngoài ý muốn.
- 3D: đạt mục tiêu frame time §6.4 sau tối ưu; draw call trong ngân sách F7.

**Mối đe dọa giá trị (phải ghi trong báo cáo kết quả):** một máy (client+server tranh CPU/IO), TLS loopback không đại diện mạng thật, chi phí Argon2 khi reconnect, GC pause, seed cố định 42 (fault deterministic — tốt cho tái lập, không bao phủ phân phối lỗi thật), đồng hồ client/server lệch nhau khi so percentile chéo.

---

## 7. Tài liệu tham chiếu (mã nguồn đã đọc)

- `aegis-server`: `AegisServer`, `TlsGateway`, `ClientSession`, `BoundedWriterQueue`, `FanoutEngine`, `MessageRouter`, `AuthService`, `StatusFeedService`, `StatusFeedServer`, `WsConnection`, `MetricsRegistry`, `ServerStore`, `SessionRegistry`, `ServerConfig`, `ServerMain`, `SyncService`, `AlertService` (đường publish).
- `aegis-persistence`: `Db`, `TransactionalPublisher`, `DeliveryRepo`, `AuditRepo`, `db/001_base_schema.sql`, `db/002_showcase_expansion.sql`.
- `aegis-core`: `PriorityScheduler`.
- `aegis-protocol`: `FrameCodecImpl`.
- `aegis-simulator`: `SimulatorMain`, `SimAgent`, `SimulatorConfig`, `FaultProfile`, `SimStats`.
- `aegis-dashboard`: `src/main.js`, `src/three/{CONTRACT.md, config, materials, structure, facade, interior, safety, environment, lighting, statusViz, camera}.js`.
- `pom.xml` (Java release 24, sqlite-jdbc 3.46.1.0); `scripts/initdb.sh`, `scripts/gen-server-cert.sh`; `docs/DEMO-RUNBOOK.md`, `docs/SECURITY.md`.

*Tài liệu này chỉ phân tích và đề xuất; không sửa mã nguồn, không chạy build/test, không commit. Mọi số liệu hiệu năng thật sẽ được điền vào §6.5 sau khi thực thi kế hoạch benchmark.*
