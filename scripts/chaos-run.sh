#!/usr/bin/env bash
# AEGIS-EBS chaos runs (SIMULATION/TRAINING ONLY).
# For each fault scenario: fresh DB + seeded world + exact N-device fleet,
# one building-wide FIRE_DRILL publish while the simulator fleet injects
# faults (drop/delay/duplicate ACKs, disconnects). Waits for the retry budget
# to settle, then exports summary + db_stats + audit evidence.
# Evidence: docs/evidence/chaos/<scenario>.json (headline contract) and
#           docs/evidence/chaos/runs/<scenario>-<runId>/{db,logs,audit.json}.
#
# Usage:   scripts/chaos-run.sh [scenario ...]   (default: mixed lossy delayed duplicate flaky)
# Env:     FLEET (100) DURATION (60) SEED (42) AEGIS_TLS_PORT (9443)
#          AEGIS_WS_PORT (9444) FORCE_BUILD=1 to rebuild classes+classpath.
# No Java sources are modified. Server process is stopped after EVERY scenario.
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

exec python3 - <<'PY'
import json, os, socket, sqlite3, subprocess, sys, time, traceback
sys.path.insert(0, os.path.join(os.environ["AEGIS_REPO"], "target", "e2e-dev"))
import ebsp

FLEET = int(os.environ["CHAOS_FLEET"])
DURATION = int(os.environ["CHAOS_DURATION"])
SEED = int(os.environ["CHAOS_SEED"])
SCENARIOS = os.environ["CHAOS_SCENARIOS"].split()
TLS = int(os.environ.get("AEGIS_TLS_PORT", "9443"))
WS = int(os.environ.get("AEGIS_WS_PORT", "9444"))
INTERESTING = {"RETRY", "DELIVERY_FAILED", "RECOVERY", "DEDUP_SUPPRESS",
               "SYNC", "SUPERSEDED", "ALERT_FULLY_ACKED", "PRE_AUTH_EXPIRED"}

def port_free(p):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", p)); return True
    except OSError:
        return False
    finally:
        s.close()

def settle(db, timeout=50):
    end = time.time() + timeout
    st = {}
    while time.time() < end:
        st = ebsp.db_stats(db)["deliveriesByState"]
        if not any(st.get(s, 0) for s in ("PENDING", "SENT", "RETRY_SCHEDULED")):
            return st
        time.sleep(1.0)
    return st

def histogram(db):
    con = sqlite3.connect(db)
    rows = con.execute("SELECT attempt_count, COUNT(*) FROM deliveries "
                       "GROUP BY attempt_count ORDER BY attempt_count").fetchall()
    con.close()
    return {str(a): n for a, n in rows}

def checkpoint(db):
    try:
        con = sqlite3.connect(db)
        con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        con.close()
        for ext in ("-wal", "-shm"):
            p = db + ext
            if os.path.exists(p) and os.path.getsize(p) == 0:
                os.remove(p)
    except Exception as e:
        print("checkpoint warn:", e)

for p in (TLS, WS):
    if not port_free(p):
        print("ERROR: port %d already busy — stop the other server first" % p)
        sys.exit(3)

