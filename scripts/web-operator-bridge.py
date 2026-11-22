#!/usr/bin/env python3
"""Local-only HTTP bridge for the web-only AEGIS-EBS demo.

The browser (http://localhost:5173) talks to this process on 127.0.0.1:9445;
this process drives the real EBSP/2.0 TLS gateway through
target/e2e-dev/ebsp.py (Device / Operator actors). No Java or dashboard source
is touched, no credential is logged or persisted, and nothing binds beyond
loopback. SIMULATION/TRAINING ONLY.
"""
import json
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPO = os.environ.get("AEGIS_REPO") or os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
sys.path.insert(0, os.path.join(REPO, "target", "e2e-dev"))
import ebsp  # noqa: E402  # pyright: ignore[reportMissingImports]

BIND_HOST = "127.0.0.1"
BIND_PORT = int(os.environ.get("WEB_BRIDGE_PORT", "9445"))
TLS_PORT = int(os.environ.get("AEGIS_TLS_PORT", "9443"))
ALLOWED_ORIGINS = (
    "http://localhost:5173",
    "http://127.0.0.1:5173",
)
DEVICE_SECRET = "device-demo-secret"
FLOORS = range(1, 11)
ZONES = ("A", "B", "C", "D")
SEQS = range(1, 13)
MAX_FLEET = len(FLOORS) * len(ZONES) * len(SEQS)  # 480 seeded devices

_lock = threading.RLock()
_fleet = {}          # deviceCode -> ebsp.Device
_silent_code = None  # deviceCode currently configured with auto_ack=False
_last_count = 20


class BadRequest(Exception):
    """Input validation failure -> HTTP 400."""


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
    # 11 healthy ACKs + exactly 1 silent device. The rest round-robin over the
    # other floors so a F05 publish also finds a real device.
    codes = ["DEV-A-F01-A-%02d" % s for s in SEQS]
    for i in range(count - len(codes)):
        f = (i % 9) + 2                      # floors 2..10
        z = ZONES[(i // 9) % len(ZONES)]
        codes.append("DEV-A-F%02d-%s-01" % (f, z))
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
    text = re.sub(r"(?i)(password|credential|secret)\s*[=:]\s*[\w-]+",
                  r"\1=***", text)
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
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
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
        path = self.path.split("?", 1)[0]
        self._reply(path, lambda: self._get(path))

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        self._reply(path, lambda: self._post(path, self._json_body()))

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

    def _get(self, path):
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
        return 404, {"ok": False, "error": "unknown endpoint"}

    def _post(self, path, body):
        if path == "/api/fleet/start":
            count = int_field(body, "count", 20, 1, MAX_FLEET)
            silent_floor = int_field(body, "silentFloor", 0, 0, 10)
            return 200, start_fleet(count if count is not None else 20,
                                    silent_floor if silent_floor is not None else 0)

        if path == "/api/fleet/stop":
            return 200, {"ok": True, "stopped": stop_fleet()}

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
