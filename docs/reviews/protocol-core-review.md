# Peer review độc lập — aegis-protocol & aegis-core

- **Ngày review:** 2026-11-21 18:20 (+07)
- **Reviewer:** agent độc lập (KHÔNG phải tác giả 2 module)
- **Repo:** `/Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs` — HEAD `1f6777d`; module được review: `5eeca68` (protocol, 56 test), `a6e7b04` (core, 73 test)
- **Phương pháp:** (1) chạy lại `mvn -q -pl aegis-protocol,aegis-core test` + đối chiếu surefire XML; (2) audit đọc từng dòng 7 lớp trọng tâm; (3) 249 check đối kháng chạy thật trong scratch, KHÔNG sửa source module.
- **Ràng buộc tuân thủ:** không sửa source aegis-protocol/aegis-core (xác nhận `git diff --stat -- aegis-protocol aegis-core` rỗng), không git commit; chỉ tạo file này + scratch `~/.hermes/profiles/cmp180-tutor/cache/scratch/review-a/`.

---

## VERDICT: **PASS** (0 critical, 0 major, 11 minor)

Cả 129 test module pass khi chạy lại. 249 check đối kháng pass, không phát hiện vi phạm contract nào ở các hạng mục bị nghi ngờ (tràn số backoff, race dedup, RBAC bypass, priority starvation). 7 oddity được chứng minh bằng probe + 4 quan sát audit → xếp loại minor, không chặn tích hợp.

---

## 1. Bảng test rerun (nguồn: surefire XML, không phải console)

Lệnh: `cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs && mvn -q -pl aegis-protocol,aegis-core test` → **exit 0**. XML trong `target/surefire-reports/` được sinh mới tại thời điểm review (đối chiếu mtime).

| Module | Suite | Tests | Failures | Errors | Skipped | Time (s) |
|---|---|---:|---:|---:|---:|---:|
| aegis-protocol | FrameCodecFixtureTest | 20 | 0 | 0 | 0 | 0.156 |
| aegis-protocol | FrameCodecStreamTest | 13 | 0 | 0 | 0 | 0.009 |
| aegis-protocol | FrameCodecValidationTest | 23 | 0 | 0 | 0 | 0.030 |
| **aegis-protocol** | **3 suites** | **56** | **0** | **0** | **0** | |
| aegis-core | AckLedgerTest | 13 | 0 | 0 | 0 | 0.008 |
| aegis-core | DedupIndexTest | 9 | 0 | 0 | 0 | 0.008 |
| aegis-core | PrioritySchedulerTest | 13 | 0 | 0 | 0 | 0.006 |
| aegis-core | RetryPolicyTest | 11 | 0 | 0 | 0 | 0.006 |
| aegis-core | ScopedRBACTest | 13 | 0 | 0 | 0 | 0.006 |
| aegis-core | TargetResolverTest | 14 | 0 | 0 | 0 | 0.029 |
| **aegis-core** | **6 suites** | **73** | **0** | **0** | **0** | |
| **TỔNG** | **9 suites** | **129** | **0** | **0** | **0** | |

Khớp commit message (56 + 73). Lưu ý: working tree có thay đổi đồng thời của agent khác ở aegis-client/dashboard/server/simulator; **2 module review không có diff** nên kết quả trên phản ánh đúng source đã commit.

## 2. Test đối kháng (scratch, chạy thật)

- Thư mục: `/Users/twot/.hermes/profiles/cmp180-tutor/cache/scratch/review-a/`
- File: `Probe.java` (harness), `ProtocolProbes.java`, `RetryDedupAckProbes.java`, `RbacTargetSchedulerProbes.java`, log `probe-run.log`, classpath `cp.txt`, output `out/`.
- Chạy lại: `cd scratch/review-a && javac --release 24 -cp "$(cat cp.txt)" -d out *.java && java -cp "out:$(cat cp.txt)" Probe`
- Kết quả: **249 PASS / 0 FAIL / 7 QUIRK-CONFIRMED** (exit 0). Probe biên dịch với JDK 24.0.1, chạy thẳng trên `target/classes` của 2 module + Jackson 2.17.2 từ `~/.m2` — không đụng Maven module.

