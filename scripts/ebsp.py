#!/usr/bin/env python3
"""Tracked EBSP/2.0 actors and evidence helpers for the AEGIS-EBS demo scripts.

This module replaces the former generated `target/e2e-dev/ebsp.py`, which was
git-ignored and therefore absent from a clean checkout.  It implements the
small client surface the demos need over real TLS: HELLO/AUTH, ALERT_PUSH
with durable receipt ACK, SYNC, and operator PUBLISH/CANCEL.  Helpers for
seeding a fresh DB, driving the Java server, and summarising SQLite evidence
live beside the actors.
"""
from __future__ import annotations

import base64
import json
import os
import signal
import re
import socket
import sqlite3
import ssl
import struct
import subprocess
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

REPO = Path(__file__).resolve().parent.parent
CERTS = REPO / "certs"
SCHEMA = REPO / "aegis-persistence" / "src" / "main" / "resources" / "db"
DEFAULT_SECRET = "device-demo-secret"
DEFAULT_HOST = os.environ.get("AEGIS_HOST", "localhost")
MAX_FRAME = 65536


# ---------------------------------------------------------------------------
# time / metadata helpers
# ---------------------------------------------------------------------------

def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))


def run_meta() -> dict[str, Any]:
    try:
        commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO,
                                capture_output=True, text=True, timeout=5).stdout.strip()
    except Exception:
        commit = "unknown"
    return {
        "runId": uuid.uuid4().hex[:12],
        "commit": commit,
        "machine": platform.platform(),
        "collectedAt": now_iso(),
    }


def install_signal_handlers() -> None:
    """Let SIGTERM/SIGINT unwind Python's finally blocks before exit."""
    def handler(signum: int, _frame: Any) -> None:
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, handler)
    signal.signal(signal.SIGINT, handler)


# ---------------------------------------------------------------------------
# framing + TLS connection
# ---------------------------------------------------------------------------

def _send_frame(sock: socket.socket, envelope: dict[str, Any]) -> None:
    body = json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if not 1 <= len(body) <= MAX_FRAME:
        raise ValueError("frame length out of range")
    sock.sendall(struct.pack(">I", len(body)) + body)


def _recv_exact(sock: socket.socket, count: int) -> bytes:
    buf = bytearray()
    while len(buf) < count:
        chunk = sock.recv(count - len(buf))
        if not chunk:
            raise ConnectionError("peer closed during frame read")
        buf.extend(chunk)
    return bytes(buf)


def _recv_frame(sock: socket.socket) -> dict[str, Any]:
    length = struct.unpack(">I", _recv_exact(sock, 4))[0]
    if not 1 <= length <= MAX_FRAME:
        raise ValueError(f"illegal frame length {length}")
    return json.loads(_recv_exact(sock, length).decode("utf-8"))


def _tls_context() -> ssl.SSLContext:
    ctx = ssl.create_default_context(cafile=str(CERTS / "aegis-server.crt"))
    ctx.check_hostname = True
    ctx.verify_mode = ssl.CERT_REQUIRED
    return ctx


