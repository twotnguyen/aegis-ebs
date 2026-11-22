# REVIEW ĐỘC LẬP H1–H4 — BACKEND-DEPTH-AUDIT (AEGIS-EBS)

| | |
|---|---|
| **Ngày review** | 2026-11-22 (UTC+07) |
| **Vai trò** | Reviewer độc lập — KHÔNG phải tác giả bản fix; **read-only** trên source/test, không sửa code, không `git commit` |
| **HEAD làm việc** | `17ea534` (working tree chứa 4 file source + 2 file test đã sửa/thêm mới, cộng evidence XML; xem §2) |
| **Đối tượng** | 4 finding HIGH trong `docs/reviews/backend-depth-audit.md`: H1 (`scheduleRetry`), H2 (`markFailed`), H3 (re-auth), H4 (WS bounded async writer) |
| **Đối chiếu** | `docs/reviews/backend-depth-audit-fixes.md` (self-report của tác giả fix) — kiểm chứng từng claim |
| **Phương pháp** | (i) đọc diff `git diff` + toàn bộ source có đánh số dòng; (ii) **chạy lại** `mvn -B -ntp verify` toàn reactor; (iii) đối chiếu số liệu surefire XML/evidence; (iv) suy luận interleaving cho các race còn lại; (v) kiểm tra luồng client/simulator có phát re-auth hợp lệ hay không |
| **VERDICT** | **PASS** — bốn finding HIGH đã được khắc phục đúng cơ chế, có regression test thật, full-reactor verify xanh. Các vấn đề ghi nhận ở §6 đều là **tồn dư có từ trước / ngoài phạm vi H1–H4**, không làm suy giảm bất biến đã sửa |

---

## 1. Ràng buộc và phạm vi thực hiện

Reviewer tuân thủ nghiêm ngặt quyền hạn: chỉ đọc mã nguồn; chạy `mvn test`/`mvn verify` và các lệnh probe read-only; **chỉ ghi file `docs/reviews/backend-h1h4-review.md` này**. Không chỉnh sửa bất kỳ file source, test hay tài liệu khác; không thực hiện `git add`/`git commit`. Các artifact `target/surefire-reports` được tạo ra như hệ quả tất yếu của việc chạy verify, không phải sửa file quản lý.

---

## 2. Kiểm chứng thực nghiệm (lệnh đã chạy / kết quả thật)

### 2.1. Trạng thái working tree trước khi review

```
git rev-parse --short HEAD   -> 17ea534
git status --short           -> M: DeliveryRepo.java, FanoutEngine.java, MessageRouter.java,
                                WsConnection.java, ServerSecurityFixesTest.java,
                                docs/BAO-CAO.md, 55 evidence XML, verify-summary.json
                              ?? DeliveryRankGuardTest.java, DeliveryRankGuardTest.xml,
                                docs/reviews/backend-depth-audit-fixes.md
```

### 2.2. Lệnh chạy và kết quả

| Lệnh | Kết quả thật |
|---|---|
| `git diff -- <4 source> <1 test>` + `git diff --no-index /dev/null DeliveryRankGuardTest.java` | Đọc đầy đủ 5 hunk fix + 127 dòng test mới (7 test) |
| `mvn -B -ntp verify` (full reactor) | **BUILD SUCCESS**, `EXIT=0`, tổng thời gian 01:27 min, Finished 2026-11-22T18:30:37+07:00 |
| `grep "Tests run:" /tmp/h1h4-verify.log` | **347 test Java, 0 failure / 0 error / 0 skipped** |
| `java -version` / `mvn -version` | shell `java` = 24.0.1 (HotSpot); **Maven chạy JVM Homebrew 27.0.1.1**, `--release 24` (xem §5) |

### 2.3. Phân bố test (đối chiếu trực tiếp với log, không tin self-report)

| Module | Số test (log verify của reviewer) | Self-report | Khớp? |
|---|---:|---:|:---:|
| aegis-protocol | 56 | 56 | ✓ |
| aegis-core | 73 | 73 | ✓ |
| aegis-persistence | 59 | 59 | ✓ |
| aegis-server | 65 | 65 | ✓ |
| aegis-client | 43 | 43 | ✓ |
| aegis-simulator | 20 | 20 | ✓ |
| aegis-network-lab | 31 | 31 | ✓ |
| **Tổng** | **347** | **347** | ✓ |

