# PEER REVIEW ĐỘC LẬP — MODULE `aegis-server` (review-c)

| | |
|---|---|
| **Ngày review** | 2026-11-21 (UTC+07) |
| **Reviewer** | Independent peer review — agent `review-c` (không phải tác giả module) |
| **Commit được review** | `251b2a8` — *feat(server): TLS gateway+RBAC+fanout+ACK/retry+recovery+WS status (52 tests)* |
| **Phạm vi** | Toàn bộ 25 class `aegis-server/src/main` (TlsGateway, MessageRouter, ClientSession, SessionRegistry, BoundedWriterQueue, FanoutEngine, AlertService, IncidentService, SyncService, AuthService, Argon2idHasher, RbacService, ServerStore, StatusFeedService/Server, WsConnection, MetricsRegistry, AuditService, AegisServer, ServerConfig, ServerMain, Json, ZoneStatus, StatusNotifier, PasswordHasher) + 12 file test |
| **Phương pháp** | (1) chạy lại `mvn -q -pl aegis-server test` **×3**, đếm từ surefire XML; (2) đọc/audit toàn bộ source + đối chiếu `docs/PROTOCOL.md`, `docs/SECURITY.md`, `aegis-dashboard/docs/status-contract.md`, skill `aegis-ebs-server`/`aegis-ebs-persistence`; (3) **33 kiểm thử đối kháng độc lập** (harness `ReviewProbe`, package `aegis.server`, chạy trên TLS loopback thật + SQLite scratch) phủ RBAC wire, ACK giả, retry exhaustion, recovery restart, TLS cert lạ, WS contract, slow-client, pre-auth linger; (4) đối chiếu quy ước retry với `RetryPolicy` core |
| **Ràng buộc** | CHỈ đọc + chạy + báo cáo. Không sửa source, không git commit. Scratch: `/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-c` |
| **Môi trường** | macOS 15.7.3 · Maven 3.10.1 (Homebrew JDK 27.0.1.1, `release=24`) · sqlite-jdbc 3.46.1.0 · argon2-jvm 2.11 |

> **Bối cảnh quan trọng (provenance):** lúc bắt đầu review, working tree có thay đổi CHƯA commit của review-b (M1 persistence: cross-device ack guard, `ON CONFLICT DO NOTHING`, `Sql.TS` fixed-width, busy_timeout trước WAL, payload_hash v2) — **sau đó được commit thành `df0241b` ngay trong lúc review diễn ra** (HEAD hiện tại: `df0241b`; source `aegis-server` vẫn nguyên ở `251b2a8`, git diff trống). Lệnh acceptance `-pl aegis-server` (không `-am`) resolve `aegis-persistence` từ **`~/.m2` jar đã install**, không từ working tree. Jar này bị agent khác thay lúc **18:48** ngay giữa quá trình review (53.310 B pre-fix → 65.732 B có fix; xác nhận bằng grep constant pool: `does not belong`, `ON CONFLICT`, `appendInstant`). Vì vậy review chạy đủ 2 cấu hình: jar cũ (run 1) và jar đã fix M1 (run 2, 3 + probe).

---

## VERDICT: **FAIL** (acceptance không tái lập được 52 test xanh; 1 issue HIGH integration-regression)

**Tóm tắt (VI):** Kiến trúc server vững và phần lớn bất biến bảo mật cốt lõi **đã được xác nhận bằng kiểm thử đối kháng thật**: RBAC publish đúng ma trận (EMPLOYEE/FLOOR_MANAGER vượt scope bị FORBIDDEN, DEVICE bị chặn), ACK cross-device/wrong-revision/wrong-alert bị từ chối ngay tại router (kể cả khi chưa có fix M1), retry đúng quy ước PROTOCOL (initial + 3 retries, cửa sổ 3s/6s/12s + jitter, messageId mới mỗi attempt — đo được 260/522/989 ms ở base 200 ms), TLS cert lạ bị client pinned từ chối (PKIX), slow-client eviction hoạt động đúng (audit + metric), recovery giao lại delivery sau restart đúng, WS snapshot/delta/resync khớp `status/1`, không log secret thật. **TUY NHIÊN** lệnh acceptance `mvn -q -pl aegis-server test` **không xanh**: 48/52 với persistence đã fix M1 (2 lỗi regression tất định + 2 lỗi test-harnessrace lặp lại 3/3 lần), 50/52 với jar cũ. Nghiêm trọng nhất: router phát hiện duplicate ACK bằng cách bắt `SQLException UNIQUE` — fix M1 (`ON CONFLICT DO NOTHING`) đã xóa ngoại lệ này ⇒ `duplicate=false` vĩnh viễn, test 2 fail tất định, audit mất outcome `DUPLICATE`; và race `RECEIVED→SENT` (updateState không guard sau khi ghi attempt) làm `FanoutEngineTest.ackReceivedStopsRetryCycle` fail 2/2 lần với jar mới. Kèm theo là các gap bảo mật mức MEDIUM đã được chứng minh bằng probe: lỗi INTERNAL lộ `e.getMessage()` ra wire (NFR-017), `STATUS_SUBSCRIBE`/WS không kiểm role/scope (EMPLOYEE đọc toàn tòa), `ACK_USER` nhận `userId` tùy ý không xác minh, kết nối pre-auth sống vô hạn (không soTimeout/cap), timestamp variable-width trong `Json.ts`/`ServerStore` (so sánh expiry TEXT sai — cùng lớp bug M1 đã fix ở persistence), update/cancel không atomic, recovery vượt ngân sách retry (attemptCount=6 đo được).

