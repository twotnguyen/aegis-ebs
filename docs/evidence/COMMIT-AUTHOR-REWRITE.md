# ÁNH XẠ COMMIT SAU KHI SỬA TÁC GIẢ

Ngày 22/11/2026: 29 commit ban đầu được ghi với danh tính `aegis <aegis@local>` do cờ `-c user.name=aegis -c user.email=aegis@local` trong lúc điều phối. Theo yêu cầu của chủ repo, lịch sử đã được rewrite để author/committer là `Twot <nguyenngoctinh011258@gmail.com>` (GitHub `twotnguyen`). Nội dung tree, thông điệp, tác giả gốc của mọi file GIỮ NGUYÊN; chỉ SHA commit đổi.

**Quy ước với evidence:** file trong `docs/evidence/` giữ nguyên commit SHA ghi tại thời điểm đo (không sửa số liệu/audit trail). Bảng dưới ánh xạ SHA cũ → SHA mới để đối chiếu. Khi trích dẫn bằng chứng trong báo cáo, hiểu SHA cũ là provenance gốc, tương đương SHA mới theo bảng này.

| SHA cũ | SHA mới | Commit |
|---|---|---|
| `373c8352a67b` | `63ab0411096a` | chore: scaffold (Maven reactor, EBSP/2.0 contract, 26-table schema, scripts) |
| `5eeca68254bb` | `34fde206aaa3` | feat(protocol): EBSP/2.0 FrameCodec + validation + 56 tests (TDD) |
| `a6e7b04ad25d` | `a1176c8b1fdf` | feat(core): reliability logic (ledger/retry/dedup/RBAC/target/priority) + 73 tes |
| `5bf5febcc060` | `57df07599687` | feat(persistence): SQLite WAL + migration + transactional publish + recovery (35 |
| `1f6777dfe4f8` | `9c0f3f53bc01` | docs: report skeleton (MauBCMH), test plan (30 acceptance + 16 chaos), demo runb |
| `88b3aca5586d` | `11780f63dcaa` | feat: CI workflow + BUILD guide; docs ARCHITECTURE/ERD(26)/SECURITY/SLIDES; seed |
| `ea7ec3efc564` | `f78c258df311` | feat(network-lab): UDP discovery + trust gate + real multicast ALERT_HINT + TCP- |
| `28f199b6d1d5` | `29eb15d8462a` | feat(dashboard): timeline/replay+chaos+reports+funnel (14 tests, browser-verifie |
| `251b2a8d1592` | `43c1a75f5ace` | feat(server): TLS gateway+RBAC+fanout+ACK/retry+recovery+WS status (52 tests); f |
| `df0241b20982` | `36ceaecc070e` | fix(persistence): peer-review M1 cross-device ack + L1 open-lock/WAL + L2 fixed- |
| `0e327ab839a9` | `c37ec9aff4d4` | docs: comprehensive README + Agents.md (orchestration/peer-review); gitignore de |
| `4d1bd6943510` | `2bd1875bf86b` | fix(client+simulator): peer-review M1 malformed-frame survival + L1 negotiated w |
| `f68f9e2310cf` | `c1c0df900344` | fix(server): peer-review S-01..S-10 (duplicate-ACK, state race, RBAC read-plane, |
| `9f0dad233c3b` | `94fd901d65ca` | build: pin core Maven plugins (resources 3.3.1 etc.) to Central-available versio |
| `9238f614d3df` | `181dd522fd86` | fix(client): deterministic reconnect-SYNC test (durability barrier + null-safe p |
| `c1dbab5b7433` | `e3c42460cb20` | docs: polish README/BUILD + ADR (8 records); seed_demo reconciled with server de |
| `d0f34dddee00` | `6a9fea6852fc` | docs: report Ch1-4 complete (1512 lines, real-number placeholders) + PERFORMANCE |
| `9c73a920d587` | `c1242199bc14` | feat(3D): realistic building modules — structure/facade/interior/safety/environm |
| `b4b8a4e96c80` | `4253b539ad02` | feat: weather module + real integration evidence (benchmark/chaos/demo) + reprod |
| `7550cea2e45e` | `7e2968b23e07` | docs: SECURITY-ASSESSMENT (control verification+STRIDE+RBAC+OWASP) + USER-GUIDE  |
| `69fbee891910` | `fece7128bd08` | docs: DEFENSE-QA (20 Q&A grounded) + CHAOS-DESIGN (C17-C28) + DEMO-VIDEO-SCRIPT  |
| `35ed1c8a7e87` | `7ec7c84cf632` | evidence: real chaos runs (lossy/delayed/duplicate, fleet 50) — retry+dedup unde |
| `9dfede11e42f` | `97877ac0a46f` | docs(report): fill Ch3 with REAL measured data — load-100 (p50=773/p95=1307/p99= |
| `8f01589b73cc` | `4a8774be36c4` | feat(3D): integrate 12 realistic building modules into main.js (build OK, LIVE-v |
| `17ea5340e05a` | `c0dd5fa6f5f8` | fix(test): resilient TlsLoopback teardown (UncheckedIOException on WAL race) + o |
| `ae9fd45118e0` | `8706c4edaee1` | fix(backend): H1-H4 audit findings (ACK-only-advances guards, reject re-auth, no |
| `4856b375c7f7` | `50bf558a3dfa` | docs(report): fill appendix with real code excerpts + reconcile final conclusion |
| `e459b05acb50` | `e1c1a1d0eae2` | feat(demo): generate verified 12:00 AEGIS demo video from real evidence + TTS vo |
| `0c8428cfe364` | `a40fab97e8cc` | fix(server): status feed generation counter + snapshot-first subscribe (CI WS fl |
