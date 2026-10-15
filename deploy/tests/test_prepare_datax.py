"""Offline preparation only: uses a tiny archive fixture and never executes DataX jobs."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest


@unittest.skipUnless(sys.platform == "linux" and os.environ.get("QDATA_TEST_MYSQL_DRIVER_JAR"),
                     "Linux and the pinned local Connector/J JAR are required")
class DataxPreparationTest(unittest.TestCase):
    def test_both_plugins_are_registered_and_existing_destinations_are_preserved(self):
        script = Path(__file__).parents[1] / "prepare-datax.sh"
        driver = Path(os.environ["QDATA_TEST_MYSQL_DRIVER_JAR"])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "datax"
            for relative in ["bin", "lib", "plugin", "conf"]:
                (payload / relative).mkdir(parents=True)
            (payload / "bin/datax.py").write_text("# fixture; no job execution\n")
            for kind in ["reader", "writer"]:
                plugin = payload / "plugin" / kind / ("rdbms" + kind)
                plugin.mkdir(parents=True)
                (plugin / "plugin.json").write_text(json.dumps({"name": "rdbms" + kind, "drivers": ["org.postgresql.Driver"]}))
            archive = root / "fixture.tar.gz"
            with tarfile.open(archive, "w:gz") as out:
                out.add(payload, arcname="datax")
            checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
            target = root / "prepared"
            command = ["bash", str(script), "--dest", str(target), "--archive", str(archive),
                       "--sha256", checksum, "--mysql-driver-jar", str(driver)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            for kind in ["reader", "writer"]:
                plugin = target / "plugin" / kind / ("rdbms" + kind)
                drivers = json.loads((plugin / "plugin.json").read_text())["drivers"]
                self.assertEqual(drivers, ["org.postgresql.Driver", "com.mysql.cj.jdbc.Driver"])
                self.assertEqual((plugin / "libs/mysql-connector-j-9.7.0.jar").read_bytes(), driver.read_bytes())
            manifest = json.loads((target / "qdata-tool-manifest.json").read_text())
            self.assertEqual(manifest["mysqlConnectorSha256"], hashlib.sha256(driver.read_bytes()).hexdigest())
            marker = target / "preserve.txt"
            marker.write_text("must survive")
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(marker.read_text(), "must survive")
            invalid = root / "invalid.jar"
            invalid.write_text("bad driver")
            command[command.index(str(target))] = str(root / "rejected")
            command[-1] = str(invalid)
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertFalse((root / "rejected").exists())


if __name__ == "__main__":
    unittest.main()
