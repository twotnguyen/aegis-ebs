# AEGIS-EBS — Dàn ý slide bảo vệ đồ án (12 phút)

**Học phần:** CMP180 — Lập trình mạng máy tính · **Đồ án:** AEGIS Emergency Broadcast System (Showcase Edition)
**Thời lượng trình bày:** 12 phút (720 giây) · **Vấn đáp:** sau trình bày (mục §4).
**Căn cứ:** Blueprint `Plan/AEGIS_MASTER_BLUEPRINT.md` §14 (Demo runbook 12 phút), §15 (rubric), §16 (Q&A); `docs/DEMO-RUNBOOK.md`; `docs/TEST-PLAN.md`; `docs/PROTOCOL.md`; `docs/SECURITY.md`.

> ⚠️ **SIMULATION / TRAINING.** Mọi slide và demo phải gắn nhãn mô phỏng. **Không điền số liệu đo đạc giả định**: các ô `[TODO: số thật]` phải được thay bằng bằng chứng thật kèm **runId, commit SHA, cấu hình máy, timestamp** (NFR-018). Số liệu chỉ hợp lệ khi capture từ test chạy được.

---

## 1. Nguyên tắc trình bày

- **Trục đánh giá CMP180** (rubric §15): tự xây dựng giao thức ứng dụng qua TCP/UDP trong Java (25%), reliability/concurrency dưới lỗi (25%), kiến trúc/SRS/schema/protocol (15%), security/authorization (10%), dashboard/UX (10%), đo lường/test/reproducible (10%), tài liệu/trình bày (5%). Dàn ý phân bổ thời gian bám các trọng số này.
- **Song song slide + demo trực tiếp:** slide là mạch tự sự; demo chạy thật theo `DEMO-RUNBOOK.md`. Mỗi slide ghi rõ *thao tác demo* và *bằng chứng cần chiếu*.
- **Fallback** (blueprint §14): nếu mạng hội trường chặn multicast hoặc nguồn/mạng không ổn định → dùng **video quay từ lần chạy thật** + **simulator deterministic** chạy cục bộ. Phân biệt rõ *recording* vs *live*.
- **Không tuyên bố quá mức:** không "exactly-once network", không "HA", không "CAP compliant" khi chưa validate schema, không "an toàn sinh mạng".

---

## 2. Mạch slide theo thời gian (tổng 12:00)

| # | Slide | Khoảng thời gian | Thời lượng | Runbook §14 hỗ trợ | Rubric |
|---|---|---|---|---|---|
| 1 | Nhan đề & disclaimer | 0:00–0:20 | 20s | System reveal | Trình bày |
| 2 | Bài toán (problem) | 0:20–1:10 | 50s | System reveal | Kiến trúc |
| 3 | Mục tiêu & phạm vi | 1:10–1:40 | 30s | Command authority | Kiến trúc |
| 4 | Toàn cảnh demo flow | 1:40–2:10 | 30s | Command authority | UX |
| 5 | Kiến trúc 4 mặt phẳng | 2:10–3:00 | 50s | Incident launch | Kiến trúc |
| 6 | EBSP/2.0 — framing & envelope | 3:00–3:50 | 50s | Incident launch | Protocol |
| 7 | EBSP/2.0 — handshake & catalog | 3:50–4:20 | 30s | Digital twin live | Protocol |
| 8 | Thiết kế reliability | 4:20–5:20 | 60s | Fault injection | Reliability |
| 9 | Bằng chứng reliability (số thật) | 5:20–6:20 | 60s | Offline recovery | Đo lường |
| 10 | Kết quả chaos C01–C16 | 6:20–7:30 | 70s | Priority / restart | Reliability |
| 11 | Bảo mật — threat model & controls | 7:30–8:30 | 60s | Server restart | Security |
| 12 | Bảo mật — RBAC & TLS truststore | 8:30–9:15 | 45s | LAN lab | Security |
| 13 | Network lab: TCP vs multicast | 9:15–10:00 | 45s | LAN lab | Protocol/Đo lường |
| 14 | Hạn chế | 10:00–10:40 | 40s | Results | Trung thực |
| 15 | Kết luận & hướng phát triển | 10:40–11:15 | 35s | Results | Kiến trúc |
| 16 | Tóm tắt kỹ thuật CMP180 + dẫn vào Q&A | 11:15–12:00 | 45s | Technical summary | Cơ bản Java |

*(Tổng thời lượng = 720s = 12:00.)*

---

## 3. Nội dung từng slide

