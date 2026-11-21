# Agents.md — Quy trình đa agent xây dựng AEGIS-EBS

Dự án này được xây dựng bởi một **orchestrator** (agent `cmp180-tutor`) điều phối nhiều **subagent** làm việc song song, có **tầng peer-review chéo** trước khi nghiệm thu. Tài liệu này mô tả cách tổ chức để bất kỳ ai (hoặc agent khác) có thể tiếp tục mở rộng dự án nhất quán.

## 1. Vai trò

| Vai trò | Trách nhiệm | Quyền hạn |
|---|---|---|
| **Orchestrator** | Dựng nền tảng chung, chia việc theo wave, kiểm định chung cuộc, commit, push | Sở hữu contract/schema/scripts; commit duy nhất |
| **Worker** | Implement một module theo TDD | Chỉ sửa module được giao; **không** git commit |
| **Reviewer** | Kiểm thử chéo công việc worker khác: chạy lại test, audit bug/bảo mật, ra verdict | **Chỉ đọc** source + chạy test; viết `docs/reviews/<module>-review.md` + scratch |
| **Fixer** | Sửa issue do reviewer phát hiện theo TDD | Sửa module liên quan; giữ API; không commit |

## 2. Nguyên tắc bất biến

1. **Foundation-first:** orchestrator dựng Maven reactor + contract EBSP/2.0 (`aegis-protocol` interface/enum/Envelope **đóng băng**) + schema 26 bảng (`001`+`002`) + scripts, kiểm chứng thật (`mvn compile`, `sqlite3` 26 bảng) rồi commit trước khi fan-out.
2. **Sở hữu file nghiêm ngặt:** mỗi worker owns đúng 1 thư mục module (hoặc 1 file docs). Không hai agent cùng sửa một module → tránh xung đột.
3. **Agent không commit:** orchestrator commit sau kiểm định (tránh khóa git index đồng thời).
4. **TDD mọi task:** viết test FAIL → chạy xác nhận FAIL → implement tối thiểu → chạy PASS → báo cáo.
5. **Peer-review trước nghiệm thu:** một reviewer *khác tác giả* chạy lại test + audit (wire-level RBAC, ACK giả, retry, TLS, recovery, logging…) → verdict PASS/FAIL + issues theo severity. Orchestrator đọc review, giao fixer nếu cần, rồi mới nghiệm thu.
6. **Kiểm chứng thật, không tin báo cáo suông:** orchestrator chạy lại `mvn -q -pl <module> test`, đếm từ **surefire XML** (`-q` ẩn số), đọc code, xác nhận contract/schema không đổi, rồi commit + `mvn -pl <module> -DskipTests install` cho module下游.
7. **Trung thực học thuật:** chỉ claim thứ demo được; số liệu đo thật kèm runId/SHA/máy; gắn nhãn SIMULATION; không bịa kết quả.

## 3. Các wave đã thực hiện

- **Wave 1 (song song):** `aegis-protocol` · `aegis-core` · `aegis-persistence` · `aegis-dashboard` shell. Độc lập nhờ contract cố định.
- **Wave 2 (song song, phụ thuộc jar wave 1 đã install):** `aegis-server` · `aegis-client`+`aegis-simulator` · `aegis-network-lab`.
- **Review + tăng tốc (song song):** reviewer protocol+core, reviewer persistence; dashboard nâng cao (timeline/chaos/reports/funnel); báo cáo Chương 1–2; CI/seed/ERD/SECURITY/SLIDES.
- **Fix:** fixer persistence (M1 cross-device ack + L1–L4); fixer server (regression duplicate-ACK sau fix M1 + gap bảo mật).
- **Wave tích hợp (tiếp theo):** full-reactor `mvn verify`, end-to-end + chaos C01–C16 + đo p50/p95/p99 + báo cáo Chương 3–4 + demo.

## 4. Bản đồ sở hữu module

