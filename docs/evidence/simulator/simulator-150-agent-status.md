# Simulator 150-agent — trạng thái bằng chứng

> Bổ sung cho TODO cũ trong `docs/BUILD.md` §2.2. **Không có** file log/report của một lần chạy đủ 150 agent; tài liệu này ghi rõ cái đã kiểm chứng và cái còn thiếu, không bịa runId.

## Đã kiểm chứng (2026-11-21, máy tham chiếu macOS 15.7.3 / Java 24.0.1)

- Trước khi đăng ký fleet: 2/2 agent bị từ chối (`auth rejections (permanent)`).
- Sau khi nạp hash Argon2id của `device-demo-secret` và 150 dòng `devices` + `zone_devices` (F01-A): `connects ok/failed = 2/0`, `heartbeats answered = 2`, exit 0; `PRAGMA foreign_key_check` sạch.
- Nguồn: recipe trong `docs/BUILD.md` §2.2 (jshell → SQL → simulator). Đây là phép thử đăng ký fleet, **không phải** lần chạy tải 150 agent end-to-end.

## Chưa có

- Log/summary của lần chạy `SimulatorMain --agents 150` kèm runId, commit SHA, cấu hình máy.
- Số liệu p50/p95/p99 ở fleet 150. Bằng chứng tải hiện có là fleet 100, runId `276dedf5` (`docs/evidence/benchmark/276dedf5-fa83-4022-b7be-f3a47fccf591/summary.json`).

## Tái lập để đóng TODO

Chạy đúng 3 bước trong `docs/BUILD.md` §2.2 với `--agents 150`, lưu stdout vào `docs/evidence/simulator/<runId>/sim.log` kèm `summary.json` và metadata máy. Chỉ đổi trạng thái thành PASS khi các file đó tồn tại.

---

## English status

Verified: fleet registration recipe worked (2/2 agents connected after registration; 150 rows inserted; FK clean). **Not captured:** an end-to-end 150-agent simulator run with runId/commit/machine metadata; the 100-client benchmark (runId `276dedf5`) is the available load evidence. Reproduce via `docs/en/BUILD.md` §2.2 and store logs under `docs/evidence/simulator/<runId>/` before claiming PASS.
