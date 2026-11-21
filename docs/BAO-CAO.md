# BÁO CÁO ĐỒ ÁN HỌC PHẦN — CMP180 Lập trình mạng máy tính
## HỆ THỐNG THÔNG BÁO KHẨN CẤP CHO TÒA NHÀ VĂN PHÒNG (AEGIS — EMERGENCY BROADCAST SYSTEM)
Giảng viên hướng dẫn: ThS. Nguyễn Văn Cẩn · Nhóm: [TODO] · Thành viên/MSSV: [TODO] · Lớp: [TODO] · Năm học 2025–2026
> SIMULATION/TRAINING — không thay thế hệ thống báo cháy/sơ tán chứng nhận.

## LỜI CAM ĐOAN

Nhóm chúng tôi xin cam đoan đồ án “Hệ thống thông báo khẩn cấp cho tòa nhà văn phòng (AEGIS — Emergency Broadcast System)” là công trình do chính nhóm thực hiện dưới sự hướng dẫn của ThS. Nguyễn Văn Cẩn. Phần cơ sở lý thuyết được tổng hợp từ giáo trình học phần CMP180 [1] và các tài liệu tham khảo được trích dẫn đầy đủ; mã nguồn hệ thống do nhóm tự xây dựng và kiểm thử. Số liệu thực nghiệm trình bày ở Chương 3 được đo đạc trực tiếp trên hệ thống thật, ghi nhận trung thực kèm điều kiện đo (runId, phiên bản mã nguồn, cấu hình máy); tuyệt đối không sao chép hoặc bịa đặt số liệu. Mọi kịch bản vận hành đều được gắn nhãn SIMULATION/TRAINING và không nhằm thay thế các hệ thống báo cháy, sơ tán đã được chứng nhận. Nhóm xin chịu hoàn toàn trách nhiệm về nội dung của đồ án.

[TODO: ký tên nhóm trưởng và các thành viên; ghi rõ họ tên, MSSV]

## MỤC LỤC

[TODO: tạo mục lục tự động trong Word (References → Table of Contents) sau khi hoàn tất Chương 3–4 và đánh số trang]

Khung mục lục tĩnh của báo cáo:

- LỜI CAM ĐOAN
- DANH MỤC KÝ HIỆU VÀ CHỮ VIẾT TẮT · DANH MỤC BẢNG · DANH MỤC HÌNH
- CHƯƠNG 1. TỔNG QUAN
  - 1.1 Giới thiệu đề tài
  - 1.2 Nhiệm vụ của đồ án (1.2.1 Tính cấp thiết; 1.2.2 Ý nghĩa khoa học và thực tiễn; 1.2.3 Mục tiêu; 1.2.4 Đối tượng và phạm vi)
  - 1.3 Cấu trúc của đồ án
- CHƯƠNG 2. CƠ SỞ LÝ THUYẾT
  - 2.1 Mô hình Client–Server
  - 2.2 Giao thức TCP và lập trình Socket Java (Socket/ServerSocket)
  - 2.3 Đa tuyến: Thread, Runnable, đồng bộ hóa và ExecutorService
  - 2.4 InetAddress và quản lý địa chỉ kết nối mạng
  - 2.5 Truyền JSON và đối tượng qua luồng mạng; framing length-prefix
  - 2.6 UDP và lập trình DatagramSocket
  - 2.7 Multicast và lập trình MulticastSocket
  - 2.8 TLS với SSLSocket/SSLServerSocket
  - 2.9 RBAC và bảo mật ứng dụng
  - 2.10 Chuẩn CAP 1.2 (tham khảo)
- CHƯƠNG 3. KẾT QUẢ THỰC NGHIỆM [TODO]
- CHƯƠNG 4. KẾT LUẬN VÀ KIẾN NGHỊ [TODO]
- TÀI LIỆU THAM KHẢO
- PHỤ LỤC: MÃ NGUỒN CHƯƠNG TRÌNH [TODO]

## DANH MỤC KÝ HIỆU VÀ CHỮ VIẾT TẮT

| Viết tắt | Tiếng Anh | Diễn giải |
|---|---|---|
| ACK | Acknowledgement | Thông điệp xác nhận đã nhận/đã đọc cảnh báo |
| AEGIS | — | Tên hệ thống thông báo khẩn cấp của đồ án |
| API | Application Programming Interface | Giao diện lập trình ứng dụng |
| CAP | Common Alerting Protocol | Chuẩn trao đổi cảnh báo khẩn cấp của OASIS [2] |
| CSDL | — | Cơ sở dữ liệu |
| CPU | Central Processing Unit | Bộ xử lý trung tâm |
| DNS | Domain Name System | Hệ thống tên miền [1, Bài 1] |
| EBSP | Emergency Broadcast System Protocol | Giao thức ứng dụng của đồ án (phiên bản 2.0) |
| EBS | Emergency Broadcast System | Hệ thống thông báo khẩn cấp |
| FIFO | First In, First Out | Vào trước ra trước |
| FR/NFR | Functional/Non-Functional Requirement | Yêu cầu chức năng/phi chức năng |
| GUI | Graphical User Interface | Giao diện đồ họa người dùng |
| HA | High Availability | Tính sẵn sàng cao (kiến trúc nhiều nút dự phòng) |
| HTTP | HyperText Transfer Protocol | Giao thức truyền siêu văn bản |
| IANA | Internet Assigned Numbers Authority | Tổ chức cấp phát số hiệu cổng/địa chỉ Internet |
| ICMP | Internet Control Message Protocol | Giao thức thông điệp điều khiển Internet |
| IGMP | Internet Group Management Protocol | Giao thức quản lý nhóm multicast [1, Bài 8] |
| IP | Internet Protocol | Giao thức liên mạng |
| JSON | JavaScript Object Notation | Định dạng dữ liệu văn bản nhẹ, nền tảng của EBSP/2.0 |
| JSSE | Java Secure Socket Extension | Thư viện TLS/SSL của Java [3] |
| JVM | Java Virtual Machine | Máy ảo Java |
| LAN | Local Area Network | Mạng cục bộ [1, Bài 1] |
| MAC | Media Access Control | Địa chỉ vật lý tầng liên kết dữ liệu |
| MSSV | — | Mã số sinh viên |
| MTU | Maximum Transmission Unit | Đơn vị truyền tối đa của tầng liên kết (Ethernet: 1500 byte) [1, Bài 7] |
| OSI | Open Systems Interconnection | Mô hình tham chiếu phân tầng 7 lớp [1, Bài 1] |
| OWASP | Open Worldwide Application Security Project | Tổ chức bảo mật ứng dụng nguồn mở [5] |
| PIM | Protocol Independent Multicast | Giao thức định tuyến multicast [1, Bài 8] |
| PKCS | Public-Key Cryptography Standards | Bộ chuẩn mật mã khóa công khai (PKCS12: định dạng keystore) |
| RBAC | Role-Based Access Control | Kiểm soát truy cập dựa trên vai trò [5] |
| RFC | Request for Comments | Tài liệu chuẩn của IETF |
| RMI | Remote Method Invocation | Triệu gọi phương thức từ xa trong Java [1, Bài 9] |
| RPC | Remote Procedure Call | Gọi thủ tục từ xa |
| SAN | Subject Alternative Name | Trường mở rộng tên thay thế trong chứng chỉ X.509 |
| SQL | Structured Query Language | Ngôn ngữ truy vấn CSDL quan hệ |
| SSL | Secure Sockets Layer | Giao thức bảo mật tầng giao vận (tiền thân của TLS) |
| TCP | Transmission Control Protocol | Giao thức điều khiển truyền vận, hướng liên kết, tin cậy [1, Bài 1] |
| TLS | Transport Layer Security | Giao thức bảo mật kênh truyền [3] |
| TTL | Time To Live | Giới hạn số router mà gói tin được đi qua [1, Bài 7] |
| UDP | User Datagram Protocol | Giao thức datagram phi liên kết [1, Bài 1, Bài 7] |
| UTF-8 | Unicode Transformation Format 8-bit | Bảng mã Unicode độ dài biến thiên, bắt buộc trong EBSP/2.0 |
| UUID | Universally Unique Identifier | Định danh duy nhất toàn cục (khóa bản ghi của hệ thống) |
| VLAN | Virtual LAN | Mạng cục bộ ảo |
| WAL | Write-Ahead Logging | Cơ chế ghi nhật ký trước của SQLite [4] |
| WAN | Wide Area Network | Mạng diện rộng |
| WS | WebSocket | Giao thức kênh truyền hai chiều qua HTTP (cầu nối dashboard) |
| XML | eXtensible Markup Language | Ngôn ngữ đánh dấu mở rộng (định dạng gốc của CAP) |

## DANH MỤC BẢNG

[TODO: số trang tự động trong Word; bổ sung bảng của Chương 3 khi có số liệu thật]

- Bảng 2.1. So sánh hai giao thức vận chuyển TCP và UDP
- Bảng 2.2. Phân dải số hiệu cổng của TCP/UDP
- Bảng 2.3. Ma trận vai trò – quyền phát cảnh báo của AEGIS-EBS
- Bảng 2.4. Ánh xạ các trường chính của CAP 1.2 sang mô hình cảnh báo AEGIS-EBS
- Bảng 2.5. Ánh xạ nội dung giáo trình CMP180 vào các mục lý thuyết và module của hệ thống

## DANH MỤC HÌNH

[TODO: số trang tự động trong Word; bổ sung ảnh dashboard 3D và biểu đồ đo của Chương 3]

- Hình 2.1. Mô hình hỏi–đáp giữa client và server (vẽ lại theo [1, Hình 5.1])
- Hình 2.2. Cấu trúc khung truyền EBSP/2.0 với tiền tố độ dài 4 byte
- Hình 2.3. Cây phân phối multicast: nhân bản gói tin trong mạng (theo [1, Hình 8.1])

## CHƯƠNG 1. TỔNG QUAN

### 1.1 Giới thiệu đề tài

Ứng dụng mạng là thành phần không thể thiếu của mạng máy tính: chúng cho phép người dùng khai thác thông tin lưu trữ trên các máy khác, trao đổi dữ liệu và huy động nhiều máy cùng giải quyết một bài toán [1, Bài 1]. Phần lớn các ứng dụng mạng hiện nay được tổ chức theo mô hình khách/chủ (client–server), trong đó máy chủ quản lý dữ liệu, cung cấp dịch vụ và máy khách chủ động thiết lập giao tiếp để khai thác dịch vụ đó [1, Bài 1, Bài 5]. Trên nền tảng Java, các ứng dụng này được xây dựng thông qua giao diện Socket của thư viện `java.net` (`Socket`, `ServerSocket`, `DatagramSocket`, `MulticastSocket`) cùng hệ thống luồng nhập/xuất `java.io` [1, Bài 1, Bài 2, Bài 5].