Chi tiết đáng chú ý trong log: `DeliveryRankGuardTest` **7/7**, `ServerSecurityFixesTest` **12/12** (10 gốc + 2 mới @Order 11/12), `FanoutEngineTest` **8/8**, `TlsLoopbackIntegrationTest` **10/10**. Như vậy claim "tăng 9 test so với 338" trong self-report là **đúng** (7 + 2).

---

## 3. Đánh giá theo finding

### 3.1. H1 — `scheduleRetry` guard (ACK-only-advances) — **ĐÃ SỬA ĐÚNG**

- **Vị trí fix:** `aegis-persistence/.../DeliveryRepo.java:112–122`. Câu lệnh giờ là
  `UPDATE deliveries SET state='RETRY_SCHEDULED', next_retry_at=?, attempt_count=attempt_count+1 WHERE id=? AND state IN ('PENDING','SENT','RETRY_SCHEDULED')` (`:117`). Trả `boolean`: `false` khi đã ACK/terminal.
- **Đánh giá:** Tập guard chính xác là tập *dispatch-owned* theo ma trận race §6.1 của audit gốc. Khi `recordAck` commit `RECEIVED` (rank 2) hoặc `ACKNOWLEDGED` (rank 3) giữa lúc `dispatch` đọc session null và lúc offline-retry ghi, mệnh đề `WHERE` loại row → **không regress trạng thái, không inflate `attempt_count`**. Đây là bất biến cốt lõi của H1.
- **Test đối kháng:** `DeliveryRankGuardTest.scheduleRetryCannotRegressReceived` / `...CannotRegressAcknowledged` thiết lập ACK thật qua `recordAck` rồi gọi `scheduleRetry` muộn; assert `assertFalse(...)` + giữ nguyên state/count. Phản xạ đúng cơ chế race, không phải test giả (`scheduleRetryAdvancesPendingAndIncrementsAttempt` chứng minh đường *hợp lệ* vẫn tiến).
- **Kết luận:** PASS. (Lưu ý tồn dư ở §6.3 về attempt row bị insert trước khi guard chạy — không phá bất biến trạng thái.)

### 3.2. H2 — `markFailed` guarded + metric/audit có điều kiện — **ĐÃ SỬA ĐÚNG**

- **Vị trí fix:** `DeliveryRepo.java:134–142` (method mới) và `FanoutEngine.java:328–350`.
  - `markFailed(id)`: `UPDATE ... SET state='FAILED', next_retry_at=NULL WHERE id=? AND state IN ('PENDING','SENT','RETRY_SCHEDULED')` (`:137–138`), **cố ý không viết `attempt_count`** → ngân sách bền vững không thể co lại. Đây là điểm khác biệt quan trọng so với lời khuyên gốc "dùng `updateDispatchState`": method mới bảo toàn count thay vì ghi giá trị đọc cũ `d.attemptCount()`.
  - `FanoutEngine.markFailed`: `failed = deliveries.markFailed(d.id())`; nếu `!failed` → `untrack()` và **return trước** `metrics.incFailed()`, `status.deliveryFailed()`, `audit.append("DELIVERY_FAILED")` (`:336–342`). Như vậy ACK thắng race không bị tính failed giả.
- **CANCELLED vẫn override:** `updateState` tổng quát (`DeliveryRepo.java:90–101`) được **giữ nguyên không guard** cho các đường tombstone (`FanoutEngine.java:125, 193, 199, 218`; `ServerStore.cancelDeliveryOn`), đúng yêu cầu "CANCELLED tombstone vẫn override ACK".
- **Test:** 4 ca trong `DeliveryRankGuardTest` (`markFailedFailsPendingDelivery`, `CannotRegressReceived`, `CannotRegressAcknowledged`, `CannotOverrideCancelled`) + `FanoutEngineTest` 8 ca (gồm `3 retries -> FAILED`) + `TlsLoopbackIntegrationTest` 10 ca vẫn xanh.
- **Kết luận:** PASS.

