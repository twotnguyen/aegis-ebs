#!/usr/bin/env python3
"""Regression tests for task 2: tracked ebsp module, safe initdb, unique fleet."""
import importlib.util
import os
import sqlite3
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parent
BRIDGE = SCRIPTS / "web-operator-bridge.py"
EBSP = SCRIPTS / "ebsp.py"
INITDB = SCRIPTS / "initdb.sh"
BASH = "C:/Program Files/Git/bin/bash.exe"


def load_bridge():
    spec = importlib.util.spec_from_file_location("bridge_task2", BRIDGE)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Task2Tests(unittest.TestCase):
    def test_ebsp_module_is_tracked_and_importable(self):
        self.assertTrue(EBSP.is_file(), "scripts/ebsp.py must be present in the checkout")
        spec = importlib.util.spec_from_file_location("ebsp_task2", EBSP)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for name in ("REPO", "Device", "Operator", "ServerProc", "seed_and_provision"):
            self.assertTrue(hasattr(module, name), name)

    def test_fleet_codes_unique_for_49_and_480(self):
        bridge = load_bridge()
        for count in (49, 480):
            codes = bridge.fleet_codes(count)
            self.assertEqual(count, len(codes))
            self.assertEqual(count, len(set(codes)), f"duplicate codes at count={count}")

    def test_initdb_preserves_existing_database_without_force(self):
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "aegis.db"
            con = sqlite3.connect(str(db))
            con.execute("CREATE TABLE sentinel (id INTEGER PRIMARY KEY)")
            con.execute("INSERT INTO sentinel VALUES (7)")
            con.commit()
            con.close()
            proc = subprocess.run(
                [BASH, str(INITDB), db.as_posix()], cwd=REPO,
                capture_output=True, text=True, timeout=60,
                env={**os.environ, "PATH": "C:/Users/Admin/bin;" + os.environ.get("PATH", "")})
            self.assertNotEqual(0, proc.returncode, "initdb must refuse an existing DB")
            con = sqlite3.connect(str(db))
            rows = con.execute("SELECT id FROM sentinel").fetchall()
            con.close()
            self.assertEqual([(7,)], rows, "existing DB must survive a refused initdb")


if __name__ == "__main__":
    unittest.main(verbosity=2)