### Slide 1 — Nhan đề & disclaimer (0:00–0:20)
- **Tiêu đề:** AEGIS — Emergency Broadcast System cho tòa nhà văn phòng · CMP180 Capstone.
- Nhóm / thành viên / MSSV / lớp / GVHD: ThS. Nguyễn Văn Cẩn · Năm học 2025–2026. `[TODO điền]`
- **Dòng bắt buộc:** `SIMULATION / TRAINING — không thay thế hệ thống báo cháy/sơ tán chứng nhận.`
- **Demo:** chiếu dashboard 3D 10 tầng, kết nối xanh, nhãn TLS/version (system reveal).
- **Ghi chú:** mở đầu dứt khoát, nêu ngay giới hạn mô phỏng để định khung trung thực.

### Slide 2 — Bài toán (problem) (0:20–1:10)
- **Vấn đề:** tòa nhà văn phòng cần phát cảnh báo khẩn cấp **tin cậy, đúng phạm vi, có xác nhận** tới nhiều thiết bị trong LAN; mạng có thể mất gói/mất ACK/client offline/server crash.
- **Khoảng trống:** broadcast đơn thuần không chứng minh được *đã tới đúng thiết bị*, không phân biệt *máy nhận* vs *người xác nhận*, không chống trùng lặp khi retry, không phân quyền theo tầng.
- **Câu hỏi thiết kế:** làm sao tự xây giao thức ứng dụng trên TCP/UDP (Java) đạt: ưu tiên, ACK 2 tầng, retry idempotent, reconnect, crash recovery, RBAC, audit, đo lường tin cậy?
- **Ghi chú:** nhấn mạnh đây là bài toán *reliable distribution + incident command*, không chỉ "gửi tin".

### Slide 3 — Mục tiêu & phạm vi (1:10–1:40)
- **Mục tiêu (P0):** server phát cảnh báo tới nhiều client LAN; priority; ACK_RECEIVED ≠ ACK_USER; retry idempotent; reconnect; crash recovery; RBAC scoped; audit.
- **Phạm vi:** 1 server LAN; client JavaFX + simulator headless (100 client bắt buộc, 200–500 stretch); dashboard web 3D; **single-node, không HA**.
- **Persona:** System Admin, Emergency Operations Controller, Security Operator, Floor Manager, Employee, Device Agent, Instructor/Observer.
- **Demo:** split-window operator vs floor-manager trái phép → `DENIED` + audit (command authority).
- **Ghi chú:** nêu rõ acceptance targets **chưa đo** ở thời điểm thiết kế; số thật chiếu ở slide 9.

### Slide 4 — Toàn cảnh demo flow (1:40–2:10)
- **Sơ đồ mạch demo 12 phút** (11 phân đoạn runbook): reveal → command authority → incident launch → digital twin → fault injection → offline recovery → priority → restart → LAN lab → results → technical summary.
- **Thông điệp:** mỗi phân đoạn chứng minh một thuộc tính hệ thống, có bằng chứng (log/report/ảnh).
- **Demo:** chuyển cảnh nhanh qua các cửa sổ sẽ dùng (dashboard, client, chaos panel, audit).
- **Ghi chú:** giúp hội đồng theo dõi mạch; không đi sâu kỹ thuật ở slide này.

### Slide 5 — Kiến trúc 4 mặt phẳng (2:10–3:00)
- **Command plane:** dashboard login, kiểm tra user/scope, tạo/sửa/hủy incident & alert.
- **Delivery plane:** phiên Java Socket TLS đã xác thực, writer queue bounded, EBSP framed, retry, ACK.
- **Persistence/observability plane:** SQLite WAL (single-writer), incident event log, audit, metrics, replay chỉ đọc.
- **Discovery/experiment plane:** UDP discovery + multicast hint trên LAN cô lập (không tin cậy).
- **Sơ đồ module:** `aegis-protocol → aegis-core → aegis-persistence → aegis-server → aegis-client/simulator/network-lab/dashboard`.
- **Demo:** incident launch FIRE_DRILL tầng 5 + zone lân cận, CAP metadata, publish → dashboard hiện target count.
- **Ghi chú:** nhấn mạnh modular monolith giữ độ sâu kỹ thuật trong 12 tuần; network I/O ngoài DB transaction.

