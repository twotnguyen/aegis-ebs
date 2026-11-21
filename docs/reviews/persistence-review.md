# PEER REVIEW ĐỘC LẬP — MODULE `aegis-persistence` (review-b)

| | |
|---|---|
| **Ngày review** | 2026-11-21 (UTC+07) |
| **Reviewer** | Independent peer review — agent `review-b` (không phải tác giả module) |
| **Commit được review** | `5bf5feb` — *feat(persistence): SQLite WAL + migration + transactional publish + recovery (35 tests)* |
| **HEAD tại thời điểm review** | `1f6777d` (docs skeleton; không thay đổi source persistence) |
| **Phạm vi** | `aegis-persistence/src/main` (Db, MigrationRunner, TransactionalPublisher, RecoveryScanner, 6 repos, Sql, records/enums) + `db/001_base_schema.sql`, `db/002_showcase_expansion.sql` + test suite |
| **Phương pháp** | (1) chạy lại `mvn -q -pl aegis-persistence test` ×2, đối chiếu surefire XML; (2) đọc/audit toàn bộ source chính + schema; (3) 27 kiểm thử đối kháng độc lập (harness `ReviewB`/`ReviewB2`) trên SQLite scratch; (4) đối chiếu ngữ nghĩa với `docs/PROTOCOL.md` |
| **Ràng buộc** | CHỈ đọc + chạy + báo cáo. Không sửa source module, không git commit. Scratch: `/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-b` |
| **Môi trường** | macOS 15.7.3 · Maven 3.10.1 (Homebrew JDK 27.0.1.1, `release=24`) · harness: JDK 24.0.1 · sqlite-jdbc 3.46.1.0 |

> Ngoài phạm vi: các file WIP chưa commit của agent khác (`aegis-server/src`, `aegis-client/src`, `aegis-network-lab/src`, `aegis-persistence/src/main/resources/db/seed_demo.sql`). Có ghi chú INFO liên quan khi cần.

---

## VERDICT: **PASS** (kèm khuyến nghị)

**Tóm tắt (VI):** Module đạt yêu cầu. 35/35 test pass ở cả 2 lần chạy lại. Kiểm thử đối kháng độc lập xác nhận: **không có SQL injection** (100% PreparedStatement bind), **FK được enforce trên mọi connection**, **transaction publish atomic đúng** (rollback sạch, enqueue chỉ sau COMMIT), **idempotency requestId đúng** (kể cả đua 8 thread), **WAL + busy_timeout hoạt động**, **recovery phủ đúng 4 state theo spec** (kể cả FAILED), **timestamp UTC nhất quán**. Phát hiện **5 issue cần xử lý (1 MEDIUM + 4 LOW)** và 8 ghi chú INFO — không có CRITICAL/HIGH, không issue nào phá vỡ bảo đảm cốt lõi của module.

**Issue count: 5 actionable (0 CRITICAL · 0 HIGH · 1 MEDIUM · 4 LOW) + 8 INFO.**

---

## 1. Test rerun (xác nhận từ surefire, không dựa vào stdout `-q`)

Lệnh: `cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs && mvn -q -pl aegis-persistence test`

- **Run 1:** exit 0 · **Run 2:** exit 0 (surefire XML ghi mới lúc 18:14 local).
- Tổng hợp từ `aegis-persistence/target/surefire-reports/TEST-*.xml` (cả 2 lần identical):

| Test class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| DbTest | 3 | 0 | 0 | 0 |
| MigrationRunnerTest | 3 | 0 | 0 | 0 |
| RecoveryScannerTest | 4 | 0 | 0 | 0 |
| RepositoriesTest$Users | 4 | 0 | 0 | 0 |
| RepositoriesTest$Devices | 3 | 0 | 0 | 0 |
| RepositoriesTest$RoleGrants | 3 | 0 | 0 | 0 |
| RepositoriesTest$Alerts | 3 | 0 | 0 | 0 |
| RepositoriesTest$Deliveries | 5 | 0 | 0 | 0 |
| RepositoriesTest$Audit | 1 | 0 | 0 | 0 |
| TransactionalPublisherTest | 6 | 0 | 0 | 0 |
| **TỔNG** | **35** | **0** | **0** | **0** |