### 3.3. H3 — Từ chối re-auth trên session đã xác thực — **ĐÃ SỬA ĐÚNG, KHÔNG PHÁ CLIENT HỢP LỆ**

- **Vị trí fix:** `MessageRouter.java:143–153` (`authOperator`) và `:175–185` (`authDevice`). Cả hai kiểm `session.isAuthenticated()` **trước** khi đọc payload/gọi `AuthService`, ghi audit `AUTH_{OPERATOR,DEVICE}_REJECTED` (outcome `DENIED`) rồi trả `AUTH_RESULT{ok:false, code:"ALREADY_AUTHENTICATED"}`.
- **Đóng đúng lỗ hổng:** Nhờ guard đứng trước `bindDevice`/`bindOperator`, không còn đường ghi đè `deviceId` hay tạo session lai. `SessionRegistry.deviceOnline` (`:34–40`) vẫn thay session cũ đúng cách; `unregister` (`:26–31`) vẫn xóa theo `(deviceId, session)`. Hệ quả H3 gốc (entry `byDeviceId[A]` rò rỉ → push A sang socket B, hoặc session OPERATOR mang `deviceId` → delivery A không ACK được) không còn với tới: muốn đổi danh tính phải `LOGOUT` (đã có lệnh tại `MessageRouter.java:90–93`) + kết nối mới.
- **Không phá client hợp lệ:**
  - `MessageRouter.route:84–93` vẫn cho HELLO/AUTH_*/HEARTBEAT/HEARTBEAT_ACK/LOGOUT đi qua trước auth; guard chỉ chặn lần auth **thứ hai trở đi**. Auth lần đầu (`kind == UNAUTHENTICATED`) hoàn toàn không bị ảnh hưởng.
  - `ClientSession.isAuthenticated()` = `kind != UNAUTHENTICATED` (`ClientSession.java:130`), được set đúng bởi `bindOperator`/`bindDevice` (`:96–111`).
  - Rà soát toàn repo: `aegis-client/AlertClientEngine.handshake:246–273` và `aegis-simulator/SimAgent.java:140` gửi `AUTH_DEVICE` **đúng một lần trên mỗi socket mới**; reconnect tạo socket mới → `kind` lại là `UNAUTHENTICATED`. Không có luồng nào gửi AUTH lại trên session sống. Client nhận `ok=false` đã có đường xử lý (`AuthRejectedException`, `:267–272`).
  - Test @Order 11 (`ServerSecurityFixesTest.java:530–550`): device A auth thành công → thử AUTH_DEVICE B và AUTH_OPERATOR → cả hai `ALREADY_AUTHENTICATED` → `HEARTBEAT` vẫn trả `HEARTBEAT_ACK` (chứng minh session giữ identity gốc, không bị logout).
- **Kết luận:** PASS.

### 3.4. H4 — WS bounded async writer — **ĐÃ SỬA ĐÚNG**

- **Vị trí fix:** `WsConnection.java:26, 32–40, 48–68, 71–95, 173–182`.
  - `LinkedBlockingQueue<String> outQueue` capacity **256** (`:26, :32`); `Thread.ofVirtual().name("ws-writer")` khởi tạo trong constructor (`:40`).
  - `sendText` (`:48–56`): `if (closed) return false`; `outQueue.offer(text)` **không chặn**; đầy → `close()` + `return false`. Không còn `out.write` trên thread gọi (đây là điểm loại bỏ HOL blocking của `broadcastDelta`).
  - `writeLoop` (`:59–68`) là nơi duy nhất gọi `writeFrame` (blocking) — tách hẳn I/O chậm khỏi timer pool; `InterruptedException` → exit.
  - `close()` (`:173–182`): đặt `closed=true`, `writer.interrupt()` (đánh thức `take()`), `socket.close()` (giải phóng writer đang kẹt ở `out.write`). `writeFrame` IOException cũng gọi `close()` (`:91–94`) nên writer luôn thoát.
