"""Offline fixtures; embedded preparation is portable, shell integration requires Linux.

QDATA_TEST_{MYSQL,HIVE}_DRIVER_JAR enable pinned-artifact checks. No jobs or JDBC
connections are started. Tiny fixture JAR bytes below only test file preparation.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest

SCRIPT = Path(__file__).parents[1] / "prepare-datax.sh"
MYSQL_NAME = "mysql-connector-j-9.7.0.jar"
HIVE_NAME = "hive-jdbc-4.2.0-standalone.jar"
HIVE_CLASS = "org.apache.hive.jdbc.HiveDriver"
MYSQL_CLASS = "com.mysql.cj.jdbc.Driver"


class DataxPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.payload = self.root / "datax"
        for relative in ("bin", "lib", "plugin", "conf"):
            (self.payload / relative).mkdir(parents=True)
        (self.payload / "bin/datax.py").write_text("# fixture; no job execution\n")
        self.mysql = self.root / MYSQL_NAME
        self.hive = self.root / HIVE_NAME
        self.mysql.write_bytes(b"mysql copy fixture (not a runnable JAR)")
        self.hive.write_bytes(b"hive copy fixture (not a runnable JAR)")
        for kind in ("reader", "writer"):
            plugin = self.plugin(self.payload, kind)
            plugin.mkdir(parents=True)
            (plugin / "plugin.json").write_text(json.dumps({
                "name": "rdbms" + kind, "description": "preserved",
                "drivers": ["org.postgresql.Driver"]}))
        self.target = self.root / "prepared"

    @staticmethod
    def plugin(root, kind):
        return root / "plugin" / kind / ("rdbms" + kind)

    def command(self, shell=False):
        archive = self.root / "fixture.tar.gz"
        with tarfile.open(archive, "w:gz") as out:
            out.add(self.payload, arcname="datax")
        checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
        if shell:
            return ["bash", str(SCRIPT), "--dest", str(self.target), "--archive", str(archive),
                    "--sha256", checksum, "--mysql-driver-jar", str(self.mysql),
                    "--hive-driver-jar", str(self.hive)]
        # Execute the unchanged embedded Python with the wrapper's argument contract.
        program = SCRIPT.read_text().split("<<'PY'\n", 1)[1].rsplit("\nPY", 1)[0]
        return [sys.executable, "-c", program, str(archive), str(self.target), "https://example.invalid/datax",
                checksum, "fixture-md5", str(self.mysql), hashlib.sha256(self.mysql.read_bytes()).hexdigest(),
                str(self.hive), hashlib.sha256(self.hive.read_bytes()).hexdigest(),
                "https://example.invalid/hive"]

    def prepare(self):
        return subprocess.run(self.command(), capture_output=True, text=True)

    def test_both_plugins_preserve_metadata_and_register_both_local_drivers(self):
        result = self.prepare()
        self.assertEqual(result.returncode, 0, result.stderr)
        for kind in ("reader", "writer"):
            plugin = self.plugin(self.target, kind)
            metadata = json.loads((plugin / "plugin.json").read_text())
            self.assertEqual(metadata["drivers"], ["org.postgresql.Driver", MYSQL_CLASS, HIVE_CLASS])
            self.assertEqual(metadata["description"], "preserved")
            for name, source in ((MYSQL_NAME, self.mysql), (HIVE_NAME, self.hive)):
                self.assertEqual((plugin / "libs" / name).read_bytes(), source.read_bytes())
        manifest = json.loads((self.target / "qdata-tool-manifest.json").read_text())
        self.assertEqual(manifest["hiveJdbcVersion"], "4.2.0")
        self.assertEqual(manifest["hiveJdbcClassifier"], "standalone")
        self.assertEqual(manifest["hiveJdbcSha256"], hashlib.sha256(self.hive.read_bytes()).hexdigest())
        self.assertEqual(manifest["mysqlConnectorSha256"], hashlib.sha256(self.mysql.read_bytes()).hexdigest())

    def test_preexisting_destination_is_preserved(self):
        self.target.mkdir()
        marker = self.target / "preserve.txt"
        marker.write_text("must survive")
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertEqual(marker.read_text(), "must survive")
        self.assertFalse((self.target / "plugin").exists())

    def test_duplicate_registration_is_not_added(self):
        for kind in ("reader", "writer"):
            registration = self.plugin(self.payload, kind) / "plugin.json"
            registration.write_text(json.dumps({"drivers": [MYSQL_CLASS, HIVE_CLASS]}))
        self.assertEqual(self.prepare().returncode, 0)
        for kind in ("reader", "writer"):
            drivers = json.loads((self.plugin(self.target, kind) / "plugin.json").read_text())["drivers"]
            self.assertEqual(drivers, [MYSQL_CLASS, HIVE_CLASS])

    def test_existing_hive_driver_rejects_entire_new_installation(self):
        libraries = self.plugin(self.payload, "writer") / "libs"
        libraries.mkdir()
        (libraries / "hive-jdbc-other.jar").write_text("unreviewed")
        result = self.prepare()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("already contains a Hive", result.stderr)
        self.assertFalse(self.target.exists())

    def test_existing_mysql_driver_rejects_entire_new_installation(self):
        libraries = self.plugin(self.payload, "reader") / "libs"
        libraries.mkdir()
        (libraries / "mysql-connector-old.jar").write_text("unreviewed")
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertFalse(self.target.exists())

    def test_invalid_registration_rejects_entire_new_installation(self):
        registration = self.plugin(self.payload, "writer") / "plugin.json"
        registration.write_text(json.dumps({"drivers": HIVE_CLASS}))
        self.assertNotEqual(self.prepare().returncode, 0)
        self.assertFalse(self.target.exists())

    def test_offline_missing_either_driver_fails_before_download(self):
        for option in ("--mysql-driver-jar", "--hive-driver-jar"):
            with self.subTest(option=option):
                command = ["bash", str(SCRIPT), "--dest", str(self.target), "--archive", "/unused/archive",
                           option, "/unused/driver"]
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
                self.assertIn("Offline preparation requires", result.stderr)
                self.assertFalse(self.target.exists())

    @unittest.skipUnless(os.environ.get("QDATA_TEST_MYSQL_DRIVER_JAR") and os.environ.get("QDATA_TEST_HIVE_DRIVER_JAR"),
                         "Pinned local MySQL and Hive artifacts are required")
    def test_pinned_artifacts_match_checksums_used_by_wrapper(self):
        for engine in ("mysql", "hive"):
            with self.subTest(engine=engine):
                driver = Path(os.environ["QDATA_TEST_" + engine.upper() + "_DRIVER_JAR"])
                expected = re.search(engine + r"_driver_sha256='([0-9a-f]{64})'", SCRIPT.read_text()).group(1)
                self.assertEqual(hashlib.sha256(driver.read_bytes()).hexdigest(), expected)

    @unittest.skipUnless(sys.platform == "linux" and os.environ.get("QDATA_TEST_MYSQL_DRIVER_JAR")
                         and os.environ.get("QDATA_TEST_HIVE_DRIVER_JAR"), "Linux with both pinned artifacts is required")
    def test_linux_wrapper_checks_both_hashes_before_publication(self):
        self.mysql = Path(os.environ["QDATA_TEST_MYSQL_DRIVER_JAR"])
        self.hive = Path(os.environ["QDATA_TEST_HIVE_DRIVER_JAR"])
        command = self.command(shell=True)
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        bad = self.root / "bad.jar"
        bad.write_bytes(b"bad driver")
        for flag in ("--mysql-driver-jar", "--hive-driver-jar"):
            with self.subTest(flag=flag):
                failed = list(command)
                rejected = self.root / (flag + "-rejected")
                failed[failed.index("--dest") + 1] = str(rejected)
                failed[failed.index(flag) + 1] = str(bad)
                result = subprocess.run(failed, capture_output=True, text=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("SHA-256 mismatch", result.stderr)
                self.assertFalse(rejected.exists())


if __name__ == "__main__":
    unittest.main()