Trong các tòa nhà văn phòng, nhu cầu truyền thông tin khẩn cấp — chẳng hạn hướng dẫn sơ tán khi có cháy, cảnh báo sự cố an ninh, hoặc thông báo diễn tập — tới đúng người, đúng khu vực và trong thời gian ngắn nhất là một bài toán thực tế quan trọng. Bên cạnh các hệ thống báo cháy và phát thanh chuyên dụng đã được chứng nhận, hạ tầng mạng LAN sẵn có của tòa nhà mở ra khả năng xây dựng một kênh thông báo khẩn cấp bằng phần mềm: một máy chủ trung tâm tiếp nhận lệnh phát cảnh báo từ người có thẩm quyền, phân phối tới hàng loạt máy trạm (client) đặt tại các tầng/khu vực, đồng thời theo dõi trạng thái nhận tin của từng máy trạm và hiển thị bức tranh toàn cảnh cho người vận hành.

Đồ án học phần CMP180 “Lập trình mạng máy tính” xây dựng hệ thống **AEGIS-EBS (Emergency Broadcast System)** theo hướng tiếp cận trên. Hệ thống là một ứng dụng client–server hoàn chỉnh bằng Java, vận dụng tổng hợp kiến thức của chín bài trong giáo trình [1]: mô hình phân tầng OSI/TCP-IP và các giao thức vận chuyển (Bài 1); luồng nhập/xuất (Bài 2); lập trình đa tuyến (Bài 3); quản lý địa chỉ kết nối với `InetAddress`/`URL` (Bài 4); lập trình socket TCP với `Socket`/`ServerSocket` (Bài 5); kỹ thuật server đa client và tuần tự hóa đối tượng (Bài 6); lập trình UDP với `DatagramSocket` (Bài 7); lập trình multicast với `MulticastSocket` (Bài 8); và mô hình phân tán đối tượng RMI (Bài 9, được nghiên cứu để đối sánh và lựa chọn kiến trúc). Trên nền các kỹ thuật đó, đồ án bổ sung những nội dung thực hành hiện đại không có trong giáo trình như bảo mật kênh truyền TLS [3], kiểm soát truy cập theo vai trò RBAC và bảo mật ứng dụng [5], hướng dẫn sử dụng UDP an toàn theo RFC 8085 [6], lưu trữ bền vững với SQLite WAL [4], và tham khảo chuẩn cảnh báo CAP 1.2 của OASIS [2].

Về mặt tổ chức, hệ thống gồm: máy chủ trung tâm (cổng TLS 9443) quản lý cảnh báo, thiết bị, người dùng và phân phối tin; ứng dụng client JavaFX cho máy trạm; bộ mô phỏng client headless phục vụ kiểm thử tải; phòng thí nghiệm mạng (`aegis-network-lab`) minh họa UDP discovery và multicast hint; và dashboard web “digital twin” dựng tòa nhà 3D bằng Three.js [7], nhận trạng thái thời gian thực qua WebSocket (cổng 9444). Các thành phần giao tiếp bằng giao thức ứng dụng riêng **EBSP/2.0** — khung truyền JSON mã hóa UTF-8 chặt với tiền tố độ dài 4 byte — được đặc tả trong `docs/PROTOCOL.md`.

**Lưu ý phạm vi sử dụng:** AEGIS-EBS là hệ thống **SIMULATION/TRAINING** phục vụ học tập và diễn tập; không thay thế hệ thống báo cháy, cảnh báo sơ tán hay bất kỳ thiết bị an toàn nào đã được chứng nhận theo quy định.

### 1.2 Nhiệm vụ của đồ án

#### 1.2.1 Tính cấp thiết

Thông báo khẩn cấp trong tòa nhà có đặc thù khác với truyền tin thông thường: giá trị của thông điệp gắn chặt với thời điểm và độ tin cậy của việc phân phối. Một cảnh báo đến chậm, đến thiếu đối tượng, bị hiển thị trùng lặp, hoặc bị kẻ xấu giả mạo đều có thể gây hậu quả nghiêm trọng — từ hoang mang, mất niềm tin vào hệ thống, cho đến cản trở sơ tán. Trong khi đó, các cách tiếp cận ngây thơ thường gặp trong đồ án lập trình mạng bộc lộ nhiều hạn chế:

- **Phát quảng bá bằng UDP thuần túy** không đảm bảo gói tin đến đích, không đảm bảo thứ tự và có thể nhân bản thông điệp [1, Bài 7]; phía gửi không có cách nào biết máy trạm đã nhận tin hay chưa.
- **Kênh không xác thực** cho phép bất kỳ máy nào trong LAN giả mạo nguồn phát cảnh báo (UDP spoofing) hoặc nghe lén, sửa đổi nội dung; đây là lỗ hổng đã được OWASP cảnh báo đối với các hệ thống thiếu kiểm soát truy cập và bảo vệ kênh truyền [5].
- **Server đơn tuyến, không trạng thái bền vững** chỉ phục vụ được một client tại một thời điểm [1, Bài 6], và sẽ mất toàn bộ trạng thái phân phối khi tiến trình gặp sự cố (crash) hoặc máy khởi động lại.
- **Thiếu cơ chế chống trùng và chống hiển thị lại tin cũ** khiến client nhận lặp cảnh báo sau mỗi lần thử lại hoặc sau khi kết nối lại, gây nhiễu loạn thông tin trong tình huống khẩn cấp.

Vì vậy, cần một hệ thống được thiết kế bài bản ngay từ tầng giao thức: kênh truyền chính tin cậy có bảo mật (TCP/TLS), cơ chế xác nhận hai tầng phân biệt “máy đã nhận” với “người đã đọc”, thử lại lũy thừa có jitter và idempotent để không tạo bản trùng, đồng bộ trạng thái khi client kết nối lại, khôi phục sau sự cố từ CSDL bền vững, phân quyền phát tin theo vai trò và phạm vi tầng/khu vực, cùng nhật ký kiểm toán. Đồng thời, về mặt đào tạo, học phần CMP180 yêu cầu sinh viên không chỉ viết được các ví dụ socket rời rạc mà phải phân tích, thiết kế giao thức và tích hợp các kỹ thuật của Bài 1–8 thành một ứng dụng mạng hoàn chỉnh có kiểm chứng [1]. Một đồ án đáp ứng đồng thời hai yêu cầu trên là cần thiết.

#### 1.2.2 Ý nghĩa khoa học và thực tiễn

**Ý nghĩa khoa học.** Đồ án hệ thống hóa toàn bộ kiến thức lập trình mạng Java của giáo trình [1] thành một kiến trúc ứng dụng thống nhất, trong đó mỗi kỹ thuật (luồng I/O, đa tuyến, socket TCP/UDP, multicast, địa chỉ mạng) giữ một vai trò xác định. Trên nền đó, đồ án giải quyết các bài toán kinh điển của hệ phân tán ở quy mô LAN: đóng khung thông điệp trên dòng byte (framing length-prefix), định danh và chống trùng (messageId/requestId/deliveryId), máy trạng thái phân phối chỉ tiến (ACK only advances state), lập lịch ưu tiên có chống bỏ đói (starvation), và công bố nguyên tử gắn với khôi phục sau sự cố (transactional publish + recovery trên SQLite WAL [4]). Đồ án cũng xây dựng khung thực nghiệm định lượng có thể lặp lại: ma trận 30 bài kiểm thử chấp nhận (T-01…T-30) và 16 kịch bản gây lỗi có chủ đích (chaos C01…C16, seed xác định) được ánh xạ tới từng module trong `docs/TEST-PLAN.md`, làm cơ sở đo độ trễ phân phối (p50/p95/p99), tỷ lệ ACK và hành vi hệ thống dưới lỗi.

**Ý nghĩa thực tiễn.** Sản phẩm là một hệ thống mô phỏng/đào tạo trực quan: dashboard 3D phản ánh trạng thái từng tầng/khu vực theo thời gian thực [7], cho phép người học quan sát trọn vẹn vòng đời một cảnh báo — từ lúc người có thẩm quyền phát lệnh, phân phối tới client, xác nhận, thử lại khi mất ACK, cho tới đồng bộ lại sau mất kết nối và khôi phục sau khi server khởi động lại. Kiến trúc “hint qua multicast/UDP, nội dung xác thực qua TLS” của phòng thí nghiệm mạng là một mẫu hình tham khảo có giá trị cho các ứng dụng LAN một-nhiều, nơi phải đồng thời bảo đảm tốc độ lan truyền và tính xác thực. Toàn bộ giới hạn của hệ thống (một nút, chứng chỉ tự ký, multicast phạm vi LAN) được nêu trung thực, kèm định hướng phát triển, giúp kết quả đồ án có thể tái sử dụng làm nền tảng học tập và nghiên cứu tiếp.

#### 1.2.3 Mục tiêu của đồ án

**Mục tiêu tổng quát:** thiết kế và cài đặt hệ thống AEGIS-EBS theo mô hình client–server, trong đó một server trong LAN phát cảnh báo khẩn cấp tới nhiều client một cách tin cậy, có ưu tiên, có xác thực và phân quyền, có khả năng chịu lỗi và khôi phục; đồng thời đo lường định lượng độ tin cậy của quá trình phân phối.

**Mục tiêu cụ thể:**

1. Xây dựng giao thức ứng dụng EBSP/2.0 trên TCP/TLS: khung length-prefix (uint32 big-endian, độ dài 1…65536 byte), thân JSON UTF-8 chặt, phong bì `{version, type, messageId, correlationId, sentAt, payload}` và danh mục 28 loại thông điệp; kiểm tra chặt đầu vào, từ chối khung quá cỡ trước khi cấp phát bộ nhớ.
2. Phân phối một-nhiều tới các client theo mục tiêu (tòa nhà/tầng/zone) với bốn mức ưu tiên INFO < WARNING < EMERGENCY < CRITICAL; lập lịch ưu tiên nghiêm ngặt kèm luật công bằng chống bỏ đói luồng mức thấp.
3. Hiện thực ACK hai tầng: `ACK_RECEIVED` (máy trạm đã nhận và lưu bền vững) tách biệt với `ACK_USER` (người dùng đã xác nhận đọc); trạng thái phân phối chỉ tiến, không lùi.
4. Thử lại idempotent: mỗi lần thử mang messageId mới nhưng deliveryId/alertId/revision ổn định; lịch thử lại 3s/6s/12s cộng jitter, tối đa 3 lần thử lại rồi chuyển FAILED; client khử trùng để không hiển thị lặp.
5. Kết nối lại và đồng bộ: heartbeat chu kỳ 10s, đánh dấu suspect sau 3 lần lỡ; client reconnect thực hiện SYNC để nhận cảnh báo đang hoạt động; bản tin hủy/hết hạn dùng tombstone chống hiển thị lại tin cũ.
6. Khôi phục sau sự cố: công bố cảnh báo nguyên tử trong một giao dịch SQLite (WAL), quét khôi phục các delivery đang PENDING/SENT/RETRY_SCHEDULED/FAILED sau khi server khởi động lại; không tuyên bố “exactly-once”.
7. Bảo mật và phân quyền: xác thực người dùng/thiết bị qua TLS; RBAC năm vai trò với phạm vi phát tin theo tòa nhà/tầng; mật khẩu băm Argon2id; nhật ký kiểm toán mọi thao tác quản trị và các lần từ chối.
8. Phòng thí nghiệm UDP/multicast: discovery tìm server bằng datagram UDP có nonce; phát `ALERT_HINT` qua nhóm multicast lớp D; chứng minh mẫu hình “hint chỉ là gợi ý — nội dung hiển thị phải được xác thực qua TLS”; đo so sánh fanout TCP với multicast và unicast UDP.
9. Đo lường tin cậy: chạy tải với 100 client mô phỏng và client thật; ghi nhận độ trễ phân phối p50/p95/p99, tỷ lệ ACK, số lần thử lại, thông lượng và tài nguyên; thực hiện 16 kịch bản chaos có seed và lưu bằng chứng.
10. Dashboard digital twin: dựng tòa nhà 10 tầng × 4 zone bằng Three.js [7], tô màu theo trạng thái thiết bị/phân phối, nhận `STATUS_SNAPSHOT`/`STATUS_DELTA` qua WebSocket và hiển thị chỉ số đo.

