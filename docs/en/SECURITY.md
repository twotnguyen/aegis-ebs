Language: English | Vietnamese original: docs/SECURITY.md

# AEGIS-EBS — Security & Privacy Model (SECURITY)

**Course:** CMP180 — Computer Network Programming · **Project:** AEGIS Emergency Broadcast System (Showcase Edition)
**Document type:** Security design + implementation mapping + OWASP checklist.
**Sources:** Blueprint `Plan/AEGIS_MASTER_BLUEPRINT.md` §9 (Security & privacy), §7 (EBSP/2.0), §3 (NFR); `docs/PROTOCOL.md` (EBSP/2.0 wire contract); OWASP Cheat Sheet Series (Authorization, Session Management, Password Storage).

> ⚠️ **SIMULATION / TRAINING.** This document describes the security design of an **academic simulation system**, not a certified fire alarm/evacuation system, and does not replace life-safety devices. All test figures must be **real evidence** (runId, commit SHA, machine configuration, timestamp) per NFR-018; this document **does not** fill in assumed measurements.

---

## 0. Implementation status conventions

For academic honesty, every measure is labeled with its status at the time of writing:

| Label | Meaning |
|---|---|
| ✅ **Implemented** | Code exists in the reactor with corresponding unit/integration tests. |
| 🟡 **Partial / demo** | Code exists but parameters or scope are demo-level and need tuning for real deployment. |
| 🔧 **Designed — awaiting gateway** | Specified in the blueprint/PROTOCOL; backend services are ready but the **gateway/router layer (`aegis.server.ServerMain`) is not yet in the source code**, so wire-level enforcement is incomplete. |

**Current-state note:** modules `aegis-protocol`, `aegis-core`, `aegis-persistence` are frozen and tested (56 + 73 + 35 tests). `aegis-server` contains implemented business services (`AuthService`, `RbacService`, `Argon2idHasher`, `SessionRegistry`, `ClientSession`, `BoundedWriterQueue`, `FanoutEngine`, `AlertService`, `AuditService`, `ServerStore`, `SyncService`) but **has no TLS gateway/router entry point** to enforce authentication per message at the wire layer. The 🔧 measures are therefore **design contracts** the gateway must enforce when completed, not claims of running code.

---

## 1. Scope, trust boundaries & assumptions

### 1.1. Four planes (blueprint §6.1) and trust boundaries

| Plane | Channel | Trust level | Security notes |
|---|---|---|---|
| Command plane | EBSP/2.0 over **TCP/TLS** port 9443 | **Trusted** after authentication | Dashboard/operator logs in, scope checked, incident & alert create/update/cancel. |
| Delivery plane | EBSP/2.0 over **TCP/TLS** 9443 | **Trusted** after authentication | Authenticated device sessions, bounded queues, retry, ACK. |
| Persistence/observability | SQLite WAL (local) | Internally trusted | Single-writer; append-only audit; metrics; read-only replay. |
| Discovery/experiment | **UDP 9999** + **Multicast 239.10.10.10:9998** | **UNTRUSTED** | *Hint/advisory* only; never triggers official alert display. |

**Trust-boundary principles (blueprint §0.2, §7.7; PROTOCOL.md):**

1. **Only authenticated TCP/TLS** may trigger alert display. UDP discovery and multicast hints are **not** authenticated sources.
2. The client **never** treats multicast/UDP as an operator command (invariant §5).
3. The server **derives the allowed scope from the DB**, never from client-supplied role/floor metadata (§7.2).
4. The `simulation` field in the payload is **not a security boundary**; the server's demo mode + a separate device allowlist are the controls (§7.2).
5. `sessionId` is **not by itself authorization evidence** outside the authenticated connection (§7.4).
6. The demo runs on an **isolated LAN**; never broadcast fake alerts to public networks or real-user environments.

### 1.2. Hard assumptions

- A single server node → **single point of failure**; crash recovery ≠ high availability (no HA claim).
- TLS uses a **self-signed** certificate for the demo (see §6); not a public CA.
- Demo devices use a shared credential (`device-demo-secret`) — **lab only** (see §6.5).
- No real sirens, elevators, doors or locks are controlled; scenarios affect only authorized demo clients.

---

## 2. Threat model

Method: list actors/vectors → threatened assets → impact → countermeasures → test mapping. STRIDE notation: **S**poofing, **T**ampering, **R**epudiation, **I**nformation disclosure, **D**enial of service, **E**levation of privilege.