### Slide 6 — EBSP/2.0: framing & envelope (3:00–3:50)
- **Framing (byte contract):** `uint32 BE length L` + `L bytes strict UTF-8 JSON`, `L ∈ [1, 65536]`. Chặn oversize **TRƯỚC** cấp phát; `readFully` xử lý partial/coalesced reads.
- **Envelope:** `{version:2, type, messageId, correlationId, sentAt(UTC ISO-8601), payload}`.
- **Validation:** strict UTF-8 (REPORT), chỉ JSON object, cấm trailing tokens, `version==2`, `type` thuộc enum, trường bắt buộc non-blank.
- **Idempotency identifiers:** CREATE dùng `requestId`; delivery có `deliveryId + alertId + revision` ổn định; mỗi attempt transport `messageId` mới.
- **Mã nguồn:** `aegis.protocol.{Protocol, FrameCodecImpl, Envelope, MessageType}` (56 test).
- **Demo:** chiếu frame thật (hex/JSON) từ log; chỉ rõ length prefix + body.
- **Ghi chú:** đây là phần "tự xây giao thức" — trọng số 25%; nói chậm, rõ byte contract.

### Slide 7 — EBSP/2.0: handshake & message catalog (3:50–4:20)
- **Handshake:** TLS handshake (verify truststore + hostname) → `HELLO{version,deviceId,nonce}` → `SERVER_HELLO{serverId,challenge}` → `AUTH_DEVICE/AUTH_OPERATOR` → `AUTH_RESULT{sessionId,permissions}` → `SYNC_REQUEST/SYNC_RESULT` → `HEARTBEAT`.
- **Catalog nhóm:** Handshake/Identity · Commands (INCIDENT_*, ALERT_*) · Delivery/Client (ALERT_PUSH, ACK_*, SYNC_*, HEARTBEAT) · Dashboard (STATUS_*) · Errors (typed codes).
- **Nguyên tắc:** `sessionId` không là bằng chứng phân quyền ngoài kết nối xác thực; server suy scope từ DB.
- **Demo:** digital twin live — tầng đổi màu, 3 client thật + 100 simulator phản ánh target.
- **Ghi chú:** chiếu bảng catalog rút gọn; nhấn mạnh version negotiation + stale revision suppression.

### Slide 8 — Thiết kế reliability (4:20–5:20)
- **ACK 2 tầng:** `ACK_RECEIVED` (lưu cục bộ bền vững) ≠ `ACK_USER` (cử chỉ người dùng). Client ghi inbox SQLite (WAL + synchronous=FULL) **TRƯỚC** khi gửi ACK_RECEIVED.
- **Retry:** 3s/6s/12s + jitter, tối đa 3 retry rồi `FAILED` (`RetryPolicy`, `FanoutEngine`); mỗi attempt `messageId` mới, cùng `deliveryId`.
- **Dedup:** `(alertId, revision, deviceId)` → đúng 1 popup hiển thị (NFR-005); `DedupIndex` chặn dispatch stale; `InboxStore` monotonic revision (STORED/DUPLICATE/STALE).
- **ACK chỉ tiến trạng thái:** rank guard `PENDING < SENT/RETRY < RECEIVED < ACK/FAILED < EXPIRED/CANCELLED` (`DeliveryRepo.recordAck`, `AckLedger`).
- **Crash recovery:** transactional outbox — commit rồi mới enqueue; `RecoveryScanner` dựng lại PENDING/SENT/RETRY_SCHEDULED/FAILED sau restart.
- **Tombstone:** cancel/expire không hồi sinh alert cũ khi sync/retry.
- **Demo:** fault injection — drop ACK 1 client → retry + popup không trùng + attempt trace.
- **Ghi chú:** nêu bất biến §5; đây là trọng số reliability 25%.

### Slide 9 — Bằng chứng reliability (số thật) (5:20–6:20)
- **Acceptance targets (chưa đo → phải đo):** NFR-003 p95 dispatch→ACK_RECEIVED ≤ 2s (100 client, LAN khai báo); NFR-004 > 99% ACK_RECEIVED trong 10s (loại target fault-injected); NFR-005 đúng 1 popup; NFR-006 không mất row đã commit.
- **Bảng kết quả thật** `[TODO: điền số đo thật + runId/SHA/máy]`:

| Chỉ số | Định nghĩa | Kết quả thật |
|---|---|---|
| p50 / p95 / p99 (ms) | `ackReceipt − dispatchStart` monotonic, cùng process | `[TODO]` |
| Tỷ lệ ACK_RECEIVED | tử số/mẫu số thật (loại fault-injected) | `[TODO]` |
| Retry volume | tổng attempt sau lần đầu / delivery | `[TODO]` |
| Throughput / CPU / RSS | đo khi 100 client × 15 phút | `[TODO]` |
| Popup trùng | số popup / (deviceId,alertId,revision) | `[TODO]` |