#### 1.2.4 Đối tượng và phạm vi nghiên cứu

**Đối tượng nghiên cứu:** (i) các kỹ thuật lập trình mạng Java trong giáo trình CMP180 [1] — mô hình client–server, luồng nhập/xuất, đa tuyến và đồng bộ, `InetAddress`, socket TCP (`Socket`/`ServerSocket`), server đa client và tuần tự hóa đối tượng, UDP (`DatagramPacket`/`DatagramSocket`), multicast (`MulticastSocket`), RMI (đối sánh); (ii) bài toán phân phối cảnh báo khẩn cấp tin cậy, có bảo mật và có thể kiểm chứng trong mạng LAN tòa nhà.

**Phạm vi triển khai:**

- **Kiến trúc:** một server trung tâm trong LAN (single-node), không cụm dự phòng, không HA; các client kết nối tới server qua TCP/TLS cổng 9443; dashboard nhận cầu nối WebSocket cổng 9444.
- **Thành phần:** client JavaFX (JavaFX 24) có hộp thư bền vững phía client; simulator headless tạo tải 100 client và chaos có seed; dashboard web (Node.js ≥ 20.19, Vite, Three.js [7]); phòng thí nghiệm mạng UDP/multicast.
- **Dữ liệu:** SQLite với 26 bảng (WAL, khóa ngoại bật, giao dịch BEGIN IMMEDIATE) [4]; migration idempotent; bản ghi định danh UUID, thời gian UTC ISO-8601.
- **Bảo mật:** TLS với chứng chỉ tự ký (RSA 2048, SAN localhost/127.0.0.1) và truststore ghim (pinning) phía client — phù hợp môi trường lab, không phải hạ tầng khóa công khai production; RBAC + audit trong phạm vi một tòa nhà.
- **Multicast:** nhóm lớp D dành cho quản trị 239.10.10.10, cổng 9998, TTL = 1 (chỉ trong liên kết LAN); discovery UDP cổng 9999; datagram ≤ 1024 byte để không phân mảnh IP; có phương án dự phòng unicast khi mạng không hỗ trợ multicast.
- **Môi trường phát triển:** JDK 24 (Maven `release=24`), Maven đa module, JUnit 5, Jackson 2.17, sqlite-jdbc 3.46, argon2-jvm; dashboard phát triển trên Node 26.

**Ngoài phạm vi:** thay thế hệ thống báo cháy/sơ tán chứng nhận; triển khai WAN/Internet và đi qua NAT/firewall phức tạp; kiến trúc nhiều server (HA, replication); ứng dụng di động và push notification thương mại; xuất bản tin CAP XML đầy đủ theo [2] (mới tham khảo metadata, định hướng phát triển ở Chương 4); đánh giá an toàn mật mã hình thức.

### 1.3 Cấu trúc của đồ án

Báo cáo được tổ chức thành bốn chương theo mẫu báo cáo môn học của HUTECH (MauBCMH):

- **Chương 1 — Tổng quan:** giới thiệu đề tài, tính cấp thiết, ý nghĩa khoa học/thực tiễn, mục tiêu, đối tượng và phạm vi nghiên cứu, cấu trúc báo cáo.
- **Chương 2 — Cơ sở lý thuyết:** trình bày nền tảng lý thuyết của hệ thống dựa trên giáo trình CMP180 [1] (Bài 1–9) và các nguồn bổ sung [2]–[7]: mô hình client–server; TCP và socket Java; đa tuyến và ExecutorService; InetAddress; truyền JSON/đối tượng qua mạng và framing length-prefix; UDP; multicast; TLS; RBAC và bảo mật; chuẩn CAP 1.2. Mỗi mục đều liên hệ trực tiếp với cách kỹ thuật đó được áp dụng trong AEGIS-EBS.
- **Chương 3 — Kết quả thực nghiệm:** yêu cầu hệ thống (FR/NFR), phân tích và thiết kế (kiến trúc, sơ đồ module, thiết kế lớp, ERD 26 bảng, giao thức EBSP/2.0, máy trạng thái), cài đặt, hướng dẫn biên dịch/chạy, kiểm thử và số liệu đo thật (bảng test, chaos C01–C16, p50/p95/p99, tỷ lệ ACK, ảnh dashboard), ưu điểm/hạn chế. [TODO — chờ số liệu thực nghiệm thật]
- **Chương 4 — Kết luận và kiến nghị:** đối chiếu kết quả với mục tiêu, tổng kết hạn chế và đề xuất hướng phát triển. [TODO]

Phần Tài liệu tham khảo liệt kê bảy nguồn [1]–[7] được trích dẫn trong toàn báo cáo; Phụ lục trích mã nguồn các module chính.

## CHƯƠNG 2. CƠ SỞ LÝ THUYẾT

Chương này trình bày cơ sở lý thuyết của hệ thống AEGIS-EBS. Nguồn chủ đạo là giáo trình CMP180 [1] gồm chín bài; các nội dung ngoài giáo trình (TLS, ExecutorService, RBAC, hướng dẫn UDP, CAP, SQLite WAL, Three.js) được tham khảo từ [2]–[7] và ghi chú rõ. Mỗi mục kết thúc bằng phần liên hệ với cách kỹ thuật được vận dụng trong hệ thống. Quy ước trích dẫn: [1, Bài n] chỉ bài n của giáo trình; các bảng/hình của giáo trình được dẫn theo số hiệu gốc.

### 2.1 Mô hình Client–Server

Theo [1, Bài 1], hầu hết chương trình mạng hiện nay hoạt động theo mô hình khách/chủ: chương trình server quản lý một lượng dữ liệu lớn và cung cấp dịch vụ; chương trình client khai thác dữ liệu/dịch vụ đó. Client chủ động thiết lập cuộc giao tiếp, còn server lắng nghe yêu cầu thiết lập từ client rồi phục vụ. Quá trình trao đổi gồm ba bước: truyền yêu cầu từ client tới server, server xử lý, truyền đáp ứng về client [1, Bài 5]. Mô hình hỏi–đáp này đồng bộ hóa hai thông điệp: sau khi gửi yêu cầu, client chuyển sang trạng thái chờ cho tới khi nhận đáp ứng (Hình 2.1, vẽ lại theo [1, Hình 5.1]).

```
Client                                    Server (đang lắng nghe)
  |-------------- Request (yêu cầu) --------->|
  |   [chờ]                        [xử lý yêu cầu]
  |<------------- Reply (đáp ứng) ------------|
```

**Hình 2.1.** Mô hình hỏi–đáp giữa client và server (vẽ lại theo [1, Hình 5.1]).

Server đơn tuyến chỉ phục vụ một liên kết tại một thời điểm; khi khối lượng xử lý lớn hoặc không biết trước thời điểm hoàn thành, mô hình này không chấp nhận được. Khắc phục bằng **server tương tranh (concurrent server)**: mỗi phiên client được giao một thread riêng xử lý, còn server tiếp tục lắng nghe các liên kết mới [1, Bài 6]. Đây là nền tảng để một server phục vụ đồng thời hàng trăm client.

Một mô hình phân tán khác được giáo trình trình bày là **RMI (Remote Method Invocation)** — cho phép đối tượng trên JVM này gọi phương thức của đối tượng trên JVM khác thông qua lớp trung gian stub/skeleton và trình đăng ký `rmiregistry` [1, Bài 9]. RMI che giấu chi tiết mạng, truyền tham số là đối tượng `Serializable` bằng tuần tự hóa (marshalling/unmarshalling) trên socket TCP. Tuy nhiên RMI chỉ hỗ trợ Java–Java, gắn chặt với cơ chế tuần tự hóa đối tượng của Java và khó kiểm soát tường minh khuôn dạng dữ liệu trên đường truyền. (Ghi chú thực hành hiện đại: công cụ `rmic` và skeleton tĩnh đã lỗi thời trong Java mới; mô hình stub/skeleton trong [1, Bài 9] là mô hình giáo khoa.) Vì AEGIS-EBS cần một hợp đồng truyền thông tường minh, kiểm soát chặt từng byte (framing, xác thực, phiên bản) và có thể kiểm thử độc lập, đồ án **không dùng RMI** mà chọn giao thức thông điệp EBSP/2.0 trên socket TCP — vẫn thuần túy client–server theo [1, Bài 5].

**Liên hệ với AEGIS-EBS.** Hệ thống gồm một server trung tâm và nhiều client (máy trạm JavaFX, client mô phỏng, dashboard). Client chủ động mở kết nối TLS tới server và xác thực (`HELLO`/`AUTH_DEVICE`/`AUTH_OPERATOR`); sau đó kết nối được giữ sống lâu dài để server **đẩy** cảnh báo (`ALERT_PUSH`) xuống client — phần mở rộng của mô hình hỏi–đáp cổ điển, nhưng vẫn đúng bản chất client–server vì client là bên khởi tạo liên kết và server là bên nắm thẩm quyền dữ liệu. Server áp dụng mô hình tương tranh [1, Bài 6]: mỗi kết nối được phục vụ bởi các thread riêng (đọc và ghi), cho phép phục vụ đồng thời 100 client mô phỏng cùng nhiều client thật. Dashboard đóng vai trò client quan sát, nhận cầu nối trạng thái `status/1` qua WebSocket.

### 2.2 Giao thức TCP và lập trình Socket Java