| ID | Threat | STRIDE | Vector / actor | Assets & impact | Countermeasure (see §3) | Status | Tests |
|---|---|---|---|---|---|---|---|
| **T1** | **Rogue LAN spoof discovery** — an attacker on the LAN forges `DISCOVER_EBS`/`ALERT_HINT` responses to redirect clients to a fake server | S, T | Unauthenticated UDP unicast/broadcast 9999; multicast 239.10.10.10:9998 | Client connects to attacker server → credential leak, fake alert display | Hints advisory only; client must verify TLS via pinned truststore + hostname/SAN + `CN==serverId` + nonce echo (`DiscoveryClient.discoverTrusted()`); authoritative content only over TLS | ✅ (network-lab) | T-24, T-25; C13, C14; `DiscoveryTrustTest`, `DiscoveryNonceEchoTest`, `VerifiedAlertSubscriberTest` |
| **T2** | **Stolen device credential** — credential theft (inbox file, memory, logs, network) to impersonate a device | S, I | `device-secret` leak; DB `credential_hash` read | Impersonate device to receive/send false ACKs; escalate to operator session | Argon2id (memory-hard, unique salt) instead of plaintext/fast SHA; wipe char arrays; `enabled=0` accounts cannot authenticate; credential revoke/rotate; no secrets in logs; TLS against eavesdropping | ✅ 🟡 | T-05, T-06; `AuthService`, `Argon2idHasher`; C09 |
| **T3** | **Session replay** — capture and replay frames/sessionId to reuse a session or old ACK | S, R | TCP sniffing (without TLS); replay of `ALERT_PUSH`/ACK; guess sessionId | Old alerts replayed; fake ACKs; session impersonation | TLS against transport eavesdropping/replay; sessionId UUIDv4 (122-bit entropy, CSPRNG); session expiry (idle + absolute); `deviceOnline()` closes old sessions on reconnect; ACK only advances state (rank guard); dedup `(alertId,revision,deviceId)`; tombstones prevent stale redisplay | ✅ 🔧 | T-13..17; C03, C05, C12; `AckLedgerTest`, `DedupIndexTest`, `InboxStoreTest` |
| **T4** | **Role escalation** — operator/device elevates privileges (edits role/grant in payload, calls commands above its role) | E, T | Client sends self-declared role/floor; EMPLOYEE/DEVICE calls `ALERT_PUBLISH` | Unauthorized alert broadcast; self privilege modification | Server derives scope from DB (`RbacService.grantsFor`), ignores client role; `ScopedRBAC.canPublish` deny-by-default; router validates message types against authenticated role; EMPLOYEE/DEVICE never publish; no self-service privilege change | ✅ 🔧 | T-07; C08; `ScopedRBACTest` |
| **T5** | **Out-of-scope broadcast** — Floor Manager/Security Operator publishes to floors/buildings outside the grant | E, T | Arbitrary target payload extends floor/zone | Alerts to out-of-scope devices → disturbance, area information leak | Immutable `Grants` (defensive copy); `canPublish` checks building/floor; `TargetResolver` immutable snapshot from DB; empty target → explicit error; audit every DENIED | ✅ | T-07, T-08; C08; `ScopedRBACTest`, `TargetResolverTest`, `TransactionalPublisherTest` |
| **T6** | **Malformed frame** — oversize frame, invalid UTF-8, deep/broken JSON, wrong version/type to crash or exhaust memory | T, D | Length prefix > 64KiB; deeply nested JSON; invalid UTF-8; trailing tokens | Memory overflow, server crash, denial of service | `FrameCodecImpl` rejects oversize **BEFORE** allocation; `readFully` handles partial/coalesced reads; strict UTF-8 (REPORT); objects only, no trailing tokens; `version==2`; type in enum; JSON depth limit (Jackson `StreamReadConstraints`); read timeout + quota | ✅ 🟡 | T-01..04; C10; `FrameCodecStreamTest`, `FrameCodecValidationTest` |
| **T7** | **Slow-client exhaustion** — slow/non-reading client fills queues and slows the whole system | D | Client does not drain socket; INFO flood | Queue memory exhaustion; delivery to other clients blocked | `BoundedWriterQueue` capacity 256/session; priority eviction (CRITICAL not blocked by INFO); `REJECTED_SLOW` → disconnect slow client; one writer thread/session (no frame interleaving); queue depth metrics | ✅ | T-23; C16, C04; `BoundedWriterQueue` (bounded queue test) |
| **T8** | **Log exposure** — secret/password/token/raw PII leaking into logs or reports | I | Logging auth commands, payloads, credential-bearing exceptions | Credential/PII leak via logs, incident reports | No logging of passwords/credentials; `Argon2idHasher.wipeArray`; audit records only actor/action/scope/outcome/correlationId; masking filter (design); permission-controlled incident reports; T-28 log scan | 🟡 🔧 | T-28; NFR-016 |
| **T9** | **Privileged operator misuse** — a valid operator abuses authority (wrong publish/cancel, erasing traces) | R, E | Malicious SYSTEM_ADMIN/SECURITY_OPERATOR actions | False alerts; repudiation | Append-only audit of all high-risk actions; dual-approval (demo, optional); hash-chain tamper-evidence (stretch, limited §3.10); least *** | 🔧 | FR-020; C08 (audit) |
| **T10** | **Database & log data exposure** — reading SQLite files/backups containing hashes, PII, audit | I | Access to `aegis.db`, WAL, backups | Credential hash, PII, incident history leak | Argon2id (irreversible hash); PII minimization; DB file permissions; bounded retention (NFR-012); pepper (optional, defense-in-depth) | 🟡 🔧 | NFR-012, NFR-016 |

---

## 3. Controls

### 3.1. Server authentication via TLS truststore (against T1, T2, T3)

- **Client authenticates the server, never trust-all.** `aegis.client.TlsSupport.clientContext(truststore, password)` loads `KeyStore` → `TrustManagerFactory` → `SSLContext("TLS")`, **trusting only the certificate in the truststore** (pinned). No empty `X509TrustManager` / trust-all in the demo default (NFR-009).
- **Hostname/SAN check**: the demo certificate has `SAN=dns:localhost,ip:127.0.0.1`; the client must match the connected host against the SAN (see §6 for a 2-machine demo).
- **Server identity binding in the lab** (`aegis-network-lab`, ✅): `DiscoveryClient.discoverTrusted()` returns a `TrustedServer` only when all 4 conditions hold:
  1. the discovery response's nonce matches the sent nonce;
  2. TLS handshake with pinned truststore succeeds + hostname/SAN verification;
  3. certificate `CN` == advertised `serverId`;
  4. `SERVER_HELLO` echoes the discovery nonce correctly **over that same TLS session**.
