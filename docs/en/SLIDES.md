Language: English | Vietnamese original: docs/SLIDES.md

# AEGIS-EBS — Defense slide outline (12 minutes)

**Course:** CMP180 — Computer Network Programming · **Project:** AEGIS Emergency Broadcast System (Showcase Edition)
**Presentation duration:** 12 minutes (720 seconds) · **Q&A:** after the presentation (section §4).
**Basis:** Blueprint `Plan/AEGIS_MASTER_BLUEPRINT.md` §14 (12-minute demo runbook), §15 (rubric), §16 (Q&A); `docs/DEMO-RUNBOOK.md`; `docs/TEST-PLAN.md`; `docs/PROTOCOL.md`; `docs/SECURITY.md`.

> ⚠️ **SIMULATION / TRAINING.** Every slide and demo must carry the simulation label. **Do not fill in assumed measurements**: `[TODO: real figures]` cells must be replaced with real evidence including **runId, commit SHA, machine configuration, timestamp** (NFR-018). Figures are valid only when captured from a runnable test.

---

## 1. Presentation principles

- **CMP180 assessment focus** (rubric §15): self-built application protocol over TCP/UDP in Java (25%), reliability/concurrency under faults (25%), architecture/SRS/schema/protocol (15%), security/authorization (10%), dashboard/UX (10%), measurement/testing/reproducibility (10%), documentation/presentation (5%). The outline allocates time according to these weights.
- **Slides + live demo in parallel:** the slides provide the narrative; the demo actually runs per `DEMO-RUNBOOK.md`. Each slide states the *demo action* and the *evidence to show*.
- **Fallback** (blueprint §14): if the venue network blocks multicast or the source/network is unstable → use **video recorded from a real run** + a **deterministic simulator** running locally. Distinguish clearly between *recording* and *live*.
- **No overclaiming:** no "exactly-once network", no "HA", no "CAP compliant" without schema validation, no "life-safety system".

---

## 2. Timeline of slides (total 12:00)

| # | Slide | Time window | Duration | Supporting runbook §14 | Rubric |
|---|---|---|---|---|---|
| 1 | Title & disclaimer | 0:00–0:20 | 20s | System reveal | Presentation |
| 2 | Problem | 0:20–1:10 | 50s | System reveal | Architecture |
| 3 | Objectives & scope | 1:10–1:40 | 30s | Command authority | Architecture |
| 4 | Demo-flow overview | 1:40–2:10 | 30s | Command authority | UX |
| 5 | Four-plane architecture | 2:10–3:00 | 50s | Incident launch | Architecture |
| 6 | EBSP/2.0 — framing & envelope | 3:00–3:50 | 50s | Incident launch | Protocol |
| 7 | EBSP/2.0 — handshake & catalog | 3:50–4:20 | 30s | Digital twin live | Protocol |
| 8 | Reliability design | 4:20–5:20 | 60s | Fault injection | Reliability |
| 9 | Reliability evidence (real figures) | 5:20–6:20 | 60s | Offline recovery | Measurement |
| 10 | Chaos results C01–C16 | 6:20–7:30 | 70s | Priority / restart | Reliability |
| 11 | Security — threat model & controls | 7:30–8:30 | 60s | Server restart | Security |
| 12 | Security — RBAC & TLS truststore | 8:30–9:15 | 45s | LAN lab | Security |
| 13 | Network lab: TCP vs multicast | 9:15–10:00 | 45s | LAN lab | Protocol/Measurement |
| 14 | Limitations | 10:00–10:40 | 40s | Results | Honesty |
| 15 | Conclusion & future work | 10:40–11:15 | 35s | Results | Architecture |
| 16 | CMP180 technical summary + lead-in to Q&A | 11:15–12:00 | 45s | Technical summary | Java fundamentals |

*(Total duration = 720s = 12:00.)*

---

## 3. Content of each slide

