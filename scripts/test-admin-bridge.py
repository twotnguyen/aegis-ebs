#!/usr/bin/env python3
"""Offline tests for the Admin Console surface of web-operator-bridge.py.

Runs with `python3 scripts/test-admin-bridge.py`. No AEGIS server, TLS gateway
or listening socket is started: the bridge module is imported from source,
its handler is driven in-process, and all data lives in a temp SQLite file.
"""
import importlib.util
import json
import os
import sqlite3
import sys
import tempfile
import unittest
import uuid
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
BRIDGE = SCRIPTS / "web-operator-bridge.py"
SECRET = "super-secret-hash-DO-NOT-LEAK"

SCHEMA = """
CREATE TABLE buildings (id TEXT PRIMARY KEY, code TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL);
CREATE TABLE floors (id TEXT PRIMARY KEY,
                     building_id TEXT NOT NULL REFERENCES buildings(id),
                     floor_number INTEGER NOT NULL, label TEXT,
                     UNIQUE(building_id, floor_number));
CREATE TABLE users (id TEXT PRIMARY KEY, username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    role TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL);
CREATE TABLE role_grants (id TEXT PRIMARY KEY,
                          user_id TEXT NOT NULL REFERENCES users(id),
                          building_id TEXT REFERENCES buildings(id),
                          floor_id TEXT REFERENCES floors(id),
                          scope TEXT NOT NULL, granted_at TEXT NOT NULL);
CREATE TABLE alerts (id TEXT PRIMARY KEY,
                     building_id TEXT NOT NULL REFERENCES buildings(id),
                     created_by TEXT NOT NULL REFERENCES users(id),
                     category TEXT NOT NULL, priority TEXT NOT NULL,
                     state TEXT NOT NULL, current_revision INTEGER NOT NULL DEFAULT 1,
                     created_at TEXT NOT NULL, expires_at TEXT);
CREATE TABLE audit_events (id TEXT PRIMARY KEY, occurred_at TEXT NOT NULL,
                           actor_id TEXT, action TEXT NOT NULL, scope TEXT,
                           outcome TEXT NOT NULL, correlation_id TEXT,
                           payload_json TEXT);
"""

TS = "2026-01-01T00:00:00.000000000Z"


def load_bridge(name, db_path, host=None, origins=None):
    """Import the bridge with a chosen env, then restore the caller's env."""
    keys = {
        "AEGIS_DB": str(db_path),
        "WEB_BRIDGE_HOST": host,
        "WEB_BRIDGE_ORIGINS": origins,
    }
    previous = {key: os.environ.get(key) for key in keys}
    try:
        for key, value in keys.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        spec = importlib.util.spec_from_file_location(name, BRIDGE)
        assert spec is not None and spec.loader is not None
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
        return module
    finally:
        for key, value in previous.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value


def seed(db_path):
    conn = sqlite3.connect(str(db_path))
    try:
        conn.executescript(SCHEMA)
        conn.executemany("INSERT INTO buildings VALUES (?,?,?)", [
            ("B-1", "MAIN", "Main building"),
            ("B-2", "ANNEX", "Annex"),
        ])
        conn.executemany("INSERT INTO floors VALUES (?,?,?,?)", [
            ("F-1-1", "B-1", 1, "F01"),
            ("F-1-2", "B-1", 2, "F02"),
            ("F-2-1", "B-2", 1, "A01"),
        ])
        conn.executemany("INSERT INTO users VALUES (?,?,?,?,?,?)", [
            ("U-ALICE", "alice", SECRET, "SECURITY_OPERATOR", 1, TS),
            ("U-BOB", "bob", "another-secret-hash", "FLOOR_MANAGER", 0, TS),
            ("U-ADMIN", "root", "root-secret-hash-DO-NOT-LEAK", "SYSTEM_ADMIN", 1, TS),
        ])
        conn.execute("INSERT INTO role_grants VALUES (?,?,?,?,?,?)",
                     ("G-SEED", "U-BOB", "B-1", "F-1-2", "VIEW", TS))
        conn.executemany("INSERT INTO alerts VALUES (?,?,?,?,?,?,?,?,?)", [
            ("A-ACTIVE", "B-1", "U-ALICE", "FIRE_DRILL", "CRITICAL",
             "ACTIVE", 1, TS, None),
            ("A-CANCELLED", "B-1", "U-ALICE", "FIRE_DRILL", "CRITICAL",
             "CANCELLED", 1, TS, None),
        ])
        conn.executemany(
            "INSERT INTO audit_events VALUES (?,?,?,?,?,?,?,?)", [
                ("E-1", "2026-01-02T00:00:00.000000000Z", "U-BOB",
                 "USER_LOGIN", "B-1", "OK", None, '{"password":"hunter2"}'),
                ("E-2", "2026-01-03T00:00:00.000000000Z", "U-ALICE",
                 "ROLE_GRANT_CREATED", "B-1", "OK", "G-SEED",
                 '{"grantId":"G-SEED"}'),
                ("E-3", "2026-01-04T00:00:00.000000000Z", "U-BOB",
                 "USER_LOGIN", "B-1", "FAIL", None, "{}"),
            ])
        conn.commit()
    finally:
        conn.close()


