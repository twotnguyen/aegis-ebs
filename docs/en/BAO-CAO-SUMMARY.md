Language: English | Vietnamese original: docs/BAO-CAO.md

# Condensed English summary — CMP180 Capstone Project Report: AEGIS Emergency Broadcast System

**Condensed English summary, not a full translation; authoritative full report is docs/BAO-CAO.md (Vietnamese).**

Course: CMP180 — Computer Network Programming, HUTECH. Advisor: Nguyen Van Can (ThS.). Group: AEGIS-EBS. Members / student IDs: Nguyen Ngoc Tinh – 2380614851; Bui Thanh Long An – 2380600103; Nguyen Hoang Tung – 2380602485; Vo Thanh Dong – 2380600518. Class/Cohort: SE 2023–2027. Academic year 2025–2026.
> SIMULATION/TRAINING — does not replace certified fire alarm/evacuation systems.

## CHAPTER 1. OVERVIEW

### 1.1 Introduction
The project builds an emergency broadcast system for office buildings: a command center sends prioritized alerts to hundreds of LAN clients with scoped targeting, two-layer acknowledgements, idempotent retry, reconnect/sync, crash recovery, RBAC and an append-only audit trail, plus a 3D digital twin, a chaos lab and real UDP/multicast experiments. It is an academic prototype built on self-implemented Java Socket protocols — not a life-safety product.

### 1.2 Project tasks
- **Urgency:** ordinary broadcast cannot prove the right device received an alert, cannot distinguish machine receipt from human acknowledgement, cannot suppress duplicates on retry, and cannot enforce floor-level authorization.
- **Scientific and practical significance:** demonstrates application-layer protocol design over TCP/UDP (framing, envelope, 28 message types), reliability under packet/ACK loss and server crash, and a measured comparison of TCP fanout vs multicast hints.
- **Objectives (10 targets):** EBSP/2.0 on TCP/TLS; targeted one-to-many delivery with 4 priority levels and anti-starvation; two-layer ACK with advance-only state; idempotent retry; reconnect + SYNC; crash recovery; security/authorization; UDP/multicast lab; reliability measurement; 3D digital twin.
- **Subject and scope:** one LAN server, JavaFX clients + headless simulator (100 mandatory, 200–500 stretch), web dashboard; single-node, no HA.

### 1.3 Report structure
4 chapters: Overview; Theoretical Background; Experimental Results; Conclusion and Recommendations, followed by References and a source-code appendix (6 representative code excerpts: framing, retry, rank guard, re-auth rejection, bounded WS writer, 3D twin state).

## CHAPTER 2. THEORETICAL BACKGROUND

- **2.1 Client–Server model; 2.2 TCP and Java Socket programming** (`Socket`/`ServerSocket`, byte streams); **2.3 Multithreading** — Thread, Runnable, synchronization, `ExecutorService` and virtual threads; **2.4 InetAddress** and connection address management (IPv4/IPv6, DNS, private ranges, loopback).
- **2.5 JSON over network streams and length-prefix framing** — strict UTF-8 JSON, `uint32 BE` length L ∈ [1, 65536], reject oversize before allocation, `readFully` for partial/coalesced reads.
- **2.6 UDP and DatagramSocket** — MTU 1500 bytes on Ethernet, IPv4 maximum UDP payload 65507 bytes, TTL; **2.7 Multicast and MulticastSocket** — group addressing, `joinGroup(SocketAddress,NetworkInterface)`, IGMP/PIM, administratively-scoped group; **2.8 TLS with SSLSocket/SSLServerSocket** — JSSE, X.509 certificates, truststore pinning.
- **2.9 RBAC and application security** — deny-by-default, scope derived from the server DB, Argon2id, audit; **2.10 CAP 1.2 (reference)** — common alerting protocol metadata (urgency/severity/certainty), not claimed compliant.
- Theory is cited from the CMP180 textbook [1, Lessons 1–9] and [2] OASIS CAP 1.2, [3] Oracle JSSE/ExecutorService docs, [4] SQLite WAL, [5] OWASP cheat sheets, [6] RFC 8085, [7] three.js docs.