- **Cách đo percentile:** timestamp monotonic phía server (dispatch & receipt) mỗi delivery; **báo cáo riêng timeout/omission** để tránh thiên lệch mẫu thành công (MetricsRegistry.recordLatency).
- **Demo:** offline recovery — disconnect/reconnect → alert active sync, revision cũ ẩn; chiếu metrics dashboard.
- **Ghi chú:** tuyệt đối không bịa số; nếu chưa đo, nói rõ "đang đo" và chiếu quy trình đo.

### Slide 10 — Kết quả chaos C01–C16 (6:20–7:30)
- **Chaos Lab:** tiêm lỗi có seed deterministic, tự sinh báo cáo; so sánh baseline vs faulty run.
- **Bảng chaos** (blueprint §12) — `[TODO: điền kết quả thật + evidence mỗi dòng]`:

| ID | Lỗi tiêm | Thuộc tính kỳ vọng | Bằng chứng |
|---|---|---|---|
| C01 | Drop ACK_RECEIVED 1 lần | Retry; alert hiện đúng 1 lần | Event log + ảnh |
| C02 | Delay ACK 5s | Retry/backoff đúng | Delivery attempts |
| C03 | Duplicate ALERT_PUSH | Inbox idempotent; re-ACK | Local DB unique key |
| C04 | Disconnect 20% agent | Target còn lại không bị chặn | Queue + status graph |
| C05 | Reconnect 20% client | Active sync, chặn stale | Sync trace |
| C06 | Kill server sau commit, trước send | Pending work recovered | Restart report |
| C07 | Kill sau socket write, trước ACK commit | Resend, không trùng hiển thị | Retry + inbox |
| C08 | Floor manager publish trái phép | DENIED + audit | Auth log |
| C09 | Cert TLS giả/không rõ | Connection rejected | Security trace |
| C10 | Frame oversize, UTF-8/JSON lỗi | Reject, không crash/unbounded alloc | Fuzz test |
| C11 | 100 client nhận CRITICAL giữa INFO burst | Critical expedited; fairness | p95 theo class |
| C12 | Cancel khi client offline | Tombstone giao khi reconnect | Client UI state |
| C13 | UDP forged server response | Không trust khi chưa verify TLS | Trace |
| C14 | Multicast bị chặn | TCP primary vẫn chạy | Comparison lab |
| C15 | SQLite writer contention tạm thời | Bounded retry; persistence nhất quán | DB health |
| C16 | Slow client / full writer queue | Client khác tiếp tục; slow target hiện rõ | Queue depth graph |

- **Demo:** priority preemption (INFO burst → CRITICAL) + server restart khôi phục delivery từ DB.
- **Ghi chú:** mỗi scenario phải có seed + lệnh chạy + evidence; không tuyên bố "đã pass" khi chưa chạy thật.

### Slide 11 — Bảo mật: threat model & controls (7:30–8:30)
- **8 mối đe dọa chính** (docs/SECURITY.md §2): rogue LAN spoof discovery · stolen device credential · session replay · role escalation · out-of-scope broadcast · malformed frame · slow-client exhaustion · log exposure.
- **Controls tiêu biểu:**
  - TLS truststore verify (pinned, no trust-all) + lab `CN==serverId` + nonce echo;
  - Argon2id (memory-hard, salt, wipe) — tham số demo 16 MiB, khuyến nghị OWASP ≥ 19 MiB;
  - frame max 64KiB + strict parse + giới hạn độ sâu JSON;
  - bounded queue 256 + slow-client disconnect;
  - requestId idempotency; session expiry; mask secrets; sanitized errors; audit append-only.
- **Ranh giới tin cậy:** chỉ TCP/TLS xác thực kích hoạt hiển thị; UDP/multicast = advisory.
- **Demo:** server restart → khôi phục từ DB, **không** tuyên bố exactly-once network.
- **Ghi chú:** trung thực nêu phần đã hiện thực vs thiết kế chờ gateway (SECURITY §0, §8).

### Slide 12 — Bảo mật: RBAC & TLS truststore (8:30–9:15)
- **RBAC matrix (role × thao tác × scope):** SYSTEM_ADMIN mọi nơi; SECURITY_OPERATOR trong building được cấp; FLOOR_MANAGER chỉ tầng được cấp; EMPLOYEE/DEVICE không publish.
- **Deny-by-default + scope từ DB** (`ScopedRBAC`, `Grants`, `RbacService`); target snapshot bất biến; DENIED + audit.
- **Truststore self-signed demo:** `keytool -genkeypair` (RSA2048, SAN) → `-exportcert` PEM → `-importcert` truststore PKCS12; client pin truststore.
- **Giới hạn:** không phải CA công cộng, không OCSP/CRL, mật khẩu demo công khai, CN=localhost (LAN thật phải sinh lại SAN).
- **Demo:** chiếu `DENIED` + audit entry của floor-manager trái phép (T-07/C08); chiếu `keytool -list` truststore.
- **Ghi chú:** nhấn mạnh least *** + không tin role client gửi; liên hệ OWASP Authorization.