### Slide 1 — Title & disclaimer (0:00–0:20)
- **Title:** AEGIS — Emergency Broadcast System for office buildings · CMP180 Capstone.
- Team: AEGIS-EBS. Members / student IDs: Nguyen Ngoc Tinh (2380614851) · Bui Thanh Long An (2380600103) · Nguyen Hoang Tung (2380602485) · Vo Thanh Dong (2380600518). Class/Cohort: SE 2023–2027 · Advisor: Nguyen Van Can (ThS.) · Academic year 2025–2026.
- **Mandatory line:** `SIMULATION / TRAINING — does not replace certified fire alarm/evacuation systems.`
- **Demo:** show the 3D dashboard with 10 floors, green connection, TLS/version label (system reveal). The navigation bar has **6 tabs** (instead of the old 5): **Twin · Incidents · Chaos · Reports · Funnel · Operator** — the Operator tab publishes/cancels real alerts and starts/stops the device fleet.
- **Web-only demo (recommended for the defense):** `bash scripts/demo-web.sh` → `http://localhost:5173` → Operator tab: Fleet Start (20) → Publish FIRE_DRILL F05 (red zone + ACKs on the LIVE dashboard) → Retry (1 silent device, `FAILED` after 3s/6s/12s) → Cancel; HTTP bridge `http://localhost:9445`. No new figures added; LIVE screenshots are stored at `docs/evidence/dashboard/`.
- **Note:** open decisively, state the simulation limitation immediately to frame everything honestly.

### Slide 2 — Problem (0:20–1:10)
- **Problem:** an office building needs to broadcast emergency alerts **reliably, within the correct scope, with acknowledgement** to many devices on a LAN; the network may drop packets/ACKs, clients may go offline, the server may crash.
- **Gap:** plain broadcast cannot prove *the right device received it*, cannot distinguish *machine receipt* from *human acknowledgement*, cannot prevent duplicates on retry, and cannot enforce floor-level permissions.
- **Design question:** how to self-build an application protocol on TCP/UDP (Java) achieving: priority, two-layer ACK, idempotent retry, reconnect, crash recovery, RBAC, audit, reliable measurement?
- **Note:** stress that this is a *reliable distribution + incident command* problem, not merely "send a message".

### Slide 3 — Objectives & scope (1:10–1:40)
- **Objectives (P0):** server broadcasts alerts to many LAN clients; priority; ACK_RECEIVED ≠ ACK_USER; idempotent retry; reconnect; crash recovery; scoped RBAC; audit.
- **Scope:** 1 LAN server; JavaFX client + headless simulator (100 clients mandatory, 200–500 stretch); 3D web dashboard; **single-node, no HA**.
- **Personas:** System Admin, Emergency Operations Controller, Security Operator, Floor Manager, Employee, Device Agent, Instructor/Observer.
- **Demo:** split-window operator vs unauthorized floor-manager → `DENIED` + audit (command authority).
- **Note:** state acceptance targets **not yet measured** at design time; real figures are shown on slide 9.

### Slide 4 — Demo-flow overview (1:40–2:10)
- **Diagram of the 12-minute demo flow** (11 runbook segments): reveal → command authority → incident launch → digital twin → fault injection → offline recovery → priority → restart → LAN lab → results → technical summary.
- **Message:** each segment demonstrates one system property, with evidence (log/report/screenshot).
- **Demo:** quickly switch through the windows to be used (dashboard, client, chaos panel, audit).
- **Note:** helps the committee follow the flow; no technical depth on this slide.

### Slide 5 — Four-plane architecture (2:10–3:00)
- **Command plane:** dashboard login, user/scope checks, incident & alert create/update/cancel.
- **Delivery plane:** authenticated Java Socket TLS sessions, bounded writer queue, EBSP frames, retry, ACK.
- **Persistence/observability plane:** SQLite WAL (single-writer), incident event log, audit, metrics, read-only replay.
- **Discovery/experiment plane:** UDP discovery + multicast hints on an isolated LAN (untrusted).
- **Module diagram:** `aegis-protocol → aegis-core → aegis-persistence → aegis-server → aegis-client/simulator/network-lab/dashboard`.
- **Demo:** incident launch FIRE_DRILL on floor 5 + neighboring zone, CAP metadata, publish → dashboard shows target count.
- **Note:** emphasize how the modular monolith keeps technical depth within 12 weeks; network I/O outside DB transactions.