**Issue count: 22 actionable (0 CRITICAL · 1 HIGH · 9 MEDIUM · 12 LOW) + 6 INFO.**

---

## 1. Test rerun — xác nhận từ surefire XML (không dựa stdout `-q`)

Lệnh: `cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs && mvn -q -pl aegis-server test`

| Run | Thời điểm | `aegis-persistence` jar trong `~/.m2` | Tests | Fail | Exit |
|---|---|---|---:|---:|---|
| 1 | ~18:45 | cũ (17:52, 53.310 B, chưa fix M1) | **52** | **2** | 1 |
| 2 | ~18:52 | đã fix M1 (18:48, 65.732 B) | **52** | **4** | 1 |
| 3 | ~19:10 | đã fix M1 | **52** | **4** | 1 |

Tổng hợp surefire run 3 (`aegis-server/target/surefire-reports/TEST-*.xml`):

| Test class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Argon2idHasherTest | 5 | 0 | 0 | 0 |
| BoundedWriterQueueTest | 6 | 0 | 0 | 0 |
| FanoutEngineTest | 7 | **1** | 0 | 0 |
| MetricsRegistryTest | 6 | 0 | 0 | 0 |
| RbacServiceTest | 5 | 0 | 0 | 0 |
| SessionRegistryTest | 5 | 0 | 0 | 0 |
| TlsLoopbackIntegrationTest | 10 | **3** | 0 | 0 |
| ZoneStatusTest | 8 | 0 | 0 | 0 |
| **Tổng** | **52** | **4** | **0** | **0** |

### 1.1. Phân tích 4 failure (run 2/3, jar đã fix M1)

1. **`TlsLoopbackIntegrationTest.publishFanoutAckAndIdempotentReplay:212` — `duplicate` expected true, got false. TẤT ĐỊNH.** `MessageRouter.ack` (MessageRouter.java:224‑232) suy ra `duplicate=true` bằng cách bắt `SQLException` chứa "UNIQUE" từ `DeliveryRepo.recordAck`. Fix M1 đổi INSERT sang `ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING` ⇒ không còn ngoại lệ ⇒ `duplicate=false` cho mọi replay, audit ghi `OK` thay vì `DUPLICATE`. Probe S2 xác nhận trên wire: ACK lặp trả `{"ok":true,"duplicate":false,...}`. → **S-01 (HIGH)**.
2. **`FanoutEngineTest.ackReceivedStopsRetryCycle:197` — expected RECEIVED, got SENT. Lặp 2/2 lần với jar mới (pass với jar cũ).** Race thật trong server: `FanoutEngine.sendPush` ghi frame lên socket **trước** khi commit `recordAttempt` + `updateState(SENT)` (FanoutEngine.java:238‑249); `DeliveryRepo.updateState` không có rank guard. Test đọc frame xong gọi `recordAck` ngay (FanoutEngineTest.java:193‑194, không poll SENT trước): transaction ack (dài hơn do SELECT guard của M1) commit `RECEIVED` trước, rồi `updateState(SENT)` của dispatcher commit sau ⇒ **trạng thái durable thụt lùi RECEIVED→SENT** (vi phạm "ACK only advances state" ở chiều ngược lại). Retry vẫn dừng (untrack) nhưng DB sai → SYNC/dashboard sai, recovery sẽ re-enqueue delivery đã ack. Probe S4 (12 ack nhanh qua wire) không tái lập được interleaving này (0/12) — race phụ thuộc timing, bằng chứng là 2 lần fail surefire liên tiếp. → **S-02 (MEDIUM)**.
3. **`TlsLoopbackIntegrationTest.slowClientIsDisconnectedWhenQueueOverflows:475` — audit thiếu `SLOW_CLIENT_DISCONNECT`. Lặp 3/3 lần.** Không phải server bug: config test `heartbeat(800,3)` (TlsLoopbackIntegrationTest.java:71) → suspect sau ~2,4 s, trong khi flood 320 publish × 8 KB mất ~3 s trên máy này ⇒ **heartbeat sweep đóng client chậm trước khi queue 256 tràn**. Bằng chứng surefire system-out: đúng 2 dòng `heartbeat suspect` (test 8 + test 9), **0** dòng `slow client disconnected`; session vẫn bị đóng (until dòng 473 pass) nhưng audit là `HEARTBEAT_SUSPECT`. `ClientSession.send` trả `REJECTED_SLOW` không callback khi session đã closed (ClientSession.java:70) nên không có audit SLOW_CLIENT. Probe S9 với `heartbeat(60000,3)` (tolerance > flood time): eviction fires đúng, audit + metric = 1 ⇒ **policy server đúng, test race**. → **S-03 (MEDIUM, test harness)**.
4. **`TlsLoopbackIntegrationTest.crashRecoveryRedeliversAfterRestart:495` — timeout "alert3 delivery parked". Lặp 3/3 lần. CASCADE từ failure 3.** Test 9 fail ở dòng 475 **trước** vòng drain 320 `COMMAND_RESULT` (dòng 478‑481) ⇒ queue operator còn reply cũ. Helper `publish()` (dòng 130‑142) dùng `request→await("COMMAND_RESULT")` **không đối chiếu correlationId** ⇒ test 10 nhặt reply flood cũ (ok=true, alertId của alert tầng 3) làm `alert3Id`; `deliveryOf(alert3Id, d1Id)` (thiết bị tầng 1) luôn throw ⇒ until timeout. Chính pitfall đã ghi trong skill ("stale COMMAND_RESULT poisons alertId assertions") nhưng harness chưa phòng vệ khi test trước fail. → gộp **S-03**.

