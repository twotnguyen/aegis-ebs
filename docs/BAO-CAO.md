# BÁO CÁO ĐỒ ÁN HỌC PHẦN — CMP180 Lập trình mạng máy tính
## HỆ THỐNG THÔNG BÁO KHẨN CẤP CHO TÒA NHÀ VĂN PHÒNG (AEGIS — EMERGENCY BROADCAST SYSTEM)
Giảng viên hướng dẫn: ThS. Nguyễn Văn Cẩn · Nhóm: [TODO] · Thành viên/MSSV: [TODO] · Lớp: [TODO] · Năm học 2025–2026
> SIMULATION/TRAINING — không thay thế hệ thống báo cháy/sơ tán chứng nhận.

## LỜI CAM ĐOAN [TODO ký tên]
## MỤC LỤC · DANH MỤC KÝ HIỆU/VIẾT TẮT · DANH MỤC BẢNG · DANH MỤC HÌNH [TODO tự động trong Word]

## CHƯƠNG 1. TỔNG QUAN
1.1 Giới thiệu đề tài · 1.2 Nhiệm vụ (tính cấp thiết, ý nghĩa khoa học/thực tiễn, mục tiêu, đối tượng/phạm vi) · 1.3 Cấu trúc đồ án.
- Mục tiêu: server phát cảnh báo khẩn cấp tới nhiều client LAN; ưu tiên; ACK 2 tầng; retry idempotent; reconnect; crash recovery; RBAC; audit; multicast lab; đo lường tin cậy.
- Phạm vi: 1 server LAN, client JavaFX + simulator headless, dashboard web 3D; giới hạn single-node, không HA.

## CHƯƠNG 2. CƠ SỞ LÝ THUYẾT
2.1 Client-Server · 2.2 TCP Socket Java (Socket/ServerSocket) · 2.3 Thread/ExecutorService · 2.4 InetAddress · 2.5 Truyền đối tượng/JSON qua luồng mạng (framing length-prefix) · 2.6 UDP DatagramSocket · 2.7 MulticastSocket · 2.8 TLS (SSLSocket/SSLServerSocket) · 2.9 RBAC + bảo mật · 2.10 CAP 1.2 (tham khảo).
[TODO: trích giáo trình CMP180 Bài 1–9 + nguồn ngoại bộ OWASP/OASIS/RFC 8085]

## CHƯƠNG 3. KẾT QUẢ THỰC NGHIỆM
3.1 Yêu cầu hệ thống (FR/NFR) · 3.2 Phân tích & thiết kế (kiến trúc 4 plane, sơ đồ module, thiết kế lớp, ERD 26 bảng, giao thức EBSP/2.0, state machine) · 3.3 Cài đặt (môi trường JDK24/Maven/Node, cấu trúc thư mục, mã nguồn tiêu biểu: FrameCodec, TransactionalPublisher, AckLedger/RetryPolicy, TLS gateway, multicast) · 3.4 Hướng dẫn biên dịch/chạy · 3.5 Kiểm thử & kết quả THẬT (bảng test pass, chaos C01–C16, p50/p95/p99, tỷ lệ ACK, ảnh dashboard 3D) · 3.6 Ưu điểm/hạn chế.
[TODO: điền số liệu đo thật + runId/commit SHA/máy đo; KHÔNG bịa số]

## CHƯƠNG 4. KẾT LUẬN VÀ KIẾN NGHỊ
[TODO: kết quả đạt được so mục tiêu; hạn chế (single-node, multicast LAN, TLS self-signed); hướng phát triển (HA, CAP export chuẩn, PostgreSQL)]

## TÀI LIỆU THAM KHẢO
[1] CMP180.md/pdf (HUTECH). [2] OASIS CAP 1.2. [3] Oracle SSLSocket/ExecutorService. [4] SQLite WAL. [5] OWASP Authorization/Session/Password Storage. [6] RFC 8085 UDP Guidelines. [7] Three.js.
## PHỤ LỤC: MÃ NGUỒN CHƯƠNG TRÌNH [TODO trích module chính]
