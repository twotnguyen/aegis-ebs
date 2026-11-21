# PEER REVIEW ĐỘC LẬP — `aegis-client` + `aegis-simulator` (review-d)

| | |
|---|---|
| **Ngày review** | 2026-11-21 18:54 (UTC+07) |
| **Reviewer** | Independent peer review — agent `review-d` (KHÔNG phải tác giả module) |
| **Commit được review** | `251b2a8` — *feat(server): TLS gateway+RBAC+fanout+ACK/retry+recovery+WS status (52 tests); feat(client+simulator): JavaFX durable inbox + 100-agent fault-injection fleet (56 tests)* |
| **Phạm vi** | `aegis-client/src/main` (AlertClientEngine, InboxStore, AlertRecord, ClientConfig, TlsSupport, ClientApp, ClientListener) + `aegis-simulator/src/main` (SimAgent, FaultInjector, FaultProfile, FaultDecision, SimStats, SimulatorConfig, SimulatorMain, TlsSupport) + toàn bộ test suite 2 module; đối chiếu `docs/PROTOCOL.md`, `aegis-core` (DedupIndex, RetryPolicy) và wire contract của aegis-server |
| **Phương pháp** | (1) chạy lại `mvn -q -pl aegis-client,aegis-simulator test`, đối chiếu **surefire XML** (không dựa stdout `-q`); (2) audit đọc từng dòng source chính + test; (3) **6 probe đối kháng độc lập** chạy thật trong scratch (không sửa source); (4) kiểm tra nhãn SIMULATION của JavaFX shell |
| **Ràng buộc** | CHỈ đọc + chạy + báo cáo. Không sửa source, không git commit. Xác nhận `git diff --stat -- aegis-client aegis-simulator` **rỗng** trước và sau review. Scratch: `/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-d` |
| **Môi trường** | macOS 15.7.3 · Apache Maven 3.10.1 · Homebrew OpenJDK 27.0.1.1 (pom `release=24`) · sqlite-jdbc 3.46.1.0 · jackson 2.17.2 |

> Ngoài phạm vi: các file WIP chưa commit của agent khác (`aegis-persistence/*`, `certs/`). Không ảnh hưởng kết quả test client/simulator (56/56 xanh với working tree hiện tại).
>
> **Ghi chú thời điểm:** trong lúc review, HEAD chuyển từ `251b2a8` → `df0241b` (agent khác commit fix persistence). Đã xác minh `git diff 251b2a8 df0241b -- aegis-client aegis-simulator` **rỗng** — source 2 module được review không đổi, kết quả review giữ nguyên giá trị.

---

## VERDICT: **PASS** (kèm khuyến nghị)

**Tóm tắt (VI):** Cả hai module đạt yêu cầu chấp nhận. **56/56 test pass** (37 client + 19 simulator, 0 failure/error/skip — xác nhận từ surefire XML). Toàn bộ 8 bảo đảm cốt lõi được audit + kiểm chứng bằng test/probe: **durable-commit TRƯỚC ACK_RECEIVED**, **dedup duplicate/stale không hiển thị lại**, **ACK_USER tách biệt gesture người**, **cancel tombstone chặn stale**, **reconnect SYNC + pending-ACK resend**, **heartbeat watchdog**, **TLS từ chối cert không tin cậy**, **fault injection deterministic (đúng 4 RNG draw cố định, cùng seed cùng kết quả)**. JavaFX shell gắn nhãn SIMULATION vĩnh viễn (banner + title + popup), có popup + beep tùy chọn. Phát hiện **1 issue MEDIUM** (controller thread chết lặng khi gặp frame malformed — probe đối kháng xác nhận, không phá vỡ contract với server đúng chuẩn) + **4 LOW** + **6 INFO**. Không có CRITICAL/HIGH.

**Issue count: 11 (0 CRITICAL · 0 HIGH · 1 MEDIUM · 4 LOW · 6 INFO).**

---

## 1. Test rerun — xác nhận từ surefire XML