### Slide 13 — Network lab: TCP vs multicast (9:15–10:00)
- **Discovery:** UDP 9999 `DISCOVER_EBS{nonce,version}` → reply `{serverHost,tlsPort,nonce,serverId}`; **không tin** reply khi chưa verify TLS.
- **Hint:** multicast thật `MulticastSocket` group 239.10.10.10:9998, TTL/interface cấu hình; `ALERT_HINT{alertId,revision,serverId}` advisory → chỉ kích hoạt fetch TLS.
- **Benchmark:** publisherPackets/Bytes (TCP N×M frame vs multicast M hint), receivedCopies (nhân bản kernel), latency avg/p50/p95/max; report JSON+MD.
- **Môi trường macOS:** `lo0` có MULTICAST nhưng không route → probe phát hiện, fallback unicast UDP; `en0` giao multicast loopback thật.
- **Demo:** so sánh TCP reliable push vs multicast hint (nếu VLAN hỗ trợ); giải thích tradeoff.
- **Ghi chú:** UDP không delivery guarantee/congestion control (RFC 8085); multicast chỉ hint, nội dung authoritative qua TLS.

### Slide 14 — Hạn chế (10:00–10:40)
- **Single-node** → single point of failure; crash recovery ≠ HA.
- **TLS self-signed** demo, không CA công cộng; credential demo công khai + device secret dùng chung (chỉ lab).
- **Multicast LAN** phụ thuộc VLAN/interface; macOS lo0 không route multicast.
- **Gateway/router chưa hoàn thiện** → một số thực thi bảo mật tầng wire là thiết kế (SECURITY §8).
- **Argon2id demo 16 MiB** dưới khuyến nghị OWASP 19 MiB; session absolute TTL chưa có.
- **SQLite single-writer** là bottleneck đã thừa nhận; chưa PostgreSQL.
- **CAP metadata** resemblance ≠ compliance (chưa validate schema XML).
- **Không phải hệ thống an toàn sinh mạng chứng nhận.**
- **Ghi chú:** nêu hạn chế chủ động, đúng tinh thần NFR-018 + blueprint §0.2.

### Slide 15 — Kết luận & hướng phát triển (10:40–11:15)
- **Đạt được so mục tiêu:** tự xây EBSP/2.0 trên TCP/UDP Java; ACK 2 tầng + retry idempotent + dedup + reconnect + crash recovery; RBAC scoped + audit; digital twin + chaos lab + đo lường. `[TODO: đối chiếu kết quả thật]`
- **Hướng phát triển:** HA/active-passive; PostgreSQL khi có bằng chứng bottleneck; CAP XML export validate schema; floor edge relay (nghiên cứu); nâng Argon2id + PKI thật; hoàn thiện gateway thực thi bảo mật wire.
- **Ghi chú:** kết luận bám mục tiêu slide 3; không tuyên bố vượt quá bằng chứng.

### Slide 16 — Tóm tắt kỹ thuật CMP180 + dẫn vào Q&A (11:15–12:00)
- **Ánh xạ giáo trình CMP180 → mã nguồn:**
  - Bài 1–2 (TCP/IP, I/O streams): `FrameCodecImpl` đọc/ghi byte, `DataInputStream.readFully`.
  - Bài 3 (Thread/Runnable, concurrency): writer thread/phiên, `PriorityScheduler`, `FanoutEngine.dispatchLoop`, executor.
  - Bài 5–6 (Socket/ServerSocket, multi-client, serialization): gateway TLS, `ClientSession`, JSON framing.
  - Bài 7 (UDP DatagramSocket): discovery 9999, lab datagram codec ≤ 1024 byte.
  - Bài 8 (MulticastSocket): hint group 239.10.10.10:9998, joinGroup, TTL/interface.
  - Bài 9 (RMI/RPC — tham khảo mô hình): đối chiếu vì sao chọn socket tự xây thay vì RMI/broker.
- **Disclaimer nhắc lại:** SIMULATION/TRAINING.
- **Dẫn vào vấn đáp:** "Nhóm em sẵn sàng trả lời câu hỏi." (chuẩn bị mục §4).
- **Ghi chú:** chỉ rõ *tự cài đặt* Socket/Stream/Thread/UDP/Multicast — trọng số cơ bản 25%.

---

## 4. Q&A dự kiến (vấn đáp sau 12 phút)

### 4.1. Tám câu hỏi theo blueprint §16 (bắt buộc chuẩn bị)

