Language: English | Vietnamese original: docs/TEST-PLAN.md

# TEST PLAN — mapping acceptance & chaos tests to modules/commands
Run unit tests: `mvn -q test` (whole reactor). Surefire XML in target/surefire-reports provides the exact figures.
## Acceptance (from Plan/ACCEPTANCE_TEST_MATRIX.csv, 30 tests)
| Test | Scenario | Responsible module | Command/verify |
|---|---|---|---|
| T-01..04 | Framing fragment/coalesce/oversize/invalid | aegis-protocol | mvn -pl aegis-protocol test (FrameCodecStream/Validation) |
| T-05,06 | Fake TLS cert / device disabled | aegis-server | integration loopback TLS |
| T-07 | RBAC out-of-scope | aegis-core/server | ScopedRBACTest + server deny |
| T-08 | target snapshot floors 4-6 | aegis-core/persistence | TargetResolverTest + TransactionalPublisherTest |
| T-09,10 | requestId idempotency/conflict | aegis-persistence | TransactionalPublisherTest |
| T-11,12 | durable inbox / RECEIVED≠ACK_USER | aegis-client | client inbox test |
| T-13,14 | retry on dropped ACK / dedup | aegis-core/server | RetryPolicyTest + integration |
| T-15,16,17 | reconnect/expire/cancel tombstone | aegis-server/client | SYNC integration |
| T-18,19 | crash recovery | aegis-persistence/server | RecoveryScannerTest + restart |
| T-20,21 | 100 clients / latency | aegis-simulator/server | load run + metrics |
| T-22 | priority burst | aegis-core | PrioritySchedulerTest |
| T-23 | slow client queue | aegis-server | bounded queue test |
| T-24,25 | UDP spoof / multicast block | aegis-network-lab | lab test |
| T-26,27 | digital twin counts / replay | aegis-dashboard/server | WS contract + replay |
| T-28 | secrets in logs | aegis-server | log scan |
| T-29 | deterministic chaos | aegis-simulator | seeded run |
| T-30 | fresh install | scripts/README | boot <30min |
## Chaos C01–C16 (blueprint §12)
C01 drop ACK · C02 delay ACK · C03 duplicate · C04 disconnect 20% · C05 reconnect · C06 kill after commit · C07 kill after write · C08 unauthorized · C09 fake cert · C10 oversize fuzz · C11 priority burst · C12 cancel offline · C13 forged UDP · C14 multicast block · C15 SQLite contention · C16 slow client.

| ID | Result | Evidence |
|---|---|---|
| C01 | **MEASURED** — lossy: 15 ACKs dropped → 15 retries, ACK 49/50 (98%), 1 FAILED at attempt 4; p50/p95/p99 = 493/6026/13575 ms; seed 42, runId `e1943406` | `docs/evidence/chaos/lossy.json`, `docs/evidence/chaos/runs/lossy-e1943406/` |
| C02 | **MEASURED** — delayed: 50 ACKs delayed, ACK 50/50, 0 retries; 1585/2703/2707 ms; runId `d04af662` | `docs/evidence/chaos/delayed.json` |
| C03 | **MEASURED** — duplicate: 22 duplicate ACKs deduped, ACK 100%; 189/301/306 ms; runId `04c97420` | `docs/evidence/chaos/duplicate.json` |
| C04 | **MEASURED** — flaky: 21 disconnects → 21 reconnects, ACK 100%; 599/1016/1033 ms; runId `8e92ca3c` | `docs/evidence/chaos/flaky.json` |
| C05 | **MEASURED** — mixed: 13 reconnects, ACK 100%, 12 retries; 1286/3506/5008 ms; runId `3fe99b11` | `docs/evidence/chaos/mixed.json` |
| C06 | PASS (unit/integration) — commit before enqueue; recovery redelivers | `RecoveryScannerTest`, `TlsLoopbackIntegrationTest.crashRecoveryRedeliversAfterRestart` |
| C07 | PASS — pending ACK resent after reconnect, server dedup | `ClientEngineTest.crashBetweenCommitAndAckResendsPendingAckOnReconnect` |
| C08 | PASS — deny-by-default + audit | `ScopedRBACTest`, `RbacServiceTest`, `ServerSecurityFixesTest` |
| C09 | PASS — PKIX rejects untrusted cert | `ClientTlsSmokeTest`, `DiscoveryTrustTest`, review-c probe |
| C10 | PASS — oversize rejected before allocation; malformed UTF-8 rejected | `FrameCodecValidationTest`, `FrameCodecStreamTest`, review-a probe |
| C11 | PASS — flood of 5000 CRITICAL still gets relief | `PrioritySchedulerTest`, `BoundedWriterQueueTest`, review-a probe |
| C12 | PASS — cancel tombstone does not revive old alert | `TlsLoopbackIntegrationTest`, `ClientEngineTest` |
| C13 | PASS — nonce echo + `CN==serverId` + TLS pin | `DiscoveryTrustTest`, `DiscoveryNonceEchoTest` |
| C14 | PASS — unicast fallback; 3-mode benchmark (−87.5% packets) | `UnicastFallbackTest`, `MulticastLoopbackTest`, `docs/evidence/benchmark/276dedf5-fa83-4022-b7be-f3a47fccf591/summary.json` |
| C15 | PASS — 26 tables, clean FK, first-open mutex | `DbTest`, `PeerReviewFixesTest$ConcurrentFirstOpen` |
| C16 | PASS — priority eviction + `SLOW_CLIENT_DISCONNECT` audit | `BoundedWriterQueueTest`, `TlsLoopbackIntegrationTest.slowClientIsDisconnectedWhenQueueOverflows` |

C01–C05: fleet 50, seed 42, ports 29443/29444, Apple M3 Pro/Java 24.0.1; all runs `failed=[]`. C06–C16 have **no separate chaos-run files**; evidence is surefire XML under `docs/evidence/verify/<module>/` plus probes in `docs/reviews/`. Do not claim PASS without evidence.