Lệnh: `cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs && mvn -q -pl aegis-client,aegis-simulator test` → **exit 0** (log: `scratch/review-d/mvn-test.log`).

| Module | Test class | Tests | Fail | Err | Skip |
|---|---|---:|---:|---:|---:|
| aegis-client | AlertRecordTest | 6 | 0 | 0 | 0 |
| aegis-client | ClientConfigTest | 3 | 0 | 0 | 0 |
| aegis-client | ClientEngineTest | 15 | 0 | 0 | 0 |
| aegis-client | ClientTlsSmokeTest | 2 | 0 | 0 | 0 |
| aegis-client | InboxStoreTest | 11 | 0 | 0 | 0 |
| **aegis-client** | **tổng** | **37** | **0** | **0** | **0** |
| aegis-simulator | FaultInjectorTest | 9 | 0 | 0 | 0 |
| aegis-simulator | SimulatorConfigTest | 6 | 0 | 0 | 0 |
| aegis-simulator | SimulatorSmokeTest | 4 | 0 | 0 | 0 |
| **aegis-simulator** | **tổng** | **19** | **0** | **0** | **0** |
| **TỔNG** | | **56** | **0** | **0** | **0** |

Đúng khớp contract 37+19=56. Smoke simulator chạy thật TLS loopback (30/12/8/100 agent, ~30s).

---

## 2. Audit 8 bảo đảm cốt lõi (file:dòng + bằng chứng)

### 2.1 Durable-commit TRƯỚC ACK_RECEIVED — ✅ ĐẠT
- `AlertClientEngine.handleAlert` (`AlertClientEngine.java:321-340`): `inbox.store(a)` (line 330) → `fireAlert(a,true)` (332) → `sendAckReceived` (333) → `markAckReceivedSent` (334). Commit xảy ra trước mọi ACK trên wire.
- `InboxStore.store` (`InboxStore.java:89-141`): `BEGIN IMMEDIATE` → INSERT/UPDATE → `COMMIT`; pragma `journal_mode=WAL`, `synchronous=FULL`, `busy_timeout=5000` áp dụng trên mọi connection (`InboxStore.java:350-359`); test `walModeAndPragmasApplied` xác nhận WAL persist trên file.
- Send thất bại → row vẫn `ack_received_sent_at IS NULL` → `pendingReceivedAcks()` (`InboxStore.java:270-273`) → `resendPendingAcks()` khi reconnect (`AlertClientEngine.java:405-414`). Crash-safe.
- Test đối kháng phía server: `durableCommitAndUserNotificationPrecedeAckReceived` (`ClientEngineTest.java:92-119`) — tại thời điểm ACK_RECEIVED tới server, assert `inbox.find()` non-empty **và** listener đã notify; correlationId trỏ đúng push messageId. `crashBetweenCommitAndAckResendsPendingAckOnReconnect` (:175-190) mô phỏng crash giữa commit và ACK.

### 2.2 Dedup duplicate/stale — không hiển thị lại — ✅ ĐẠT
- `DedupIndex` (aegis-core): monotonic max theo `(alertId, deviceId)`; `seen()` trả true cho revision ≤ max (exact dup + stale), thread-safe.
- Seed từ durable inbox khi start: `AlertClientEngine.java:104-106` ← `inbox.knownRevisions()` (`InboxStore.java:282-292`, `MAX(revision, cancel_revision)` — **bao gồm tombstone**).
- Duplicate/stale: re-ACK idempotent (server ngừng retry) + `fireAlert(a,false)` — không popup (`AlertClientEngine.java:322-339`); `ClientApp.onAlert` return sớm khi `!fresh` (`ClientApp.java:200`).
- Tests: `duplicatePushIsReAckedButNeverRedisplayed`, `staleRevisionSuppressedForDisplay`, `reconnectSendsSyncWithKnownRevisionsAndDedupsRedelivery` (SYNC redelivery không popup lại); `InboxStoreTest.duplicateAndStaleRevisionsSuppressed`, `concurrentStoresOfSameAlertYieldExactlyOneStored` (12 thread đua → đúng 1 STORED).
- **Probe D (scratch):** restart với tombstone rev2 → push stale rev1 + dup rev2 đều suppressed (2), re-ACK đủ 3, rev3 mới reactivate + hiển thị. PASS.

