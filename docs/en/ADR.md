Language: English | Vietnamese original: docs/ADR.md

# AEGIS-EBS — Architecture Decision Records (ADR)

**Course:** CMP180 — Computer Network Programming · **Project:** AEGIS Emergency Broadcast System (Showcase Edition)
**Document type:** Architecture Decision Records — capstone project.
**Scope:** Records the repository's foundational architecture decisions, each with four parts *Context — Decision — Consequences — Alternatives*, mapped to current source code using `path/to/file.java:line` syntax.

> ⚠️ **SIMULATION / TRAINING.** The system is an academic simulation platform, not a certified fire alarm / evacuation system, and does not replace life-safety devices. All demo parameters are recorded honestly; no industrial-grade certification is claimed without verification evidence.

**Status convention:** `Accepted` — the decision has been implemented in source code and tested. This document records decisions only; for the wire contract see [`docs/PROTOCOL.md`](PROTOCOL.md), for overall architecture see [`docs/ARCHITECTURE.md`](ARCHITECTURE.md), for the security model see [`docs/SECURITY.md`](SECURITY.md).

**Decision sources:** `Plan/AEGIS_MASTER_BLUEPRINT.md` (blueprint v2.0) §0, §5–§10, §16; source code of modules `aegis-protocol`, `aegis-core`, `aegis-persistence`, `aegis-server`, `aegis-client`, `aegis-network-lab`, `aegis-simulator`, `aegis-dashboard`.

---

## Table of contents

