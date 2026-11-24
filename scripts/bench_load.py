#!/usr/bin/env python3
"""Benchmark driver extracted from bench-load.sh (no heredoc, testable)."""
import json
import os
import socket
import sqlite3
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ebsp

ebsp.install_signal_handlers()

FLEET = int(os.environ["BENCH_FLEET"])
DURATION = int(os.environ["BENCH_DURATION"])
SEED = int(os.environ["BENCH_SEED"])
TLS = int(os.environ.get("AEGIS_TLS_PORT", "9443"))
WS = int(os.environ.get("AEGIS_WS_PORT", "9444"))


def port_free(p):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", p))
        return True
    except OSError:
        return False
    finally:
        s.close()


def settle(db, timeout=40):
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

meta = ebsp.run_meta()
out = os.path.join(ebsp.REPO, "docs", "evidence", "benchmark", meta["runId"])
os.makedirs(out, exist_ok=True)
db = os.path.join(out, "bench.db")
log = os.path.join(out, "server.log")
simlog = os.path.join(out, "sim.log")
srv = op = sim = None
rc = None
try:
    srv, fleet = ebsp.seed_and_provision(db, log, n_sim=FLEET, tls_port=TLS, ws_port=WS)
    print("server pid=%s fleet=%d tls=%d ws=%d" % (srv.pid, len(fleet), TLS, WS))
    cmd = ebsp.sim_cmd(FLEET, "clean", seed=SEED, duration=DURATION, port=TLS, stagger_ms=5)
    with open(simlog, "w") as lf:
        sim = subprocess.Popen(cmd, cwd=ebsp.REPO, stdout=lf, stderr=subprocess.STDOUT)
    t0 = time.time()
    authed = 0
    while time.time() - t0 < 40:
        authed = len(ebsp.audit_rows(db, actions=["AUTH_DEVICE_SUCCESS"], limit=FLEET + 50))
        if authed >= FLEET:
            break
        time.sleep(0.5)
    auth_wait = round(time.time() - t0, 2)
    print("fleet authenticated: %d/%d in %.1fs" % (authed, FLEET, auth_wait))
    op = ebsp.Operator("admin", "admin-demo-secret", port=TLS)
    publish_at = ebsp.now_iso()
    pub = op.publish(category="FIRE_DRILL", priority="CRITICAL",
                     title="BENCH %d CLEAN" % FLEET,
                     instruction="Benchmark drill: acknowledge immediately.",
                     simulation=True)
    print("publish:", {k: pub.get(k) for k in ("ok", "alertId", "deliveryCount")})
    rc = sim.wait(timeout=DURATION + 120)
    text = open(simlog).read()
    summary = ebsp.parse_sim_summary(text)
    settled = settle(db, timeout=40)
    stats = ebsp.db_stats(db)
    result = {
        "meta": meta,
        "config": {"fleet": FLEET, "scenario": "clean", "seed": SEED,
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
        "headline": {
            "latencyMs": stats["latencyMsFromDb"],
            "ackReceivedRate": stats["ackReceivedRate"],
            "ackReceivedRatePct": (round(100 * stats["ackReceivedRate"], 2)
                                   if stats["ackReceivedRate"] is not None else None),
            "retriesFromAttempts": stats["retriesFromAttempts"],
            "reconnects": summary.get("reconnects"),
            "deliveriesTotal": stats["deliveriesTotal"],
        },
        "artifacts": {"db": db, "serverLog": log, "simLog": simlog},
    }
    with open(os.path.join(out, "summary.json"), "w") as f:
        json.dump(result, f, indent=1)
    latest = os.path.join(ebsp.REPO, "docs", "evidence", "benchmark", "latest")
    if os.path.islink(latest) or os.path.exists(latest):
        os.remove(latest)
    os.symlink(meta["runId"], latest)
    print("BENCH RESULT " + json.dumps(result["headline"]))
    print("evidence: " + out)
    if rc != 0:
        print("ERROR: simulator exit code %s" % rc)
        sys.exit(1)
    if summary.get("agentsFinished") != FLEET:
        print("ERROR: agentsFinished != fleet")
        sys.exit(1)
finally:
    if op is not None:
        op.close()
    if sim is not None and sim.poll() is None:
        sim.terminate()
        try:
            sim.wait(timeout=10)
        except subprocess.TimeoutExpired:
            sim.kill()
    if srv is not None:
        srv.stop()
        print("server stopped (pid=%s)" % srv.pid)
    checkpoint(db)