### 2.3 ACK_USER tách biệt gesture người — ✅ ĐẠT
- `ackUser(alertId, userId)` (`AlertClientEngine.java:159-172`): chỉ gọi từ UI button; durable mark `markUserAck` TRƯỚC, send sau; offline → pending, reconnect gửi lại.
- `markUserAck` (`InboxStore.java:153-165`): `WHERE revision=? AND cancelled=0 AND ack_user_at IS NULL` — chỉ advance, không ack lại, không ack cancelled, sai revision → false.
- ACK_RECEIVED không bao giờ hàm ý ACK_USER và ngược lại (2 cột/2 loại frame riêng; test `userAckIsASeparateHumanGesture` assert đúng 1 ACK_RECEIVED + 1 ACK_USER, `offlineUserGestureIsDurableAndSentAfterReconnect`).
- Payload ACK_USER đủ `userId`, `action=ACKNOWLEDGE`, `deliveryId`, `revision`, correlationId = push messageId (`AlertClientEngine.java:384-394`).

### 2.4 Cancel tombstone chặn stale — ✅ ĐẠT
- `handleCancel` (`AlertClientEngine.java:342-369`): **lookup deliveryId TRƯỚC `applyCancel`** (line 350-352 — đúng pitfall đã ghi trong skill: `find()` trả empty cho row cancelled), seed dedup revision tombstone (353), ACK_CANCEL echo `deliveryId` + `operationId` (362-364) để server match delivery row.
- `applyCancel` (`InboxStore.java:177-223`): chỉ nhận khi `revision >= stored && revision > cancel_revision` trước đó; alert chưa biết → tombstone-only row chặn push stale về sau; revision mới hơn tombstone → reactivate (`store` CASE WHEN, line 115).
- Tests: `cancelTombstoneRemovesDisplayAndBlocksStaleRedisplay` (kể cả `ackUser` trên cancelled → false), `cancelOfUnknownAlertBlocksLaterStalePush`, `cancelTombstonePreventsRedisplayAndStaleCancelIgnored`, `syncResultAppliesAlertsAndCancels`.
- **Probe C (scratch):** SYNC_RESULT dùng field **`tombstones`** (field thật của aegis-server, không phải `cancels` của fixture) → cancel applied, ACK_CANCEL gửi với deliveryId=DLV-T. PASS. (Engine xử lý cả 2 field: `AlertClientEngine.java:302`.)

### 2.5 Reconnect: SYNC + pending-ACK resend — ✅ ĐẠT
- `runLoop` (`AlertClientEngine.java:188-235`): reconnect (connectedOnce) → `sendSyncRequest()` mang `knownRevisions` từ inbox (205, 396-402) → `resendPendingAcks()` (206) — ACK_RECEIVED pending trước, ACK_USER pending sau.
- Backoff: `RetryPolicy` (aegis-core) exponential + jitter; default `retryMaxAttempts=4, base=3000` (`ClientConfig.java:21`) → 3s/6s/12s + jitter, 3 retry rồi FAILED — đúng `docs/PROTOCOL.md` dòng 9. Test `retryExhaustionUsesDeterministicExponentialBackoff` so khớp từng delay với reference seeded.
- AUTH_RESULT ok=false → `AuthRejectedException` → **FAILED vĩnh viễn, không retry** (`AlertClientEngine.java:208-213, 256-261`; test `authRejectionFailsFastWithoutRetry` assert đúng 1 connection).
- Wire contract đúng server: AUTH_DEVICE `{deviceCode, credential}` (+ alias deviceId/secret bị server bỏ qua) (`AlertClientEngine.java:246-253`; SimAgent.java:123-128).

