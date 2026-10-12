#!/usr/bin/env python3
"""Install a new, isolated qData instance on Linux using Java 21 and systemd."""

import argparse
import getpass
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from xml.sax.saxutils import escape


REPOSITORY = Path(__file__).resolve().parent.parent
IDENTIFIER = re.compile(r"^[a-z][a-z0-9_]{0,31}$")
INSTANCE = re.compile(r"^[a-z][a-z0-9-]{0,23}$")


class InstallError(RuntimeError):
    pass


def run(command, *, input_text=None, env=None, cwd=None, private=False):
    result = subprocess.run(command, input=input_text, text=True, capture_output=True,
                            env=env, cwd=cwd)
    if result.returncode:
        detail = "" if private else result.stderr[-3000:]
        raise InstallError(f"{Path(str(command[0])).name} failed (exit {result.returncode}). {detail}")
    return result.stdout.strip()


def write_protected(path, content, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        stream.write(content)


def read_private_file(path):
    if path.is_symlink() or not path.is_file():
        raise InstallError("Credential input must be a regular file, not a symlink")
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise InstallError("Credential input must have permissions 600 or stricter")
    if path.stat().st_uid != os.geteuid():
        raise InstallError("Credential input must be owned by the installing user")
    return path.read_text(encoding="utf-8")


def read_settings(path):
    settings = {}
    for line in read_private_file(path).splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise InstallError("Credential settings require KEY=VALUE lines")
        if value.startswith(('"', "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise InstallError("Unterminated quoted credential setting")
            value = value[1:-1]
        if key in settings or "\x00" in value:
            raise InstallError("Duplicate or invalid credential setting")
        settings[key] = value
    return settings


def administrator_password(password_file):
    if password_file:
        password = read_private_file(password_file).rstrip("\r\n")
    else:
        if not sys.stdin.isatty():
            raise InstallError("Use --admin-password-file for unattended installation")
        password = getpass.getpass("Admin login password (5-20 characters; existing password may be reused): ")
        if getpass.getpass("Confirm admin password: ") != password:
            raise InstallError("Administrator password confirmation differs")
    if not 5 <= len(password) <= 20 or any(character in password for character in "\r\n\x00"):
        raise InstallError("Administrator password must contain 5-20 characters, matching qData login")
    return password


def env_text(settings):
    lines = []
    for key, value in sorted(settings.items()):
        if any(character in value for character in "\r\n\x00"):
            raise InstallError("Runtime environment values must be single line")
        escaped = value.replace("\\", "\\\\").replace('"', '\\"')
        lines.append(f'{key}="{escaped}"')
    return "\n".join(lines) + "\n"


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checked_database_manifest(directory):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    filenames = {"schema.sql", "reference.sql", "bootstrap.sql", "five-layers.sql"}
    if set(manifest.get("files", {})) != filenames:
        raise InstallError("Initialization manifest must identify the four reviewed SQL files")
    for name in filenames:
        if file_sha256(directory / name) != manifest["files"][name]:
            raise InstallError("Initialization SQL SHA-256 differs from manifest: " + name)
    tables = set(re.findall(r"^CREATE TABLE `([a-z][a-z0-9_]*)`", (directory / "schema.sql").read_text(), re.MULTILINE))
    seeds = manifest.get("fresh_seed_rows", {})
    if len(tables) != manifest.get("tables") or not seeds or not set(seeds).issubset(tables):
        raise InstallError("Initialization manifest table or seed policy is invalid")
    if any(type(count) is not int or count < 1 for count in seeds.values()):
        raise InstallError("Initialization seed counts must be positive integers")
    return manifest, tables


def validate_seed_counts(rows, schema_tables, seed_rows):
    if set(rows) != schema_tables:
        raise InstallError("Unable to verify every initial database table")
    for name in sorted(schema_tables):
        expected = seed_rows.get(name, 0)
        try:
            actual = int(rows[name])
        except ValueError as error:
            raise InstallError("Invalid initial row count for table: " + name) from error
        if actual != expected:
            raise InstallError(f"Initial data differs from clean policy: {name} has {actual} rows; expected {expected}")


def sql_literal(value):
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def assert_identifier(value, label):
    if not IDENTIFIER.fullmatch(value):
        raise InstallError(f"{label} must start with a lowercase letter and contain a-z, 0-9 or _")


def assert_empty_database(table_count):
    try:
        count = int(table_count.strip())
    except ValueError as exception:
        raise InstallError("Unable to verify that the target database is empty") from exception
    if count != 0:
        raise InstallError("Target database contains tables; installation refuses to overwrite it")


def available_port(port):
    if not 1024 <= port <= 65535:
        raise InstallError("Ports must be between 1024 and 65535")
    try:
        with socket.socket() as connection:
            connection.bind(("0.0.0.0", port))
    except OSError as exception:
        raise InstallError(f"Port {port} is already in use") from exception


def await_tcp(host, port, label):
    for attempt in range(90):
        try:
            with socket.create_connection((host, int(port)), timeout=2):
                return
        except OSError:
            time.sleep(2)
    raise InstallError(f"{label} did not become reachable within 180 seconds")


def await_json_http(url, label, expected_status, field, value):
    for attempt in range(90):
        try:
            request = urllib.request.Request(url, headers={"Accept": "application/json"})
            try:
                response = urllib.request.urlopen(request, timeout=3)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                payload = json.load(response)
                if response.status == expected_status and payload.get(field) == value:
                    return
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(2)
    raise InstallError(label + " did not pass its actual HTTP application startup check")


def java21(java_home):
    executable = str(java_home / "bin/java") if java_home else shutil.which("java")
    if not executable:
        raise InstallError("Install JDK 21 and set JAVA_HOME or --java-home")
    result = subprocess.run([executable, "-version"], capture_output=True, text=True)
    version = result.stdout + result.stderr
    if result.returncode or not re.search(r'version "21(?:\.|\")', version):
        raise InstallError("qData installation requires Java 21")
    executable = Path(executable).resolve()
    if not (executable.parent / "javac").is_file():
        raise InstallError("A full JDK 21 (including javac), not only a JRE, is required")
    return executable


def assert_toolchain_access(path):
    path = path.resolve()
    ancestors = ((path,) if path.is_dir() else ()) + tuple(path.parents)
    for ancestor in ancestors:
        if not ancestor.stat().st_mode & stat.S_IXOTH:
            raise InstallError("Runtime toolchain is behind a private directory; copy it to an application-accessible path")
    if path.is_file() and not path.stat().st_mode & stat.S_IROTH:
        raise InstallError("Runtime toolchain files must be readable by the instance service account")


def font_configuration(font_runtime, cache):
    return ('<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig><dir>'
            + escape(str(font_runtime / "usr/share/fonts")) + '</dir><cachedir>'
            + escape(str(cache)) + '</cachedir></fontconfig>\n')


def font_environment(font_runtime, configuration):
    libraries = font_runtime / "usr/lib/x86_64-linux-gnu"
    return {"FONTCONFIG_FILE": str(configuration),
            "FONTCONFIG_PATH": str(font_runtime / "etc/fonts"),
            "LD_LIBRARY_PATH": str(libraries)}


def check_font_environment(font_runtime=None):
    if font_runtime:
        font_runtime = font_runtime.resolve()
        required = ("usr/bin/fc-list", "usr/share/fonts", "usr/lib/x86_64-linux-gnu/libfontconfig.so.1")
        for relative in required:
            if not (font_runtime / relative).exists():
                raise InstallError("The supplied offline font runtime is incomplete")
        with tempfile.TemporaryDirectory(prefix="qdata-font-preflight-") as directory:
            temporary = Path(directory)
            configuration = temporary / "fonts.conf"
            configuration.write_text(font_configuration(font_runtime, temporary / "cache"), encoding="utf-8")
            environment = os.environ.copy()
            environment.update(font_environment(font_runtime, configuration))
            if not run([str(font_runtime / "usr/bin/fc-list")], env=environment).strip():
                raise InstallError("The offline font runtime contains no usable fonts")
        return
    executable = shutil.which("fc-list")
    if not executable or not run([executable]).strip():
        raise InstallError("Install fontconfig and DejaVu fonts before installation (Ubuntu: apt-get install fontconfig fonts-dejavu-core)")


def dependency_settings(arguments):
    prefix = arguments.instance.replace("-", "_")
    settings = {
        "MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": str(arguments.mysql_port),
        "MYSQL_ADMIN_USER": "root", "MYSQL_ADMIN_PASSWORD": secrets.token_urlsafe(32),
        "MYSQL_DATABASE": prefix, "MYSQL_APP_USER": f"{prefix}_app",
        "MYSQL_APP_PASSWORD": secrets.token_urlsafe(32),
        "REDIS_HOST": "127.0.0.1", "REDIS_PORT": str(arguments.redis_port),
        "REDIS_PASSWORD": secrets.token_urlsafe(32), "REDIS_DATABASE": "0",
        "RABBITMQ_HOST": "127.0.0.1", "RABBITMQ_PORT": str(arguments.rabbitmq_port),
        "RABBITMQ_USERNAME": prefix, "RABBITMQ_PASSWORD": secrets.token_urlsafe(32),
        "NEO4J_URI": f"bolt://127.0.0.1:{arguments.neo4j_port}",
        "NEO4J_USERNAME": "neo4j", "NEO4J_PASSWORD": secrets.token_urlsafe(32),
    }
    if arguments.existing_dependencies:
        if not arguments.dependencies_file:
            raise InstallError("--existing-dependencies requires --dependencies-file")
        provided = read_settings(arguments.dependencies_file)
        required = ("MYSQL_HOST", "MYSQL_PORT", "MYSQL_ADMIN_USER", "MYSQL_ADMIN_PASSWORD",
                    "REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD", "RABBITMQ_HOST",
                    "RABBITMQ_PORT", "RABBITMQ_USERNAME", "RABBITMQ_PASSWORD", "NEO4J_URI",
                    "NEO4J_USERNAME", "NEO4J_PASSWORD")
        missing = [key for key in required if key not in provided]
        if missing:
            raise InstallError("Missing dependency settings: " + ", ".join(missing))
        settings.update(provided)
    assert_identifier(settings["MYSQL_DATABASE"], "Database name")
    assert_identifier(settings["MYSQL_APP_USER"], "Application database user")
    if settings["MYSQL_APP_USER"] == settings["MYSQL_ADMIN_USER"]:
        raise InstallError("Use a separate application database account")
    for key in ("MYSQL_PORT", "REDIS_PORT", "RABBITMQ_PORT"):
        if not settings[key].isdigit() or not 1 <= int(settings[key]) <= 65535:
            raise InstallError(f"Invalid {key}")
    if not settings["REDIS_DATABASE"].isdigit():
        raise InstallError("REDIS_DATABASE must be a number")
    return settings


def rsa_pair(runtime, stem, source, prefix):
    private = runtime / (stem + "-private.pem")
    public = runtime / (stem + "-public.pem")
    if prefix + "_PRIVATE_KEY_FILE" in source:
        private_text = read_private_file(Path(source[prefix + "_PRIVATE_KEY_FILE"]))
        write_protected(private, private_text)
    else:
        run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
             "-out", str(private)], private=True)
        private.chmod(0o600)
    public_text = run(["openssl", "pkey", "-in", str(private), "-pubout"], private=True) + "\n"
    if prefix + "_PUBLIC_KEY_FILE" in source:
        expected = Path(source[prefix + "_PUBLIC_KEY_FILE"]).read_text(encoding="utf-8")
        if expected.strip() != public_text.strip():
            raise InstallError("RSA public and private keys do not match")
    write_protected(public, public_text, 0o640)
    return private.read_text(encoding="utf-8"), public_text


def generate_keys(arguments, runtime):
    source = read_settings(arguments.key_file) if arguments.key_file else {}
    aes = source.get("QDATA_AES_KEY", secrets.token_hex(8))
    if len(aes.encode("utf-8")) != 16 or not re.fullmatch(r"[a-zA-Z0-9]{16}", aes):
        raise InstallError("QDATA_AES_KEY must contain 16 ASCII letters or digits")
    _, public_text = rsa_pair(runtime, "rsa", source, "QDATA_RSA")
    browser_private, browser_public = rsa_pair(runtime, "browser-rsa", source, "QDATA_BROWSER_RSA")
    if browser_public.strip() == public_text.strip():
        raise InstallError("Browser remember-password keys must be separate from backend RSA keys")
    frontend = {"VITE_APP_AES_KEY": aes,
                "VITE_APP_RSA_PUBLIC_KEY": browser_public.replace("\n", "\\n"),
                "VITE_APP_RSA_PRIVATE_KEY": browser_private.replace("\n", "\\n")}
    write_protected(runtime / "frontend.env", "\n".join(f"{key}={value}" for key, value in frontend.items()) + "\n")
    return aes, public_text, frontend


def prepare_datax(arguments, root):
    if arguments.datax_home:
        directory = arguments.datax_home.resolve()
        for relative in ("bin/datax.py", "lib", "plugin", "conf"):
            if not (directory / relative).exists():
                raise InstallError("The supplied DataX home is incomplete")
        arguments.datax_home = directory
        return
    if arguments.skip_datax:
        return
    directory = root / "tools/datax"
    command = ["bash", str(REPOSITORY / "deploy/prepare-datax.sh"), "--dest", str(directory)]
    if arguments.datax_archive:
        command.extend(["--archive", str(arguments.datax_archive.resolve())])
    if arguments.datax_sha256:
        command.extend(["--sha256", arguments.datax_sha256])
    result = subprocess.run(command)
    if result.returncode:
        raise InstallError("DataX preparation failed; provide --datax-home or an offline --datax-archive")
    arguments.datax_home = directory


def build_artifacts(arguments, runtime, java, frontend):
    artifacts = arguments.artifacts_dir.resolve()
    if not arguments.skip_build:
        build_environment = os.environ.copy()
        build_environment.update(frontend)
        build_environment["JAVA_HOME"] = str(java.parent.parent)
        build_environment["PATH"] = str(java.parent) + ":" + build_environment.get("PATH", "")
        command = ["bash", str(REPOSITORY / "deploy/build.sh"), "--env-file", str(runtime / "frontend.env")]
        if arguments.offline:
            command.append("--offline")
        if arguments.dependency_dir:
            command.extend(["--dependency-dir", str(arguments.dependency_dir)])
        result = subprocess.run(command, env=build_environment, cwd=REPOSITORY)
        if result.returncode:
            raise InstallError("Source build failed; installation data is preserved for inspection")
    required = ["qdata-server.jar", "qdata-service-quality.jar", "qdata-executor-etl.jar", "PasswordHash.class"]
    if arguments.with_ai:
        required.append("qdata-ai-server.jar")
    for filename in required:
        if not (artifacts / filename).is_file():
            raise InstallError(f"Missing build artifact: {filename}")
    manifest_path = artifacts / "build-manifest.json"
    if not manifest_path.is_file():
        raise InstallError("Build manifest is missing; rebuild with deploy/build.sh")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("frontendRsaPurpose") != "browser-rememberme":
        raise InstallError("Build manifest does not identify separate browser remember-password RSA keys")
    fingerprints = manifest.get("artifacts")
    if not isinstance(fingerprints, dict):
        raise InstallError("Build manifest is missing artifact SHA-256 fingerprints")
    security_libraries = list((artifacts / "tools/security-jars").glob("*.jar"))
    if not security_libraries:
        raise InstallError("Password hashing helper crypto dependencies are missing")
    for filename in required + [str(path.relative_to(artifacts)) for path in security_libraries]:
        expected = fingerprints.get(filename)
        if not isinstance(expected, str) or not re.fullmatch(r"[a-f0-9]{64}", expected):
            raise InstallError("Build manifest fingerprint is missing: " + filename)
        if file_sha256(artifacts / filename) != expected:
            raise InstallError("Artifact SHA-256 differs from the build manifest: " + filename)
    aes_hash = hashlib.sha256(frontend["VITE_APP_AES_KEY"].encode()).hexdigest()
    public_hash = hashlib.sha256(frontend["VITE_APP_RSA_PUBLIC_KEY"].replace("\\n", "\n").encode()).hexdigest()
    if manifest.get("frontendAesSha256") != aes_hash or manifest.get("frontendRsaPublicSha256") != public_hash:
        raise InstallError("Build frontend keys differ from this instance; rebuild or supply matching --key-file")
    frontend_api_base = manifest.get("frontendApiBase", "/").rstrip("/") + "/"
    if frontend_api_base not in ("/", "/prod-api/"):
        raise InstallError("Configure a matching gateway for this custom frontend API base before installation")
    arguments.frontend_api_base = frontend_api_base
    return artifacts


class Mysql:
    def __init__(self, settings, runtime, container=None):
        self.settings = settings
        self.container = container
        self.options = runtime / "mysql-admin.cnf"
        escaped_password = settings["MYSQL_ADMIN_PASSWORD"].replace("\\", "\\\\").replace('"', '\\"')
        content = ("[client]\nprotocol=tcp\n" + f"host={settings['MYSQL_HOST']}\n"
                   + f"port={settings['MYSQL_PORT']}\nuser={settings['MYSQL_ADMIN_USER']}\n"
                   + f'password="{escaped_password}"\n')
        if container:
            content = content.replace(f"port={settings['MYSQL_PORT']}", "port=3306").replace(
                f"host={settings['MYSQL_HOST']}", "host=127.0.0.1")
        write_protected(self.options, content)

    def query(self, statement, database=None):
        if self.container:
            command = ["docker", "exec", "-i", self.container, "mysql",
                       "--defaults-extra-file=/run/secrets/mysql-admin.cnf"]
        else:
            executable = shutil.which("mysql") or shutil.which("mariadb")
            if not executable:
                raise InstallError("An installed MySQL command-line client is required for existing dependencies")
            command = [executable, "--defaults-extra-file=" + str(self.options)]
        command.extend(["--batch", "--skip-column-names", "--default-character-set=utf8mb4"])
        if database:
            command.append(database)
        return run(command, input_text=statement, private=True)

    def await_ready(self):
        for attempt in range(90):
            try:
                if self.query("SELECT 1;") == "1":
                    return
            except InstallError:
                pass
            time.sleep(2)
        raise InstallError("MySQL did not become ready within 180 seconds")

    def initialize(self, administrator_hash):
        database_manifest, schema_tables = checked_database_manifest(REPOSITORY / "deploy/database")
        database = self.settings["MYSQL_DATABASE"]
        application_user = self.settings["MYSQL_APP_USER"]
        if self.query("SELECT @@lower_case_table_names;") != "1":
            raise InstallError("MySQL must be initialized with lower_case_table_names=1 for this schema")
        count = self.query("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema="
                           + sql_literal(database) + ";")
        assert_empty_database(count)
        account_count = self.query("SELECT COUNT(*) FROM mysql.user WHERE user=" + sql_literal(application_user) + ";")
        if account_count != "0":
            raise InstallError("Application MySQL user already exists; use a new dedicated account")
        self.query(f"CREATE DATABASE IF NOT EXISTS `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;\n")
        for filename in ("schema.sql", "reference.sql", "bootstrap.sql", "five-layers.sql"):
            script = (REPOSITORY / "deploy/database" / filename).read_text(encoding="utf-8")
            if filename == "bootstrap.sql":
                if script.count("__QDATA_ADMIN_PASSWORD_HASH__") != 1:
                    raise InstallError("Bootstrap SQL must contain exactly one administrator hash placeholder")
                script = script.replace("__QDATA_ADMIN_PASSWORD_HASH__", administrator_hash)
            self.query(script, database)
        self.verify_clean_database(database, database_manifest, schema_tables)
        password = sql_literal(self.settings["MYSQL_APP_PASSWORD"])
        account = sql_literal(application_user) + "@'%'"
        self.query(f"CREATE USER {account} IDENTIFIED BY {password};\nGRANT ALL PRIVILEGES ON `{database}`.* TO {account};\n")

    def verify_clean_database(self, database, manifest, schema_tables):
        actual = set(self.query("SELECT table_name FROM information_schema.tables WHERE table_schema="
                                + sql_literal(database) + " AND table_type='BASE TABLE';").splitlines())
        if actual != schema_tables:
            raise InstallError("Initial database table set differs from the reviewed schema")
        statement = " UNION ALL ".join("SELECT " + sql_literal(name) + ", COUNT(*) FROM `" + name + "`"
                                       for name in sorted(schema_tables)) + ";"
        rows = dict(line.split("\t") for line in self.query(statement, database).splitlines())
        validate_seed_counts(rows, schema_tables, manifest["fresh_seed_rows"])
        administrator = self.query("SELECT COUNT(*) FROM system_user WHERE user_id=1 AND user_name='admin' "
                                   "AND status='0' AND del_flag='0' AND CHAR_LENGTH(password)=60 "
                                   "AND password REGEXP '^[$]2[aby][$][0-9]{2}[$]';", database)
        layers = set(self.query("SELECT eng_name FROM dm_data_layer;", database).splitlines())
        project = self.query("SELECT COUNT(*) FROM att_project WHERE id=1 AND code='default' AND manager_id=1;", database)
        if administrator != "1" or project != "1" or layers != {"ODS", "DWD", "DWS", "DWT", "ADS"}:
            raise InstallError("Initial administrator, empty default project or five-layer definitions are invalid")
        print(f"Clean initial database verified: {len(schema_tables)} tables; "
              f"{len(schema_tables) - len(manifest['fresh_seed_rows'])} business/operational tables empty.", flush=True)


def dependency_containers(arguments, settings, runtime, root):
    containers = []
    for component in ("mysql", "redis", "rabbitmq", "neo4j"):
        name = f"{arguments.instance}-{component}"
        present = subprocess.run(["docker", "container", "inspect", name], capture_output=True)
        if present.returncode == 0:
            raise InstallError(f"Container {name} already exists; use another instance name")
        containers.append(name)
    data = root / "data"
    data.mkdir(mode=0o700, exist_ok=True)
    mysql_environment = runtime / "mysql.env"
    write_protected(mysql_environment, "MYSQL_ROOT_PASSWORD=" + settings["MYSQL_ADMIN_PASSWORD"] + "\n")
    mysql = Mysql(settings, runtime, containers[0])
    run(["docker", "run", "-d", "--name", containers[0], "--restart", "unless-stopped",
         "-p", f"127.0.0.1:{settings['MYSQL_PORT']}:3306",
         "--mount", f"type=bind,src={data / 'mysql'},dst=/var/lib/mysql",
         "--mount", f"type=bind,src={mysql.options},dst=/run/secrets/mysql-admin.cnf,readonly",
         "--env-file", str(mysql_environment),
         "mysql:8.0", "--lower-case-table-names=1", "--character-set-server=utf8mb4",
         "--collation-server=utf8mb4_general_ci"], private=True)
    redis_configuration = runtime / "redis.conf"
    write_protected(redis_configuration, "bind 0.0.0.0\nprotected-mode yes\nappendonly yes\nrequirepass "
                    + settings["REDIS_PASSWORD"] + "\n", 0o600)
    run(["docker", "run", "-d", "--name", containers[1], "--restart", "unless-stopped",
         "-p", f"127.0.0.1:{settings['REDIS_PORT']}:6379",
         "--mount", f"type=bind,src={data / 'redis'},dst=/data",
         "--mount", f"type=bind,src={redis_configuration},dst=/usr/local/etc/redis/redis.conf,readonly",
         "redis:7-alpine", "sh", "-c", "cp /usr/local/etc/redis/redis.conf /data/qdata-redis.conf && chown redis:redis /data/qdata-redis.conf && chmod 600 /data/qdata-redis.conf && exec docker-entrypoint.sh redis-server /data/qdata-redis.conf"], private=True)
    rabbit_environment = runtime / "rabbitmq.env"
    write_protected(rabbit_environment, f"RABBITMQ_DEFAULT_USER={settings['RABBITMQ_USERNAME']}\n"
                    + f"RABBITMQ_DEFAULT_PASS={settings['RABBITMQ_PASSWORD']}\n")
    run(["docker", "run", "-d", "--name", containers[2], "--restart", "unless-stopped",
         "-p", f"127.0.0.1:{settings['RABBITMQ_PORT']}:5672", "--env-file", str(rabbit_environment),
         "--mount", f"type=bind,src={data / 'rabbitmq'},dst=/var/lib/rabbitmq", "rabbitmq:3.13-management"], private=True)
    neo4j_environment = runtime / "neo4j.env"
    write_protected(neo4j_environment, "NEO4J_AUTH=neo4j/" + settings["NEO4J_PASSWORD"] + "\n"
                    "NEO4J_server_memory_heap_initial__size=256m\n"
                    "NEO4J_server_memory_heap_max__size=512m\n"
                    "NEO4J_server_memory_pagecache_size=256m\n")
    run(["docker", "run", "-d", "--name", containers[3], "--restart", "unless-stopped",
         "-p", f"127.0.0.1:{arguments.neo4j_port}:7687", "--env-file", str(neo4j_environment),
         "--mount", f"type=bind,src={data / 'neo4j'},dst=/data", "neo4j:5.26-community"], private=True)
    return mysql


def runtime_properties(arguments, settings, root, service):
    runtime = root / "runtime"
    ports = {"web": arguments.backend_port, "quality": arguments.quality_port, "ai": arguments.ai_port}
    url = (f"jdbc:mysql://{settings['MYSQL_HOST']}:{settings['MYSQL_PORT']}/{settings['MYSQL_DATABASE']}"
           + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai")
    properties = {
        "server.port": ports[service], "spring.profiles.active": "prod", "datasource.type": "mysql",
        "server.forward-headers-strategy": "framework",
        "mysql.driver-class-name": "com.mysql.cj.jdbc.Driver", "mysql.url": url,
        "mysql.username": settings["MYSQL_APP_USER"], "mysql.password": "${QDATA_MYSQL_PASSWORD}",
        "spring.datasource.dynamic.datasource.master.url": url,
        "spring.datasource.dynamic.datasource.master.username": settings["MYSQL_APP_USER"],
        "spring.datasource.dynamic.datasource.master.password": "${QDATA_MYSQL_PASSWORD}",
        "spring.datasource.dynamic.datasource.master.driver-class-name": "com.mysql.cj.jdbc.Driver",
        "spring.data.redis.host": settings["REDIS_HOST"], "spring.data.redis.port": settings["REDIS_PORT"],
        "spring.data.redis.database": settings["REDIS_DATABASE"], "spring.data.redis.password": "${QDATA_REDIS_PASSWORD}",
        "ds.redis.host": settings["REDIS_HOST"], "ds.redis.port": settings["REDIS_PORT"],
        "ds.redis.database": settings["REDIS_DATABASE"], "ds.redis.password": "${QDATA_REDIS_PASSWORD}",
        "spring.rabbitmq.host": settings["RABBITMQ_HOST"], "spring.rabbitmq.port": settings["RABBITMQ_PORT"],
        "spring.rabbitmq.username": settings["RABBITMQ_USERNAME"], "spring.rabbitmq.password": "${QDATA_RABBITMQ_PASSWORD}",
        "spring.data.neo4j.uri": settings["NEO4J_URI"], "spring.data.neo4j.username": settings["NEO4J_USERNAME"],
        "spring.data.neo4j.password": "${QDATA_NEO4J_PASSWORD}",
        "spring.neo4j.uri": settings["NEO4J_URI"], "spring.neo4j.authentication.username": settings["NEO4J_USERNAME"],
        "spring.neo4j.authentication.password": "${QDATA_NEO4J_PASSWORD}",
        "token.secret": "${QDATA_TOKEN_SECRET}", "user.password.universalPassword": "",
        "qdata.embedded-redis.enabled": "false", "custom.mongo.enabled": "false",
        "qdata.profile": str(root / "upload") + "/", "logging.level.tech.qiantong": "INFO",
        "logging.file.name": str(root / "logs" / (service + ".log")),
        "spring.devtools.restart.enabled": "false", "spring.datasource.druid.stat-view-servlet.enabled": "false",
        "ds.quality_url": f"http://127.0.0.1:{arguments.quality_port}/quality/qualityTaskExecutor/runExecuteTask",
        "ds.mc_url": f"http://127.0.0.1:{arguments.web_port}/mc/taskExecutor/runExecuteTask",
        "path.quality_url": f"http://127.0.0.1:{arguments.quality_port}/quality/qualityTaskExecutor",
        "path.mc_url": f"http://127.0.0.1:{arguments.web_port}/mc/taskExecutor",
        "ds.base_url": "${QDATA_DS_BASE_URL:}", "ds.token": "${QDATA_DS_TOKEN:}",
        "warehouse.ds.project-code": "${WAREHOUSE_DS_PROJECT_CODE:}",
        "warehouse.ds.health-url": "${WAREHOUSE_DS_HEALTH_URL:}",
        "ds.spark.main_jar": "${QDATA_DS_ETL_JAR_URI:}",
        "ds.spark.master_url": "${QDATA_SPARK_MASTER:yarn}",
        "ds.spark.hive_metastore_uris": "${QDATA_HIVE_METASTORE_URIS:}",
        "ds.hdfs.url": "${QDATA_HDFS_URL:}",
        "ds.http_quality_projectCode": "${WAREHOUSE_DS_PROJECT_CODE:}",
        "ds.http_mc_projectCode": "${WAREHOUSE_DS_PROJECT_CODE:}",
        "file.job.log.qualitytask_prefix_url": str(root / "upload/job-log/quality-task"),
        "dromara.x-file-storage.local-plus[0].platform": "local",
        "dromara.x-file-storage.local-plus[0].enable-storage": "true",
        "dromara.x-file-storage.local-plus[0].enable-access": "true",
        "dromara.x-file-storage.local-plus[0].path-patterns": "/local-plus/**",
        "dromara.x-file-storage.local-plus[0].storage-path": str(root / "upload") + "/",
        "dromara.x-file-storage.aliyun-oss[0].enable-storage": "false",
        "knife4j.basic.enable": "false", "id.workerId": "1" if service == "web" else "2" if service == "quality" else "3",
        "id.datacenterId": "1",
    }
    if service == "web":
        properties["server.address"] = "127.0.0.1"
    if arguments.datax_home:
        properties.update({"datax.home": str(arguments.datax_home),
                           "datax.datax-py-path": str(arguments.datax_home / "bin/datax.py"),
                           "datax.lib-dir": str(arguments.datax_home / "lib"),
                           "datax.python-command": "python3", "datax.job-dir": str(root / "upload/datax/job")})
    if arguments.hadoop_conf_dir:
        properties["ds.spark.hadoop_conf_dir"] = str(arguments.hadoop_conf_dir.resolve())
    if arguments.spark_home:
        properties["ds.spark.submit_path"] = str(arguments.spark_home.resolve() / "bin/spark-submit")
    content = "\n".join(f"{key}={value}" for key, value in sorted(properties.items())) + "\n"
    write_protected(runtime / f"application-{service}.properties", content, 0o640)


def install_services(arguments, root, java, artifacts):
    account = arguments.instance
    account_lookup = subprocess.run(["id", "-u", account], capture_output=True)
    if account_lookup.returncode == 0:
        raise InstallError("System account already exists; choose a new instance name")
    run(["useradd", "--system", "--user-group", "--home-dir", str(root), "--shell", "/usr/sbin/nologin", account])
    run(["chown", f"root:{account}", str(root), str(root / "runtime")])
    root.chmod(0o750)
    (root / "runtime").chmod(0o750)
    for directory in ("upload", "logs", "state"):
        path = root / directory
        path.mkdir(mode=0o750)
        run(["chown", f"{account}:{account}", str(path)])
    (root / "lib").mkdir(mode=0o750)
    run(["chown", f"root:{account}", str(root / "lib")])
    if (root / "tools").exists():
        run(["chown", "-R", f"root:{account}", str(root / "tools")])
        (root / "tools").chmod(0o750)
        for directory in ("log", "log_perf", "tmp"):
            path = root / "tools/datax" / directory
            path.mkdir(exist_ok=True, mode=0o750)
            run(["chown", "-R", f"{account}:{account}", str(path)])
    services = {"web": "qdata-server.jar", "quality": "qdata-service-quality.jar"}
    if arguments.with_ai:
        services["ai"] = "qdata-ai-server.jar"
    services_to_start = []
    for service, jar in services.items():
        shutil.copy2(artifacts / jar, root / "lib" / jar)
        runtime_properties(arguments, arguments.dependencies, root, service)
        launch = (f'{java} -Xms{arguments.java_xms} -Xmx{arguments.java_xmx} -Djava.awt.headless=true -Duser.dir={root} '
                  + f'-jar {root / "lib" / jar} --spring.profiles.active=prod '
                  + f'--spring.config.additional-location=file:{root}/runtime/application-{service}.properties,'
                  + f'optional:file:{root}/runtime/external.properties')
        unit = f"""[Unit]
Description=qData {service} ({arguments.instance})
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User={account}
Group={account}
WorkingDirectory={root}
EnvironmentFile={root}/runtime/runtime.env
ExecStart={launch}
Restart=on-failure
RestartSec=10
TimeoutStopSec=60
UMask=0027
NoNewPrivileges=true
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
"""
        name = f"{arguments.instance}-{service}.service"
        write_protected(Path("/etc/systemd/system") / name, unit, 0o644)
        services_to_start.append(name)
    shutil.copy2(artifacts / "qdata-executor-etl.jar", root / "lib/qdata-executor-etl.jar")
    for path in (root / "runtime").iterdir():
        if path.name in {"runtime.env", "rsa-private.pem", "rsa-public.pem", "external.properties", "fonts.conf"} or path.name.startswith("application-"):
            run(["chown", f"root:{account}", str(path)])
            path.chmod(0o640)
    run(["systemctl", "daemon-reload"])
    for name in services_to_start:
        run(["systemctl", "enable", "--now", name])
    return services_to_start


def install_gateway(arguments, root):
    runtime = root / "runtime"
    gateway_configuration = runtime / "nginx.conf"
    ai_location = (f"location /prod-ai/ {{ proxy_pass http://127.0.0.1:{arguments.ai_port}/; "
                   + "proxy_http_version 1.1; proxy_set_header Host $http_host; proxy_set_header X-Real-IP $remote_addr; "
                   + "proxy_set_header X-Forwarded-Proto $scheme; proxy_set_header X-Forwarded-Port $server_port; "
                   + "proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for; proxy_buffering off; proxy_read_timeout 600s; }")
    if not arguments.with_ai:
        ai_location = "location /prod-ai/ { return 503; }"
    configuration = f"""worker_processes auto;
pid /tmp/{arguments.instance}-nginx.pid;
error_log stderr warn;
events {{ worker_connections 1024; }}
http {{
    access_log off;
    client_max_body_size 3000m;
    server {{
        listen {arguments.web_port};
        {ai_location}
        location /prod-api/ {{
            proxy_pass http://127.0.0.1:{arguments.backend_port}/;
            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-Forwarded-Port $server_port;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_read_timeout 600s;
        }}
        location / {{
            proxy_pass http://127.0.0.1:{arguments.backend_port};
            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-Forwarded-Port $server_port;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_read_timeout 600s;
        }}
    }}
}}
"""
    write_protected(gateway_configuration, configuration, 0o600)
    if shutil.which("docker"):
        name = arguments.instance + "-gateway"
        if subprocess.run(["docker", "inspect", name], capture_output=True).returncode == 0:
            raise InstallError("Gateway container already exists; choose a new instance name")
        run(["docker", "run", "-d", "--name", name, "--restart", "unless-stopped", "--network", "host",
             "--mount", f"type=bind,src={gateway_configuration},dst=/etc/nginx/nginx.conf,readonly",
             "nginx:1.28-alpine"], private=True)
        return "docker:" + name
    nginx = shutil.which("nginx")
    if not nginx:
        raise InstallError("Install Nginx or Docker for the same-origin frontend/API gateway")
    name = arguments.instance + "-gateway.service"
    write_protected(Path("/etc/systemd/system") / name, f"""[Unit]
Description=qData frontend gateway ({arguments.instance})
After=network-online.target {arguments.instance}-web.service

[Service]
Type=simple
ExecStart={nginx} -c {gateway_configuration} -g "daemon off;"
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
""", 0o644)
    run(["systemctl", "daemon-reload"])
    run(["systemctl", "enable", "--now", name])
    return name


def check_web(arguments, names):
    api_prefix = getattr(arguments, "frontend_api_base", "/").rstrip("/")
    for attempt in range(150):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{arguments.web_port}{api_prefix}/captchaImage", timeout=3) as response:
                payload = json.load(response)
                if payload.get("code") == 200:
                    break
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(2)
    else:
        raise InstallError("qData did not pass the local HTTP startup check; inspect instance systemd logs")
    frontend_resources = check_frontend(f"http://127.0.0.1:{arguments.web_port}")
    print(f"Login HTML and {frontend_resources} real entry JS/CSS resources verified.", flush=True)
    await_tcp("127.0.0.1", arguments.quality_port, "qData quality service")
    await_json_http(f"http://127.0.0.1:{arguments.quality_port}/actuator/health",
                    "qData quality SQL readiness", 200, "status", "UP")
    if arguments.with_ai:
        await_tcp("127.0.0.1", arguments.ai_port, "qData AI service")
        await_json_http(f"http://127.0.0.1:{arguments.web_port}/prod-ai/chat/conversation/myList",
                        "qData AI authenticated application routes", 401, "code", 401)
    for name in names:
        if run(["systemctl", "is-active", name]) != "active":
            raise InstallError(f"Service is not active: {name}")


class FrontendEntryParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.app_root = False
        self.resources = set()

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        self.app_root = self.app_root or values.get("id") == "app"
        candidate = values.get("src") if tag == "script" else values.get("href") if tag == "link" else None
        if candidate and candidate.startswith("/assets/") and urllib.parse.urlparse(candidate).path.endswith((".js", ".css")):
            self.resources.add(candidate)


def check_frontend(base):
    request = urllib.request.Request(base + "/login", headers={"Accept": "text/html", "Accept-Encoding": "identity"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            body = response.read()
            if response.status != 200 or content_type != "text/html":
                raise InstallError("Login page did not return actual HTML")
        parser = FrontendEntryParser()
        parser.feed(body.decode("utf-8"))
        if not parser.app_root or not parser.resources or not any(urllib.parse.urlparse(path).path.endswith(".js") for path in parser.resources):
            raise InstallError("Login HTML is missing the Vue app or real entry resources")
        for path in sorted(parser.resources):
            javascript = urllib.parse.urlparse(path).path.endswith(".js")
            acceptable = {"text/javascript", "application/javascript"} if javascript else {"text/css"}
            request = urllib.request.Request(base + path, headers={"Accept": "text/javascript" if javascript else "text/css"})
            with urllib.request.urlopen(request, timeout=30) as response:
                content_type = response.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                prefix = response.read(4096).lstrip()
                if response.status != 200 or content_type not in acceptable or not prefix or prefix.startswith(b"<"):
                    raise InstallError("Frontend entry resource has an unexpected status, MIME type or body: " + path)
        return len(parser.resources)
    except (OSError, ValueError, urllib.error.URLError) as exception:
        raise InstallError("Login HTML or entry resource HTTP validation failed; inspect this instance logs") from exception


def argument_parser():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", type=Path, default=Path("/data/qdata-new"))
    parser.add_argument("--instance", default="qdata-new")
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--admin-password-file", type=Path)
    parser.add_argument("--key-file", type=Path)
    parser.add_argument("--existing-dependencies", action="store_true")
    parser.add_argument("--dependencies-file", type=Path)
    parser.add_argument("--artifacts-dir", type=Path, default=REPOSITORY / "deploy/artifacts")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--dependency-dir", type=Path)
    parser.add_argument("--with-ai", action="store_true")
    parser.add_argument("--web-port", type=int, default=8080)
    parser.add_argument("--backend-port", type=int, default=18080)
    parser.add_argument("--quality-port", type=int, default=8083)
    parser.add_argument("--ai-port", type=int, default=8087)
    parser.add_argument("--mysql-port", type=int, default=13306)
    parser.add_argument("--redis-port", type=int, default=16379)
    parser.add_argument("--rabbitmq-port", type=int, default=25672)
    parser.add_argument("--neo4j-port", type=int, default=17687)
    parser.add_argument("--java-xms", default="256m")
    parser.add_argument("--java-xmx", default="2g")
    parser.add_argument("--datax-home", type=Path)
    parser.add_argument("--datax-archive", type=Path)
    parser.add_argument("--datax-sha256")
    parser.add_argument("--skip-datax", action="store_true")
    parser.add_argument("--hadoop-conf-dir", type=Path)
    parser.add_argument("--spark-home", type=Path)
    parser.add_argument("--font-runtime", type=Path)
    return parser


def install(arguments):
    if sys.platform != "linux" or os.geteuid() != 0:
        raise InstallError("Run the installer as root on the destination Linux machine")
    if not INSTANCE.fullmatch(arguments.instance):
        raise InstallError("Instance name must contain lowercase letters, digits and hyphens")
    root = arguments.install_dir.resolve()
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(root)) or root == Path("/"):
        raise InstallError("Installation path must be an absolute path without whitespace")
    if root.exists() and any(root.iterdir()):
        raise InstallError("Installation directory is not empty; existing installations are never overwritten")
    if not Path("/run/systemd/system").is_dir():
        raise InstallError("A running systemd system manager is required")
    for command in ("systemctl", "useradd", "openssl"):
        if not shutil.which(command):
            raise InstallError(f"Install required tool: {command}")
    check_font_environment(arguments.font_runtime)
    if not shutil.which("docker") and not shutil.which("nginx"):
        raise InstallError("Install Docker Engine or Nginx for the frontend/API gateway")
    if not arguments.existing_dependencies:
        if not shutil.which("docker"):
            raise InstallError("Install Docker Engine, or use --existing-dependencies")
        run(["docker", "info", "--format", "{{.ServerVersion}}"])
    for value in (arguments.java_xms, arguments.java_xmx):
        if not re.fullmatch(r"[1-9][0-9]*[mMgG]", value):
            raise InstallError("Java heap sizes must have m or g units")
    ports = [arguments.web_port, arguments.backend_port, arguments.quality_port]
    if arguments.with_ai:
        ports.append(arguments.ai_port)
    if not arguments.existing_dependencies:
        ports.extend([arguments.mysql_port, arguments.redis_port, arguments.rabbitmq_port, arguments.neo4j_port])
    if len(set(ports)) != len(ports):
        raise InstallError("Instance ports must be different")
    for port in ports:
        available_port(port)
    for service in ("web", "quality", "ai", "gateway"):
        if (Path("/etc/systemd/system") / f"{arguments.instance}-{service}.service").exists():
            raise InstallError("Instance systemd unit already exists; choose a new instance name")
    if subprocess.run(["id", "-u", arguments.instance], capture_output=True).returncode == 0:
        raise InstallError("Instance user already exists; choose a new instance name")
    java = java21(arguments.java_home)
    assert_toolchain_access(java)
    if arguments.spark_home:
        assert_toolchain_access(arguments.spark_home / "bin/spark-submit")
    if arguments.hadoop_conf_dir:
        assert_toolchain_access(arguments.hadoop_conf_dir)
    if arguments.font_runtime:
        assert_toolchain_access(arguments.font_runtime / "usr/bin/fc-list")
    arguments.dependencies = dependency_settings(arguments)
    password = administrator_password(arguments.admin_password_file)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    runtime = root / "runtime"
    runtime.mkdir(mode=0o700)
    aes, public, frontend = generate_keys(arguments, runtime)
    settings = arguments.dependencies
    environment = {
        "QDATA_AES_KEY": aes, "QDATA_RSA_PRIVATE_KEY_FILE": str(runtime / "rsa-private.pem"),
        "QDATA_RSA_PUBLIC_KEY_FILE": str(runtime / "rsa-public.pem"),
        "QDATA_MYSQL_PASSWORD": settings["MYSQL_APP_PASSWORD"], "QDATA_REDIS_PASSWORD": settings["REDIS_PASSWORD"],
        "QDATA_RABBITMQ_PASSWORD": settings["RABBITMQ_PASSWORD"], "QDATA_NEO4J_PASSWORD": settings["NEO4J_PASSWORD"],
        "QDATA_TOKEN_SECRET": secrets.token_urlsafe(48), "QDATA_DS_BASE_URL": "", "QDATA_DS_TOKEN": "",
        "WAREHOUSE_DS_PROJECT_CODE": "", "WAREHOUSE_DS_HEALTH_URL": "",
        "QDATA_DS_ETL_JAR_URI": "file:" + str(root / "lib/qdata-executor-etl.jar"),
        "JAVA_HOME": str(java.parent.parent),
        "PATH": str(java.parent) + ":/usr/local/bin:/usr/bin:/bin",
    }
    if arguments.hadoop_conf_dir:
        environment["HADOOP_CONF_DIR"] = str(arguments.hadoop_conf_dir.resolve())
    if arguments.spark_home:
        environment["SPARK_HOME"] = str(arguments.spark_home.resolve())
        environment["PATH"] = str(java.parent) + ":" + str(arguments.spark_home.resolve() / "bin") + ":/usr/local/bin:/usr/bin:/bin"
    if arguments.font_runtime:
        fonts = arguments.font_runtime.resolve()
        configuration = runtime / "fonts.conf"
        write_protected(configuration, font_configuration(fonts, root / "state/fontconfig"), 0o640)
        environment.update(font_environment(fonts, configuration))
    write_protected(runtime / "runtime.env", env_text(environment), 0o640)
    write_protected(runtime / "external.properties", "# Add external cluster settings here.\n"
                    "# Hive/Kyuubi/Doris credentials and connections are configured through the qData UI.\n"
                    "# DS HTTP callback URLs must be reachable from all DolphinScheduler Workers.\n", 0o640)
    artifacts = build_artifacts(arguments, runtime, java, frontend)
    prepare_datax(arguments, root)
    print("Build keys and artifacts verified. Preparing isolated dependencies.", flush=True)
    helper_classpath = str(artifacts) + ":" + str(artifacts / "tools/security-jars/*")
    administrator_hash = run([str(java), "-cp", helper_classpath, "PasswordHash"], input_text=password + "\n", private=True)
    password = None
    if not re.fullmatch(r"\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}", administrator_hash):
        raise InstallError("Administrator hash generation failed")
    if arguments.existing_dependencies:
        mysql = Mysql(settings, runtime)
    else:
        for directory in ("mysql", "redis", "rabbitmq", "neo4j"):
            (root / "data" / directory).mkdir(parents=True, mode=0o700)
        mysql = dependency_containers(arguments, settings, runtime, root)
    mysql.await_ready()
    mysql.initialize(administrator_hash)
    await_tcp(settings["REDIS_HOST"], settings["REDIS_PORT"], "Redis")
    await_tcp(settings["RABBITMQ_HOST"], settings["RABBITMQ_PORT"], "RabbitMQ")
    neo4j_uri = urllib.parse.urlparse(settings["NEO4J_URI"])
    if neo4j_uri.hostname:
        await_tcp(neo4j_uri.hostname, neo4j_uri.port or 7687, "Neo4j")
    print("Empty database initialized. Starting independent qData services.", flush=True)
    names = install_services(arguments, root, java, artifacts)
    gateway = install_gateway(arguments, root)
    check_web(arguments, names)
    report = {"instance": arguments.instance, "database": settings["MYSQL_DATABASE"], "services": names,
              "webPort": arguments.web_port, "backendPort": arguments.backend_port,
              "frontendApiBase": arguments.frontend_api_base,
              "qualityPort": arguments.quality_port, "gateway": gateway,
              "startupVerified": True, "externalClusterAcceptance": "REQUIRES_CONFIGURATION_AND_VALIDATION",
              "datax": {"home": str(arguments.datax_home) if arguments.datax_home else None,
                        "provisioned": arguments.datax_home is not None, "taskAcceptance": "NOT_EXECUTED"},
              "aiServiceInstalled": arguments.with_ai,
              "build": json.loads((artifacts / "build-manifest.json").read_text(encoding="utf-8")),
              "artifactSha256": {path.name: file_sha256(path)
                                  for path in (root / "lib").glob("*.jar")}}
    write_protected(root / "installation.json", json.dumps(report, indent=2) + "\n", 0o640)
    print(f"Installed {arguments.instance}; admin login at http://<Linux-host>:{arguments.web_port}/")
    print(f"Protected configuration: {runtime}; configure external clusters before business acceptance.")


if __name__ == "__main__":
    try:
        install(argument_parser().parse_args())
    except (InstallError, OSError, ValueError) as exception:
        print(f"Installation stopped safely: {exception}", file=sys.stderr)
        sys.exit(1)
