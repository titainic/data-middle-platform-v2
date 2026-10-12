import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


MODULE_SPEC = importlib.util.spec_from_file_location("qdata_install", Path(__file__).parents[1] / "install.py")
installer = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(installer)


class InstallationSafetyTest(unittest.TestCase):
    def test_existing_tables_block_fresh_installation(self):
        with self.assertRaisesRegex(installer.InstallError, "refuses to overwrite"):
            installer.assert_empty_database("217")

    def test_non_numeric_table_count_is_not_treated_as_empty(self):
        with self.assertRaises(installer.InstallError):
            installer.assert_empty_database("")

    def test_empty_database_is_accepted(self):
        installer.assert_empty_database("0\n")

    def test_secret_input_cannot_be_world_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            password = Path(directory) / "password"
            password.write_text("example-password")
            password.chmod(0o644)
            with self.assertRaisesRegex(installer.InstallError, "600"):
                installer.read_private_file(password)

    def test_secret_parser_does_not_execute_shell_code(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = Path(directory) / "settings"
            settings.write_text('MYSQL_ADMIN_PASSWORD="$(touch should-not-exist)"\n')
            settings.chmod(0o600)
            self.assertEqual(installer.read_settings(settings)["MYSQL_ADMIN_PASSWORD"], "$(touch should-not-exist)")

    def test_runtime_settings_reject_multiline_environment_injection(self):
        with self.assertRaises(installer.InstallError):
            installer.env_text({"PASSWORD": "secret\nEXTRA=value"})

    def test_runtime_file_creation_cannot_overwrite_existing_files(self):
        with tempfile.TemporaryDirectory() as directory:
            existing = Path(directory) / "runtime.env"
            existing.write_text("preserve")
            with self.assertRaises(FileExistsError):
                installer.write_protected(existing, "new")
            self.assertEqual(existing.read_text(), "preserve")

    def test_application_sql_credentials_are_not_admin_credentials(self):
        parser = installer.argument_parser()
        arguments = parser.parse_args(["--instance", "unit-test"])
        settings = installer.dependency_settings(arguments)
        self.assertNotEqual(settings["MYSQL_APP_USER"], settings["MYSQL_ADMIN_USER"])
        self.assertNotEqual(settings["MYSQL_APP_PASSWORD"], settings["MYSQL_ADMIN_PASSWORD"])

    def test_mysql_and_redis_override_both_legacy_and_spring_namespaces(self):
        with tempfile.TemporaryDirectory() as directory:
            arguments = installer.argument_parser().parse_args([])
            settings = installer.dependency_settings(arguments)
            root = Path(directory)
            (root / "runtime").mkdir()
            installer.runtime_properties(arguments, settings, root, "quality")
            configuration = (root / "runtime/application-quality.properties").read_text()
            self.assertIn("datasource.type=mysql\n", configuration)
            self.assertIn("mysql.password=${QDATA_MYSQL_PASSWORD}\n", configuration)
            self.assertIn("spring.datasource.dynamic.datasource.master.password=${QDATA_MYSQL_PASSWORD}\n", configuration)
            self.assertIn("spring.data.redis.password=${QDATA_REDIS_PASSWORD}\n", configuration)
            self.assertIn("ds.redis.password=${QDATA_REDIS_PASSWORD}\n", configuration)
            self.assertIn("qdata.embedded-redis.enabled=false\n", configuration)
            self.assertNotIn(settings["MYSQL_ADMIN_PASSWORD"], configuration)

    def test_identifier_rejects_sql_injection(self):
        with self.assertRaises(installer.InstallError):
            installer.assert_identifier("qdata`;DROP DATABASE other;--", "Database")

    def test_frontend_remember_password_keys_cannot_reuse_backend_private_key(self):
        with tempfile.TemporaryDirectory() as directory:
            arguments = installer.argument_parser().parse_args([])
            with mock.patch.object(installer, "rsa_pair", return_value=("private", "public")):
                with self.assertRaisesRegex(installer.InstallError, "separate from backend"):
                    installer.generate_keys(arguments, Path(directory))

    def test_generated_frontend_uses_only_independent_browser_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            arguments = installer.argument_parser().parse_args([])
            root = Path(directory)
            _, server_public, frontend = installer.generate_keys(arguments, root)
            self.assertNotEqual(frontend["VITE_APP_RSA_PUBLIC_KEY"].replace("\\n", "\n"), server_public)
            self.assertNotEqual(frontend["VITE_APP_RSA_PRIVATE_KEY"].replace("\\n", "\n"),
                                (root / "rsa-private.pem").read_text())
            self.assertEqual((root / "frontend.env").stat().st_mode & 0o777, 0o600)

    def artifact_fixture(self, root):
        arguments = installer.argument_parser().parse_args(["--skip-build", "--artifacts-dir", str(root)])
        frontend = {"VITE_APP_AES_KEY": "a" * 16, "VITE_APP_RSA_PUBLIC_KEY": "browser-public\\n"}
        filenames = ("qdata-server.jar", "qdata-service-quality.jar", "qdata-executor-etl.jar",
                     "PasswordHash.class", "tools/security-jars/crypto.jar")
        fingerprints = {}
        for filename in filenames:
            path = root / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(filename.encode())
            fingerprints[filename] = installer.file_sha256(path)
        manifest = {"artifacts": fingerprints, "frontendRsaPurpose": "browser-rememberme",
                    "frontendAesSha256": hashlib.sha256(frontend["VITE_APP_AES_KEY"].encode()).hexdigest(),
                    "frontendRsaPublicSha256": hashlib.sha256(frontend["VITE_APP_RSA_PUBLIC_KEY"].replace("\\n", "\n").encode()).hexdigest()}
        (root / "build-manifest.json").write_text(json.dumps(manifest))
        return arguments, frontend

    def test_changed_jar_is_rejected_before_any_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            arguments, frontend = self.artifact_fixture(root)
            (root / "qdata-server.jar").write_bytes(b"changed binary")
            with self.assertRaisesRegex(installer.InstallError, "SHA-256 differs"):
                installer.build_artifacts(arguments, root, None, frontend)

    def test_unknown_password_crypto_jar_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            arguments, frontend = self.artifact_fixture(root)
            (root / "tools/security-jars/unlisted.jar").write_bytes(b"unlisted dependency")
            with self.assertRaisesRegex(installer.InstallError, "fingerprint is missing"):
                installer.build_artifacts(arguments, root, None, frontend)

    def test_private_external_toolchain_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / "java"
            executable.write_text("fixture")
            executable.chmod(0o755)
            with self.assertRaisesRegex(installer.InstallError, "private directory"):
                installer.assert_toolchain_access(executable)


if __name__ == "__main__":
    unittest.main()