**Kết luận acceptance:** tuyên bố "52 tests green" của commit `251b2a8` **không tái lập được trên máy này ở cả 2 cấu hình persistence** (50/52 jar cũ; 48/52 jar mới). Tại HEAD hiện tại (`df0241b`, đã gồm fix M1): **48/52 — acceptance ĐỎ**. 2 failure là regression integration thật (S-01, S-02), 2 là test-harness race/cascade (S-03). Lưu ý quy trình: `-pl aegis-server` không `-am` khiến kết quả phụ thuộc jar install gần nhất trong `~/.m2` — acceptance nên chạy `-am` hoặc install lại persistence có chủ đích (khuyến nghị R0).

---

## 2. Audit wire-level (đối chiếu PROTOCOL.md / SECURITY.md / status-contract.md)

Bằng chứng: `ReviewProbe` — 33 check, **33 PASS / 0 FAIL** ở lần chạy cuối (các check "GAP" PASS nghĩa là *gap được xác nhận tồn tại*; chi tiết trong `probe-results.txt`).

| Hạng mục | Kết luận | Bằng chứng |
|---|---|---|
| Auth gate (HELLO/AUTH_* trước auth; mọi command khác NOT_AUTHENTICATED) | ✅ ĐÚNG | MessageRouter.java:89‑93; test 1 pass; probe anonymous publish bị ERROR |
| RBAC publish theo role×scope (EMPLOYEE/DEVICE không publish; FM chỉ tầng grant; SECOPS building; ADMIN mọi nơi) | ✅ ĐÚNG | Probe S1: EMPLOYEE→FORBIDDEN, FM floor1→FORBIDDEN, FM floor7→OK, FM cancel alert floor1→FORBIDDEN, SECOPS→OK, DEVICE→ERROR; `ScopedRBAC`+`Grants` bất biến, scope từ DB (`RbacService.grantsFor`), `floorId=null→"*"` không khớp floor grant ⇒ FM không lách building-wide |
| Zone normalization ("A" ↔ "F01-A") | ✅ ĐÚNG | AlertService.resolveScope:90‑95; TargetResolver key zone_code; mismatch zone/floor → NO_TARGETS (không phát nhầm) |
| ACK cross-device / wrong-alert / wrong-revision | ✅ ĐÚNG (router guard độc lập với M1) | Probe S2: claim deviceId khác → FORBIDDEN; deliveryId của device khác → NOT_FOUND; revision sai → NOT_FOUND; alertId sai → NOT_FOUND (MessageRouter.java:210‑221). Fix M1 trong persistence là phòng thủ tầng 2 — **đã có hiệu lực trong jar hiện tại** |
| ACK idempotent replay | ⚠️ State đúng, **flag `duplicate` hỏng với jar M1** | Probe S2: state giữ ACKNOWLEDGED, ok=true, nhưng duplicate=false → S-01 |
| ACK_USER accountability | ❌ GAP | Probe S2: `userId=admin` tùy ý được chấp nhận, ghi thẳng vào `device_acks.user_id` (UUID admin thật) — MessageRouter.java:222 không xác minh → S-06 |
| ACK_CANCEL | ⚠️ Self-suppression | Probe S2: device tự CANCEL delivery của alert đang ACTIVE (alert vẫn ACTIVE) — không kiểm tra alert state/CANCEL_PUSH trước → S-11 (chỉ hại chính device đó, không cross-device) |
| Retry: initial + 3 retries rồi FAILED; messageId mới mỗi attempt | ✅ ĐÚNG | Probe S3: 4 attempts (OFFLINE×4 khi offline; SENT×4 khi online-im-lặng), 4 messageId phân biệt, attemptCount=4, FAILED; cửa sổ đo được w1=260, w2=522, w3=989 ms (base 200 + jitter[0,200) + overhead) — xem §3 |
| Retry sau recovery/reconnect | ❌ VƯỢT NGÂN SÁCH | Probe S5: delivery FAILED lúc restart được recover → attempt 5 (offline) → device reconnect trong cửa sổ → attempt 6 (attemptCount=6 đo được); `settled()` không gồm FAILED (FanoutEngine.java:230‑233), `resumeDevice`/dispatch cứ push tiếp → S-10 |
| Slow-client eviction (queue 256, priority evict, REJECTED_SLOW→disconnect+audit) | ✅ ĐÚNG (policy) | Probe S9: flood 320×8KB, client không đọc → SLOW_CLIENT_DISCONNECT audit + metric=1 + session đóng; BoundedWriterQueue TreeSet đúng chiều priority (Priority ordinal INFO=0…CRITICAL=3), CRITICAL không bị INFO chặn; test 9 fail là race heartbeat (S-03) |
| TLS: server identity từ PKCS12; cert lạ bị từ chối | ✅ ĐÚNG | Probe S6: client truststore chỉ chứa cert khác → `SSLHandshakeException (certificate_unknown) PKIX path building failed`; `setNeedClientAuth(false)` đúng thiết kế (auth ứng dụng sau TLS); lưu ý suite server dùng trust-all client nên không tự kiểm tra pinning — probe bổ sung |
| WS status/1 contract | ✅_SHAPE ĐÚNG / ❌ AUTH GAP | Probe S7: snapshot đủ version/type/buildingId/ts/floors 10×4/8 trường zone/metrics 5 khóa; severity khớp normative `ZoneStatus.deriveSeverity` từng zone; delta mang activeAlerts full-list; resync→snapshot. **Kết nối WS không cần auth** (và STATUS_SUBSCRIBE qua TLS chỉ requireOperator, EMPLOYEE vẫn nhận full snapshot — trái ma trận SECURITY.md §4.2) → S-05 |
| Recovery đúng state | ✅ MỘT PHẦN | Probe S5: RECOVERY audit, delivery scheduler-owned được giao lại sau restart+reconnect, ACK được → RECEIVED; SYNC mang active alerts + tombstones (SyncService/ServerStore truy vấn theo device_id của session, không theo payload client). FAILED-recovery vượt ngân sách retry + counter failed đếm lại sau restart → S-10 |
| Secrets trong log | ✅ SẠCH (secret thật) | Grep toàn bộ LOG/System.out: không log password/credential; AuthService không log; Argon2 wipe char[]; audit chỉ actor/action/scope/outcome/correlationId + metadata nghiệp vụ; AUTH_RESULT không echo password. Ngoại lệ: banner demo của ServerMain in credential demo công khai (S-21, lab-only) |
| Error sanitize (NFR-017) | ❌ GAP | MessageRouter.java:113,116 gửi `e.getMessage()` (SQL/storage) ra wire; AlertService.java:166,214,263 + IncidentService.java:58,82 tương tự. SECURITY.md §3.9 yêu cầu gateway sanitize — **chưa làm** → S-04 |
| Frame validation | ✅ (kế thừa protocol) | FrameCodecImpl chặn oversize trước allocation, strict UTF-8, version==2, type enum (56 test protocol); router thêm UNKNOWN_TYPE; Json.MAPPER dùng StreamReadConstraints mặc định (đã ghi 🟡 trong SECURITY.md — INFO) |
| Session lifecycle | ⚠️ | deviceOnline đóng phiên cũ ✅; heartbeat sweep ✅; **sweep bỏ qua session chưa auth** (SessionRegistry.java:65) + không soTimeout/cap kết nối (TlsGateway.java:90‑97) ⇒ probe S8: session pre-auth sống >4s với tolerance 2,4s, giữ vô hạn bằng HEARTBEAT_ACK → S-07; chưa absolute TTL (đã ghi 🔧) → S-19 |