### Slide 6 — EBSP/2.0: framing & envelope (3:00–3:50)
- **Framing (byte contract):** `uint32 BE length L` + `L bytes strict UTF-8 JSON`, `L ∈ [1, 65536]`. Reject oversize **BEFORE** allocation; `readFully` handles partial/coalesced reads.
- **Envelope:** `{version:2, type, messageId, correlationId, sentAt(UTC ISO-8601), payload}`.
- **Validation:** strict UTF-8 (REPORT), JSON objects only, no trailing tokens, `version==2`, `type` in enum, required fields non-blank.
- **Idempotency identifiers:** CREATE uses `requestId`; delivery has stable `deliveryId + alertId + revision`; each transport attempt has a new `messageId`.
- **Source code:** `aegis.protocol.{Protocol, FrameCodecImpl, Envelope, MessageType}` (56 tests).
- **Demo:** show a real frame (hex/JSON) from the log; point out the length prefix + body.
- **Note:** this is the "self-built protocol" part — 25% weight; speak slowly and clearly about the byte contract.

### Slide 7 — EBSP/2.0: handshake & message catalog (3:50–4:20)
- **Handshake:** TLS handshake (verify truststore + hostname) → `HELLO{version,deviceId,nonce}` → `SERVER_HELLO{serverId,challenge}` → `AUTH_DEVICE/AUTH_OPERATOR` → `AUTH_RESULT{sessionId,permissions}` → `SYNC_REQUEST/SYNC_RESULT` → `HEARTBEAT`.
- **Catalog groups:** Handshake/Identity · Commands (INCIDENT_*, ALERT_*) · Delivery/Client (ALERT_PUSH, ACK_*, SYNC_*, HEARTBEAT) · Dashboard (STATUS_*) · Errors (typed codes).
- **Principle:** `sessionId` is not authorization evidence outside the authenticated connection; the server derives scope from the DB.
- **Demo:** digital twin live — floor changes color, 3 real clients + 100 simulator agents reflecting the target.
- **Note:** show the abbreviated catalog table; emphasize version negotiation + stale revision suppression.

### Slide 8 — Reliability design (4:20–5:20)
- **Two-layer ACK:** `ACK_RECEIVED` (durable local storage) ≠ `ACK_USER` (human gesture). The client writes its SQLite inbox (WAL + synchronous=FULL) **BEFORE** sending ACK_RECEIVED.
- **Retry:** 3s/6s/12s + jitter, at most 3 retries then `FAILED` (`RetryPolicy`, `FanoutEngine`); each attempt has a new `messageId`, same `deliveryId`.
- **Dedup:** `(alertId, revision, deviceId)` → exactly 1 displayed popup (NFR-005); `DedupIndex` blocks stale dispatch; `InboxStore` monotonic revision (STORED/DUPLICATE/STALE).
- **ACK only advances state:** rank guard `PENDING < SENT/RETRY < RECEIVED < ACK/FAILED < EXPIRED/CANCELLED` (`DeliveryRepo.recordAck`, `AckLedger`).
- **Crash recovery:** transactional outbox — commit then enqueue; `RecoveryScanner` rebuilds PENDING/SENT/RETRY_SCHEDULED/FAILED after restart.
- **Tombstone:** cancel/expire do not resurrect old alerts on sync/retry.
- **Demo:** fault injection — drop ACK of 1 client → retry + non-duplicate popup + attempt trace.
- **Note:** state the §5 invariants; this is the 25% reliability weight.

### Slide 9 — Reliability evidence (real figures) (5:20–6:20)
- **Acceptance targets vs measured results (runId `276dedf5`):** NFR-003 p95 dispatch→ACK_RECEIVED ≤ 2s → **measured 1,307s (pass)**; NFR-004 > 99% ACK_RECEIVED within 10s → **measured 100% (pass)**; NFR-005 exactly 1 popup → **0 duplicate push/ACK**; NFR-006 no committed row lost → **100 durable deliveries, FK clean**. (NFR thresholds from docs/BAO-CAO.md §3.1.)
- **Table of real results** (runId/SHA/machine stated in the table header):

| Metric | Definition | Real result (runId `276dedf5`, commit `c1dbab5`, M3 Pro) |
|---|---|---|
| p50 / p95 / p99 (ms) | `ackReceipt − dispatchStart` monotonic, same process | **773 / 1307 / 1334** (100 samples) |
| ACK_RECEIVED rate | received/targeted (fleet 100, clean, seed 42) | **100% (100/100)** |
| Retry volume | attemptCount−1 / delivery | **0** (attemptHistogram `{1:100}`) |
| Throughput | 100 deliveries completed in ~1,34 s | **≈75 deliveries/s** (CPU/RSS not captured) |
| Duplicate popups | duplicate push/ACK | **0 duplicate push** (clean); chaos duplicate 22 ACKs deduped, no duplicate popup |