- **Tích hợp đúng với feed:** `StatusFeedService.broadcastDelta:235–238` — `if (conn.sendText(text)) sent++; else unsubscribe(conn);` → subscriber bị drop được gỡ khỏi `CopyOnWriteArrayList`; các subscriber khác tiếp tục được duyệt. `StatusFeedServer.handle:119–126` dùng `WsConnection` cho cả snapshot lẫn `readLoop`; `finally:129–136` luôn `unsubscribe + close`.
- **Framing RFC6455 giữ nguyên:** `writeFrame` (`:71–95`) vẫn mã hóa 0x81, độ dài 7/16/64-bit, `writeLock`, ping/pong/close như cũ — không thay đổi hợp đồng dây.
- **Test @Order 12** (`ServerSecurityFixesTest.java:553–575`): socket pair peer không đọc, frame 60 KB, lặp tới 4000 → assert `dropped`, `isClosed()`, `elapsedMs < 5000`. Chạy thực tế 9.1 s cho cả class, **12/12 pass**.
- **Kết luận:** PASS.

---

## 4. Kiểm tra chéo các bất biến theo yêu cầu

| # | Bất biến cần kiểm | Kết quả | Bằng chứng |
|---|---|---|---|
| 4.1 | **Rank semantics ACK-only-advances** | Đạt | `recordAck` SQL CASE-rank `DeliveryRepo.java:227–240` (PENDING0<SENT/RETRY1<RECEIVED2<ACK/FAILED3<EXPIRED/CANCELLED4) kèm ownership; `scheduleRetry :117` và `markFailed :138` guard tập dispatch-owned; `updateDispatchState :268–269` guard tương tự. Mọi đường ghi từ dispatcher/timer giờ đều không lùi ACK. |
| 4.2 | **CANCELLED tombstone vẫn override ACK** | Đạt | `rank(CANCELLED)=4` (`DeliveryRepo.java:284`) là rank cao nhất → `recordAck` (`WHERE CASE state... < 4`) không đè tombstone; các đường cancel dùng `updateState` **unguarded** (`FanoutEngine.java:125, 193, 199, 218`) → CANCELLED luôn thắng. Test `markFailedCannotOverrideCancelled` xanh. |
| 4.3 | **Recovery / retry budget** | Đạt | `settled()` gồm `FAILED` (`FanoutEngine.java:230–236`) → recovered FAILED untrack không tốn attempt; `markFailed` giữ nguyên `attempt_count` → không coi ngân sách; `scheduleRetry` tăng nguyên tử trong SQL. `FanoutEngineTest.recoveredFailedDeliveryGetsNoNewAttempts` + `RecoveryScannerTest` (4 ca) xanh. |
| 4.4 | **Re-auth không phá client hợp lệ** | Đạt | Guard chỉ chặn lần auth thứ 2+; client/simulator auth 1 lần/socket (§3.3); test @Order 11 xác nhận HEARTBEAT_ACK sau khi reject. |
| 4.5 | **WS snapshot ordering / close / no thread leak** | Đạt (kèm tồn dư §6.1) | Snapshot và mọi delta đi chung một `outQueue` FIFO → **thứ tự giữa các frame đã enqueue được bảo toàn**; `readLoop` `finally:169` và `StatusFeedServer.handle finally:129–136` đều gọi `close()`; `close()` interrupt + đóng socket → writer (virtual thread) luôn thoát `writeLoop`, không rò rỉ. Caveat: cửa sổ add-subscriber trước khi offer snapshot (§6.1) là lỗi có từ trước, không do fix H4 tạo ra. |
| 4.6 | **Slow subscriber drop không ảnh hưởng subscriber lành** | Đạt | `sendText` non-blocking; overflow chỉ `close()` đúng conn đó; `broadcastDelta` gỡ đúng conn đó khỏi `subscribers`. Code inspection xác nhận timer pool không bị chặn. Test @Order 12 chứng minh drop xảy ra mà sender không block; **chưa có assert trực tiếp** "subscriber khác vẫn nhận delta" (§6.4). |

---

## 5. Kiểm chứng self-report `backend-depth-audit-fixes.md`