**Giao thức TCP.** TCP (Transmission Control Protocol) hoạt động ở tầng vận chuyển của mô hình OSI (tầng vận chuyển của TCP/IP), cung cấp dịch vụ truyền dữ liệu tin cậy, hướng liên kết giữa hai nút mạng [1, Bài 1]. TCP chia dữ liệu thành các đoạn, gắn header chứa cổng nguồn/đích, số thứ tự (Sequence Number), số xác nhận (Acknowledge Number), cửa sổ (Window), checksum và các cờ điều khiển [1, Bảng 1.1]. Nhờ số thứ tự và xác nhận, phía nhận sắp xếp lại dữ liệu đúng trình tự và phát hiện mất mát để yêu cầu truyền lại; trường Window cho phép điều khiển luồng — bên nhận thông báo lượng dữ liệu tối đa có thể nhận. Đổi lại, TCP có chi phí thiết lập/giải phóng liên kết và độ trễ truyền lại khi lỗi [1, Bài 5]. Header TCP tối thiểu 20 byte, so với 8 byte của UDP [1, Bài 7]. Bảng 2.1 tóm tắt khác biệt hai giao thức theo [1, Bảng 7.2].

**Bảng 2.1.** So sánh hai giao thức vận chuyển TCP và UDP (theo [1, Bảng 7.2], bổ sung kích thước header từ [1, Bài 7]).

| Đặc trưng | UDP | TCP |
|---|---|---|
| Hướng liên kết | Không | Có |
| Sử dụng phiên | Không | Có |
| Độ tin cậy (đảm bảo đến đích) | Không | Có |
| Xác nhận (ACK) | Không | Có |
| Đánh thứ tự | Không | Có |
| Điều khiển luồng | Không | Có |
| Kích thước header tối thiểu | 8 byte | 20 byte |
| Kiểu truyền hỗ trợ | unicast, broadcast, multicast | unicast |

**Số hiệu cổng.** TCP và UDP dùng cổng 16 bit (0…65535) để ánh xạ dữ liệu tới đúng tiến trình. Cổng chia ba dải: cổng hệ thống 0–1023 (dành cho tiến trình đặc quyền, các dịch vụ nổi tiếng), cổng người dùng/đã đăng ký 1024–49151 (ứng dụng server có thể chọn hoặc đăng ký với IANA), cổng động/riêng 49152–65535 (client thường được cấp phát tự động) [1, Bài 1] (Bảng 2.2).

**Bảng 2.2.** Phân dải số hiệu cổng của TCP/UDP [1, Bài 1].

| Dải cổng | Tên gọi | Mục đích điển hình |
|---|---|---|
| 0–1023 | Cổng hệ thống (well-known) | Dịch vụ chuẩn, cần đặc quyền để gán |
| 1024–49151 | Cổng người dùng (registered) | Ứng dụng server tự chọn/đăng ký |
| 49152–65535 | Cổng động/riêng (dynamic) | Cổng tạm phía client, chọn tự do |

**Khái niệm Socket.** Socket là trừu tượng hóa cơ chế kết nối giữa hai ứng dụng qua mạng, kết hợp địa chỉ IP và số hiệu cổng; khái niệm do Đại học UC Berkeley đưa ra, cho phép xem liên kết mạng như một luồng đọc/ghi được, che giấu chi tiết mức thấp (kích thước gói, truyền lại, định tuyến) [1, Bài 1, Bài 5]. Một socket hỗ trợ bảy thao tác cơ bản: kết nối tới máy ở xa; gửi dữ liệu; nhận dữ liệu; ngắt liên kết; gán cổng; nghe dữ liệu đến; chấp nhận liên kết từ xa trên cổng đã gán [1, Bài 5]. Với chồng TCP/IP có hai kiểu socket chính: **stream socket** (TCP, tin cậy) và **datagram socket** (UDP, đơn giản). Java hiện thực stream socket bằng `Socket`/`ServerSocket`, datagram socket bằng `DatagramPacket`/`DatagramSocket` [1, Bài 5].

**Lớp Socket (phía client và đầu mối giao tiếp).** Các hàm khởi tạo thông dụng: `Socket(String host, int port)` và `Socket(InetAddress host, int port)` — tạo socket TCP và kết nối ngay tới đích; dạng bốn tham số còn cho phép chọn giao tiếp mạng cục bộ và cổng cục bộ (localPort = 0 để hệ thống tự chọn) [1, Bảng 5.1]. Sau khi kết nối, `getInputStream()` và `getOutputStream()` trả về luồng nhập/xuất của liên kết song công (full-duplex): hai phía có thể gửi và nhận đồng thời; ý nghĩa dữ liệu do giao thức ứng dụng quy định [1, Bài 5]. Các phương thức thông tin: `getInetAddress()`, `getPort()` (đích), `getLocalAddress()`, `getLocalPort()` (cục bộ). Đóng liên kết bằng `close()`, hoặc đóng từng chiều bằng `shutdownInput()`/`shutdownOutput()`; không nên trông chờ hệ thống tự đóng socket, đặc biệt với chương trình chạy vô hạn [1, Bài 5]. Ba tùy chọn quan trọng [1, Bài 5.3.4]:

- `setTcpNoDelay(true)`: vô hiệu hóa thuật toán Nagle — gửi gói nhỏ ngay thay vì gom chờ xác nhận, giảm độ trễ cho thông điệp điều khiển khẩn cấp.
- `setSoLinger(on, seconds)`: quy định hành vi với dữ liệu chưa gửi khi `close()`; linger = 0 hủy phần chưa gửi, linger > 0 chờ gửi nốt trong thời hạn.
- `setSoTimeout(milliseconds)`: giới hạn thời gian phong tỏa của thao tác đọc; hết hạn sẽ ném ngoại lệ thay vì chờ vô hạn — cần cho các vòng đọc có kiểm soát.

**Lớp ServerSocket (phía server).** Vòng đời server gồm: tạo `ServerSocket` trên cổng xác định; gọi `accept()` (phong tỏa tới khi có client kết nối, trả về `Socket` của liên kết); lấy luồng nhập/xuất từ socket đó; tương tác theo giao thức tới khi ngắt liên kết; đóng socket phiên; quay lại `accept()` chờ liên kết kế tiếp [1, Bài 5.4]. Hàm khởi tạo `ServerSocket(int port)` (port = 0 để hệ thống chọn cổng ngẫu nhiên — không hữu ích cho server vì client cần biết trước cổng) và `ServerSocket(int port, int queueLength, InetAddress bindAddress)` — đặt chiều dài hàng đợi liên kết chờ và ràng buộc địa chỉ cục bộ trên máy nhiều IP [1, Bảng 5.3]. `IOException` phát sinh khi cổng đã bị chiếm hoặc không đủ quyền gán cổng 0–1023. Thông tin cổng đang nghe lấy bằng `getLocalPort()`.

**Liên hệ với AEGIS-EBS.** Kênh phân phối chính của hệ thống là stream socket TCP sống lâu dài trên cổng 9443 (bọc TLS, xem 2.8). Server lắng nghe bằng `SSLServerSocket` (lớp con của `ServerSocket`), mỗi liên kết được chấp nhận tạo thành một `ClientSession` với luồng đọc riêng và một thread ghi riêng; client JavaFX dùng `SSLSocket` (lớp con của `Socket`) kết nối tới host/cổng cấu hình. Vì TCP đảm bảo tin cậy và thứ tự ở tầng vận chuyển [1, Bài 1], tầng giao thức EBSP/2.0 không phải lo truyền lại đoạn hay sắp xếp gói, mà tập trung vào ranh giới thông điệp (2.5), ngữ nghĩa phân phối và chống trùng ở tầng ứng dụng. Các cổng dịch vụ của hệ thống (9443 TLS, 9444 WS, 9999 discovery UDP, 9998 multicast) đều thuộc dải cổng người dùng 1024–49151 theo Bảng 2.2.

### 2.3 Đa tuyến: Thread, Runnable, đồng bộ hóa và ExecutorService

**Đơn tiến trình và đa tiến trình.** Chương trình đơn tiến trình thực thi tuần tự: lệnh trước chưa xong thì lệnh sau chưa chạy. Chương trình đa tiến trình cho phép nhiều luồng điều khiển thực hiện “cùng lúc”: trên máy đa nhân là song song thật, trên đơn nhân là xoay vòng — hệ điều hành cấp mỗi tiến trình một CPU ảo và luân phiên phân phối thời gian xử lý [1, Bài 3]. Ứng dụng mạng bắt buộc phải đa tuyến: server phải đáp ứng nhiều client đồng thời, client vừa nhận dữ liệu mạng vừa cập nhật giao diện [1, Bài 3].

**Lớp Thread và giao diện Runnable.** Hai cách tạo thread [1, Bài 3]: (i) kế thừa `Thread` và override `run()`; (ii) hiện thực giao diện `Runnable` (chỉ có `void run()`) rồi liên kết với `Thread` qua constructor `Thread(Runnable target)` — dùng khi lớp đã kế thừa lớp khác. Kích hoạt bằng `start()` (JVM tạo thread mới và gọi `run()` trong đó); gọi trực tiếp `run()` chỉ chạy tuần tự trên thread hiện tại. Gọi `start()` lần hai trên cùng thread ném `IllegalThreadStateException`. Thread có 10 mức ưu tiên (1…10, mặc định 5, hằng `MIN_PRIORITY`/`MAX_PRIORITY`), chỉnh bằng `setPriority(int)` (giá trị ngoài khoảng ném `IllegalArgumentException`); cần lưu ý ưu tiên chỉ là gợi ý cho bộ lập lịch và phụ thuộc nền tảng. Dừng thread bằng `interrupt()` — nếu thread đang `sleep()` sẽ ném `InterruptedException`; các phương thức hỗ trợ gồm `sleep(ms)`, `yield()`, và bộ `wait()/notify()/notifyAll()` (kế thừa từ `Object`) để phối hợp chờ–đánh thức giữa các thread [1, Bài 3].

**Đồng bộ hóa.** Khi nhiều thread cùng truy cập vùng nhớ chia sẻ, lỗi tương tranh (race condition) xảy ra — ví dụ kinh điển trong [1, Bài 3.4]: nhiều thread cùng giảm biến đếm `x` không đồng bộ khiến `x` xuống âm. Từ khóa `synchronized` bảo đảm tại một thời điểm chỉ một thread thực thi phương thức hoặc khối lệnh trên cùng đối tượng khóa: `synchronized` trên phương thức, hoặc khối `synchronized(expr){...}` lấy khóa của đối tượng `expr`. Kết hợp `wait()/notifyAll()` xây dựng được các cấu trúc phối hợp như hàng đợi producer–consumer. Trao đổi dữ liệu giữa hai thread còn có thể dùng cặp luồng ống `PipedOutputStream`/`PipedInputStream` (nối bằng `connect()`); đọc khi ống rỗng sẽ phong tỏa tới khi có dữ liệu hoặc ống đóng [1, Bài 3.5].

**Server tương tranh và giới hạn của thread-per-connection.** [1, Bài 6] cài đặt server đa client bằng cách tạo một thread mới cho mỗi `Socket` chấp nhận được. Cách này đơn giản nhưng không giới hạn số thread: mỗi thread tốn bộ nhớ ngăn xếp và chi phí lập lịch; hàng nghìn kết nối đồng thời có thể làm kiệt tài nguyên. (Ghi chú thực hành hiện đại, ngoài giáo trình:) thực tế production dùng **thread pool** qua `ExecutorService` (`java.util.concurrent`) [3]: tập thread được tái sử dụng, tác vụ nộp qua `execute(Runnable)`/`submit(Callable)`, có `shutdown()`/`awaitTermination()` để dừng trật tự; `ScheduledExecutorService` hỗ trợ tác vụ định kỳ (heartbeat, retry). Kết hợp hàng đợi bị chặn (bounded queue) giữa các thread cho phép quan sát và xử lý quá tải thay vì phình bộ nhớ vô hạn.

