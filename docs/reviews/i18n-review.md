# Peer-review i18n VI/EN — AEGIS-EBS

**Reviewer:** subagent độc lập `deleg_1c5b0486` (read-only, không commit).
**Verdict:** **PASS** (đối chiếu diff từ HEAD `4af087f`), kèm 3 finding MED và 3 finding LOW.

## Đã đối chiếu
- `src/i18n.js`: parity cấu trúc VI/EN, interpolation, fallback, guards headless, `npm test` 20/20.
- 117 `data-i18n*` ban đầu → sau sửa **122**, mọi key đều tồn tại trong dictionary.
- DOM id/class/data-tab/data-view không đổi, chỉ thêm `lang-toggle`/`lang-vi`/`lang-en`; event payload và `KIND_CATEGORY` không đổi.
- Toggle: `localStorage['aegis.lang']`, `document.documentElement.lang`, `subscribe/applyStatic`, 3D HUD unsubscribe trên dispose.
- Docs Anh: header nguồn Việt, link không gãy, giữ runId/commit SHA, disclaimer `BAO-CAO-SUMMARY.md` đúng.

## Findings đã khắc phục
| Mức | Vấn đề | Cách sửa |
|---|---|---|
| MED | Docs ghi 14 test dashboard, lệnh `node --test test/logic.test.js` | Cập nhật VI + EN thành **20 test** (`14 logic + 6 i18n`), lệnh `node --test test/logic.test.js test/i18n.test.js`; Java 338→347 có ghi chú baseline. |
| MED | `#op-status` mang chữ `idle` cứng, `statusKey` rỗng | Thêm key `operator.idle`; `opState.statusKey='operator.idle'`; gọi `opSetStatus` lúc init + subscribe đổi ngôn ngữ. |
| MED | Log Operator còn EN khi VI | Mọi template log qua `t()` (`operator.logOk/logHealthOk/logFleetStartOk/logFleetStopOk/logRetryHint`); nhãn `opWithBusy` dùng `t(...)` tại callsite. |
| LOW | Fallback `summarizeEvent` còn EN | Thêm `event.fallback.*`, gọi `t()`. |
| LOW | Summary mock incident tiếng Việt lọt sang EN | Thêm `event.mock.summary` VI/EN. Đổi biến thời gian `let t` → `cursor` để không che hàm dịch `t()` (bug phát hiện khi chạy lại test: 17/20 → sửa → 20/20). |
| LOW | Placeholder tĩnh chưa gắn i18n, 2 key không dùng | Gắn `data-i18n` cho `#rp-samples`, `#rp-window`, `#fn-count`, `#feed-mode`, `#last-update`; thêm key `*Initial`. |

## Kiểm định sau sửa
- `npm run build` OK; `npm test` **20/20 pass**.
- Browser LIVE: VI `NHÀN RỖI`, log `thống kê đội thiết bị · …`; EN `METRICS`, log mới `fleet stats · …`; `moduleErrors=[]`; reload giữ `en`.
- Ảnh: `docs/evidence/dashboard/i18n-vi.png`, `i18n-en.png`; metadata `i18n-meta.json`.