class _Connection:
    """One TLS socket with a single reader and correlation-id waiters."""

    def __init__(self, port: int, host: str, on_frame: Callable[[dict[str, Any]], None]):
        self.host = host
        self.port = int(port)
        self.on_frame = on_frame
        self.sock: ssl.SSLSocket | None = None
        self._waiters: dict[str, tuple[threading.Event, list[dict[str, Any]]]] = {}
        self._lock = threading.Lock()
        self._closed = threading.Event()
        self._reader: threading.Thread | None = None

    def connect(self) -> None:
        raw = socket.create_connection((self.host, self.port), timeout=10)
        raw.settimeout(30)
        self.sock = _tls_context().wrap_socket(raw, server_hostname="localhost")
        self._reader = threading.Thread(target=self._read_loop, name="ebsp-reader", daemon=True)
        self._reader.start()

    def send(self, msg_type: str, payload: dict[str, Any], correlation: str | None = None) -> str:
        if self.sock is None:
            raise ConnectionError("not connected")
        message_id = str(uuid.uuid4())
        envelope = {
            "version": 2,
            "type": msg_type,
            "messageId": message_id,
            "correlationId": correlation,
            "sentAt": now_iso(),
            "payload": payload,
        }
        with self._lock:
            _send_frame(self.sock, envelope)
        return message_id

    def request(self, msg_type: str, payload: dict[str, Any], timeout: float = 10.0) -> dict[str, Any]:
        event = threading.Event()
        result: list[dict[str, Any]] = []
        message_id = self.send(msg_type, payload)
        with self._lock:
            self._waiters[message_id] = (event, result)
        if not event.wait(timeout):
            with self._lock:
                self._waiters.pop(message_id, None)
            raise TimeoutError(f"no reply to {msg_type}")
        return result[0] if result else {}

    def _read_loop(self) -> None:
        assert self.sock is not None
        try:
            while not self._closed.is_set():
                envelope = _recv_frame(self.sock)
                if not isinstance(envelope, dict):
                    continue
                correlation = envelope.get("correlationId")
                with self._lock:
                    waiter = self._waiters.pop(correlation, None) if correlation else None
                if waiter:
                    waiter[1].append(envelope)
                    waiter[0].set()
                    continue
                try:
                    self.on_frame(envelope)
                except Exception as exc:  # keep the reader alive; surface to caller logs
                    print(f"ebsp: frame handler failed: {exc}")
        except (OSError, ssl.SSLError, ValueError, ConnectionError):
            pass
        finally:
            self._closed.set()

    def close(self) -> None:
        self._closed.set()
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass
        if self._reader and self._reader.is_alive():
            self._reader.join(timeout=2)


# ---------------------------------------------------------------------------
# device actor
# ---------------------------------------------------------------------------