- **Percentile method:** server-side monotonic timestamps (dispatch & receipt) per delivery; timeouts/omissions reported separately to avoid successful-sample bias (MetricsRegistry.recordLatency). Evidence: `docs/evidence/benchmark/276dedf5…/summary.json` + bench.db/server.log/sim.log.
- **Demo:** offline recovery — disconnect/reconnect → active-alert sync, old revision hidden; show dashboard metrics. Chaos flaky (runId `8e92ca3c`) measured 21 disconnects → 21 reconnects, ACK still 100%.
- **Note:** the figures above come from real runs, not inference; if CPU/RSS/mixed load were not measured, say "not measured".

### Slide 10 — Chaos results C01–C16 (6:20–7:30)
- **Chaos Lab:** deterministic seeded fault injection, automatic reports; compare baseline vs faulty run.
- **Table of real results** (`docs/evidence/chaos/*.json`, fleet 50, seed 42, M3 Pro/Java 24.0.1):

| ID | Injected fault | Expected property | Evidence |
|---|---|---|---|
| C01 | Drop ACK_RECEIVED once | Retry; alert appears exactly once | **MEASURED** 15 drops → 15 retries, ACK 98% (49/50), 1 FAILED within budget; `lossy.json` e1943406 |
| C02 | Delay ACK 5s | Correct retry/backoff | **MEASURED** 50 delayed ACKs, ACK 100%, 0 retries; `delayed.json` d04af662 |
| C03 | Duplicate ALERT_PUSH | Idempotent inbox; re-ACK | **MEASURED** 22 ACKs deduped, ACK 100%; `duplicate.json` 04c97420 |
| C04 | Disconnect 20% of agents | Remaining targets not blocked | **MEASURED** 21 disconnects→21 reconnects, ACK 100%; `flaky.json` 8e92ca3c |
| C05 | Reconnect 20% of clients | Active sync, stale blocked | **MEASURED** mixed 13 reconnects, ACK 100%; `mixed.json` 3fe99b11 |
| C06 | Kill server after commit, before send | Pending work recovered | Unit/integration: `RecoveryScannerTest` 4 + `TlsLoopback` crashRecovery |
| C07 | Kill after socket write, before ACK commit | Resend, no duplicate display | `ClientEngineTest` 20 (pending ACK resend) |
| C08 | Floor manager publishes out of scope | DENIED + audit | `ServerSecurityFixesTest` 12 + `ScopedRBACTest` 13 |
| C09 | Fake/unknown TLS cert | Connection rejected | `ClientTlsSmokeTest` 2 + review-c probe |
| C10 | Oversize frame, invalid UTF-8/JSON | Reject, no crash/unbounded alloc | `FrameCodecValidationTest` 23 |
| C11 | 100 clients receive CRITICAL during INFO burst | Critical expedited; fairness | `PrioritySchedulerTest` 13 + 5000-message flood probe |
| C12 | Cancel while client offline | Tombstone delivered on reconnect | `TlsLoopback` cancel tombstone + `ClientEngineTest` |
| C13 | Forged UDP server response | Not trusted before TLS verification | `DiscoveryTrustTest` 6 + `DiscoveryNonceEchoTest` 6 |
| C14 | Multicast blocked | TCP primary still works | `UnicastFallbackTest` 2 + benchmark in 3 modes (−87,5% packets) |
| C15 | Temporary SQLite writer contention | Bounded retry; consistent persistence | `DbTest` 3 + `PeerReviewFixesTest$ConcurrentFirstOpen` |
| C16 | Slow client / full writer queue | Other clients continue; slow target evident | `BoundedWriterQueueTest` 6 + `TlsLoopback` slowClient |

- **Demo:** priority preemption (INFO burst → CRITICAL) + server restart recovering deliveries from DB.
- **Note:** every scenario must have seed + run command + evidence; never claim "passed" without a real run.

### Slide 11 — Security: threat model & controls (7:30–8:30)
- **8 main threats** (docs/SECURITY.md §2): rogue LAN spoof discovery · stolen device credential · session replay · role escalation · out-of-scope broadcast · malformed frame · slow-client exhaustion · log exposure.
- **Representative controls:**
  - TLS truststore verification (pinned, no trust-all) + lab `CN==serverId` + nonce echo;
  - Argon2id (memory-hard, salt, wipe) — demo parameters 16 MiB, OWASP recommendation ≥ 19 MiB;
  - 64KiB max frame + strict parsing + JSON depth limit;
  - bounded queue 256 + slow-client disconnect;
  - requestId idempotency; session expiry; secret masking; sanitized errors; append-only audit.
