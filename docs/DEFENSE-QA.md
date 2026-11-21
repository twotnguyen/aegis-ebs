# CÂU HỎI BẢO VỆ ĐỒ ÁN — AEGIS-EBS (kèm trả lời grounded mã nguồn)

> 18+ câu hỏi phản biện thường gặp khi bảo vệ đồ án Lập trình mạng, trả lời dựa trên mã nguồn thật.

## A. Câu hỏi nền tảng (blueprint §16)

**Q1. TCP đã tin cậy, sao còn cần ACK ứng dụng?**
TCP ACK chỉ xác nhận byte tới tầng giao vận của máy nhận, không chứng minh ứng dụng đã xử lý/lưu bền vững, và không định danh được thiết bị nào đã nhận. `ACK_RECEIVED` chỉ gửi SAU khi client commit durable inbox (`AlertClientEngine.handleAlert`: inbox.store → ACK), chứng minh bản tin đã lưu; `ACK_USER` tách biệt xác nhận con người. Server cần biết *thiết bị nào* nhận để retry đúng đích.

**Q2. Sao không dùng MQTT/Firebase?**
Mục tiêu môn CMP180 là tự xây dựng giao thức + quản lý kết nối bằng Java Socket. Dùng broker có sẵn sẽ triệt tiêu phần bị chấm (framing, đa luồng, ACK/retry, recovery). MQTT QoS1 chỉ là tham khảo nguyên lý at-least-once; ta tự cài đặt trên `Socket`/`ServerSocket`/`SSLSocket`.

**Q3. Retry có đảm bảo exactly-once?**
Không. Mạng không thể exactly-once; ta làm **at-least-once có giới hạn** (retry 3s/6s/12s, max 3) + **idempotent processing**: `deliveryId` ổn định, `alertId/revision`, client dedup (`DedupIndex`) → UI effectively-once (không popup trùng). `requestId` UNIQUE chống tạo alert trùng.

**Q4. Sao multicast chỉ là hint?**
UDP multicast không tin cậy, không xác thực, không đảm bảo tới mọi receiver. Dùng làm kênh cảnh báo chính là rủi ro an toàn. `MulticastHintPublisher` gửi `ALERT_HINT{alertId,revision,serverId}` advisory; `VerifiedAlertSubscriber` chỉ hiển thị nội dung fetch qua TLS đã xác thực. Multicast vẫn được đo lường thật (giảm ~87.5% gói/~97.8% byte so TCP fanout).

**Q5. Server chết thì sao?**
Single-node, không HA (ghi rõ giới hạn). Nhưng **crash recovery**: `TransactionalPublisher` commit alert+target+deliveries trước khi enqueue (transactional outbox); restart `RecoveryScanner` quét deliveries PENDING/SENT/RETRY_SCHEDULED/FAILED để giao lại. Work đã commit không mất.

**Q6. Sao chọn SQLite?**
Đơn giản, transaction tin cậy ở tải demo. WAL cho đọc đồng thời nhưng **một writer**; dùng BEGIN IMMEDIATE + busy_timeout + transaction ngắn. Chỉ chuyển PostgreSQL khi có bằng chứng nghẽn thật (đo, không đoán).

**Q7. Đo percentile latency thế nào?**
Server dùng clock đơn điệu: `ackReceipt - dispatchStart` mỗi delivery (`MetricsRegistry` nearest-rank p50/p95/p99). Tách timeout khỏi mẫu thành công để tránh bias. Mỗi run kèm runId/commit SHA/máy đo (xem `docs/evidence/benchmark/*/summary.json`).

**Q8. CAP export có tương thích chuẩn?**
Chỉ "CAP-inspired metadata" (urgency/severity/certainty). Muốn tuyên bố tương thích CAP 1.2 phải validate XML namespace/required fields/vocabulary bằng schema — hiện là stretch, chưa tuyên bố compliant.

## B. Câu hỏi sâu kỹ thuật