---

## 3. Quy ước retry — đối chiếu PROTOCOL.md và RetryPolicy core

**PROTOCOL.md:** "Retry demo: 3s/6s/12s + jitter, max 3 retries then FAILED"; "each transport attempt new messageId".

**Core `RetryPolicy` (aegis-core):** `maxAttempts` *tính cả attempt đầu* (javadoc dòng 9‑14); `shouldRetry(attemptsSoFar) = attemptsSoFar < maxAttempts`; `delayMs(attemptsSoFar) = base·2^attemptsSoFar + jitter[0, base)`, bão hòa không overflow.

**Caller server dùng thế nào (chuỗi chính xác):**
- `AegisServer.java:87`: `new RetryPolicy(Math.max(1, config.maxRetries()=3), base=3000)` ⇒ core nhận `maxAttempts=3` (**đảo ngữ nghĩa**: server dùng như "max retries").
- `FanoutEngine.windowMs(attempt)` (273‑276): `index = clamp(attempt−1, 0, maxAttempts−1=2)` → `delayMs(index)`. Sau attempt 1 → delayMs(0)=3s+j; attempt 2 → delayMs(1)=6s+j; attempt 3 → delayMs(2)=12s+j; attempt ≥4 kẹp về 12s+j (chỉ dùng cho offline slot cuối).
- `FanoutEngine.checkDelivery` (299‑300): `retriesConsumed = attemptCount−1`; `shouldRetry(consumed)`: consumed 0,1,2 → true (retry 1,2,3); consumed 3 → false → `markFailed`.
- **Net: initial send + đúng 3 retries = 4 attempts, cửa sổ 3/6/12s + jitter, rồi FAILED — KHỚP PROTOCOL.** Bù trừ kép (`maxRetries` truyền vào tham số `maxAttempts` + nạp `attemptCount−1`) triệt tiêu nhau; hành vi đúng nhưng **ngữ nghĩa ngược javadoc core** → hazard bảo trì (S-13): ai "sửa" caller truyền `attemptCount` theo javadoc sẽ mất 1 retry; `Math.max(1,…)` khiến `maxRetries=0` vẫn retry 1 lần.
- **Liên quan báo cáo off-by-one của reviewer trước:** ở tổ hợp core+server hiện tại, index `attempt−1` là **đúng** (không còn off-by-one hành vi). Bằng chứng độc lập: `FanoutEngineTest` zero-jitter cửa sổ đúng 150/300/450 ms (pass cả 3 run); probe S3 base 200 đo được 260/522/989 ms ∈ [base·2^k, base·2^k + base) + overhead scheduler.
- messageId: `sendPush` sinh `Json.uuid()` mỗi attempt (FanoutEngine.java:236), ghi `delivery_attempts`; offline attempt cũng ghi messageId riêng (264). Probe S3: 4/4 phân biệt ở cả 2 chế độ ✅.
- Attempt counting gồm offline slot (`offlineAttempt` tăng attemptCount + scheduleRetry) ✅ đúng skill; generation guard chống stale check ✅ (279‑291).

