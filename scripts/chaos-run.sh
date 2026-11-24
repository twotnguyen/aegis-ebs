#!/usr/bin/env bash
# AEGIS-EBS chaos runs (SIMULATION/TRAINING ONLY).
# This wrapper stays executable; the driver lives in scripts/chaos_run.py.
# Usage: scripts/chaos-run.sh [scenario ...]
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"
export AEGIS_REPO="$REPO"
export CHAOS_FLEET="${FLEET:-100}" CHAOS_DURATION="${DURATION:-60}" CHAOS_SEED="${SEED:-42}"
export CHAOS_SCENARIOS="${*:-mixed lossy delayed duplicate flaky}"

if [ ! -f certs/aegis-server.p12 ] || [ ! -f certs/aegis-truststore.p12 ] || [ ! -f certs/aegis-server.crt ]; then
  bash scripts/gen-server-cert.sh
fi
if [ "${FORCE_BUILD:-0}" = "1" ] || [ ! -f aegis-server/target/classes/aegis/server/ServerMain.class ] || [ ! -f aegis-server/target/cp-server.txt ]; then
  mvn -q -pl aegis-server -am -Dmaven.test.skip=true install
  mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=target/cp-server.txt
fi
if [ "${FORCE_BUILD:-0}" = "1" ] || [ ! -f aegis-simulator/target/classes/aegis/simulator/SimulatorMain.class ] || [ ! -f aegis-simulator/target/cp-simulator.txt ]; then
  mvn -q -pl aegis-simulator -am -Dmaven.test.skip=true install
  mvn -q -pl aegis-simulator dependency:build-classpath -Dmdep.outputFile=target/cp-simulator.txt
fi

python3 "$SCRIPT_DIR/chaos_run.py" &
PY_PID=$!
trap 'kill -TERM "$PY_PID" 2>/dev/null || true; wait "$PY_PID" 2>/dev/null || true; exit 143' INT TERM
wait "$PY_PID"