## CHAPTER 3. EXPERIMENTAL RESULTS

### 3.1 System requirements
FR-01…FR-33 and NFR-001…NFR-018 cover protocol framing, targeted broadcast with priority, two-layer ACK, idempotent retry (3s/6s/12s + jitter, max 3 retries then FAILED), reconnect/expire/cancel tombstones, crash recovery, RBAC, audit, measurement/reproducibility, fresh install < 30 minutes, and 64 KiB resource bounds. NFR-001 target p50/p95/p99 from publish→`ACK_RECEIVED` measured with a monotonic 4096-sample reservoir.

### 3.2 Analysis and design
Four planes (command, delivery, persistence/observability, discovery/experiment); modular monolith of 7 Java modules + Node dashboard; transactional outbox (commit alert+revision+snapshot+deliveries+operation with `request_id UNIQUE` before enqueue); 26-table SQLite schema (`001_base_schema.sql` 12 tables + `002_showcase_expansion.sql` 14 tables); EBSP/2.0 envelope `{version:2, type, messageId, correlationId, sentAt, payload}` with 28 message types; state machines for Incident / Alert / Delivery with rank guard; `status/1` WebSocket feed with snapshot/delta and canonical zone severity.

### 3.3 Implementation
Java 24 (Maven 3.10), sqlite-jdbc, Jackson, Argon2id, JavaFX 24, Three.js + Vite (Node 26), JUnit 5. Demo world: building `AEGIS-BLD-A`, 10 floors x 4 zones x 12 devices = 480 devices `DEV-A-F01-A-01`…`DEV-A-F10-D-12`; ports TLS 9443 / WS 9444 / Operator bridge 9445 / UDP discovery 9999 / multicast 239.10.10.10:9998 / Vite 5173. The 3D twin is assembled from **12 real modules** (structure, facade, interior, safety, environment, lighting, statusViz, camera, HUD, and related modules); the enlarged footprint is **72x50 m (3.600 m², 2.73x the 44x30 m version)**, making the 4 zones per floor legible. Context instances fell 2.738→275 and triangles 55.060→5.730 (≈90%); frustum-fit of 8 corners keeps the whole tower in view. Latest LIVE evidence: `docs/evidence/dashboard/twin-enlarged-live.png` + `twin-enlarged-meta.json` (241.336 triangles, 230 InstancedMesh / 14.491 instances, `moduleErrors=[]`, fleet 20). FPS was not measured because browser tabs are throttled — no frame-rate claim.

### 3.4 Build and run
`./scripts/dev.sh`, `./scripts/test.sh`, `./scripts/initdb.sh`, `./scripts/gen-server-cert.sh`, `bash scripts/demo-web.sh`; fresh-install budget < 30 minutes; full commands in `docs/BUILD.md` and `docs/USER-GUIDE.md`.

