Language: English | Vietnamese original: docs/DEMO-RUNBOOK.md

# DEMO RUNBOOK — 12 minutes (blueprint §14)
Preparation: 2-machine LAN Win+Mac (real multicast); or 1 machine + simulator (unicast fallback). Label everything SIMULATION.
0:00 System reveal: 3D dashboard with 10 floors, green connection, TLS/version.
1:00 Command authority: operator vs unauthorized floor-manager → DENIED + audit.
2:00 Incident launch: FIRE_DRILL on floor 5 + neighboring zone, CAP metadata, publish.
3:30 Digital twin live: floor changes color, 3 real clients + 100 simulator agents reflecting the target.
4:30 Fault injection: drop ACK of 1 client → retry + non-duplicate popup + attempt trace.
6:00 Offline recovery: disconnect/reconnect → active-alert sync, old revision hidden.
7:15 Priority: INFO burst then CRITICAL → queue classes + delivery.
8:15 Server restart: recover deliveries from DB; no exactly-once claim.
9:15 LAN lab: compare reliable TCP vs multicast hint (if the VLAN supports it).
10:15 Results: p50/p95/p99, retries, throughput, CPU (real numbers).
11:15 Technical summary: identify Socket/Stream/Thread/UDP/Multicast in CMP180 + disclaimer.
Fallback: video recorded from a real run + deterministic simulator if the venue network blocks multicast.