✅ **Xác nhận 35 test pass** (RepositoriesTest container đóng góp 0 test, chỉ @Nested).

## 2. Kết quả audit theo từng hạng mục yêu cầu

| Hạng mục | Kết luận | Bằng chứng chính (file:dòng) |
|---|---|---|
| **SQL injection (nối chuỗi SQL?)** | ✅ AN TOÀN. Mọi truy vấn đều `PreparedStatement` + bind tham số. Nối chuỗi chỉ ở fragment SQL tĩnh (danh sách cột) và script migration đóng gói trong classpath (trusted). Không có input người dùng nối vào SQL. | `UserRepo.java:38-57`, `AlertRepo.java:43-191`, `DeliveryRepo.java:42-209`, `DeviceRepo.java:36-96`, `AuditRepo.java:37-82`, `RoleGrantRepo.java:37-71`, `TransactionalPublisher.java:117-129`, `MigrationRunner.java:71-77`. Đối kháng: 6 username hostile (`admin'--`, `x'; DROP TABLE users;--`, `' OR '1'='1`, …) + payload trong title/body/metadata/audit/device_code → lưu literal, lookup khớp đúng 1 row, không table nào hỏng. |
| **FK enforcement** | ✅ BẬT trên MỌI connection (`PRAGMA foreign_keys=ON` lúc open, không nằm trong transaction). Orphan alert/device/delivery/ack đều bị từ chối; `PRAGMA foreign_key_check` sạch trên toàn bộ 28 DB scratch sau đối kháng. | `Db.java:35`; `001_base_schema.sql:4-17`, `002:4-127`; harness `fk-orphan-rejected`, `final-foreign-key-check`. |
| **Transaction atomicity / rollback** | ✅ ĐÚNG. `BEGIN IMMEDIATE` → inserts → `COMMIT`; catch → `ROLLBACK` (addSuppressed). Enqueue CHỈ sau commit (connection thứ 2 nhìn thấy rows tại thời điểm enqueue). FK lỗi giữa chừng (device cuối orphan) → rollback sạch cả 5 bảng, không enqueue, DB không "poisoned". Enqueuer ném lỗi sau commit → rows bền, recovery rebuild đủ. | `TransactionalPublisher.java:85-112`, `MigrationRunner.java:48-63`, `DeliveryRepo.java:147-184`; harness `rollback-orphan-midway`, `enqueue-failure-after-commit-durable-recoverable`; test `publishCommitsAllRowsAndEnqueuesOnlyAfterCommit`. |
| **WAL / busy_timeout** | ✅ WAL xác nhận từng connection (throw nếu bị từ chối); reader isolation đúng; writer thứ 2 đợi ~1340ms sau lock rồi commit thành công (busy_timeout=5000 hiệu lực). ⚠️ Một race thứ tự pragma lúc first-open → xem **L1**. | `Db.java:36-41`; harness `wal-reader-isolation`, `busy-timeout-rides-out-writer-lock`, `concurrent-first-open-fresh-db`. |
| **Idempotency requestId** | ✅ ĐÚNG. `alert_operations.request_id TEXT NOT NULL UNIQUE` → duplicate bị rollback deterministic, không leak row. Đua 8 thread cùng requestId: đúng 1 commit + 1 enqueue batch, 7 rollback. `requestId=null` bỏ qua idempotency (documented, opt-in) → INFO I3. Hash payload có vấn đề fingerprint → **L4** (không ảnh hưởng tính đúng của idempotency). | `002_showcase_expansion.sql:78`, `TransactionalPublisher.java:96-98,115-131`; harness `concurrent-publish-same-requestId`, `null-requestId-skips-idempotency`. |
| **Recovery có bỏ sót state?** | ✅ KHÔNG sót theo spec. Seed đủ 8 state → scan/recover trả đúng {PENDING, SENT, RETRY_SCHEDULED, FAILED} (FAILED included đúng spec), loại 4 state terminal; recover đúng 1 lần/delivery, read-only, restart-safe (test + harness độc lập). Lưu ý: filter theo delivery-state, KHÔNG theo alert-state → INFO I1 (tombstone cancel/expire là việc của scheduler theo PROTOCOL.md). | `DeliveryRepo.java:76-88`, `RecoveryScanner.java:25-41`, `DeliveryState.isActive()`; harness `recovery-state-coverage`, `recovery-ignores-alert-state`. |
| **Timestamp UTC nhất quán** | ✅ NHẤT QUÁN. `DateTimeFormatter.ISO_INSTANT` → luôn `...Z`; round-trip chính xác 0ns/500ms/123456789ns; publish dùng MỘT `clock.instant()` cho alert/revision/deliveries/operation; Clock injectable, default `systemUTC`. ⚠️ Fraction variable-width gây sai ORDER BY TEXT khi trộn precision → **L2**. | `Sql.java:12,20-31`, `TransactionalPublisher.java:72-83`; harness `timestamp-utc-roundtrip`, `publish-single-clock-instant`, `timestamp-lexicographic-order`. |