class Device:
    """Authenticated device with a JSON inbox and receipt ACKs."""

    def __init__(self, code: str, secret: str = DEFAULT_SECRET, port: int = 9443,
                 auto_ack: bool = True, inbox_file: str | Path | None = None,
                 name: str | None = None, host: str = DEFAULT_HOST):
        self.code = code
        self.name = name or code
        self.device_id: str | None = None
        self.auto_ack = auto_ack
        self.inbox_file = Path(inbox_file) if inbox_file else None
        self.inbox: dict[str, dict[str, Any]] = {}
        self.push_log: list[dict[str, Any]] = []
        self.events: list[dict[str, Any]] = []
        self.tombstones: dict[str, dict[str, Any]] = {}
        self._load_inbox()
        self._conn = _Connection(port, host, self._on_frame)
        self._conn.connect()
        self._conn.request("HELLO", {
            "clientType": "DEVICE", "deviceId": code, "supportedVersions": [2],
        })
        result = self._conn.request("AUTH_DEVICE", {
            "deviceCode": code, "credential": secret, "deviceId": code, "secret": secret,
        })
        if not result.get("payload", {}).get("ok"):
            self._conn.close()
            raise PermissionError("device auth rejected: " + str(result.get("payload", {}).get("code")))
        self.device_id = result["payload"].get("deviceId")

    def _load_inbox(self) -> None:
        if self.inbox_file and self.inbox_file.is_file():
            try:
                self.inbox = json.loads(self.inbox_file.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                self.inbox = {}

    def _save_inbox(self) -> None:
        if not self.inbox_file:
            return
        self.inbox_file.parent.mkdir(parents=True, exist_ok=True)
        self.inbox_file.write_text(json.dumps(self.inbox, indent=1), encoding="utf-8")

    def _entry(self, payload: dict[str, Any]) -> dict[str, Any]:
        alert_id = str(payload.get("alertId"))
        entry = self.inbox.setdefault(alert_id, {
            "alertId": alert_id,
            "revision": payload.get("revision"),
            "deliveryId": payload.get("deliveryId"),
            "acked": False,
            "userAcked": False,
            "receivedAt": now_iso(),
        })
        return entry

    def _on_frame(self, envelope: dict[str, Any]) -> None:
        msg_type = envelope.get("type")
        payload = envelope.get("payload") or {}
        if msg_type == "ALERT_PUSH":
            entry = self._entry(payload)
            self.push_log.append({
                "kind": "push", "payload": payload, "at": now_iso(),
                "messageId": envelope.get("messageId"),
            })
            self.events.append({"kind": "push", "alertId": entry["alertId"]})
            self._save_inbox()
            if self.auto_ack:
                self.ack_received(entry["alertId"], entry["deliveryId"],
                                  entry["revision"], envelope.get("messageId"), wait=False)
        elif msg_type == "CANCEL_PUSH":
            alert_id = str(payload.get("alertId"))
            self.tombstones[alert_id] = payload
            self.push_log.append({"kind": "cancel", "payload": payload, "at": now_iso()})
            self.events.append({"kind": "cancel", "alertId": alert_id})
            self._send_ack("ACK_CANCEL", payload, envelope.get("messageId"), wait=False)
        elif msg_type == "SYNC_RESULT":
            for alert in payload.get("alerts") or []:
                self._entry(alert)
            for tomb in payload.get("tombstones") or payload.get("cancels") or []:
                self.tombstones[str(tomb.get("alertId"))] = tomb
            self._save_inbox()

    def _send_ack(self, msg_type: str, payload: dict[str, Any], correlation: str | None,
                  wait: bool) -> dict[str, Any] | None:
        frame = {
            "alertId": payload.get("alertId"),
            "deliveryId": payload.get("deliveryId"),
            "revision": payload.get("revision"),
            "deviceId": self.device_id,
            "ackAt": now_iso(),
        }
        if not wait:
            self._conn.send(msg_type, frame, correlation)
            return None
        return self._conn.request(msg_type, frame, timeout=8).get("payload", {})

    def ack_received(self, alert_id: str, delivery_id: str, revision: int,
                     correlation: str | None = None, wait: bool = True) -> dict[str, Any] | None:
        result = self._send_ack("ACK_RECEIVED", {
            "alertId": alert_id, "deliveryId": delivery_id, "revision": revision,
        }, correlation, wait)
        entry = self.inbox.get(str(alert_id))
        if entry is not None:
            entry["acked"] = True
            self._save_inbox()
        return result

    def ack_user(self, alert_id: str, user_id: str) -> dict[str, Any] | None:
        entry = self.inbox.get(str(alert_id))
        if entry is None:
            return None
        result = self._send_ack("ACK_USER", {
            "alertId": alert_id, "deliveryId": entry.get("deliveryId"),
            "revision": entry.get("revision"), "userId": user_id,
            "action": "ACKNOWLEDGE",
        }, None, True)
        if result and result.get("ok"):
            entry["userAcked"] = True
            self._save_inbox()
        return result

    def sync(self) -> dict[str, Any]:
        result = self._conn.request("SYNC_REQUEST", {
            "deviceId": self.device_id,
            "knownRevisions": {aid: entry.get("revision") for aid, entry in self.inbox.items()},
        }, timeout=12).get("payload", {})
        self._on_frame({"type": "SYNC_RESULT", "payload": result})
        return result

    def disconnect(self) -> None:
        self._conn.close()

    close = disconnect


# ---------------------------------------------------------------------------
# operator actor
# ---------------------------------------------------------------------------

class Operator:
    """Authenticated operator issuing command-plane frames."""

    def __init__(self, username: str, password: str, port: int = 9443,
                 host: str = DEFAULT_HOST):
        self._conn = _Connection(port, host, lambda env: None)
        self._conn.connect()
        self._conn.request("HELLO", {
            "clientType": "OPERATOR", "supportedVersions": [2],
        })
        result = self._conn.request("AUTH_OPERATOR", {
            "username": username, "password": password,
        }).get("payload", {})
        if not result.get("ok"):
            self._conn.close()
            raise PermissionError("operator auth rejected: " + str(result.get("code")))
        self.role = result.get("role")

    def publish(self, category: str = "FIRE_DRILL", priority: str = "CRITICAL",
                title: str = "SIMULATION DRILL", instruction: str = "Simulation only.",
                floor: int | None = None, zone: str | None = None,
                simulation: bool = True, **extra: Any) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "requestId": str(uuid.uuid4()),
            "category": category,
            "priority": priority,
            "title": title,
            "instruction": instruction,
            "simulation": simulation,
            "urgency": extra.get("urgency", "Immediate"),
            "severity": extra.get("severity", "Severe"),
            "certainty": extra.get("certainty", "Observed"),
        }
        if floor is not None:
            payload["floorNumber"] = int(floor)
        if zone is not None:
            payload["zoneCode"] = str(zone)
        for key in ("buildingId", "incidentId", "expiresAt"):
            if extra.get(key) is not None:
                payload[key] = extra[key]
        return self._conn.request("ALERT_PUBLISH", payload, timeout=15).get("payload", {})

    def cancel(self, alert_id: str, reason: str = "demo cancel") -> dict[str, Any]:
        return self._conn.request("ALERT_CANCEL", {
            "requestId": str(uuid.uuid4()), "alertId": alert_id, "reason": reason,
        }, timeout=15).get("payload", {})

    def close(self) -> None:
        self._conn.close()