- **Authoritative content only over TLS**: `VerifiedAlertSubscriber` receives a hint → fetches over TLS → the sink sees only the real revision from the server, **ignoring the revision in the hint**.
- **Server keystore**: `TlsSupport.serverContext(keystore, password)` (PKCS12/JKS by file extension) for the gateway and test fixtures.
- 🔧 Gateway must enable `SSLServerSocket` with `setNeedClientAuth(false)` (application authentication via `AUTH_DEVICE`/`AUTH_OPERATOR` after TLS) and **reject every application message before authentication** (FR-001).

### 3.2. RBAC + scope at EVERY command (against T4, T5)

- **Deny-by-default, least *** (OWASP Authorization). `aegis.core.ScopedRBAC.canPublish(role, grants, buildingId, floorId)` ✅:
  - `SYSTEM_ADMIN` → everywhere;
  - `SECURITY_OPERATOR` → within the granted building (all floors of that building);
  - `FLOOR_MANAGER` → only the granted floor within the granted building;
  - `EMPLOYEE`, `DEVICE` → **never** publish.
- **Immutable grants** (`aegis.core.Grants`): defensive copy in the constructor, accessors return unmodifiable views → mutating the source collection after creation cannot change scope.
- **Scope from DB, not client** (`aegis.server.RbacService.grantsFor(userId)` ✅): loads `role_grants` → `Grants`. `BUILDING_WIDE_FLOOR = "*"` is the placeholder for building-wide scope (does not match a specific floor grant).
- **Immutable target snapshot** (`TargetResolver` + `TransactionalPublisher` ✅): resolve devices from the DB at activation time; no out-of-scope devices; snapshot unchanged after activation (FR-009, T-08).
- 🔧 **Router enforces per message** (blueprint §7.1: "Strictly validate API message types against authenticated role"): the gateway must check `MessageType` against `ClientSession.role()`/`kind()` **before** calling services; `AlertService` assumes "RBAC is enforced by the router BEFORE these methods run". Every denial → `ACCESS_DENIED`/`INVALID_SCOPE` + audit (FR-006, FR-020, NFR-008).
- **No self-elevation**: an operator cannot change its own role/grants (Security Operator persona, blueprint §1.1); grant administration belongs to SYSTEM_ADMIN only.

### 3.3. Argon2id password storage (against T2, T10)

- **Algorithm**: `aegis.server.Argon2idHasher` ✅ uses `de.mkammerer:argon2-jvm:2.11`, `Argon2Factory.Argon2Types.ARGON2id` (native libs bundled linux/win/darwin including aarch64).
- **Injectable parameters** (`ServerConfig.Builder`): demo defaults `iterations=2`, `memoryKib=16384` (16 MiB), `parallelism=1`.
  - 🟡 **OWASP note**: the Password Storage Cheat Sheet recommends Argon2id **at least m=19 MiB (19456 KiB), t=2, p=1** (RFC 9106). The 16 MiB demo default is **below** the recommendation; real deployment must raise `memoryKib ≥ 19456` (or an OWASP-equivalent parameter set). Parameters are injectable, so tuning needs no code change.
- **Unique salt per password**: Argon2id generates a random salt embedded in the `$argon2id$...` encoded string → defeats rainbow tables.
- **No plaintext, no fast hash**: SHA-256 is used only for idempotency `payload_hash` (not passwords). Passwords/credentials always go through Argon2id.
- **Memory wipe**: `hash()`/`verify()` use `char[]` and `argon2.wipeArray(chars)` in `finally` → fewer credential traces in RAM.
- **Safe verify**: accepts only strings starting with `$argon2id$`; malformed hash → `false` (no auth crash); `raw==null` → `false`.
- **Disabled accounts**: `AuthService.authenticateOperator/authenticateDevice` ✅ checks `enabled()`; `enabled=0` accounts/devices **never** authenticate (FR-003, T-06).
- **Pepper** (optional, defense-in-depth — OWASP): a key stored outside the DB may be added; by itself it does not increase security if used alone.
- 🟡 **Demo**: `ServerStore.seedWorld` hashes demo credentials (`admin-demo-secret`, `secops-demo-secret`, `floor7-demo-secret`, `employee-demo-secret`, fleet-shared `device-demo-secret`). These values are **public in source code** → lab only, never production (see §6.5).

### 3.4. requestId idempotency (against T3, command replay)

- **CREATE/PUBLISH**: `TransactionalPublisher.publish(Request)` ✅ writes `alert_operations(request_id UNIQUE, state DONE, payload_hash sha256)` in **one** `BEGIN IMMEDIATE` transaction; enqueue only **after** commit (transactional outbox).
- **Replay with the same requestId** → returns the ORIGINAL alert, no second fanout (`AlertService.publish` ✅: `findOperationByRequestId` → `CommandOutcome.ok(..., duplicate=true)` + `PUBLISH_DUPLICATE` audit).
- **Concurrent race**: `UNIQUE request_id` collision → re-read the winner and return the idempotent result (✅ catches `SQLException duplicateRace`).
- **UPDATE/CANCEL**: `ServerStore.insertOperation` writes `request_id` + `payload_hash` (canonical sha256) ✅; replay returns the original result.
- **Distinguishing CONFLICT** (blueprint UC-01/§5): same requestId but **different payload** → `CONFLICT`. 🟡 `payload_hash` is already stored for detection; returning `CONFLICT` on a different fingerprint is a router/service contract still to be completed (current code prefers returning the original for a duplicate requestId).
- **Idempotent delivery**: each delivery has a stable `deliveryId` + `alertId` + `revision`; each transport attempt uses a new `messageId`; ACK only advances state (PROTOCOL.md, `DeliveryRepo.recordAck` rank guard).

### 3.5. 64KiB frame cap + depth limits (against T6)