| Claim trong self-report | Đánh giá |
|---|---|
| "Full reactor `mvn -B -ntp verify`: BUILD SUCCESS, 347 test Java, 0 failure/0 error" | **Đúng** — reviewer tự chạy cùng lệnh, cùng số liệu (§2.2–2.3) |
| Phân bố 56/73/59/65/43/20/31 | **Đúng** từ log surefire |
| "+9 test so với 338 (7 `DeliveryRankGuardTest` + 2 `ServerSecurityFixesTest`)" | **Đúng** — đếm 7 test mới + class server giờ 12 (từ 10) |
| H1: guard `AND state IN (...)` vào `scheduleRetry`, trả false khi đã ACK | **Đúng** (`DeliveryRepo.java:117, 120`) |
| H2: `markFailed` guarded, **chỉ đếm metric/audit khi guard pass**, giữ `updateState` cho CANCELLED | **Đúng** (`FanoutEngine.java:336–348`) |
| H3: cả `authOperator`/`authDevice` trả `AUTH_RESULT{ok:false, ALREADY_AUTHENTICATED}` + audit `AUTH_*_REJECTED` | **Đúng** (`MessageRouter.java:144–152, 176–184`) |
| H4: `LinkedBlockingQueue(256)` + virtual writer, `sendText` non-blocking, đầy → close, `close()` interrupt writer + đóng socket, framing giữ nguyên | **Đúng** (`WsConnection.java:26, 32–68, 173–182`) |
| "Bằng chứng: ... runId `verify-347-h1h4`, máy Apple M3 Pro/**Java 24.0.1**" | **Chính xác một phần** — runId/máy đúng; nhưng JVM thực chạy Maven là **Homebrew 27.0.1.1** (`mvn -version`), `24.0.1` là `java` mặc định của shell (xem `verify-summary.json:12`). Không làm sai số liệu test, nhưng là **sai lệch nhỏ về provenance môi trường** (§6.5). |
| "Còn lại (MEDIUM/LOW...) ghi nhận trung thực" | **Đúng** — các finding M1–M13/L1–L18 vẫn mở; review này không đòi sửa chúng trong đợt H1–H4 |

→ **Không có claim sai trọng yếu** trong self-report; chỉ một điểm imprecise về phiên bản JVM (LOW).

---

## 6. Vấn đề tồn dư (severity / `file:dòng`)

> Tất cả đều là **pre-existing hoặc hệ quả thiết kế đã có từ trước đợt fix H1–H4**. Không một mục nào làm suy giảm bất biến đã sửa; vì vậy không kéo verdict xuống FAIL. Được liệt kê để orchestrator đưa vào backlog đúng hạn (roadmap M/L của audit gốc).