- **Trust boundary:** only authenticated TCP/TLS triggers display; UDP/multicast = advisory.
- **Demo:** server restart → recovery from DB, **no** exactly-once network claim.
- **Note:** honestly distinguish implemented vs design-awaiting-gateway (SECURITY §0, §8).

### Slide 12 — Security: RBAC & TLS truststore (8:30–9:15)
- **RBAC matrix (role x action x scope):** SYSTEM_ADMIN everywhere; SECURITY_OPERATOR in the granted building; FLOOR_MANAGER only on the granted floor; EMPLOYEE/DEVICE cannot publish.
- **Deny-by-default + scope from DB** (`ScopedRBAC`, `Grants`, `RbacService`); immutable target snapshot; DENIED + audit.
- **Demo self-signed truststore:** `keytool -genkeypair` (RSA2048, SAN) → `-exportcert` PEM → `-importcert` PKCS12 truststore; client pins the truststore.
- **Limitations:** not a public CA, no OCSP/CRL, public demo passwords, CN=localhost (real LAN requires SAN regeneration).
- **Demo:** show `DENIED` + audit entry of an unauthorized floor-manager (T-07/C08); show `keytool -list` of the truststore.
- **Note:** emphasize least *** and not trusting client-supplied roles; connect to OWASP Authorization.

### Slide 13 — Network lab: TCP vs multicast (9:15–10:00)
- **Discovery:** UDP 9999 `DISCOVER_EBS{nonce,version}` → reply `{serverHost,tlsPort,nonce,serverId}`; **do not trust** the reply before TLS verification.
- **Hint:** real multicast `MulticastSocket` group 239.10.10.10:9998, configured TTL/interface; advisory `ALERT_HINT{alertId,revision,serverId}` → only triggers a TLS fetch.
- **Benchmark:** publisherPackets/Bytes (TCP NxM frames vs multicast M hints), receivedCopies (kernel replication), latency avg/p50/p95/max; JSON+MD report.
- **macOS environment:** `lo0` has MULTICAST but no route → probe detects it, falls back to UDP unicast; `en0` delivers real multicast loopback.
- **Demo:** compare reliable TCP push vs multicast hint (if the VLAN supports it); explain the trade-off.
- **Note:** UDP has no delivery guarantee/congestion control (RFC 8085); multicast is hint-only, authoritative content via TLS.

### Slide 14 — Limitations (10:00–10:40)
- **Single-node** → single point of failure; crash recovery ≠ HA.
- **Self-signed TLS** demo, not a public CA; public demo credentials + shared device secret (lab only).
- **Multicast LAN** depends on VLAN/interface; macOS lo0 does not route multicast.
- **Gateway/router incomplete** → some wire-level security enforcement is design only (SECURITY §8).
- **Demo Argon2id 16 MiB** below OWASP recommendation 19 MiB; no absolute session TTL yet.
- **SQLite single-writer** is an acknowledged bottleneck; no PostgreSQL yet.
- **CAP metadata** resemblance ≠ compliance (no XML schema validation).
- **Not a certified life-safety system.**
- **Note:** state limitations proactively, in the spirit of NFR-018 + blueprint §0.2.

### Slide 15 — Conclusion & future work (10:40–11:15)
- **Achieved against objectives:** self-built EBSP/2.0 over Java TCP/UDP; two-layer ACK + idempotent retry + dedup + reconnect + crash recovery; scoped RBAC + audit; 3D digital twin + chaos lab + measurement. Evidence: `verify-347-h1h4` (347 Java/14 dashboard), 100-client benchmark, chaos C01–C05, LIVE screenshots. All 4 HIGH findings of the in-depth audit were fixed (`docs/reviews/backend-depth-audit-fixes.md`).
- **Future work:** HA/active-passive; PostgreSQL when bottleneck evidence exists; schema-validated CAP XML export; floor edge relay (research); raise Argon2id parameters + real PKI; complete the gateway for wire-level security enforcement.
- **Note:** conclude against slide 3 objectives; do not claim beyond evidence.