### 3.5 Testing and experimental results
- **Test inventory:** latest local reactor verify: **361 Java tests (1 skipped, 0 failures/errors) + 22 dashboard tests** (16 logic + 6 i18n); archived official run `verify-347-h1h4` on Apple M3 Pro (2026-11-21) recorded 347 Java + 14 dashboard. Per current module: protocol 57, core 76, persistence 59, server 73, client 45, simulator 20, network-lab 31 = 361. Offline script tests: 17 (`test-task2.py` 7 + `test-admin-bridge.py` 10). Historical baseline: 338 Java / 14 dashboard.
- **Acceptance T-01…T-30:** all 30 have concrete evidence. T-30 is now **PASS** — a fresh clone reached `AEGIS EBS server ready` in 451 s with cold Maven/npm caches (toolchain preinstalled; installation time not counted); 361 Java tests (1 skipped), 22 dashboard tests, 26 tables, 480 seeded devices; runId `fresh-1795688539`. Earlier status: UNVERIFIED with only a 1.74 s warm-cache measurement.
- **Peer review:** independent per-module reviews + adversarial probes; 4 HIGH findings of the backend depth audit fixed (`docs/reviews/backend-depth-audit-fixes.md`); commits `f68f9e2` (S-01…S-10 fixes), `9f0dad2` (historical 318 methods / 338 executions baseline).
- **Chaos C01…C16:** C01–C05 **measured** (fleet 50, seed 42, commits `69fbee8` for C01–C03 and `17ea534` for C04–C05, ports 29443/29444, Apple M3 Pro/Java 24.0.1): C01 lossy runId `e1943406` — 15 dropped ACKs → 15 retries, 98% (49/50), 1 FAILED at attempt 4, p50/p95/p99 = 493/6026/13575 ms; C02 delayed `d04af662` — 50 delayed ACKs, 100%, 0 retries, 1585/2703/2707 ms; C03 duplicate `04c97420` — 22 ACKs deduped, 100%, 189/301/306 ms; C04 flaky `8e92ca3c` — 21 disconnects → 21 reconnects, 100%, 599/1016/1033 ms; C05 mixed `3fe99b11` — 13 reconnects, 100%, 12 retries, 1286/3506/5008 ms; all runs `failed=[]`. C06–C16 verified by unit/integration tests + review probes (crash/RBAC/cert/fuzz/priority/cancel/UDP/multicast/contention/slow-client).
- **100-client load benchmark:** runId `276dedf5-fa83-4022-b7be-f3a47fccf591`, commit `c1dbab5`, Apple M3 Pro (12 cores, 18 GB, arm64), macOS 15.7.3, Java 24.0.1, Python 3.14.2, host Macbook-Tinh, started 2026-11-21T13:58:01Z; fleet 100, `clean`, seed 42, 30 s, TLS 29443/WS 29444, maxRetries=3 base 3000 ms; building-wide CRITICAL FIRE_DRILL (deliveryCount 100). Results: **p50 = 773,142 ms, p95 = 1307,147 ms, p99 = 1334 ms** (100 samples), ACK_RECEIVED 100% (100/100), 0 retries, 0 duplicate popups, 100 durable deliveries with clean FK; server metrics reported p50=773/p95=1311/p99=1338 ms, transport_retries=0. CPU/RSS/WAL were not captured — no estimated numbers substituted.
- **Fanout benchmark (real measurements):** publisher-level counters — TCP_FANOUT 1600 writes / 823848 bytes; MULTICAST_HINT and UNICAST_UDP_FALLBACK 1600 datagrams / 143136 bytes; multicast reduces ~**87.5% publisher writes / 97.8% publisher bytes** (8 subscribers x 200 messages, macOS loopback). These are application-level counters, not packet captures; hints carry advisory metadata and exclude the TLS content fetch. Full table in table 3.19 of the Vietnamese report and `target/lab-report/fanout-report.{json,md}`.
- **Digital twin observation:** real LIVE screenshot at commit `9dfede1` (Apple M3 Pro, 2026-11-21T21:32 +07, 40-device simulated fleet, 4 active alerts F05 CRITICAL FIRE_DRILL / F04 WARNING HAZMAT / F07 EMERGENCY SECURITY / F02 INFO ANNOUNCEMENT; Zone Status 28 online / 1 critical / 11 offline). Evidence sets in `docs/evidence/` include `verify/` (archived run: 347 Java tests in 52 XML files; current run: 361 Java / 1 skipped), `benchmark/`, `chaos/`, `dashboard/`, and `video/aegis-ebs-demo.mp4` (720,05 s — a presentation video made from evidence, not a live screen recording). Benchmark/chaos/verify runs carry runId/commit SHA/measurement machine; the video set has only generation metadata.

### 3.6 Strengths and limitations
Strengths: testable pure logic separated from I/O (`aegis-core`), real SQLite/TLS integration tests, headless CI, self-implemented protocol with evidence, multi-round peer review. Limitations: single-node/SQLite single-writer; self-signed TLS and demo secrets; multicast LAN-only; mixed/200–500 load, CPU/RSS/WAL and 2-machine LAN not measured; group identity details pending.