## 3. Thử nghiệm đối kháng độc lập (scratch `review-b`)

Harness: `ReviewB.java` (27 check, package `aegis.persistence`, reuse `Fixtures`/`TestDb` từ test-classes, chạy trên DB scratch riêng) + `ReviewB2.java` (probe quy kết lỗi concurrent-migrate). Kết quả cuối: **15 PASS · 5 WEAK · 7 INFO · 0 FAIL** (`harness-results.txt`, `dbs-final/results.txt`).

Các check PASS đáng chú ý (ngoài coverage của 35 test gốc):
- Injection qua username/nội dung (6 payload) — không bypass, không hỏng bảng.
- 8 thread publish requestId khác nhau: 8 alerts / 24 deliveries / 8 operations, không lỗi.
- 8 thread cùng requestId: exactly-once commit + enqueue.
- Orphan FK giữa transaction (device cuối) → rollback toàn bộ.
- Enqueuer chết sau COMMIT → durability + RecoveryScanner rebuild 3/3.
- `busy_timeout` chịu lock writer giữ 1200ms; WAL reader isolation.
- `UNIQUE(alert_id,revision,device_id)` chặn delivery trùng.
- `foreign_key_check` sạch trên 28 DB sau mọi thí nghiệm.

WEAK (weakness đã chứng minh được, chi tiết ở mục 4): `ack-cross-device-mismatch`, `concurrent-first-open-fresh-db` (SQLITE_BUSY tái hiện 2/3 và 1/3 trials; probe fresh-migrate 3/5 trials có 1–2 lỗi SQLITE_BUSY, WAL-mode 0 lỗi), `timestamp-lexicographic-order`, `ack-duplicate-kind-throws`, `payload-hash-collision`.

## 4. Issues theo severity (file:dòng + bằng chứng + khuyến nghị)

### 🟠 MEDIUM — 1 issue

**M1. `recordAck` không ràng buộc delivery ↔ device/alert/revision → thiết bị khác có thể ack/cancel nhầm (hoặc cố ý) delivery của nhau.**
- **Vị trí:** `DeliveryRepo.java:137-186` (INSERT ack `151-163`, UPDATE state `164-173` chỉ `WHERE id=?` + rank guard); schema `device_acks` `001_base_schema.sql:14` chỉ FK tồn tại, không composite-consistency.
- **Bằng chứng (harness `ack-cross-device-mismatch`):** `recordAck(devA, alert, rev1, deliveryId_của_devB, USER, operator)` **thành công**: delivery của devB → ACKNOWLEDGED, ack row ghi công devA. Revision khai man cũng không bị kiểm.
- **Tác động:** API nhận `deviceId`/`deliveryId` rời nhau; server network-facing nếu truyền thẳng `deliveryId` do client gửi sẽ cho phép thiết bị A ack/cancel delivery của thiết bị B (phá hoại fanout/ledger). Giảm nhẹ: header `002_showcase_expansion.sql:2` tuyên bố "App enforces cross-building/zone consistency"; tuy nhiên phần wiring ack của `aegis-server` hiện là WIP (chưa có call-site `recordAck`, mới có hook `FanoutEngine.onAck:347-349`) nên chưa thể xác nhận app đã validate.
- **Khuyến nghị (rẻ, nên làm trong module):** thêm điều kiện ràng buộc vào UPDATE: `... WHERE id=? AND device_id=? AND alert_id=? AND revision=?` và coi 0-row là mismatch (rollback cả ack row); hoặc composite FK `(delivery_id, device_id, alert_id, revision)` tham chiếu deliveries. Tối thiểu: document precondition + bắt buộc server validate trước khi gọi.