Nhóm probe: P1–P18 (framing/UTF-8/JSON/version), C1–C6 (backoff/overflow), D1–D5 (dedup race), A1–A6 (ack race), R1–R7 (RBAC/Grants), T1–T5 (TargetResolver), S1–S5 (scheduler flood/random/concurrent).

## 3. Kết quả audit theo hạng mục yêu cầu

| Hạng mục nghi ngờ | Kết luận | Bằng chứng |
|---|---|---|
| Framing oversize trước allocation | **ĐẠT** | `checkLength` (FrameCodecImpl L83–87) gọi trước `new byte[len]` ở cả `decode` (L53) lẫn `readFrame` (L69). Probe P1/P2: prefix 0, 65537, 0x80000000, 0xFFFFFFFF, 2^20 với stream "đầu độc" (ném AssertionError nếu đọc byte thứ 5) → FrameException, không đọc body. |
| readFully / partial / coalesced | **ĐẠT** | P12: 2 frame + sentinel, chunk 1/3/65536 → đọc đúng từng frame, sentinel còn nguyên, không over-read. P13: EOF giữa header/body → EOFException. Fixture test có sẵn cũng phủ. |
| UTF-8 strict | **ĐẠT** | Decoder REPORT (L104–108). P3: 9 mẫu (0xFF/0xFE, overlong C0 AF + E0 80 AF, surrogate ED A0 80 / ED BF BF, cụt 2/4 byte, > U+10FFFF) đều bị từ chối. P18: tiếng Việt + emoji 4-byte round-trip nguyên vẹn. |
| version == 2 | **ĐẠT** | P6: `"2"`, 2.0, 2e0, 3, 0, −2, 2147483648, true, null, thiếu version → FrameException. `isInt()` chặn cả float/long (L129). |
| Tràn số backoff | **ĐẠT** | `DELAY_CAP = Long.MAX_VALUE/2` (L23), nhân đôi có chặn (L64–66), guard `total < 0 → Long.MAX_VALUE` (L73) đúng vì exponential ≤ 2^62−1 và jitter < 2^63 nên overflow chỉ wrap sang âm. C1/C2: base = Long.MAX_VALUE và CAP+1, n tới Integer.MAX_VALUE → luôn dương, bão hòa CAP/MAX. C3: base=1 exact 2^n tới n=61, bão hòa từ n=62. |
| Race dedup | **ĐẠT** | `ConcurrentHashMap.compute` atomic (L26–32). D1: 64 thread × 10 round first-sight → đúng 1 deliver. D2: 8 thread × 200 revision xáo trộn → mỗi revision deliver ≤ 1 lần, revision max luôn deliver đúng 1. A1/A6: ack cùng client 32 thread → đúng 1 advance; 1000 cặp ack đua → exactly-once, 100 alert complete. |
| RBAC bypass (FM vượt scope, EMPLOYEE publish) | **KHÔNG CÓ** | `canPublish` (ScopedRBAC L22–33): FM chỉ qua `coversFloor(buildingId, floorId)` — grant building-only KHÔNG đủ (R1), sai floor/sai building bị chặn; EMPLOYEE/DEVICE → false kể cả full grants (R3); SO chỉ trong building được grant (R2); null grants → scoped roles deny (R3). Grants defensive-copy thật: sửa source sau khi khởi tạo không ảnh hưởng (R5). |
| Priority starvation | **KHÔNG CÓ** | S1 flood 5000 CRITICAL + 500×3 tier dưới: run CRITICAL liên tiếp tối đa 8 khi tier dưới còn hàng; E/W/I được phục vụ lần đầu ở dispatch 8/17/26; 2700 dispatch đầu chia relief đúng 100/100/100. S3: 20.000 ops ngẫu nhiên — size invariant, FIFO từng tier, không mất/trùng, burst bound giữ nguyên. S4: 4 producer + 1 consumer đồng thời → đủ 10.000 item, không trùng. (Có 1 inversion 1-dispatch do state cũ — MINOR-7, không phải starvation.) |
| Null handling | **ĐẠT, 2 điểm không nhất quán** | Mọi API null-check fail-fast (NPE có message) trừ: `readFrame(null)`/`writeFrame(null,…)` ném NPE thô thay vì FrameException như `decode(null)`/`encode(null)` (MINOR-8); canonical ctor Grants NPE với null floor-value trong khi `Grants.of()` coi như rỗng (MINOR-9). |