**Liên hệ với AEGIS-EBS.** Mỗi `ClientSession` phía server có một thread ghi chạy qua `Executor` (`writerExecutor.execute(this::writerLoop)`), lấy khung từ `BoundedWriterQueue` (sức chứa mặc định 256) và ghi ra luồng xuất — bảo đảm các khung không đan xen nhau trên cùng socket. Hàng đợi này hiện thực chính sách client chậm tất định: đầy hàng đợi thì khung ưu tiên cao hơn được thay chỗ khung thấp nhất (EVICTED_LOWER); không thay được thì từ chối (REJECTED_SLOW) và ngắt kết nối client chậm — nhờ vậy lũ thông điệp INFO không bao giờ chặn khung điều khiển CRITICAL. Toàn cục, `PriorityScheduler` lập lịch bốn mức ưu tiên theo nguyên tắc ưu tiên nghiêm ngặt + công bằng: tối đa 8 lần phát liên tiếp (MAX_BURST) từ tầng cao nhất rồi nhường một lượt cho tầng thấp hơn còn hàng, xoay vòng để không tầng nào bị bỏ đói. Trạng thái chia sẻ được bảo vệ đúng kỹ thuật của [1, Bài 3]: `AckLedger` dùng phương thức `synchronized` (sổ ACK chỉ tiến), `ClientSession` dùng `volatile`/`AtomicBoolean` cho trạng thái phiên và đóng một lần; các vòng nhận UDP/multicast trong `aegis-network-lab` chạy trên thread daemon riêng, lỗi listener không được phép giết vòng nhận.

### 2.4 InetAddress và quản lý địa chỉ kết nối mạng

**Địa chỉ IP và DNS.** Mỗi nút mạng TCP/IP được định danh bằng địa chỉ IP (IPv4: 32 bit, dạng x.x.x.x; IPv6: 128 bit) gồm phần mạng và phần host; các lớp A/B/C dành cho unicast, lớp D (bắt đầu `1110`) dành cho multicast, dải riêng 10/8, 172.16/12, 192.168/16 dùng trong mạng nội bộ, 127.0.0.1 là loopback [1, Bài 1]. Vì địa chỉ số khó nhớ, hệ thống tên miền DNS ánh xạ tên (hostname) sang địa chỉ IP: máy khách hỏi DNS cục bộ, nếu không phân giải được sẽ hỏi ngược lên DNS gốc [1, Bài 1.2.5].

**Lớp InetAddress.** `java.net.InetAddress` biểu diễn địa chỉ Internet với hai thuộc tính cơ bản hostname và address. Lớp **không có constructor công khai**; thay vào đó có ba phương thức tĩnh tạo đối tượng [1, Bảng 4.1]:

- `getByName(String hostname)`: phân giải tên (hoặc chuỗi IP) thành một `InetAddress`;
- `getAllByName(String hostname)`: trả về tất cả địa chỉ của tên miền;
- `getLocalHost()`: địa chỉ của máy đang chạy chương trình.

Các phương thức truy xuất: `getHostName()` (trả tên, hoặc chuỗi IP nếu không có tên), `getAddress()` (mảng byte địa chỉ IP) [1, Bảng 4.2]. Nhóm kiểm tra tính chất địa chỉ [1, Bảng 4.3]: `isReachable(timeout)` (thăm dò bằng ICMP ECHO hoặc kết nối TCP tới cổng 7/Echo; lưu ý firewall có thể chặn), `isMulticastAddress()` (có thuộc lớp D không), `isLoopbackAddress()`, `isSiteLocalAddress()` (địa chỉ cục bộ — tiện cho định tuyến nội bộ).

**Lớp URL và URLConnection.** `URL` định vị tài nguyên qua giao thức/host/cổng/đường dẫn, với bốn constructor (ném `MalformedURLException` khi sai khuôn dạng); `openStream()` trả luồng nhập dữ liệu thô, `openConnection()` trả đối tượng `URLConnection` — liên kết mở cho phép cả đọc lẫn ghi tới tài nguyên [1, Bài 4.2–4.3]. Đây là nền tảng cho các giao tiếp mức ứng dụng chuẩn (HTTP/WS) của thành phần web.

**Liên hệ với AEGIS-EBS.** `MulticastConfig` của phòng thí nghiệm mạng phân giải nhóm bằng `InetAddress.getByName(group)` và **bắt buộc** `isMulticastAddress()` trả true — loại ngay địa chỉ không thuộc lớp D trước khi mở socket; cổng và TTL cũng được kiểm tra miền giá trị (1…65535, 0…255). Client cấu hình server bằng host/cổng; giao thức discovery UDP trả về `serverHost`/`tlsPort` để client chuyển sang kênh TLS đã ghim chứng chỉ (2.6). Dashboard kết nối bằng URL dạng `ws://host:9444` — đúng vai trò định vị tài nguyên của URL trong [1, Bài 4].

### 2.5 Truyền JSON và đối tượng qua luồng mạng; framing length-prefix

**Hệ thống luồng java.io.** Truyền/nhận dữ liệu mạng trong Java thực chất là đọc/ghi luồng [1, Bài 2]. Theo loại dữ liệu, luồng chia thành luồng byte (`InputStream`/`OutputStream`) và luồng ký tự (`Reader`/`Writer`); theo thao tác, chia thành nhập và xuất. `InputStream.read()` trả về -1 khi hết luồng; `read(byte[])` trả về số byte **thực sự** đọc được — có thể nhỏ hơn sức chứa bộ đệm, nên một lời gọi đọc không đảm bảo lấy đủ số byte yêu cầu [1, Bảng 2.2]. Luồng lọc (`FilterInputStream`/`FilterOutputStream` và các lớp con) bọc lên luồng cơ bản tạo thành dây chuyền xử lý, ví dụ `DataInputStream(BufferedInputStream(FileInputStream))` [1, Bài 2.4]; luồng đệm giảm số thao tác đọc/ghi vật lý nhưng tăng rủi ro mất dữ liệu chưa flush khi chương trình dừng đột ngột [1, Bài 2.5]. `DataInputStream`/`DataOutputStream` đọc/ghi giá trị định kiểu (int, boolean, UTF…); phải đọc đúng thứ tự đã ghi [1, Bảng 2.6]. Đặc biệt, `DataInputStream.readFully(byte[])` phong tỏa tới khi đọc **đủ** số byte yêu cầu (hoặc ném ngoại lệ nếu hết luồng giữa chừng) — công cụ then chốt để đọc khung trên dòng byte.

**Tuần tự hóa đối tượng.** [1, Bài 6] trình bày object serialization: biến đối tượng thành dãy byte để lưu trữ hoặc truyền đi; chỉ cần hiện thực `Serializable`, thuộc tính đánh dấu `transient` bị bỏ qua. `ObjectOutputStream.writeObject(obj)` ghi đối tượng, `ObjectInputStream.readObject()` phục hồi (ném `ClassNotFoundException` nếu thiếu lớp); hai luồng này phải bọc trên luồng byte của socket. Truyền đối tượng qua mạng nhờ đó rất gọn: `oos.writeObject(obj); oos.flush();` phía gửi và `(Kieu) ois.readObject();` phía nhận [1, Bài 6.2].

**Vì sao không dùng tuần tự hóa Java cho EBSP.** Tuần tự hóa Java gắn chặt hai phía với Java và với phiên bản lớp (`serialVersionUID`), khó kiểm soát khuôn dạng trên đường truyền, và giải tuần tự hóa dữ liệu không tin cậy là bề mặt tấn công đã được cảnh báo rộng rãi trong thực hành bảo mật [5]. AEGIS-EBS chọn **JSON UTF-8 chặt** (thư viện Jackson) làm thân thông điệp: văn bản đọc được, độc lập ngôn ngữ, dễ kiểm thử bằng fixture, dễ validate từng trường. Đổi lại, phải tự giải quyết bài toán ranh giới thông điệp.

**Bài toán framing trên dòng byte TCP.** TCP cung cấp dòng byte tin cậy, có thứ tự nhưng **không có ranh giới thông điệp**: một thông điệp có thể bị tách thành nhiều lần đọc (fragmentation), hoặc nhiều thông điệp đến dính trong một lần đọc (coalescing) [1, Bài 5] (bản chất dòng byte của stream socket). Giao thức ứng dụng vì thế phải tự đóng khung. Kỹ thuật **length-prefix** đặt trước mỗi thân thông điệp một trường độ dài cố định: phía đọc đọc đủ 4 byte tiền tố, diễn giải độ dài L, rồi đọc đúng L byte thân — không đọc thừa (để dành khung kế tiếp trong luồng), không đọc thiếu.

**Đặc tả khung EBSP/2.0.** Mỗi khung gồm: `uint32 big-endian L` (L ∈ 1…65536) + `L byte JSON UTF-8 chặt` (Hình 2.2). Phong bì: `{version: 2, type, messageId, correlationId?, sentAt (UTC ISO-8601), payload}`; `type` thuộc danh mục 28 loại thông điệp (`HELLO`, `AUTH_*`, `ALERT_CREATE/PUBLISH/UPDATE/CANCEL`, `ALERT_PUSH`, `ACK_RECEIVED`, `ACK_USER`, `SYNC_*`, `HEARTBEAT`, `STATUS_*`…).

```
 byte 0   byte 1   byte 2   byte 3   byte 4 ................ byte 4+L-1
+--------+--------+--------+--------+-----------------------------+
|     Độ dài L (uint32, big-endian) |   L byte JSON (UTF-8 chặt)    |
+--------+--------+--------+--------+-----------------------------+
 L ∈ [1, 65536]; JSON = {version:2, type, messageId, correlationId?, sentAt, payload}
```

**Hình 2.2.** Cấu trúc khung truyền EBSP/2.0 với tiền tố độ dài 4 byte.

Thứ tự kiểm tra khi nhận (cài đặt trong `FrameCodecImpl.readFrame`): (1) `readFully` 4 byte tiền tố — chịu được trường hợp header về làm nhiều mẩu nhỏ; (2) kiểm tra L ∈ 1…65536 và **từ chối khung quá cỡ trước khi cấp phát** bộ đệm thân (chống tấn công cạn kiệt bộ nhớ bằng độ dài giả); (3) `readFully` đúng L byte thân — xử lý đọc thiếu và không đụng khung đã dính kế tiếp; (4) giải mã UTF-8 ở chế độ REPORT (ném lỗi với byte dị dạng, không thay thế âm thầm); (5) phân tích JSON: phải là một object duy nhất, không token thừa sau object; (6) kiểm tra `version == 2`, `type` thuộc enum, các trường bắt buộc không rỗng, `payload` hiện diện. Mọi vi phạm ném `FrameException` và kết nối bị xử lý theo chính sách — không bao giờ “cố hiểu” dữ liệu lỗi.