### 🟡 LOW — 4 issues

**L1. `Db.open()` đặt `busy_timeout` SAU `PRAGMA journal_mode=WAL` → SQLITE_BUSY khi nhiều connection mở đồng thời lần đầu trên file mới.**
- **Vị trí:** `Db.java:36-41` (WAL switch dòng 36, busy_timeout dòng 41).
- **Bằng chứng:** harness `concurrent-first-open-fresh-db`: 8 thread `open()` file chưa WAL → SQLITE_BUSY "database file is locked" (tái hiện 2/3 trials, lần chạy cuối 1/3). Probe `ReviewB2`: 4 thread `migrate()` file FRESH → 3/5 trials có 1–2 lỗi SQLITE_BUSY; file ĐÃ WAL → 0/3 trials lỗi; schema cuối cùng vẫn đúng 26 bảng, re-migrate no-op (không hỏng dữ liệu, tự lành khi retry).
- **Nguyên nhân:** chuyển journal_mode cần lock ngắn; lúc đó chưa có busy handler nên không đợi. Race chỉ tồn tại ở cửa sổ first-open (sau khi DB đã WAL, pragma là no-op).
- **Khuyến nghị:** đảo thứ tự — `PRAGMA busy_timeout=5000` ngay sau `foreign_keys`, trước `journal_mode=WAL`; hoặc đồng bộ hóa lần open/migrate đầu tiên trước khi dùng concurrent.

**L2. Timestamp ISO_INSTANT fraction variable-width → `ORDER BY` TEXT sai thứ tự thời gian khi trộn precision.**
- **Vị trí:** `Sql.java:12` (`TS = DateTimeFormatter.ISO_INSTANT`); các query bị ảnh hưởng: `AuditRepo.java:58` (`ORDER BY occurred_at DESC`), `DeliveryRepo.java:69,80-81` (`ORDER BY created_at, id`), `AlertRepo.java:100`.
- **Bằng chứng (harness `timestamp-lexicographic-order`):** event FIRST `2026-11-21T07:00:00Z` và SECOND `2026-11-21T07:00:00.500Z` (sau 500ms) → `recent()` trả FIRST trước vì `'Z' (0x5A) > '.' (0x2E)` theo byte. ISO_INSTANT phát fraction 0/3/6/9 chữ số tùy giá trị nano.
- **Tác động:** tiềm ẩn với một system clock đồng nhất (production hiện tại đều cùng precision); lộ ra khi trộn instant tròn giây (Clock.fixed, ts client gửi, import truncation) với instant có fraction — audit timeline và thứ tự recovery oldest-first có thể sai. Test hiện tại không bắt được vì `RepositoriesTest.java:285-292` dùng clock cố định cùng timestamp (tie-break rowid).
- **Khuyến nghị:** formatter fixed-width UTC (vd `uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'` — luôn 9 chữ số nano) hoặc lưu epoch millis; giữ `Instant.parse` tương thích ngược khi đọc (parse được cả format cũ). Thêm test ordering trộn precision.

**L3. `recordAck` ném SQLException với duplicate ack cùng kind — traffic bình thường của transport at-least-once.**
- **Vị trí:** `DeliveryRepo.java:151-163` + UNIQUE `device_acks(device_id,alert_id,revision,ack_kind)` `001_base_schema.sql:14`.
- **Bằng chứng (harness `ack-duplicate-kind-throws`):** RECEIVED lần 2 cho cùng (device,alert,revision) → SQLException; rollback atomic (state vẫn RECEIVED, không leak ack row). Tính đúng state được bảo toàn.
- **Tác động:** thiết bị retry ack (gói ack thất lạc — kịch bản T-13/14 trong TEST-PLAN) sẽ nhận lỗi từ persistence layer; caller phải tự coi UNIQUE-violation là no-op benign. Nếu server map lỗi này thành NACK → vòng retry thừa.
- **Khuyến nghị:** cho ack INSERT dùng `ON CONFLICT(device_id,alert_id,revision,ack_kind) DO NOTHING` rồi vẫn chạy guarded UPDATE → `recordAck` idempotent; hoặc document rõ contract "SQLITE_CONSTRAINT_UNIQUE = duplicate, treat as success" và xử lý ở server.