| ID | Severity | Vị trí | Mô tả & khuyến nghị |
|---|---|---|---|
| R1 | **LOW** | `aegis-server/.../StatusFeedService.java:246–247` (đối chiếu `:235–238`) | `subscribe()` thêm conn vào `subscribers` **trước** khi offer snapshot. Một `broadcastDelta()` chạy đúng cửa sổ đó có thể enqueue delta vào queue của conn này trước snapshot → client nhận delta trước snapshot. Lỗi có từ trước fix H4 (khi ấy `sendText` blocking vẫn mở cùng cửa sổ), **không phải regression**. Sửa: offer snapshot trước rồi mới `subscribers.add(conn)`, hoặc khóa subscribe/broadcast bằng cùng monitor. |
| R2 | **LOW** | `aegis-server/.../FanoutEngine.java:281–282` | `offlineAttempt` gọi `recordAttempt(OFFLINE)` **trước** `scheduleRetry` đã guard và **bỏ qua giá trị trả về**. Nếu ACK thắng race, guard chặn state/count nhưng vẫn tồn tại một attempt row "OFFLINE" rời rạc + một timer `scheduleCheck` vô ích (timer sẽ reload thấy `settled()` rồi untrack). Không phá ACK-only-advances; là nhiễu audit (liên quan L8 cũ). Sửa: kiểm tra boolean `scheduleRetry`, skip `scheduleCheck`/gộp attempt+state vào một transaction có guard. |
| R3 | **LOW** | `aegis-server/.../WsConnection.java:173–175` | `close()` kiểm `if (closed) return` rồi mới gán — check-then-act không nguyên tử (cũng là L9 cũ). Hai caller đồng thời (writer IOException + overflow) có thể cùng interrupt/close socket; hậu quả lành tính (idempotent) nhưng nên dùng `AtomicBoolean.compareAndSet` như `ClientSession.close:118`. |
| R4 | **LOW** | `aegis-server/src/test/.../ServerSecurityFixesTest.java:553–575` | Test H4 chỉ có **một** subscriber chậm, chưa assert trực tiếp rằng một subscriber lành co-subscribed vẫn nhận delta và timer pool vẫn chạy. Hành vi đúng được suy từ code (`sendText` non-blocking + unsubscribe cục bộ), nhưng nên thêm test 2-subscriber để chốt claim "không ảnh hưởng subscriber lành". |
| R5 | **LOW** | `aegis-server/src/test/.../ServerSecurityFixesTest.java:530–550` | Test H3 assert HEARTBEAT_ACK sau reject nhưng chưa assert `deviceSession(A)` vẫn trả session gốc / push A vẫn đến A. Guard đứng trước `bind*` nên code đúng; nên bổ sung assert registry để khóa chặt hệ quả cross-device. |
| R6 | **LOW** | `docs/reviews/backend-depth-audit-fixes.md:7`; `docs/evidence/verify/verify-summary.json:12` | Ghi "Java 24.0.1" trong khi Maven thực chạy JVM Homebrew **27.0.1.1** (`release=24`). Nên ghi rõ "JVM 27.0.1.1, target release 24" để tránh nhầm khi tái lập. |

---

## 7. Kết luận

**VERDICT: PASS.**

Bốn finding HIGH của `backend-depth-audit.md` đã được khắc phục đúng cơ chế và có regression test đối kháng thật:

- **H1/H2** — mọi đường ghi của dispatcher/timer (`scheduleRetry`, `markFailed`, `updateDispatchState`) giờ đều bị chặn bởi tập trạng thái `{PENDING,SENT,RETRY_SCHEDULED}`; ACK chỉ tiến, `attempt_count` không inflate/thu hẹp; metric/audit `DELIVERY_FAILED` chỉ tăng khi guard thực sự pass; tombstone CANCELLED vẫn dùng `updateState` không guard nên vẫn override ACK.
- **H3** — re-auth bị từ chối với `ALREADY_AUTHENTICATED` trước khi bind, đóng hoàn toàn đường rò rỉ `byDeviceId`; luồng auth hợp lệ (một lần mỗi socket) của client/simulator không bị ảnh hưởng.
- **H4** — `sendText` trở thành `offer` không chặn vào queue bounded 256 do virtual thread riêng tiêu thụ; subscriber chậm bị drop cục bộ, không còn head-of-line-block timer pool; snapshot/delta cùng queue nên thứ tự frame đã enqueue được bảo toàn, và `close()` giải phóng writer — không rò rỉ thread.

Bằng chứng thực thi: `mvn -B -ntp verify` → **BUILD SUCCESS, 347 Java test, 0 failure/0 error**, trong đó `DeliveryRankGuardTest 7/7`, `ServerSecurityFixesTest 12/12`, `FanoutEngineTest 8/8`, `TlsLoopbackIntegrationTest 10/10`. Self-report `backend-depth-audit-fixes.md` khớp số liệu; chỉ có một sai lệch nhỏ về provenance JVM (R6). Các tồn dư R1–R6 đều LOW, có từ trước hoặc ngoài phạm vi H1–H4 — orchestrator nên đưa vào backlog M/L thay vì chặn nghiệm thu đợt này.

**Khuyến nghị cho orchestrator:** có thể accept HEAD `17ea534` cho riêng phạm vi H1–H4; vẫn giữ nguyên gate "full-reactor verify xanh + reviewer PASS" cho lần commit tổng hợp, và không push các finding MEDIUM/LOW còn mở như thể đã xong.
