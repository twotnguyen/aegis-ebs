# ĐÁNH GIÁ AN TOÀN ĐỘC LẬP — AEGIS-EBS

> Tài liệu thẩm định bảo mật grounded mã nguồn thật. Phân biệt rõ **đã hiện thực** (✅), **mức demo** (🟡), **thiết kế/chưa làm** (🔧). SIMULATION/TRAINING ONLY.

## 1. Phạm vi & ranh giới tin cậy
Hệ thống 4 plane: command (operator/dashboard), delivery (TCP/TLS fanout), persistence/observability (SQLite WAL + audit + metrics), discovery/experiment (UDP/multicast **không tin cậy**). Chỉ kênh TCP/TLS đã xác thực mới kích hoạt hiển thị cảnh báo; UDP/multicast chỉ là con trỏ/hint.

## 2. Xác minh control đã hiện thực (file:dòng)
| Control | Hiện thực | Bằng chứng |
|---|---|---|
| TLS server identity | ✅ | `TlsGateway` dùng `SSLServerSocket` + PKCS12 keystore; client `TlsSupport` pin truststore, **không trust-all**; từ chối cert lạ (PKIX) — xác minh bằng probe review-c |
| Auth gate | ✅ | `MessageRouter` chỉ chấp nhận HELLO/AUTH_* trước auth; command khác → NOT_AUTHENTICATED |
| RBAC scope deny-by-default | ✅ | `RbacService.grantsFor` đọc `role_grants` từ DB; `ScopedRBAC.canPublish`: SYSTEM_ADMIN mọi nơi, SECURITY_OPERATOR building grant, FLOOR_MANAGER chỉ floor grant, EMPLOYEE/DEVICE không publish; probe EMPLOYEE/FM vượt scope → FORBIDDEN |
| Argon2id password | 🟡 | `Argon2idHasher` (argon2-jvm); tham số demo 16 MiB < khuyến nghị OWASP 19 MiB — cần nâng khi production |
| Frame giới hạn | ✅ | `FrameCodecImpl` chặn length >65536 TRƯỚC cấp phát; UTF-8 strict (REPORT); JSON single object; version==2; type enum |
| Bounded writer queue | ✅ | `BoundedWriterQueue` 256/session, priority eviction, slow-client disconnect + audit SLOW_CLIENT_DISCONNECT |
| Pre-auth TTL/cap | ✅ | `TlsGateway` soTimeout=preAuthTimeoutMs (mặc định 10s) + absolute TTL từ `ClientSession.createdAt` (heartbeat không gia hạn); maxConnections (mặc định 512); audit PRE_AUTH_EXPIRED |
| requestId idempotency | ✅ | `alert_operations.request_id UNIQUE` + payload_hash canonical; duplicate requestId → trả alert gốc, không tạo mới |
| ACK cross-device guard | ✅ | `MessageRouter.ack` kiểm deviceId/alertId/revision khớp deliveryId; `DeliveryRepo.recordAck` ràng buộc ownership (M1 fix) |
| Sanitize error | ✅ | INTERNAL trả thông điệp chung, chi tiết chỉ LOG.warn (NFR-017, S-04 fix) |
| ACK_USER userId verify | ✅ | kiểm userId tồn tại + enabled trong users (S-06 fix) |
| Read-plane RBAC | 🟡 | STATUS_SUBSCRIBE chặn EMPLOYEE/DEVICE, scope theo building/floor; WS feed là trusted-LAN read plane + cap 64 subscriber (S-05) |
| Audit append-only | ✅ | `audit_events` ghi actor/scope/outcome/correlationId; denied publish hiển thị trong audit |
| Timestamp fixed-width | ✅ | `Json.ts` + `Sql.TS` appendInstant(9) → so sánh expiry/ordering đúng (S-08) |
| Atomic lifecycle | ✅ | `ServerStore.applyUpdate/applyCancel/createIncidentAtomic/transitionIncidentAtomic` một BEGIN IMMEDIATE + ROLLBACK (S-09) |
| Recovery retry budget | ✅ | FAILED settled không vượt maxAttempts (S-10) |