# ---------------------------------------------------------------------------
# server lifecycle + DB provisioning
# ---------------------------------------------------------------------------

def _classpath(module: str) -> str:
    parts = [str(REPO / module / "target" / "classes")]
    for sibling in ("aegis-protocol", "aegis-core", "aegis-persistence"):
        parts.append(str(REPO / sibling / "target" / "classes"))
    cp_files = [REPO / module / "target" / f"cp-{module.replace('aegis-', '')}.txt",
                REPO / module / "cp.txt"]
    for cp_file in cp_files:
        if cp_file.is_file():
            content = cp_file.read_text(encoding="utf-8").strip().replace("\n", "")
            if content:
                parts.append(content)
            break
    return os.pathsep.join(parts)


def _port_open(port: int, host: str = "127.0.0.1") -> bool:
    with socket.socket() as sock:
        sock.settimeout(0.5)
        return sock.connect_ex((host, port)) == 0


class ServerProc:
    def __init__(self, db: str | Path, log: str | Path, tls_port: int = 9443,
                 ws_port: int = 9444):
        self.db = Path(db)
        self.log = Path(log)
        self.tls_port = int(tls_port)
        self.ws_port = int(ws_port)
        self.proc: subprocess.Popen | None = None

    @property
    def pid(self) -> int | None:
        return self.proc.pid if self.proc else None

    def start(self, timeout: float = 90.0) -> "ServerProc":
        if not (CERTS / "aegis-server.p12").is_file():
            raise FileNotFoundError("run scripts/gen-server-cert.sh first")
        self.log.parent.mkdir(parents=True, exist_ok=True)
        log_file = open(self.log, "w", encoding="utf-8")
        cmd = ["java", "-cp", _classpath("aegis-server"), "aegis.server.ServerMain",
               "--db", str(self.db), "--keystore", str(CERTS / "aegis-server.p12"),
               "--storepass", "aegis-demo", "--tls-port", str(self.tls_port),
               "--ws-port", str(self.ws_port)]
        self.proc = subprocess.Popen(cmd, cwd=REPO, stdout=log_file,
                                     stderr=subprocess.STDOUT)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"server exited early: see {self.log}")
            if _port_open(self.tls_port) and _port_open(self.ws_port):
                return self
            time.sleep(0.25)
        self.kill9()
        raise TimeoutError(f"server did not start on {self.tls_port}/{self.ws_port}")

    def stop(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)

    def kill9(self) -> None:
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait(timeout=10)


def _init_db(db: Path) -> None:
    db.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(str(db))
    try:
        con.execute("PRAGMA foreign_keys=ON")
        for name in ("001_base_schema.sql", "002_showcase_expansion.sql"):
            con.executescript((SCHEMA / name).read_text(encoding="utf-8"))
        con.commit()
    finally:
        con.close()