### 2.6 Heartbeat watchdog — ✅ ĐẠT (1 lưu ý LOW, xem L1)
- Client-initiated HEARTBEAT theo interval server quảng cáo (SERVER_HELLO/AUTH_RESULT `heartbeatIntervalMs`, guard ≥100ms) (`AlertClientEngine.java:113-133, 264-269`); trả lời HEARTBEAT của server bằng HEARTBEAT_ACK (309-314).
- Watchdog: `soTimeout` → đếm silence; `silentMs >= 3 × heartbeatTimeoutMs` → IOException "heartbeat suspect" → reconnect (`AlertClientEngine.java:277-284`). Test `heartbeatWatchdogReconnectsAfterThreeMissedIntervals` dùng server **nuốt frame im lặng** (đúng pitfall skill — fixture auto-reply sẽ không bao giờ kích hoạt watchdog).
- **Probe F (scratch):** server KHỎE mạnh quảng cáo interval 700ms với cfg timeout 200ms → 2 lần suspect giả + reconnect churn (3 connection/3.5s). Xem issue L1.

### 2.7 TLS untrusted-cert reject — ✅ ĐẠT
- `TlsSupport.clientContext` (`TlsSupport.java:19-35`): TrustManagerFactory init từ đúng truststore → X509 chain validation chuẩn, **không có trust-all bypass**; SSLContext "TLS".
- Test `untrustedServerCertificateIsRejected` (`ClientTlsSmokeTest.java`): cert khác CA → engine FAILED, **0 alert được nhận, 0 ACK gửi đi**; `tlsHandshakeAlertAndAckOverLoopback`: keytool cert thật, SSLSocket phía server, durable alert + ACK qua TLS.
- Simulator cùng TlsSupport (diff chỉ khác package); smoke asserts `sawTlsSocket` cho fleet.
- Lưu ý hardening I1: raw SSLSocket không bật hostname verification mặc định của JSSE (xem dưới).

### 2.8 Fault injection deterministic — ✅ ĐẠT
- `FaultInjector.decide()` (`FaultInjector.java:24-35`): **đúng 4 `nextDouble()` unconditional, thứ tự cố định** (drop, delay-jitter, duplicate, disconnect) — không phụ thuộc rate/timing/thread.
- Per-agent RNG dẫn xuất deterministic từ master seed: `master = new Random(seed)` → `agentSeed = master.nextLong()` theo index (`SimulatorMain.java:63-69`).
- Tests: `sameSeedProducesIdenticalDecisionStream` (1000 decisions), `differentSeedsDiverge`, rate semantics (~30%/~50% trong khoảng chặt), `sameSeedReproducesIdenticalFaultAccounting` (2 fleet run seed 99 lossy → **số liệu giống hệt** end-to-end), `hundredAgentFleetSmoke` (100 agent, 200 ACK chính xác), messageId mới mỗi ACK copy (assert distinct trong `mixedScenario...`).
- **Probe B (scratch):** RNG đếm draw — 1000 decisions → `nextDouble=4000, otherMethods=0`, stream cùng seed giống hệt. PASS.
- Phạm vi determinism: xem I3 (kịch bản có disconnect, aggregate counts phụ thuộc timing reconnect — đúng bản chất, không phải lỗi).

---

## 3. JavaFX shell — nhãn SIMULATION, popup, âm thanh — ✅ ĐẠT

