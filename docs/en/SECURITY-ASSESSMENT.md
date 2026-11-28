Language: English | Vietnamese original: docs/SECURITY-ASSESSMENT.md

# INDEPENDENT SECURITY ASSESSMENT — AEGIS-EBS

> Security review document grounded in the actual source code. It clearly distinguishes **implemented** (✅), **demo-level** (🟡), and **designed/not yet done** (🔧). SIMULATION/TRAINING ONLY.

## 1. Scope & trust boundaries
The system has 4 planes: command (operator/dashboard), delivery (TCP/TLS fanout), persistence/observability (SQLite WAL + audit + metrics), discovery/experiment (**untrusted** UDP/multicast). Only an authenticated TCP/TLS channel may trigger alert display; UDP/multicast are only pointers/hints.

## 2. Verification of implemented controls (file:line)
| Control | Status | Evidence |
|---|---|---|
| TLS server identity | ✅ | `TlsGateway` uses `SSLServerSocket` + PKCS12 keystore; client `TlsSupport` pins the truststore, **no trust-all**; rejects unknown certs (PKIX) — verified by review-c probe |
| Auth gate | ✅ | `MessageRouter` accepts only HELLO/AUTH_* before authentication; other commands → NOT_AUTHENTICATED |
| Scoped RBAC deny-by-default | ✅ | `RbacService.grantsFor` reads `role_grants` from the DB; `ScopedRBAC.canPublish`: SYSTEM_ADMIN everywhere, SECURITY_OPERATOR granted building, FLOOR_MANAGER granted floor only, EMPLOYEE/DEVICE cannot publish; EMPLOYEE/FM out-of-scope probes → FORBIDDEN |
| Argon2id password | 🟡 | `Argon2idHasher` (argon2-jvm); demo parameters 16 MiB < OWASP recommendation of 19 MiB — must be raised for production |
| Frame cap | ✅ | `FrameCodecImpl` blocks length >65536 BEFORE allocation; strict UTF-8 (REPORT); single JSON object; version==2; type enum |
| Bounded writer queue | ✅ | `BoundedWriterQueue` 256/session, priority eviction, slow-client disconnect + SLOW_CLIENT_DISCONNECT audit |
| Pre-auth TTL/cap | 🟡 | `TlsGateway` has soTimeout=preAuthTimeoutMs (10s), maxConnections (512), PRE_AUTH_EXPIRED audit ✅; **no absolute TTL for authenticated sessions** (`SECURITY.md` §3.7/§8) |
| requestId idempotency | ✅ | `alert_operations.request_id UNIQUE` + canonical payload_hash; duplicate requestId → returns original alert, no new one |
| ACK cross-device guard | ✅ | `MessageRouter.ack` checks deviceId/alertId/revision against deliveryId; `DeliveryRepo.recordAck` enforces ownership (M1 fix) |
| Sanitized errors | ✅ | INTERNAL returns a generic message, details only in LOG.warn (NFR-017, S-04 fix) |
| ACK_USER userId verification | ✅ | checks that userId exists and is enabled in users (S-06 fix) |
| Read-plane RBAC | 🟡 | STATUS_SUBSCRIBE blocks EMPLOYEE/DEVICE, scoped by building/floor; WS feed is a trusted-LAN read plane with a cap of 64 subscribers (S-05) |
| Append-only audit | ✅ | `audit_events` records actor/scope/outcome/correlationId; denied publishes appear in audit |
| Fixed-width timestamp | ✅ | `Json.ts` + `Sql.TS` appendInstant(9) → correct expiry/ordering comparison (S-08) |
| Atomic lifecycle | ✅ | `ServerStore.applyUpdate/applyCancel/createIncidentAtomic/transitionIncidentAtomic` use one BEGIN IMMEDIATE + ROLLBACK (S-09) |
| Recovery retry budget | ✅ | FAILED is settled and never exceeds maxAttempts (S-10) |

## 3. Threat model (STRIDE)
| ID | Threat | STRIDE | Control | Test | Status |
|---|---|---|---|---|---|
| T1 | Rogue LAN spoof discovery | Spoofing | UDP nonce echo + TLS pin + CN==serverId + SERVER_HELLO nonce | T-24, C13 | ✅ blocked |
| T2 | stolen device credential | Spoofing | per-device credential, revoke via enabled=0 | T-06 | 🟡 shared demo secret |
| T3 | session replay | Replay | sessionId UUIDv4 + TLS + pre-auth TTL | C09 | ✅ |
| T4 | role escalation | Elevation | server-side RBAC on every command, scope from DB | T-07, C08 | ✅ |
| T5 | out-of-scope broadcast | Elevation | TargetResolver snapshot + ScopedRBAC | T-08 | ✅ |
| T6 | malformed frame | DoS/Tamper | frame cap + strict UTF-8/JSON + max depth | T-09, C10 | ✅ |
| T7 | slow-client exhaustion | DoS | bounded queue 256 + eviction | T-23, C16 | ✅ |
| T8 | log exposure | Info disclosure | sanitized errors ✅; centralized masking filter 🔧 | T-28 | 🟡 |
| T9 | privileged misuse | Elevation | audit for high-risk actions + (optional dual approval) | C08 | 🟡 |
| T10 | DB exposure | Info disclosure | file permissions, PII minimization | — | 🔧 |

## 4. RBAC matrix (role x action x scope)
| Role | publish | cancel | read status | manage users | scope |
|---|---|---|---|---|---|
| SYSTEM_ADMIN | ✅ | ✅ | ✅ | ✅ | global |
| SECURITY_OPERATOR | ✅ | ✅ | ✅ | ❌ | building grant |
| FLOOR_MANAGER | ✅ | ✅ | ✅ (floor grant) | ❌ | floor grant |
| EMPLOYEE | ❌ | ❌ | ❌ (subscribe denied) | ❌ | device |
| DEVICE | ❌ | ❌ | ❌ | ❌ | self ACK |

## 5. Residual risks & recommendations
- 🟡 WS status feed is a trusted-LAN read plane (no separate TLS yet) — production needs TLS + auth for WS.
- 🟡 Demo credentials are public (admin-demo-secret…), device secret is shared — production needs per-secret credentials + a vault.
- 🟡 Self-signed CA, no OCSP/CRL — production should use a trusted CA + rotation.
- 🟡 Argon2id 16 MiB < OWASP 19 MiB (m≥19 MiB, t=2, p=1).
- 🔧 No centralized absolute session TTL / centralized masking filter yet — recommended additions.
- 🔧 Hash-chain audit (tamper evidence) is a stretch goal, not implemented.

## 6. OWASP checklist (summary)
- **Authorization**: deny-by-default ✅; check on every command ✅; scope from server ✅; client role not trusted ✅; denied actions audited ✅.
- **Session Management**: UUIDv4 entropy ≥122 bits ✅; TLS ✅; pre-auth expiry ✅; revocation ✅; absolute TTL 🔧.
- **Password Storage**: Argon2id ✅ (parameters need raising 🟡); unique salt ✅; no secrets in logs ✅; rotation 🔧.

## 7. Conclusion
The security core is **quite good for a capstone project**: TLS pinning, scoped RBAC, idempotency, frame hardening, backpressure, audit and sanitized errors are all implemented and verified with adversarial probes. Remaining gaps are mainly **demo→production** issues (WS TLS, secret management, CA, Argon2id parameters) — limitations are stated explicitly, and absolute safety is not claimed.