## 3. Threat model (STRIDE)
| ID | Mối đe | STRIDE | Control | Test | Trạng thái |
|---|---|---|---|---|---|
| T1 | Rogue LAN spoof discovery | Spoofing | UDP nonce echo + TLS pin + CN==serverId + SERVER_HELLO nonce | T-24, C13 | ✅ chặn |
| T2 | stolen device credential | Spoofing | credential riêng/device, revoke enabled=0 | T-06 | 🟡 demo secret chung |
| T3 | session replay | Replay | sessionId UUIDv4 + TLS + pre-auth TTL | C09 | ✅ |
| T4 | role escalation | Elevation | RBAC server-side mỗi lệnh, scope từ DB | T-07, C08 | ✅ |
| T5 | out-of-scope broadcast | Elevation | TargetResolver snapshot + ScopedRBAC | T-08 | ✅ |
| T6 | malformed frame | DoS/Tamper | frame cap + UTF-8/JSON strict + max depth | T-09, C10 | ✅ |
| T7 | slow-client exhaustion | DoS | bounded queue 256 + eviction | T-23, C16 | ✅ |
| T8 | log exposure | Info disclosure | mask secrets, sanitized error | T-28 | ✅ |
| T9 | privileged misuse | Elevation | audit high-risk + (optional dual approval) | C08 | 🟡 |
| T10 | DB exposure | Info disclosure | file permission, PII minimization | — | 🔧 |

## 4. RBAC matrix (role × thao tác × scope)
| Role | publish | cancel | read status | manage users | scope |
|---|---|---|---|---|---|
| SYSTEM_ADMIN | ✅ | ✅ | ✅ | ✅ | global |
| SECURITY_OPERATOR | ✅ | ✅ | ✅ | ❌ | building grant |
| FLOOR_MANAGER | ✅ | ✅ | ✅ (floor grant) | ❌ | floor grant |
| EMPLOYEE | ❌ | ❌ | ❌ (subscribe denied) | ❌ | device |
| DEVICE | ❌ | ❌ | ❌ | ❌ | self ACK |

## 5. Residual risks & khuyến nghị
- 🟡 WS status feed là trusted-LAN read plane (chưa TLS riêng) — production cần TLS + auth cho WS.
- 🟡 Demo credentials công khai (admin-demo-secret…), device secret dùng chung — production cần secret riêng + vault.
- 🟡 Self-signed CA, không OCSP/CRL — production dùng CA tin cậy + rotation.
- 🟡 Argon2id 16 MiB < OWASP 19 MiB (m≥19 MiB, t=2, p=1).
- 🔧 Chưa có absolute session TTL tập trung / masking filter tập trung — khuyến nghị bổ sung.
- 🔧 Hash-chain audit (tamper evidence) là stretch, chưa làm.

## 6. OWASP checklist (tóm tắt)
- **Authorization**: deny-by-default ✅; kiểm mỗi lệnh ✅; scope từ server ✅; không tin client role ✅; audit denied ✅.
- **Session Management**: entropy UUIDv4 ≥122 bit ✅; TLS ✅; expiry pre-auth ✅; revoke ✅; absolute TTL 🔧.
- **Password Storage**: Argon2id ✅ (tham số cần nâng 🟡); salt riêng ✅; không log secret ✅; rotation 🔧.

## 7. Kết luận
Lõi bảo mật ở mức **khá tốt cho đồ án**: TLS pinning, RBAC scope, idempotency, frame hardening, backpressure, audit, sanitize error đều đã hiện thực và kiểm chứng bằng probe đối kháng. Các khoảng trống còn lại chủ yếu ở mức **demo→production** (WS TLS, secret management, CA, tham số Argon2id) — đã ghi rõ giới hạn, không tuyên bố an toàn tuyệt đối.
