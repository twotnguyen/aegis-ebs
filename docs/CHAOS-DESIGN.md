# THIẾT KẾ MỞ RỘNG CHAOS — AEGIS-EBS (C17+)

> Bổ sung cho C01–C16 (blueprint §12). Tài liệu THIẾT KẾ, không phải kết quả đã đo. Mỗi kịch bản: cách inject, bất biến kỳ vọng, metric + ngưỡng, tái lập seed.

## Nền tảng inject hiện có
- `aegis-simulator.FaultInjector` / `FaultProfile`: dropRate, delayBaseMs/delayJitterMs, duplicateRate/duplicateCount, disconnectRate; mỗi decision tiêu thụ đúng 4 RNG draw (cố định) → cùng seed identical.
- Scenarios: clean/lossy/delayed/duplicate/flaky/mixed + per-rate overrides.
- Server: `RetryPolicy` (3s/6s/12s, max 3), `BoundedWriterQueue` 256, `RecoveryScanner`, SQLite WAL busy_timeout.

## Kịch bản mở rộng
| ID | Kịch bản | Cách inject | Bất biến kỳ vọng | Metric + ngưỡng | Tái lập |
|---|---|---|---|---|---|
| C17 | Burst 1000 alert | operator publish liên tiếp 1000 requestId khác nhau, fleet 100 | không mất alert committed; priority CRITICAL không starvation; queue không OOM | p95 dispatch < ngưỡng tải; queue depth high-water ≤256/session; 0 alert mất | seed cố định + script burst |
| C18 | Clock skew | chỉnh clock client lệch ±5s (simulator override sentAt) | server dùng monotonic riêng, không dựa clock client; expiry tính theo server | latency đo vẫn đúng (server monotonic); không mis-expiry | override timestamp sim |
| C19 | SQLite WAL contention cực đoan | nhiều writer đồng thời + busy_timeout thấp | transaction ngắn + retry BUSY; không corrupt; FK sạch | foreign_key_check=0; 0 SQLITE_BUSY tràn; commit thành công | concurrent publish script |
| C20 | Network partition giả lập | simulator disconnect toàn bộ fleet giữa chừng rồi reconnect | alert active sync lại đúng; tombstone không hồi sinh; ACK pending resend | reconnect ≤15s; sync đúng revision; 0 popup trùng | disconnectRate=1.0 timed |
| C21 | Retry storm | drop ACK 100% nhiều alert cùng lúc | retry bounded max 3 rồi FAILED; không infinite loop; CPU không spike | attemptCount ≤4; FAILED đúng; CPU đo được | dropRate=1.0 |
| C22 | Cancel trong khi retry | cancel alert đang RETRY_SCHEDULED | cancel tombstone chặn retry hồi sinh; ACK_CANCEL độc lập | 0 alert cancelled hiển thị lại; operation receipt đúng | script cancel timed |
| C23 | Duplicate requestId flood | gửi cùng requestId N lần payload giống/khác | giống → trả alert gốc (idempotent); khác → CONFLICT; không tạo trùng | alert count đúng 1; CONFLICT đúng | flood same requestId |
| C24 | Slow-reader backpressure | client đọc chậm (sleep trước read) | bounded queue eviction; client khác không bị kẹt; slow client disconnect + audit | session khác p95 ổn định; SLOW_CLIENT_DISCONNECT audit | sim slow-read override |
| C25 | Memory pressure | fleet 500 + payload lớn | frame cap 64KiB chặn; bounded queue giới hạn; không OOM | RSS đo được; 0 unbounded alloc; GC ổn | fleet 500 + payload max |
| C26 | Forged multicast hint rev cao | gửi ALERT_HINT revision giả cao | client guard verified-revision, không refetch amplify; hiển thị đúng TLS truth | 0 popup giả; refetch count bounded | multicast forge script |
| C27 | Pre-auth connection flood | mở nhiều kết nối không auth | pre-auth TTL 10s + maxConnections cap; reject vượt cap | PRE_AUTH_EXPIRED audit; connection count ≤ cap | raw socket flood |
| C28 | Oversize/malformed fuzz | gửi frame length khổng lồ + UTF-8 lỗi + JSON sâu | reject trước cấp phát; server không crash | 0 crash; INVALID_FRAME/MESSAGE_TOO_LARGE; RSS ổn | fuzz script |

## Bất biến toàn cục phải giữ
1. ACK-only-advances (rank guard) — không trạng thái lùi.
2. Idempotency: requestId/deliveryId/revision — không xử lý trùng.
3. No starvation: CRITICAL ưu tiên nhưng tier thấp vẫn được phục vụ (burst 8).
4. Recovery đúng ngân sách retry, không vượt maxAttempts.
5. Cancel/expire tombstone không hồi sinh.
6. Bounded memory: frame cap + queue cap.
7. Multicast/UDP không bao giờ là lệnh tin cậy.

## Cách đo & báo cáo
Mỗi kịch bản chạy qua `scripts/chaos-run.sh` (hoặc simulator overrides), xuất `docs/evidence/chaos/<scenario>.json` gồm: config + seed, sim summary (parse_sim_summary), db_stats (latency percentiles, ACK rate, retry), audit_rows (denied/disconnect/recovery), runId/commit SHA/máy. So sánh baseline (clean) vs fault để chứng minh hệ thống chịu lỗi. **Không bịa số** — chỉ báo cáo kết quả chạy thật.
