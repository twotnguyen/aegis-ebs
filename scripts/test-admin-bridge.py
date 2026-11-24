#!/usr/bin/env python3
"""Offline tests for the authenticated Admin Console surface of web-operator-bridge.py."""
import importlib.util
import json
import os
import sqlite3
import sys
import tempfile
import types
import unittest
import uuid
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
BRIDGE = SCRIPTS / "web-operator-bridge.py"
SECRET = "super-secret-hash-DO-NOT-LEAK"
PASSWORDS = {"root": "root-pass", "alice": "alice-pass", "bob": "bob-pass"}

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


def seed(db_path):
    conn = sqlite3.connect(str(db_path))
    try:
        conn.executescript(SCHEMA)
        conn.executemany("INSERT INTO buildings VALUES (?,?,?)", [
            ("B-1", "MAIN", "Main building"), ("B-2", "ANNEX", "Annex")])
        conn.executemany("INSERT INTO floors VALUES (?,?,?,?)", [
            ("F-1-1", "B-1", 1, "F01"), ("F-1-2", "B-1", 2, "F02"),
            ("F-2-1", "B-2", 1, "A01")])
        conn.executemany("INSERT INTO users VALUES (?,?,?,?,?,?)", [
            ("U-ALICE", "alice", SECRET, "SECURITY_OPERATOR", 1, TS),
            ("U-BOB", "bob", "another-secret-hash", "FLOOR_MANAGER", 0, TS),
            ("U-ADMIN", "root", "root-secret-hash-DO-NOT-LEAK", "SYSTEM_ADMIN", 1, TS)])
        conn.execute("INSERT INTO role_grants VALUES (?,?,?,?,?,?)",
                     ("G-SEED", "U-BOB", "B-1", "F-1-2", "VIEW", TS))
        conn.executemany("INSERT INTO alerts VALUES (?,?,?,?,?,?,?,?,?)", [
            ("A-ACTIVE", "B-1", "U-ALICE", "FIRE_DRILL", "CRITICAL",
             "ACTIVE", 1, TS, None),
            ("A-CANCELLED", "B-1", "U-ALICE", "FIRE_DRILL", "CRITICAL",
             "CANCELLED", 1, TS, None)])
        conn.executemany("INSERT INTO audit_events VALUES (?,?,?,?,?,?,?,?)", [
            ("E-1", "2026-01-02T00:00:00.000000000Z", "U-BOB",
             "USER_LOGIN", "B-1", "OK", None, '{"password":"hunter2"}'),
            ("E-2", "2026-01-03T00:00:00.000000000Z", "U-ALICE",
             "ROLE_GRANT_CREATED", "B-1", "OK", "G-SEED",
             '{"grantId":"G-SEED"}'),
            ("E-3", "2026-01-04T00:00:00.000000000Z", "U-BOB",
             "USER_LOGIN", "B-1", "FAIL", None, "{}")])
        conn.commit()
    finally:
        conn.close()


def fake_ebsp_module(db_path):
    """Stand in for the slow/external TLS auth at the test boundary."""
    class BoundOperator:
        def __init__(self, username, password, port=None):
            self.role = None
            self.user_id = None
            con = sqlite3.connect(str(db_path))
            try:
                row = con.execute(
                    "SELECT id, role, enabled FROM users WHERE username=?",
                    (username,)).fetchone()
            finally:
                con.close()
            if row is None or PASSWORDS.get(username) != password or not row[2]:
                raise PermissionError("auth rejected")
            self.role = row[1]
            self.user_id = row[0]

        def close(self):
            pass

    module = types.ModuleType("ebsp")
    module.Operator = BoundOperator
    module.REPO = str(SCRIPTS.parent)
    return module


def load_bridge(name, db_path, host=None, origins=None):
    keys = {"AEGIS_DB": str(db_path), "WEB_BRIDGE_HOST": host,
            "WEB_BRIDGE_ORIGINS": origins}
    previous = {key: os.environ.get(key) for key in keys}
    old_ebsp = sys.modules.get("ebsp")
    try:
        for key, value in keys.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value
        sys.modules["ebsp"] = fake_ebsp_module(db_path)
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
        if old_ebsp is None:
            sys.modules.pop("ebsp", None)
        else:
            sys.modules["ebsp"] = old_ebsp


