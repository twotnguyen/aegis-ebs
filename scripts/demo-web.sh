#!/usr/bin/env bash
# One-command, web-only AEGIS-EBS demo launcher (SIMULATION/TRAINING ONLY).
#
#   bash scripts/demo-web.sh
#
# Builds if needed, initialises aegis.db / TLS certs if missing, starts the real
# aegis-server JVM, the loopback web-operator bridge and the Vite dashboard,
# then prints http://localhost:5173. Nothing is committed; Ctrl-C stops all
# three processes and leaves the logs under docs/evidence/demo-web/.
#
# Env: AEGIS_TLS_PORT (9443), AEGIS_WS_PORT (9444), WEB_BRIDGE_PORT (9445).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"
export AEGIS_REPO="$REPO"

export AEGIS_TLS_PORT="${AEGIS_TLS_PORT:-9443}"
export AEGIS_WS_PORT="${AEGIS_WS_PORT:-9444}"
export WEB_BRIDGE_PORT="${WEB_BRIDGE_PORT:-9445}"
TLS_PORT="$AEGIS_TLS_PORT"
WS_PORT="$AEGIS_WS_PORT"
BRIDGE_PORT="$WEB_BRIDGE_PORT"
DASH_PORT=5173

EVIDENCE="$REPO/docs/evidence/demo-web"
mkdir -p "$EVIDENCE"

SERVER_PID=""
BRIDGE_PID=""
DASH_PID=""
CLEANED=0

kill_tree() {
  # Recurse first so npm -> sh -> vite children never survive the launcher.
  local pid="$1" signal="$2" child
  for child in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill_tree "$child" "$signal"
  done
  kill -s "$signal" "$pid" 2>/dev/null || true
}

alive() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

cleanup() {
  [ "$CLEANED" = "1" ] && return 0
  CLEANED=1
  trap - EXIT INT TERM
  local pid
  for pid in "$DASH_PID" "$BRIDGE_PID" "$SERVER_PID"; do
    alive "$pid" && kill_tree "$pid" TERM
  done
  sleep 1
  for pid in "$DASH_PID" "$BRIDGE_PID" "$SERVER_PID"; do
    alive "$pid" && kill_tree "$pid" KILL
  done
  echo "demo-web: stopped all processes; logs in $EVIDENCE"
}
trap 'cleanup' EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

wait_port() {
  # wait_port <port> <label> <seconds> [comma-separated pids to watch]
  python3 - "$1" "$2" "$3" "${4:-}" <<'PY'
import os, socket, sys, time
port, label, limit = int(sys.argv[1]), sys.argv[2], float(sys.argv[3])
pids = [int(p) for p in sys.argv[4].split(",") if p]
def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False
deadline = time.time() + limit
while time.time() < deadline:
    if pids and not all(alive(p) for p in pids):
        print("demo-web: %s process exited early" % label, file=sys.stderr)
        sys.exit(2)
    for host in ("127.0.0.1", "::1"):
        try:
            with socket.create_connection((host, port), timeout=0.5):
                sys.exit(0)
        except OSError:
            pass
    time.sleep(0.25)
print("demo-web: timeout waiting for %s on port %d" % (label, port), file=sys.stderr)
sys.exit(1)
PY
}

# ---- 1. build (only if classes are missing) --------------------------------
need_build=0
if [ ! -f aegis-server/target/classes/aegis/server/ServerMain.class ] || \
   [ ! -d aegis-server/target/classes ] || \
   [ ! -d aegis-persistence/target/classes ] || \
   [ ! -d aegis-core/target/classes ] || \
   [ ! -d aegis-protocol/target/classes ]; then
  need_build=1
fi
if [ "$need_build" = "1" ]; then
  echo "demo-web: building Java modules (first run)..."
  mvn -q -pl aegis-server -am -Dmaven.test.skip=true install
fi

# mdep.outputFile is resolved relative to the aegis-server module dir,
# so this writes aegis-server/cp.txt (NOT ./cp.txt at the repo root).
if [ "$need_build" = "1" ] || [ ! -f aegis-server/cp.txt ]; then
  echo "demo-web: generating aegis-server/cp.txt..."
  mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=cp.txt
fi
CP="$REPO/aegis-server/target/classes"
for module in persistence core protocol; do
  CP="$CP:$REPO/aegis-$module/target/classes"
done
CP="$CP:$(tr -d '\n' < "$REPO/aegis-server/cp.txt")"

# ---- 2. database + TLS identity --------------------------------------------
if [ ! -f aegis.db ]; then
  echo "demo-web: initialising aegis.db..."
  bash scripts/initdb.sh aegis.db
fi
if [ ! -f certs/aegis-server.p12 ] || [ ! -f certs/aegis-server.crt ] || \
   [ ! -f certs/aegis-truststore.p12 ]; then
  echo "demo-web: generating demo TLS certs..."
  bash scripts/gen-server-cert.sh
fi

# ---- 3. real server (seeds 480 devices with codes DEV-A-F01-A-01 ..) -------
java -cp "$CP" aegis.server.ServerMain \
  --db "$REPO/aegis.db" \
  --keystore "$REPO/certs/aegis-server.p12" --storepass aegis-demo \
  --tls-port "$TLS_PORT" --ws-port "$WS_PORT" \
  > "$EVIDENCE/server.log" 2>&1 &
SERVER_PID=$!
if ! wait_port "$TLS_PORT" "server" 90 "$SERVER_PID"; then
  echo "demo-web: server failed to start (see $EVIDENCE/server.log)"
  exit 1
fi
echo "demo-web: server up (pid $SERVER_PID, TLS $TLS_PORT, WS $WS_PORT)"

# ---- 4. loopback web bridge ------------------------------------------------
python3 "$SCRIPT_DIR/web-operator-bridge.py" > "$EVIDENCE/bridge.log" 2>&1 &
BRIDGE_PID=$!
if ! wait_port "$BRIDGE_PORT" "bridge" 20 "$BRIDGE_PID"; then
  echo "demo-web: bridge failed to start (see $EVIDENCE/bridge.log)"
  exit 1
fi
echo "demo-web: bridge up (pid $BRIDGE_PID, http://127.0.0.1:$BRIDGE_PORT)"

# ---- 5. dashboard ----------------------------------------------------------
if [ ! -d aegis-dashboard/node_modules ]; then
  echo "demo-web: npm install (first run)..."
  (cd aegis-dashboard && npm install)
fi
(cd aegis-dashboard && npm run dev -- --host 127.0.0.1 --port "$DASH_PORT" --strictPort) \
  > "$EVIDENCE/dashboard.log" 2>&1 &
DASH_PID=$!
if ! wait_port "$DASH_PORT" "dashboard" 90 "$DASH_PID"; then
  echo "demo-web: dashboard failed to start (see $EVIDENCE/dashboard.log)"
  exit 1
fi

echo ""
echo "AEGIS EBS web demo READY"
echo "  Dashboard : http://localhost:$DASH_PORT   <-- nhấn tab Operator"
echo "  Bridge    : http://127.0.0.1:$BRIDGE_PORT/api/health"
echo "  Logs      : $EVIDENCE/{server,bridge,dashboard}.log"
echo "  Dừng      : Ctrl-C (kills server + bridge + dashboard)"
echo ""

while alive "$SERVER_PID" && alive "$BRIDGE_PID" && alive "$DASH_PID"; do
  sleep 1
done
exit 1