- **Hard cap**: `Protocol.MAX_FRAME_BYTES = 65536`, `PREFIX_BYTES = 4` ✅. `FrameCodecImpl.checkLength` rejects `L < 1 || L > 65536` **BEFORE allocating the body** (in `decode`, `readFrame`, `encode`) → no unbounded memory allocation (NFR-010).
- **Exact byte contract**: `uint32 BE length L` + `L bytes strict UTF-8 JSON`; `DataInputStream.readFully` for the 4 header bytes then the body → partial reads handled; coalesced frames consumed correctly with no over-read (✅ `FrameCodecStreamTest`).
- **Strict UTF-8**: `CharsetDecoder.onMalformedInput(REPORT).onUnmappableCharacter(REPORT)` → invalid UTF-8 rejected (✅).
- **Strict JSON**: objects only; no trailing tokens after the object; `version` must be integer == 2; `type` in `MessageType` enum; `messageId`/`sentAt` required non-blank; `payload` required (✅ `FrameCodecValidationTest`).
- **Max depth**:
  - 🟡 Jackson `2.17.2` applies default `StreamReadConstraints`: `maxNestingDepth = 1000`, `maxStringLength = 20_000_000`. Since frames are capped at 64KiB, string length/depth are indirectly bounded by frame size.
  - 🔧 **Recommendation**: configure stricter explicit `StreamReadConstraints` (e.g. small `maxNestingDepth`, `maxStringLength` ≤ 64KiB) on the decode `ObjectMapper`, to follow blueprint §7.1 ("too-deep ... JSON") and not depend on library defaults. `FrameCodecImpl`/`Json.MAPPER` do not currently set explicit constraints.
- **Read timeout + per-connection quota** (blueprint §7.1): 🔧 the gateway must set `setSoTimeout`, limit connection count and frames/second quota against floods (with `RATE_LIMITED`, `SERVER_BUSY`).

### 3.6. Bounded queue + slow-client policy (against T7)

- **`BoundedWriterQueue`** ✅ default capacity **256**/session (`ServerConfig.writerQueueCapacity`), `TreeSet` sorted by priority (higher first) then FIFO within a tier:
  - not full → `ACCEPTED`;
  - full & new frame outranks the lowest-priority frame → `EVICTED_LOWER` (evict low frame; CRITICAL not blocked by INFO flood);
  - full & no eviction possible → `REJECTED_SLOW` → caller **disconnects the slow client** (`ClientSession.onSlowClient`).
- **One writer thread/session** (`ClientSession.writerLoop` ✅): all server→client frames go through one queue + one writer → **no interleaving of different frames' bytes** on the same Socket (blueprint §6.4).
- **Global priority scheduling** (`PriorityScheduler` + `FanoutEngine` ✅): `CRITICAL > EMERGENCY > WARNING > INFO` (ascending `Priority` ordinal), bounded burst + fairness (process at most 8 higher-tier items then consider a lower tier — blueprint §7.6); critical jobs are never silently dropped; INFO may be delayed/coalesced under a documented policy.
- **Metrics**: queue depth per priority/session, high-water mark, `evictedCount()` (NFR-011, NFR-014).
- **Network I/O outside DB transactions** (blueprint §7.6, §8.3): enqueue only after commit; retry scheduling idempotent.

### 3.7. Session expiry & session management (against T3)

- **Heartbeat/presence** ✅ (`SessionRegistry`): heartbeat 10s, suspect after **3 misses** (`heartbeatIntervalMs=10000`, `heartbeatMaxMissed=3`); `sweep(now, interval, maxMissed)` is pure logic on an injectable `Clock` → deterministic tests; caller audits + closes suspects.
- **Close old sessions on reconnect** ✅: `deviceOnline(session)` replaces the previous session of the same `deviceId` and calls `close()` on it → prevents parallel stale sessions.
- **Strong random sessionId**: `Json.uuid()` = `UUID.randomUUID()` (UUIDv4, **122-bit entropy from CSPRNG `SecureRandom`**) → satisfies OWASP Session Management (entropy ≥ 64 bits, length ≥ 128 bits, meaningless content). `messageId`/`correlationId` are also UUIDv4.
- **sessionId is not authorization evidence outside the authenticated connection** (PROTOCOL §7.4): every command still goes through role/scope checks on the authenticated session.
- 🔧 **Idle + absolute expiry** (OWASP Session Management; FR-004 "session expiry", "session revoke works"): the gateway needs idle TTL and absolute TTL for operator sessions, session revocation on logout/privilege change, and sessionId renewal on privilege change (against session fixation). `ClientSession` currently has only heartbeat/suspect, **no** absolute expiry field.
- **LOGOUT** destroys the session; reconnect creates a new authenticated session + `SYNC_REQUEST`/`SYNC_RESULT` (UC-03).

### 3.8. Secret masking in logs (against T8, T10)

- **No credential logging** ✅: `AuthService` does not log passwords/credentials; `Argon2idHasher` wipes `char[]`; `AlertService`/`FanoutEngine` log only `requestId`/`deliveryId`/`alertId` (business identifiers, not secrets).
- **Minimal-PII audit** ✅ (`AuditService`): records `actorId, action, scope, outcome, correlationId, payload` — payload is business metadata (requestId/alertId/priority/targets), no passwords.
- 🟡 **Centralized masking filter** (NFR-016): 🔧 a log filter (logback pattern/layout) is needed to mask all sensitive fields (password, token, credential, secret) before writing; there is no central filter yet — not logging secrets relies on per-call-site discipline.
- **T-28 log scan** (TEST-PLAN): scanning logs for secrets/passwords/raw tokens must be clean; real evidence must include a runId.
- **Permission-controlled incident reports** (NFR-016): only authorized roles may view reports containing sensitive information.

### 3.9. Sanitized errors (against T8, information disclosure)

