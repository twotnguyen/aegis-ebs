# KHẮC PHỤC BACKEND-DEPTH-AUDIT — H1…H4 (HIGH)

> Vòng lặp peer-review → fixer → kiểm chứng tiếp nối `docs/reviews/backend-depth-audit.md` (35 finding: 4 HIGH/13 MEDIUM/18 LOW). Tài liệu này ghi nhận việc khắc phục **4 finding HIGH** kèm bằng chứng test thật. Các MEDIUM/LOW còn lại được ghi nhận trung thực là hướng hardening tiếp theo.

## Kết quả tổng
- Full reactor `mvn -B -ntp verify`: **BUILD SUCCESS, 347 test Java, 0 failure/0 error** (protocol 56, core 73, persistence 59, server 65, client 43, simulator 20, network-lab 31) + 14 dashboard. Tăng 9 test so với 338 trước đó (7 `DeliveryRankGuardTest` + 2 `ServerSecurityFixesTest` H3/H4).
- Bằng chứng: `docs/evidence/verify/verify-summary.json` + surefire XML (runId `verify-347-h1h4`, máy Apple M3 Pro/Java 24.0.1).

## H1 — scheduleRetry không guard (ACK-only-advances)
- **Vấn đề:** `DeliveryRepo.scheduleRetry` UPDATE vô điều kiện `state='RETRY_SCHEDULED', attempt_count+1`; `FanoutEngine.offlineAttempt` gọi nó có thể regress một delivery đã RECEIVED/ACKNOWLEDGED về RETRY_SCHEDULED và inflate attempt_count.
- **Khắc phục:** thêm guard `AND state IN ('PENDING','SENT','RETRY_SCHEDULED')` vào `scheduleRetry` (`DeliveryRepo.java`); trả false (no-op) nếu đã ack/terminal.
- **Test:** `DeliveryRankGuardTest.scheduleRetryAdvancesPendingAndIncrementsAttempt`, `scheduleRetryCannotRegressReceived`, `scheduleRetryCannotRegressAcknowledged`.

## H2 — markFailed không guard (ACK-only-advances)
- **Vấn đề:** `FanoutEngine.markFailed` gọi `DeliveryRepo.updateState(FAILED,…)` vô điều kiện; ACK commit giữa `checkDelivery` đọc và UPDATE có thể bị FAILED đè (RECEIVED→FAILED), attempt_count lùi.
- **Khắc phục:** thêm method guarded `DeliveryRepo.markFailed(id)` (`SET state='FAILED', next_retry_at=NULL WHERE id=? AND state IN ('PENDING','SENT','RETRY_SCHEDULED')`, giữ nguyên attempt_count); `FanoutEngine.markFailed` dùng nó và **chỉ đếm metric/audit DELIVERY_FAILED khi guard pass** (ACK thắng race → untrack, không tính là failed). Giữ `updateState` tổng quát cho các đường CANCELLED (tombstone phải override ACK) và test setup.
- **Test:** `DeliveryRankGuardTest.markFailedFailsPendingDelivery`, `markFailedCannotRegressReceived`, `markFailedCannotRegressAcknowledged`, `markFailedCannotOverrideCancelled`; `FanoutEngineTest` (8) + `TlsLoopbackIntegrationTest` (10, gồm 3-retries→FAILED) vẫn xanh.

## H3 — re-auth trên session đã authenticated (cross-device leak)
- **Vấn đề:** `MessageRouter.authOperator/authDevice` không kiểm tra `isAuthenticated()`; re-bind device khác trên session sống để lại entry `byDeviceId` cũ → ALERT_PUSH của device cũ định tuyến sai session (dễ với shared demo credential).
- **Khắc phục:** chặn re-auth — cả `authOperator` và `authDevice` trả `AUTH_RESULT{ok:false, code:ALREADY_AUTHENTICATED}` + audit `AUTH_*_REJECTED` nếu session đã authenticated; buộc LOGOUT + reconnect để đổi identity.
- **Test:** `ServerSecurityFixesTest.reAuthenticationOnAuthenticatedSessionIsRejected` (@Order 11): re-auth device khác + re-auth operator đều bị từ chối, session giữ identity gốc (HEARTBEAT_ACK vẫn hoạt động).

## H4 — WS sendText blocking trên timer pool chung (slow subscriber stalls feed)
- **Vấn đề:** `StatusFeedService.broadcastDelta` gọi `WsConnection.sendText` (out.write+flush blocking) tuần tự trên timer pool 2 thread; một subscriber chậm/không auth không drain socket → block thread timer → kẹt toàn bộ status feed.
- **Khắc phục:** `WsConnection` dùng **bounded async writer** — hàng đợi `LinkedBlockingQueue`(256) + virtual thread writer riêng; `sendText` thành non-blocking `offer`, queue đầy → close subscriber (drop). Broadcast/timer không bao giờ block trên peer chậm; framing RFC6455 giữ nguyên; `close()` interrupt writer + đóng socket.
- **Test:** `ServerSecurityFixesTest.wsSlowSubscriberIsDroppedWithoutBlockingSender` (@Order 12): socket pair peer không đọc, gửi frame 60KB → subscriber bị drop khi backlog đầy, `sendText` non-blocking (<5s), `isClosed()` true. `TlsLoopbackIntegrationTest` WS contract (snapshot on subscribe) vẫn xanh với async writer.

## Còn lại (MEDIUM/LOW — hướng hardening, chưa sửa đợt này)
Ghi nhận trung thực theo audit: M1 runtime reconciliation delivery kẹt, M2 double-dispatch race, M3/M4 revision/incident state ngoài transaction, M6 pre-auth slowloris giữa frame + WS handshake timeout, M7 recovery OFFLINE attempt/jitter, M9 queue bounded theo frame chứ không byte, M10 zone counter/AckLedger không rebuild sau restart, M11 INCIDENT_EVENT scope filter, M13 acceptLoop transient IOException; và các LOW. Đây là các cải thiện độ bền sâu hơn, không phải lỗ hổng chặn nghiệm thu; sẽ ưu tiên theo roadmap trong audit.