---

## 4. Đối chiếu M1 persistence (review-b)

- **Cross-device ACK:** router đã tự guard (deviceId session + alertId/revision/deviceId của delivery) **trước** khi gọi `recordAck` ⇒ an toàn kể cả với jar cũ (probe S2 chạy trên jar mới cũng pass). Fix M1 (SELECT guard + UPDATE bind đủ bộ khóa) là phòng thủ tầng 2 cần thiết cho caller khác — **đã install, có hiệu lực**.
- **Regression S-01 (HIGH):** `ON CONFLICT DO NOTHING` của M1 xóa tín hiệu UNIQUE mà router dựa vào để báo `duplicate`. Hai module phải chốt hợp đồng: `recordAck` trả về cờ inserted/no-op (hoặc router SELECT `device_acks` trước), rồi cập nhật test 2 + audit `DUPLICATE`.
- **Timestamp:** M1 sửa `Sql.TS` sang fixed-width 9 chữ số, nhưng **server không dùng `Sql.ts`** cho các bảng riêng: `Json.ts` (Json.java:14) vẫn `ISO_INSTANT` variable-width, được `ServerStore` ghi vào incidents/incident_events/metrics_samples/seed và làm `now` trong so sánh TEXT `expires_at <= ?` (ServerStore.java:296‑297, 315, 335). Demo lexicographic: `…00.4Z > …00.45Z` dù 400ms < 450ms ⇒ alert có thể hết hạn sớm/muộn sai, `ORDER BY occurred_at` của incident_events (timeline dashboard) không đảm bảo thứ tự. Fix M1 **không phủ** phần server này → S-08.
- **RecoveryScanner gồm FAILED** (đúng spec persistence) nhưng server không xử lý ngân sách retry khi recover FAILED → S-10 (spec tension, cần chốt liên module).
- Test M1 (`PeerReviewFixesTest`, RepositoriesTest sửa) thuộc phạm vi review-b; review-c chỉ ghi nhận tương tác.

---

## 5. Issues theo severity (file:dòng — bằng chứng — khuyến nghị)

### HIGH (1)

**S-01 · Duplicate-ACK detection vỡ với persistence đã fix M1 (integration regression, tất định).**
`MessageRouter.java:224‑232` bắt `SQLException` chứa "UNIQUE" để đặt `duplicate=true`; `DeliveryRepo.recordAck` (bản M1 đã install 18:48) dùng `ON CONFLICT DO NOTHING` ⇒ không ném ⇒ mọi ACK lặp trả `duplicate=false`, audit outcome `OK` thay `DUPLICATE`. Bằng chứng: surefire run 2/3 `publishFanoutAckAndIdempotentReplay:212` fail; probe S2 (`duplicate=false`, state vẫn ACKNOWLEDGED — idempotency state đúng). **Fix:** mở rộng `recordAck` trả cờ inserted (hoặc router kiểm tra `device_acks` trước khi ghi), giữ audit `DUPLICATE`; cập nhật test 2. Fix M1 đã commit (`df0241b`) ⇒ server bắt buộc phải adapt theo hợp đồng mới.

### MEDIUM (9)

**S-02 · Race thụt lùi trạng thái RECEIVED→SENT (ACK only advances bị phá ở chiều ghi của dispatcher).**
`FanoutEngine.java:238‑249`: frame lên socket trước, `recordAttempt`+`updateState(SENT)` commit sau, không guard; `DeliveryRepo.updateState` unconditional. ACK concurrent commit RECEIVED trước → SENT ghi đè. Bằng chứng: `FanoutEngineTest.ackReceivedStopsRetryCycle:197` fail 2/2 run với jar M1 (pass jar cũ — transaction ack dài hơn làm race gần tất định); probe S4 0/12 qua wire (interleaving-dependent). Hệ quả: DB sai state, SYNC/dashboard sai, recovery re-enqueue delivery đã ack. **Fix:** chuyển SENT có guard (`WHERE state='PENDING'` hoặc rank guard như recordAck); test poll SENT trước khi ack.

