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
Each scenario: seed + run command + evidence (log/report/screenshot). [TODO fill in real results]
