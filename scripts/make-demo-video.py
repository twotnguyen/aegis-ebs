#!/usr/bin/env python3
"""Tạo video demo AEGIS-EBS 12 phút từ evidence thật (không quay màn hình giả).

Pipeline: Pillow render slide PNG (tiếng Việt, font Arial Unicode) -> `say -v Linh`
voiceover -> ffmpeg ghép ảnh tĩnh + audio thành MP4. Slide twin dùng ẢNH LIVE THẬT
docs/evidence/dashboard/twin-live-overview.png. Mọi số liệu lấy từ docs/evidence/.
Chạy: python3 scripts/make-demo-video.py
"""
from __future__ import annotations
import json
import subprocess
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "docs/evidence/video"
FRAMES = OUT / "frames"
AUDIO = OUT / "audio"
FONT_PATH = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
FONT_BOLD = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
W, H = 1280, 720
FPS = 24

SLIDES: list[dict[str, Any]] = [
    dict(t=0, title="AEGIS-EBS — Emergency Broadcast System",
         lines=["Trung tâm chỉ huy truyền thông khẩn cấp cho tòa nhà thông minh",
                "Mô phỏng huấn luyện — SIMULATION / TRAINING",
                "CMP180 · Lập trình mạng · HUTECH"],
         voice="Chào bạn. Đây là AEGIS EBS, hệ thống truyền thông khẩn cấp mô phỏng cho tòa nhà thông minh, đồ án môn Lập trình mạng CMP180. Toàn bộ nội dung là mô phỏng huấn luyện, không thay thế hệ thống báo cháy thật."),
    dict(t=45, title="Kiến trúc bốn mặt phẳng",
         lines=["Command · Delivery · Persistence/Observability · Discovery/Experiment",
                "Kênh chính: TCP/TLS có xác thực", "UDP discovery + multicast hint chỉ là advisory",
                "8 module Java + dashboard Three.js"],
         voice="Hệ thống gồm bốn mặt phẳng: điều hành, phân phối, lưu trữ quan sát, và khám phá thí nghiệm. Kênh chính là TCP với TLS, còn UDP discovery và multicast hint chỉ mang tính tư vấn, không bao giờ là lệnh tin cậy."),
    dict(t=90, title="Giao thức EBSP/2.0",
         lines=["uint32 big-endian L (1..65536) + strict UTF-8 JSON",
                "28 message types · version = 2", "readFully xử lý partial/coalesced frame",
                "Oversize bị chặn TRƯỚC khi cấp phát"],
         voice="Giao thức ứng dụng EBSP phiên bản 2 dùng khung độ dài 4 byte big endian, tối đa 64 ký lô byte, chứa JSON UTF-8 nghiêm ngặt. Bộ giải mã đọc đủ byte với readFully và từ chối khung quá cỡ trước khi cấp phát bộ nhớ."),
    dict(t=150, title="Fleet 100 thiết bị kết nối TLS",
         lines=["Benchmark runId 276dedf5 · commit c1dbab5", "100/100 thiết bị xác thực",
                "100 alert · 100 ACK_RECEIVED · 0 retry", "p50 773 ms · p95 1307 ms · p99 1334 ms"],
         voice="Trong lần chạy chuẩn với một trăm thiết bị mô phỏng, tất cả đều xác thực thành công. Một trăm cảnh báo được phát và nhận đủ, không cần retry. Độ trễ từ phát đến xác nhận có p năm mươi là 773 mili giây và p chín mươi chín là 1334 mili giây."),
    dict(t=210, title="Fire drill CRITICAL tầng 5",
         lines=["4 alert ACTIVE trong ảnh LIVE: F05 FIRE_DRILL, F04 HAZMAT, F07 SECURITY, F02 INFO",
                "ACK_RECEIVED (máy lưu bền) ≠ ACK_USER (người bấm)",
                "Rank guard: ACK chỉ tiến trạng thái",
                "Digital twin đổi màu zone theo severity"],
         voice="Khi operator phát cảnh báo cháy giả lập tầng năm, tòa nhà số đổi màu zone theo mức nghiêm trọng. Hệ thống phân biệt xác nhận đã lưu bền của máy và xác nhận của người dùng. Rank guard bảo đảm trạng thái chỉ tiến, không bao giờ lùi."),
    dict(t=270, title="Reliability engine: retry & dedup",
         lines=["Retry 3s / 6s / 12s + jitter, tối đa 3 lần", "Cùng deliveryId, messageId mới mỗi attempt",
                "requestId UNIQUE → idempotent publish", "DedupIndex + inbox bền vững → đúng 1 popup"],
         voice="Bộ máy tin cậy dùng retry ba giây, sáu giây, mười hai giây kèm jitter, tối đa ba lần. Mỗi lần thử có message ID mới nhưng cùng delivery ID. request ID duy nhất và dedup phía nhận giúp người dùng thấy đúng một popup dù mạng gửi lại."),
    dict(t=330, title="Chaos C01–C05 — đo thật",
         lines=["lossy: 15 ACK drop → 15 retry, ACK 98%", "delayed: ACK 100%, p50 1585 ms",
                "duplicate: 22 ACK dedup, ACK 100%", "flaky: 21 disconnect → 21 reconnect",
                "mixed: 8 drop + 62 delay + 13 reconnect, ACK 100%"],
         voice="Năm kịch bản chaos đã chạy thật. Kịch bản mất ACK ghi nhận mười lượt drop được retry, tỷ lệ nhận 98 phần trăm. Kịch bản trễ vẫn đạt 100 phần trăm. Kịch bản trùng được khử trùng. Flaky và mixed cho thấy kết nối lại và đồng bộ hoạt động."),
    dict(t=400, title="Kiểm chứng H1–H4 từ audit chuyên sâu",
         lines=["H1 scheduleRetry + H2 markFailed: guard pre-ack", "H3: từ chối re-auth, tránh lộ cross-device",
                "H4: WS bounded async writer, không kẹt timer", "Reviewer độc lập: PASS · verify 347 test"],
         voice="Audit chuyên sâu phát hiện bốn vấn đề mức cao. Chúng tôi đã thêm guard cho retry và đánh dấu thất bại, chặn xác thực lại trên phiên đã đăng nhập, và chuyển ghi WebSocket sang hàng đợi bất đồng bộ. Reviewer độc lập đã chạy lại và cho kết quả đạt."),
    dict(t=470, title="Digital twin LIVE (ảnh thật)", image=str(REPO / "docs/evidence/dashboard/twin-live-overview.png"),
         lines=["Server thật · fleet 40 · 4 cảnh báo ACTIVE", "Zone F05 critical · F04 warning · F07 emergency · F02 info",
                "Badge LIVE · Connected 40 · feed ws://localhost:9444", "Nguồn: docs/evidence/dashboard/twin-live-overview.png"],
         voice="Đây là ảnh chụp thật dashboard đang ở chế độ LIVE. Mười tầng, bốn mươi zone, bốn cảnh báo đang hoạt động. Màu của từng zone phản ánh trạng thái có thật từ server, không phải dữ liệu giả để trình diễn."),
    dict(t=540, title="Bảo mật & phân quyền",
         lines=["TLS pinning truststore · từ chối cert lạ", "RBAC 5 vai trò, scope từ CSDL, deny-by-default",
                "Argon2id · audit append-only · sanitize lỗi", "Residual: self-signed, Argon2 16 MiB, WS trusted-LAN"],
         voice="Bảo mật gồm ghim chứng chỉ TLS, phân quyền năm vai trò theo phạm vi lưu trong cơ sở dữ liệu, băm mật khẩu Argon2id, nhật ký kiểm toán chỉ thêm và làm sạch thông báo lỗi. Các hạn chế như chứng chỉ tự ký được nêu rõ."),
    dict(t=610, title="Network lab: TCP vs multicast",
         lines=["8 subscriber × 200 message", "TCP_FANOUT: 1600 gói / 823848 byte",
                "MULTICAST: 200 gói / 17892 byte", "Giảm 87,5% gói · 97,8% byte tại nguồn"],
         voice="Phòng thí nghiệm mạng so sánh ba chế độ. Multicast giảm 87,5 phần trăm số gói và 97,8 phần trăm số byte tại nguồn so với phát TCP từng bản. Đây là bằng chứng định lượng cho cây phân phối multicast trong Bài 8 của giáo trình."),
    dict(t=680, title="Kết luận & limits trung thực",
         lines=["347 test Java + 14 test dashboard · CI xanh", "ACK 2 tầng · retry idempotent · crash recovery",
                "Digital twin 3D · chaos · benchmark thật", "Single-node · multicast LAN · chưa đo 200–500 client"],
         voice="Tổng kết: ba trăm bốn mươi bảy test Java và mười bốn test dashboard đều xanh, CI thành công. Hệ thống có xác nhận hai tầng, retry idempotent, phục hồi sau sự cố, song sinh số 3D và đo lường thật. Chúng tôi thừa nhận giới hạn single node, multicast chỉ trong LAN, và chưa đo tải hai trăm đến năm trăm client."),
]