**S-03 · Test harness: slow-client race + cascade stale COMMAND_RESULT (acceptance không ổn định).**
`TlsLoopbackIntegrationTest.java:71` heartbeat(800,3)=2,4s < flood ~3s ⇒ suspect thắng overflow (3/3 run; system-out có `heartbeat suspect` cho slow device, không có `slow client disconnected`); fail tại :475 bỏ qua drain :478‑481 ⇒ test 10 nhặt reply cũ (:130‑142 không match correlationId) ⇒ timeout :495 (3/3). Policy server ĐÚNG (probe S9 với tolerance 60s: audit+metric đủ). **Fix:** tăng heartbeat tolerance của test 9 (≥30s) hoặc giảm flood; drain trong `finally`; `request()` match `correlationId`; thêm mapping `STATUS_SUBSCRIBE→STATUS_SNAPSHOT` vào `TestTlsClient.replyType` (INFO-1).

**S-04 · Lộ exception message nội bộ ra wire (NFR-017, SECURITY.md §3.9 yêu cầu bắt buộc).**
`MessageRouter.java:113` ("storage error: "+e.getMessage()), `:116` (RuntimeException message); `AlertService.java:166,214,263`; `IncidentService.java:58,82`. Chi tiết SQL/đường dẫn có thể tới client. **Fix:** map `INTERNAL_ERROR` + thông điệp chung + correlationId; chi tiết chỉ log server (đã LOG.warn sẵn).

**S-05 · Read-plane không phân quyền: STATUS_SUBSCRIBE thiếu role/scope; WS feed mở hoàn toàn.**
`MessageRouter.java:411‑414` chỉ `requireOperator` ⇒ EMPLOYEE nhận full snapshot 10 tầng (probe S1 PASS-gap; ma trận SECURITY.md §4.2: EMPLOYEE ✗, FM/SECOPS theo scope). `StatusFeedServer.java:65‑108` không auth, không cap subscriber (probe S7 connect chay lấy snapshot). **Fix:** kiểm role/scope cho STATUS_SUBSCRIBE (từ chối EMPLOYEE, lọc theo building/floor grant); WS: tài liệu hóa trusted-LAN read-plane hoặc thêm token/scope + giới hạn subscriber.

**S-06 · ACK_USER nhận `userId` tùy ý từ payload, không xác minh (accountability spoofing).**
`MessageRouter.java:222` → ghi thẳng `device_acks.user_id`. Probe S2: device gán userId=admin, DB lưu UUID admin thật. **Fix:** xác minh user tồn tại/enabled (và tùy chọn: cùng floor/thiết bị), hoặc chỉ nhận diện operator đã auth; từ chối userId lạ. (Test 2 dòng 203 hiện dùng "user-1" tùy ý — chuẩn hóa luôn.)

**S-07 · DoS pre-auth: không soTimeout, không cap kết nối, sweep bỏ qua session chưa auth.**
`TlsGateway.java:90‑97` (accept không setSoTimeout, không giới hạn), `SessionRegistry.java:65` (`!isAuthenticated() → continue`). Probe S8: session HELLO-only sống >4s (tolerance 2,4s), giữ vô hạn bằng HEARTBEAT_ACK pre-auth (MessageRouter.java:84). SECURITY.md §3.5/§3.10 đã ghi 🔧 nhưng vector thật trên wire. **Fix:** soTimeout read, TTL pre-auth (vd 10s không auth → đóng), max connections + quota.

**S-08 · Timestamp variable-width phía server: so sánh expiry TEXT sai + ordering incident_events/metrics không đảm bảo.**
`Json.java:14` (`ISO_INSTANT`), `ServerStore.java:296‑297,315,335` (`expires_at > ?`/`<= ?` với `now=Json.ts`), ghi incidents/events/metrics/seed bằng Json.ts. Demo: `…00.4Z > …00.45Z` lexicographic dù ngược thời gian ⇒ expire sớm/muộn, activeAlerts sai; fix `Sql.TS` của M1 không phủ server. **Fix:** formatter fixed-width (appendInstant(9) hoặc epoch millis) dùng chung; so sánh expiry bằng epoch/numeric.

**S-09 · ALERT_UPDATE / ALERT_CANCEL / incident writes không atomic (crash-consistency).**
`AlertService.java:187‑207`: addRevision → setCurrentRevision → addTarget/create deliveries → supersede → insertOperation là nhiều transaction rời; crash giữa chừng ⇒ currentRevision=2 không có delivery rev2 + rev1 bị supersede ⇒ alert "tàng hình" với device (activeAlertsForDevice bỏ qua khi thiếu delivery), không có đường sửa. Cancel (236‑246) và IncidentService tương tự. Publish đã atomic (TransactionalPublisher) — update/cancel chưa. **Fix:** gói BEGIN IMMEDIATE một Connection (insertOn pattern của persistence).

**S-10 · Recovery/resume vượt ngân sách retry + đếm failed hai lần sau restart.**
`FanoutEngine.java:230‑233` (`settled` không gồm FAILED), `:335‑348` (resumeDevice push cả FAILED còn tracked), `:311‑325` (failedCounted in-memory). Probe S5: attemptCount=6 sau restart (5=offline recovery, 6=push khi reconnect trong cửa sổ); PROTOCOL "max 3 retries then FAILED". Counter failed/zone đếm lại từ 0 sau restart (StatusFeedService counters in-memory) → dashboard thấy giảm. **Fix:** recover FAILED không sinh attempt mới (re-mark FAILED hoặc chỉ phục vụ SYNC), hoặc ngân sách recovery tường minh; persist/derive counters + failedCounted từ DB.

