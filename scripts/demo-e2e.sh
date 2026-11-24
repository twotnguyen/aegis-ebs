#!/usr/bin/env bash
# AEGIS-EBS end-to-end demo (SIMULATION/TRAINING ONLY).
# Device/operator actors come from the tracked scripts/ebsp.py module.
# Deterministic device actors (tracked scripts/ebsp.py) against a real
# aegis-server JVM. Phases:
#   1 fanout+ACK   : building-wide FIRE_DRILL -> 8 devices, 7 auto-ACK, 1 silent
#   2 retry->FAILED: silent device burns the 3-retry budget (3s/6s/12s windows)
#   3 reconnect    : silent device reconnects with durable inbox, SYNC + late ACK
#   4 recovery     : publish while fleet offline, SIGKILL server, restart,
#                    RecoveryScanner re-enqueues, fleet reconnects and ACKs
#   5 RBAC deny    : EMPLOYEE publish denied, FLOOR_MANAGER out-of-scope denied,
#                    SECURITY_OPERATOR building-wide allowed (positive control)
#   6 WS snapshot  : status/1 feed snapshot + deltas captured
# Evidence: docs/evidence/demo/<runId>/{demo.json,demo.log,ws-snapshot.json,
#           demo.db,server.log,inbox/*.json}. Server always stopped on exit.
#
# Usage: scripts/demo-e2e.sh
# Env:   N_SIM (6) N_REAL (2) AEGIS_TLS_PORT (9443) AEGIS_WS_PORT (9444)
#        FORCE_BUILD=1 to rebuild classes+classpath.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO"
export AEGIS_REPO="$REPO"
export DEMO_N_SIM="${N_SIM:-6}" DEMO_N_REAL="${N_REAL:-2}"

if [ ! -f certs/aegis-server.p12 ] || [ ! -f certs/aegis-truststore.p12 ] || [ ! -f certs/aegis-server.crt ]; then
  bash scripts/gen-server-cert.sh
fi
if [ "${FORCE_BUILD:-0}" = "1" ] || [ ! -f aegis-server/target/classes/aegis/server/ServerMain.class ] || [ ! -f aegis-server/target/cp-server.txt ]; then
  mvn -q -pl aegis-server -am -Dmaven.test.skip=true install
  mvn -q -pl aegis-server dependency:build-classpath -Dmdep.outputFile=target/cp-server.txt
fi

exec python3 - <<'PY'
import json, os, socket, sqlite3, sys, time
sys.path.insert(0, os.path.join(os.environ["AEGIS_REPO"], "scripts"))
import ebsp
ebsp.install_signal_handlers()

N_SIM = int(os.environ["DEMO_N_SIM"])
N_REAL = int(os.environ["DEMO_N_REAL"])
TLS = int(os.environ.get("AEGIS_TLS_PORT", "9443"))
WS = int(os.environ.get("AEGIS_WS_PORT", "9444"))
SILENT = "REAL-DEV-%04d" % N_REAL          # last real device stays silent in phase 1
TERMINAL = ("RECEIVED", "ACKNOWLEDGED", "FAILED", "CANCELLED", "EXPIRED")

def port_free(p):
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", p)); return True
    except OSError:
        return False
    finally:
        s.close()

def q(db, sql, args=()):
    con = sqlite3.connect(db, timeout=30)
    con.row_factory = sqlite3.Row
    rows = [dict(r) for r in con.execute(sql, args)]
    con.close()
    return rows

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
out = os.path.join(ebsp.REPO, "docs", "evidence", "demo", meta["runId"])
os.makedirs(os.path.join(out, "inbox"), exist_ok=True)
db = os.path.join(out, "demo.db")
log = os.path.join(out, "server.log")
trace_f = open(os.path.join(out, "demo.log"), "w")
phases = []

def trace(msg):
    line = "%s %s" % (ebsp.now_iso(), msg)
    print(line)
    trace_f.write(line + "\n")
    trace_f.flush()

def phase(name, details, t0, start_epoch):
    rec = {"name": name, "startedAt": t0, "endedAt": ebsp.now_iso(),
           "seconds": round(time.time() - start_epoch, 2)}
    rec.update(details)
    phases.append(rec)
    trace("PHASE %s done (%.1fs)" % (name, rec["seconds"]))
    return rec

def inbox_path(code):
    return os.path.join(out, "inbox", code + ".json")

