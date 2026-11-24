#!/usr/bin/env bash
# Launcher 1 lệnh cho 1 device JavaFX nhận cảnh báo theo zone.
# Keep this launcher executable in the repository.
# SIMULATION/TRAINING ONLY — không phải hệ thống báo động thật.
#
#   bash scripts/demo-zone-client.sh [DEVICE_CODE] [SERVER_HOST] [TLS_PORT] [TRUSTSTORE] [TRUSTSTORE_PASSWORD] [INBOX]
#
# Mặc định: DEV-A-F05-A-01, 127.0.0.1, 9443, certs/aegis-truststore.p12,
# aegis-demo, client-inbox-<DEVICE_CODE>.db.
# Chạy từ bất cứ đâu: script luôn cd về gốc repo trước khi gọi Maven.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"

usage() {
  cat <<'EOF'
Usage: bash scripts/demo-zone-client.sh [DEVICE_CODE] [SERVER_HOST] [TLS_PORT] [TRUSTSTORE] [TRUSTSTORE_PASSWORD] [INBOX]

Khởi động aegis-client (JavaFX desktop) cho MỘT device, kết nối TLS tới aegis-server.
Server phải đang chạy và máy này cần màn hình thật (JavaFX không headless).

Tham số (có thể bỏ qua từ trái sang phải để dùng mặc định):
  1  DEVICE_CODE          mặc định DEV-A-F05-A-01 (phải là device có thật trong server seed)
  2  SERVER_HOST          mặc định 127.0.0.1 (Tailscale: IP tailnet của máy server)
  3  TLS_PORT             mặc định 9443
  4  TRUSTSTORE           mặc định certs/aegis-truststore.p12 (đường dẫn tương đối tính từ gốc repo)
  5  TRUSTSTORE_PASSWORD  mặc định aegis-demo
  6  INBOX                mặc định client-inbox-<DEVICE_CODE>.db (SQLite, tương đối tính từ gốc repo)

Biến môi trường:
  DEVICE_SECRET          mặc định device-demo-secret (credential seed demo của server)
  USER_ID                UUID user enabled cho ACK_USER (tự lấy từ aegis.db nếu có)

Ví dụ:
  bash scripts/demo-zone-client.sh
  bash scripts/demo-zone-client.sh DEV-A-F05-B-02 127.0.0.1 9443
  bash scripts/demo-zone-client.sh DEV-A-F05-A-01 100.x.y.z 9443

Lưu ý: CLI của client dùng --device-id, nhưng payload AUTH_DEVICE gửi lên server
là {deviceCode, credential}; server mới là nơi resolve device -> floor/zone và
quyết định ALERT_PUSH tới thiết bị nào. AlertRecord ở client KHÔNG chứa floor/zone.

SIMULATION/TRAINING ONLY. Nhấn Ctrl+C để đóng cửa sổ client.
EOF
}

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
  esac
done

DEVICE_CODE="${1:-DEV-A-F05-A-01}"
SERVER_HOST="${2:-127.0.0.1}"
TLS_PORT="${3:-9443}"
TRUSTSTORE="${4:-certs/aegis-truststore.p12}"
TS_PASSWORD="${5:-aegis-demo}"
INBOX="${6:-client-inbox-${DEVICE_CODE}.db}"
DEVICE_SECRET="${DEVICE_SECRET:-device-demo-secret}"
USER_ID="${USER_ID:-}"
if [ -z "$USER_ID" ] && [ -f "$REPO/aegis.db" ] && command -v sqlite3 >/dev/null 2>&1; then
  USER_ID="$(sqlite3 "$REPO/aegis.db" \
    "SELECT id FROM users WHERE username='employee' LIMIT 1" 2>/dev/null || true)"
fi
USER_ID="${USER_ID:-USER-SIM-0001}"

if [ -z "$DEVICE_CODE" ] || [ -z "$SERVER_HOST" ]; then
  echo "demo-zone-client: DEVICE_CODE và SERVER_HOST không được rỗng (xem --help)" >&2
  exit 2
fi
case "$TLS_PORT" in
  ''|*[!0-9]*) echo "demo-zone-client: TLS_PORT phải là số: $TLS_PORT" >&2; exit 2 ;;
esac

# javafx:run fork với working dir = aegis-client/, nên mọi path phải là TUYỆT ĐỐI.
case "$TRUSTSTORE" in
  /*) ;;
  *) TRUSTSTORE="$REPO/$TRUSTSTORE" ;;
esac
case "$INBOX" in
  /*) ;;
  *) INBOX="$REPO/$INBOX" ;;
esac

if [ ! -f "$TRUSTSTORE" ]; then
  echo "demo-zone-client: không thấy truststore: $TRUSTSTORE" >&2
  echo "demo-zone-client: sinh cert demo bằng: bash scripts/gen-server-cert.sh" >&2
  exit 1
fi

echo "demo-zone-client: device=$DEVICE_CODE -> tls://$SERVER_HOST:$TLS_PORT"
echo "demo-zone-client: truststore=$TRUSTSTORE inbox=$INBOX"
echo "demo-zone-client: SIMULATION/TRAINING ONLY — không phải báo động thật"

exec mvn -pl aegis-client javafx:run -Djavafx.args="--host $SERVER_HOST --port $TLS_PORT --device-id $DEVICE_CODE --user-id $USER_ID --device-secret $DEVICE_SECRET --truststore $TRUSTSTORE --truststore-password $TS_PASSWORD --inbox $INBOX"