**Q1. Vì sao dùng TCP nếu vẫn cần ACK tầng ứng dụng?**
> TCP ACK chỉ xác nhận **byte ở tầng transport** đã tới buffer nhận, không chứng minh nghiệp vụ. `ACK_RECEIVED` xác nhận client **đã ghi bền vững vào inbox cục bộ** (SQLite WAL + synchronous=FULL) và xác định **đúng thiết bị/target**; `ACK_USER` là **cử chỉ người dùng** tách biệt (actor + thời điểm riêng). Ba tầng ngữ nghĩa khác nhau nên không thể thay thế nhau. (Bằng chứng: `InboxStore.store` trước `markAckReceivedSent`; `AckLedger`; PROTOCOL §7.5.)

**Q2. Vì sao không dùng MQTT/Firebase (cloud push/broker)?**
> Mục tiêu học tập CMP180 yêu cầu **tự xây giao thức ứng dụng và socket trực tiếp** trong Java (TCP/UDP), không dùng cloud push/broker làm truyền tải chính. MQTT là **tham khảo hữu ích** (QoS, retained message) nhưng không phải dependency truyền tải chính. Tự xây EBSP/2.0 cho thấy hiểu biết framing, concurrency, reliability ở mức byte. (Blueprint §0, §17.)

**Q3. Retry có bảo đảm exactly-once không?**
> **Không.** Retry là **best-effort at-least-once** trong chính sách bounded (3s/6s/12s + jitter, tối đa 3 retry rồi FAILED). Dedup theo `(alertId, revision, deviceId)` tạo **effectively-once UI action** trong giả định local storage: đúng 1 popup hiển thị dù transport trùng. Mạng không exactly-once; chỉ có idempotent receipt + dedup display. (Bằng chứng: `RetryPolicy`, `DedupIndex`, `InboxStore` STORED/DUPLICATE/STALE; NFR-005.)

**Q4. Vì sao multicast chỉ là hint?**
> UDP multicast **không** là cơ chế phân phối khẩn cấp tin cậy, có xác thực trên diện rộng: không delivery guarantee, không congestion control (RFC 8085), không xác thực nguồn. Payload authoritative đã xác thực đi qua **TLS TCP**; hint chỉ kích hoạt client fetch nội dung thật qua TLS. Client không bao giờ hiển thị cảnh báo chính thức trực tiếp từ multicast. (Bằng chứng: `VerifiedAlertSubscriber`, `DiscoveryClient.discoverTrusted`; PROTOCOL §11.)

**Q5. Nếu server trung tâm chết thì sao?**
> Công việc **đã commit** sống sót và được phát lại sau restart (`RecoveryScanner` dựng lại PENDING/SENT/RETRY_SCHEDULED/FAILED — transactional outbox). **Không có availability** cho tới khi server hồi phục → **không tuyên bố HA**. Single-node là single point of failure đã thừa nhận; cluster/floor relay chỉ ở mục nghiên cứu tùy chọn. (Bằng chứng: `TransactionalPublisher` commit→enqueue; C06/C07.)

**Q6. Vì sao chọn SQLite?**
> Đơn giản và transaction tin cậy ở tải demo dự kiến; WAL cải thiện concurrency đọc/ghi nhưng **vẫn chỉ một writer commit tại một thời điểm** (single-writer bottleneck đã thừa nhận). `PRAGMA foreign_keys=ON` mỗi connection, `busy_timeout` kiểm soát, transaction ngắn. Chỉ chuyển PostgreSQL khi có **bằng chứng bottleneck thật**, không phỏng đoán. (Bằng chứng: `Db`, `MigrationRunner` 26 bảng; NFR-013; C15.)

**Q7. Đo percentile latency thế nào?**
> Dùng **timestamp monotonic phía server**: `dispatchStart` và `ackReceipt` cho mỗi delivery, trong cùng vòng đời process (đồng hồ monotonic, không wall-clock nhảy). p50/p95/p99 tính trên các delivery **quan sát thành công**; **báo cáo riêng timeout/omission** để tránh thiên lệch mẫu thành công (successful-sample bias). Kèm metadata run: runId, git SHA, CPU/RAM/OS, JDK, số client, topology LAN, fault profile. (Bằng chứng: `MetricsRegistry.recordLatency`; blueprint §11; NFR-018.)

**Q8. Điều gì làm CAP export được coi là compliant?**
> Phải đúng **XML namespace, trường bắt buộc, vocabulary, schema validation** và **profile checking** (OASIS CAP 1.2). **Giống metadata thôi là chưa đủ** — chưa validate schema thì không tuyên bố tương thích. Hiện AEGIS mới có CAP-like metadata (severity/urgency/certainty tách biệt priority broadcast); XML export validate schema là **stretch**, chỉ tuyên bố khi schema test pass. (Blueprint §0.1, FR-033, §16.)