def seed_and_provision(db: str | Path, log: str | Path, n_sim: int = 0, n_real: int = 0,
                       tls_port: int = 9443, ws_port: int = 9444,
                       host: str = DEFAULT_HOST) -> tuple[ServerProc, list[dict[str, Any]]]:
    """Fresh DB + server seed + extra SIM-DEV/REAL-DEV fleet registration."""
    db_path = Path(db)
    _init_db(db_path)
    server = ServerProc(db_path, log, tls_port, ws_port).start()
    if not n_sim and not n_real:
        return server, []
    con = sqlite3.connect(str(db_path))
    con.row_factory = sqlite3.Row
    try:
        row = con.execute("SELECT credential_hash FROM devices LIMIT 1").fetchone()
        if row is None:
            raise RuntimeError("server did not seed the demo world")
        credential_hash = row["credential_hash"]
        floor_id = con.execute("SELECT id FROM floors WHERE floor_number=1 LIMIT 1").fetchone()["id"]
        zone_id = con.execute(
            "SELECT id FROM zones WHERE floor_id=? AND zone_code='A'", (floor_id,)).fetchone()["id"]
        stamp = now_iso()
        fleet: list[dict[str, Any]] = []
        for prefix, count in (("SIM-DEV", n_sim), ("REAL-DEV", n_real)):
            for index in range(1, count + 1):
                code = f"{prefix}-{index:04d}"
                device_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "aegis/" + code))
                con.execute(
                    "INSERT OR IGNORE INTO devices(id,floor_id,device_code,credential_hash,enabled,enrolled_at)"
                    " VALUES(?,?,?,?,1,?)",
                    (device_id, floor_id, code, credential_hash, stamp))
                con.execute(
                    "INSERT OR IGNORE INTO zone_devices(zone_id,device_id,assigned_at) VALUES(?,?,?)",
                    (zone_id, device_id, stamp))
                fleet.append({"code": code, "id": device_id})
        con.commit()
    finally:
        con.close()
    return server, fleet


def sim_cmd(agents: int, scenario: str, seed: int, duration: int, port: int,
            stagger_ms: int = 5, host: str = DEFAULT_HOST) -> list[str]:
    classpath = _classpath("aegis-simulator")
    return [
        "java", "-cp", classpath, "aegis.simulator.SimulatorMain",
        "--agents", str(agents), "--scenario", scenario, "--seed", str(seed),
        "--host", host, "--port", str(port),
        "--device-prefix", "SIM-DEV", "--device-secret", DEFAULT_SECRET,
        "--truststore", str(CERTS / "aegis-truststore.p12"),
        "--truststore-password", "aegis-demo",
        "--duration", str(duration), "--stagger-ms", str(stagger_ms),
    ]


# ---------------------------------------------------------------------------
# evidence helpers
# ---------------------------------------------------------------------------

def _rows(db: str | Path, sql: str, params: tuple = ()) -> list[dict[str, Any]]:
    con = sqlite3.connect(str(db))
    con.row_factory = sqlite3.Row
    try:
        return [dict(row) for row in con.execute(sql, params).fetchall()]
    finally:
        con.close()


def audit_rows(db: str | Path, actions: list[str] | None = None,
               limit: int = 1000) -> list[dict[str, Any]]:
    if actions:
        marks = ",".join("?" for _ in actions)
        sql = (f"SELECT * FROM (SELECT * FROM audit_events WHERE action IN ({marks}) "
               "ORDER BY occurred_at DESC LIMIT ?) ORDER BY occurred_at ASC")
        rows = _rows(db, sql, tuple(actions) + (int(limit),))
    else:
        rows = _rows(db, "SELECT * FROM (SELECT * FROM audit_events ORDER BY occurred_at DESC LIMIT ?)"
                     " ORDER BY occurred_at ASC", (int(limit),))
    return rows


def delivery_attempts(db: str | Path, alert_id: str) -> list[dict[str, Any]]:
    return _rows(db,
        "SELECT da.*, d.device_id, d.state FROM delivery_attempts da "
        "JOIN deliveries d ON d.id=da.delivery_id WHERE d.alert_id=? "
        "ORDER BY da.attempted_at, da.id", (alert_id,))


