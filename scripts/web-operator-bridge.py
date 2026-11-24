#!/usr/bin/env python3
"""Local-only HTTP bridge for the web-only AEGIS-EBS demo + Admin Console.

The browser (http://localhost:5173) talks to this process on 127.0.0.1:9445;
this process drives the real EBSP/2.0 TLS through the tracked `scripts/ebsp.py`
(Device / Operator actors). The /api/admin/* surface
reads and approves role grants in AEGIS_DB (SQLite). No Java or dashboard
source is touched, no credential is logged or persisted, and nothing binds
beyond loopback unless WEB_BRIDGE_HOST / WEB_BRIDGE_ORIGINS are set explicitly
(e.g. a Tailscale hostname). SIMULATION/TRAINING ONLY.
"""
import json
import os
import re
import socket
import sqlite3
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Union
from urllib.parse import parse_qs

REPO = os.environ.get("AEGIS_REPO") or os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ebsp  # noqa: E402  # pyright: ignore[reportMissingImports]

# Loopback is the safe default. Widening the bind (Tailscale) or the CORS
# allowlist is always an explicit opt-in via environment variables.
BIND_HOST = os.environ.get("WEB_BRIDGE_HOST", "127.0.0.1")
BIND_PORT = int(os.environ.get("WEB_BRIDGE_PORT", "9445"))
TLS_PORT = int(os.environ.get("AEGIS_TLS_PORT", "9443"))
_ALLOWED_DEFAULT = (
    "http://localhost:5173",
    "http://127.0.0.1:5173",
)
_extra_origins = tuple(
    item.strip()
    for item in os.environ.get("WEB_BRIDGE_ORIGINS", "").split(",")
    if item.strip()
)
ALLOWED_ORIGINS = tuple(dict.fromkeys(_ALLOWED_DEFAULT + _extra_origins))
AEGIS_DB = os.environ.get("AEGIS_DB") or os.path.join(REPO, "aegis.db")
DEVICE_SECRET = "device-demo-secret"
FLOORS = range(1, 11)
ZONES = ("A", "B", "C", "D")
SEQS = range(1, 13)
MAX_FLEET = len(FLOORS) * len(ZONES) * len(SEQS)  # 480 seeded devices

_lock = threading.RLock()
_fleet = {}          # deviceCode -> ebsp.Device
_silent_code = None  # deviceCode currently configured with auto_ack=False
_last_count = 20

ADMIN_SESSION_TTL = 1800
ALLOWED_GRANT_SCOPES = frozenset({"PUBLISH"})
_admin_sessions = {}  # token -> {userId, username, role, expiresAt}


class BadRequest(Exception):
    """Input validation failure -> HTTP 400."""


def _admin_session(headers):
    """Return the authenticated admin session for a Bearer header, else None."""
    auth = (headers or {}).get("Authorization", "")
    if not isinstance(auth, str) or not auth.startswith("Bearer "):
        return None
    token = auth[7:].strip()
    with _lock:
        session = _admin_sessions.get(token)
        if session is None:
            return None
        if time.time() >= session["expiresAt"]:
            _admin_sessions.pop(token, None)
            return None
        return dict(session)


def _require_admin(headers):
    session = _admin_session(headers)
    if session is None or session["role"] != "SYSTEM_ADMIN":
        return None
    return session


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def fleet_codes(count, silent_floor=0):
    """Deterministic real seed codes: DEV-A-F%02d-{A..D}-01..12."""
    if isinstance(count, bool) or not isinstance(count, int):
        raise BadRequest("count must be an integer")
    if count < 1 or count > MAX_FLEET:
        raise BadRequest("count must be between 1 and %d" % MAX_FLEET)
    if isinstance(silent_floor, bool) or not isinstance(silent_floor, int):
        raise BadRequest("silentFloor must be an integer")
    if silent_floor < 0 or silent_floor > 10:
        raise BadRequest("silentFloor must be 0..10")
    # First 12 devices are all F01-A: the retry demo publishes F01-A and needs
    # 11 healthy ACKs + exactly 1 silent device. Every floor/zone/sequence
    # combination after that is emitted once, so counts up to 480 stay unique.
    codes = ["DEV-A-F01-A-%02d" % s for s in SEQS]
    for floor in range(2, 11):
        for zone in ZONES:
            for seq in SEQS:
                codes.append("DEV-A-F%02d-%s-%02d" % (floor, zone, seq))
    for zone in ZONES[1:]:
        for seq in SEQS:
            codes.append("DEV-A-F01-%s-%02d" % (zone, seq))
    codes = codes[:count]
    if silent_floor:
        silent = "DEV-A-F%02d-A-01" % silent_floor
        if silent not in codes:
            codes[-1] = silent
    return codes