### 4.2. Câu hỏi bảo mật dự kiến (bổ sung, bám SECURITY.md)

**Q9. Vì sao self-signed mà không dùng CA công cộng?**
> Demo LAN cô lập, không có domain công cộng; mục tiêu là minh họa **cơ chế xác thực server bằng truststore pinned** (NFR-009) và chống rogue server (T1). Self-signed không được OS/browser tin mặc định, không OCSP/CRL, mật khẩu demo công khai → **chỉ lab**. Production cần PKI thật, secret riêng từng thiết bị, rotate/revoke. (SECURITY §6.5.)

**Q10. Tham số Argon2id có đạt khuyến nghị OWASP không?**
> Demo dùng m=16 MiB, t=2, p=1 — **thấp hơn** ngưỡng OWASP tối thiểu m=19 MiB (19456 KiB), t=2, p=1 (RFC 9106). Tham số **injectable** nên nâng lên ≥19456 KiB khi production không cần sửa mã. Argon2id memory-hard + salt duy nhất + wipe bộ nhớ; không plaintext, không SHA nhanh cho mật khẩu. (SECURITY §3.3, checklist P3.)

**Q11. Chống session replay thế nào?**
> TLS chống nghe lén/replay transport; sessionId UUIDv4 **122-bit entropy CSPRNG** (đạt OWASP ≥64 bit); `deviceOnline()` đóng phiên cũ khi reconnect; ACK **chỉ tiến trạng thái** (rank guard) nên ACK cũ không lùi được; dedup + tombstone chặn hồi sinh alert cũ; hết hạn idle/absolute là thiết kế cần hoàn thiện ở gateway. (SECURITY §3.7; T-13..17.)

**Q12. Floor Manager phát vượt tầng thì hệ thống xử lý sao?**
> `ScopedRBAC.canPublish` kiểm tra `Grants.coversFloor(buildingId, floorId)` lấy từ DB `role_grants`, **không tin floor client gửi**. Ngoài scope → `DENIED/INVALID_SCOPE` + audit append-only (actor/scope/outcome/correlationId). Target snapshot resolve từ DB, bất biến sau activate. (T-07/C08; `ScopedRBACTest`.)

**Q13. UDP discovery bị giả mạo thì client có sập bẫy không?**
> Không. `DiscoveryClient.discoverTrusted()` chỉ trả `TrustedServer` khi đủ 4 điều kiện: nonce echo khớp, TLS truststore pinned + hostname/SAN verify, `CN==serverId`, `SERVER_HELLO` echo nonce qua chính phiên TLS. Hint multicast giả revision cao vẫn bị bỏ qua — sink chỉ thấy nội dung TLS authoritative. (T-24/C13; `DiscoveryTrustTest`.)

**Q14. Một client đọc chậm có làm nghẽn cả hệ thống không?**
> Không. Mỗi phiên có `BoundedWriterQueue` capacity 256 + một writer thread riêng. Đầy mà frame mới priority cao hơn → evict frame thấp (CRITICAL không bị INFO chặn); không evict được → `REJECTED_SLOW` → **ngắt client chậm**, các client khác tiếp tục. Queue depth + high-water mark được đo. (T-23/C16; NFR-011.)

**Q15. Frame oversize/JSON sâu có gây cạn bộ nhớ không?**
> `FrameCodecImpl.checkLength` từ chối `L>65536` **TRƯỚC khi cấp phát body**; strict UTF-8 REPORT; chỉ JSON object, cấm trailing tokens; Jackson `StreamReadConstraints` mặc định giới hạn độ sâu 1000 (khuyến nghị cấu hình tường minh chặt hơn). Read timeout + quota mỗi kết nối chống flood. (T-01..04/C10; NFR-010.)

**Q16. Credential thiết bị bị đánh cắp thì sao?**
> Credential lưu dạng **Argon2id hash** (không đảo ngược), salt duy nhất, wipe RAM; thiết bị `enabled=0` không xác thực được; rotate/revoke theo FR-003. TLS chống nghe lén credential trên dây. **Hạn chế demo:** device secret dùng chung fleet + công khai trong mã → chỉ lab; production phải cấp secret riêng từng thiết bị. (T2; SECURITY §3.3, §6.5.)

### 4.3. Câu hỏi tình huống / đánh đổi