class InProcessHandler:
    """Drive the real Handler methods and capture _send() without a socket."""

    def __init__(self, bridge, body=None):
        self.bridge = bridge
        self.body = body or {}
        self.sent = (0, {})  # (status, payload) captured by _send()
        self.headers = {}

    def _json_body(self):
        return self.body

    def _send(self, status, obj):
        self.sent = status, obj

    def get(self, path, query=""):
        full = path + ("?" + query if query else "")
        parts = full.split("?", 1)
        route, query_string = parts[0], (parts[1] if len(parts) > 1 else "")
        self.bridge.Handler._reply(
            self, route, lambda: self.bridge.Handler._get(
                self, route, query_string))
        return self.sent

    def post(self, path, body):
        route = path.split("?", 1)[0]
        self.body = body
        self.bridge.Handler._reply(
            self, route, lambda: self.bridge.Handler._post(
                self, route, self._json_body()))
        return self.sent


class AdminBridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.db = Path(cls.tmp.name) / "admin-test.db"
        seed(cls.db)
        cls.bridge = load_bridge("web_operator_bridge_admin", cls.db)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()
        sys.modules.pop("web_operator_bridge_admin", None)

    def setUp(self):
        self.handler = lambda body=None: InProcessHandler(self.bridge, body)

    def req(self, method, path, body=None, query=""):
        handler = self.handler(body)
        return handler.get(path, query) if method == "GET" else handler.post(path, body)

    def assert_clean(self, payload):
        text = json.dumps(payload, ensure_ascii=False)
        self.assertNotIn(SECRET, text)
        self.assertNotIn("another-secret-hash", text)
        self.assertNotIn("root-secret-hash-DO-NOT-LEAK", text)
        self.assertNotIn("password_hash", text)

    def audit_count(self):
        return self.bridge.admin_overview()["counts"]["auditEvents"]

    def test_01_default_config_stays_loopback(self):
        self.assertEqual(self.bridge.BIND_HOST, "127.0.0.1")
        self.assertIn("http://localhost:5173", self.bridge.ALLOWED_ORIGINS)
        self.assertIn("http://127.0.0.1:5173", self.bridge.ALLOWED_ORIGINS)
        self.assertEqual(self.bridge.AEGIS_DB, str(self.db))

    def test_02_opt_in_host_and_origins(self):
        cfg = load_bridge(
            "web_operator_bridge_cfg", self.db, host="0.0.0.0",
            origins=" https://admin.ts.example ,http://127.0.0.1:5173,,https://two.example ")
        try:
            self.assertEqual(cfg.BIND_HOST, "0.0.0.0")
            self.assertIn("https://admin.ts.example", cfg.ALLOWED_ORIGINS)
            self.assertIn("https://two.example", cfg.ALLOWED_ORIGINS)
            self.assertIn("http://localhost:5173", cfg.ALLOWED_ORIGINS)
            self.assertNotIn("", cfg.ALLOWED_ORIGINS)
            self.assertEqual(cfg.ALLOWED_ORIGINS.count("http://127.0.0.1:5173"), 1)
        finally:
            sys.modules.pop("web_operator_bridge_cfg", None)

    def test_03_overview_counts(self):
        status, result = self.req("GET", "/api/admin/overview")
        self.assertEqual(status, 200)
        self.assertEqual(result["counts"], {
            "users": 3, "roleGrants": 1, "auditEvents": 3, "activeAlerts": 1})
        self.assert_clean(result)

    def test_04_users_without_password_hash(self):
        status, result = self.req("GET", "/api/admin/users")
        self.assertEqual(status, 200)
        self.assertEqual([u["username"] for u in result["users"]], ["alice", "bob", "root"])
        for user in result["users"]:
            self.assertEqual(set(user), {"id", "username", "role", "enabled", "created_at"})
        self.assert_clean(result)

    def test_05_grants_join_fields(self):
        status, result = self.req("GET", "/api/admin/grants")
        self.assertEqual(status, 200)
        grant = next(g for g in result["grants"] if g["id"] == "G-SEED")
        self.assertEqual(grant, {
            "id": "G-SEED", "username": "bob", "role": "FLOOR_MANAGER",
            "building_id": "B-1", "floor_id": "F-1-2",
            "scope": "VIEW", "granted_at": TS})
        self.assert_clean(result)

    def test_06_audit_filter_order_limit_and_scrub(self):
        status, result = self.req("GET", "/api/admin/audit")
        self.assertEqual(status, 200)
        self.assertEqual([e["id"] for e in result["events"]], ["E-3", "E-2", "E-1"])
        self.assertEqual(result["limit"], 100)

        status, result = self.req("GET", "/api/admin/audit",
                                  query="action=USER_LOGIN")
        self.assertEqual([e["id"] for e in result["events"]], ["E-3", "E-1"])

        status, result = self.req("GET", "/api/admin/audit",
                                  query="outcome=OK")
        self.assertEqual([e["id"] for e in result["events"]], ["E-2", "E-1"])

        status, result = self.req("GET", "/api/admin/audit",
                                  query="action=USER_LOGIN&outcome=OK")
        self.assertEqual([e["id"] for e in result["events"]], ["E-1"])

        status, result = self.req("GET", "/api/admin/audit", query="limit=1")
        self.assertEqual([e["id"] for e in result["events"]], ["E-3"])

        for bad in ("limit=0", "limit=501", "limit=abc", "limit="):
            status, result = self.req("GET", "/api/admin/audit", query=bad)
            self.assertEqual(status, 400, bad)
            self.assert_clean(result)

        clean = self.req("GET", "/api/admin/audit", query="action=USER_LOGIN")[1]
        self.assertNotIn("hunter2", json.dumps(clean["events"]))
        scrubbed = [e for e in clean["events"] if e["id"] == "E-1"]
        self.assertEqual(len(scrubbed), 1)
        self.assertIn("***", scrubbed[0]["payload_json"])

    def test_07_approve_inserts_uuid_grant_and_audit(self):
        before_grants = self.bridge.admin_overview()["counts"]["roleGrants"]
        before_audit = self.audit_count()
        status, result = self.req("POST", "/api/admin/grants/approve", {
            "username": "alice", "buildingId": "B-1", "floorId": "F-1-1",
            "scope": "PUBLISH", "operator": "root"})
        self.assertEqual(status, 200, result)
        self.assert_clean(result)
        grant = result["grant"]
        uuid.UUID(grant["id"])
        self.assertEqual(grant["username"], "alice")
        self.assertEqual(grant["role"], "SECURITY_OPERATOR")
        self.assertEqual(grant["scope"], "PUBLISH")

        conn = sqlite3.connect(str(self.db))
        conn.row_factory = sqlite3.Row
        try:
            row = conn.execute("SELECT * FROM role_grants WHERE id=?",
                               (grant["id"],)).fetchone()
            self.assertIsNotNone(row)
            self.assertEqual(row["user_id"], "U-ALICE")
            self.assertEqual(row["floor_id"], "F-1-1")
            event = conn.execute("SELECT * FROM audit_events WHERE id=?",
                                 (result["auditId"],)).fetchone()
            self.assertIsNotNone(event)
            self.assertEqual(event["action"], "OPERATOR_GRANT_APPROVED")
            self.assertEqual(event["outcome"], "OK")
            self.assertEqual(event["actor_id"], "U-ADMIN")
            self.assertEqual(event["correlation_id"], grant["id"])
            payload = json.loads(event["payload_json"])
            self.assertEqual(payload["grantId"], grant["id"])
            self.assertEqual(payload["scope"], "PUBLISH")
            self.assertNotIn("password", json.dumps(payload))
        finally:
            conn.close()
        self.assertEqual(self.bridge.admin_overview()["counts"]["roleGrants"],
                         before_grants + 1)
        self.assertEqual(self.audit_count(), before_audit + 1)

    def test_08_approve_validation_is_400(self):
        before = self.bridge.admin_overview()["counts"]
        bad_bodies = [
            {},
            {"username": "alice"},
            {"username": "ghost", "buildingId": "B-1"},
            {"username": "alice", "buildingId": "NOPE"},
            {"username": "alice", "buildingId": "B-1", "floorId": "F-2-1"},
            {"username": "alice", "buildingId": "B-1", "floorId": "NOPE"},
            {"username": "alice", "buildingId": "B-1", "floorId": 7},
            {"username": "alice", "buildingId": "B-1", "operator": 42},
            {"username": "alice", "buildingId": "B-1", "operator": "alice"},
            {"username": "alice", "buildingId": "B-1", "operator": "ghost"},
        ]
        for body in bad_bodies:
            status, result = self.req("POST", "/api/admin/grants/approve", body)
            self.assertEqual(status, 400, body)
            self.assertTrue(result["ok"] is False)
            self.assert_clean(result)
        self.assertEqual(self.bridge.admin_overview()["counts"], before)

    def test_09_revoke_deletes_grant_and_audits(self):
        created = self.bridge.admin_grant_approve({
            "username": "bob", "buildingId": "B-1", "floorId": "F-1-2",
            "operator": "root"})
        grant_id = created["grant"]["id"]
        before_audit = self.audit_count()
        status, result = self.req("POST", "/api/admin/grants/revoke", {
            "grantId": grant_id, "operator": "root"})
        self.assertEqual(status, 200, result)
        self.assert_clean(result)
        self.assertEqual(result["revoked"], grant_id)
        conn = sqlite3.connect(str(self.db))
        conn.row_factory = sqlite3.Row
        try:
            self.assertIsNone(conn.execute(
                "SELECT 1 FROM role_grants WHERE id=?", (grant_id,)).fetchone())
            event = conn.execute("SELECT * FROM audit_events WHERE id=?",
                                 (result["auditId"],)).fetchone()
            self.assertEqual(event["action"], "OPERATOR_GRANT_REVOKED")
            self.assertEqual(event["outcome"], "OK")
            self.assertEqual(event["actor_id"], "U-ADMIN")
            self.assertEqual(json.loads(event["payload_json"])["grantId"], grant_id)
        finally:
            conn.close()
        self.assertEqual(self.audit_count(), before_audit + 1)

        status, result = self.req("POST", "/api/admin/grants/revoke",
                                  {"grantId": "missing"})
        self.assertEqual(status, 400)
        status, result = self.req("POST", "/api/admin/grants/revoke", {})
        self.assertEqual(status, 400)
        self.assertEqual(self.audit_count(), before_audit + 1)

    def test_10_legacy_routes_and_404(self):
        status, result = self.req("GET", "/api/fleet/stats")
        self.assertEqual(status, 200)
        self.assertTrue(result["ok"])
        self.assertEqual(result["fleetCount"], 0)
        status, result = self.req("GET", "/api/nope")
        self.assertEqual(status, 404)
        status, result = self.req("POST", "/api/nope", {})
        self.assertEqual(status, 404)
        self.assert_clean(result)

    def test_11_safe_error_never_echoes_secrets(self):
        err = self.bridge.safe_error(
            ValueError('bad password="%s" secret=abc123' % SECRET),
            secrets=(SECRET,))
        self.assertNotIn(SECRET, err)
        self.assertNotIn("abc123", err)
        self.assertIn("***", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