- **Typed error codes** (PROTOCOL §7.3): `AUTH_REQUIRED, ACCESS_DENIED, INVALID_SCOPE, INVALID_FRAME, INVALID_VERSION, MESSAGE_TOO_LARGE, RATE_LIMITED, CONFLICT, EXPIRED, SERVER_BUSY, INTERNAL_ERROR` — with `correlationId` for tracing without leaking internals (NFR-017).
- **Strict error payload** ✅ (`Json.BadPayload`): missing/wrong-typed fields → `BadPayload` with the field name, mapped to `ERROR/BAD_PAYLOAD` on the wire.
- 🟡 **Gap to fix**: `AlertService.publish/update/cancel` currently returns `CommandOutcome.error("INTERNAL", "publish failed: " + e.getMessage())` — **leaking internal exception messages** (may contain SQL details/paths). 🔧 The gateway must **sanitize**: map to `INTERNAL_ERROR` + generic message + `correlationId`, writing full details **only to the server log** (never to the client), as required by NFR-017.
- **No stack traces/paths/SQL exposed to clients**; authentication errors do not distinguish "user does not exist" from "wrong password" (avoid user enumeration) — `AuthService` returns `Optional.empty()` for both.

### 3.10. Audit, tamper-evidence & additional controls

- **Append-only audit** ✅ (`AuditService` + `audit_events`): all high-risk actions (PUBLISH, UPDATE, CANCEL, DENIED, RETRY, DELIVERY_FAILED, SUPERSEDED, DEDUP_SUPPRESS, ALERT_FULLY_ACKED) record actor/scope/outcome/correlationId. Best-effort: an audit write error is logged but **does not break the command path** (this trade-off needs review for real deployment — see §7).
- **Dual operator approval** (blueprint §9, 🔧 optional): for non-emergency training workflows; a demo may require 2 approvals before publishing a simulation scenario to a wide target set. **Not** a rule for real emergency alerts without an approved safety process.
- **Tamper-evidence hash chain** (blueprint §9, stretch 🔧): add `previous_hash` + `event_hash` to the incident event log; unauthorized modification is detected when the **chain head is anchored outside the DB**. **Limitation**: a *standalone* hash chain is not immutable and cannot stop an administrator from rewriting the whole DB.
- **Connection limits / rate limiting** (blueprint §9, 🔧): cap concurrent connections, frames/second quota, `RATE_LIMITED`/`SERVER_BUSY` against DoS.
- **PII minimization** (blueprint §9): collect only the personal data needed for ACK_USER accountability; bounded retention (NFR-012).
- **Credential rotation/revocation** (FR-003): devices/users can rotate credentials and be disabled; `enabled=0` blocks authentication immediately.

---

## 4. RBAC matrix (role x action x scope)

### 4.1. Roles in source code

`aegis.protocol.Role` ✅ has **5** values: `SYSTEM_ADMIN, SECURITY_OPERATOR, FLOOR_MANAGER, EMPLOYEE, DEVICE`.

**Blueprint persona §1.1 → Role mapping:**

| Persona (blueprint) | Role in code | Notes |
|---|---|---|
| System Administrator | `SYSTEM_ADMIN` | Manages buildings/devices/users/grants/retention/policy. |
| Emergency Operations Controller | `SECURITY_OPERATOR` (building-wide grant) or `SYSTEM_ADMIN` | Coordinates incidents/alerts by building/floor. |
| Security Operator | `SECURITY_OPERATOR` | Publish/cancel in the granted scope; cannot change own permissions. |
| Floor Manager | `FLOOR_MANAGER` | Publish only to floors in scope; cannot extend targets with arbitrary payload. |
| Employee | `EMPLOYEE` | Receive alerts + ACK_USER; personal identity if accountability is needed. |
| Device Agent | `DEVICE` | Device identity, local storage, ACK_RECEIVED, heartbeat, sync. |
| Instructor/Observer | *(not a Role enum value)* | **Read-only** demonstration mode, cannot publish real alerts. |

### 4.2. Action matrix

Cell convention: **✓** = allowed; **✗** = denied (deny-by-default); **S** = allowed *within scope* (see §4.3). Server→client commands (`ALERT_PUSH`, `CANCEL_PUSH`, `COMMAND_RESULT`, `SYNC_RESULT`, `SERVER_HELLO`, `AUTH_RESULT`, `HEARTBEAT_ACK`, `STATUS_*`) are not client-initiated and are not listed as client permissions.

| Action group (MessageType) | SYSTEM_ADMIN | SECURITY_OPERATOR | FLOOR_MANAGER | EMPLOYEE | DEVICE | Scope / condition |
|---|---|:--:|:--:|:--:|:--:|---|
| User/device enrollment, role grants, retention/policy administration | ✓ | ✗ | ✗ | ✗ | ✗ | System-wide; no self-elevation |
| `INCIDENT_CREATE`, `INCIDENT_TRANSITION` | ✓ | S | S | ✗ | ✗ | In granted building (SECOPS) / floor (FM) — *design* |
| `ALERT_CREATE`, `ALERT_PUBLISH` | ✓ | S | S | ✗ | ✗ | **`ScopedRBAC.canPublish` ✅**; building/floor scope from DB |
| `ALERT_UPDATE`, `ALERT_CANCEL` | ✓ | S | S | ✗ | ✗ | Must match the scope of the alert being edited/cancelled — *router check* |
| `STATUS_SUBSCRIBE` (dashboard read) | ✓ | S | S | ✗ | ✗ | Read in scope; Instructor/Observer read-only demo mode |
| `ACK_RECEIVED` | ✗ | ✗ | ✗ | ✗ | ✓ | Only for the **authenticated device's own** delivery |
| `ACK_USER` | ✗ | ✗ | ✗ | ✓ | ✓ | Human gesture; real user identity if accountability is needed |
| `ACK_CANCEL` | ✗ | ✗ | ✗ | ✗ | ✓ | Cancellation receipt to the correct device |
| `SYNC_REQUEST` | ✗ | ✗ | ✗ | ✗ | ✓ | Own device state only |
| `HEARTBEAT` | ✓ | ✓ | ✓ | ✗ | ✓ | Keepalive of an authenticated session |
| `AUTH_OPERATOR` | ✓ | ✓ | ✓ | ✓ | ✗ | Operator authentication (Argon2id) |
| `AUTH_DEVICE` | ✗ | ✗ | ✗ | ✗ | ✓ | Device authentication (Argon2id) |
| `HELLO`, `LOGOUT` | ✓ | ✓ | ✓ | ✓ | ✓ | Session lifecycle |