## CHAPTER 4. CONCLUSION AND RECOMMENDATIONS

### 4.1 Results achieved against objectives
All ten objectives were built and functionally verified with real evidence (`verify-347-h1h4`, 100-client benchmark, chaos C01–C05, LIVE dashboard): EBSP/2.0 framing and 28 message types; targeted priority fanout with 256-entry bounded queues and MAX_BURST 8 fairness; two-layer ACK with SQL rank guard; bounded retry with dedup; reconnect + SYNC tombstones; transactional-outbox recovery; scope-based RBAC with Argon2id and audit; UDP/multicast lab with TLS verification; 100-client p50/p95/p99 measurement; 10-floor x 4-zone Three.js digital twin from 12 modules with 72x50 m footprint. The methodological contribution is multi-round independent peer review with real adversarial probes plus a quantitative multicast benchmark.

### 4.2 Main limitations
- **Single-node:** one LAN server is a single point of failure; crash recovery does not equal high availability — no new alerts are delivered while the server is down; SQLite's single writer remains a write bottleneck.
- **LAN-scoped multicast:** group `239.10.10.10` with TTL=1 stays on the link; no router/NAT traversal; depends on switch IGMP snooping; macOS loopback needs unicast fallback. No promise for an unfamiliar venue network — fallback = unicast + recorded video.
- **Self-signed TLS:** minimal trust model for a one-server lab; no CA, no lifecycle/revocation, hostname verification not enabled on raw SSLSocket, demo Argon2id parameters below OWASP thresholds.
- Additional: extended measurements (mixed/200–500, CPU/RSS/WAL, real 2-machine LAN) and group identity information were not available at submission; minor codec/memory-lifecycle items are listed in §3.6.2 of the full report.

### 4.3 Recommendations and future work
1. HA: active–passive cluster with leader election or floor relays; share the durable outbox across nodes using the existing idempotency contract.
2. Standards-compliant CAP 1.2 XML export with OASIS schema validation (`status = Exercise/Test` for drills).
3. Move to PostgreSQL only with real multi-writer/replication evidence; keep the additive 26-table schema and rank guard.
4. Production security hardening: real PKI + certificate lifecycle, hostname verification, Argon2id m ≥ 19 MiB per OWASP, absolute session TTL, central log masking, strict ACK_USER userId verification.
5. Network scope: IGMP snooping/PIM on a real LAN, controlled unicast hints over WAN/NAT, RFC 8085 compliance, rerun benchmark on a 2-machine LAN.
6. Close remaining issues: evict AckLedger/DedupIndex by alert lifetime, normalize RetryPolicy convention, durable/derived feed counters, explicit FAILED recovery budget, seeded RNG + virtual clock for absolute reproducibility.
7. Extend measurement + automate evidence capture in CI (current base: verify 51+ XML, 100-client benchmark, 5 chaos presets, LIVE dashboard images).
8. Research: mobile/push alerts outside the LAN, certified physical-device integration, OpenTelemetry end-to-end traces, formal safety evaluation of delivery state machines, multi-building campus twins.

### 4.4 General conclusion
Within the verified lab scope, the project delivers a complete Java client–server system using the nine CMP180 lessons plus modern practices [2]–[7], with a reliable core whose guarantees are backed by real measurements rather than claims. Outstanding extended measurements and group identity details do not change the conclusion about the verified core, but limit the scope of the claims.

## References and authoritative source
The full Vietnamese report `docs/BAO-CAO.md` is authoritative for the complete bibliography [1]–[7], all tables/figures, code excerpts and the exact wording of every claim. This summary preserves the four-chapter structure, conclusions, limitations and the measured figures (archived run: 347 Java tests; current local verify: 361 Java tests (1 skipped) + 22 dashboard; 773/1307/1334 ms, chaos C01–C05, 12-module 3D twin, 72x50 m) without inventing any measurement.