| Module | Worker | Reviewer | Trạng thái |
|---|---|---|---|
| aegis-protocol | wave1 | review-a | PASS (56 test, 11 minor) |
| aegis-core | wave1 | review-a | PASS (73 test) |
| aegis-persistence | wave1 + fixer | review-b | PASS sau fix (49 test) |
| aegis-server | wave2 + fixer | review-c | đang sửa regression (52 test mục tiêu) |
| aegis-client | wave2 | review-d | PASS (37 test, 1 medium) |
| aegis-simulator | wave2 | review-d | PASS (19 test) |
| aegis-network-lab | wave2 | (self-verified) | PASS (31 test, multicast thật) |
| aegis-dashboard | wave1 + nâng cấp | (browser smoke) | PASS (14 test, build OK) |

## 5. Skills tái sử dụng (profile cmp180-tutor)

| Skill | Dùng khi |
|---|---|
| `cmp180-textbook-map` | Tra giáo trình CMP180: bản đồ bài, 29 câu hỏi, lỗi code sách |
| `aegis-orchestration` | Điều phối wave + peer-review + kiểm định |
| `aegis-ebs-persistence` | Mở rộng/tích hợp SQLite persistence (quy ước, pitfall) |
| `aegis-ebs-server` | Kiến trúc server + pitfall |
| `aegis-ebs-client-simulator` | Contract client/simulator, lệnh chạy, pitfall |
| `aegis-network-lab` | UDP/multicast/TLS lab, benchmark, pitfall macOS |
| `aegis-dashboard` | View, contract status/1, build, pitfall |
| `aegis-ebs-report` | Viết/sửa báo cáo docs/BAO-CAO.md |

## 6. Pitfall đã gặp (quan trọng)

- **HTTP 429 fair-share** thường xảy ra ở bước *phát báo cáo cuối* sau khi work đã xong. Một child `status=failed` vẫn có thể đã hoàn tất code → **luôn kiểm chứng artifact** (file + chạy lại test) trước khi giao lại.
- **`mvn -pl <module>` không `-am`** resolve dependency từ jar install gần nhất trong `~/.m2` → kết quả phụ thuộc bản install; acceptance nên `-am` hoặc install có chủ đích. (Đây là nguồn regression server khi persistence fix đổi `recordAck`.)
- **Concurrent mvn** trên các module khác nhau an toàn; install jar chung một lần để worker không rebuild module chung (`-am` race).
- Maven chạy trên Homebrew **JDK 27** với `release=24`; sqlite-jdbc phát warning native-access benign.
- **Multicast macOS `lo0`** gửi được nhưng không nhận (không route) → probe send/receive thật để phát hiện, fallback unicast; multicast thật chạy `en0`/LAN.
- **Surefire** chỉ nhận `*Test`/`Test*`/`*Tests`/`*TestCase`; class `*IT` bị bỏ qua thầm lặng.
- **Timestamp SQLite** phải fixed-width (nano 9 chữ số) để `ORDER BY TEXT` đúng thứ tự.

## 7. Cách mở rộng bằng agent

1. Đọc skill liên quan + `docs/PROTOCOL.md` + `docs/ARCHITECTURE.md`.
2. Xác định module sở hữu; **không** đụng module khác/contract.
3. TDD: thêm test FAIL → implement → PASS (`mvn -q -pl <module> test`, đếm surefire).
4. Nếu đổi API module nền (protocol/persistence/core), install lại jar và chạy lại module phụ thuộc.
5. Giao reviewer chéo trước khi orchestrator nghiệm thu + commit.
6. Cập nhật skill tương ứng với pitfall mới.

## 8. Bằng chứng kiểm định (tính đến commit gần nhất)

- 317 test Java + 14 test dashboard; peer-review reports trong `docs/reviews/`.
- Integration TLS loopback thật: auth → publish → fanout → ACK → retry → FAILED → RBAC deny → cancel tombstone → reconnect SYNC → WS snapshot/delta → heartbeat suspect → slow-client eviction → server restart recovery.
- Multicast thật đo được: giảm ~87.5% gói / ~97.8% byte so TCP fanout (8 subscriber × 200 message).
- Simulator fleet 100 agent: 200/200 ACK; fault injection cùng seed cho kết quả identical.