def font(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT_PATH, size)

def make_slide(index: int, s: dict) -> Path:
    img = Image.new("RGB", (W, H), (9, 15, 30))
    d = ImageDraw.Draw(img)
    # gradient accent
    for y in range(H):
        r = int(9 + 12 * y / H); g = int(15 + 20 * y / H); b = int(30 + 38 * y / H)
        d.line([(0, y), (W, y)], fill=(r, g, b))
    d.rounded_rectangle((48, 38, W - 48, 110), radius=18, fill=(18, 32, 58), outline=(56, 189, 248), width=2)
    d.text((72, 58), f"{s['t']//60:02d}:{s['t']%60:02d}  {s['title']}", font=font(34, True), fill=(224, 242, 255))
    if s.get("image") and Path(s["image"]).exists():
        shot = Image.open(s["image"]).convert("RGB")
        shot.thumbnail((900, 320))
        img.paste(shot, ((W - shot.width) // 2, 116))
        base = 116 + shot.height + 12
        line_gap, box_h, line_font = 58, 48, font(19)
    else:
        base = 165
        line_gap, box_h, line_font = 76, 62, font(23)
    for i, line in enumerate(s["lines"]):
        d.rounded_rectangle((70, base + i * line_gap, W - 70, base + i * line_gap + box_h), radius=10,
                            fill=(16, 27, 48), outline=(30, 60, 90), width=1)
        d.text((92, base + i * line_gap + (box_h - 26) // 2), "• " + line, font=line_font, fill=(207, 226, 245))
    d.text((72, H - 48), "AEGIS-EBS · SIMULATION / TRAINING · CMP180", font=font(20), fill=(120, 170, 205))
    path = FRAMES / f"slide-{index:02d}.png"
    img.save(path)
    return path

def duration(path: Path) -> float:
    out = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries",
        "format=duration", "-of", "default=nw=1:nk=1", str(path)], text=True)
    return float(out.strip())

def main():
    OUT.mkdir(parents=True, exist_ok=True); FRAMES.mkdir(exist_ok=True); AUDIO.mkdir(exist_ok=True)
    # Voiceover thật bằng giọng tiếng Việt Linh
    for i, s in enumerate(SLIDES):
        wav = AUDIO / f"slide-{i:02d}.aiff"
        subprocess.run(["say", "-v", "Linh", "-o", str(wav), s["voice"]], check=True)
        s["_audio"] = wav
        s["_dur"] = duration(wav)
    # Slide cuối mỗi cảnh kéo tới timestamp tiếp theo; voiceover có thể ngắn hơn.
    concat = OUT / "concat.txt"
    with concat.open("w") as f:
        for i, s in enumerate(SLIDES):
            png = make_slide(i, s)
            seg = OUT / f"seg-{i:02d}.mp4"
            end = SLIDES[i+1]["t"] if i + 1 < len(SLIDES) else 720
            target = end - s["t"]
            # Ép đúng target seconds: audio ngắn thì apad, dài thì atrim cắt bớt.
            subprocess.run(["ffmpeg", "-y", "-v", "error", "-loop", "1", "-framerate", str(FPS), "-i", str(png),
                "-i", str(s["_audio"]), "-filter_complex",
                f"[1:a]apad,atrim=duration={target},asetpts=PTS-STARTPTS[a]",
                "-map", "0:v", "-map", "[a]", "-c:v", "libx264", "-tune", "stillimage",
                "-pix_fmt", "yuv420p", "-r", str(FPS), "-c:a", "aac", "-t", str(target), str(seg)], check=True)
            f.write(f"file '{seg}'\n")
    video = OUT / "aegis-ebs-demo.mp4"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0",
        "-i", str(concat), "-c", "copy", str(video)], check=True)
    meta = {"generatedFrom": "docs/DEMO-VIDEO-SCRIPT.md + docs/evidence/",
            "voice": "macOS say -v Linh (vi_VN)", "resolution": f"{W}x{H}", "fps": FPS,
            "durationSeconds": 720, "realEvidenceSlides": [8],
            "note": "Video dựng từ evidence thật (ảnh dashboard LIVE, số đo benchmark/chaos/verify) + voiceover TTS; KHÔNG phải bản quay màn hình live 12 phút. Dùng làm video nộp/bảo vệ có caption + số liệu đã kiểm chứng."}
    (OUT / "demo-video-meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2))
    print("VIDEO:", video, video.stat().st_size, "bytes")

if __name__ == "__main__":
    main()