### Slide 16 — CMP180 technical summary + lead-in to Q&A (11:15–12:00)
- **CMP180 textbook → source-code mapping:**
  - Lessons 1–2 (TCP/IP, I/O streams): `FrameCodecImpl` byte read/write, `DataInputStream.readFully`.
  - Lesson 3 (Thread/Runnable, concurrency): writer thread per session, `PriorityScheduler`, `FanoutEngine.dispatchLoop`, executors.
  - Lessons 5–6 (Socket/ServerSocket, multi-client, serialization): TLS gateway, `ClientSession`, JSON framing.
  - Lesson 7 (UDP DatagramSocket): discovery on 9999, lab datagram codec ≤ 1024 bytes.
  - Lesson 8 (MulticastSocket): hint group 239.10.10.10:9998, joinGroup, TTL/interface.
  - Lesson 9 (RMI/RPC — model reference): compare why self-built sockets were chosen over RMI/broker.
- **Repeat disclaimer:** SIMULATION/TRAINING.
- **Lead-in to Q&A:** "Our team is ready to answer your questions." (prepare section §4).
- **Note:** point out the *self-implemented* Socket/Stream/Thread/UDP/Multicast — 25% fundamentals weight.

---

## 4. Expected Q&A (after the 12-minute presentation)

### 4.1. Eight blueprint §16 questions (mandatory preparation)

**Q1. Why use TCP if an application-level ACK is still needed?**
> A TCP ACK only confirms **bytes at the transport layer** reached the receive buffer; it proves nothing at the business level. `ACK_RECEIVED` confirms the client **durably wrote to its local inbox** (SQLite WAL + synchronous=FULL) and identifies the **correct device/target**; `ACK_USER` is a **separate human gesture** (own actor and timestamp). Three different semantic layers cannot substitute for each other. (Evidence: `InboxStore.store` before `markAckReceivedSent`; `AckLedger`; PROTOCOL §7.5.)

**Q2. Why not MQTT/Firebase (cloud push/broker)?**
> The CMP180 learning goal requires **self-building the application protocol and using sockets directly** in Java (TCP/UDP), with no cloud push/broker as primary transport. MQTT is a **useful reference** (QoS, retained messages) but not a transport dependency. Self-built EBSP/2.0 demonstrates understanding of framing, concurrency and reliability at byte level. (Blueprint §0, §17.)

**Q3. Does retry guarantee exactly-once?**
> **No.** Retry is **best-effort at-least-once** within a bounded policy (3s/6s/12s + jitter, max 3 retries then FAILED). Dedup by `(alertId, revision, deviceId)` creates an **effectively-once UI action** under the local-storage assumption: exactly 1 displayed popup even if transport duplicates occur. Networks cannot be exactly-once; only idempotent receipts + deduplicated display exist. (Evidence: `RetryPolicy`, `DedupIndex`, `InboxStore` STORED/DUPLICATE/STALE; NFR-005.)

**Q4. Why is multicast only a hint?**
> UDP multicast is **not** a reliable, authenticated emergency distribution mechanism at wide scope: no delivery guarantee, no congestion control (RFC 8085), no source authentication. The authoritative verified payload travels over **TCP TLS**; the hint only triggers the client to fetch real content over TLS. The client never displays an official alert directly from multicast. (Evidence: `VerifiedAlertSubscriber`, `DiscoveryClient.discoverTrusted`; PROTOCOL §11.)

**Q5. What if the central server dies?**
> **Committed** work survives and is replayed after restart (`RecoveryScanner` rebuilds PENDING/SENT/RETRY_SCHEDULED/FAILED — transactional outbox). **There is no availability** until the server recovers → **no HA claim**. Single-node is an acknowledged single point of failure; cluster/floor relays are optional research only. (Evidence: `TransactionalPublisher` commit→enqueue; C06/C07.)

**Q6. Why SQLite?**
> Simple and reliably transactional at the expected demo load; WAL improves read/write concurrency but **still only one writer commits at a time** (acknowledged single-writer bottleneck). `PRAGMA foreign_keys=ON` on every connection, controlled `busy_timeout`, short transactions. Switch to PostgreSQL only with **real bottleneck evidence**, not guesswork. (Evidence: `Db`, `MigrationRunner` 26 tables; NFR-013; C15.)

**Q7. How are latency percentiles measured?**
> Use **server-side monotonic timestamps**: `dispatchStart` and `ackReceipt` for each delivery, within the same process lifetime (monotonic clock, not jumpy wall-clock). p50/p95/p99 are computed over **successfully observed** deliveries; **timeouts/omissions are reported separately** to avoid successful-sample bias. Each run includes metadata: runId, git SHA, CPU/RAM/OS, JDK, client count, LAN topology, fault profile. (Evidence: `MetricsRegistry.recordLatency`; blueprint §11; NFR-018.)