## 4. Danh sách issue theo severity

### Critical (0) — không có
### Major (0) — không có

### Minor (11)

| # | Issue | File + dòng | Bằng chứng | Khuyến nghị |
|---|---|---|---|---|
| MINOR-1 | JSON trùng key được chấp nhận, last-wins (`{"version":1,"version":2,…}` → version 2; type BOGUS→HELLO). RFC 8259: names SHOULD be unique; parser khác upstream có thể hiểu khác → rủi ro policy ambiguity/smuggling. | `aegis-protocol/.../FrameCodecImpl.java` L113–126 (`mapper.readTree`) | Probe P7 QUIRK-CONFIRMED | Bật `JsonParser.Feature.STRICT_DUPLICATE_DETECTION` trên mapper dùng cho decode (hoặc từ chối frame có key trùng). |
| MINOR-2 | `sentAt` chỉ kiểm tra non-blank, không validate UTC ISO-8601 như envelope line của contract (`"yesterday"` được nhận). | `FrameCodecImpl.java` L143, L151–157; `docs/PROTOCOL.md` L5 | Probe P8 QUIRK-CONFIRMED | Validate `Instant.parse`/`DateTimeFormatter.ISO_INSTANT` trong codec, hoặc ghi rõ handler chịu trách nhiệm validate. |
| MINOR-3 | Lone surrogate escape `\uD800` trong payload lọt qua strict-UTF-8 (wire bytes hợp lệ) → TextNode chứa surrogate đơn; encode lại phía đối tác có thể biến thành `?` → interop hazard. | `FrameCodecImpl.java` L101–111 (decodeBody) + Jackson databind | Probe P9 QUIRK-CONFIRMED | Quét TextNode reject lone surrogate, hoặc document rõ hành vi; cân nhắc `JsonWriteFeature.ESCAPE_NON_ASCII` khi encode. |
| MINOR-4 | Quy ước index `delayMs(attemptsSoFar) = base·2^attemptsSoFar`: vòng lặp retry tự nhiên gọi `delayMs(attemptsSoFar)` sau attempt 1 sẽ ra 6/12/24s thay vì demo 3/6/12s của PROTOCOL.md; caller phải truyền `attemptsSoFar−1`. Class tự nhất quán với javadoc + test, nhưng dễ dùng sai ở server. | `aegis-core/.../RetryPolicy.java` L9–14 (javadoc), L54–57 (`shouldRetry`), L60–74 (`delayMs`); `docs/PROTOCOL.md` L9 | Probe C6 QUIRK-CONFIRMED | Ghi rõ "delayMs nhận số retry đã thực hiện (0-based)" hoặc thêm helper `delayBeforeRetry(int attemptNumber)`; **verify cách gọi trong aegis-server khi module này commit**. |
| MINOR-5 | Composite key `alertId + '\0' + deviceId` va chạm nếu ID chứa NUL: `("a\0b","c")` và `("a","b\0c")` chung slot → suppress nhầm delivery. Thực tế ID là UUID nên rủi ro thấp. | `aegis-core/.../DedupIndex.java` L24 | Probe D4 QUIRK-CONFIRMED | Reject ID chứa `\0` (validate đầu vào) hoặc dùng key độ dài-prefix / record 2 trường. |
| MINOR-6 | Cùng lỗi composite-key NUL trong index floor/zone: 2 thiết bị ở scope khác nhau bị gộp một danh sách target. | `aegis-core/.../TargetResolver.java` L93–95 (`key`), L44–48 | Probe T4 QUIRK-CONFIRMED | Như MINOR-5: validate ID không chứa NUL khi nạp registry. |
| MINOR-7 | State burst không reset khi scheduler drain: drain đúng lúc `burstCount=8` rồi offer mới 1 CRITICAL + 1 INFO → poll đầu tiên trả INFO (priority inversion 1 dispatch, không starvation). | `aegis-core/.../PriorityScheduler.java` L32–34 (fields), L53–75 (`poll`), nhánh empty L55–57 không reset | Probe S2 QUIRK-CONFIRMED | Reset `burstTier/burstCount/lastReliefTier` khi `highest < 0` (scheduler rỗng). |
| MINOR-8 | `readFrame(null)` / `writeFrame(null, e)` ném NPE thô từ DataInputStream/OutputStream, không nhất quán với `decode(null)`/`encode(null)` → FrameException. | `FrameCodecImpl.java` L64–65, L76–79 | Probe P10 (NPE xác nhận) | `Objects.requireNonNull(in, "in")` có message, hoặc bọc FrameException cho nhất quán contract. |
| MINOR-9 | Canonical ctor `Grants` NPE khi value của `floorsByBuilding` null (`Set.copyOf(null)`), trong khi `Grants.of()` coi null như tập rỗng — hai đường khởi tạo null-tolerance khác nhau. | `aegis-core/.../Grants.java` L26 (ctor) vs L42 (`of`) | Probe R6 (cả hai hành vi xác nhận) | Chuẩn hóa null → `Set.of()` ngay trong canonical ctor để mọi đường tạo đều nhất quán. |
| MINOR-10 | `AckLedger.entries` không có close/evict — alert mở rồi complete vẫn chiếm memory vĩnh viễn; server chạy dài sẽ phình bộ nhớ. | `aegis-core/.../AckLedger.java` L38, L41–52 (toàn class 83 dòng, không có remove) | Audit code (không cần probe) | Thêm `close(alertId)`/`completeAndRemove` hoặc TTL-evict sau khi alert ACK/EXPIRED/CANCELLED. |
| MINOR-11 | `DedupIndex.highestRevision` không TTL/eviction — tăng trưởng không giới hạn theo số (alert, device) từng thấy. | `aegis-core/.../DedupIndex.java` L18 | Audit code | Evict theo vòng đời alert (tombstone expire) hoặc bounded cache; cân nhắc cùng cơ chế với MINOR-10. |