**Q9. Race giữa ACK và dispatch xử lý sao?**
`FanoutEngine.sendPush` commit `recordAttempt` + `updateDispatchState(SENT)` TRƯỚC khi offer frame vào writer queue; `updateDispatchState` có rank guard (WHERE state IN PENDING/SENT/RETRY_SCHEDULED) nên ACK đến muộn không bị SENT đè lùi (ACK-only-advances). Test `ackReceivedStopsRetryCycle` ổn định.

**Q10. Idempotency của duplicate ACK?**
`DeliveryRepo.recordAck` dùng `ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING`, trả cờ inserted; router đặt `duplicate=!inserted` + audit DUPLICATE. Duplicate không lỗi, không tạo row trùng.

**Q11. Recovery có vượt ngân sách retry?**
Không. `settled()` gồm FAILED; delivery FAILED recover sẽ untrack không thêm attempt (attemptCount giữ = maxAttempts). Test `recoveredFailedDeliveryGetsNoNewAttempts`.

**Q12. Transactional outbox là gì trong project?**
Persist deliveries chính là hàng đợi dispatch bền vững: insert alert/revision/targets/deliveries trong MỘT transaction, commit rồi mới enqueue mạng. Crash giữa chừng → không mất việc, recovery giao lại. Không cần bảng outbox riêng.

**Q13. Backpressure slow-client?**
`BoundedWriterQueue` 256/session, priority eviction (CRITICAL giữ chỗ), client chậm quá ngưỡng → disconnect + audit SLOW_CLIENT_DISCONNECT. Một client chậm không kẹt các client khác.

**Q14. TLS pinning hoạt động sao?**
Client `TlsSupport` nạp truststore cố định (cert server self-signed), `SSLContext` chỉ tin cert đó; không trust-all. Cert lạ → PKIX path building failed. Discovery UDP phải kèm TLS verify + CN==serverId + nonce echo mới tin.

**Q15. RBAC scope enforce ở đâu?**
Server-side mỗi lệnh: `RbacService.grantsFor` đọc role_grants từ DB, `ScopedRBAC.canPublish(role,grants,building,floor)`. FLOOR_MANAGER chỉ floor grant; không tin role/floor client gửi lên. Target snapshot bất biến lúc activate.

**Q16. Framing chống oversize/fragment?**
`FrameCodecImpl` đọc uint32 BE, chặn L>65536 TRƯỚC cấp phát; `readFully` xử lý partial; nhiều frame dính nhau đọc tuần tự đúng ranh giới; UTF-8 REPORT + JSON single object + version/type validate. Test fragment/coalesce/oversize/invalid.

**Q17. Dedup revision hoạt động sao?**
`DedupIndex.seen(alertId,revision,deviceId)`: revision ≤ max đã thấy → duplicate/stale (suppress display, vẫn re-ACK); revision mới hơn → deliver. Seed từ durable inbox kể cả tombstone. Cancel tombstone chặn alert cũ hồi sinh.

**Q18. Vì sao timestamp fixed-width?**
SQLite so sánh TEXT lexicographic; ISO instant variable-width (`.5Z` vs `.500Z`) sai thứ tự. `Sql.TS`/`Json.ts` dùng appendInstant(9) nano fixed-width → ordering/expiry đúng.

**Q19. Đa luồng mô hình gì?**
Accept loop + reader mỗi kết nối + bounded writer thread/session; `ExecutorService`/virtual threads cho simulator fleet. Không nhiều thread ghi xen kẽ byte một socket. PriorityScheduler toàn cục + queue mỗi session.

**Q20. Đo multicast vs TCP thế nào, kết quả?**
`FanoutBenchmark`: TCP fanout gửi full content N×M lần; multicast gửi M hint (1 bản link nguồn, kernel/switch nhân bản). Đo publisher packets/bytes vs received copies + latency. Kết quả thật (8 sub×200 msg): multicast −87.5% gói/−97.8% byte; latency loopback macOS cao hơn unicast (caveat trung thực: multicast thắng ở copies/bytes trên link thật, không phải loopback latency).