### LOW (12)

**S-11 · ACK_CANCEL trên alert ACTIVE được chấp nhận (self-suppression).** `MessageRouter.java:97,203‑245` không kiểm alert state/CANCEL_PUSH; probe S2: delivery→CANCELLED, alert vẫn ACTIVE. Chỉ ảnh hưởng chính device đó. **Fix:** yêu cầu alert CANCELLED/EXPIRED (hoặc đã có CANCEL_PUSH) trước khi nhận ACK_CANCEL.

**S-12 · requestId idempotency không phân biệt operation kind/payload (thiếu CONFLICT).** `AlertService.java:174‑178,222‑226` + `ServerStore.findOperationByRequestId`: UPDATE dùng lại requestId của PUBLISH → trả alert gốc (probe S10), dù payload_hash khác. SECURITY.md §3.4 🟡. **Fix:** so payload_hash + kind → CONFLICT.

**S-13 · Ngữ nghĩa RetryPolicy đảo giữa core và caller (hazard off-by-one tương lai) + clamp maxRetries=0.** `AegisServer.java:87` truyền maxRetries vào tham số `maxAttempts`; `FanoutEngine.java:274,299` nạp attempt−1 — hành vi hiện ĐÚNG (§3) nhưng ngược javadoc core; `Math.max(1,…)` khiến maxRetries=0 vẫn retry 1. **Fix:** `RetryPolicy(maxRetries+1, base)` + truyền attemptCount theo javadoc, hoặc đổi tên tham số core; bỏ clamp hoặc tài liệu hóa.

**S-14 · WS RFC6455 hygiene.** `WsConnection.java:97‑114` chấp nhận client frame không mask (RFC MUST close), không chặn control frame >125B (`sendPong` :62 sẽ ghi length byte sai), ignore continuation/binary (:123); `StatusFeedServer.java:69‑80` BufferedReader đọc headers có read-ahead ⇒ nuốt frame client pipeline ngay sau handshake (mất resync). **Fix:** enforce mask + control length; đọc header byte-wise không buffer; xử lý fragmentation hoặc close đúng.

**S-15 · INCIDENT_EVENT broadcast không scope + lệch vocabulary contract + không lên WS.** `IncidentService.java:86‑99` gửi mọi OPERATOR session (kể EMPLOYEE, khác building); eventKind "OPENED"/"TRANSITION_*" ≠ contract `INCIDENT_OPENED`/`INCIDENT_TRANSITION`; WS bridge không phát INCIDENT_EVENT (timeline live trống, dashboard mock-first đỡ). **Fix:** lọc scope, đúng vocabulary, cân nhắc bridge WS.

**S-16 · AckLedger mở theo alert (không theo revision) + ACK_USER metric không dedup.** `FanoutEngine.java:142‑152` (alreadyOpen bỏ qua expected set của revision mới) ⇒ ALERT_FULLY_ACKED có thể fire sớm khi rev2 chưa ack; `:367‑368` incAckUser mỗi lần (kể duplicate). **Fix:** ledger per (alert,revision); dedup USER.

**S-17 · Zone `received` đếm mỗi SENT attempt (retry inflate) + counters reset khi restart.** `StatusFeedService.java:85‑88` + `FanoutEngine.java:250`; contract funnel clamp phía dashboard; monotonic chỉ trong lifetime. **Fix:** đếm first-dispatch per delivery; persist counters hoặc derive từ DB.

**S-18 · Hình dạng reply từ chối không nhất quán.** ACK denial → ERROR{FORBIDDEN/NOT_FOUND} (MessageRouter.java:211,219) trong khi publish/cancel denial → COMMAND_RESULT{ok:false} (:426‑434); DEVICE publish → ERROR INTERNAL "operator session required" (SecurityException :199 rơi vào catch RuntimeException :114). **Fix:** thống nhất COMMAND_RESULT cho command-level denial; map SecurityException→FORBIDDEN/ACCESS_DENIED.

**S-19 · Chưa absolute/idle TTL operator; re-auth giữ nguyên sessionId.** `ClientSession` (chỉ heartbeat/suspect); `MessageRouter.java:133‑153` bind lại không renew sessionId (chống fixation, SECURITY.md S4/S5 🔧). **Fix:** TTL tuyệt đối + renew khi đổi quyền.

**S-20 · Argon2 demo dưới ngưỡng OWASP + String credential không wipe được + timing enumeration.** `ServerConfig` m=16384<19456 (đã 🟡); `Argon2idHasher.java:28,38` nhận String (bản sao bất tử trong heap/Jackson); `AuthService.java:30‑32` user lạ không chạy Argon2 ⇒ trả lời nhanh hơn (phân biệt user tồn tại qua timing). **Fix:** nâng memoryKib mặc định; API char[]; dummy verify khi user vắng.