Ghi chú phân loại: MINOR-1…9 đều tái hiện được bằng probe (log `probe-run.log`); MINOR-10/11 là quan sát thiết kế vòng đời, chưa gây lỗi chức năng trong phạm vi 2 module thuần logic.

## 5. Khuyến nghị ưu tiên

1. **Trước khi tích hợp aegis-server:** chốt quy ước index retry (MINOR-4) và kiểm tra chỗ gọi `delayMs`; reset burst state khi drain (MINOR-7) — sửa 3 dòng, loại inversion.
2. **Hardening codec (rẻ, làm sớm):** STRICT_DUPLICATE_DETECTION (MINOR-1), validate sentAt ISO-8601 (MINOR-2), xử lý lone surrogate (MINOR-3).
3. **Validate ID không chứa NUL** ở biên nạp dữ liệu (MINOR-5/6) — UUID thực tế không chứa NUL nên có thể xếp sau.
4. **Vòng đời bộ nhớ:** thêm close/evict cho AckLedger + DedupIndex (MINOR-10/11) trước khi chạy demo dài ngày.
5. **Nhất quán null:** MINOR-8/9 sửa cùng đợt với hardening.
6. Giữ nguyên các điểm đã đạt: oversize-before-allocation, readFully/coalesced, strict UTF-8, compute-atomic dedup, synchronized ledger, burst fairness — đây là phần lõi đúng, đừng refactor liều.

## 6. Phạm vi & hạn chế

- Chỉ review 2 module aegis-protocol/aegis-core (source đã commit). Payload typing theo MessageType là trách nhiệm handler (Envelope javadoc) — ngoài phạm vi.
- aegis-server/client chưa commit tại thời điểm review → MINOR-4 cần đối chiếu lại với caller thực tế.
- Working tree có thay đổi song song của agent khác ở module khác; review này không đụng tới và đã xác nhận 2 module review không diff.
- Không git commit; artifacts duy nhất trong repo là file báo cáo này.

---
*Harness đối kháng: `~/.hermes/profiles/cmp180-tutor/cache/scratch/review-a/` — 249 check PASS, 0 FAIL, 7 quirk confirmed; chạy lại bằng lệnh ở mục 2.*
