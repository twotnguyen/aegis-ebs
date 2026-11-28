Language: English | Vietnamese original: docs/PERFORMANCE.md

# PERFORMANCE — Performance analysis & optimization proposals for AEGIS-EBS

> Scope: `aegis-server`, `aegis-persistence`, `aegis-simulator`, `aegis-dashboard/src/three` (and `src/main.js`).
> Method: **static source-code analysis** (reading code, reasoning about structure). This document **contains no measured figures**;
> every number given is (a) a default configuration value quoted from code, (b) a static estimate derived from code structure (explicitly labeled),
> or (c) an empty result cell in the benchmark plan. Section §6 defines how to measure in order to fill those cells with real numbers.
> Citations use `file:line`. No proposal may break the accepted safety invariants (S-01, S-02, S-05, S-07, S-09, S-10 — see `docs/SECURITY.md`, `docs/reviews/`).

---

## 1. Current execution architecture (what is ALREADY done right)

This section records performance mechanisms already in place so proposals do not "reinvent" them.

### 1.1 Backend

| Mechanism | Current state (file:line) |
|---|---|
| Virtual threads for every connection | `AegisServer.java:85` (`Executors.newVirtualThreadPerTaskExecutor()`); reader per TLS connection `TlsGateway.java:108`; writer per session `ClientSession.java:67`; accept loop `TlsGateway.java:78`; WS handler `StatusFeedServer.java:66`; dispatcher `AegisServer.java:90`. Simulator: 1 virtual thread/agent `SimulatorMain.java:67,74`. |
| Java 24 platform | `pom.xml:19` (`maven.compiler.release=24`) → JEP 491: `synchronized` no longer pins virtual threads; `BoundedWriterQueue`/`Db` synchronized blocks are pinning-safe on JDK 24+. |
| SQLite WAL + busy_timeout + FK in the right order | `Db.java:54-58`: `foreign_keys=ON` → `busy_timeout=5000` (`Db.java:28`) → `journal_mode=WAL` (with BUSY retry, `Db.java:79-104`); initialization serialized per-file (`Db.java:50-51`). |
| Short transactions, atomic writes | `BEGIN IMMEDIATE … COMMIT` in `TransactionalPublisher.java:85-108`, `DeliveryRepo.recordAck` (`DeliveryRepo.java:161-222`), `ServerStore.applyUpdate/applyCancel/createIncidentAtomic/transitionIncidentAtomic` (`ServerStore.java:320-461`). Fanout enqueue ONLY after commit (`TransactionalPublisher.java:111`). |
| Bounded queue backpressure | `BoundedWriterQueue` capacity 256 (`ServerConfig.java:66`), priority TreeSet + FIFO (`BoundedWriterQueue.java:31-33`), evict lowest-priority frame when full (`BoundedWriterQueue.java:50-57`), `REJECTED_SLOW` → disconnect slow client (`ClientSession.java:71-78`, `TlsGateway.java:174-181`). |
| Status delta already batched/coalesced | `alertsDirty` rebuilds at most once per cycle (`StatusFeedService.java:102-107,250-259`); `statusBatchMs=500` cycle (`ServerConfig.java:67`, `AegisServer.java:122-123`); deltas contain only changed zones/metrics/alerts (`StatusFeedService.java:199-230`); alert stats use one GROUP BY query (`ServerStore.java:589-606`). |
| Monotonic counters + bounded-window percentiles | `MetricsRegistry.java:17` (4096 samples), nearest-rank p50/p95/p99 (`MetricsRegistry.java:38-53`); counters only increase (`MetricsRegistry.java:55-61`); flush to `metrics_samples` every 30s (`AegisServer.java:125,177-186`). |
| Anti-starvation priority | `PriorityScheduler.MAX_BURST=8` + lower-tier rotation (`PriorityScheduler.java:27,53-75`). |
| Input resource limits | `maxConnections=512` (`ServerConfig.java:74`, enforced `TlsGateway.java:93-98`); pre-auth TTL 10s (`ServerConfig.java:73`, `TlsGateway.java:103,135-141`); WS cap of 64 subscribers (`StatusFeedServer.java:36,100-108`); frames ≤ 64KiB (`FrameCodecImpl.java:84`), WS ≤ 1MiB (`WsConnection.java:16`). |
| Single TLS frame write | `FrameCodecImpl.writeFrame` writes one byte array + flush (`FrameCodecImpl.java:76-79`) → 1 TLS record/frame; `TCP_NODELAY` (`TlsGateway.java:100`). |

### 1.2 3D dashboard

| Mechanism | Current state (file:line) |
|---|---|
| InstancedMesh for repeated elements | `structure.js:58-74` (columns/beams per floor), `facade.js:60-68,118-120` (glass/spandrel/mullion per floor), `interior.js:108-118,228-244` (16 global furniture batches), `environment.js:71-92` (24 InstancedMesh), `safety.js:35-53` (stairs, extinguishers, sprinklers…), `lighting.js:145-199` (light panels, lamp posts…), `statusViz.js:323-343` (device markers per zone). |
| Geometry reuse | Shared unit box: `structure.js:38`, `facade.js:42`, `environment.js:54`, `safety.js:70`; merged geometry via `mergeGeometries` for tables/chairs: `interior.js:8,30,62-103`. |
| Shared materials + clone once | Library `materials.js:4-24`; statusViz clones zone material exactly once on bind then mutates it (`statusViz.js:159-161,214-218`). |
| No per-frame allocation | Scratch objects `statusViz.js:118-127`; 512-element particle pool, 1 `Points` draw call (`statusViz.js:44,82-105`); cached colors `lighting.js:206-216`. |
| Real-light ceiling | `MAX_POINT_LIGHTS=12` + hard check (`lighting.js:13,125-128`); the rest are emissive-only (`lighting.js:130-199`). |
| Triangle budget | Target < 300k triangles for the whole scene (`CONTRACT.md:25`); triangle counting at build: `environment.js:414-430`, `interior.js:247-253`. |
| Pixel ratio capped | `main.js:75` (`min(devicePixelRatio, 2)`). |

**Integration status:** `main.js` now imports and assembles all `src/three` modules through `safeBuild()` (`main.js:38-50,143-165`); the old simple twin is no longer the current configuration. The 3D analysis below describes the assembled module scene; the empty benchmark tables in §6.5 are unmeasured items, not a pending integration.

---

## 2. Backend optimization proposals (B1–B12)

Each proposal: **Current state → Problem → Solution → Expected benefit → How to measure**. No pre-existing measurements; "expected benefit" is a qualitative/quantitative inference from code structure, to be verified by §6.

### B1. Remove O(N²) DB reads when enqueueing a fanout batch (HIGHEST priority)