class InProcessHandler:
    def __init__(self, bridge, body=None, headers=None):
        self.bridge = bridge
        self.body = body or {}
        self.headers = headers or {}
        self.sent = (0, {})

    def _json_body(self):
        return self.body

    def _send(self, status, obj):
        self.sent = status, obj

    def get(self, path, query=""):
        full = path + ("?" + query if query else "")
        route, query_string = (full.split("?", 1) + [""])[:2]
        self.bridge.Handler._reply(
            self, route, lambda: self.bridge.Handler._get(self, route, query_string, self.headers))
        return self.sent

    def post(self, path, body):
        route = path.split("?", 1)[0]
        self.body = body
        self.bridge.Handler._reply(
            self, route, lambda: self.bridge.Handler._post(
                self, route, self._json_body(), self.headers))
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

    def req(self, method, path, body=None, query="", token=None):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        handler = InProcessHandler(self.bridge, body, headers)
        return handler.get(path, query) if method == "GET" else handler.post(path, body)

    def login(self, username="root", password="root-pass"):
        status, result = self.req("POST", "/api/admin/login",
                                  {"username": username, "password": password})
        self.assertEqual(200, status, result)
        return result["token"]

    def audit_count(self):
        return self.bridge.admin_overview()["counts"]["auditEvents"]

    def assert_clean(self, payload):
        text = json.dumps(payload, ensure_ascii=False)
        for secret in (SECRET, "another-secret-hash", "root-secret-hash-DO-NOT-LEAK",
                       "root-pass", "password_hash"):
            self.assertNotIn(secret, text)

    def test_01_default_config_stays_loopback(self):
        self.assertEqual("127.0.0.1", self.bridge.BIND_HOST)
        self.assertIn("http://localhost:5173", self.bridge.ALLOWED_ORIGINS)

    def test_02_unauthenticated_admin_routes_401(self):
        for method, path, body in (
            ("GET", "/api/admin/overview", None),
            ("GET", "/api/admin/users", None),
            ("GET", "/api/admin/grants", None),
            ("GET", "/api/admin/audit", None),
            ("POST", "/api/admin/grants/approve", {}),
            ("POST", "/api/admin/grants/revoke", {}),
        ):
            status, result = self.req(method, path, body)
            self.assertEqual(401, status, (method, path, result))

    def test_03_login_rejects_bad_credentials(self):
        for username, password in (("root", "wrong"), ("alice", "alice-pass"),
                                   ("bob", "bob-pass"), ("ghost", "root-pass")):
            status, result = self.req("POST", "/api/admin/login",
                                      {"username": username, "password": password})
            self.assertEqual(401, status, (username, result))
            self.assert_clean(result)

    def test_04_authenticated_reads_and_actor_from_session(self):
        token = self.login()
        status, result = self.req("GET", "/api/admin/overview", token=token)
        self.assertEqual(200, status, result)
        self.assertEqual({"users": 3, "roleGrants": 1, "auditEvents": 3,
                          "activeAlerts": 1}, result["counts"])
        status, result = self.req("POST", "/api/admin/grants/approve", {
            "username": "alice", "buildingId": "B-1", "operator": "alice",
        }, token=token)
        self.assertEqual(200, status, result)
        con = sqlite3.connect(str(self.db))
        try:
            event = con.execute(
                "SELECT actor_id FROM audit_events WHERE id=?",
                (result["auditId"],)).fetchone()
            self.assertEqual("U-ADMIN", event[0])
        finally:
            con.close()

    def test_05_view_scope_rejected(self):
        token = self.login()
        before = self.bridge.admin_overview()["counts"]["roleGrants"]
        status, result = self.req("POST", "/api/admin/grants/approve", {
            "username": "alice", "buildingId": "B-1", "scope": "VIEW",
        }, token=token)
        self.assertEqual(400, status, result)
        self.assertEqual(before, self.bridge.admin_overview()["counts"]["roleGrants"])

    def test_06_floor_scoped_seops_rejected(self):
        token = self.login()
        status, result = self.req("POST", "/api/admin/grants/approve", {
            "username": "alice", "buildingId": "B-1", "floorId": "F-1-1",
        }, token=token)
        self.assertEqual(400, status, result)
        status, result = self.req("POST", "/api/admin/grants/approve", {
            "username": "alice", "buildingId": "B-1",
        }, token=token)
        self.assertEqual(200, status, result)

    def test_07_duplicate_approve_is_idempotent(self):
        token = self.login()
        body = {"username": "alice", "buildingId": "B-1"}
        first = self.req("POST", "/api/admin/grants/approve", body, token=token)[1]
        before = self.bridge.admin_overview()["counts"]
        second = self.req("POST", "/api/admin/grants/approve", body, token=token)[1]
        self.assertTrue(second.get("duplicate"))
        self.assertEqual(first["grant"]["id"], second["grant"]["id"])
        self.assertEqual(before, self.bridge.admin_overview()["counts"])

    def test_08_revoke_deletes_grant_and_audits(self):
        token = self.login()
        created = self.bridge.admin_grant_approve({
            "username": "alice", "buildingId": "B-1"}, "U-ADMIN")
        grant_id = created["grant"]["id"]
        status, result = self.req("POST", "/api/admin/grants/revoke",
                                  {"grantId": grant_id}, token=token)
        self.assertEqual(200, status, result)
        con = sqlite3.connect(str(self.db))
        try:
            self.assertIsNone(con.execute(
                "SELECT 1 FROM role_grants WHERE id=?", (grant_id,)).fetchone())
            event = con.execute("SELECT actor_id FROM audit_events WHERE id=?",
                                (result["auditId"],)).fetchone()
            self.assertEqual("U-ADMIN", event[0])
        finally:
            con.close()

    def test_09_audit_filters_and_scrub(self):
        token = self.login()
        status, result = self.req("GET", "/api/admin/audit",
                                  query="action=USER_LOGIN", token=token)
        self.assertEqual(200, status)
        self.assertEqual(["E-3", "E-1"], [e["id"] for e in result["events"]])
        for bad in ("limit=0", "limit=501", "limit=abc", "limit="):
            status, result = self.req("GET", "/api/admin/audit",
                                      query=bad, token=token)
            self.assertEqual(400, status, bad)
        clean = self.req("GET", "/api/admin/audit", query="action=USER_LOGIN",
                         token=token)[1]
        self.assertNotIn("hunter2", json.dumps(clean["events"]))
        self.assertIn("***", next(e for e in clean["events"] if e["id"] == "E-1")["payload_json"])

    def test_10_legacy_routes_404_and_safe_error(self):
        self.assertEqual(200, self.req("GET", "/api/fleet/stats")[0])
        self.assertEqual(404, self.req("GET", "/api/nope")[0])
        err = self.bridge.safe_error(ValueError(
            'bad password="%s" secret=abc123' % SECRET), secrets=(SECRET,))
        self.assertNotIn(SECRET, err)
        self.assertIn("***", err)


if __name__ == "__main__":
    unittest.main(verbosity=2)
