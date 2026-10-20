import importlib.util
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock


MODULE_SPEC = importlib.util.spec_from_file_location("qdata_install", Path(__file__).parents[1] / "install.py")
installer = importlib.util.module_from_spec(MODULE_SPEC)
MODULE_SPEC.loader.exec_module(installer)


class GatewayTemporaryPathsTest(unittest.TestCase):
    def test_native_gateway_prepares_private_worker_owned_paths_before_starting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "qdata"
            (root / "runtime").mkdir(parents=True, mode=0o750)
            arguments = installer.argument_parser().parse_args(["--instance", "qdata"])
            with mock.patch.object(installer.shutil, "which", side_effect=lambda name: "/usr/sbin/nginx" if name == "nginx" else None), \
                 mock.patch.object(installer.pwd, "getpwnam", return_value=mock.Mock(pw_gid=1234)), \
                 mock.patch.object(installer.grp, "getgrgid", return_value=mock.Mock(gr_name="worker-group")) as group, \
                 mock.patch.object(installer, "assert_toolchain_access") as access, \
                 mock.patch.object(installer, "write_protected") as write, \
                 mock.patch.object(installer, "run") as run:
                old_umask = os.umask(0o077)
                try:
                    installer.install_gateway(arguments, root)
                finally:
                    os.umask(old_umask)
            group.assert_called_once_with(1234)
            access.assert_called_once_with(root.parent)
            configuration = write.call_args_list[0].args[1]
            self.assertIn("user nobody worker-group;", configuration)
            temporary_root = root.parent / "qdata-nginx-tmp"
            self.assertEqual(temporary_root.stat().st_mode & 0o777, 0o755)
            paths = [temporary_root / name for name in ("proxy", "client_body", "fastcgi", "uwsgi", "scgi")]
            for path in paths:
                self.assertIn(f"{path.name}_temp_path {path};", configuration)
                self.assertEqual(path.stat().st_mode & 0o777, 0o700)
                self.assertFalse(path.is_relative_to(root))
            self.assertEqual(run.call_args_list[0], mock.call(["chown", "nobody:worker-group", *map(str, paths)]))
            self.assertEqual(run.call_args_list[-1], mock.call(["systemctl", "enable", "--now", "qdata-gateway.service"]))

    def test_existing_native_temporary_directory_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "qdata"
            temporary_root = root.parent / "qdata-nginx-tmp"
            temporary_root.mkdir(mode=0o750)
            marker = temporary_root / "keep"
            marker.write_text("existing data")
            arguments = installer.argument_parser().parse_args(["--instance", "qdata"])
            with mock.patch.object(installer.shutil, "which", side_effect=lambda name: "/usr/sbin/nginx" if name == "nginx" else None), \
                 mock.patch.object(installer, "assert_toolchain_access"), \
                 mock.patch.object(installer, "write_protected") as write, \
                 mock.patch.object(installer, "run") as run:
                with self.assertRaisesRegex(installer.InstallError, "temporary directory already exists"):
                    installer.install_gateway(arguments, root)
            self.assertEqual(marker.read_text(), "existing data")
            self.assertEqual(temporary_root.stat().st_mode & 0o777, 0o750)
            run.assert_not_called()
            write.assert_not_called()

    def test_native_gateway_rejects_private_parent_before_creating_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "qdata"
            arguments = installer.argument_parser().parse_args(["--instance", "qdata"])
            with mock.patch.object(installer.shutil, "which", side_effect=lambda name: "/usr/sbin/nginx" if name == "nginx" else None), \
                 mock.patch.object(installer, "assert_toolchain_access", side_effect=installer.InstallError("private directory")), \
                 mock.patch.object(installer, "write_protected") as write, \
                 mock.patch.object(installer, "run") as run:
                with self.assertRaisesRegex(installer.InstallError, "private directory"):
                    installer.install_gateway(arguments, root)
            self.assertFalse((root.parent / "qdata-nginx-tmp").exists())
            run.assert_not_called()
            write.assert_not_called()

    def test_docker_gateway_initializes_paths_inside_container_with_matching_worker(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "qdata"
            (root / "runtime").mkdir(parents=True)
            arguments = installer.argument_parser().parse_args(["--instance", "unit-test"])
            with mock.patch.object(installer.shutil, "which", return_value="/usr/bin/docker"), \
                 mock.patch.object(installer.subprocess, "run", return_value=mock.Mock(returncode=1)), \
                 mock.patch.object(installer.pwd, "getpwnam") as account, \
                 mock.patch.object(installer, "run") as run:
                self.assertEqual(installer.install_gateway(arguments, root), "docker:unit-test-gateway")
            account.assert_not_called()
            configuration = (root / "runtime/nginx.conf").read_text()
            self.assertIn("user nginx nginx;", configuration)
            temporary_root = root.parent / "unit-test-nginx-tmp"
            self.assertFalse(temporary_root.exists())
            command = run.call_args.args[0]
            self.assertEqual(command[-3:-1], ["sh", "-c"])
            script = command[-1]
            for name in ("proxy", "client_body", "fastcgi", "uwsgi", "scgi"):
                path = temporary_root / name
                self.assertIn(f"{name}_temp_path {path};", configuration)
                self.assertIn(str(path), script)
            self.assertIn("chown nginx:nginx", script)
            self.assertIn("chmod 700", script)
            self.assertTrue(script.endswith("&& exec nginx -g 'daemon off;'"))
            installer.subprocess.run(["sh", "-n", "-c", script], check=True)


class InstallationSafetyTest(unittest.TestCase):
    def test_gateway_forwards_websocket_handshakes_on_both_backend_routes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "runtime").mkdir()
            arguments = installer.argument_parser().parse_args([])
            with mock.patch.object(installer.shutil, "which", return_value="/usr/bin/docker"), \
                 mock.patch.object(installer.subprocess, "run", return_value=mock.Mock(returncode=1)), \
                 mock.patch.object(installer, "run"):
                installer.install_gateway(arguments, root)
            configuration = (root / "runtime/nginx.conf").read_text()
            self.assertIn("map $http_upgrade $connection_upgrade", configuration)
            self.assertEqual(configuration.count("proxy_set_header Upgrade $http_upgrade;"), 2)
            self.assertEqual(configuration.count("proxy_set_header Connection $connection_upgrade;"), 2)

    def test_offline_datax_requires_driver_before_attempting_download(self):
        arguments = installer.argument_parser().parse_args(["--offline", "--datax-archive", "/unused/datax.tar.gz"])
        with mock.patch.object(installer.subprocess, "run") as run:
            with self.assertRaisesRegex(installer.InstallError, "datax-mysql-driver"):
                installer.prepare_datax(arguments, Path("/unused"))
            run.assert_not_called()

    def test_datax_preparation_receives_both_offline_drivers(self):
        arguments = installer.argument_parser().parse_args(["--offline", "--datax-archive", "/unused/datax.tar.gz",
                                                           "--datax-mysql-driver", "/unused/mysql.jar",
                                                           "--datax-hive-driver", "/unused/hive.jar"])
        with mock.patch.object(installer.subprocess, "run", return_value=mock.Mock(returncode=0)) as run:
            installer.prepare_datax(arguments, Path("/unused"))
            self.assertIn("--mysql-driver-jar", run.call_args.args[0])
            self.assertIn("/unused/mysql.jar", run.call_args.args[0])
            self.assertIn("--hive-driver-jar", run.call_args.args[0])
            self.assertIn("/unused/hive.jar", run.call_args.args[0])

    def test_offline_datax_requires_hive_driver_without_network(self):
        arguments = installer.argument_parser().parse_args([
            "--offline", "--datax-archive", "/unused/datax.tar.gz",
            "--datax-mysql-driver", "/unused/mysql.jar"])
        with mock.patch.object(installer.subprocess, "run") as run:
            with self.assertRaisesRegex(installer.InstallError, "datax-hive-driver"):
                installer.prepare_datax(arguments, Path("/unused"))
            run.assert_not_called()

    def test_existing_eight_character_login_password_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            password = Path(directory) / "password"
            password.write_text("unitPass")
            password.chmod(0o600)
            self.assertEqual(installer.administrator_password(password), "unitPass")

    def test_initial_password_uses_the_application_length_boundaries(self):
        with tempfile.TemporaryDirectory() as directory:
            password = Path(directory) / "password"
            password.touch(mode=0o600)
            for length in (5, 20):
                password.write_text("a" * length)
                self.assertEqual(len(installer.administrator_password(password)), length)
            for length in (4, 21):
                password.write_text("a" * length)
                with self.assertRaisesRegex(installer.InstallError, "5-20"):
                    installer.administrator_password(password)

    def test_clean_initialization_policy_accepts_only_system_seed_rows(self):
        installer.validate_seed_counts({"system_user": "1", "da_datasource": "0"},
                                       {"system_user", "da_datasource"}, {"system_user": 1})

    def test_business_fixture_blocks_clean_initialization(self):
        with self.assertRaisesRegex(installer.InstallError, "da_datasource has 1 rows"):
            installer.validate_seed_counts({"system_user": "1", "da_datasource": "1"},
                                           {"system_user", "da_datasource"}, {"system_user": 1})

    def test_extra_account_blocks_clean_initialization(self):
        with self.assertRaisesRegex(installer.InstallError, "system_user has 2 rows"):
            installer.validate_seed_counts({"system_user": "2"}, {"system_user"}, {"system_user": 1})

    def test_incomplete_table_audit_is_rejected(self):
        with self.assertRaisesRegex(installer.InstallError, "every initial database table"):
            installer.validate_seed_counts({"system_user": "1"},
                                           {"system_user", "da_datasource"}, {"system_user": 1})

    def test_changed_initialization_sql_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {name: "" for name in ("schema.sql", "reference.sql", "bootstrap.sql", "five-layers.sql")}
            files["schema.sql"] = "CREATE TABLE `system_user` (id INT);\n"
            for name, content in files.items():
                (root / name).write_text(content)
            manifest = {"tables": 1, "fresh_seed_rows": {"system_user": 1},
                        "files": {name: installer.file_sha256(root / name) for name in files}}
            (root / "manifest.json").write_text(json.dumps(manifest))
            (root / "reference.sql").write_text("unexpected fixture")
            with self.assertRaisesRegex(installer.InstallError, "SQL SHA-256 differs"):
                installer.checked_database_manifest(root)

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