**Định danh, idempotency và ACK hai tầng.** Trên nền khung tin cậy, ngữ nghĩa phân phối được thiết kế chống trùng: lệnh `ALERT_CREATE` mang `requestId` duy nhất (công bố trùng requestId bị rollback tất định); mỗi delivery có `deliveryId` + `alertId` + `revision` ổn định qua mọi lần thử; mỗi lần thử vận chuyển dùng `messageId` mới để phân biệt attempt. Client khử trùng theo bộ định danh này và lưu hộp thư bền vững, nên `ACK_RECEIVED` (máy đã nhận, đã lưu) tách biệt với `ACK_USER` (thao tác xác nhận của con người). ACK chỉ làm trạng thái **tiến lên** (PENDING → SENT/RETRY_SCHEDULED → RECEIVED → ACK/FAILED → EXPIRED/CANCELLED), không bao giờ lùi; hủy/hết hạn để lại tombstone ngăn hiển thị lại tin cũ sau SYNC.

**Bền vững và khôi phục.** Trạng thái phân phối được lưu trong SQLite 26 bảng với WAL [4]: mỗi lần mở kết nối bật `foreign_keys`, đặt `busy_timeout`; lệnh phát cảnh báo chạy trong một giao dịch BEGIN IMMEDIATE — ghi alert + revision + target snapshot + deliveries + operations (requestId UNIQUE, hash payload) rồi COMMIT; chỉ **sau commit** mới xếp hàng gửi. Nhờ đó server gặp sự cố giữa chừng không để lại trạng thái nửa vời, và `RecoveryScanner` xây dựng lại hàng đợi gửi từ các delivery chưa hoàn tất sau khi khởi động lại — hiện thực nguyên tắc “không tuyên bố exactly-once, chỉ at-least-once + khử trùng phía nhận”.

**Liên hệ với AEGIS-EBS.** Toàn bộ mục này là đặc tả trực tiếp của module `aegis-protocol` (`FrameCodec`, `FrameCodecImpl`, `Envelope`, `Protocol`, `MessageType`) và `aegis-persistence` (`TransactionalPublisher`, `RecoveryScanner`, `DeliveryRepo`), được kiểm thử bằng các bộ test framing fragment/coalesce/oversize/invalid (T-01…T-04) và idempotency/recovery (T-09, T-10, T-18, T-19) trong `docs/TEST-PLAN.md`.

### 2.6 UDP và lập trình DatagramSocket

**Đặc trưng giao thức UDP.** UDP (User Datagram Protocol) là giao thức tầng giao vận, phía trên IP, **phi liên kết** và best-effort [1, Bài 7]. Mỗi datagram là gói độc lập, tự chứa đủ thông tin định tuyến; UDP chỉ thêm header 8 byte (cổng nguồn, cổng đích, độ dài, checksum) rồi để IP chuyển đi [1, Bài 1, Bài 7]. UDP không bắt tay, không phiên, không đảm bảo đến đích, không đảm bảo thứ tự, không chống nhân bản, không điều khiển luồng [1, Bài 7.1.3]. Ưu điểm: không trễ thiết lập liên kết, tốc độ nhanh, header nhỏ, và hỗ trợ truyền một-nhiều (broadcast/multicast) — điều TCP không làm được vì TCP chỉ unicast và lượng ACK từ nhiều nút sẽ làm quá tải nguồn [1, Bài 1, Bài 7.1.4].

**MTU, TTL và kích thước datagram.** MTU là số byte dữ liệu tối đa một khung tầng liên kết truyền được (Ethernet: 1500 byte); datagram UDP lớn hơn MTU sẽ bị IP phân mảnh, và chỉ cần mất một mảnh là mất cả datagram [1, Bài 7.1.1]. Về lý thuyết, tải UDP tối đa của IPv4 là 65535 − 20 (header IP) − 8 (header UDP) = 65507 byte, nhưng thực hành tốt là giữ datagram nhỏ dưới MTU để tránh phân mảnh. TTL giới hạn số router gói được đi qua, ngăn vòng lặp định tuyến; mỗi router giảm TTL một đơn vị, về 0 thì gói bị loại [1, Bài 7.1.1]. Theo RFC 8085 [6], ứng dụng UDP phải tự đảm nhận các trách nhiệm mà TCP vốn lo: kiểm soát tắc nghẽn (không phát tràn tốc độ đường truyền), giới hạn kích thước thông điệp, bật checksum, và cân nhắc middlebox; trong môi trường kiểm soát (LAN lab) các yêu cầu có thể nhẹ hơn nhưng vẫn phải ý thức đầy đủ.

**Lớp DatagramPacket.** Biểu diễn một datagram [1, Bài 7.2]. Constructor phía nhận: `DatagramPacket(byte[] buf, int length)` và `DatagramPacket(byte[] buf, int offset, int length)` — dữ liệu nhận được chép vào bộ đệm, chiều dài vượt bộ đệm ném `IllegalArgumentException`. Constructor phía gửi: `DatagramPacket(byte[] buf, int length, InetAddress dest, int port)` (và biến thể offset/`SocketAddress`) — gói mang sẵn địa chỉ đích. Các phương thức chính: `getData()`, `getLength()`, `getAddress()`, `getPort()` (đọc thông tin gói nhận), `setAddress()`, `setPort()`, `setLength()` (tái sử dụng gói để gửi tiếp). Dữ liệu luôn là mảng byte; muốn xử lý như luồng, bọc `ByteArrayInputStream` trên `getData()` rồi nối `InputStreamReader`/`BufferedReader` [1, Bài 7.4.1].

**Lớp DatagramSocket.** Gắn với một cổng UDP cục bộ, dùng cho cả gửi lẫn nhận tới/nhiều host độc lập — không có khái niệm liên kết riêng như TCP [1, Bài 7.3]. `send(DatagramPacket)` gửi gói; `receive(DatagramPacket)` phong tỏa chờ gói đến và chép vào bộ đệm của gói; `setSoTimeout(ms)` đặt hạn phong tỏa cho `receive()` (hết hạn ném `SocketTimeoutException` — lưu ý giá trị 0 nghĩa là chờ vô hạn, không phải “không phong tỏa”); `close()` giải phóng cổng và đánh thức `receive()` đang chờ. Vòng nhận điển hình: tạo socket gán cổng → tạo gói rỗng với bộ đệm → lặp `receive()` → xử lý nội dung [1, Bài 7.4].

**Liên hệ với AEGIS-EBS.** Module `aegis-network-lab` dùng UDP cho **discovery**: client phát `DISCOVER_EBS {nonce, version}` tới cổng 9999; `DiscoveryServer` nhận, kiểm tra chặt bằng `LabDatagramCodec` (JSON object duy nhất, UTF-8 chặt, ≤ 1024 byte — dưới MTU Ethernet nên không phân mảnh), rồi trả `{serverHost, tlsPort, nonce, serverId}` lặp lại đúng nonce. Điểm mấu chốt về an toàn: **phúc đáp UDP là không tin cậy** — bất kỳ ai cũng có thể giả mạo (spoofing). Vì vậy `DiscoveryClient.discoverTrusted` chỉ chấp nhận server sau khi: bắt tay TLS với truststore đã ghim, trao đổi `HELLO`/`SERVER_HELLO` lặp nonce **trong phiên TLS đó**, và kiểm tra CN chứng chỉ trùng `serverId`. Datagram giả mạo cùng lắm gây một lần kết nối thử, không thể tiêm nội dung hay chuyển hướng client. Kịch bản tấn công này được kiểm thử bằng T-24/C13 (UDP forged). Ngoài ra, khi multicast không khả dụng, `FallbackHintBus` chuyển sang phát hint UDP unicast từng thuê bao — vẫn giữ nguyên tắc “hint không phải nội dung” (2.7).

### 2.7 Multicast và lập trình MulticastSocket

**Ba kiểu truyền và mục đích multicast.** Unicast gửi từ một nguồn tới một đích; broadcast gửi tới mọi nút trong phân đoạn mạng (255.255.255.255 hoặc địa chỉ broadcast subnet — router không chuyển tiếp); multicast gửi tới **một nhóm** các máy tự nguyện tham gia [1, Bài 8.1]. Nếu phát cùng nội dung cho N máy bằng unicast, nguồn phải nhân bản N lần, gây dư thừa và nghẽn trên đường truyền; multicast giải quyết bằng **cây định tuyến multicast**: tại mỗi thời điểm, trên mỗi nhánh chỉ có một gói, việc nhân bản xảy ra ở router nơi cây rẽ nhánh (Hình 2.3, theo [1, Hình 8.1]). Multicast là truyền một chiều, phi liên kết, dùng UDP chứ không dùng TCP [1, Bài 8.1].

```
          Nguồn (server)                 Trên mỗi nhánh: đúng 1 gói;
              |  1 gói                   router rẽ nhánh mới nhân bản
             R1                          (không gửi ngược về nguồn)
            /    \
          R2      R3
         /  \       \
       H1    H2      H3   ← các thành viên nhóm multicast
```

**Hình 2.3.** Cây phân phối multicast: nhân bản gói tin trong mạng (theo [1, Hình 8.1]).

**Địa chỉ multicast và quản lý nhóm.** Multicast dùng địa chỉ **lớp D**: 224.0.0.0…239.255.255.255 (4 bit đầu `1110`) [1, Bài 8.1.2]. Các không gian đáng chú ý: link-local 224.0.0.0/24 dành cho giao thức định tuyến (all-hosts 224.0.0.1, all-routers 224.0.0.2…), router không chuyển tiếp; dải quản trị 239.0.0.0/8 dùng trong vùng riêng (tương tự địa chỉ riêng RFC1918), không định tuyến liên domain; dải toàn cục 224.0.1.0…238.255.255.255 phải duy nhất nếu định tuyến Internet. Ánh xạ sang MAC Ethernet dùng tiền tố 01-00-5e và 23 bit thấp của địa chỉ IP — 5 bit bị mất khiến 32 địa chỉ multicast trùng một MAC, nên host phải lọc thêm theo IP trong frame [1, Bài 8.1.2]. Host gia nhập/rời nhóm bằng **IGMP** (v1: RFC 1112, v2: RFC 2236); các router trao đổi bằng **PIM** để dựng cây multicast không vòng lặp [1, Bài 8.1.3]. Switch L2 mặc định phát tán multicast ra mọi cổng; router/L3 chỉ chuyển khi được cấu hình định tuyến multicast — hệ quả thực tế: multicast hoạt động tốt trong LAN được cấu hình đúng, và có thể bị chặn ở mạng hội trường/VLAN lạ.