- **Vì sao audit best-effort (lỗi ghi audit không phá lệnh)?** Đánh đổi availability của đường lệnh khẩn cấp vs độ đầy đủ audit; cần xem lại khi production (có thể chuyển audit sang hàng đợi bền vững). Nêu trung thực đây là đánh đổi, không phải giải pháp tối ưu.
- **Vì sao không dùng Java thread priority cho ưu tiên alert?** Priority là **application-level scheduling** (`PriorityScheduler`, enum CRITICAL>EMERGENCY>WARNING>INFO), không phải `Thread.setPriority` (phụ thuộc OS, không deterministic). Fairness bounded burst tránh starvation.
- **CAP severity vs broadcast priority?** Khác ngữ nghĩa: `severity/urgency/certainty` (CAP) mô tả mức độ sự cố; `priority` (EBSP) mô tả thứ tự phân phối. Không nhập nhằng (bất biến §5).
- **Replay timeline có phát lại alert không?** Không. Event replay **chỉ đọc** incident event history; không gọi Publish (UC-05, §8.3).

---

## 5. Slide dự phòng (backup, không tính vào 12 phút)

- **B1.** ERD 26 bảng + entity graph (buildings→floors→zones→devices; incidents→alerts→revisions→targets→deliveries→attempts→acks; role_grants; audit_events).
- **B2.** State machines: Incident / Alert / Delivery + invariants (ACK only advances; requestId unique; tombstone).
- **B3.** Sequence diagram publish (validate→BEGIN IMMEDIATE→snapshot→commit→enqueue) và reconnect (SYNC + tombstone).
- **B4.** Bảng acceptance 30 test (T-01..30) ánh xạ module/lệnh; surefire XML cho số liệu chính xác.
- **B5.** Cấu hình demo: `ServerConfig` (TLS 9443, WS 9444, heartbeat 10s/3, retry 3s/6s/12s max 3, queue 256, Argon2id 2/16384/1).
- **B6.** Status feed contract `status/1` (WS 9444): STATUS_SNAPSHOT/DELTA, severity rules, metrics p50/p95/p99.
- **B7.** Keytool truststore commands đầy đủ (SECURITY §6) + `keytool -list -v` output thật.
- **B8.** Video fallback quay từ lần chạy thật (gắn nhãn recording) + lệnh simulator deterministic.

---

## 6. Checklist chuẩn bị bảo vệ

- [ ] Điền thông tin nhóm/MSSV/lớp (slide 1) và ký lời cam đoan trong BAO-CAO.
- [ ] Thay **toàn bộ** `[TODO: số thật]` bằng bằng chứng thật + runId/SHA/máy/timestamp (slide 9, 10). **Không bịa số.**
- [ ] Chạy `mvn -q test` toàn reactor; lưu surefire XML làm bằng chứng số test pass.
- [ ] Chạy chaos C01–C16 có seed; lưu log/report/ảnh mỗi scenario.
- [ ] Chuẩn bị truststore demo (`scripts/gen-server-cert.sh`) + kiểm tra `keytool -list`; nếu demo 2 máy, sinh lại SAN với IP thật.
- [ ] Kiểm tra multicast hội trường (`probe`); chuẩn bị fallback unicast + video nếu bị chặn.
- [ ] Gắn nhãn SIMULATION/TRAINING trên mọi slide/demo.
- [ ] Tập trả lời 8 câu blueprint §16 + câu hỏi bảo mật §4.2; phân công người trả lời theo module.
- [ ] Rà soát tuyên bố: không exactly-once network, không HA, không CAP compliant khi chưa validate, không an toàn sinh mạng.
- [ ] Đối chiếu rubric §15: đảm bảo phần Socket/UDP tự cài đặt (25%) và reliability dưới lỗi (25%) được nhấn mạnh đúng mức.

---

## 7. Tài liệu tham khảo cho slide

1. `Plan/AEGIS_MASTER_BLUEPRINT.md` — §14 (runbook), §15 (rubric), §16 (Q&A), §11 (observability), §12 (chaos).
2. `docs/DEMO-RUNBOOK.md`, `docs/TEST-PLAN.md`, `docs/PROTOCOL.md`, `docs/SECURITY.md`, `docs/BAO-CAO.md`.
3. `aegis-network-lab/src/main/resources/lab-guide.md` (UDP/multicast lab + benchmark).
4. `aegis-dashboard/docs/status-contract.md` (status feed `status/1`).
5. Giáo trình CMP180 Bài 1–9 (TCP/IP, I/O, Thread, Socket, UDP, Multicast, RMI).
6. OWASP Authorization / Session Management / Password Storage; OASIS CAP 1.2; RFC 8085; RFC 9106; Oracle SSLSocket; SQLite WAL.