**L4. `payload_hash` không phải fingerprint trung thực của payload (canonicalization mơ hồ + thiếu trường).**
- **Vị trí:** `TransactionalPublisher.java:133-142`.
- **Bằng chứng (harness `payload-hash-collision`):** `(title="a\nb", body="c")` và `(title="a", body="b\nc")` cho CÙNG sha256 (nối `\n` không chiều dài); đồng thời `buildingId`, `category`, `priority`, `createdBy`, `expiresAt` không nằm trong hash (đổi priority EMERGENCY→INFO + expiresAt vẫn cùng hash).
- **Tác động:** CHỈ ảnh hưởng giá trị audit/đối soát của `alert_operations.payload_hash`. Idempotency vẫn đúng tuyệt đối vì reject dựa trên UNIQUE `request_id` (`002:78`), không dựa vào hash.
- **Khuyến nghị:** canonicalization length-prefixed hoặc JSON chuẩn (Jackson sorted keys) phủ toàn bộ trường của Request; ghi chú version của thuật toán hash nếu cần đối soát về sau.

### ⚪ INFO — 8 ghi chú (không phải lỗi blocking; phần lớn là ranh giới thiết kế đã document)

| # | Ghi chú | Vị trí |
|---|---|---|
| I1 | Recovery filter theo delivery-state, không theo alert-state: delivery PENDING của alert đã CANCELLED vẫn được recover. Đúng spec module; PROTOCOL.md:7 giao tombstone cancel/expire cho scheduler. **Cần verify aegis-core/server chặn redelivery alert đã cancel** (đối chiếu chéo khi review core). | `DeliveryRepo.java:76-88`, `RecoveryScanner.java:25-41` |
| I2 | `updateState` (cả Delivery lẫn Alert) không có guard lifecycle/monotonic — ACKNOWLEDGED→PENDING, ACTIVE→DRAFT đều thành công (harness chứng minh). Guard CASE-rank chỉ có trong `recordAck`. Thiết kế giao state-machine cho scheduler; cân nhắc trigger/CHECK nếu muốn persistence tự enforce. | `DeliveryRepo.java:90-101`, `AlertRepo.java:111-118` |
| I3 | `requestId=null` → không ghi alert_operations, publish lặp lại đều commit (documented trong javadoc Request). Caller bắt buộc truyền requestId để CREATE retry-safe. | `TransactionalPublisher.java:30,96-98` |
| I4 | Publish nhắm device khác building vẫn commit (FK chỉ kiểm tồn tại). Header 002:2 đã tuyên bố app enforce cross-building/zone consistency — server phải validate scope target. | `002_showcase_expansion.sql:2`, harness `cross-building-target-allowed` |
| I5 | `role_grants` không UNIQUE(user,building,floor,scope) → grant trùng lặp được 2 rows. Cân nhắc UNIQUE index hoặc dedup phía app. | `001_base_schema.sql:6`, harness `duplicate-role-grants-allowed` |
| I6 | `addRevision()` + `setCurrentRevision()` là 2 transaction riêng (không có composite helper cho UPDATE path): crash giữa chừng để `current_revision` lệch `alert_revisions`. Publisher đã atomic cho rev1; đường UPDATE cần app gói 1 transaction. | `AlertRepo.java:120-138` |
| I7 | MigrationRunner: sentinel-table không phát hiện schema drift/script đã sửa sau khi apply (trade-off đã chọn: không bảng bookkeeping, giữ đúng 26 bảng); `splitStatements` không hỗ trợ `/* */` hay double-quoted identifiers (đủ cho 2 script hiện tại, cần nâng cấp nếu thêm trigger); `PRAGMA foreign_keys=ON` trong script (001:2, 002:3) là **no-op trong transaction** — vô hại vì Db.open đã set, nhưng comment trong file WIP chưa commit `seed_demo.sql` quy kết FK enforcement cho các PRAGMA này là sai (nguồn đúng: Db.open). Concurrent migrate an toàn nhờ `IF NOT EXISTS` (ngoại lệ L1 ở first-open). | `MigrationRunner.java:23-26,44,96-139`, `001:2`, `002:3` |
| I8 | CANCEL sau ACKNOWLEDGED → CANCELLED (rank 3→4): **đã kiểm chứng, nhất quán** với rank model và PROTOCOL.md:7 ("ACK only advances state", cancel tombstone); terminal, ack sau không đảo được. Không phải bug. | `DeliveryRepo.java:141-145,188-196`, harness `ack-cancel-after-user` |