chaos_dir = os.path.join(ebsp.REPO, "docs", "evidence", "chaos")
os.makedirs(os.path.join(chaos_dir, "runs"), exist_ok=True)
failed = []
for sc in SCENARIOS:
    meta = ebsp.run_meta()
    run_dir = os.path.join(chaos_dir, "runs", "%s-%s" % (sc, meta["runId"][:8]))
    os.makedirs(run_dir, exist_ok=True)
    db = os.path.join(run_dir, "chaos.db")
    log = os.path.join(run_dir, "server.log")
    simlog = os.path.join(run_dir, "sim.log")
    srv = op = sim = None
    rc = None
    print("=== chaos scenario=%s fleet=%d duration=%ds seed=%d ===" % (sc, FLEET, DURATION, SEED))
    try:
        srv, fleet = ebsp.seed_and_provision(db, log, n_sim=FLEET, tls_port=TLS, ws_port=WS)
        cmd = ebsp.sim_cmd(FLEET, sc, seed=SEED, duration=DURATION, port=TLS, stagger_ms=5)
        with open(simlog, "w") as lf:
            sim = subprocess.Popen(cmd, cwd=ebsp.REPO, stdout=lf, stderr=subprocess.STDOUT)
        t0 = time.time()
        authed = 0
        while time.time() - t0 < 40:
            authed = len(ebsp.audit_rows(db, actions=["AUTH_DEVICE_SUCCESS"], limit=FLEET + 200))
            if authed >= FLEET:
                break
            time.sleep(0.5)
        auth_wait = round(time.time() - t0, 2)
        op = ebsp.Operator("admin", "admin-demo-secret", port=TLS)
        publish_at = ebsp.now_iso()
        pub = op.publish(category="FIRE_DRILL", priority="CRITICAL",
                         title="CHAOS %s %d" % (sc.upper(), FLEET),
                         instruction="Chaos drill: faults injected by simulator.",
                         simulation=True)
        print("publish:", {k: pub.get(k) for k in ("ok", "alertId", "deliveryCount")},
              "authed=%d/%d (%.1fs)" % (authed, FLEET, auth_wait))
        rc = sim.wait(timeout=DURATION + 120)
        summary = ebsp.parse_sim_summary(open(simlog).read())
        settled = settle(db, timeout=50)
        stats = ebsp.db_stats(db)
        audit_all = ebsp.audit_rows(db, limit=20000)
        with open(os.path.join(run_dir, "audit.json"), "w") as f:
            json.dump(audit_all, f, indent=1)
        sample = [r for r in audit_all
                  if r.get("action") in INTERESTING or r.get("outcome") not in ("OK", None)][:400]
        result = {
            "meta": meta,
            "config": {"fleet": FLEET, "scenario": sc, "seed": SEED,
                       "durationSeconds": DURATION, "staggerMs": 5,
                       "tlsPort": TLS, "wsPort": WS,
                       "retryPolicy": "maxRetries=3 base=3000ms (3s/6s/12s)"},
            "publish": {"at": publish_at, "payload": pub,
                        "fleetAuthWaitSeconds": auth_wait,
                        "authenticatedAtPublish": authed},
            "simExitCode": rc,
            "simSummary": summary,
            "settledStates": settled,
            "dbStats": stats,
            "attemptHistogram": histogram(db),
            "auditCounts": stats["auditCounts"],
            "auditSample": sample,
            "auditTotalRows": len(audit_all),
            "headline": {
                "latencyMs": stats["latencyMsFromDb"],
                "ackReceivedRate": stats["ackReceivedRate"],
                "ackReceivedRatePct": (round(100 * stats["ackReceivedRate"], 2)
                                       if stats["ackReceivedRate"] is not None else None),
                "retriesFromAttempts": stats["retriesFromAttempts"],
                "reconnects": summary.get("reconnects"),
                "faultDisconnects": summary.get("disconnects"),
                "duplicatePushes": summary.get("duplicatePushes"),
                "duplicateAcks": summary.get("duplicateAcks"),
                "acksDropped": summary.get("acksDropped"),
                "acksDelayed": summary.get("acksDelayed"),
                "deliveriesByState": stats["deliveriesByState"],
            },
            "artifacts": {"db": db, "serverLog": log, "simLog": simlog,
                          "auditFull": os.path.join(run_dir, "audit.json")},
        }
        with open(os.path.join(chaos_dir, sc + ".json"), "w") as f:
            json.dump(result, f, indent=1)
        print("CHAOS %s RESULT %s" % (sc, json.dumps(result["headline"])))
        if rc != 0:
            failed.append(sc); print("ERROR: simulator exit code %s" % rc)
    except Exception as e:
        failed.append(sc)
        traceback.print_exc()
        with open(os.path.join(chaos_dir, sc + ".json"), "w") as f:
            json.dump({"meta": meta, "scenario": sc, "error": repr(e),
                       "simExitCode": rc}, f, indent=1)
    finally:
        if op is not None:
            op.close()
        if sim is not None and sim.poll() is None:
            sim.terminate()
            try: sim.wait(timeout=10)
            except subprocess.TimeoutExpired: sim.kill()
        if srv is not None:
            srv.stop()
            print("server stopped (pid=%s)" % srv.pid)
        checkpoint(db)

print("chaos done; failed=%s" % failed)
sys.exit(1 if failed else 0)
PY
