#!/usr/bin/env python3
"""Regression tests for task 2: tracked ebsp module, safe initdb, unique fleet."""
import importlib.util
import shutil
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
BASH = ("C:/Program Files/Git/bin/bash.exe"
        if Path("C:/Program Files/Git/bin/bash.exe").is_file()
        else (shutil.which("bash") or "bash"))


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

    def test_run_meta_records_machine(self):
        spec = importlib.util.spec_from_file_location("ebsp_meta", EBSP)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        meta = module.run_meta()
        self.assertTrue(meta["machine"])
        self.assertTrue(meta["runId"])

    def test_default_fleet_covers_f05_publish_zone(self):
        codes = load_bridge().fleet_codes(20)
        self.assertTrue(any(code.startswith("DEV-A-F05-") for code in codes),
                        "default fleet must cover the documented F05 publish demo")

    def test_parse_sim_summary_reads_fault_counters(self):
        spec = importlib.util.spec_from_file_location("ebsp_summary", EBSP)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        text = ("agents started/finished : 100 / 100\n"
                "connects ok/failed      : 100 / 0   reconnects: 3\n"
                "alerts received         : 200   (duplicate pushes: 4, cancels: 2)\n"
                "ACK_RECEIVED sent       : 196   dropped: 15   delayed: 7   extra copies: 5\n"
                "fault disconnects       : 9   SYNC requests: 6   heartbeats answered: 100\n"
                "connection errors       : 0   auth rejections (permanent): 0\n")
        summary = module.parse_sim_summary(text)
        self.assertEqual(15, summary["acksDropped"])
        self.assertEqual(7, summary["acksDelayed"])
        self.assertEqual(9, summary["disconnects"])
        self.assertEqual(4, summary["duplicatePushes"])


    def test_seed_and_provision_registers_fleet_before_serving(self):
        spec = importlib.util.spec_from_file_location("ebsp_provision", EBSP)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as tmp:
            db = Path(tmp) / "provision.db"
            server = None
            try:
                server, fleet = module.seed_and_provision(
                    db, Path(tmp) / "server.log", n_sim=1, n_real=1,
                    tls_port=19443, ws_port=19444)
                self.assertEqual(["SIM-DEV-0001", "REAL-DEV-0001"], [x["code"] for x in fleet])
                con = sqlite3.connect(str(db))
                try:
                    codes = {row[0] for row in con.execute("SELECT device_code FROM devices")}
                finally:
                    con.close()
                self.assertTrue(all(x["code"] in codes for x in fleet))
            finally:
                if server is not None:
                    server.stop()
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
                capture_output=True, text=True, timeout=60, env=os.environ.copy())
            self.assertEqual(2, proc.returncode, proc.stderr)
            self.assertIn("already exists", proc.stderr)
            con = sqlite3.connect(str(db))
            rows = con.execute("SELECT id FROM sentinel").fetchall()
            con.close()
            self.assertEqual([(7,)], rows, "existing DB must survive a refused initdb")


if __name__ == "__main__":
    unittest.main(verbosity=2)