> **Enforcement status:** the `ALERT_CREATE/ALERT_PUBLISH` column (and scope rules) is implemented in `ScopedRBAC.canPublish` + `RbacService` ✅ and tested by `ScopedRBACTest` (T-07). Other cells are **designed authorization policies** (FR-006, blueprint §7.1) that the **gateway/router 🔧 must enforce per message** before calling services; `AlertService`/`FanoutEngine` assume the router already checked RBAC. This matrix is a specification for completing the router, not a claim that every cell runs end-to-end.

### 4.3. Scope rules by role (source: DB `role_grants`)

| Role | Publish scope | Mechanism |
|---|---|---|
| `SYSTEM_ADMIN` | All buildings/floors/zones | `canPublish → true` |
| `SECURITY_OPERATOR` | Granted building (all its floors) | `Grants.coversBuilding(buildingId)`; grant with `floor_id=NULL` → whole building |
| `FLOOR_MANAGER` | Only the granted floor within the granted building | `Grants.coversFloor(buildingId, floorId)`; grant with a specific `floor_id` |
| `EMPLOYEE` | No publish | `canPublish → false` |
| `DEVICE` | No publish | `canPublish → false` |

- `Grants.none()` (empty) → every scoped role is denied.
- `RbacService.canPublish` substitutes `floorId=null` with `BUILDING_WIDE_FLOOR="*"` (does not match a specific floor grant) → only SYSTEM_ADMIN or a building-wide grant passes.
- **Target snapshot** resolved from the DB (`TargetResolver.resolveBuilding/resolveFloor/resolveZone`), not from client payload; an empty target set → `NO_TARGETS` (explicit error, no silent broadcast).

---

## 5. Control ↔ NFR/FR ↔ test mapping

| Control | NFR/FR | Test (TEST-PLAN) | Chaos |
|---|---|---|---|
| TLS truststore verify, no trust-all | NFR-009, FR-001 | T-05, T-06 | C09 |
| RBAC scope per command, deny-by-default | NFR-008, FR-006 | T-07 | C08 |
| Immutable target snapshot | FR-009 | T-08 | — |
| Argon2id, disabled account | FR-003 | T-06 | C09 |
| requestId idempotency | FR-016 | T-09, T-10 | C03 |
| 64KiB frame + depth + strict parse | NFR-010, FR-002 | T-01..04 | C10 |
| Bounded queue + slow-client | NFR-011 | T-23 | C16, C04 |
| Session expiry / reconnect / tombstone | FR-004, FR-015, FR-017 | T-15..17 | C05, C12 |
| Mask secrets in logs | NFR-016 | T-28 | — |
| Sanitized errors | NFR-017 | T-01..04 (ERROR) | C10 |
| UDP spoof / multicast untrusted | FR-031, FR-032 | T-24, T-25 | C13, C14 |
| Append-only audit | FR-020 | T-07 (deny audited) | C08 |

---

## 6. Guide: creating a self-signed truststore with `keytool` (demo)

> Purpose: create a self-signed TLS identity for the EBSP gateway (port 9443) and a truststore so the client can authenticate the server. **Demo/lab only.** An equivalent script already exists: `scripts/gen-server-cert.sh`.

### 6.1. Quick way (existing script)

```bash
cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs
# arguments: [output-dir=certs] [store-password=aegis-demo]
bash scripts/gen-server-cert.sh certs aegis-demo
```

Produces:
- `certs/aegis-server.p12` — PKCS12 **keystore** (server private key + cert, `SAN=localhost,127.0.0.1`);
- `certs/aegis-server.crt` — **PEM** certificate (for client import);
- `certs/aegis-truststore.p12` — PKCS12 **truststore** containing only the server cert.

The script skips key generation if `aegis-server.p12` already exists (delete the file to regenerate).

### 6.2. Manual `keytool` procedure (3 steps, exactly matching the script)

**Step 1 — Generate key pair + self-signed certificate (server keystore):**

```bash
keytool -genkeypair \
  -alias aegis-server \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=localhost, OU=AEGIS-EBS, O=CMP180, L=HoChiMinh, C=VN" \
  -ext "SAN=dns:localhost,ip:127.0.0.1" \
  -keystore certs/aegis-server.p12 -storetype PKCS12 \
  -storepass aegis-demo -keypass aegis-demo
```

**Step 2 — Export the certificate to PEM (for distribution to clients):**

```bash
keytool -exportcert -alias aegis-server \
  -keystore certs/aegis-server.p12 -storetype PKCS12 -storepass aegis-demo \
  -rfc -file certs/aegis-server.crt
```

**Step 3 — Import the cert into the client-side truststore (trust only this cert):**

```bash
keytool -importcert -noprompt -alias aegis-server \
  -file certs/aegis-server.crt \
  -keystore certs/aegis-truststore.p12 -storetype PKCS12 -storepass aegis-demo
```