- **Current state:** `FanoutEngine.enqueue` iterates each delivery calling `track` (`FanoutEngine.java:108-110`); each `track` calls `openLedger` (`FanoutEngine.java:134`), and `openLedger` always runs `deliveries.findByAlert(alertId)` — loading ALL deliveries of the alert (every revision) — before `ledger.open` throws `IllegalStateException` because the ledger is already open (`FanoutEngine.java:142-152`). `findByAlert` opens a separate connection each time (`DeliveryRepo.java:68-70,259-270`).
- **Problem:** Publishing to N devices (e.g. building-wide: 480 devices with `devicesPerZone=12`, `ServerConfig.java:69`) creates N `findByAlert` calls, each reading N rows → **O(N²) rows read + N connection opens** directly on the operator's reader thread (synchronous command path: `MessageRouter.alertPublish` → `AlertService.publish` → `TransactionalPublisher.publish` → `enqueue`, `TransactionalPublisher.java:111`). For N=480: ~480 queries x 480 rows ≈ 230k mapped rows per publish (static estimate). This O(N²) stall pattern was seen earlier in review history (already fixed on the stats side via `alertsDirty`; still present on the ledger side).
- **Solution:** Open the ledger **once for the whole batch** in `enqueue`/`recoverEnqueue`: derive `expected` from the batch itself (deliveries of the same alert/revision already carry `deviceId`), or call `findByAlert` exactly once and pass it down; `track` no longer calls `openLedger`. Keep `AckLedger.open` semantics unchanged (per-alert, idempotent).
- **Expected benefit:** Building-wide publish drops from ~N queries to ~1; ALERT_PUBLISH latency and reader-thread occupancy drop sharply; removes the risk of cascading heartbeat-suspect when an operator floods publishes.
- **How to measure:** Count `Db.open`/query calls in one publish command (temporary counter wrapper in a test harness, no production change); measure `publish()` wall-time at N = 12/48/480 (§6.2); compare before/after with JFR method profiling. Verify invariants: `FanoutEngineTest` + `TlsLoopbackIntegrationTest` still green.

### B2. Index `tracked` by deviceId for `resumeDevice`/`cancelAlert`

- **Current state:** `resumeDevice` scans ALL `tracked.values()` and calls `findById` for each delivery (`FanoutEngine.java:352-365`); it runs on every device auth (`MessageRouter.java:182`) and every SYNC (`SyncService.java:70`). `cancelAlert` also scans everything (`FanoutEngine.java:396-402`).
- **Problem:** Reconnect storm (`flaky`/`mixed` scenarios, `FaultProfile.java:25-26`): M reconnecting devices x T tracked deliveries → O(MxT) `findById` calls, each opening a connection. With 500 agents and one unacked building-wide alert: 500 x 480 ≈ 240k queries (static estimate).
- **Solution:** Add `Map<String, Set<String>> byDevice` (deviceId → deliveryIds) maintained in `track`/`untrack`; `resumeDevice` scans only that device's set. `cancelAlert` can likewise be indexed by alertId. Keep dedup/retry semantics (recovery bypasses dedup, `FanoutEngine.java:112-115`).
- **Expected benefit:** Reconnect cost becomes O(own deliveries); SYNC/auth stop depending on total system load; less SQLite writer contention during a reconnect storm.
- **How to measure:** 500-agent flaky scenario (§6.2): measure p95 AUTH_RESULT→SYNC_RESULT latency, total `findById` queries/minute (via wrapper), `HEARTBEAT_SUSPECT` rate in audit.

### B3. Pool/reuse SQLite connections instead of open-per-operation

