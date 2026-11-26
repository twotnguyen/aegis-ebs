# TEST PLAN — ánh xạ acceptance & chaos vào module/lệnh
Chạy unit: `mvn -q test` (toàn reactor). Surefire XML trong target/surefire-reports cho số liệu chính xác.
## Acceptance (từ Plan/ACCEPTANCE_TEST_MATRIX.csv, 30 test)
| Test | Kịch bản | Module phụ trách | Lệnh/verify |
|---|---|---|---|
| T-01..04 | Framing fragment/coalesce/oversize/invalid | aegis-protocol | mvn -pl aegis-protocol test (FrameCodecStream/Validation) |
| T-05,06 | TLS cert giả / device disabled | aegis-server | integration loopback TLS |
| T-07 | RBAC out-of-scope | aegis-core/server | ScopedRBACTest + server deny |
| T-08 | target snapshot floors 4-6 | aegis-core/persistence | TargetResolverTest + TransactionalPublisherTest |
| T-09,10 | requestId idempotency/conflict | aegis-persistence | TransactionalPublisherTest |
| T-11,12 | durable inbox / RECEIVED≠ACK_USER | aegis-client | client inbox test |
| T-13,14 | retry drop ACK / dedup | aegis-core/server | RetryPolicyTest + integration |
| T-15,16,17 | reconnect/expire/cancel tombstone | aegis-server/client | SYNC integration |
| T-18,19 | crash recovery | aegis-persistence/server | RecoveryScannerTest + restart |
| T-20,21 | 100 client / latency | aegis-simulator/server | load run + metrics |
| T-22 | priority burst | aegis-core | PrioritySchedulerTest |
| T-23 | slow client queue | aegis-server | bounded queue test |
| T-24,25 | UDP spoof / multicast block | aegis-network-lab | lab test |
| T-26,27 | digital twin counts / replay | aegis-dashboard/server | WS contract + replay |
| T-28 | secrets in logs | aegis-server | log scan |
| T-29 | deterministic chaos | aegis-simulator | seeded run |
| T-30 | fresh install | scripts/README | boot <30min |
## Chaos C01–C16 (blueprint §12)
C01 drop ACK · C02 delay ACK · C03 duplicate · C04 disconnect 20% · C05 reconnect · C06 kill sau commit · C07 kill sau write · C08 unauthorized · C09 cert giả · C10 oversize fuzz · C11 priority burst · C12 cancel offline · C13 UDP forged · C14 multicast block · C15 SQLite contention · C16 slow client.

| ID | Kết quả | Bằng chứng |
|---|---|---|
| C01 | **ĐO THẬT** — lossy: 15 ACK drop → 15 retry, ACK 49/50 (98%), 1 FAILED ở attempt 4; p50/p95/p99 = 493/6026/13575 ms; seed 42, runId `e1943406` | `docs/evidence/chaos/lossy.json`, `docs/evidence/chaos/runs/lossy-e1943406/` |
| C02 | **ĐO THẬT** — delayed: 50 ACK delay, ACK 50/50, 0 retry; 1585/2703/2707 ms; runId `d04af662` | `docs/evidence/chaos/delayed.json` |
| C03 | **ĐO THẬT** — duplicate: 22 ACK trùng bị khử, ACK 100%; 189/301/306 ms; runId `04c97420` | `docs/evidence/chaos/duplicate.json` |
| C04 | **ĐO THẬT** — flaky: 21 disconnect → 21 reconnect, ACK 100%; 599/1016/1033 ms; runId `8e92ca3c` | `docs/evidence/chaos/flaky.json` |
| C05 | **ĐO THẬT** — mixed: 13 reconnect, ACK 100%, 12 retry; 1286/3506/5008 ms; runId `3fe99b11` | `docs/evidence/chaos/mixed.json` |
| C06 | PASS (unit/integration) — commit trước enqueue; recovery giao lại | `RecoveryScannerTest`, `TlsLoopbackIntegrationTest.crashRecoveryRedeliversAfterRestart` |
| C07 | PASS — pending ACK gửi lại sau reconnect, dedup phía server | `ClientEngineTest.crashBetweenCommitAndAckResendsPendingAckOnReconnect` |
| C08 | PASS — deny-by-default + audit | `ScopedRBACTest`, `RbacServiceTest`, `ServerSecurityFixesTest` |
| C09 | PASS — PKIX từ chối cert lạ | `ClientTlsSmokeTest`, `DiscoveryTrustTest`, probe review-c |
| C10 | PASS — chặn oversize trước cấp phát; UTF-8 dị dạng bị từ chối | `FrameCodecValidationTest`, `FrameCodecStreamTest`, probe review-a |
| C11 | PASS — flood 5000 CRITICAL vẫn relief đúng chu kỳ | `PrioritySchedulerTest`, `BoundedWriterQueueTest`, probe review-a |
| C12 | PASS — tombstone hủy không hồi sinh alert cũ | `TlsLoopbackIntegrationTest`, `ClientEngineTest` |
| C13 | PASS — nonce echo + `CN==serverId` + TLS pin | `DiscoveryTrustTest`, `DiscoveryNonceEchoTest` |
| C14 | PASS — unicast fallback; benchmark 3 chế độ (−87,5% gói) | `UnicastFallbackTest`, `MulticastLoopbackTest`, `docs/evidence/benchmark/276dedf5-fa83-4022-b7be-f3a47fccf591/summary.json` |
| C15 | PASS — 26 bảng FK sạch, first-open mutex | `DbTest`, `PeerReviewFixesTest$ConcurrentFirstOpen` |
| C16 | PASS — eviction ưu tiên + `SLOW_CLIENT_DISCONNECT` audit | `BoundedWriterQueueTest`, `TlsLoopbackIntegrationTest.slowClientIsDisconnectedWhenQueueOverflows` |

C01–C05: fleet 50, seed 42, ports 29443/29444, Apple M3 Pro/Java 24.0.1; mọi run có `failed=[]`. C06–C16 **không có file chaos-run riêng**; bằng chứng là surefire XML trong `docs/evidence/verify/<module>/` và probe trong `docs/reviews/`. Không tuyên bố "đã pass" cho kịch bản chưa có bằng chứng.
