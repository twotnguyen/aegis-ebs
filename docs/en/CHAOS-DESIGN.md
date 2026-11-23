Language: English | Vietnamese original: docs/CHAOS-DESIGN.md

# EXTENDED CHAOS DESIGN — AEGIS-EBS (C17+)

> Supplemental to C01–C16 (blueprint §12). This is a DESIGN document, not measured results. Each scenario: injection method, expected invariant, metric + threshold, seed reproducibility.

## Existing injection foundation
- `aegis-simulator.FaultInjector` / `FaultProfile`: dropRate, delayBaseMs/delayJitterMs, duplicateRate/duplicateCount, disconnectRate; each decision consumes exactly 4 RNG draws (fixed) → identical with the same seed.
- Scenarios: clean/lossy/delayed/duplicate/flaky/mixed + per-rate overrides.
- Server: `RetryPolicy` (3s/6s/12s, max 3), `BoundedWriterQueue` 256, `RecoveryScanner`, SQLite WAL busy_timeout.

## Extended scenarios
| ID | Scenario | Injection method | Expected invariant | Metric + threshold | Reproduction |
|---|---|---|---|---|---|
| C17 | Burst of 1000 alerts | operator publishes 1000 consecutive distinct requestIds, fleet of 100 | no committed alert lost; CRITICAL priority does not starve; queue does not OOM | p95 dispatch < load threshold; queue depth high-water ≤256/session; 0 alerts lost | fixed seed + burst script |
| C18 | Clock skew | shift client clock ±5s (simulator overrides sentAt) | server uses its own monotonic clock, does not trust client clock; expiry computed by server | measured latency remains correct (server monotonic); no mis-expiry | simulator timestamp override |
| C19 | Extreme SQLite WAL contention | many concurrent writers + low busy_timeout | short transactions + BUSY retry; no corruption; FK clean | foreign_key_check=0; 0 overflow SQLITE_BUSY; successful commits | concurrent publish script |
| C20 | Simulated network partition | simulator disconnects the whole fleet mid-run then reconnects | active alerts sync correctly; tombstones not resurrected; pending ACKs resent | reconnect ≤15s; correct revision sync; 0 duplicate popups | disconnectRate=1.0 timed |
| C21 | Retry storm | drop ACK 100% for many alerts at once | retry bounded at max 3 then FAILED; no infinite loop; no CPU spike | attemptCount ≤4; FAILED correct; measurable CPU | dropRate=1.0 |
| C22 | Cancel during retry | cancel an alert currently RETRY_SCHEDULED | cancel tombstone blocks retry resurrection; ACK_CANCEL independent | 0 cancelled alerts redisplayed; correct operation receipt | timed cancel script |
| C23 | Duplicate requestId flood | send the same requestId N times with identical/different payloads | identical → return original alert (idempotent); different → CONFLICT; no duplicate creation | alert count exactly 1; correct CONFLICT | flood same requestId |
| C24 | Slow-reader backpressure | slow-reading client (sleep before read) | bounded queue eviction; other clients not blocked; slow client disconnect + audit | other sessions' p95 stable; SLOW_CLIENT_DISCONNECT audit | simulator slow-read override |
| C25 | Memory pressure | fleet of 500 + large payloads | frame cap 64KiB blocks; bounded queue limits; no OOM | measurable RSS; 0 unbounded allocation; stable GC | fleet 500 + max payload |
| C26 | Forged multicast hint with high revision | send fake high-revision ALERT_HINT | client guards verified-revision, no refetch amplification; display follows TLS truth | 0 fake popups; bounded refetch count | multicast forge script |
| C27 | Pre-auth connection flood | open many unauthenticated connections | pre-auth TTL 10s + maxConnections cap; reject above cap | PRE_AUTH_EXPIRED audit; connection count ≤ cap | raw socket flood |
| C28 | Oversize/malformed fuzz | send huge frame length + invalid UTF-8 + deep JSON | reject before allocation; server does not crash | 0 crashes; INVALID_FRAME/MESSAGE_TOO_LARGE; stable RSS | fuzz script |

## Global invariants that must hold
1. ACK-only-advances (rank guard) — no state regression.
2. Idempotency: requestId/deliveryId/revision — no duplicate processing.
3. No starvation: CRITICAL has priority but lower tiers are still served (burst 8).
4. Recovery respects the retry budget, never exceeding maxAttempts.
5. Cancel/expire tombstones are never resurrected.
6. Bounded memory: frame cap + queue cap.
7. Multicast/UDP is never a reliable command.

## How to measure & report
Each scenario runs through `scripts/chaos-run.sh` (or simulator overrides), exporting `docs/evidence/chaos/<scenario>.json` containing: config + seed, sim summary (parse_sim_summary), db_stats (latency percentiles, ACK rate, retries), audit_rows (denied/disconnect/recovery), runId/commit SHA/machine. Compare baseline (clean) vs fault to demonstrate fault tolerance. **Do not invent numbers** — report only results from real runs.