| Hạng mục | Bằng chứng |
|---|---|
| Banner SIMULATION vĩnh viễn | `ClientApp.java:92-94` — nền đỏ `#b71c1c`, chữ trắng, "⚠ SIMULATION / TRAINING ONLY — NOT A REAL EMERGENCY BROADCAST SYSTEM ⚠", không thể tắt |
| Title cửa sổ | `ClientApp.java:140` — "AEGIS Device Client — SIMULATION — deviceId" |
| Popup | `ClientApp.java:207-216` — Alert WARNING, title "SIMULATION — Emergency Alert", header "⚠ SIMULATION / TRAINING — …", body kèm "This is a SIMULATION…" |
| Âm thanh | `ClientApp.java:204-206` — `Toolkit.beep()` sau checkbox "Alert sound" (default tắt, `--sound` bật), bọc try/catch Throwable (headless-safe) |
| Gesture người tách biệt | `ClientApp.java:113-120` — button "Acknowledge (user gesture → ACK_USER)" → `engine.ackUser` |
| Không redisplay duplicate | `ClientApp.java:200` — `if (!fresh) return;` |
| Durable redisplay | `ClientApp.java:138, 158-163, 194` — reload từ inbox lúc mở + mỗi lần ONLINE |
| Payload giả `simulation=false` | `ClientApp.java:67` — vẫn nhãn "SIMULATION?"; banner không phụ thuộc payload; `AlertRecord.java:42` default true khi thiếu |
| Headless CI | JavaFX không xuất hiện trong unit test (logic ở engine, listener interface) — đúng pitfall skill |

---

## 4. Probe đối kháng độc lập (scratch, chạy thật)

Harness: `scratch/review-d/src/aegis/client/ReviewProbes.java` (package `aegis.client`, reuse fixture TestServer ở test-classes; compile chạy bằng JDK 27, classpath `full-cp.txt`). Log đầy đủ: `scratch/review-d/probes.log`.

| Probe | Kịch bản | Kết quả |
|---|---|---|
| **A** | ALERT_PUSH thiếu `title` → push hợp lệ tiếp theo | ❌ **CONTROLLER DEAD** — `IllegalArgumentException` thoát khỏi runLoop, thread `aegis-client-controller` chết; state kẹt ONLINE, 0 reconnect, push hợp lệ mất, 0 ACK, listener không nhận error → **issue M1** |
| **A2** | SYNC_RESULT.alerts entry thiếu `deliveryId` | ❌ Cùng đường chết qua `dispatch:299` → M1 |
| **B** | Đếm RNG draw của FaultInjector (1000 decisions, mixed, seed 42 ×2) | ✅ `nextDouble=4000, other=0`, stream identical |
| **C** | SYNC_RESULT field `tombstones` (server thật) | ✅ cancel applied + ACK_CANCEL deliveryId=DLV-T |
| **D** | Restart với tombstone rev2; push rev1/rev2/rev3 | ✅ 2 suppressed + re-ACK, chỉ rev3 hiển thị, active=1 |
| **F** | Server khỏe, interval quảng cáo 700ms > 3×cfg timeout 200ms | ⚠ 2 suspect giả, 3 connection/3.5s → **issue L1** |

Stack trace bằng chứng M1 (trích probes.log):
```
Exception in thread "aegis-client-controller" java.lang.IllegalArgumentException: missing or blank alert field: title
  at aegis.client.AlertRecord.requireText(AlertRecord.java:74)
  at aegis.client.AlertRecord.fromPayload(AlertRecord.java:37)
  at aegis.client.AlertRecord.fromPush(AlertRecord.java:24)
  at aegis.client.AlertClientEngine.dispatch(AlertClientEngine.java:294)
  at aegis.client.AlertClientEngine.readLoop(AlertClientEngine.java:288)
  at aegis.client.AlertClientEngine.runLoop(AlertClientEngine.java:207)
```

---

## 5. Issues

### M1 — MEDIUM · Controller thread chết lặng khi dispatch ném RuntimeException (malformed frame / lỗi SQLite)
- **Vị trí:** `AlertClientEngine.java:207` (readLoop trong try chỉ catch `IOException | FrameException`), `:288/294/299` (dispatch không bọc), `AlertRecord.java:36-37,74` (ném IAE); cùng đường thoát: `InboxStore.java:139` (IllegalStateException khi SQLite lỗi/hết disk).
- **Bằng chứng:** Probe A/A2 — 1 frame malformed từ server giết controller vĩnh viễn; state vẫn **ONLINE** (hiểu lầm cho UI), không reconnect, không listenerError, mọi push sau đó mất. Client điếc tới khi restart app.
- **Vì sao MEDIUM (không HIGH):** server là TLS-authenticated, trusted, luôn tạo payload đúng chuẩn (đã verify qua server tests); kịch bản cần server bug/bị compromise. Trong hệ thống EBS thật đây sẽ là HIGH (mất cảnh báo lặng lẽ); trong lab SIMULATION này là lỗi robustness cần sửa trước khi demo chaos phía server.
- **Khuyến nghị:** bọc `dispatch(e)` trong try/catch `RuntimeException` → `listenerError("malformed frame", ex)` + continue (bỏ frame, giữ kết nối); hoặc catch-all trong runLoop → coi như connection error → reconnect. Thêm regression test: push thiếu title/deliveryId/revision → engine vẫn ONLINE, push hợp lệ kế tiếp vẫn ACK + popup. Cân nhắc bắt cả `IllegalStateException` từ inbox (lỗi disk) để không chết lặng.

