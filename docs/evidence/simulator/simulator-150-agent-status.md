# Simulator 150-agent — trạng thái bằng chứng

> Cập nhật: đã có capture thật, runId `sim150-1795689017`. Bản cũ ghi nhận "không có capture" chỉ còn là lịch sử.

## Capture chính thức

- **runId:** `sim150-1795689017` · commit `ff41a0b` · seed 42 · scenario `clean` · `--agents 150` · duration 30 s.
- **Server:** fresh clone từ runId `fresh-1795688539`, TLS `19443`, WS `19444`, DB đã seed 480 device + 150 device `SIM-DEV-*`.
- **Fleet:** `SIM-DEV-0001..0150` trong zone `F01-A`; hash Argon2id `m=16384,t=2,p=1` sinh bằng `Argon2idHasher`, `verify=true`; 150 dòng `devices` + 150 dòng `zone_devices`, `foreign_key_check` sạch.
- **Kết quả:** `agents started/finished 150/150`, `connects ok 150`, `auth rejections (permanent) 0`, `connection errors 0`, `heartbeats answered 450`, `simExitCode 0`.
- **Hạn chế đã ghi rõ:** 88 lỗi trước TLS handshake (87 ở lần chạy lại warm-up) được retry thành công; không publish alert nên `alerts received / ACK_RECEIVED sent = 0` — capture này chứng minh đăng ký, xác thực và sống của fleet 150 agent, không phải fanout 150 delivery.

Bằng chứng thô: `sim150.log`, `sim150-rerun.log`, `server.log`; số liệu có cấu trúc: `summary.json` trong thư mục này.

## Tái lập

Chạy 3 bước trong `docs/BUILD.md` §2.2 trên server đã seed, với `--agents 150 --scenario clean --seed 42`, lưu stdout + metadata máy vào `docs/evidence/simulator/<runId>/`.

---

## English status

Official capture: runId `sim150-1795689017`, seed 42, 150/150 agents finished, 150 successful connects, 0 permanent auth rejections, 450 heartbeats. 88 pre-handshake failures were retried successfully; no alert was published, so delivery/ACK counters are 0 by design. Raw logs and `summary.json` are in this folder.
