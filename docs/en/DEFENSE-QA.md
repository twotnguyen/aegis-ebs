Language: English | Vietnamese original: docs/DEFENSE-QA.md

# PROJECT DEFENSE QUESTIONS — AEGIS-EBS (with answers grounded in source code)

> 18+ common review questions for a computer-networking project defense, answered from the actual source code.

## A. Foundation questions (blueprint §16)

**Q1. TCP is already reliable — why do we need an application-level ACK?**
A TCP ACK only proves bytes reached the receiver's transport layer; it does not prove the application processed or durably stored them, and it does not identify which device received them. `ACK_RECEIVED` is sent only AFTER the client commits its durable inbox (`AlertClientEngine.handleAlert`: inbox.store → ACK), proving the message was stored; `ACK_USER` is a separate human confirmation. The server needs to know *which device* received the alert in order to retry the right target.

**Q2. Why not MQTT/Firebase?**
The CMP180 course objective is to build the protocol and manage connections ourselves with Java Sockets. Using a ready-made broker would remove the graded parts (framing, multithreading, ACK/retry, recovery). MQTT QoS1 is only a reference for the at-least-once principle; we implement it ourselves on `Socket`/`ServerSocket`/`SSLSocket`.

**Q3. Does retry guarantee exactly-once?**
No. Exactly-once is impossible over a network; we implement **bounded at-least-once** (retry 3s/6s/12s, max 3) + **idempotent processing**: stable `deliveryId`, `alertId/revision`, client dedup (`DedupIndex`) → effectively-once UI (no duplicate popups). UNIQUE `requestId` prevents duplicate alert creation.

**Q4. Why is multicast only a hint?**
UDP multicast is unreliable, unauthenticated, and does not guarantee delivery to every receiver. Using it as the primary alert channel is a safety risk. `MulticastHintPublisher` sends an advisory `ALERT_HINT{alertId,revision,serverId}`; `VerifiedAlertSubscriber` only displays content fetched over authenticated TLS. Multicast is still measured for real (~87.5% fewer packets / ~97.8% fewer bytes than TCP fanout).

**Q5. What if the server dies?**
Single-node, no HA (limitation stated explicitly). However, **crash recovery** exists: `TransactionalPublisher` commits alert+target+deliveries before enqueueing (transactional outbox); on restart `RecoveryScanner` scans deliveries in PENDING/SENT/RETRY_SCHEDULED/FAILED to redeliver them. Committed work is not lost.

**Q6. Why SQLite?**
Simple, with reliable transactions at demo load. WAL allows concurrent reads but **one writer**; we use BEGIN IMMEDIATE + busy_timeout + short transactions. Switch to PostgreSQL only with real evidence of contention (measure, do not guess).

**Q7. How are latency percentiles measured?**
The server uses a monotonic clock: `ackReceipt - dispatchStart` per delivery (`MetricsRegistry` nearest-rank p50/p95/p99). Timeouts are separated from successful samples to avoid bias. Every run includes runId/commit SHA/measurement machine (see `docs/evidence/benchmark/*/summary.json`).

**Q8. Is the CAP export standards-compliant?**
It is only "CAP-inspired metadata" (urgency/severity/certainty). Claiming CAP 1.2 compliance would require validating the XML namespace/required fields/vocabulary against a schema — currently a stretch goal; no compliance is claimed.

## B. Deep technical questions

**Q9. How is the race between ACK and dispatch handled?**
`FanoutEngine.sendPush` commits `recordAttempt` + `updateDispatchState(SENT)` BEFORE offering the frame to the writer queue; `updateDispatchState` has a rank guard (WHERE state IN PENDING/SENT/RETRY_SCHEDULED), so a late ACK is not overwritten back to SENT (ACK-only-advances). The test `ackReceivedStopsRetryCycle` is stable.

**Q10. Idempotency of duplicate ACKs?**
`DeliveryRepo.recordAck` uses `ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING`, returning an inserted flag; the router sets `duplicate=!inserted` + audits DUPLICATE. Duplicates are not errors and create no duplicate rows.

**Q11. Does recovery exceed the retry budget?**
No. `settled()` includes FAILED; recovering a FAILED delivery untracks it without another attempt (attemptCount stays = maxAttempts). Test `recoveredFailedDeliveryGetsNoNewAttempts`.

**Q12. What is the transactional outbox in this project?**
Persisted deliveries ARE the durable dispatch queue: alert/revision/targets/deliveries are inserted in ONE transaction, and only then enqueued to the network. A crash in between loses no work; recovery redelivers. No separate outbox table is needed.

**Q13. Slow-client backpressure?**
`BoundedWriterQueue` 256/session, priority eviction (CRITICAL keeps its place), client beyond the threshold → disconnect + SLOW_CLIENT_DISCONNECT audit. One slow client does not block the others.

**Q14. How does TLS pinning work?**
Client `TlsSupport` loads a fixed truststore (self-signed server cert), and `SSLContext` trusts only that cert; no trust-all. An unknown cert → PKIX path building failed. UDP discovery must also include TLS verification + CN==serverId + nonce echo to be trusted.

**Q15. Where is scoped RBAC enforced?**
Server-side on every command: `RbacService.grantsFor` reads role_grants from the DB, `ScopedRBAC.canPublish(role,grants,building,floor)`. FLOOR_MANAGER only on the granted floor; client-supplied role/floor is never trusted. The target snapshot is immutable at activation.

**Q16. How does framing handle oversize/fragmentation?**
`FrameCodecImpl` reads uint32 BE, blocks L>65536 BEFORE allocation; `readFully` handles partial reads; coalesced frames are read sequentially at correct boundaries; strict UTF-8 (REPORT) + single JSON object + version/type validation. Tests cover fragment/coalesce/oversize/invalid.

**Q17. How does revision dedup work?**
`DedupIndex.seen(alertId,revision,deviceId)`: revision ≤ highest seen → duplicate/stale (suppress display, still re-ACK); higher revision → deliver. Seeded from the durable inbox including tombstones. Cancel tombstones prevent old alerts from reappearing.

**Q18. Why fixed-width timestamps?**
SQLite compares TEXT lexicographically; variable-width ISO instants (`.5Z` vs `.500Z`) sort incorrectly. `Sql.TS`/`Json.ts` use appendInstant(9) with fixed-width nanoseconds → correct ordering/expiry.

**Q19. What is the threading model?**
Accept loop + one reader per connection + one bounded writer thread per session; `ExecutorService`/virtual threads for the simulator fleet. Multiple threads never interleave bytes on one socket. Global PriorityScheduler + per-session queue.

**Q20. How was multicast vs TCP measured, and what was the result?**
`FanoutBenchmark`: TCP fanout sends full content NxM times; multicast sends M hints (one copy on the source link, replicated by kernel/switch). Measured publisher packets/bytes vs received copies + latency. Real result (8 subscribers x 200 messages): multicast −87.5% packets / −97.8% bytes; macOS loopback latency was higher than unicast (honest caveat: multicast wins on copies/bytes on a real link, not on loopback latency).