devs = {}
def connect(code, auto_ack=True, name=None):
    d = ebsp.Device(code, auto_ack=auto_ack, inbox_file=inbox_path(code),
                    name=name or code, port=TLS)
    devs[code] = d
    return d

def disconnect_all():
    for d in devs.values():
        d.disconnect()
    devs.clear()

def alert_states(aid):
    rows = q(db, "SELECT state, COUNT(*) n FROM deliveries WHERE alert_id=? GROUP BY state", (aid,))
    return {r["state"]: r["n"] for r in rows}

def wait_acks(aid, n, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        rows = q(db, "SELECT COUNT(*) n FROM device_acks k JOIN deliveries d ON d.id=k.delivery_id "
                     "WHERE d.alert_id=? AND k.ack_kind='RECEIVED'", (aid,))
        if rows[0]["n"] >= n:
            return rows[0]["n"]
        time.sleep(0.5)
    return q(db, "SELECT COUNT(*) n FROM device_acks k JOIN deliveries d ON d.id=k.delivery_id "
                 "WHERE d.alert_id=? AND k.ack_kind='RECEIVED'", (aid,))[0]["n"]

def audit_for(aid, actions=None):
    rows = ebsp.audit_rows(db, actions=actions, limit=20000)
    return [r for r in rows if aid in r.values()]

srv = srv2 = op = None
checks = {}
alerts = {}
try:
    srv, fleet = ebsp.seed_and_provision(db, log, n_sim=N_SIM, n_real=N_REAL,
                                         tls_port=TLS, ws_port=WS)
    codes = [f["code"] for f in fleet]
    trace("server pid=%s fleet=%d (%s) tls=%d ws=%d" % (srv.pid, len(fleet), ",".join(codes), TLS, WS))
    op = ebsp.Operator("admin", "admin-demo-secret", port=TLS)

    # ---- phase 1: fanout + ACK -------------------------------------------
    t0 = ebsp.now_iso(); ts = time.time()
    for c in codes:
        connect(c, auto_ack=(c != SILENT))
    trace("all %d devices authenticated (silent=%s)" % (len(codes), SILENT))
    pub1 = op.publish(category="FIRE_DRILL", priority="CRITICAL",
                      title="FIRE DRILL — E2E DEMO",
                      instruction="Evacuate via stairwell B. This is a simulation.",
                      simulation=True)
    aid1 = pub1["alertId"]; alerts["alert1"] = pub1
    trace("publish alert1=%s deliveryCount=%s" % (aid1, pub1.get("deliveryCount")))
    acks1 = wait_acks(aid1, len(codes) - 1, timeout=25)
    silent_entry = devs[SILENT].inbox.get(aid1)
    pushes = {c: sum(1 for p in d.push_log if p["payload"].get("alertId") == aid1)
              for c, d in devs.items()}
    checks["fanoutDeliveryCount"] = pub1.get("deliveryCount") == len(codes)
    checks["autoAcks"] = acks1 >= len(codes) - 1
    checks["silentStoredNotAcked"] = bool(silent_entry) and not silent_entry["acked"]
    phase("fanout-ack", {"publish": pub1, "receivedAcks": acks1,
                         "expectedAutoAcks": len(codes) - 1,
                         "pushesPerDevice": pushes,
                         "silentInboxEntry": silent_entry,
                         "states": alert_states(aid1)}, t0, ts)

    # ---- phase 2: retry budget -> FAILED ----------------------------------
    t0 = ebsp.now_iso(); ts = time.time()
    end = time.time() + 40
    row = None
    while time.time() < end:
        rows = q(db, "SELECT id, state, attempt_count, revision FROM deliveries "
                     "WHERE alert_id=? AND device_id=?", (aid1, devs[SILENT].device_id))
        row = rows[0] if rows else None
        if row and row["state"] == "FAILED":
            break
        time.sleep(1.0)
    attempts = [a for a in ebsp.delivery_attempts(db, aid1) if a.get("device_id") == devs[SILENT].device_id]
    times = [a.get("attempted_at") for a in attempts]
    gaps = []
    for i in range(1, len(times)):
        try:
            gaps.append(round((ebsp.parse_ts(times[i]) - ebsp.parse_ts(times[i - 1])).total_seconds(), 3))
        except Exception:
            gaps.append(None)
    checks["retryFailed"] = bool(row) and row["state"] == "FAILED"
    checks["retryAttempts4"] = bool(row) and row["attempt_count"] == 4
    phase("retry-failed", {"delivery": row, "attempts": attempts,
                           "attemptGapsSeconds": gaps,
                           "retryAudit": audit_for(aid1, ["RETRY", "DELIVERY_FAILED"]),
                           "silentPushCount": sum(1 for p in devs[SILENT].push_log
                                                  if p["payload"].get("alertId") == aid1)}, t0, ts)

    # ---- phase 3: reconnect + SYNC + late ACK ------------------------------
    t0 = ebsp.now_iso(); ts = time.time()
    devs[SILENT].disconnect()
    d2 = connect(SILENT, auto_ack=True, name=SILENT + "-reconnect")
    sync2 = d2.sync()
    late = None
    entry = d2.inbox.get(aid1)
    sync_alert = next((a for a in sync2.get("alerts", []) if a["alertId"] == aid1), None)
    if sync_alert:
        late = d2.ack_received(aid1, sync_alert["deliveryId"], sync_alert["revision"])
    elif entry:
        late = d2.ack_received(aid1, entry["deliveryId"], entry["revision"])
    row_after = q(db, "SELECT state, attempt_count FROM deliveries WHERE alert_id=? AND device_id=?",
                  (aid1, devs[SILENT].device_id))
    d1 = devs["REAL-DEV-0001"]
    d1.disconnect()
    d1b = connect("REAL-DEV-0001", name="REAL-DEV-0001-reconnect")
    sync1 = d1b.sync()
    checks["reconnectSyncOk"] = sync2 is not None
    checks["lateAckSent"] = late is not None
    phase("reconnect-sync", {
        "silentSync": {"alerts": [(a["alertId"], a.get("revision"), a.get("deliveryState"))
                                   for a in sync2.get("alerts", [])],
                       "tombstones": sync2.get("tombstones", [])},
        "lateAckReply": late, "deliveryAfterLateAck": row_after,
        "ackedDeviceSync": {"alerts": len(sync1.get("alerts", [])),
                            "tombstones": len(sync1.get("tombstones", []))},
        "reconnectAudit": audit_for(aid1, ["SYNC"])[-4:]}, t0, ts)

    # ---- phase 4: crash recovery -------------------------------------------
    t0 = ebsp.now_iso(); ts = time.time()
    disconnect_all()
    trace("fleet offline; publishing alert2 then SIGKILL server pid=%s" % srv.pid)
    pub2 = op.publish(category="FIRE_DRILL", priority="WARNING",
                      title="RECOVERY PROBE", instruction="Published while fleet offline.",
                      simulation=True)
    aid2 = pub2["alertId"]; alerts["alert2"] = pub2
    time.sleep(2.5)
    pre_crash = alert_states(aid2)
    pre_attempts = q(db, "SELECT COUNT(*) n FROM delivery_attempts da JOIN deliveries d ON d.id=da.delivery_id "
                         "WHERE d.alert_id=?", (aid2,))[0]["n"]
    crash_at = ebsp.now_iso()
    srv.kill9()
    trace("server SIGKILLed at %s (WAL left behind)" % crash_at)
    srv2 = ebsp.ServerProc(db, log, tls_port=TLS, ws_port=WS)
    srv2.start()
    restart_at = ebsp.now_iso()
    trace("server restarted pid=%s" % srv2.pid)
    rec_rows = ebsp.audit_rows(db, actions=["RECOVERY"], limit=500)
    for c in codes:
        connect(c, auto_ack=True, name=c + "-post-crash")
    end = time.time() + 45
    st2 = {}
    while time.time() < end:
        st2 = alert_states(aid2)
        if sum(st2.values()) == len(codes) and not any(s not in TERMINAL for s in st2):
            break
        time.sleep(1.0)
    acks2 = wait_acks(aid2, len(codes), timeout=10)
    checks["recoverySettled"] = sum(st2.values()) == len(codes) and all(s in TERMINAL for s in st2)
    checks["recoveryAcks"] = acks2 >= len(codes)
    phase("crash-recovery", {
        "publish": pub2, "statesBeforeCrash": pre_crash,
        "offlineAttemptsBeforeCrash": pre_attempts,
        "crashAt": crash_at, "restartAt": restart_at,
        "recoveryAuditRows": rec_rows,
        "statesAfterRecovery": st2, "receivedAcks": acks2,
        "attemptsAfter": q(db, "SELECT d.device_id, d.state, d.attempt_count FROM deliveries d "
                               "WHERE d.alert_id=? ORDER BY d.device_id", (aid2,))}, t0, ts)

    # ---- phase 5: RBAC deny + positive control ------------------------------
    t0 = ebsp.now_iso(); ts = time.time()
    op_emp = ebsp.Operator("employee", "employee-demo-secret", port=TLS)
    deny_role = op_emp.publish(category="FIRE_DRILL", priority="INFO",
                               title="UNAUTHORIZED employee publish", simulation=True)
    op_emp.close()
    op_f7 = ebsp.Operator("floor7", "floor7-demo-secret", port=TLS)
    deny_scope = op_f7.publish(category="FIRE_DRILL", priority="INFO", floor=5,
                               title="UNAUTHORIZED floor7 -> floor5", simulation=True)
    op_f7.close()
    op_sec = ebsp.Operator("secops", "secops-demo-secret", port=TLS)
    pub3 = op_sec.publish(category="FIRE_DRILL", priority="INFO",
                          title="RBAC POSITIVE CONTROL (secops)",
                          instruction="Building-wide publish by SECURITY_OPERATOR.",
                          simulation=True)
    op_sec.close()
    aid3 = pub3.get("alertId"); alerts["alert3"] = pub3
    acks3 = wait_acks(aid3, len(codes), timeout=25) if pub3.get("ok") else 0
    forbid = [r for r in ebsp.audit_rows(db, actions=["PUBLISH"], limit=2000)
              if r.get("outcome") == "FORBIDDEN"]
    checks["rbacRoleDenied"] = deny_role.get("ok") is False and deny_role.get("code") == "FORBIDDEN"
    checks["rbacScopeDenied"] = deny_scope.get("ok") is False and deny_scope.get("code") == "FORBIDDEN"
    checks["rbacPositiveControl"] = pub3.get("ok") is True and acks3 >= len(codes)
    phase("rbac", {"employeePublish": deny_role, "floor7CrossFloorPublish": deny_scope,
                   "secopsPublish": pub3, "positiveControlAcks": acks3,
                   "forbiddenAudit": forbid}, t0, ts)

    # ---- phase 6: WS status feed snapshot -----------------------------------
    t0 = ebsp.now_iso(); ts = time.time()
    frames = ebsp.ws_collect(port=WS, seconds=5.0)
    with open(os.path.join(out, "ws-snapshot.json"), "w") as f:
        json.dump({"collectedAt": t0, "seconds": 5.0, "frames": frames}, f, indent=1)
    types = {}
    for fr in frames:
        types[fr.get("type", "?")] = types.get(fr.get("type", "?"), 0) + 1
    checks["wsFrames"] = len(frames) > 0
    phase("ws-snapshot", {"frameCount": len(frames), "frameTypes": types,
                          "firstFrame": frames[0] if frames else None}, t0, ts)

    # ---- final evidence -------------------------------------------------------
    stats = ebsp.db_stats(db)
    result = {
        "meta": meta,
        "config": {"nSim": N_SIM, "nReal": N_REAL, "fleet": codes, "silentDevice": SILENT,
                   "tlsPort": TLS, "wsPort": WS,
                   "retryPolicy": "maxRetries=3 base=3000ms (3s/6s/12s)"},
        "checks": checks,
        "alerts": alerts,
        "phases": phases,
        "deviceTraces": {c: {"events": d.events, "pushLog": d.push_log,
                             "inbox": d.inbox, "tombstones": d.tombstones}
                         for c, d in devs.items()},
        "dbStats": stats,
        "artifacts": {"db": db, "serverLog": log, "demoLog": os.path.join(out, "demo.log"),
                      "wsSnapshot": os.path.join(out, "ws-snapshot.json"),
                      "inboxDir": os.path.join(out, "inbox")},
    }
    with open(os.path.join(out, "demo.json"), "w") as f:
        json.dump(result, f, indent=1)
    latest = os.path.join(ebsp.REPO, "docs", "evidence", "demo", "latest")
    if os.path.islink(latest) or os.path.exists(latest):
        os.remove(latest)
    os.symlink(meta["runId"], latest)
    trace("CHECKS " + json.dumps(checks))
    trace("evidence: " + out)
    if not all(checks.values()):
        trace("ERROR: some demo checks failed"); sys.exit(1)
    trace("DEMO E2E OK")
finally:
    for d in list(devs.values()):
        try: d.close()
        except Exception: pass
    if op is not None:
        op.close()
    if srv2 is not None:
        srv2.stop(); trace("server stopped (pid=%s)" % srv2.pid)
    elif srv is not None:
        srv.stop(); trace("server stopped (pid=%s)" % srv.pid)
    trace_f.close()
    checkpoint(db)
PY
