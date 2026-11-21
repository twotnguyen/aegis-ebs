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
Mỗi scenario: seed + lệnh chạy + evidence (log/report/ảnh). [TODO điền kết quả thật]