**Lớp MulticastSocket.** Là lớp con của `DatagramSocket` trong `java.net`, bổ sung các phương thức nhóm [1, Bài 8.2]: `joinGroup(InetAddress mcastGroup)` gia nhập nhóm, `leaveGroup(InetAddress)` rời nhóm, `setTimeToLive(int ttl)`/`getTimeToLive()` đặt/đọc giới hạn router gói được đi qua. Các bước gửi [1, Bài 8.3.1]: tạo `MulticastSocket` → tham gia nhóm → tạo `DatagramPacket` (đặt địa chỉ nhóm + cổng) → `send()` → rời nhóm. Các bước nhận [1, Bài 8.3.2]: tạo socket **gán đúng cổng nhóm** → `joinGroup` → tạo gói rỗng với bộ đệm → `receive()` chờ gói phân tán tới → xử lý dữ liệu (`new String(packet.getData(), 0, packet.getLength())`) → rời nhóm. (Ghi chú thực hành hiện đại, ngoài giáo trình:) API `joinGroup(InetAddress)`/`setTimeToLive` là dạng cũ; Java mới khuyến nghị `joinGroup(SocketAddress, NetworkInterface)` và đặt tùy chọn qua `StandardSocketOptions.IP_MULTICAST_TTL`, `IP_MULTICAST_IF`, `IP_MULTICAST_LOOP`; `SO_REUSEADDR` cho phép nhiều tiến trình thuê bao cùng chia sẻ cổng nhóm trên một máy (BSD/macOS).

**Liên hệ với AEGIS-EBS.** `MulticastHintPublisher`/`MulticastHintSubscriber` hiện thực đúng các bước của [1, Bài 8.3] bằng API hiện đại: nhóm **239.10.10.10** (dải quản trị 239/8), cổng **9998**, **TTL = 1** (chỉ trong liên kết), giao tiếp ra cấu hình được; publisher gửi **đúng một datagram cho mỗi hint bất kể số thuê bao** — đếm packets/bytes để chứng minh nhân bản xảy ra trong mạng chứ không ở nguồn. Nội dung hint là `ALERT_HINT {alertId, revision, serverId}` — **chỉ mang tính gợi ý (advisory), không bao giờ là lệnh tin cậy**: `VerifiedAlertSubscriber` nhận hint, bỏ qua revision cũ, rồi **tải nội dung thẩm quyền qua phiên TLS đã xác thực** và chỉ đưa nội dung đã xác minh vào kênh hiển thị; hint giả mạo cùng lắm gây một lần fetch. Trên macOS loopback (lo0), gửi multicast thành công nhưng không bao giờ giao được — `MulticastSupport.probe` phát hiện bằng phép thử gửi/nhận thật và `FallbackHintBus` tự chuyển sang unicast UDP. `FanoutBenchmark` so sánh TCP fanout với multicast và unicast fallback về số gói/byte phát tại nguồn, số bản nhận được và độ trễ đầu-cuối — bằng chứng định lượng cho lý thuyết Hình 2.3 (số liệu thật trình bày ở Chương 3). Kịch bản chặn multicast được kiểm thử bằng T-25/C14.

### 2.8 TLS với SSLSocket/SSLServerSocket

*(Nội dung ngoài giáo trình CMP180; tham khảo tài liệu Oracle JSSE [3].)*

**Vì sao cần TLS.** TCP đảm bảo tin cậy và thứ tự nhưng không đảm bảo bí mật, toàn vẹn hay xác thực nguồn gốc: dữ liệu đi dạng rõ (plaintext) trong LAN có thể bị nghe lén hoặc sửa đổi. TLS (Transport Layer Security) bọc kênh TCP bằng mã hóa, kiểm tra toàn vẹn và xác thực server bằng chứng chỉ X.509; JSSE (Java Secure Socket Extension) là khung cài đặt TLS/DTLS chuẩn của Java [3]. Với hệ thống phát cảnh báo khẩn cấp, xác thực server là yêu cầu sống còn: client phải biết chắc nội dung đến từ server thẩm quyền, không phải kẻ giả mạo trong LAN.

**Các thành phần JSSE.** `SSLContext` (khởi tạo bằng `SSLContext.getInstance("TLS")`) là nhà máy sản xuất socket bảo mật, được cấu hình bởi: `KeyManagerFactory` — nạp **keystore** chứa khóa riêng + chứng chỉ của phía server (danh tính trình ra khi bắt tay); `TrustManagerFactory` — nạp **truststore** chứa các chứng chỉ được tin (phía client dùng để xác minh chứng chỉ server); và `SecureRandom`. Từ `SSLContext` lấy `SSLSocketFactory`/`SSLServerSocketFactory` để tạo `SSLSocket`/`SSLServerSocket`. Điểm then chốt về kiến trúc: **`SSLSocket` kế thừa `Socket`, `SSLServerSocket` kế thừa `ServerSocket`** — sau bắt tay, hai phía vẫn giao tiếp bằng `getInputStream()`/`getOutputStream()` như socket thường [3]. Nhờ vậy toàn bộ tầng framing EBSP/2.0 (2.5) chạy nguyên vẹn trên TLS, không phải sửa codec. Bắt tay TLS thỏa thuận phiên bản giao thức và bộ mật mã (cipher suite, TLS 1.2/1.3), xác minh chuỗi chứng chỉ và tên máy (hostname/SAN), rồi sinh khóa phiên cho mã hóa đối xứng.

**Chứng chỉ tự ký và pinning trong lab.** Môi trường lab không có CA thương mại; hệ thống dùng chứng chỉ **tự ký** sinh bằng `keytool` (RSA 2048, CN=localhost, SAN=dns:localhost,ip:127.0.0.1, keystore PKCS12) và **ghim (pin)**: truststore phía client chứa đúng chứng chỉ server đó, `TrustManagerFactory` chỉ tin đúng các chứng chỉ trong truststore. Hệ quả: chứng chỉ giả (dù hợp lệ về hình thức) bị từ chối ngay khi bắt tay; kết nối tới máy khác giả danh server cũng bị từ chối vì tên/SAN không khớp. Đây là mô hình tin cậy tối giản phù hợp LAN một server; production cần PKI thực và quản lý vòng đời chứng chỉ (nêu ở hạn chế, Chương 4). Cần nhấn mạnh: **TLS xác thực kênh truyền, không thay thế ủy quyền** — sau bắt tay vẫn phải xác thực người dùng/thiết bị và kiểm tra RBAC (2.9).

**Liên hệ với AEGIS-EBS.** Script `scripts/gen-server-cert.sh` sinh bộ chứng chỉ demo (keystore server + truststore client). `TlsSupport.clientContext(truststore, password)` và `LabTlsContexts.server/client` hiện thực đúng quy trình `KeyStore → KeyManagerFactory/TrustManagerFactory → SSLContext.init(...)` của [3]. Server lắng nghe `SSLServerSocket` cổng 9443; mọi khung EBSP đi trong kênh TLS. Kiểm thử chấp nhận T-05 và chaos C09 (cert giả) xác minh client/server từ chối chứng chỉ không được ghim; T-28 quét log bảo đảm không lộ secret (mật khẩu, khóa). Giới hạn tự ký được ghi nhận trung thực trong phạm vi đồ án.

### 2.9 RBAC và bảo mật ứng dụng

*(Nội dung ngoài giáo trình CMP180; tham khảo OWASP [5].)*

**Xác thực và ủy quyền.** Xác thực (authentication) trả lời “bạn là ai”; ủy quyền (authorization) trả lời “bạn được làm gì”. OWASP nhấn mạnh mọi điểm vào phải kiểm tra ủy quyền phía server, mặc định từ chối (deny by default), và áp dụng đặc quyền tối thiểu (least privilege) [5]. **RBAC** (Role-Based Access Control) gán quyền cho vai trò, gán vai trò cho người dùng — thay vì gán quyền lẻ tẻ từng người; với hệ thống nhiều tầng/khu vực, RBAC cần mở rộng theo **phạm vi (scope)**: cùng vai trò nhưng chỉ được thao tác trong tòa nhà/tầng được cấp.

**Quản lý phiên và lưu trữ mật khẩu.** Sau xác thực, trạng thái phiên (danh tính, vai trò, phạm vi) phải được giữ phía server và gắn với kết nối; mọi thông điệp tiếp theo được xử lý theo danh tính đã ràng buộc, không tin danh tính client tự khai [5]. Mật khẩu không bao giờ lưu dạng rõ hay băm nhanh (MD5/SHA thuần); OWASP khuyến nghị **Argon2id** — hàm băm memory-hard chống brute-force bằng GPU/ASIC — với cấu hình tối thiểu khoảng 19 MiB bộ nhớ, 2 vòng lặp, 1 độ song song; salt tự động nằm trong chuỗi mã hóa `$argon2id$...` [5].

**Kiểm toán.** Mọi thao tác quản trị (phát/hủy cảnh báo, cấp quyền) và mọi lần **từ chối** ủy quyền phải ghi nhật ký kiểm toán: ai, làm gì, khi nào, kết quả — để truy vết sự cố và phát hiện lạm quyền [5]. Secret (mật khẩu, khóa riêng, nội dung nhạy cảm) không được xuất hiện trong log.

**Liên hệ với AEGIS-EBS.** Hệ thống định nghĩa năm vai trò (`aegis.protocol.Role`) với ma trận quyền phát cảnh báo như Bảng 2.3; logic ủy quyền là `ScopedRBAC.canPublish(role, grants, buildingId, floorId)` — thuần logic, kiểm thử độc lập được: `SYSTEM_ADMIN` phát mọi nơi; `SECURITY_OPERATOR` chỉ trong tòa nhà được cấp (mọi tầng của tòa đó); `FLOOR_MANAGER` chỉ đúng tầng được cấp của tòa được cấp; `EMPLOYEE` và `DEVICE` không bao giờ được phát. Phạm vi cấp quyền biểu diễn bằng record bất biến `Grants` (sao chép phòng thủ, accessor chỉ đọc) — sửa collection nguồn sau khi tạo không ảnh hưởng. Xác thực dùng `AUTH_OPERATOR`/`AUTH_DEVICE` trong kênh TLS; mật khẩu băm bằng `Argon2idHasher` (argon2-jvm, tham số cấu hình được; mặc định demo iterations=2, memory=16 MiB, parallelism=1 — triển khai thật cần nâng bộ nhớ ≥ 19 MiB theo [5]); mảng ký tự mật khẩu được xóa (`wipeArray`) sau khi băm/verify. Phiên gắn danh tính vào `ClientSession` (`bindOperator`/`bindDevice`), trạng thái `volatile`, đóng một lần bằng `AtomicBoolean`; `AuditService` ghi sự kiện kiểm toán, thao tác ngoài phạm vi bị DENIED kèm audit (T-07/C08). Thiết bị bị vô hiệu hóa không được xác thực (T-06).

**Bảng 2.3.** Ma trận vai trò – quyền phát cảnh báo của AEGIS-EBS (cài đặt trong `ScopedRBAC`).