**Q8. What would make a CAP export compliant?**
> Correct **XML namespace, required fields, vocabulary, schema validation** and **profile checking** (OASIS CAP 1.2). **Metadata resemblance is not enough** — without schema validation, no compatibility is claimed. AEGIS currently has CAP-like metadata (severity/urgency/certainty separate from broadcast priority); schema-validated XML export is a **stretch**, claimed only when schema tests pass. (Blueprint §0.1, FR-033, §16.)

### 4.2. Expected security questions (supplementary, grounded in SECURITY.md)

**Q9. Why self-signed instead of a public CA?**
> Isolated demo LAN, no public domain; the goal is to illustrate **server authentication via a pinned truststore** (NFR-009) and counter rogue servers (T1). Self-signed certs are not trusted by OS/browser by default, have no OCSP/CRL, and demo passwords are public → **lab only**. Production needs real PKI, per-device secrets, rotate/revoke. (SECURITY §6.5.)

**Q10. Do the Argon2id parameters meet OWASP recommendations?**
> The demo uses m=16 MiB, t=2, p=1 — **below** the OWASP minimum threshold m=19 MiB (19456 KiB), t=2, p=1 (RFC 9106). Parameters are **injectable**, so raising to ≥19456 KiB in production needs no code change. Argon2id is memory-hard + unique salt + memory wipe; no plaintext, no fast SHA for passwords. (SECURITY §3.3, checklist P3.)

**Q11. How is session replay prevented?**
> TLS prevents transport eavesdropping/replay; sessionId UUIDv4 **122-bit CSPRNG entropy** (meets OWASP ≥64 bits); `deviceOnline()` closes old sessions on reconnect; ACK **only advances state** (rank guard) so an old ACK cannot regress it; dedup + tombstones block stale alert resurrection; idle/absolute expiry is a design item still to be completed in the gateway. (SECURITY §3.7; T-13..17.)

**Q12. What happens when a Floor Manager publishes above its floor?**
> `ScopedRBAC.canPublish` checks `Grants.coversFloor(buildingId, floorId)` from the DB `role_grants`, **never trusting a client-supplied floor**. Out of scope → `DENIED/INVALID_SCOPE` + append-only audit (actor/scope/outcome/correlationId). The target snapshot is resolved from the DB and immutable after activation. (T-07/C08; `ScopedRBACTest`.)

**Q13. If UDP discovery is forged, does the client fall for it?**
> No. `DiscoveryClient.discoverTrusted()` returns a `TrustedServer` only when all 4 conditions hold: matching nonce echo, pinned TLS truststore + hostname/SAN verification, `CN==serverId`, and `SERVER_HELLO` echoing the nonce over that same TLS session. A forged high-revision multicast hint is still ignored — the sink sees only TLS-authoritative content. (T-24/C13; `DiscoveryTrustTest`.)

**Q14. Can one slow-reading client block the whole system?**
> No. Each session has a `BoundedWriterQueue` of capacity 256 + its own writer thread. When full and a new frame has higher priority → evict the low-priority frame (CRITICAL not blocked by INFO); if nothing can be evicted → `REJECTED_SLOW` → **disconnect the slow client**, others continue. Queue depth + high-water mark are measured. (T-23/C16; NFR-011.)

**Q15. Can oversize frames/deep JSON exhaust memory?**
> `FrameCodecImpl.checkLength` rejects `L>65536` **BEFORE allocating the body**; strict UTF-8 REPORT; JSON objects only, no trailing tokens; Jackson `StreamReadConstraints` default depth limit 1000 (stricter explicit configuration recommended). Read timeout + per-connection quota guard against floods. (T-01..04/C10; NFR-010.)

**Q16. What if a device credential is stolen?**
> Credentials are stored as **irreversible Argon2id hashes** with unique salts and RAM wiping; a device with `enabled=0` cannot authenticate; rotate/revoke per FR-003. TLS prevents credential sniffing on the wire. **Demo limitation:** a fleet-shared secret is public in source → lab only; production must issue per-device secrets. (T2; SECURITY §3.3, §6.5.)

### 4.3. Scenario / trade-off questions