def silent_code_of(count, silent_floor):
    return "DEV-A-F%02d-A-01" % silent_floor if silent_floor else None


def port_open(port, host=BIND_HOST, timeout=1.0):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def as_bool(value, default=False):
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in ("1", "true", "yes", "on")
    if isinstance(value, (int, float)):
        return bool(value)
    return default


def int_field(body, key, default=None, lo=None, hi=None):
    value = body.get(key, default)
    if value is None or value == "":
        return None
    if isinstance(value, bool):
        raise BadRequest("%s must be an integer" % key)
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise BadRequest("%s must be an integer" % key)
    if lo is not None and number < lo:
        raise BadRequest("%s must be >= %d" % (key, lo))
    if hi is not None and number > hi:
        raise BadRequest("%s must be <= %d" % (key, hi))
    return number


def zone_letter(value):
    if value is None or value == "":
        return None
    text = str(value).strip().upper()
    if "-" in text:                       # accept "F05-A" as well as "A"
        text = text.rsplit("-", 1)[-1]
    if len(text) != 1 or text not in ZONES:
        raise BadRequest("zone must be A..D or F01-A..F10-D")
    return text


def safe_error(exc, secrets=()):
    """Never echo a request body or a credential into a response/log."""
    text = "%s: %s" % (type(exc).__name__, exc)
    for secret in secrets:
        if isinstance(secret, str) and len(secret) >= 3:
            text = text.replace(secret, "***")
    # Cover `password=...`, `password_hash: ...`, JSON fields and common
    # credential/token names before the message ever reaches a client.
    text = re.sub(r"(?i)(password(?:_hash|Hash)?|credential|secret|token|api[_-]?key)"
                  r"\s*[=:]\s*[\w.-]+", r"\1=***", text)
    text = re.sub(r'(?i)("(?:password(?:_hash|Hash)?|credential|secret|token|api[_-]?key)"'
                  r'\s*:\s*)"(?:[^"\\]|\\.)*"', r'\1"***"', text)
    return text.replace("\n", " ")[:500]


# ---------------------------------------------------------------------------
# fleet lifecycle
# ---------------------------------------------------------------------------

def _stop_locked():
    stopped = len(_fleet)
    for device in list(_fleet.values()):
        try:
            device.close()
        except Exception:
            pass
    _fleet.clear()
    return stopped


def stop_fleet():
    global _silent_code
    with _lock:
        stopped = _stop_locked()
        _silent_code = None
        return stopped


def start_fleet(count=20, silent_floor=0):
    """Close the old fleet first, then connect a fresh deterministic fleet."""
    global _last_count, _silent_code
    codes = fleet_codes(count, silent_floor)
    silent = silent_code_of(count, silent_floor)
    fresh = {}
    with _lock:
        _stop_locked()
        try:
            for code in codes:
                device = None
                for attempt in range(3):
                    try:
                        device = ebsp.Device(
                            code, secret=DEVICE_SECRET, port=TLS_PORT,
                            auto_ack=(code != silent))
                        break
                    except Exception:
                        if attempt == 2:
                            raise
                        time.sleep(0.2 * (attempt + 1))
                fresh[code] = device
        except Exception:
            for device in fresh.values():
                try:
                    device.close()
                except Exception:
                    pass
            fresh.clear()
            raise
        _fleet.update(fresh)
        _last_count = count
        _silent_code = silent
        connected = len(_fleet)
    return {"ok": True, "connected": connected, "count": count,
            "codes": list(codes), "silentCode": silent}


def fleet_stats():
    with _lock:
        snapshot = [(code, device) for code, device in _fleet.items()]
    devices = []
    for code, device in snapshot:
        inbox = list(device.inbox.values())
        devices.append({
            "code": code,
            "pushes": len(inbox),
            "acked": sum(1 for entry in inbox if entry.get("acked")),
            "userAcked": sum(1 for entry in inbox if entry.get("userAcked")),
            "pushFrames": sum(1 for entry in device.push_log
                              if entry.get("kind") == "push"),
        })
    return {
        "ok": True,
        "fleetCount": len(devices),
        "pushes": sum(d["pushes"] for d in devices),
        "acked": sum(d["acked"] for d in devices),
        "userAcked": sum(d["userAcked"] for d in devices),
        "pushFrames": sum(d["pushFrames"] for d in devices),
        "devices": devices,
    }


