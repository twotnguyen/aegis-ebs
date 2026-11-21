# AEGIS Network Lab — UDP discovery + multicast ALERT_HINT + TCP-vs-multicast benchmark

CMP180 Bài 7 (UDP) và Bài 8 (Multicast) áp dụng cho EBS capstone. **SIMULATION/TRAINING only.**

## Kiến trúc & mô hình tin cậy

| Kênh | Transport | Nội dung | Tin cậy? |
|---|---|---|---|
| Discovery | UDP unicast/broadcast cổng **9999** | `DISCOVER_EBS {nonce,version}` → `{serverHost,tlsPort,nonce,serverId}` | **KHÔNG** — chỉ là con trỏ; nonce echo chống replay nhầm phiên |
| Hint | **Multicast thật** `MulticastSocket` group **239.10.10.10** cổng **9998**, TTL/interface cấu hình được | `ALERT_HINT {alertId,revision,serverId}` | **KHÔNG** — advisory, không phải lệnh; chỉ kích hoạt fetch |
| Nội dung | TCP/**TLS** (EBSP/2.0, `FrameCodecImpl` + `Envelope` của aegis-protocol) | `HELLO/SERVER_HELLO`, `SYNC_REQUEST/SYNC_RESULT` | **CÓ** — pinned trust store + CN==serverId + nonce echo |
| Fallback | UDP unicast từng subscriber | `ALERT_HINT` như trên | KHÔNG (vẫn advisory) |

Client **không bao giờ tin UDP response** khi chưa xác thực TLS: `DiscoveryClient.discoverTrusted()`
chỉ trả `TrustedServer` sau khi (1) nonce trong reply khớp nonce đã gửi, (2) handshake TLS với
trust store pinned thành công + hostname/SAN verification, (3) CN của certificate == serverId
quảng cáo, (4) `SERVER_HELLO` echo đúng nonce discovery qua phiên TLS đó. Subscriber nhận hint
→ `VerifiedAlertSubscriber` fetch qua TLS → sink chỉ thấy nội dung authoritative (revision thật
từ server, không phải revision trong hint).

`DISCOVER_EBS`/`ALERT_HINT` không nằm trong enum `MessageType` (đóng băng theo docs/PROTOCOL.md),
nên datagram lab dùng codec JSON riêng: 1 object UTF-8 nghiêm ngặt/datagram, ≤1024 byte
(dưới MTU 1500 → không phân mảnh IP, Bài 7.1.1), có trường `type`. Kênh TLS tái dùng EBSP/2.0.

## Chạy lab

Acceptance (TDD):
```bash
cd /Users/twot/Documents/CODE/LTM/CMP180/aegis-ebs
mvn -q -pl aegis-network-lab test
```

CLI (không cần sửa pom; exec plugin chạy với classpath của module):
```bash
MVN="mvn -q -pl aegis-network-lab compile exec:java -Dexec.mainClass=aegis.networklab.LabMain"

# 1. Probe multicast thật (send+receive self-test; phát hiện macOS lo0 không giao hàng)
$MVN -Dexec.args="probe"
$MVN -Dexec.args="probe --iface lo0"     # → UNAVAILABLE, exit 3 (fallback sẽ dùng unicast)

# 2. Server: discovery UDP 9999 + TLS 9443 (tự sinh cert lab bằng keytool vào target/lab-certs)
$MVN -Dexec.args="serve"

# 3. Client discovery có xác thực TLS (terminal khác)
$MVN -Dexec.args="discover"              # in TRUSTED hoặc REJECTED + lý do

# 4. Subscriber: joinGroup 239.10.10.10:9998, hint → fetch TLS → chỉ in nội dung verified
$MVN -Dexec.args="subscribe"

# 5. Publisher: gửi ALERT_HINT advisory
$MVN -Dexec.args="publish --alert demo-alert --revision 2 --count 3"

# 6. Benchmark TCP fanout vs multicast vs unicast fallback → report JSON + MD
$MVN -Dexec.args="bench --subscribers 8 --messages 200 --payload-bytes 512"
# report: <thư mục chạy mvn>/target/lab-report/fanout-report.{json,md}
# (mvn -pl chạy từ repo root → aegis-ebs/target/lab-report/)
```

Tùy chọn multicast mọi lệnh: `--group 239.10.10.10 --port 9998 --ttl 1 --iface en0`.
`publish` khi multicast không khả dụng: thêm `--targets host:port,...` để gửi unicast fallback.

## Môi trường macOS (đã kiểm chứng trên máy này)

- `lo0` có flag MULTICAST nhưng **không có route multicast** → join/send thành công nhưng
  không bao giờ nhận được. `MulticastSupport.probe` phát hiện bằng send/receive thật
  (3 probe × 800ms — UDP mất gói đơn lẻ không kết luận chết multicast) và `FallbackHintBus`
  tự chuyển sang **unicast UDP từng subscriber**.
- Interface mặc định (en0) giao multicast loopback bình thường (IP_MULTICAST_LOOP=1) →
  integration test chạy **multicast thật** trên group 239.10.10.10.
- Nhiều subscriber cùng cổng 9998: cần `SO_REUSEADDR` trước khi bind (BSD semantics) — đã set.

## Benchmark đo gì

- **publisherPackets/Bytes**: số bản sao nguồn đặt lên link của nó (TCP: N×M frame đầy đủ nội
  dung; multicast: M datagram hint; unicast fallback: N×M datagram hint).
- **receivedCopies/Bytes**: tổng số bản giao tới N subscriber (nhân bản trong mạng/kernel —
  Bài 8 Hình 8.1: trên link multicast chỉ có 1 gói).
- **Latency** avg/p50/p95/max (nanoTime gửi→nhận, cùng host) + wall-time fanout.
- Report: `target/lab-report/fanout-report.json` + `.md` (kèm caveats: loopback kernel replication,
  hint nhỏ vs nội dung đầy đủ, chi phí fetch TLS không tính trong bench transport).

## Test

- `LabDatagramCodecTest` — unit: roundtrip hint/discovery, nonce echo fields, rejects (UTF-8 lỗi,
  JSON thừa, thiếu/trường sai kiểu, version lạ, revision < 1, oversize >1024).
- `DiscoveryNonceEchoTest` — UDP loopback: server echo nonce; rogue reply sai nonce bị từ chối;
  timeout; datagram rác bị drop.
- `DiscoveryTrustTest` — TLS thật (keytool cert): happy path trusted; sai cert (unpinned) reject;
  CN ≠ serverId reject; SERVER_HELLO không echo nonce reject; chưa TLS thì không có trust.
- `MulticastLoopbackTest` — integration: multicast THẬT nếu probe OK (group 239.10.10.10:9998, 3 subscriber,
  hint → fetch TLS); nếu môi trường không hỗ trợ: in rõ lý do và kiểm tra unicast fallback.
- `UnicastFallbackTest` — cấu hình `--iface lo0` (hoặc môi trường không multicast) → bus tự
  fallback, hint vẫn tới đủ subscriber, fetch TLS verified.
- `VerifiedAlertSubscriberTest` — hint giả revision cao: sink vẫn chỉ thấy nội dung TLS;
  hint cũ/trùng bị bỏ qua không fetch.
- `FanoutBenchmarkTest` — N=4, M=30: TCP packets=120, multicast packets=30 (nếu probe OK) còn
  received copies=120 mọi mode; report JSON+MD ghi và đọc lại được.