**Verify the truststore:**

```bash
keytool -list -v -keystore certs/aegis-truststore.p12 -storetype PKCS12 -storepass aegis-demo
# Expected: 1 entry, alias aegis-server, Owner CN=localhost, SAN dns:localhost + ip:127.0.0.1
```

### 6.3. Configure the client to use the truststore

`aegis.client.ClientConfig` ✅ accepts the flags:

```bash
java ... aegis.client.ClientApp \
  --host localhost --port 9443 \
  --device-id DEV-A-F07-C-01 --device-secret <credential> \
  --truststore certs/aegis-truststore.p12 --truststore-password aegis-demo \
  --inbox aegis-client-inbox.db
```

> ⚠️ **Passwords must match.** `ClientConfig.defaults()` uses `truststore.p12` / `"changeit"`, while the cert script uses `"aegis-demo"`. Pass the correct `--truststore` and `--truststore-password` for the files you created, or the handshake fails. `TlsSupport.storeType` selects `JKS` for `.jks`/`.keystore` extensions, otherwise `PKCS12`.

### 6.4. Real 2-machine LAN demo (multicast)

The default certificate has only `SAN=dns:localhost,ip:127.0.0.1` → it **cannot** authenticate when a client on another machine connects via a LAN IP. Regenerate the cert with a SAN containing the server's real hostname/IP:

```bash
keytool -genkeypair -alias aegis-server \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=aegis-server.local, OU=AEGIS-EBS, O=CMP180, L=HoChiMinh, C=VN" \
  -ext "SAN=dns:aegis-server.local,ip:192.168.1.10" \
  -keystore certs/aegis-server.p12 -storetype PKCS12 \
  -storepass <strong-password> -keypass <strong-password>
# then exportcert + importcert as in §6.2, distribute aegis-server.crt to machine 2
```

Machine 2 client: `--host 192.168.1.10 --truststore <imported-file> --truststore-password <password>`. In the lab, `DiscoveryClient.discoverTrusted()` additionally checks `CN == serverId` and nonce echo (§3.1).

### 6.5. Limitations of the self-signed truststore (MUST be stated in the defense)

1. **Not a public CA.** A self-signed cert is not trusted by browsers/OS by default; it works only because the client **actively pins** the cert in a private truststore. There is no trust chain to a public root CA.
2. **No public revocation.** No OCSP/CRL; revocation = delete the cert from the truststore and regenerate. There is no distributed revocation mechanism.
3. **Public demo passwords.** `aegis-demo` (script) and demo secrets (`*-demo-secret`, fleet-shared `device-demo-secret`) are in source code → **lab only**, never production. Production needs strong passwords, per-device secrets, centralized key management (KMS/HSM).
4. **Narrow demo CN/SAN.** Default `CN=localhost`, loopback SAN → unusable on a real LAN without regeneration (§6.4).
5. **Short demo validity** (365 days) and RSA 2048 are the minimum acceptable for a lab; production should consider stronger keys/ECDSA and a rotation process.
6. **Not a replacement for real PKI.** This simulates *server authentication via truststore* to illustrate NFR-009 and counter T1; no industrial safety certification is claimed.
7. **Shared device secret is a demo trade-off.** `ServerStore.seedWorld` hashes one shared credential for the whole fleet for lab convenience; this **breaks** per-device identity (T2) — production must issue per-device credentials + per-device rotate/revoke (FR-003).

---

## 7. OWASP checklist

Status: **Met** ✅ · **Partial/demo** 🟡 · **Designed (awaiting gateway)** 🔧 · **Not done** ✗.

### 7.1. OWASP Authorization Cheat Sheet

| # | OWASP recommendation | AEGIS current state | Status | Evidence |
|---|---|---|---|---|
| A1 | Enforce least *** | Scoped roles; EMPLOYEE/DEVICE cannot publish; minimal grants | ✅ | `ScopedRBAC`, `Grants` |
| A2 | Deny by default | `Grants.none()` → denied; unmatched role → false | ✅ | `ScopedRBAC.canPublish` |
| A3 | Check authorization at EVERY request/command | Router checks type against role before services | 🔧 | `AlertService` assumes router; gateway not present |
| A4 | Prevent horizontal/vertical escalation | Building/floor scope from DB; no self-service privilege change | ✅ 🔧 | `RbacService.grantsFor`; router |
| A5 | Do not trust client-supplied role/scope | Server derives scope from DB, ignores client metadata | ✅ | blueprint §7.2; `TargetResolver` |
| A6 | Log authorization violations (attributable) | DENIED audit + actor/scope/correlationId | ✅ 🔧 | `AuditService`; router records DENIED |
| A7 | Fail safely on check errors | `Objects.requireNonNull`; null grants → none | ✅ | `ScopedRBAC`, `Grants` |
| A8 | Test deny-by-default + abnormal conditions | `ScopedRBACTest` (T-07, C08) | ✅ | 73 core tests |
| A9 | Separate authentication ≠ authorization | `AuthService` (identity) ≠ `RbacService` (scope) | ✅ | two separate services |

### 7.2. OWASP Session Management Cheat Sheet