| ADR | Title | Status |
|---|---|---|
| [ADR-001](#adr-001) | TCP/TLS as the primary transport instead of MQTT/broker | Accepted |
| [ADR-002](#adr-002) | JSON length-prefix instead of Java Serializable | Accepted |
| [ADR-003](#adr-003) | SQLite WAL + transactional outbox | Accepted |
| [ADR-004](#adr-004) | Two-layer ACK RECEIVED / USER | Accepted |
| [ADR-005](#adr-005) | Multicast advisory hint + TLS verification | Accepted |
| [ADR-006](#adr-006) | Modular monolith | Accepted |
| [ADR-007](#adr-007) | Argon2id for credential storage | Accepted |
| [ADR-008](#adr-008) | Three.js 3D digital twin | Accepted |

---

<a id="adr-001"></a>
## ADR-001 — TCP/TLS as the primary transport instead of MQTT/broker

**Status:** Accepted · **Related:** blueprint §0.2, §6.1, §7, §16 (question "Why not MQTT/Firebase?"); ADR-002, ADR-005.

### Context

The CMP180 assessment focus is **self-building an application-layer protocol over TCP/UDP** with `Socket`/`ServerSocket`, `DatagramSocket`, `MulticastSocket` (blueprint §0, §17 "Course-derived"). An emergency alert distribution platform additionally requires a transport with **authentication, reliability, ordered delivery and defined receive semantics**. Common industry options — MQTT brokers, Firebase Cloud Messaging, or message brokers in general — provide reliability out of the box but **hide the socket mechanisms** the course requires students to implement themselves, and add a dependency on a third-party infrastructure component.

The system runs on an **isolated demo LAN with a single server node** (`docs/ARCHITECTURE.md` §1, §3). There is no requirement for cloud push integration or wide-area networking.

### Decision

Choose **TCP + TLS as the only authenticated channel** for the entire command plane and delivery plane, with the custom application protocol **EBSP/2.0** on port `9443`:

- The gateway uses `SSLServerSocket` created from a PKCS12 keystore's `SSLContext` (`aegis-server/src/main/java/aegis/server/TlsGateway.java:74-78`); each accepted connection is handed to a reader running on a **virtual thread** (`TlsGateway.java:108`, executor `Executors.newVirtualThreadPerTaskExecutor()` at `aegis-server/src/main/java/aegis/server/AegisServer.java:85`).
- Default TLS port `tlsPort=9443` (`aegis-server/src/main/java/aegis/server/ServerConfig.java:56`).
- **Mandatory auth gate:** every application message except `HELLO`/`AUTH_*`/`HEARTBEAT`/`LOGOUT` is rejected with `NOT_AUTHENTICATED` while the session is unauthenticated (`aegis-server/src/main/java/aegis/server/MessageRouter.java:94-98`).
- Protocol constant `VERSION=2` (`aegis-protocol/src/main/java/aegis/protocol/Protocol.java:3`).
- UDP discovery (`9999`) and multicast hints (`239.10.10.10:9998`) are **not the primary channel**; they are advisory only and must be re-verified over TLS (see ADR-005).

No MQTT broker, Firebase, or any other message broker is used as the primary transport.

### Consequences

**Positive:**
- The team self-implements all framing, authentication, ACK, retry, dedup and recovery mechanisms → directly satisfying learning goals and the grading criterion "Java Socket/TCP/UDP fundamentals demonstrably self-implemented" (blueprint §15).
- Full control over reliability semantics: two-layer ACK (ADR-004), bounded retry, transactional outbox (ADR-003) — things a broker's defaults do not expose in the exact business model.
- TLS provides server authentication + channel security; a pinned truststore prevents LAN spoofing (ADR-005, `docs/SECURITY.md` §3.1).
- Virtual threads allow thousands of concurrent connections without a large platform-thread pool.

**Negative / trade-offs:**
- Reliability (retry, dedup, recovery) must be built and tested ourselves — more work and higher risk of bugs than using a broker.
- **Single point of failure** explicitly acknowledged: crash recovery on one node **is not high availability** (blueprint §0.2; `docs/ARCHITECTURE.md` §1 principle 2).
- TLS handshake cost and management of self-signed certificates (see ADR-005, `docs/SECURITY.md` §6.5).

### Alternatives

| Option | Why not chosen as primary channel |
|---|---|
| **MQTT broker** (Mosquitto/EMQX) | Hides the self-built socket mechanisms; MQTT QoS 1/2 does not map directly to our two-layer business ACK; adds infrastructure dependency. Kept as **reference documentation** (blueprint §16). |
| **Firebase / cloud push** | Depends on an external service, no self-implemented protocol; unsuitable for an isolated LAN and the course objectives. |
| **Plain TCP without TLS** | No server authentication, no channel security → easy eavesdropping/spoofing (violates NFR-009). |
| **UDP as primary channel** | Unreliable, unordered, unauthenticated → unsuitable for emergency alert distribution (RFC 8085). |

---

<a id="adr-002"></a>
## ADR-002 — JSON length-prefix instead of Java Serializable

**Status:** Accepted · **Related:** blueprint §7.1; `docs/PROTOCOL.md`; ADR-001.

### Context

EBSP/2.0 needs a clear **wire contract** at the byte level, language-independent (Java server/client, JavaScript dashboard), strictly validatable and able to **bound resources** against malformed data. Java `Serializable`/`ObjectOutputStream` is the "quick" choice in the Java ecosystem but: (i) only JVMs can read it, (ii) carries serious security risks from deserialization gadget chains, (iii) has no explicit schema to validate against, (iv) makes it hard to bound size before allocation.

The course requires understanding **byte-stream I/O** and manual framing (`DataInputStream.readFully`, handling partial/coalesced reads).

### Decision

Define framing **`uint32 BE length L` + `L bytes strict UTF-8 JSON`**, with `L ∈ [1, 65536]`:

- Constants: `MAX_FRAME_BYTES = 65536`, `PREFIX_BYTES = 4` (`aegis-protocol/src/main/java/aegis/protocol/Protocol.java:4-5`).
- **Reject oversize BEFORE allocating the body:** `checkLength(len)` is called immediately after reading the 4-byte header, before `readFully(body)` (`aegis-protocol/src/main/java/aegis/protocol/FrameCodecImpl.java:69-71`, `checkLength` at `:83-88`; encode/decode also check at `:39`, `:53`).
- `readFully` for header and body → handles partial reads; coalesced frames are consumed correctly with no over-read (`FrameCodecImpl.java:64-73`).
- **Strict UTF-8:** `CharsetDecoder.onMalformedInput(REPORT).onUnmappableCharacter(REPORT)` (`FrameCodecImpl.java:105-106`).
- **Strict JSON:** objects only, trailing tokens forbidden (`FrameCodecImpl.java:120`); `version` must be an integer == 2 (`:128-131`); `type` must belong to the `MessageType` enum.
- Envelope: `{version:2, type, messageId, correlationId, sentAt(UTC ISO-8601), payload}` (`docs/PROTOCOL.md:5`).

### Consequences

**Positive:**
- Language-independent: the Node/JS dashboard reads the same contract (`aegis-dashboard/src/data.js`).
- Explicit validation at every layer (length → UTF-8 → JSON → version → type → role) → resists malformed frames (threat T6, `docs/SECURITY.md` §3.5).
- Bounded memory: oversized frames are never allocated (NFR-010).
- Manual framing demonstrates exactly the byte-stream I/O skill taught in the course.

**Negative / trade-offs:**
- Text JSON uses more bandwidth than binary (Protobuf); parsing costs more than binary deserialization.
- The current JSON depth limit relies on Jackson's default `StreamReadConstraints` (maxNestingDepth=1000) + the hard 64 KiB cap; **no stricter explicit configuration yet** — recorded as a 🟡 gap (`docs/SECURITY.md` §3.5, §8 item 4).
- Partial/coalesced reads and strict UTF-8 must be handled ourselves (implemented and tested via `FrameCodecStreamTest`, `FrameCodecValidationTest`).

### Alternatives

| Option | Why not chosen |
|---|---|
| **Java `ObjectOutputStream`/Serializable** | JVM-only; deserialization gadget risk; no explicit schema; hard to bound size before allocation. |
| **Protobuf / FlatBuffers** | Binary efficiency but adds a toolchain/codegen, diverging from the course's self-built protocol focus. |
| **Newline-delimited JSON** | Ambiguous when payloads contain newlines; no explicit length to reject oversize before allocation. |
| **Fixed-size struct** | Inflexible for varied business payloads (alert, incident, sync). |

---

<a id="adr-003"></a>
## ADR-003 — SQLite WAL + transactional outbox

**Status:** Accepted · **Related:** blueprint §8.1, §8.3, §16 (question "Why SQLite?"); ADR-004; `docs/ERD.md`.

### Context

The **durable-first** principle: every alert/target/delivery state must be durable **before** network dispatch, so a server crash cannot lose a committed alert. A single-node system at demo load (100–500 clients) needs reliable transactions without operating a separate DB server. It also needs **crash recovery** that rebuilds delivery history without creating duplicate alerts.

SQLite is an embedded DB with no server process and ACID transactions; **WAL** mode allows reads in parallel with writes (but only one writer commits at a time — an acknowledged bottleneck, blueprint §8.1).

### Decision

1. **SQLite WAL + disciplined pragmas on every connection** (`aegis-persistence/src/main/java/aegis/persistence/Db.java`):
   - Order `PRAGMA foreign_keys=ON` → `busy_timeout=5000` → `journal_mode=WAL` (`Db.java:54-57`, `:83-90`); WAL switch verified, throws if refused (`:87`).
   - JVM-global per-URL file-open lock (`OPEN_LOCKS`, `Db.java:31`, `:50`) + `SQLITE_BUSY` retry when switching to WAL (`:72-90`) to avoid first-open races on new files.
   - Short-lived connections with try-with-resources; write transactions use manual `BEGIN IMMEDIATE`/`COMMIT`/`ROLLBACK`.
2. **Transactional outbox — the `deliveries` table IS the durable dispatch queue**, no separate outbox table:
   - `TransactionalPublisher.publish(Request)`: `BEGIN IMMEDIATE` → write `alerts`(ACTIVE, rev1) + `alert_revisions` + `alert_targets`(snapshot) + `deliveries`(PENDING) + `alert_operations`(idempotency) in **one transaction** → `COMMIT` (`aegis-persistence/src/main/java/aegis/persistence/TransactionalPublisher.java:85-99`).
   - **Only after COMMIT** call `DeliveryEnqueuer.enqueue(batch)` (`TransactionalPublisher.java:110-111`). Any failure → `ROLLBACK`, nothing enqueued (`:100-107`).
   - `UNIQUE(alert_id,revision,device_id)` constraint on `deliveries` (`aegis-persistence/src/main/resources/db/001_base_schema.sql:10`).
3. **Restart recovery:** `RecoveryScanner.scan()` queries deliveries still owned by the scheduler (`PENDING/SENT/RETRY_SCHEDULED/FAILED`, `DeliveryRepo.findActive` at `aegis-persistence/src/main/java/aegis/persistence/DeliveryRepo.java:80`), ordered by `created_at,id`; `recover(enqueuer)` rebuilds history (`aegis-persistence/src/main/java/aegis/persistence/RecoveryScanner.java:25-41`). `AegisServer` runs recovery **before** opening the gateway (`AegisServer.java:108-114`).
4. **26-table schema, idempotent migration via sentinels:** `MigrationRunner` applies `001_base_schema.sql` (12 tables) then `002_showcase_expansion.sql` (14 tables), each in one `BEGIN IMMEDIATE`; already-applied detection uses sentinel tables `buildings`/`demo_scenarios`, with no bookkeeping table (`aegis-persistence/src/main/java/aegis/persistence/MigrationRunner.java:24-25`, `:49`).

### Consequences

**Positive:**
- No committed alert lost on crash; an enqueuer failure after commit is repaired by recovery (`docs/ARCHITECTURE.md` §6.1).
- Simple, no external DB server dependency; ACID transactions reliable at demo load.
- Outbox integrated into `deliveries` → no extra poller component/table.
- Recovery includes `FAILED` but FAILED is terminal for the retry budget: a FAILED delivery is untracked without consuming a new attempt (`FanoutEngine.settled`, `aegis-server/src/main/java/aegis/server/FanoutEngine.java:230-236`, note S-10).

**Negative / trade-offs:**
- **One writer commits at a time** → write bottleneck; mitigated with short transactions, `busy_timeout`, no network I/O in transactions (`docs/ARCHITECTURE.md` §5.5).
- No HA; one DB file on one host (`docs/SECURITY.md` §1.2).
- WAL creates auxiliary files (`-wal`, `-shm`); checkpointing needed on close (the client's `InboxStore` checkpoints WAL).
- Timestamps need fixed-width 9-digit nanoseconds for correct TEXT `ORDER BY` (`aegis-persistence/src/main/java/aegis/persistence/Sql.java:20`; `aegis-server/src/main/java/aegis/server/Json.java:22-23`).

### Alternatives

| Option | Why not chosen |
|---|---|
| **PostgreSQL/MySQL** | Requires operating a separate DB server, overhead not justified at demo load; switch only with **real bottleneck evidence** (blueprint §16). |
| **In-memory queue + async persistence** | Loses work on crash before persistence → violates durable-first. |
| **Separate outbox table + poller** | Adds moving parts; `deliveries` already serves as the durable outbox. |
| **File-per-alert / append-only log** | Hard to do atomic multi-table transactions, hard to query delivery state. |

---

<a id="adr-004"></a>
## ADR-004 — Two-layer ACK RECEIVED / USER

**Status:** Accepted · **Related:** blueprint §5, §7.5, §16 (question "Why TCP if application ACK is needed?"); ADR-003; `docs/PROTOCOL.md:7`.

### Context

TCP's transport-level ACK only proves **bytes reached the transport stack**; it does not prove the application **durably stored** the alert, much less that **a human acknowledged it**. Emergency alert distribution needs a clear distinction:
- *machine receipt* — the device received and wrote it to its local store;
- *human acknowledgement* — a user performed the confirmation action (accountability).

At-least-once retry means ACKs may be sent multiple times → ACKs must be **idempotent**, and delivery state must **only advance, never regress**.

### Decision

Define two distinct ACK kinds (`aegis-persistence/src/main/java/aegis/persistence/AckKind.java`: `RECEIVED`, `USER`, `CANCEL`):

1. **`ACK_RECEIVED` — client-side durable-first:** the client commits its local inbox (SQLite WAL, `synchronous=FULL`) **before** sending the ACK (`aegis-client/src/main/java/aegis/client/AlertClientEngine.java:339-353`, `:400`; pragmas at `aegis-client/src/main/java/aegis/client/InboxStore.java:384-385`). Duplicate messages are re-ACKed idempotently but show no second popup (dedup seeded from the inbox, `AlertClientEngine.java:109`).
2. **`ACK_USER` — human gesture:** `markUserAck` is persisted first, sent while online; if offline it stays pending and is sent after reconnect (`AlertClientEngine.java:160-174`, `:412`; `InboxStore.markUserAck` at `:159-162`). `ACK_USER` is **never inferred from** `ACK_RECEIVED`.
3. **Server verifies ownership + idempotency:** the router checks the ACK comes from the correct authenticated device (`MessageRouter.java:220-223`), for the correct alert/revision/delivery (`:224-231`); if `ACK_USER` carries a `userId`, that user must exist and be enabled (`:233-243`, note S-06).
4. **Atomic ACK write + rank guard:** `DeliveryRepo.recordAck` returns a **boolean inserted**; it verifies delivery ownership of `(deviceId,alertId,revision)` in `BEGIN IMMEDIATE`, then `INSERT ... ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING` (`aegis-persistence/src/main/java/aegis/persistence/DeliveryRepo.java:150-196`); state advances only by SQL rank `PENDING0 < SENT/RETRY1 < RECEIVED2 < ACK/FAILED3 < EXPIRED/CANCELLED4` (`:200-209`, `rank` function at `:249-257`). `UNIQUE(device_id,alert_id,revision,ack_kind)` constraint (`001_base_schema.sql:14`).
5. **Duplicate on the wire:** `!inserted` → `duplicate=true` + `DUPLICATE` audit (`MessageRouter.java:245-256`).

### Consequences

**Positive:**
- Clear semantics: never reports "user acknowledged" when only a machine receipt exists.
- Idempotent ACKs tolerate at-least-once traffic (re-ACK raises no UNIQUE error, no mistaken NACK).
- Monotonic state prevents RECEIVED→SENT regression from late retries (coordinated with ADR-003/S-02).
- Durable pending ACKs are resent after crash/reconnect (`AlertClientEngine.java:423-431`).

**Negative / trade-offs:**
- More states and logic (rank guard, ownership binding, inserted-flag contract).
- `ACK_USER` permits `userId=null` (anonymous) but a supplied userId must be verified → UI must attribute responsibility clearly.
- Depends on a durable client inbox; losing the inbox loses receipt evidence.

### Alternatives

| Option | Why not chosen |
|---|---|
| **Single ACK** | Confuses machine/human; cannot attribute the confirming person. |
| **TCP ACK only** | Does not prove business-level durable storage, does not identify the target. |
| **NACK (negative ack)** | Does not prove receipt; hard to distinguish "not arrived" from "ACK lost". |
| **Exactly-once transport** | Impossible to guarantee generally on a lossy network; at-least-once + dedup is used (see DEFENSE-QA Q3). |

---

<a id="adr-005"></a>
## ADR-005 — Multicast advisory hint + TLS verification

**Status:** Accepted · **Related:** blueprint §7.7, §16 (question "Why multicast only hints?"); `docs/SECURITY.md` T1, §3.1; ADR-001.

### Context

The course requires practising `MulticastSocket`/`DatagramSocket` (Lessons 7–8). Multicast can "wake up" many clients with one datagram — attractive for emergency alerts. However **UDP multicast is unreliable, unauthenticated and unsuitable for wide area** (RFC 8085); on the demo LAN an attacker could forge `ALERT_HINT`/`DISCOVER_EBS` to redirect clients to a fake server or display fake alerts (threat T1, `docs/SECURITY.md` §2).

We must reconcile: demonstrating the course's multicast technique **and** keeping the invariant "the client never trusts multicast/UDP as an operator command" (blueprint §5).

### Decision

Multicast/UDP is an **advisory hint** only; authoritative content always comes over verified TLS:

1. **Lab parameters:** administratively-scoped group `239.10.10.10`, port `9998`, `TTL=1` (link-local), discovery on `9999` (`aegis-network-lab/src/main/java/aegis/networklab/LabDefaults.java:5-9`).
2. **Real publisher/subscriber:** `MulticastSocket` → `joinGroup(group, interface)` → `setOption(IP_MULTICAST_TTL)` → `send` one datagram per hint (`aegis-network-lab/src/main/java/aegis/networklab/MulticastHintPublisher.java:33-41`, `:53`); subscriber bind → joinGroup → receive loop → leaveGroup (`MulticastHintSubscriber.java:33-40`, `:56`, `:87`).
3. **Hint is a trigger, not content:** `VerifiedAlertSubscriber.onHint` receives a hint → **fetches authoritative content over a verified TLS session** → the sink only sees the real revision from the server, **ignoring the revision in the hint**; stale/duplicate hints (≤ last verified) are skipped without fetch (`aegis-network-lab/src/main/java/aegis/networklab/VerifiedAlertSubscriber.java:44-82`).
4. **Discovery must be re-verified:** `DiscoveryClient.discoverUntrusted` checks nonce echo (`DiscoveryClient.java:93-99`); `discoverTrusted` opens TLS with a **pinned truststore**, requires `CN == serverId`, and echoes the nonce correctly in `HELLO/SERVER_HELLO` **over that same TLS session** (`DiscoveryClient.java:105-112`).
5. **Client authenticates the server, never trust-all:** `TlsSupport.clientContext` loads `KeyStore` → `TrustManagerFactory` → `SSLContext("TLS")`, trusting only the certificate in the truststore (`aegis-client/src/main/java/aegis/client/TlsSupport.java:19-27`).
6. On a single macOS host, multicast may fail → unicast fallback (`UnicastHintPublisher`/`UnicastHintSubscriber`, `docs/ARCHITECTURE.md` §3.3).

### Consequences

**Positive:**
- A forged hint (wrong revision/alertId) causes at most one fetch; **fake content is never displayed** because the sink only accepts TLS-verified content.
- Correctly demonstrates the course's multicast + UDP discovery techniques without breaking the security invariant.
- Coalescing duplicate/fake hints reduces redundant fetches (`VerifiedAlertSubscriber.java:49-54`).

**Negative / trade-offs:**
- An extra TLS fetch round-trip after a hint → higher display latency than "trusting the hint directly".
- Multicast needs a supporting real LAN (TTL/interface, rate limit); tunnels like Tailscale usually fail (`docs/ARCHITECTURE.md` §3.3).
- A forged hint with a high revision can **block a genuine hint with the same revision number** afterwards (limitation explicitly recorded at `VerifiedAlertSubscriber.java:51-52`) — but cannot create fake content.
- Self-signed truststore is not a public CA, no OCSP/CRL (`docs/SECURITY.md` §6.5).

### Alternatives

| Option | Why not chosen |
|---|---|
| **Multicast as primary distribution channel** | Unreliable, unauthenticated → violates the invariant and RFC 8085. |
| **Digitally sign multicast payloads** | Complex key distribution; still does not solve UDP reliability (packet loss). |
| **Drop multicast entirely** | Loses the course's `MulticastSocket` practice objective. |
| **UDP broadcast** | Narrow scope, no group control, still unauthenticated. |

---

<a id="adr-006"></a>
## ADR-006 — Modular monolith

**Status:** Accepted · **Related:** blueprint §6.3, §6.4; `docs/ARCHITECTURE.md` §1.1, §4.

### Context

The capstone is built by a student team and deployed as **one server node on the demo LAN**. Clear module boundaries are needed for division of work and testing, but the operational cost of a distributed system (service discovery, distributed transactions, network partitions) is **not needed**. The course focus is network programming, not microservice operations.

Blueprint §6.3 proposes a reference package tree; some proposed modules are merged into `aegis-core` (pure logic) and `aegis-server` (application services).

### Decision

Build a **modular monolith**: one Maven reactor with 7 Java modules + an independent Node dashboard, running in **one server process** (`AegisServer`):

- Modules declared: `aegis-protocol`, `aegis-core`, `aegis-persistence`, `aegis-server`, `aegis-client`, `aegis-simulator`, `aegis-network-lab` (`pom.xml:10-16`); compiler release 24 (`pom.xml:19`).
- Dependency relations: `protocol ← core ← {persistence, server, client, simulator}`; `server ← {protocol, core, persistence}`; `network-lab ← protocol`; dashboard independent, communicating only via WebSocket `status/1` (`docs/ARCHITECTURE.md` §1.1).
- `AegisServer` wires everything: migrate → seedWorld → RecoveryScanner → WS feed + TLS gateway + timers (`aegis-server/src/main/java/aegis/server/AegisServer.java:57-125`).
- **Immutable shared contracts:** `aegis-protocol` (EBSP/2.0) and the 26-table schema are common ground; changing the wire contract requires a version bump, changing the schema requires an additive migration (`docs/ARCHITECTURE.md` §1 principle 6, §4.2).
- Pure I/O-free logic lives in `aegis-core` (`ScopedRBAC`, `PriorityScheduler`, `RetryPolicy`, `DedupIndex`, `TargetResolver`, `AckLedger`, `Grants`) → deterministic tests, shared by both sides.

### Consequences

**Positive:**
- Simple deployment/debugging: one process, one DB file; in-process calls, no internal network partitions.
- Module boundaries enforced by Maven dependencies + package discipline; immutable contracts keep modules compatible.
- Pure logic in `aegis-core` tests without I/O → fast, deterministic tests (injectable Clock).
- Can be split into services later because contracts are already separated (protocol/persistence independent).

**Negative / trade-offs:**
- No independent scaling of parts; the whole server is one deployment unit.
- **SPOF** of one process (acknowledged, no HA claim).
- A module error can affect the whole process; bounded-resource discipline is needed for isolation (ADR-003, bounded queues).

### Alternatives

| Option | Why not chosen |
|---|---|
| **Microservices** | Operational overhead (discovery, distributed transactions, distributed observability) not justified for a single-node demo load. |
| **One flat package (big ball of mud)** | No boundaries, hard to divide work/test, contracts easily broken. |
| **Complex OSGi / Java module system** | Unnecessary complexity for a capstone. |

---

<a id="adr-007"></a>
## ADR-007 — Argon2id for credential storage

**Status:** Accepted · **Related:** blueprint §9; `docs/SECURITY.md` §3.3, §7.3 (OWASP Password Storage); ADR-001.

### Context

The server stores operator passwords (`users.password_hash`) and device credentials (`devices.credential_hash`). Threat T2 (credential theft) and T10 (DB file read) require a **memory-hard** hash with a unique salt, resistant to rainbow tables and GPU/ASIC cracking. The OWASP Password Storage Cheat Sheet recommends **Argon2id** (RFC 9106) with minimum parameters `m=19 MiB (19456 KiB), t=2, p=1`.

At the same time the demo needs authentication fast enough for 100–500 device clients → parameters must be **injectable** to balance demo speed with production safety.

### Decision

Use **Argon2id** via `de.mkammerer:argon2-jvm:2.11` (native libs bundled including darwin-aarch64):

- `Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)` (`aegis-server/src/main/java/aegis/server/Argon2idHasher.java:13`).
- Injectable parameters via constructor (`Argon2idHasher.java:18-24`); demo defaults `iterations=2`, `memoryKib=16384` (16 MiB), `parallelism=1` (`aegis-server/src/main/java/aegis/server/ServerConfig.java:70-72`).
- **Unique salt** per password generated by Argon2id, embedded in the `$argon2id$...` string.
- **Memory wipe:** `hash()`/`verify()` use `char[]` and `argon2.wipeArray(chars)` in `finally` (`Argon2idHasher.java:28-33`, `:38-48`).
- **Safe verify:** accepts only strings starting with `$argon2id$`; malformed/null hash → `false`, authentication does not crash (`Argon2idHasher.java:39`).
- **Disabled accounts:** `AuthService` checks `enabled()` before verify; `enabled=0` never authenticates (`aegis-server/src/main/java/aegis/server/AuthService.java:28-39`).
- SHA-256 is used **only** for idempotency `payload_hash`, never for passwords (`docs/SECURITY.md` §3.3).

**Honest 🟡 note:** the 16 MiB demo default is **below** the OWASP 19 MiB threshold; production must raise `memoryKib ≥ 19456` (parameters are injectable, so no code change is needed). Demo credentials (`*-demo-secret`, fleet-shared `device-demo-secret`) are public in source code → **lab only** (`aegis-server/src/main/java/aegis/server/ServerStore.java:631-637`, `:697-700`; `docs/SECURITY.md` §6.5).

### Consequences

**Positive:**
- Memory-hard → better resistance to GPU/ASIC cracking than fast hashes; unique salt defeats rainbow tables.
- `char[]` wiping reduces credential traces in RAM; malformed hashes do not crash verification.
- `enabled=0` blocks authentication immediately → supports revoke/rotate (FR-003).
- Injectable parameters: production tuning without code changes.

**Negative / trade-offs:**
- Demo parameters below the OWASP threshold (speed trade-off for the lab, recorded 🟡).
- Shared device secret breaks per-device identity (T2) — lab only; production must issue per-device credentials + rotation (`docs/SECURITY.md` §6.5 item 7).
- CPU/RAM cost per verify is higher than SHA → parameters need consideration under heavy load.
- Pepper (defense-in-depth) is not used (`docs/SECURITY.md` §7.3 P8).

### Alternatives

| Option | Why not chosen |
|---|---|
| **SHA-256 / MD5 (fast hash)** | Extremely fast GPU/ASIC cracking; not memory-hard; used only for payload_hash, not passwords. |
| **bcrypt** | Memory-hard only to a limited extent (4 KiB), less flexible parameters than Argon2id. |
| **PBKDF2** | CPU-hard only, not memory-hard → easily parallelized on GPUs. |
| **Plaintext** | Unacceptable (violates OWASP P1). |
| **scrypt** | Similar to Argon2id, but Argon2id is the current OWASP/RFC 9106 recommendation. |

---

<a id="adr-008"></a>
## ADR-008 — Three.js 3D digital twin

**Status:** Accepted · **Related:** blueprint §10 (Digital Twin UI), §1.2; `docs/ARCHITECTURE.md` §7; `aegis-dashboard/docs/status-contract.md`.

### Context

Blueprint §10 requires a **visual digital twin**: a 10-floor building diagram whose devices/zones change color from **real server events**, not meaningless animation (blueprint §0.1 item 1). The dashboard must be the **reference client of the status feed**, not a second backend. The blueprint suggests JavaFX, but a web application is easier to share and cross-platform without installation — suitable for a defense in front of a committee.

The `status/1` feed over WebSocket `9444` is a **read plane** on a trusted LAN; resource limits are needed, and severity must be derived canonically from real delivery counters.

### Decision

Build the dashboard with **Vite + Three.js** (web), rendering the 3D twin from the `status/1` feed:

- **3D twin:** `WebGLRenderer`, a 10-floor x 4-zone scene (2x2), each zone a box colored by severity (`aegis-dashboard/src/main.js:2-9`, `:13`, `:74`, `:117`, `:167`, `:223-233`). Zone code `F%02d-{A..D}` (`main.js:167`).
- **Feed-only, no private backend:** WebSocket client of `status/1` (`aegis-dashboard/src/ws.js`); snapshot + delta contract in `aegis-dashboard/src/data.js` (`:113-136` pure delta merge).
- **Canonical severity from real counters:** `deriveSeverity` (offline/critical/warning/online) mirrored between server and client (`aegis-server/src/main/java/aegis/server/ZoneStatus.java:22-30` ↔ `aegis-dashboard/src/data.js:99-100`); **the online client count is never used as the ACK denominator**.
- **Server feed:** `StatusFeedService` batches 500 ms, sends a snapshot on connect, and sends only changed parts in deltas (`aegis-server/src/main/java/aegis/server/StatusFeedService.java:116`, `:155`, `:179-233`, `:247`); `StatusFeedServer` caps subscribers at **64**, above which → HTTP 503 (`aegis-server/src/main/java/aegis/server/StatusFeedServer.java:36`, `:100-103`).
- **Reconnect + MOCK fallback:** exponential backoff 1 s → 15 s + jitter; after **3 failures** switch to MOCK (`MOCK FEED` badge), automatically returning to LIVE on reconnect (`aegis-dashboard/src/ws.js:6-7`, `:19-22`, `:74`, `:82-86`).
- **Graceful degradation:** WebGL unavailable → panels still work, twin disabled (`main.js:317-324`).

### Consequences

**Positive:**
- A lively 3D twin colored by real state → visually demonstrates alert distribution, impressive during the defense.
- Cross-platform, runs in a browser with no installation; demo links are easy to share.
- Separate read plane: the dashboard is not a second backend and writes no business data.
- MOCK fallback + badge keeps the demo running if the server dies, **clearly labeled** to avoid mistaking fake data for real.

**Negative / trade-offs:**
- The WS feed is an **unauthenticated read plane** on a trusted LAN (explicitly recorded, `StatusFeedServer.java:27-30`); subscriber cap (64) required, trusted-LAN only.
- Severity rules must be **mirrored in two languages** (Java `ZoneStatus` ↔ JS `data.js`) → risk of drift if one side changes.
- MOCK mode without clear labeling could be mistaken for real figures (badge added).
- Three.js/WebGL depends on browser GPU; weak machines may be slow (degradation exists).

### Alternatives

| Option | Why not chosen |
|---|---|
| **Pure JavaFX 2D/3D** | Installation friction, hard to share; JavaFX is already used for the operator UI, so a web dashboard is more clearly separated. |
| **2D canvas/SVG** | Less impressive than a 3D twin; the blueprint prioritizes building visualization. |
| **Grafana / generic dashboard** | Cannot model buildings/zones; not a real digital twin. |
| **Server-rendered HTML** | No interactive live 3D; hard to recolor from real-time events. |

---

## References

1. `Plan/AEGIS_MASTER_BLUEPRINT.md` — AEGIS EBS Command Center / Showcase Edition, CMP180 Capstone Blueprint v2.0 (§0, §5–§10, §15–§17).
2. `docs/PROTOCOL.md` — EBSP/2.0 wire contract.
3. `docs/ARCHITECTURE.md` — AEGIS-EBS System Architecture.
4. `docs/SECURITY.md` — Security & Privacy Model.
5. `docs/ERD.md` — 26-table schema relationship specification.
6. `aegis-dashboard/docs/status-contract.md` — `status/1` feed contract.
7. OWASP Cheat Sheet Series (Authorization, Session Management, Password Storage); IETF RFC 9106 (Argon2), RFC 8085 (UDP Usage Guidelines); SQLite WAL (sqlite.org/wal.html); OASIS CAP 1.2.
8. Cross-checked source code: `.java`/`.js`/`.sql` files cited as `path:line` in each ADR.