### L1 — LOW · Watchdog/soTimeout neo theo cfg cục bộ, không theo interval đàm phán
- **Vị trí:** `AlertClientEngine.java:196` (soTimeout = `cfg.heartbeatTimeoutMs()`), `:280` (ngưỡng `3 × cfg.heartbeatTimeoutMs()`), trong khi nhịp gửi HEARTBEAT theo `heartbeatIntervalMs` server quảng cáo (`:264-269`).
- **Bằng chứng:** Probe F — server khỏe quảng cáo 700ms, cfg 200ms → suspect giả + reconnect churn liên tục.
- **Thực tế:** aegis-server quảng cáo 10s = default cfg 10s → không kích hoạt; chỉ lỗi khi lệch cấu hình.
- **Khuyến nghị:** sau handshake, đặt soTimeout + ngưỡng watchdog theo interval đàm phán (vd `soTimeout = interval`, suspect sau `3 × interval`), hoặc clamp interval quảng cáo ≤ 3×timeout.

### L2 — LOW · Cột "User ACK" reset về false sau reload dù ACK đã durable
- **Vị trí:** `ClientApp.java:158-163` (`reloadFromInbox` luôn `new AlertRow(a, false)`), `InboxStore.activeAlerts()` (`InboxStore.java:226-240`) không trả `ack_user_at`.
- **Hệ quả:** sau restart/reconnect, alert đã được người dùng ack hiển thị như chưa ack; bấm Acknowledge lại → `ackUser` false (không gửi trùng ACK_USER — durability đúng) nhưng UI gây hiểu lầm.
- **Khuyến nghị:** expose `ack_user_at`/`user_id` trong activeAlerts (record wrapper) và set `userAcked` theo đó.

### L3 — LOW · Javadoc SimulatorMain hướng dẫn `exec:java` — System.exit giết Maven JVM
- **Vị trí:** `SimulatorMain.java:17` (javadoc ví dụ `exec-maven-plugin:3.2.0:java`).
- **Hệ quả:** `main` gọi `System.exit` (đúng design cho exit code 0/1/2); chạy `exec:java` trong cùng JVM Maven sẽ kill Maven giữa chừng. Skill/runbook đã dùng `exec:exec` fork — javadoc lệch.
- **Khuyến nghị:** sửa javadoc sang `exec:exec -Dexec.executable=java -Dexec.args="-classpath %classpath aegis.simulator.SimulatorMain …"` (như skill aegis-ebs-client-simulator).

### L4 — LOW · SimAgent retry mãi AUTH rejection vĩnh viễn
- **Vị trí:** `SimAgent.java:130-133` (ok=false → IOException thường) → vòng retry tới deadline (`:75-105`); khác client fail-fast đúng (`AlertClientEngine.java:208-213`).
- **Hệ quả:** sai credential → cả fleet hammer auth tới hết duration, inflate `connectFailures`; không hỏng dữ liệu nhưng phí tài nguyên và che khuất lỗi cấu hình.
- **Khuyến nghị:** đánh dấu permanent-fail cho agent khi ok=false (dừng agent, đếm riêng), hoặc document chủ ý retry.