**S-21 · ServerMain in banner credential demo ra stdout; --storepass lộ qua process list.** `ServerMain.java:78‑80,44` (giá trị công khai lab-only, T-28 scan sẽ flag). **Fix:** gate banner sau cờ riêng hoặc tài liệu hóa; storepass qua env/file.

**S-22 · Metric chết + HELLO quảng cáo sai host.** `MetricsRegistry.java:60` `incSlowClientDrops` không ai gọi; `MessageRouter.java:129` `statusFeed="ws://localhost:"+wsPort` (sai host LAN, port 0 trong test). **Fix:** nối evictedCount vào drops hoặc bỏ; advertise host cấu hình.

### INFO (6)

1. `TestTlsClient.replyType` (TestTlsClient.java:103‑111) thiếu mapping STATUS_SUBSCRIBE→STATUS_SNAPSHOT (probe phải send+await tay) — harness pitfall, không phải server bug.
2. `Json.MAPPER` (Json.java:13) dùng StreamReadConstraints mặc định Jackson (depth 1000) — đã ghi 🟡 SECURITY.md §3.5; frame cap 64KiB giới hạn gián tiếp.
3. `ServerStore.deviceInfo` LEFT JOIN zone_devices lấy row đầu nếu device đa zone (demo 1 zone/device — vô hại).
4. `StatusFeedService.broadcastDelta` so `lastBroadcast` (batch trước) với snapshot vừa gửi subscriber mới — race hiếm khi zone đổi rồi đổi lại giữa 2 batch ⇒ subscriber giữ giá trị stale tới lần đổi kế tiếp (counters monotonic nên chỉ online/alerts bị ảnh hưởng).
5. WS không soTimeout → connection idle linger (virtual thread rẻ, không cap — xem S-05/S-07).
6. Acceptance `-pl aegis-server` không `-am` → phụ thuộc jar `~/.m2` install gần nhất (đã đổi giữa review lúc 18:48); nên chuẩn hóa quy trình install/`-am` để kết quả tái lập (R0).

---

## 6. Khuyến nghị ưu tiên

| # | Việc | Liên quan |
|---|---|---|
| R0 | Chuẩn hóa acceptance: `mvn -q -pl aegis-server -am test` (hoặc install persistence có chủ đích trước khi chạy); chốt thứ tự commit M1-fix ↔ server-adapt | S-01, INFO-6 |
| R1 | Sửa hợp đồng duplicate-ACK với review-b (recordAck trả cờ inserted), cập nhật router + test 2 + audit DUPLICATE | S-01 |
| R2 | Guard chuyển SENT (rank/state) trong sendPush; test poll SENT trước ack | S-02 |
| R3 | Sửa harness test 9/10: heartbeat tolerance lớn, drain trong finally, correlationId matching | S-03 |
| R4 | Sanitize INTERNAL_ERROR tại router (chi tiết chỉ log); thống nhất reply denial | S-04, S-18 |
| R5 | Phân quyền read-plane: STATUS_SUBSCRIBE role/scope + WS auth/cap (hoặc tài liệu trusted-LAN) | S-05 |
| R6 | Xác minh userId ACK_USER; chặn ACK_CANCEL khi alert ACTIVE | S-06, S-11 |
| R7 | soTimeout + pre-auth TTL + connection cap | S-07 |
| R8 | Fixed-width timestamp toàn server (Json.ts) + so sánh expiry numeric | S-08 |
| R9 | Gói update/cancel/incident trong BEGIN IMMEDIATE | S-09 |
| R10 | Chốt ngữ nghĩa recovery FAILED (không attempt mới) + counters bền vững; thống nhất RetryPolicy(maxRetries+1) với core | S-10, S-13, S-17 |

**Điểm mạnh cần giữ:** kiến trúc outbox-after-commit + dedup gate chỉ FIRST dispatch + generation guard; router guard ACK độc lập tầng persistence; BoundedWriterQueue priority đúng chiều + slow-client policy có audit/metric; ZoneStatus khớp normative từng zone; audit không secret; TLS identity chuẩn PKCS12; recovery đỏ-giao-diện đúng cho delivery scheduler-owned.

---

## 7. Phụ lục — bằng chứng & artifacts (scratch, không commit)

Scratch: `/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-c/`
- `mvn-test.log`, `mvn-test-run2.log`, `mvn-test-run3.log` — output 3 lần chạy acceptance.
- `probe/aegis/server/ReviewProbe.java` — harness đối kháng 33 check (package aegis.server, reuse TestTlsClient/TestWsClient/TestSupport từ test-classes).
- `probe-stdout.log` / `probe-stdout2.log`, `probe-run/probe-results.txt` — kết quả probe (lần cuối: 33 PASS / 0 FAIL; các check GAP PASS = xác nhận gap).
- `installed-persistence/`, `installed-persistence2/` — giải nén jar ~/.m2 trước/sau 18:48 (grep constant pool chứng minh fix M1 xuất hiện giữa review).
- `cp.txt`, `probe-classes/`, `probe-run/` — classpath + DB scratch của probe.

Surefire: `aegis-server/target/surefire-reports/TEST-*.xml` (run 3: 52 tests / 4 failures / 0 errors / 0 skipped).

*Tài liệu này do reviewer độc lập tạo; không sửa source module, không git commit.*
