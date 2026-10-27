import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


installer = load("pinned_installer", "deploy/install.py")
frontend = load("pinned_frontend", "deploy/tools/prepare_frontend.py")


class ImagePinsTest(unittest.TestCase):
    def test_online_and_wrong_local_tag_use_pinned_index(self):
        pins = json.loads((ROOT / "deploy/image-digests.json").read_text())
        for tag, entry in pins.items():
            for result in (subprocess.CompletedProcess([], 1, ""),
                           subprocess.CompletedProcess([], 0, "sha256:" + "0" * 64)):
                with self.subTest(tag=tag), mock.patch.object(installer.subprocess, "run", return_value=result):
                    self.assertEqual(tag + "@" + entry["digest"], installer.dependency_image(tag))

    def test_offline_loaded_image_requires_exact_config_digest(self):
        entry = json.loads((ROOT / "deploy/image-digests.json").read_text())["mysql:8.0"]
        for config in entry["configs"].values():
            result = subprocess.CompletedProcess([], 0, config + "\n")
            with mock.patch.object(installer.subprocess, "run", return_value=result):
                self.assertEqual(config, installer.dependency_image("mysql:8.0"))


class FrontendPinsTest(unittest.TestCase):
    def fixture(self, path):
        manifest = {"lockSha256": hashlib.sha256(b"lock").hexdigest(), "scripts": []}
        (path / "package-lock.json").write_bytes(b"lock")
        for name in ("first", "second"):
            package = path / "node_modules" / name
            package.mkdir(parents=True)
            (package / "postinstall.js").write_bytes(b"reviewed entry")
            (package / "utils.js").write_bytes(b"reviewed helper")
            manifest["scripts"].append({"package": name, "entry": "postinstall.js", "files": {
                filename: hashlib.sha256((package / filename).read_bytes()).hexdigest()
                for filename in ("postinstall.js", "utils.js")}})
        return manifest

    def invoke(self, path, manifest, runner):
        original = Path.read_text

        def read_text(file, *args, **kwargs):
            if file.name == "frontend-install-scripts.json":
                return json.dumps(manifest)
            return original(file, *args, **kwargs)

        with mock.patch.object(Path, "read_text", read_text), mock.patch.object(frontend.subprocess, "run", runner):
            frontend.prepare(path, "reviewed-node")

    def test_all_scripts_are_verified_before_any_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            manifest = self.fixture(path)
            (path / "node_modules/second/utils.js").write_bytes(b"tampered")
            runner = mock.Mock()
            with self.assertRaisesRegex(ValueError, "SHA256 mismatch"):
                self.invoke(path, manifest, runner)
            runner.assert_not_called()

    def test_lockfile_change_requires_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            manifest = self.fixture(path)
            (path / "package-lock.json").write_bytes(b"changed lock")
            runner = mock.Mock()
            with self.assertRaisesRegex(ValueError, "package-lock.json changed"):
                self.invoke(path, manifest, runner)
            runner.assert_not_called()

    def test_reviewed_entries_only_and_failures_propagate(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            manifest = self.fixture(path)
            runner = mock.Mock()
            self.invoke(path, manifest, runner)
            self.assertEqual(2, runner.call_count)
            runner.assert_any_call(["reviewed-node", "postinstall.js"], cwd=path / "node_modules/first", check=True)
            runner.side_effect = subprocess.CalledProcessError(1, "reviewed-node")
            with self.assertRaises(subprocess.CalledProcessError):
                self.invoke(path, manifest, runner)


if __name__ == "__main__":
    unittest.main()