### I1 — INFO · Raw SSLSocket không bật hostname verification (defense-in-depth)
- `TlsSupport.java:19-35` (cả 2 module): JSSE không tự verify hostname trên SSLSocket thô (chỉ HttpsURLConnection mặc định bật). Lab an toàn vì truststore pin **đúng leaf self-signed** SAN=localhost,127.0.0.1 (`scripts/gen-server-cert.sh`) — kẻ tấn công cần chính private key đó. Khuyến nghị hardening: `SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` trên socket client.

### I2 — INFO · SimAgent không có silence watchdog
- `SimAgent.java:137-146`: chỉ soTimeout 2s tick + deadline; server im lặng (half-open) không bị suspect chủ động — phục hồi dựa vào TCP write fail hoặc deadline. Chấp nhận được cho simulator (client có watchdog thật, mục 2.6).

### I3 — INFO · Phạm vi end-to-end determinism dưới kịch bản disconnect
- Decision stream deterministic tuyệt đối theo (profile, seed) — probe B + smoke lossy chứng minh identical accounting. Với flaky/mixed, reconnect backoff dùng `ThreadLocalRandom` jitter (`SimAgent.java:54` — RetryPolicy không inject RNG) + deadline thời gian thực → aggregate counts có thể dao động giữa các run cùng seed. Smoke test chỉ assert exact-reproducibility cho lossy (không disconnect) — phạm vi trung thực. Nếu cần reproducible tuyệt đối cả mixed: inject seeded RNG vào RetryPolicy của SimAgent + virtual clock.

### I4 — INFO · `activeAlerts` sort timestamp TEXT lexicographic
- `InboxStore.java:229` `ORDER BY received_at DESC`: `Instant.toString()` có precision thay đổi (".5Z" vs không fraction) → có thể lệch thứ tự ở biên sub-second. Chỉ ảnh hưởng thứ tự hiển thị, không ảnh hưởng correctness. Khuyến nghị: lưu epoch millis INTEGER hoặc chuẩn hóa format.

### I5 — INFO · TlsSupport trùng lặp giữa 2 module
- Diff chỉ khác dòng package. Có thể đưa về `aegis-core` (hoặc module shared) để tránh drift; không gấp.

### I6 — INFO · ACK_CANCEL cho alert unknown có thể thiếu deliveryId
- `AlertClientEngine.java:351-364`: alert chưa từng store + push không kèm deliveryId → ACK_CANCEL không có deliveryId, server ack() không match được delivery row (server sẽ reject/ignore — đúng phía server). Vô hại trong lab; push thật của server luôn kèm deliveryId.

---

## 6. Khuyến nghị ưu tiên

1. **Sửa M1 trước khi demo chaos/server-fuzz** (bọc dispatch + regression test malformed frame) — thay đổi nhỏ, khu trú `AlertClientEngine`, không đổi wire contract.
2. L1 (watchdog theo interval đàm phán) + L2 (User ACK column durable) — polish trước buổi bảo vệ.
3. L3 sửa javadoc; L4 cân nhắc fail-fast agent.
4. Các INFO: hardening tùy chọn, không chặn nghiệm thu.

**Kết luận:** module đúng contract EBSP/2.0 và skill aegis-ebs-client-simulator; 56/56 test xanh; các bảo đảm durable/dedup/ACK/tombstone/reconnect/TLS/determinism đều được kiểm chứng độc lập bằng probe chạy thật. **PASS** với 1 MEDIUM + 4 LOW cần theo dõi (không issue nào phá vỡ bảo đảm cốt lõi trong ngữ cảnh server trusted của lab).

---

## 7. Artefacts scratch (không commit)

`/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-d/`:
- `mvn-test.log` — log mvn rerun (exit 0)
- `cp.txt`, `full-cp.txt` — classpath probe
- `src/aegis/client/ReviewProbes.java` — harness 6 probe đối kháng
- `probes.log` — output đầy đủ (kèm stack trace M1)
- `classes/`, `tmp/` — build + inbox tạm của probe
- `client-simulator-review.md` — bản copy báo cáo này