## 5. Khuyến nghị ưu tiên

1. **(M1)** Thêm binding `device_id/alert_id/revision` vào UPDATE của `recordAck` (hoặc composite FK) — 1 dòng WHERE, chặn ack chéo thiết bị ngay tại persistence.
2. **(L1)** Đảo thứ tự pragma trong `Db.open`: `busy_timeout` trước `journal_mode=WAL` — hết race first-open.
3. **(L3)** Cho `recordAck` idempotent với duplicate kind (`ON CONFLICT DO NOTHING`) hoặc document contract xử lý UNIQUE ở server; thêm test duplicate-ack.
4. **(L2)** Chuẩn hóa timestamp fixed-width (hoặc epoch millis) + test ordering trộn precision.
5. **(L4)** Canonicalize payload_hash phủ đủ trường, length-prefixed/JSON.
6. **(I1, I4)** Khi review aegis-core/server: xác nhận tombstone cancel/expire chặn redelivery và validate target scope/cross-building; wiring ack của server (đang WIP) phải validate delivery thuộc device trước khi gọi `recordAck`.
7. Bổ sung test còn thiếu (không bắt buộc cho PASS): duplicate ack kind, ack mismatch device, mixed-precision ordering, concurrent first-open, concurrent migrate fresh-file.

## 6. Kết luận kiểm thử chéo

- 35/35 test gốc pass ×2 lần chạy độc lập — **không phát hiện test gian lận/không ổn định**; coverage tốt (pragmas, FK, WAL isolation, 26 bảng + idempotent migrate, atomicity commit-visibility, rollback FK/duplicate, concurrent publish, recovery restart).
- 27 check đối kháng độc lập: 15 PASS / 5 WEAK / 7 INFO / 0 FAIL — **mọi bảo đảm cốt lõi đều trụ vững trước tấn công**; 5 weakness được chứng minh bằng thực nghiệm đều ở mức MEDIUM/LOW, có khuyến nghị sửa cụ thể, không chặn tích hợp.
- **VERDICT: PASS.** Module đủ điều kiện làm nền persistence cho aegis-core/server; xử lý M1 + L1 trước khi server wiring ack đi vào production-like demo.

---

## Phụ lục A — Tái lập thí nghiệm đối kháng

```bash
cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs
S=/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-b
CP="aegis-protocol/target/classes:aegis-persistence/target/classes:aegis-persistence/target/test-classes:$HOME/.m2/repository/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar:$HOME/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar"
javac -cp "$CP" -d "$S/classes" "$S/ReviewB.java" "$S/ReviewB2.java"
java --enable-native-access=ALL-UNNAMED -cp "$CP:$S/classes" aegis.persistence.ReviewB  "$S/dbs-rerun"
java --enable-native-access=ALL-UNNAMED -cp "$CP:$S/classes" aegis.persistence.ReviewB2 "$S/dbs-probe-rerun"
```

(Yêu cầu `mvn -pl aegis-persistence test-compile` trước để có target/classes + test-classes. Race L1 không deterministik: chạy lại nhiều trial nếu chưa tái hiện.)

## Phụ lục B — Artefacts scratch (không commit)

- `ReviewB.java`, `ReviewB2.java` — harness đối kháng (27 check + probe quy kết).
- `harness-results.txt`, `dbs-final/results.txt` — kết quả lần chạy cuối (15/5/7/0).
- `probe-results.txt` — kết quả probe concurrent-migrate (FRESH 5 trials, WAL 3 trials, RECOVER).
- `dbs/`, `dbs2/`, `dbs-final/`, `dbs-probe/` — SQLite files thí nghiệm (28 DB ở lần chạy cuối, foreign_key_check sạch).
- Báo cáo này: `docs/reviews/persistence-review.md` (file mới duy nhất trong repo; không sửa source, không commit).
