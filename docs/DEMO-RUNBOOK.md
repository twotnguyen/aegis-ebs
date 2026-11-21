# DEMO RUNBOOK — 12 phút (blueprint §14)
Chuẩn bị: LAN 2 máy Win+Mac (multicast thật); hoặc 1 máy + simulator (unicast fallback). Gắn nhãn SIMULATION.
0:00 System reveal: dashboard 3D 10 tầng, kết nối xanh, TLS/version.
1:00 Command authority: operator vs floor-manager trái phép → DENIED + audit.
2:00 Incident launch: FIRE_DRILL tầng 5 + zone lân cận, CAP metadata, publish.
3:30 Digital twin live: tầng đổi màu, 3 client thật + 100 simulator phản ánh target.
4:30 Fault injection: drop ACK 1 client → retry + popup không trùng + attempt trace.
6:00 Offline recovery: disconnect/reconnect → alert active sync, revision cũ ẩn.
7:15 Priority: INFO burst rồi CRITICAL → queue classes + phân phối.
8:15 Server restart: khôi phục delivery từ DB, không claim exactly-once.
9:15 LAN lab: so sánh TCP reliable vs multicast hint (nếu VLAN hỗ trợ).
10:15 Results: p50/p95/p99, retry, throughput, CPU (số thật).
11:15 Technical summary: chỉ rõ Socket/Stream/Thread/UDP/Multicast trong CMP180 + disclaimer.
Fallback: video quay từ lần chạy thật + simulator deterministic nếu mạng hội trường chặn multicast.