- **Current state:** Every repo method opens a new connection: `DeliveryRepo.findById` (`DeliveryRepo.java:58-66`), `recordAttempt` (`:115-130`), `updateDispatchState` (`:235-247`), `AuditRepo.append` (`AuditRepo.java:26-34`), all of `ServerStore`. Each `Db.open` passes through the per-file global lock (`Db.java:50-51`) then `DriverManager.getConnection` + 3 PRAGMAs + a `journal_mode=WAL` query (`Db.java:52-58`).
- **Problem:** (a) Fixed per-DB-operation cost (open + pragmas) multiplied by the very high frequency of the ACK/dispatch path; (b) the `OPEN_LOCKS` lock **serializes every open in the JVM** — a contention point when hundreds of virtual threads read/write concurrently; (c) each new connection is a file handle + private cache. Note: on Java 24 (`pom.xml:19`) `synchronized` does not pin virtual threads, so the issue here is **serialized throughput**, not pinning.
- **Solution:** A small connection pool (e.g. blocking queue of pre-pragma'd connections, or HikariCP with `maximumPoolSize` ≈ CPU cores for writers + a few readers). WAL allows multiple readers alongside one writer (`Db.java:20-21`), so a reader pool exploits concurrent reads. Keep `OPEN_LOCKS` for new-file initialization; keep the pragma order. sqlite-jdbc connections are not thread-safe → the pool must lend exclusively.
- **Expected benefit:** Remove open/pragma cost from the hot path; reduce serialization at the per-file lock; increase ACK/dispatch throughput at high client counts (to be verified by §6, no numbers promised in advance).
- **How to measure:** Microbenchmark `Db.open` vs pool checkout (JMH or wall-time harness); count opens/second under 500-agent load; JFR `jdk.JavaMonitorEnter` at `Db.open`; compare p99 publish→ACK before/after.

### B4. Reduce SQL round-trips in the single-threaded dispatcher

- **Current state:** A single dispatcher (`FanoutEngine.java:156-174`). Each `dispatch` re-reads the delivery (`:181`) and alert (`:190`) even though `Tracked` already holds alert+revision (`:60,130`); `sendPush` writes `recordAttempt` then `updateDispatchState` — **2 connections/2 separate transactions** (`:247-248`) before offering the frame (`:252`). `checkDelivery` calls `findById` again (`:312`).
- **Problem:** ~4+ connection opens per push, serialized on one thread. Fanout throughput is bounded by SQL latency x number of deliveries; for a building-wide alert with 480 targets, the time to emit all frames grows linearly with per-delivery SQL cost.
- **Solution:** (a) Merge `recordAttempt + updateDispatchState` into **one transaction on one connection** (add a composite method in `DeliveryRepo`, preserving S-02 order: commit attempt+SENT BEFORE offering the frame — `FanoutEngine.java:242-251`); (b) use the alert/revision already cached in `Tracked` for supersede checks, re-reading only on a change signal (ALERT_UPDATE/CANCEL already go through `AlertService`/`engine.cancelAlert`); (c) advanced option: parallelize I/O preparation with a few workers while keeping **one owner per delivery** (the tracked map) so generation guards are not broken (`:295-303`).
- **Expected benefit:** ~half as many write transactions per push; shorter dispatcher occupancy; lower p99 publish→ACK for wide fanout. S-02/S-10 invariants preserved.
- **How to measure:** Count transactions/push via wrapper; measure `PriorityScheduler.size()` depth (`PriorityScheduler.java:81-83`) and drain time after a building-wide publish; `FanoutEngineTest` (including `recoveredFailedDeliveryGetsNoNewAttempts`) must stay green.

### B5. Cap Argon2id concurrency during a reconnect storm

- **Current state:** Each AUTH_DEVICE runs Argon2id `hasher.verify` on the connection's reader thread (`AuthService.java:35-40`); default production parameters `iterations=2, memory=16384 KiB (16 MiB), parallelism=1` (`ServerConfig.java:70-72`).
- **Problem:** 500 agents reconnecting at once (flaky/mixed) → up to 500 parallel verifies, each allocating ~16 MiB of temporary memory + CPU → peak ~8 GiB of instantaneous memory pressure (static estimate from parameters), GC pauses and auth latency spikes; other connections (including the operator's) suffer collateral delay.
- **Solution:** A semaphore limiting concurrent verifies (≈ CPU cores), queueing the rest on virtual threads (cheap); optional addition: cache verify results by (deviceCode, credentialHash) with a short TTL for the demo lab — **security trade-off must be documented** (a credential leaked during the TTL remains usable) and enabled only in SIMULATION mode. Do not lower the default Argon2 parameters.
- **Expected benefit:** Memory/CPU peak during reconnect storms bounded at a configured level; p95 auth stable; alert delivery path unaffected.
- **How to measure:** 500-agent flaky scenario: measure p50/p95/p99 of AUTH_DEVICE→AUTH_RESULT (audit timestamp `AUTH_DEVICE_SUCCESS`), RSS/GC pauses (JFR `jdk.GCPhasePause`), count concurrent verifies.

### B6. Diff status deltas arithmetically instead of rebuilding a full snapshot each cycle

- **Current state:** `broadcastDelta` (500ms cycle) builds a full `snapshot()`: `floorsNode` iterates 40 zones (`StatusFeedService.java:128-140`), `onlineByZone` iterates every device session (`:159-166`, `SessionRegistry.java:51-57`), `metricsNode` computes 3 percentiles (`:168-176`), `alertCache.deepCopy` (`:123`); then compares each zone with `JsonNode.equals` (`:202-218`). The `connectivityChanged` hook is currently a no-op (`:109-112`).
- **Problem:** Every cycle allocates a full JSON tree + deepCopy + deep comparison even when nothing changed; O(zones + sessions + alerts) cost and steady GC garbage twice per second, growing with connection count. `alertsDirty` coalescing is good (`:102-107`) but the diff itself is expensive.
- **Solution:** Keep the last broadcast state as **numbers** (per zone: sent received/acknowledged/failed/online/severity) and compare arithmetically; build JSON nodes only for genuinely changed zones. Maintain `onlineByZone` incrementally in `connectivityChanged`/bind/close (the hook already exists) instead of rescanning each cycle. Preserve the status/1 contract (delta shape unchanged — `aegis-dashboard/docs/status-contract.md`).
- **Expected benefit:** CPU + allocation per cycle drop sharply when the system is idle; the 500ms cycle remains viable even with 500 connections; delta latency unchanged.
- **How to measure:** JFR allocation rate + `broadcastDelta` wall-time before/after at 100/200/500 clients; delta byte size (count in `Json.write`, `:233`); contract snapshot compared via dashboard tests (`npm test`).

### B7. Isolate slow WS subscribers from the shared timer thread (head-of-line blocking)

- **Current state:** `broadcastDelta` writes to each subscriber sequentially via `WsConnection.sendText` — a **blocking** socket write inside `synchronized(writeLock)` (`StatusFeedService.java:233-239`, `WsConnection.java:31-55`). This cycle runs on `timers`, which has **2 platform threads** shared with heartbeat sweep/broadcast, expiry and metrics flush (`AegisServer.java:84,120-125`). WS sockets have no write timeout (java.net.Socket does not support one).
- **Problem:** One dashboard that stops reading (full TCP send buffer) blocks `sendText` indefinitely → blocks the whole broadcast loop → **delays heartbeat sweep/broadcast for every TLS connection** → risk of mass suspect (3x10s, `ServerConfig.java:62-63`). This is a system-stability risk, not just performance.
- **Solution:** Apply the pattern already used on the TLS side: one bounded queue + private virtual writer thread per subscriber (reuse `BoundedWriterQueue`); when full → disconnect that subscriber (slow-client policy similar to `TlsGateway.java:174-181`) without affecting others. Cap queue capacity in bytes to block against large deltas.
- **Expected benefit:** Removes cross-plane deadlock risk between the WS read plane and the TLS command plane; heartbeat stays on schedule regardless of dashboard behavior; subscriber resources remain bounded (cap 64, `StatusFeedServer.java:36`).
- **How to measure:** Integration test: 1 non-reading subscriber + 1 normal subscriber; measure heartbeat cadence (audit/log), normal subscriber's delta latency, and time to detect/disconnect the slow subscriber. Cross-check with existing TLS slow-client tests.

### B8. WAL tuning: `synchronous=NORMAL` (lab mode) + checkpoint monitoring

- **Current state:** WAL + `busy_timeout=5000` are set in the correct order (`Db.java:54-58`); transactions are already short (§1.1). `synchronous` is never set → default **FULL**: every commit fsyncs the WAL. `wal_autocheckpoint`/`journal_size_limit` are not set → SQLite defaults (checkpoint roughly every ~1000 pages).
- **Problem:** With a very high commit rate (one `recordAck` transaction per ACK, one per attempt), fsync per commit dominates the hot path I/O cost; WAL grows under sustained writes and checkpoints can cause short stalls.
- **Solution:** Add `PRAGMA synchronous=NORMAL` **for demo/lab mode only** (explicit trade-off: still durable against process crash, but the last commit may be lost on power failure — acceptable for SIMULATION, never for real data). Monitor `-wal` file size, proactively call `wal_checkpoint(PASSIVE)` in an idle timer; set `journal_size_limit`. Keep FULL as the safe default.
- **Expected benefit:** Lower commit latency (fewer fsyncs), higher ACK/dispatch throughput; transaction semantics unchanged.
- **How to measure:** Measure p50/p95/p99 `recordAck` latency (wall-time around the call) with FULL vs NORMAL at 500 agents; track WAL size + BUSY count in logs; verify restart recovery still works (`RecoveryScanner`, existing crash-recovery test).

### B9. Eliminate N+1 in `ServerStore` + batch audit writes

- **Current state (repeated query chains):**
  - `activeAlerts`: SELECT a list of ids then `alerts.findById` for each id (`ServerStore.java:465-481`).
  - `activeAlertsForDevice` (SYNC path): per alert → `findById` + `findRevision` + `deliveryFor`; `deliveryFor` itself SELECTs ids then `findById` (`ServerStore.java:502-528,548-561`) — 2 queries for 1 row.
  - `expireOverdue` (runs every 1s, `AegisServer.java:124`): SELECT ids then `updateState` per id, each with a connection (`ServerStore.java:484-499`).
  - `rebuildAlertCache`: `findRevision` per alert + `targets` for building-wide alerts (`StatusFeedService.java:279-280,299-300`).
  - `AuditRepo.append` opens a connection per event (`AuditRepo.java:26-34`); the ACK path audits every ACK (`MessageRouter.java:250-252`) → an ACK flood becomes a flood of tiny write transactions contending for SQLite's single writer.
- **Problem:** SYNC/reconnect storms and the status cycle create masses of redundant queries/connections; audit becomes a significant write load under flood.
- **Solution:** (a) JOIN queries returning complete rows (alert+revision+delivery) in one call for `activeAlertsForDevice`/`activeAlerts`; (b) `expireOverdue` uses a single `UPDATE … RETURNING id` (sqlite-jdbc 3.46.1.0, `pom.xml:23`, supports SQLite RETURNING ≥3.35) in one transaction; (c) audit: append-only buffer + flush in one transaction per status cycle (500ms) — **keep synchronous writes for security-critical events** (DENIED/FORBIDDEN/SLOW_CLIENT_DISCONNECT) so security traces stay immediate; document the ≤500ms audit-visibility trade-off for ordinary events.
- **Expected benefit:** SYNC becomes O(1) queries/alert instead of O(3–4); expiry sweep 1 transaction/second; fewer write transactions contending for the writer during ACK floods.
- **How to measure:** `EXPLAIN QUERY PLAN` the new queries; count connections per SYNC command; p95 SYNC_RESULT under a 500-agent reconnect storm; cross-check existing `SyncService`/reconnect tests.

### B10. Make backpressure observable: wire `EVICTED_LOWER` into metrics + byte budget

- **Current state:** `offer` returns `EVICTED_LOWER` when evicting a low-priority frame (`BoundedWriterQueue.java:50-57`) and counts `evicted` (`:35,76`), but `ClientSession.send` handles only `REJECTED_SLOW` (`ClientSession.java:71-78`), and `FanoutEngine.sendPush` also branches only on `REJECTED_SLOW` (`FanoutEngine.java:253-266`). `MetricsRegistry.incSlowClientDrops()` exists (`MetricsRegistry.java:60`) but **has no production caller** (searched all of `aegis-server/src/main`). Queue capacity is counted in **frames** (256), not bytes.
- **Problem:** Evicted frames (data loss to a slow but not disconnected client) are invisible to operations/benchmarks; hard to tell "healthy system" from "system silently dropping". Frames vary in size (ALERT_PUSH ~a few hundred bytes vs 8KB frames in the slow-client test), so per-session memory budget varies widely.
- **Solution:** On `EVICTED_LOWER`: call `metrics.incSlowClientDrops()` + throttled audit (e.g. 1 event/session/cycle) + expose `queueDepth/evicted` on the observability plane (additive metric fields in the status contract, without breaking the existing contract). Optional: add a byte threshold alongside the frame threshold.
- **Expected benefit:** Measurable backpressure (prerequisite for §6 benchmarks); early detection of slow clients before disconnect; current disconnect policy unchanged.
- **How to measure:** Flood a slow client (reproduce test condition 9 of `TlsLoopbackIntegrationTest`): reconcile `evictedCount` with the new metric; confirm no spurious disconnects in the clean scenario.

### B11. O(1) percentiles via histogram + clarify latency semantics

- **Current state:** `percentile` copies + sorts up to 4096 samples per call (`MetricsRegistry.java:38-49`); called 3 times per 500ms delta cycle (`StatusFeedService.java:171-173`), once per subscription snapshot (`:247`), and 3 times per 30s flush (`AegisServer.java:179-181`). Latency samples are `ackedAt − alert.createdAt` (`FanoutEngine.java:377-382`).
- **Problem:** (a) O(n log n) per call under a copy lock — small but repeated 6+ times/second and growing as the window fills; (b) sample semantics are **end-to-end publish→first ACK_RECEIVED**, including the retry window (3s/6s/12s) and delays injected by the simulator — not pure transport latency; reading p99 without understanding this leads to wrong conclusions.
- **Solution:** Replace reservoir+sort with a fixed-bucket histogram (e.g. 1ms buckets up to 60s, or HdrHistogram) for O(1) percentile queries, keeping nearest-rank and an equivalent window; keep monotonic counters. Document two metrics: `delivery_latency_*` (end-to-end, existing) and `transport_latency` (ACK − last SENT attempt, computed from DB, §6.3).
- **Expected benefit:** Lighter status cycle; stable percentiles under high load; reports no longer conflate system delay with network delay.
- **How to measure:** Unit test comparing histogram vs nearest-rank percentiles on the same sample set (document acceptable bucket error); JFR CPU in `broadcastDelta`; compare `metrics_samples` before/after.

### B12. Simulator: delayed ACK must not block heartbeat + add client-side latency measurement

- **Current state:** `SimAgent.handleAlert` calls `Thread.sleep` directly on the read thread when delaying an ACK (`SimAgent.java:202-208`); heartbeats are sent only when `readFrame` throws `SocketTimeoutException` (soTimeout 2000ms, `SimAgent.java:86,155-157,243-252`). The `mixed` preset delays 300ms + jitter 1200ms (`FaultProfile.java:26`). `SimStats` has no push→ACK latency field.
- **Problem:** (a) With long sleeps (especially a large `--delay-ms` override), the agent sends no HEARTBEAT → server suspects after 3x10s (`ServerConfig.java:62-63`) → "fake" disconnects pollute benchmark results; with default presets (≤1.5s) it is safe, but the limitation must be documented. (b) No client-side latency measurement → only the server clock is available.
- **Solution:** (a) Move delayed ACK sending to another virtual thread, or cap blocking delay at < `heartbeatIntervalMs x maxMissed`; (b) add `pushReceivedAt → ackSentAt` recording to `SimStats` (client-side percentiles) for cross-checking against the server.
- **Expected benefit:** Fault-injection benchmarks produce no disconnect artifacts; two independent measurement sources (client/server) for p50/p95/p99.
- **How to measure:** Run `delayed` with a delay above the threshold: confirm 0 `HEARTBEAT_SUSPECT` audits caused by injection after the fix; compare client vs server percentiles (§6.3) — any systematic offset must be explained (clocks, writer queues).

---

## 3. 3D optimization proposals (F1–F7)

### 3.1 Static estimate of module-scene draw calls (NOT MEASURED — inferred from code structure)

Count the meshes/InstancedMeshes each module creates (1 mesh = 1 draw call in the main pass, excluding shadow/transmission passes):

| Module | Counting method (file:line) | Estimated draw calls |
|---|---|---:|
| structure | 11 slabs (`structure.js:81-85`) + 4 batchesx10 floors (`:116-134`) + 8 podium rings + 2 podium batches (`:142-169`) + 4 core walls + 2 core batches + cap + 2 plinths (`:181-230`) | ~70 |
| facade | 3 batchesx10 floors (`facade.js:118-120`) + ~46 lobby meshes (`:123-187`) + 3 signs (`:235-238`) + 8 parapet/coping (`:245-254`) + 3 beacons (`:263-290`) | ~90 |
| interior | 10 corridors + 40 zone plates (`interior.js:137-160`) + ≤16 batches (`:108-118,228-244`) | ≤66 |
| safety | 5 stair batches (`safety.js:108-112`) + 22 signsx3 meshes (`:144-173`) + 4 extinguisher/sprinkler batches (`:202-228`) + 1 strobe-base batch + **40 individual strobe domes** (`:236-254`) + **44 evacuation route tubes** (`:281-297,348-349`) + ~9 assembly-point meshes (`:303-346`) | ~169 |
| environment | 24 InstancedMesh + ~41 regular meshes (counted from `environment.js:107-411`) | ~65 |
| lighting | 5 emissive InstancedMesh (`lighting.js:145-199`) | 5 |
| statusViz | 40 heatmaps + 40 marker batches + 1 Points (`statusViz.js:219-227,330-342,95-99`) + beacon 3 meshesx(0..40 zones) (`:385-412`) | 81…201 |
| **Total main pass** | | **~546…666** |

Excluding: shadow pass (meshes with `castShadow=true` redrawn into the shadow map — `structure.js:51-52,62-63`, `interior.js:240`, `safety.js:46-47`, `environment.js:63,87`) and the transmission pass of `MeshPhysicalMaterial` (`materials.js:10-11,17`). The current simple twin in `main.js` has about ~66 meshes (40 zones + 11 slabs + roof + beacon + ground + grid + highlight + 10 sprites, `main.js:107-215`) — static estimate.

### F1. Conditionally merge cross-floor instanced batches (reduce draw-call fragmentation)

- **Current state:** structure creates 4 batches/floor x 10 (`structure.js:116-134`), facade 3 batches/floor x 10 (`facade.js:79-121`) — ~70 draw calls for elements identical in geometry/material. The reason for per-floor split: camera cutaway/explode/isolate (`camera.js:8-19`).
- **Problem:** In overview mode (no isolate), those 70 draw calls could be only ~6–10 global batches; repeated CPU submission of draw commands is unnecessary.
- **Solution:** Two representations: global batch (overview) and per-floor batches (when cutaway/explode/isolate is active) — switch on view-mode change; or keep per-floor but enable frustum culling (F2) so off-screen floors cost no submission. Geometry already shares the unit box (`structure.js:38`, `facade.js:42`), so merging is just combining instance matrices.
- **Expected benefit:** Main-pass draw calls drop by ~60 in overview (static estimate from §3.1 table); per-floor isolation ability preserved.
- **How to measure:** `renderer.info.render.calls` in overview vs isolate before/after; visual screenshot comparison; frame time (§6.4).

### F2. Frustum culling for interior & markers (currently disabled intentionally)

- **Current state:** `interior.js:241` sets `im.frustumCulled = false` for EVERY furniture batch (they span the whole tower); `statusViz.js:332` disables culling for per-zone markers; `statusViz.js:97` disables it for Points (reasonable: particles "park" at y=−1e4, `:45`).
- **Problem:** When zoomed into one floor, all interior triangles of all 10 floors are still submitted each frame (estimated tens of thousands of triangles — `interior.js:6` sets a <100k target). The 40 zone markers are also always submitted even when the zone is hidden.
- **Solution:** Split interior batches by floor (the `fg` group already exists, `interior.js:131-134`) + `computeBoundingSphere()` + enable `frustumCulled`; markers: small bounding sphere per zone anchor → enable culling. Beware the known trap: `Box3.setFromObject` on an InstancedMesh returns the UNION of all instances — per-instance footprint checks must loop `getMatrixAt(i)` + `geometry.boundingBox.applyMatrix4`. Trade-off: draw-call count increases (16→up to 160) but submitted triangles drop when zoomed; measure to choose the balance point, or keep global batches + camera-distance LOD.
- **Expected benefit:** Submitted triangles scale with zoom level; lighter GPU work in close-up views (the main interactive mode).
- **How to measure:** `renderer.info.render.triangles` + frame time in overview vs floor zoom, before/after; confirm nothing disappears under isolate/cutaway.

### F3. Batch exit signs / strobes / evacuation routes (~150 fewer draw calls)

- **Current state:** 22 EXIT signs, each with 3 separate meshes (frame/panel/text) = 66 draw calls (`safety.js:144-173`); 40 strobe domes, each with 1 mesh + 1 private material for independent blinking (`safety.js:236-254`), updating 40 lights every frame (`:373-385`); 44 `TubeGeometry` pipes sharing one material but split into 44 meshes (`safety.js:268-297,348-349`).
- **Problem:** ~150 draw calls for static or color-only elements; the largest draw-call cluster of the module scene (§3.1 table).
- **Solution:** (a) EXIT signs → 3 InstancedMesh (frame/panel/text) with per-sign matrices; (b) strobes → 1 InstancedMesh + blinking `instanceColor` (MaterialBasic/`onBeforeCompile` for per-instance emissive; accepting color-intensity changes if keeping the current material, since per-instance is otherwise impossible); (c) static evacuation routes → `mergeGeometries` into 1 mesh (pattern already in repo: `interior.js:8,30`). Keep `userData.instances` for tooltips (`safety.js:48-50`) by storing parallel metadata.
- **Expected benefit:** ~150 draw calls → ~5 (static estimate); also cheaper strobe CPU update (1 color buffer instead of 40 materials).
- **How to measure:** `renderer.info.render.calls` before/after; verify blinking for critical zones still behaves correctly (`safety.js:360-386`); visual screenshot comparison.

### F4. Texture atlas for canvas signs & floor labels

- **Current state:** Each sign/label has its own canvas texture: roof signs 2048x128 (`facade.js:190-213`), EXIT 128x48 (`safety.js:138`), assembly points 256x128 (`safety.js:327`), 10 floor labels 128x64 in the simple twin (`main.js:138-153,185`). Each texture brings a private material → separate binds, and blocks instancing (F3a needs a shared material).
- **Problem:** Many small textures = many binds/material switches, memory cost, no batch merging; text re-rendered on every build.
- **Solution:** One canvas atlas (e.g. 1024x1024, power of two, mipmaps, anisotropy 4 as currently `facade.js:209`) drawing all labels once at build; one shared `MeshBasicMaterial`; each sign uses UV offsets (edit geometry UVs or instance attributes). Dispose the atlas once (current resource-ownership rules: `facade.js:304-315`, `safety.js:388-395`).
- **Expected benefit:** Fewer texture/material binds; unlocks F3a; lower texture memory (static estimate: ~15 separate textures → 1).
- **How to measure:** `renderer.info.memory.textures`, `renderer.info.programs.length`, draw calls before/after; check text legibility at distance (mipmaps).

### F5. Transmission glass + shadow map: material LOD and static shadows

- **Current state:** `glass`/`glassDark` are `MeshPhysicalMaterial` with `transmission 0.6/0.4` (`materials.js:10-11`), water `transmission 0.3` (`materials.js:17`). Three.js must render an extra **transmission pass** (draw the opaque scene into a render target) when transmission materials are present. Shadows: 1 DirectionalLight, 2048² map, PCFSoft, ortho ±65m (`lighting.js:75-83`, `main.js:77-78,99`); nearly every solid mesh has `castShadow=true` (`structure.js:51-52,62-63`, `interior.js:240`, `safety.js:46-47`). The sun only moves during day/night cross-fade (`lighting.js:224-250,260-267`).
- **Problem:** In overview, glass covers most of the facade (30 glass batches + 10 dark-spandrel batches, `facade.js:118-119`) → the transmission pass nearly redraws the whole scene every frame; the shadow map redraws all castShadow meshes each frame even though the scene is static (estimate: shadow + transmission passes can lift total submissions from ~550–670 to over 1000 draw calls/frame — inferred from structure, not measured).
- **Solution:** (a) Material LOD: overview/night use a transparent opacity-only `MeshStandardMaterial` glass variant; enable transmission only when zoomed into the lobby; (b) `renderer.shadowMap.autoUpdate=false` + `needsUpdate=true` only during day/night cross-fade or an animating explode/cutaway (the rest of the scene is static); (c) tighten the shadow camera to the tower footprint (~±35m) or use 2 cascades for better useful texel density; (d) keep the 12 point-light ceiling (already in place, `lighting.js:13,125-128`).
- **Expected benefit:** Remove one full-scene pass in normal mode plus most shadow passes in static state → sharply lower draw calls/frame and GPU time (quantified by §6.4, no promise in advance).
- **How to measure:** Frame time + `renderer.info.render.calls` with transmission on/off and shadow autoUpdate on/off; visual day/night comparison; measure at DPR 1 and 2.

### F6. Reduce per-frame CPU: dirty flags for world-matrix transforms & zone/strobe loops

- **Current state:** `statusViz.update` loops 40 zones every frame: `syncEntry` calls `mesh.updateWorldMatrix(true,false)` + walks the parent tree checking visibility + copies the anchor transform (`statusViz.js:547-568,596-621`); `safety.update` rebuilds `criticalSet` from status + loops 40 strobes every frame (`safety.js:360-386`); roof beacon pulse every frame (`facade.js:298-302`).
- **Problem:** Most of this work is only needed when (a) status changes or (b) the camera is animating explode/cutaway/fly-to (`camera.js:658-668`); repeating it every frame wastes CPU on weak/integrated-GPU machines and causes frame-time jitter.
- **Solution:** Dirty flags: run `syncEntry` only when the source transform changes (event from the camera module or matrix comparison) or on `setStatus`; cache visibility; update `criticalSet` only in `setStatus`; blink strobes via 1 `instanceColor` buffer (same as F3b) instead of 40 materials. Keep particle integration every frame (they genuinely move, `statusViz.js:455-483`).
- **Expected benefit:** Lower frame CPU when the scene is static; more stable p95 frame time; no visual behavior change.
- **How to measure:** `performance.now()` around update loops (Chrome Performance panel), compare p50/p95 frame time before/after in static and explode modes.

### F7. Draw-call budget + `renderer.info` measurement (and assembler integration)

- **Current state:** `CONTRACT.md:25` sets only a triangle budget (<300k), no draw-call budget; build-time triangle counting exists (`environment.js:414-430`, `interior.js:247-253`), `statusViz.getStats` exposes zone/beacon/particle counts (`statusViz.js:637-650`). `main.js` has assembled the modules via `safeBuild()` (`main.js:143-165`); a `renderer.info` HUD is still absent.
- **Problem:** No permanent draw-call/frame-time observability → none of F1–F6 can be verified; the module scene (~550–670 estimated draw calls, §3.1) is now running instead of the ~66-call twin — a budget is needed to stay within target-machine capability.
- **Solution:** (a) Add an HUD/overlay measuring `renderer.info.render.calls/triangles`, `memory.textures/geometries`, `programs.length` + a ring buffer of p50/p95/p99 frame time (reuse the `reports.js` ring-buffer pattern); (b) set a main-pass draw-call budget (proposed starting point ≤200 in overview — **a target to be calibrated by measurement, not a measured result**); (c) benchmark the current module scene, comparing with the simple twin only when needed (§6.4).
- **Expected benefit:** Every 3D proposal has verifiable figures; performance regressions detected as soon as modules are added.
- **How to measure:** That HUD itself; the §6.4 procedure; record results in the empty §6.5 tables.

---

## 4. Proposal summary & priority order

| Code | Proposal | Expected impact | Effort | Invariant-break risk |
|---|---|---|---|---|
| B1 | Eliminate O(N²) openLedger on enqueue | Very high (wide publish) | Low | Low (ledger semantics unchanged) |
| B2 | Index tracked by device | High (reconnect storm) | Low | Low |
| B7 | Isolate slow WS subscribers | High (system stability) | Medium | Low (new policy, TLS untouched) |
| B4 | Merge dispatch transactions | High (fanout) | Medium | **S-02: commit-before-offer must hold** |
| B3 | SQLite connection pool | High (all DB paths) | Medium | Medium (connection thread-safety) |
| B9 | Eliminate N+1 + batch audit | Medium–high | Medium | Security audit stays synchronous |
| B5 | Cap concurrent Argon2 | High under storm | Low | Hash parameters not lowered |
| B6 | Arithmetic delta diff | Medium | Medium | status/1 contract unchanged |
| B8 | synchronous=NORMAL (lab) | Medium | Low | Durability trade-off on power loss — document |
| B10 | Observe EVICTED_LOWER | Medium (measurement) | Low | Disconnect policy unchanged |
| B11 | Percentile histogram | Low–medium | Low | Keep nearest-rank + monotonic |
| B12 | Simulator delay/heartbeat + client measurement | Medium (measurement quality) | Low | Fault semantics unchanged |
| F3 | Batch signs/strobes/routes | Very high (3D, ~150 calls) | Medium | Keep tooltip metadata |
| F5 | Glass LOD + static shadows | Very high (3D, extra passes) | Medium | Day/night visuals must be compared |
| F2 | Frustum culling for interior/markers | High when zoomed | Medium | InstancedMesh union-bounds trap |
| F1 | Conditional cross-floor batches | Medium–high | Medium | Keep isolate/cutaway |
| F4 | Texture atlas | Medium | Low | Resource dispose rules |
| F6 | Dirty-flag updates | Medium | Low | No behavior change |
| F7 | Budget + measurement HUD | Measurement prerequisite | Low | Additive |

Recommended execution order: **F7/B10 (observe first) → B1 → B2 → B7 → B4/B3 → B9/B5/B6/B8/B11/B12 → F3 → F5 → F2/F1/F4/F6**. Principle: measure before changing, change one proposal at a time, rerun the module's acceptance test suite (running tests is outside this document's scope).

---

## 5. Invariants to preserve during optimization (summary from review rounds)

1. **S-02:** attempt + SENT committed BEFORE the frame enters the writer queue; `updateDispatchState` has a guard `state IN (PENDING,SENT,RETRY_SCHEDULED)` (`FanoutEngine.java:242-251`, `DeliveryRepo.java:235-247`).
2. **S-01:** `recordAck` returns the inserted flag; `ON CONFLICT DO NOTHING`; ACK must match (deviceId, alertId, revision) (`DeliveryRepo.java:150-224`).
3. **S-10:** FAILED is terminal for the retry budget; recovery does not consume a new attempt (`FanoutEngine.java:230-236`).
4. **Pragma order:** FK → busy_timeout → WAL, serialized per-file at initialization (`Db.java:54-58,50-51`); do not reorder.
5. **Monotonicity:** zone counters and delivery state only advance (`StatusFeedService.java:33-37`, rank guard `DeliveryRepo.java:198-211`); the dashboard relies on monotonicity to spawn particles (`statusViz.js:536-541`).
6. **Enqueue after commit** (`TransactionalPublisher.java:110-111`); fanout only after UPDATE/CANCEL commits (`ServerStore.java:312-318,359-363`).
7. **Resource limits:** maxConnections 512, pre-auth TTL, WS cap 64, frame-size guard (§1.1) — never relaxed for performance.
8. **3D:** dispose only resources the module owns, never the shared materials library (`facade.js:304-315`, `environment.js:434-447`, `safety.js:388-395`); pad floor mesh names as `F05` to match zoneCode.

---

## 6. Benchmark plan: 100 → 200 → 500 clients

### 6.1 Environment & configuration

- One macOS machine (server + simulator + dashboard on the same host — note the limitation in §6.6), JDK ≥24 (`pom.xml:19`), build with `mvn -q -DskipTests package` (run by the executor; this document does not run builds).
- Server defaults (`ServerConfig.Builder`, `ServerConfig.java:56-74`): TLS 9443, WS 9444, heartbeat 10s/3 missed, retry 3s/6s/12s max 3, writer queue 256, statusBatch 500ms, devicesPerZone 12 (→ 480 seeded devices), Argon2 t=2/m=16MiB/p=1, preAuth TTL 10s, **maxConnections 512**.
- CLI note: `ServerMain` exposes only `--db --keystore --storepass --tls-port --ws-port --building --devices-per-zone --no-seed --heartbeat-ms --retry-base-ms` (`ServerMain.java:35-52`); knobs `statusBatchMs/maxConnections/argon2` are Builder-only → the benchmark uses defaults, or a separate test harness if variation is needed.
- **500 agents + operator sit close to the 512 cap**; reconnect churn can create overlapping pre-auth sessions → if the cap is hit, reduce to 480 agents or raise the cap via a harness (record the configuration used in the results).

### 6.2 Provisioning & load scenarios

**Simulator device provisioning (mandatory):** `SimAgent` sends `deviceCode = SIM-DEV-0001…` (`SimulatorMain.java:71`, `SimAgent.java:137`) and `AuthService.authenticateDevice` looks up `device_code` (`AuthService.java:37`); the default seed creates only `DEV-A-F%02d-%s-%02d` (`ServerStore.java:675`). You must pre-create N devices with matching codes, using the shared hash of `device-demo-secret` (all demo devices share 1 hash, `ServerStore.java:637`), assigned to floors/zones so the status feed counts correctly:

```sql
-- Example: create 500 devices SIM-DEV-0001..0500, evenly distributed over 10 floors x 4 zones,
-- copying credential_hash from an already-seeded demo device (run after the server's first seed).
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i < 500)
INSERT INTO devices(id, floor_id, device_code, credential_hash, enabled, enrolled_at)
SELECT lower(hex(randomblob(16))),
       (SELECT id FROM floors ORDER BY floor_number LIMIT 1 OFFSET (i-1)/50),
       'SIM-DEV-' || printf('%04d', i),
       (SELECT credential_hash FROM devices WHERE device_code='DEV-A-F01-A-01'),
       1, strftime('%Y-%m-%dT%H:%M:%fZ','now')
FROM n;
-- Assign the corresponding zone_devices so deviceInfoMap/zonesByFloor count correctly (StatusFeedService.init).
```

Run the simulator with `--device-secret device-demo-secret` (matching the copied hash), `--truststore certs/aegis-truststore.p12 --truststore-password aegis-demo` (`scripts/gen-server-cert.sh`). Fleet: `--agents 100|200|500 --seed 42 --stagger-ms 5` (defaults, `SimulatorConfig.java:33-34`), `--duration 300` for the measurement window.

**Fault scenarios** (`FaultProfile.java:21-26`): `clean` (baseline), `mixed` (drop 0.15, delay 300+jitter1200ms, dup 0.3x2, disconnect 0.2), `lossy` (drop 0.3), `flaky` (disconnect 0.3 — reconnect storm).

**Publish load** (real operator or harness using `aegis-client`/test client):
- F1: zone publish (12 targets), rate 1 command/10s.
- F2: floor publish (48 targets), 1 command/10s.
- F3: building-wide publish (480 targets), 1 command/30s + a burst of 3 consecutive commands (measures B1/B4).
- F4: alternating ALERT_UPDATE/CANCEL (measures supersede + tombstone, `FanoutEngine.java:197-205`).

**Procedure per tier 100/200/500:** fresh DB (`scripts/initdb.sh` + seed) → start server → ramp fleet (5ms stagger) → 60s warm-up → 300s measurement window → 3 repetitions/scenario → record medoid + variation range. Never mix scenarios within one measurement window.

### 6.3 Metrics & collection (no invented numbers — result tables left empty)

**Server side:**
- `metrics_samples`: `delivery_latency_p50/p95/p99`, `transport_retries` (30s flush, `AegisServer.java:177-186`; schema `002_showcase_expansion.sql:121`). Semantics: end-to-end publish→ACK_RECEIVED (`FanoutEngine.java:377-382`) — include the B11 clarification.
- Monotonic counters: published/ackReceived/ackUser/failed/retry/slowClientDisconnects (`MetricsRegistry.java:63-69`) — periodic dump via harness (public getters).
- Pure transport latency (SQL, no code change): compare `device_acks.acked_at` with `delivery_attempts.attempted_at` of the last SENT attempt (schema `001_base_schema.sql:13-14`; fixed-width 9-digit-nanosecond timestamps so TEXT comparison follows time order):

```sql
SELECT d.id,
       (julianday(a.acked_at) - julianday(t.attempted_at)) * 86400000 AS transport_ms
FROM device_acks a
JOIN deliveries d ON d.id = a.delivery_id
JOIN delivery_attempts t ON t.delivery_id = d.id AND t.outcome = 'SENT'
WHERE a.ack_kind = 'RECEIVED'
  AND t.attempted_at = (SELECT MAX(attempted_at) FROM delivery_attempts
                        WHERE delivery_id = d.id AND outcome = 'SENT');
-- Compute p50/p95/p99 outside (Python/SQL percentile) over the measurement window.
```

- Audit: count `SLOW_CLIENT_DISCONNECT`, `HEARTBEAT_SUSPECT`, `DELIVERY_FAILED`, `RETRY`, `DUPLICATE` per window (`audit_events`, `AuditRepo.java:54-67`).
- Queues: scheduler depth (`PriorityScheduler.size()`), per-session `evictedCount`/`size` (`BoundedWriterQueue.java:75-76`) — before B10, read only via harness/test.
- SQLite: `-wal` file size, BUSY count in logs, (after B8) checkpoint statistics.
- System: JFR (`jdk.CPULoad`, `jdk.GCPhasePause`, `jdk.VirtualThreadPinned`), RSS, async-profiler wall/CPU if needed.

**Simulator side:** `SimStats` summary printed at session end (`SimulatorMain.java:50`): connectSuccess/connectFailures/authRejections/reconnects/syncRequests/alertsReceived/duplicatePushes/acksSent/acksDropped/acksDelayed/duplicateAcks/disconnects/heartbeats/agentsFinished. (After B12: add client-side push→ACK percentiles.)

**Dashboard side:** cadence + byte size of STATUS_DELTA (counted in `ws.js`), subscriber count; 3D per §6.4.

### 6.4 3D benchmark (in parallel with the backend benchmark)

- Current configuration: the `src/three` module scene is already assembled in `main.js` via `safeBuild()` (§1.2); use a dedicated page harness per `CONTRACT.md:28-29` only to measure an isolated module.
- Fixed views: `overview`, zoom to 1 floor (`flyToFloor`), `explode`, `isolateFloor` (`camera.js:8-19`); DPR 1 and 2 (`main.js:75`); day and night (`lighting.js:252-257`).
- Per configuration: 60s camera orbit along a fixed trajectory; collect `renderer.info.render.calls/triangles`, `memory.textures/geometries`, `programs.length` after each frame; frame time via `performance.now()` → p50/p95/p99 (ring buffer, `reports.js` pattern).
- A/B variants for F5: transmission on/off, shadow `autoUpdate` on/off; for F2: culling on/off; for F3/F4: before/after batching/atlas.
- Reference targets (NOT measured results): p95 frame time ≤ 16.7ms at DPR1 on the target machine for overview mode; starting draw-call budget ≤200 (F7) — calibrate with real numbers.

### 6.5 Result tables (EMPTY — fill after real runs)

| Tier | Scenario | p50 (ms) | p95 (ms) | p99 (ms) | retries/100 deliveries | FAILED | slow-client disc. | heartbeat suspect | server CPU % | RSS (MB) |
|---|---|---|---|---|---|---|---|---|---|---|
| 100 | clean | | | | | | | | | |
| 100 | mixed | | | | | | | | | |
| 200 | clean | | | | | | | | | |
| 200 | mixed | | | | | | | | | |
| 500 | clean | | | | | | | | | |
| 500 | mixed | | | | | | | | | |
| 500 | flaky | | | | | | | | | |

| 3D configuration | View | DPR | draw calls | triangles | frame p50 (ms) | frame p95 (ms) | frame p99 (ms) | textures |
|---|---|---|---|---|---|---|---|---|
| simple twin | overview | 1 | | | | | | |
| simple twin | overview | 2 | | | | | | |
| module scene | overview | 1 | | | | | | |
| module scene | floor zoom | 1 | | | | | | |
| module scene | explode | 1 | | | | | | |

### 6.6 Pass criteria & threats to validity

**Pass criteria (thresholds inferred from design, not measurements):**
- `clean`: 0 `SLOW_CLIENT_DISCONNECT`, 0 `FAILED`, 0 retries (all ACKs within the initial 3s window — `RetryPolicy` base 3000, `ServerConfig.java:64`); p99 publish→ACK < 3s.
- `mixed`: retry rate consistent with dropRate 0.15 (sampling error allowed); no `HEARTBEAT_SUSPECT` caused by delay injection (B12); duplicate ACKs handled idempotently (`DUPLICATE` count matches simulator `duplicateAcks`).
- Tier increase 100→200→500: p99 grows sublinearly with client count; scheduler queue drains between publishes; connection cap not hit unexpectedly.
- 3D: achieve the §6.4 frame-time target after optimization; draw calls within the F7 budget.

**Threats to validity (must be recorded in the results report):** one machine (client+server compete for CPU/IO), TLS loopback not representative of a real network, Argon2 cost on reconnect, GC pauses, fixed seed 42 (deterministic faults — good for reproduction, does not cover real fault distributions), client/server clock skew when comparing cross-side percentiles.

---

## 7. References (source code read)

- `aegis-server`: `AegisServer`, `TlsGateway`, `ClientSession`, `BoundedWriterQueue`, `FanoutEngine`, `MessageRouter`, `AuthService`, `StatusFeedService`, `StatusFeedServer`, `WsConnection`, `MetricsRegistry`, `ServerStore`, `SessionRegistry`, `ServerConfig`, `ServerMain`, `SyncService`, `AlertService` (publish path).
- `aegis-persistence`: `Db`, `TransactionalPublisher`, `DeliveryRepo`, `AuditRepo`, `db/001_base_schema.sql`, `db/002_showcase_expansion.sql`.
- `aegis-core`: `PriorityScheduler`.
- `aegis-protocol`: `FrameCodecImpl`.
- `aegis-simulator`: `SimulatorMain`, `SimAgent`, `SimulatorConfig`, `FaultProfile`, `SimStats`.
- `aegis-dashboard`: `src/main.js`, `src/three/{CONTRACT.md, config, materials, structure, facade, interior, safety, environment, lighting, statusViz, camera}.js`.
- `pom.xml` (Java release 24, sqlite-jdbc 3.46.1.0); `scripts/initdb.sh`, `scripts/gen-server-cert.sh`; `docs/DEMO-RUNBOOK.md`, `docs/SECURITY.md`.

*This document only analyzes and proposes; it does not modify source code, run builds/tests, or commit. All real performance figures will be filled into §6.5 after executing the benchmark plan.*