- **Why best-effort audit (audit write errors do not break commands)?** A trade-off between availability of the emergency command path and audit completeness; revisit for production (e.g. move audit to a durable queue). State honestly as a trade-off, not an optimal solution.
- **Why not use Java thread priority for alert priority?** Priority is **application-level scheduling** (`PriorityScheduler`, CRITICAL>EMERGENCY>WARNING>INFO enum), not `Thread.setPriority` (OS-dependent, nondeterministic). Bounded burst fairness prevents starvation.
- **CAP severity vs broadcast priority?** Different semantics: `severity/urgency/certainty` (CAP) describe incident severity; `priority` (EBSP) describes dispatch order. Do not conflate them (invariant §5).
- **Does timeline replay re-publish alerts?** No. Event replay is **read-only** on incident event history; Publish is not called (UC-05, §8.3).

---

## 5. Backup slides (not counted in the 12 minutes)

- **B1.** 26-table ERD + entity graph (buildings→floors→zones→devices; incidents→alerts→revisions→targets→deliveries→attempts→acks; role_grants; audit_events).
- **B2.** State machines: Incident / Alert / Delivery + invariants (ACK only advances; requestId unique; tombstone).
- **B3.** Publish sequence diagram (validate→BEGIN IMMEDIATE→snapshot→commit→enqueue) and reconnect (SYNC + tombstone).
- **B4.** 30-test acceptance table (T-01..30) mapped to modules/commands; surefire XML for exact figures.
- **B5.** Demo configuration: `ServerConfig` (TLS 9443, WS 9444, heartbeat 10s/3, retry 3s/6s/12s max 3, queue 256, Argon2id 2/16384/1).
- **B6.** Status feed contract `status/1` (WS 9444): STATUS_SNAPSHOT/DELTA, severity rules, p50/p95/p99 metrics.
- **B7.** Full keytool truststore commands (SECURITY §6) + real `keytool -list -v` output.
- **B8.** Video fallback recorded from a real run (labeled recording) + deterministic simulator commands.

---

## 6. Defense preparation checklist

- [ ] Fill in team/student ID/class info (slide 1) and sign the statement of originality in BAO-CAO.
- [x] Replace **all** `[TODO: real figures]` with real evidence + runId/SHA/machine/timestamp (slides 9, 10) — done; see `docs/evidence/`.
- [x] Run `mvn -B -ntp verify` across the whole reactor; save 51+ surefire XML files in `docs/evidence/verify/` (347 pass).
- [x] Chaos with seeds: C01–C05 really run via `scripts/chaos-run.sh` (`failed=[]`); C06–C16 have unit/integration tests + probes in surefire/reviews.
- [ ] If a second machine is available: rerun chaos/multicast on a real Win+Mac LAN; currently measured on a single M3 Pro host loopback.
- [ ] Prepare the demo truststore (`scripts/gen-server-cert.sh`) + verify `keytool -list`; for a 2-machine demo, regenerate the SAN with the real IP.
- [ ] Check venue multicast (`probe`); prepare unicast fallback + video if blocked.
- [ ] Label every slide/demo SIMULATION/TRAINING.
- [ ] Practise answers to the 8 blueprint §16 questions + §4.2 security questions; assign answerers by module.
- [ ] Review claims: no exactly-once network, no HA, no CAP compliance without validation, no life-safety system.
- [ ] Check against rubric §15: ensure self-implemented Socket/UDP (25%) and reliability under faults (25%) are emphasized appropriately.

---

## 7. Slide references

1. `Plan/AEGIS_MASTER_BLUEPRINT.md` — §14 (runbook), §15 (rubric), §16 (Q&A), §11 (observability), §12 (chaos).
2. `docs/DEMO-RUNBOOK.md`, `docs/TEST-PLAN.md`, `docs/PROTOCOL.md`, `docs/SECURITY.md`, `docs/BAO-CAO.md`.
3. `aegis-network-lab/src/main/resources/lab-guide.md` (UDP/multicast lab + benchmark).
4. `aegis-dashboard/docs/status-contract.md` (status feed `status/1`).
5. CMP180 textbook Lessons 1–9 (TCP/IP, I/O, Thread, Socket, UDP, Multicast, RMI).
6. OWASP Authorization / Session Management / Password Storage; OASIS CAP 1.2; RFC 8085; RFC 9106; Oracle SSLSocket; SQLite WAL.