# ---------------------------------------------------------------------------
# Admin Console (SQLite) — parameterized access, no secret ever in a response
# ---------------------------------------------------------------------------

_ADMIN_GRANT_SELECT = """
SELECT rg.id AS id, u.username AS username, u.role AS role,
       rg.building_id AS building_id, rg.floor_id AS floor_id,
       rg.scope AS scope, rg.granted_at AS granted_at
FROM role_grants rg
JOIN users u ON u.id = rg.user_id
LEFT JOIN buildings b ON b.id = rg.building_id
LEFT JOIN floors f ON f.id = rg.floor_id
"""
ADMIN_GRANT_SELECT = _ADMIN_GRANT_SELECT + " ORDER BY rg.granted_at DESC, rg.id"
ADMIN_GRANT_BY_ID = _ADMIN_GRANT_SELECT + " WHERE rg.id = ?"

_SCRUB_RE = re.compile(
    r'(?i)("(?:password(?:_hash|Hash)?|credential|secret|token|api[_-]?key)"'
    r'\s*:\s*)"(?:[^"\\]|\\.)*"')


def _now_ts():
    """Fixed-width UTC timestamp (9 fractional digits, like Java Sql.TS)."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f") + "000Z"


def _connect():
    path = AEGIS_DB
    if not path or not os.path.isfile(path):
        raise BadRequest("AEGIS database is not available")
    conn = sqlite3.connect(path, timeout=5.0)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=5000")
    return conn


def _table_exists(conn, name):
    return conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone() is not None


def _rows(conn, sql, params=()):
    return [dict(row) for row in conn.execute(sql, params).fetchall()]


def _scrub_json(text):
    """Redact credential-looking fields in stored audit payloads."""
    if not isinstance(text, str):
        return text
    return _SCRUB_RE.sub(r'\1"***"', text)


def _text_field(body, key, required=True):
    value = body.get(key)
    if value is None or (isinstance(value, str) and not value.strip()):
        if required:
            raise BadRequest("%s is required" % key)
        return None
    if not isinstance(value, str):
        raise BadRequest("%s must be a string" % key)
    return value.strip()


def _grant_payload(grant, **extra):
    payload = {
        "grantId": grant["id"],
        "username": grant["username"],
        "role": grant["role"],
        "buildingId": grant["building_id"],
        "floorId": grant["floor_id"],
        "scope": grant["scope"],
        "grantedAt": grant["granted_at"],
    }
    payload.update(extra)
    return json.dumps(payload, ensure_ascii=False, sort_keys=True)


def _audit_insert(conn, action, grant, operator, correlation_id):
    audit_id = str(uuid.uuid4())
    conn.execute(
        "INSERT INTO audit_events "
        "(id, occurred_at, actor_id, action, scope, outcome, correlation_id, payload_json) "
        "VALUES (?,?,?,?,?,?,?,?)",
        (audit_id, _now_ts(), operator, action, grant["building_id"], "OK",
         correlation_id, _grant_payload(grant, operator=operator)))
    return audit_id


def admin_overview():
    counts = {}
    conn = _connect()
    try:
        for key, table in (("users", "users"),
                           ("roleGrants", "role_grants"),
                           ("auditEvents", "audit_events")):
            counts[key] = (conn.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]
                           if _table_exists(conn, table) else 0)
        counts["activeAlerts"] = (
            conn.execute("SELECT COUNT(*) FROM alerts WHERE state='ACTIVE'").fetchone()[0]
            if _table_exists(conn, "alerts") else 0)
    finally:
        conn.close()
    result = {"ok": True, "counts": dict(counts)}
    result.update(counts)          # convenience top-level aliases
    return result


def admin_users():
    conn = _connect()
    try:
        rows = (_rows(conn, "SELECT id, username, role, enabled, created_at "
                            "FROM users ORDER BY username")
                if _table_exists(conn, "users") else [])
    finally:
        conn.close()
    return {"ok": True, "users": rows}


def admin_grants():
    conn = _connect()
    try:
        rows = (_rows(conn, ADMIN_GRANT_SELECT)
                if _table_exists(conn, "role_grants") else [])
    finally:
        conn.close()
    return {"ok": True, "grants": rows}


def admin_audit(limit: Union[int, str, None] = 100, action=None, outcome=None):
    if limit is None:
        limit = 100
    if isinstance(limit, bool):
        raise BadRequest("limit must be an integer")
    try:
        limit = int(limit)
    except (TypeError, ValueError):
        raise BadRequest("limit must be an integer")
    if limit < 1 or limit > 500:
        raise BadRequest("limit must be between 1 and 500")
    clauses, params = [], []
    for column, value in (("action", action), ("outcome", outcome)):
        if value:
            if not isinstance(value, str):
                raise BadRequest("%s must be a string" % column)
            clauses.append(column + " = ?")
            params.append(value)
    sql = ("SELECT id, occurred_at, actor_id, action, scope, outcome, "
           "correlation_id, payload_json FROM audit_events")
    if clauses:
        sql += " WHERE " + " AND ".join(clauses)
    sql += " ORDER BY occurred_at DESC LIMIT ?"
    params.append(limit)
    conn = _connect()
    try:
        rows = (_rows(conn, sql, tuple(params))
                if _table_exists(conn, "audit_events") else [])
    finally:
        conn.close()
    for row in rows:
        row["payload_json"] = _scrub_json(row.get("payload_json"))
    return {"ok": True, "events": rows, "limit": limit,
            "action": action, "outcome": outcome}


def _resolve_admin_operator(conn, actor_id):
    """The actor comes from the authenticated session, never from the body."""
    row = conn.execute(
        "SELECT id, role, enabled FROM users WHERE id = ?", (actor_id,)).fetchone()
    if row is None or row["role"] != "SYSTEM_ADMIN" or not row["enabled"]:
        raise BadRequest("operator must be an enabled SYSTEM_ADMIN")
    return row["id"]


def _admin_login(body):
    username = _text_field(body, "username")
    password = body.get("password")
    if not isinstance(password, str) or not password:
        raise BadRequest("password required")
    op = None
    try:
        op = ebsp.Operator(username, password, port=TLS_PORT)
    except Exception:
        return 401, {"ok": False, "error": "admin login denied"}
    finally:
        if op is not None:
            try:
                op.close()
            except Exception:
                pass
    conn = _connect()
    try:
        row = conn.execute(
            "SELECT id, role, enabled FROM users WHERE username = ?",
            (username,)).fetchone()
    finally:
        conn.close()
    if row is None or row["role"] != "SYSTEM_ADMIN" or not row["enabled"]:
        return 401, {"ok": False, "error": "admin login denied"}
    token = uuid.uuid4().hex
    with _lock:
        _admin_sessions[token] = {
            "userId": row["id"], "username": username, "role": row["role"],
            "expiresAt": time.time() + ADMIN_SESSION_TTL,
        }
    return 200, {"ok": True, "token": token, "username": username,
                 "role": row["role"]}


def admin_grant_approve(body, actor_id):
    username = _text_field(body, "username")
    building_id = _text_field(body, "buildingId")
    floor_id = _text_field(body, "floorId", required=False)
    scope = _text_field(body, "scope", required=False) or "PUBLISH"
    if scope not in ALLOWED_GRANT_SCOPES:
        raise BadRequest("unsupported grant scope")
    conn = _connect()
    try:
        with conn:
            resolved_actor = _resolve_admin_operator(conn, actor_id)
            user = conn.execute("SELECT id, role FROM users WHERE username = ?",
                                (username,)).fetchone()
            if user is None:
                raise BadRequest("unknown username")
            role = user["role"]
            if role == "SECURITY_OPERATOR" and floor_id is not None:
                raise BadRequest("floor-scoped SECOPS grant would broaden to the building")
            if role == "FLOOR_MANAGER" and floor_id is None:
                raise BadRequest("FLOOR_MANAGER grant requires floorId")
            if role in ("EMPLOYEE", "DEVICE"):
                raise BadRequest("role cannot publish")
            if (not _table_exists(conn, "buildings") or
                    conn.execute("SELECT 1 FROM buildings WHERE id = ?",
                                 (building_id,)).fetchone() is None):
                raise BadRequest("unknown buildingId")
            if floor_id is not None:
                floor = conn.execute("SELECT building_id FROM floors WHERE id = ?",
                                     (floor_id,)).fetchone()
                if floor is None or floor["building_id"] != building_id:
                    raise BadRequest("floorId does not belong to buildingId")
            existing = conn.execute(
                "SELECT id FROM role_grants WHERE user_id=? AND building_id=? "
                "AND floor_id IS ? AND scope=?",
                (user["id"], building_id, floor_id, scope)).fetchone()
            if existing is not None:
                row = conn.execute(ADMIN_GRANT_BY_ID, (existing["id"],)).fetchone()
                return {"ok": True, "duplicate": True, "grant": dict(row),
                        "auditId": None}
            grant_id = str(uuid.uuid4())
            granted_at = _now_ts()
            conn.execute(
                "INSERT INTO role_grants "
                "(id, user_id, building_id, floor_id, scope, granted_at) "
                "VALUES (?,?,?,?,?,?)",
                (grant_id, user["id"], building_id, floor_id, scope, granted_at))
            grant = {"id": grant_id, "username": username, "role": role,
                     "building_id": building_id, "floor_id": floor_id,
                     "scope": scope, "granted_at": granted_at}
            audit_id = _audit_insert(conn, "OPERATOR_GRANT_APPROVED", grant,
                                     resolved_actor, grant_id)
    finally:
        conn.close()
    return {"ok": True, "duplicate": False, "grant": grant, "auditId": audit_id}


def admin_grant_revoke(body, actor_id):
    grant_id = _text_field(body, "grantId")
    conn = _connect()
    try:
        with conn:
            resolved_actor = _resolve_admin_operator(conn, actor_id)
            row = conn.execute(ADMIN_GRANT_BY_ID, (grant_id,)).fetchone()
            if row is None:
                raise BadRequest("unknown grantId")
            grant = dict(row)
            conn.execute("DELETE FROM role_grants WHERE id = ?", (grant_id,))
            audit_id = _audit_insert(conn, "OPERATOR_GRANT_REVOKED", grant,
                                     resolved_actor, grant_id)
    finally:
        conn.close()
    return {"ok": True, "revoked": grant_id, "grant": grant, "auditId": audit_id}


# ---------------------------------------------------------------------------
# HTTP surface
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):
        pass                      # no request bodies, headers or secrets logged

    def _cors(self):
        origin = self.headers.get("Origin")
        allow = origin if origin in ALLOWED_ORIGINS else ALLOWED_ORIGINS[0]
        self.send_header("Access-Control-Allow-Origin", allow)
        self.send_header("Vary", "Origin")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        self.send_header("Access-Control-Max-Age", "600")

    def _send(self, status, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _reply(self, path, run):
        try:
            status, result = run()
        except BadRequest as exc:
            status, result = 400, {"ok": False, "error": safe_error(exc)}
        except Exception as exc:
            # Generic only: no traceback, no body, no credential anywhere.
            status, result = 500, {"ok": False, "error": safe_error(exc)}
        self._send(status, result)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self._cors()
        self.end_headers()

    def do_GET(self):
        parts = self.path.split("?", 1)
        path = parts[0]
        query = parts[1] if len(parts) > 1 else ""
        self._reply(path, lambda: self._get(path, query, self.headers))

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        self._reply(path, lambda: self._post(path, self._json_body(), self.headers))

    def _json_body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            raise BadRequest("invalid Content-Length")
        if length < 0 or length > 65536:
            raise BadRequest("body too large")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise BadRequest("invalid JSON body")
        if not isinstance(body, dict):
            raise BadRequest("JSON body must be an object")
        return body

    def _get(self, path, query="", headers=None):
        if path == "/api/health":
            tls_open = port_open(TLS_PORT)
            with _lock:
                fleet_count = len(_fleet)
            result = {"ok": tls_open, "tlsPort": TLS_PORT,
                      "tlsOpen": tls_open, "fleetCount": fleet_count,
                      "bridgePort": BIND_PORT}
            if not tls_open:
                result["error"] = "TLS gateway not reachable on 127.0.0.1:%d" % TLS_PORT
            return 200, result
        if path == "/api/fleet/stats":
            return 200, fleet_stats()

        if path.startswith("/api/admin/"):
            session = _require_admin(headers)
            if session is None:
                return 401, {"ok": False, "error": "admin login required"}

        if path == "/api/admin/overview":
            return 200, admin_overview()
        if path == "/api/admin/users":
            return 200, admin_users()
        if path == "/api/admin/grants":
            return 200, admin_grants()
        if path == "/api/admin/audit":
            params = parse_qs(query or "", keep_blank_values=True)

            def one(name):
                values = params.get(name) or []
                return values[-1] if values else None

            raw_limit = one("limit")
            return 200, admin_audit(
                limit=100 if raw_limit is None else raw_limit,
                action=one("action"), outcome=one("outcome"))

        return 404, {"ok": False, "error": "unknown endpoint"}

    def _post(self, path, body, headers=None):
        if path == "/api/admin/login":
            return _admin_login(body)

        if path == "/api/fleet/start":
            count = int_field(body, "count", 20, 1, MAX_FLEET)
            silent_floor = int_field(body, "silentFloor", 0, 0, 10)
            return 200, start_fleet(count if count is not None else 20,
                                    silent_floor if silent_floor is not None else 0)

        if path == "/api/fleet/stop":
            return 200, {"ok": True, "stopped": stop_fleet()}

        if path.startswith("/api/admin/"):
            session = _require_admin(headers)
            if session is None:
                return 401, {"ok": False, "error": "admin login required"}

        if path == "/api/admin/grants/approve":
            return 200, admin_grant_approve(body, session["userId"])
        if path == "/api/admin/grants/revoke":
            return 200, admin_grant_revoke(body, session["userId"])

        if path in ("/api/publish", "/api/cancel"):
            username, password = body.get("username"), body.get("password")
            if not isinstance(username, str) or not username.strip():
                raise BadRequest("username required")
            if not isinstance(password, str) or not password:
                raise BadRequest("password required")
            secrets = (password,)
            # "ALL" is the web contract for building-wide targeting: omit the
            # field so ebsp.Operator publishes without floor/zone and the server
            # resolves the full building.
            raw_floor = body.get("floor")
            floor = None if raw_floor in (None, "", "ALL", "all") else int_field(body, "floor", None, 1, 10)
            raw_zone = body.get("zone")
            zone = None if raw_zone in (None, "", "ALL", "all") else zone_letter(raw_zone)
            op = None
            try:
                op = ebsp.Operator(username, password, port=TLS_PORT)
                if path == "/api/publish":
                    silent = as_bool(body.get("silent"), False)
                    if silent:
                        # Fleet started with silentFloor=1 already has the silent
                        # device. Restarting the same codes mid-publish races the
                        # server's session unregister and silently loses pushes.
                        with _lock:
                            need_silent = _silent_code not in _fleet
                        if need_silent:
                            start_fleet(_last_count, floor if floor is not None else 1)
                    payload = op.publish(
                        category=str(body.get("category") or "FIRE_DRILL"),
                        priority=str(body.get("priority") or "CRITICAL"),
                        title=str(body.get("title") or "Demo alert"),
                        instruction=str(body.get("instruction") or "Simulation drill."),
                        floor=floor, zone=zone, simulation=True)
                    result = {"ok": bool(payload.get("ok")),
                              "alertId": payload.get("alertId"),
                              "deliveryCount": payload.get("deliveryCount"),
                              "duplicate": payload.get("duplicate", False),
                              "silentFloor": floor if silent else 0,
                              "payload": payload}
                    if not result["ok"]:
                        result["error"] = payload.get("code") or "publish rejected"
                else:
                    alert_id = body.get("alertId")
                    if not isinstance(alert_id, str) or not alert_id:
                        raise BadRequest("alertId required")
                    reason = str(body.get("reason") or "demo cancel")
                    payload = op.cancel(alert_id, reason=reason)
                    result = {"ok": bool(payload.get("ok")),
                              "alertId": payload.get("alertId") or alert_id,
                              "duplicate": payload.get("duplicate", False),
                              "payload": payload}
                    if not result["ok"]:
                        result["error"] = payload.get("code") or "cancel rejected"
                return 200, result
            except BadRequest:
                raise
            except Exception as exc:
                result = {"ok": False, "error": safe_error(exc, secrets)}
                return 500, result
            finally:
                if op is not None:
                    try:
                        op.close()
                    except Exception:
                        pass

        return 404, {"ok": False, "error": "unknown endpoint"}


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    server = BridgeServer((BIND_HOST, BIND_PORT), Handler)
    print("web-operator-bridge ready: http://%s:%d/api/health (TLS %s:%d, CORS %s)"
          % (BIND_HOST, BIND_PORT, BIND_HOST, TLS_PORT, ",".join(ALLOWED_ORIGINS)), flush=True)
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        stop_fleet()
        server.server_close()


if __name__ == "__main__":
    main()