| Vai trò | Quyền phát cảnh báo | Phạm vi |
|---|---|---|
| SYSTEM_ADMIN | Có | Mọi tòa nhà, mọi tầng |
| SECURITY_OPERATOR | Có | Trong tòa nhà được cấp (mọi tầng của tòa đó) |
| FLOOR_MANAGER | Có | Đúng tầng được cấp của tòa nhà được cấp |
| EMPLOYEE | Không | Chỉ nhận cảnh báo; gửi ACK_USER |
| DEVICE | Không | Máy trạm: nhận ALERT_PUSH; gửi ACK_RECEIVED/ACK_USER |

### 2.10 Chuẩn CAP 1.2 (tham khảo)

*(Nội dung ngoài giáo trình CMP180; tham khảo chuẩn OASIS [2].)*

**CAP là gì.** Common Alerting Protocol (CAP) phiên bản 1.2 là chuẩn OASIS (thông qua ngày 01/07/2010) định dạng chung để trao đổi cảnh báo khẩn cấp đa hiểm họa (all-hazard) trên mọi loại mạng [2]. Một bản tin CAP là tài liệu XML `<alert>` gồm: phần định danh/trạng thái (`identifier`, `sender`, `sent`, `status`, `msgType`, `scope`, `references`, `incidents`); một hoặc nhiều khối `<info>` mô tả sự kiện (`category`, `event`, `urgency`, `severity`, `certainty`, `headline`, `description`, `instruction`, `effective`, `onset`, `expires`…); và khối `<area>` khoanh vùng ảnh hưởng (`areaDesc`, `polygon`, `circle`, `geocode`). Ba trục phân loại mức độ của CAP — urgency (khẩn cấp về thời gian), severity (mức nghiêm trọng), certainty (độ chắc chắn) — cùng `msgType` (Alert/Update/Cancel/Ack/Error) và `status` (Actual/Exercise/Test/System/Draft) tạo nên ngữ nghĩa vòng đời cảnh báo chuẩn mực.

**Ánh xạ tham khảo vào AEGIS-EBS.** EBSP/2.0 không phải bản tin CAP XML; đồ án **tham khảo mô hình ngữ nghĩa** của CAP khi thiết kế payload cảnh báo JSON: bốn mức ưu tiên INFO/WARNING/EMERGENCY/CRITICAL lấy cảm hứng từ trục severity; vòng đời revision + `ALERT_UPDATE`/`ALERT_CANCEL`/hết hạn tương ứng `msgType` Update/Cancel và trường `expires`; mục tiêu theo tòa nhà/tầng/zone tương ứng khối `<area>`; metadata sự kiện (loại sự cố, mô tả, hướng dẫn) tương ứng `<info>`. Đặc biệt, CAP có sẵn `status = Exercise/Test` — phù hợp định vị SIMULATION/TRAINING của hệ thống: mọi bản tin demo đều mang bản chất diễn tập. Bảng 2.4 tóm tắt ánh xạ; việc xuất bản tin CAP XML chuẩn đầy đủ là hướng phát triển (Chương 4), không nằm trong phạm vi hiện tại.

**Bảng 2.4.** Ánh xạ các trường chính của CAP 1.2 [2] sang mô hình cảnh báo AEGIS-EBS (tham khảo ngữ nghĩa, không phải tương thích XML).

| CAP 1.2 | AEGIS-EBS (EBSP/2.0) | Ghi chú |
|---|---|---|
| `alert.identifier`, `sender`, `sent` | `alertId` (UUID), người phát (userId/role), `sentAt` UTC | Định danh + nguồn + thời điểm |
| `msgType` Alert/Update/Cancel | `ALERT_CREATE`/`ALERT_PUBLISH`, `ALERT_UPDATE` (revision tăng), `ALERT_CANCEL` | Vòng đời bản tin |
| `status` Exercise/Test | Nhãn SIMULATION/TRAINING toàn hệ thống | Định vị diễn tập |
| `info.severity`/`urgency` | `Priority` INFO/WARNING/EMERGENCY/CRITICAL | Bốn mức, lấy cảm hứng severity |
| `info.event`, `description`, `instruction` | Payload metadata sự cố + nội dung/hướng dẫn hiển thị | Nội dung cho người dùng |
| `info.expires`, `references` | Tombstone hết hạn/hủy; `correlationId`, chuỗi revision | Chống hiển thị tin cũ |
| `area.areaDesc`, `geocode` | Target snapshot: tòa nhà/tầng/zone (`AlertTarget`) | Khoanh vùng nhận tin |

**Tóm tắt Chương 2.** Chương này đã trình bày mười nhóm lý thuyết nền tảng: mô hình client–server và lựa chọn kiến trúc (2.1); TCP và socket Java (2.2); đa tuyến, đồng bộ và thread pool (2.3); địa chỉ mạng với InetAddress (2.4); luồng I/O, JSON/đối tượng và framing length-prefix cùng ngữ nghĩa idempotent/ACK/recovery (2.5); UDP và discovery an toàn (2.6); multicast và mẫu hình hint–verify (2.7); TLS/JSSE (2.8); RBAC và bảo mật ứng dụng (2.9); CAP 1.2 tham khảo (2.10). Bảng 2.5 ánh xạ từng bài của giáo trình [1] vào mục lý thuyết và module cài đặt tương ứng — làm cơ sở trực tiếp cho Chương 3.

**Bảng 2.5.** Ánh xạ nội dung giáo trình CMP180 [1] vào các mục lý thuyết và module của hệ thống.

| Bài (giáo trình [1]) | Nội dung chính | Mục lý thuyết | Module AEGIS-EBS |
|---|---|---|---|
| Bài 1 | OSI/TCP-IP, TCP/UDP/IP, cổng, DNS | 2.1, 2.2, 2.6 | aegis-protocol, aegis-server (cổng dịch vụ) |
| Bài 2 | Luồng byte/ký tự, luồng lọc, luồng đệm | 2.5 | aegis-protocol (FrameCodecImpl: DataInputStream/OutputStream) |
| Bài 3 | Thread, Runnable, synchronized, piped streams | 2.3 | aegis-server (writer thread), aegis-core (AckLedger), aegis-network-lab (vòng nhận) |
| Bài 4 | InetAddress, URL/URLConnection | 2.4 | aegis-network-lab (MulticastConfig, discovery), aegis-client |
| Bài 5 | Socket/ServerSocket TCP, tùy chọn socket | 2.2 | aegis-server (TLS gateway), aegis-client (AlertClientEngine) |
| Bài 6 | Concurrent server, tuần tự hóa đối tượng | 2.3, 2.5 | aegis-server (ClientSession/Executor), aegis-protocol (JSON thay serialization) |
| Bài 7 | UDP, DatagramPacket/DatagramSocket, MTU/TTL | 2.6 | aegis-network-lab (DiscoveryServer/Client, UnicastHint*) |
| Bài 8 | Multicast, nhóm lớp D, IGMP/PIM, MulticastSocket | 2.7 | aegis-network-lab (MulticastHintPublisher/Subscriber, FallbackHintBus) |
| Bài 9 | RMI, stub/skeleton, registry (đối sánh) | 2.1 | Không dùng RMI — chọn giao thức thông điệp tường minh |

Các nguồn ngoài giáo trình: [3] (ExecutorService, JSSE/TLS) → 2.3, 2.8; [4] (SQLite WAL) → 2.5; [5] (OWASP RBAC/phiên/mật khẩu) → 2.9; [6] (RFC 8085) → 2.6; [2] (CAP 1.2) → 2.10; [7] (Three.js) → dashboard (1.1, 1.2.3).

## CHƯƠNG 3. KẾT QUẢ THỰC NGHIỆM
3.1 Yêu cầu hệ thống (FR/NFR) · 3.2 Phân tích & thiết kế (kiến trúc 4 plane, sơ đồ module, thiết kế lớp, ERD 26 bảng, giao thức EBSP/2.0, state machine) · 3.3 Cài đặt (môi trường JDK24/Maven/Node, cấu trúc thư mục, mã nguồn tiêu biểu: FrameCodec, TransactionalPublisher, AckLedger/RetryPolicy, TLS gateway, multicast) · 3.4 Hướng dẫn biên dịch/chạy · 3.5 Kiểm thử & kết quả THẬT (bảng test pass, chaos C01–C16, p50/p95/p99, tỷ lệ ACK, ảnh dashboard 3D) · 3.6 Ưu điểm/hạn chế.
[TODO: điền số liệu đo thật + runId/commit SHA/máy đo; KHÔNG bịa số]

## CHƯƠNG 4. KẾT LUẬN VÀ KIẾN NGHỊ
[TODO: kết quả đạt được so mục tiêu; hạn chế (single-node, multicast LAN, TLS self-signed); hướng phát triển (HA, CAP export chuẩn, PostgreSQL)]

## TÀI LIỆU THAM KHẢO

[1] Văn Thiên Hoàng (biên soạn). *Lập trình mạng* — giáo trình học phần CMP180. Trường Đại học Công nghệ TP.HCM (HUTECH), 2021. Mã tài liệu 1.2021.CMP180, 105 trang, 9 bài (Bài 1: Tổng quan về lập trình mạng; Bài 2: Quản lý các luồng nhập xuất; Bài 3: Lập trình đa tuyến; Bài 4: Quản lý địa chỉ kết nối mạng; Bài 5: Lập trình socket cho giao thức TCP; Bài 6: Kỹ thuật đa tiến trình và tuần tự hóa đối tượng trong ứng dụng mạng; Bài 7: Lập trình socket cho giao thức UDP; Bài 8: Lập trình multicast; Bài 9: Phân tán đối tượng trong Java bằng RMI). Bản lưu học phần: `CMP180.md` / `CMP180.pdf`.

[2] OASIS. *Common Alerting Protocol Version 1.2*. OASIS Standard, 01/07/2010. https://docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2-os.html

[3] Oracle. *Java Secure Socket Extension (JSSE) Reference Guide* (JDK 24); *SSLSocket/SSLServerSocket — Java SE 24 Javadoc*; *ExecutorService — Java SE 24 Javadoc*. https://docs.oracle.com/en/java/javase/24/security/java-secure-socket-extension-jsse-reference-guide.html ; https://docs.oracle.com/en/java/javase/24/docs/api/java.base/javax/net/ssl/SSLSocket.html ; https://docs.oracle.com/en/java/javase/24/docs/api/java.base/java/util/concurrent/ExecutorService.html

[4] SQLite. *Write-Ahead Logging*. https://www.sqlite.org/wal.html

[5] OWASP. *Authorization Cheat Sheet*; *Session Management Cheat Sheet*; *Password Storage Cheat Sheet*. https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html ; https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html ; https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html

[6] L. Eggert, G. Fairhurst, G. Shepherd. *UDP Usage Guidelines*. BCP 145, RFC 8085, IETF, 03/2017. https://www.rfc-editor.org/info/rfc8085/

[7] Three.js. *Three.js Documentation* (thư viện đồ họa 3D nền web, dùng cho dashboard digital twin của đồ án). https://threejs.org/docs/

## PHỤ LỤC: MÃ NGUỒN CHƯƠNG TRÌNH [TODO trích module chính]
