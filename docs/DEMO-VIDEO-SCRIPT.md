# KỊCH BẢN QUAY VIDEO DEMO — AEGIS-EBS (12 phút)

> SIMULATION/TRAINING ONLY xuyên suốt. Khớp `docs/DEMO-RUNBOOK.md`.
>
> **Video demo đã tạo:** `docs/evidence/video/aegis-ebs-demo.mp4` — **720,05 s (12:00)**, 1280×720/24 fps, H.264+AAC, 4,2 MB. Dựng bằng `scripts/make-demo-video.py` từ **evidence thật** (ảnh dashboard LIVE `docs/evidence/dashboard/twin-live-overview.png`, số liệu benchmark/chaos/verify) + voiceover tiếng Việt (`say -v Linh`). Đây là **video thuyết trình có số liệu đã kiểm chứng**, KHÔNG phải bản quay màn hình live 12 phút; metadata trung thực trong `demo-video-meta.json`. Nếu cần bản quay live OBS đúng thao tác, dùng storyboard bên dưới.
> Kiểm chứng: `ffprobe` → duration 720,046440 s; khung 08:20 trích tại `docs/evidence/video/frame-live-check.png` (đã soát bằng vision: không chồng chữ, có ảnh dashboard LIVE).

## Checklist chuẩn bị trước quay (dành cho bản quay live OBS; video đã dựng từ evidence như nêu ở trên)
- [ ] `./scripts/dev.sh` BUILD_OK; `./scripts/initdb.sh` + seed; `./scripts/gen-server-cert.sh`.
- [ ] Server chạy (9443/9444); dashboard `npm run dev` (5173); 1 client JavaFX + simulator fleet sẵn sàng.
- [ ] Màn hình: dashboard 3D (chính) + terminal lệnh + client popup (phụ).
- [ ] OBS: scene chuyển dashboard/terminal/client; mic voiceover; banner SIMULATION luôn hiển thị.
- [ ] Fallback: video quay sẵn từng cảnh + simulator deterministic nếu mạng LAN chặn multicast.
- [ ] Không phát cảnh báo giả ra mạng thật; mọi alert nhãn SIMULATION.

## Storyboard
| Time | Cảnh | Lời dẫn (voiceover) | Thao tác màn hình | Hình mong đợi |
|---|---|---|---|---|
| 0:00–0:45 | Giới thiệu | "AEGIS-EBS — trung tâm chỉ huy truyền thông khẩn cấp cho tòa nhà thông minh. Đây là mô phỏng huấn luyện." | Dashboard 3D tòa nhà AEGIS TOWER 10 tầng xoay nhẹ; badge SIMULATION | Tòa nhà thật: kết cấu, kính, nội thất, mái HVAC, cảnh quan, ánh sáng |
| 0:45–2:00 | Fleet kết nối | "100 thiết bị mô phỏng kết nối TLS, xác thực, heartbeat." | Terminal chạy simulator `--agents 100`; dashboard twin đổi xanh | Marker thiết bị online; HUD connected=100; SUMMARY connects |
| 2:00–4:00 | Fire drill CRITICAL | "Operator phát cháy tầng 5, mức CRITICAL, phạm vi tầng 4–6." | Operator publish; zone F05 đổi đỏ pulse; client popup + beep; ACK_RECEIVED→ACK_USER | Zone đỏ nhấp nháy, beacon sự cố, popup client, funnel SENT→RECEIVED→ACK |
| 4:00–6:00 | Chaos retry | "Bật fault injection: mất ACK. Server retry 3s/6s/12s cùng deliveryId." | Chaos tab chọn lossy; trace retry attempt 1/2/3; duplicate suppression | Retry trace, messageId mới mỗi attempt, không popup trùng |
| 6:00–7:30 | Crash recovery | "Server chết giữa delivery. Restart khôi phục từ SQLite." | Kill server giữa chừng; restart; dashboard reconnect; alert active sync lại | Recovery scanner log; delivery dở được giao lại; không mất alert |
| 7:30–8:30 | RBAC deny | "Floor manager cố phát ngoài phạm vi → bị chặn + audit." | Đăng nhập floor7; publish building khác; FORBIDDEN; audit tab hiện denied | Audit row ACCESS_DENIED, actor/scope rõ |
| 8:30–10:00 | Multicast lab | "So sánh multicast hint vs TCP fanout: giảm ~87.5% gói, ~97.8% byte." | Network-lab bench; bảng so sánh; hint không tin cậy → fetch TLS | Bảng metric multicast vs TCP; nhãn hint advisory |
| 10:00–11:30 | Benchmark + báo cáo | "Đo p50/p95/p99, ACK rate, retry trên 100 client. Số liệu thật kèm runId/commit." | Mở docs/evidence/benchmark summary.json; biểu đồ percentile | runId, commit SHA, máy đo, p50/p95/p99 thật |
| 11:30–12:00 | Kết | "Hệ thống chứng minh truyền tin tin cậy, chịu lỗi, phân quyền, đo lường trung thực." | Dashboard overview; recap 9 bài CMP180 | Tổng kết + giới hạn demo |

## Cách quay từng cảnh
- Dashboard: OBS window capture trình duyệt; dùng phím 1–7 chuyển tab (Twin · Incidents · Chaos · Reports · Funnel · Operator · Admin); camera 3D fly-to-floor/cutaway/explode cho cảnh tầng.
- Terminal: OBS window capture terminal; gõ lệnh thật (không giả output).
- Client: OBS window capture JavaFX popup.
- Voiceover: thu riêng hoặc live; giữ nhãn SIMULATION trong lời dẫn.

## Fallback (nếu live lỗi)
- Mạng chặn multicast → dùng số đo loopback đã ghi + giải thích caveat, hoặc 2 máy LAN thật.
- Server crash khó kiểm soát → quay sẵn cảnh recovery bằng script deterministic (seed cố định).
- JavaFX headless → simulator + dashboard thay client GUI.
- Luôn có video quay sẵn toàn bộ 12 phút để nộp nếu demo live thất bại.

## Hậu kỳ
- Cắt theo storyboard, thêm caption tiếng Việt, highlight số liệu thật, giữ banner SIMULATION.
- Xuất MP4 ≤1080p, nộp kèm `docs/evidence/` (log/JSON thật) để giảng viên đối chiếu.