def db_stats(db: str | Path) -> dict[str, Any]:
    states = _rows(db, "SELECT state, COUNT(*) n FROM deliveries GROUP BY state")
    by_state = {row["state"]: row["n"] for row in states}
    total = sum(by_state.values())
    acks = _rows(db,
        "SELECT a.created_at, k.acked_at FROM device_acks k "
        "JOIN alerts a ON a.id=k.alert_id WHERE k.ack_kind='RECEIVED'")
    latencies = sorted(max(0.0, (parse_ts(row["acked_at"]) - parse_ts(row["created_at"])).total_seconds() * 1000)
                       for row in acks)
    p95 = latencies[min(len(latencies) - 1, int(len(latencies) * 0.95))] if latencies else None
    attempts = _rows(db, "SELECT COUNT(*) n FROM delivery_attempts")[0]["n"]
    audit = _rows(db, "SELECT action, outcome, COUNT(*) n FROM audit_events "
                       "GROUP BY action, outcome ORDER BY action, outcome")
    audit_counts = {f"{row['action']}/{row['outcome']}": row["n"] for row in audit}
    return {
        "deliveriesByState": by_state,
        "deliveriesTotal": total,
        "ackReceivedCount": len(acks),
        "ackReceivedRate": (len(acks) / total) if total else None,
        "latencyMsFromDb": round(p95, 2) if p95 is not None else None,
        "latencySampleCount": len(latencies),
        "attemptsTotal": attempts,
        "retriesFromAttempts": max(0, attempts - total),
        "auditCounts": audit_counts,
    }


def parse_sim_summary(text: str) -> dict[str, int]:
    patterns = {
        "agentsStarted": r"agents started/finished\s*:\s*(\d+)\s*/\s*(\d+)",
        "connectSuccess": r"connects ok/failed\s*:\s*(\d+)\s*/\s*(\d+)",
        "alertsReceived": r"alerts received\s*:\s*(\d+)",
        "acksSent": r"ACK_RECEIVED sent\s*:\s*(\d+)",
        "reconnects": r"reconnects:\s*(\d+)",
        "connectionErrors": r"connection errors\s*:\s*(\d+)",
        "authRejections": r"auth rejections \(permanent\):\s*(\d+)",
    }
    result: dict[str, int] = {}
    for key, pattern in patterns.items():
        match = re.search(pattern, text)
        if match:
            result[key] = int(match.group(1))
    for key, group in (("agentsFinished", 2), ("connectFailures", 2)):
        pattern = patterns["agentsStarted" if key == "agentsFinished" else "connectSuccess"]
        match = re.search(pattern, text)
        if match:
            result[key] = int(match.group(group))
    return result


def ws_collect(port: int, seconds: float = 5.0) -> list[dict[str, Any]]:
    """Collect status/1 JSON frames from the local WebSocket feed."""
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    frames: list[dict[str, Any]] = []
    with socket.create_connection(("127.0.0.1", int(port)), timeout=5) as sock:
        sock.settimeout(max(1.0, seconds))
        request = (f"GET / HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
                   "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        sock.sendall(request.encode("ascii"))
        header = bytearray()
        while b"\r\n\r\n" not in header:
            chunk = sock.recv(1)
            if not chunk:
                return frames
            header.extend(chunk)
        if b"101" not in header.split(b"\r\n", 1)[0]:
            return frames
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                first = sock.recv(2)
            except socket.timeout:
                break
            if len(first) < 2:
                break
            length = first[1] & 0x7F
            if length == 126:
                length = struct.unpack(">H", _recv_exact(sock, 2))[0]
            elif length == 127:
                length = struct.unpack(">Q", _recv_exact(sock, 8))[0]
            body = _recv_exact(sock, length)
            try:
                frames.append(json.loads(body.decode("utf-8")))
            except (ValueError, UnicodeDecodeError):
                continue
    return frames