| # | OWASP recommendation | AEGIS current state | Status | Evidence |
|---|---|---|---|---|
| S1 | Session ID entropy ≥ 64 bit, CSPRNG | UUIDv4 = 122-bit entropy (`SecureRandom`) | ✅ | `Json.uuid()` |
| S2 | Session ID length ≥ 128 bit, meaningless content | 128-bit UUID, no PII | ✅ | `Json.uuid()` |
| S3 | Session ID is not authorization evidence outside the authenticated connection | Role/scope checked per command on the session | ✅ 🔧 | PROTOCOL §7.4; router |
| S4 | Idle + absolute expiry | Heartbeat suspect (10s/3 misses) ✅; no absolute TTL yet | 🟡 🔧 | `SessionRegistry.sweep`; FR-004 |
| S5 | Renew session ID on privilege change / against fixation | Reconnect creates new session; renewal on privilege change unclear | 🔧 | UC-03; gateway |
| S6 | Session revocation (logout/revoke) | `LOGOUT`, `closeAll`, `deviceOnline` close old sessions | ✅ 🔧 | `SessionRegistry`; FR-004 |
| S7 | Protect session store from exposure | Sessions in server RAM; raw tokens not persisted | ✅ | `SessionRegistry` (ConcurrentHashMap) |
| S8 | No session ID leak via URL/log | sessionId not logged; TLS channel | 🟡 🔧 | masking filter §3.8 |
| S9 | TLS protects the session exchange channel | TLS 9443, truststore verification | ✅ | `TlsSupport`; NFR-009 |

### 7.3. OWASP Password Storage Cheat Sheet

| # | OWASP recommendation | AEGIS current state | Status | Evidence |
|---|---|---|---|---|
| P1 | No plaintext storage | Only Argon2id hashes | ✅ | `users.password_hash`, `devices.credential_hash` |
| P2 | Use Argon2id (memory-hard) | `Argon2Factory.Argon2Types.ARGON2id` | ✅ | `Argon2idHasher` |
| P3 | Minimum parameters m=19 MiB, t=2, p=1 | Demo m=16 MiB, t=2, p=1 → **below threshold** | 🟡 | `ServerConfig` (raise to ≥19456 KiB in production) |
| P4 | Unique salt per password | Argon2id generates salt embedded in `$argon2id$` | ✅ | `Argon2idHasher.hash` |
| P5 | No fast hash (SHA-256) for passwords | SHA-256 only for idempotency payload_hash | ✅ | `AlertService.sha256` (not passwords) |
| P6 | Wipe secrets from memory | `wipeArray(char[])` in finally | ✅ | `Argon2idHasher` |
| P7 | Safe verify, no crash on bad hash | `$argon2id$` prefix check; malformed → false | ✅ | `Argon2idHasher.verify` |
| P8 | Pepper (optional defense-in-depth) | Not used | ✗ | recommend adding a key outside the DB |
| P9 | Rotate/revoke credentials | `enabled=0` blocks auth; rotation via hash update | ✅ 🟡 | `AuthService`; FR-003 |
| P10 | No password logging | No credential logging; wipe | ✅ 🟡 | §3.8; T-28 |

---

## 8. Security limitations & unfinished work (honest)

1. 🔧 **Gateway/router (`ServerMain`) not yet in source code.** Per-message RBAC enforcement, pre-auth message rejection, read timeout/quota, rate limiting and wire-level error sanitization are **design contracts** awaiting completion. Backend services are ready + tested.
2. 🔧 **Absolute session TTL** and sessionId renewal on privilege change are not implemented (only heartbeat/suspect exists).
3. 🟡 **Demo Argon2id parameters (16 MiB) below OWASP recommendation (19 MiB).** Must be raised for production.
4. 🟡 **`StreamReadConstraints` not explicitly configured** (relies on Jackson default 1000 + 64KiB hard cap).
5. 🟡 **`INTERNAL` errors currently include `e.getMessage()`** → must be sanitized at the gateway before reaching clients (NFR-017).
6. 🟡 **No centralized log masking filter**; not leaking secrets relies on call-site discipline + T-28 scan.
7. 🟡 **CONFLICT for duplicate requestId with a different payload** is not fully returned (only `payload_hash` stored, original result preferred).
8. 🟡 **Public demo credentials + shared device secret** → lab only, not production.
9. 🔧 **Dual-approval, hash-chain tamper-evidence, pepper, connection/rate limits** are designs/stretch goals, not implemented.
10. **Self-signed TLS** is not a public CA, no OCSP/CRL (§6.5).
11. **Single-node** → no HA; best-effort audit may lose events if an audit write fails (trade-off to revisit).
12. **Not a certified life-safety system**; every scenario is SIMULATION/TRAINING.

---

## 9. References

1. `Plan/AEGIS_MASTER_BLUEPRINT.md` — §9 (Security & privacy), §7 (EBSP/2.0), §3 (NFR-008..018), §5 (invariants), §6 (architecture), §12 (chaos), §16 (Q&A).
2. `docs/PROTOCOL.md` — EBSP/2.0 wire contract (framing, envelope, idempotency, message catalog, TLS primary / multicast advisory).
3. OWASP **Authorization** Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
4. OWASP **Session Management** Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
5. OWASP **Password Storage** Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
6. IETF **RFC 9106** — Argon2 Inputs and Outputs (Argon2id parameters).
7. IETF **RFC 8085** — UDP Usage Guidelines (unreliable multicast/discovery).
8. Oracle Java SE — `SSLSocket` / `SSLServerSocket` / `TrustManagerFactory` / `KeyStore`.
9. OASIS **CAP 1.2** — severity/urgency/certainty metadata (separate from broadcast priority).
10. Cross-checked source code: `aegis.protocol.{Protocol,FrameCodecImpl,Role,MessageType,Priority}`, `aegis.core.{ScopedRBAC,Grants,AckLedger,DedupIndex,RetryPolicy,PriorityScheduler,TargetResolver}`, `aegis.server.{AuthService,Argon2idHasher,RbacService,SessionRegistry,ClientSession,BoundedWriterQueue,FanoutEngine,AlertService,AuditService,ServerStore}`, `aegis.client.{TlsSupport,ClientConfig,InboxStore}`, `aegis-network-lab` (lab-guide.md), `scripts/gen-server-cert.sh`.
