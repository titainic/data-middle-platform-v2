#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def run(command, cwd=None, env=None):
    subprocess.run(command, cwd=cwd, env=env, check=True)


def ignore_source(directory, names):
    ignored = []
    for name in names:
        # This build directory contains a Vue page, not generated output.
        if name == "build" and Path(directory).parts[-5:] == ("qdata-ui", "src", "views", "sys", "tool"):
            continue
        if name in {".git", "target", "dist", "node_modules", "runtime", "runtime-data", ".offline", ".codex-backups", "upload", "build", ".idea"}:
            ignored.append(name)
        elif name.endswith((".pem", ".key", ".jar", ".class", ".log")):
            ignored.append(name)
        elif name.startswith("application") and name.endswith(".yml") and not name.endswith(".example.yml"):
            ignored.append(name)
        elif name.startswith(".env") and not name.endswith(".example"):
            ignored.append(name)
        elif name in {".flattened-pom.xml", "dependency-reduced-pom.xml"}:
            ignored.append(name)
    return ignored


def load_frontend_env(path, environment):
    if not path:
        return
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"(VITE_[A-Z0-9_]+)\s*=\s*(.*)", line)
        if not match:
            raise ValueError(f"{path}:{number}: expected VITE_NAME=value")
        value = match.group(2)
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        environment[match.group(1)] = value


def copy_template_files(source):
    for template in source.rglob("application*.example.yml"):
        shutil.copy2(template, template.with_name(template.name.replace(".example.yml", ".yml")))
    frontend = source / "qdata-ui"
    shutil.copy2(frontend / ".env.production.example", frontend / ".env.production")


def main():
    parser = argparse.ArgumentParser(description="Build qData from reviewed source on Linux with JDK 21")
    parser.add_argument("--offline", action="store_true", help="Use only the supplied third-party dependency kit")
    parser.add_argument("--dependency-dir", type=Path, help="Directory containing maven/, node_modules/ and manifest.json")
    parser.add_argument("--env-file", type=Path, help="Private frontend VITE environment file")
    parser.add_argument("--skip-ai", action="store_true", help="Explicitly omit the independent AI inference service")
    parser.add_argument("--maven-settings", type=Path, help="Optional Maven settings.xml for your repository mirror")
    arguments = parser.parse_args()
    if platform.system() != "Linux":
        raise ValueError("Build on the target Linux machine. macOS builds are not supported by this deployment script.")
    environment = os.environ.copy()
    load_frontend_env(arguments.env_file, environment)
    aes_key = environment.get("VITE_APP_AES_KEY", environment.get("QDATA_AES_KEY", ""))
    if len(aes_key.encode("utf-8")) != 16 or aes_key == "replace-at-build-time":
        raise ValueError("Provide a private 16-byte VITE_APP_AES_KEY (or QDATA_AES_KEY) matching the runtime AES key")
    environment["VITE_APP_AES_KEY"] = aes_key
    public_key = environment.get("VITE_APP_RSA_PUBLIC_KEY", "").replace("\\n", "\n")
    if "-----BEGIN PUBLIC KEY-----" not in public_key:
        raise ValueError("Provide VITE_APP_RSA_PUBLIC_KEY from the independent browser rememberMe RSA pair")
    if "-----BEGIN PRIVATE KEY-----" not in environment.get("VITE_APP_RSA_PRIVATE_KEY", "").replace("\\n", "\n"):
        raise ValueError("Provide VITE_APP_RSA_PRIVATE_KEY from the browser pair; never supply the backend private key")
    java_home = environment.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    maven = shutil.which("mvn")
    node = shutil.which("node")
    npm = shutil.which("npm")
    if not all((java, javac, maven, node, npm)):
        raise ValueError("Install JDK 21, Maven 3.9+, Node.js 20+ and npm first")
    java_version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
    if not re.search(r'version "21[.\"]', java_version):
        raise ValueError("JAVA_HOME/java must use JDK 21")
    node_version = subprocess.check_output([node, "--version"], text=True).strip()
    if int(node_version.lstrip("v").split(".")[0]) < 20:
        raise ValueError("Node.js 20 or later is required")
    root = Path(__file__).resolve().parents[2]
    build = root / "deploy/build" / time.strftime("%Y%m%d-%H%M%S")
    source = build / "source"
    artifacts = root / "deploy/artifacts"
    artifacts.mkdir(parents=True, exist_ok=True)
    shutil.copytree(root / "qdata-work", source, ignore=ignore_source)
    copy_template_files(source)
    frontend_defaults = {}
    load_frontend_env(source / "qdata-ui/.env.production", frontend_defaults)
    frontend_api_base = environment.get("VITE_APP_BASE_API", frontend_defaults.get("VITE_APP_BASE_API", "/"))
    repository = build / "maven"
    if arguments.dependency_dir:
        kit = arguments.dependency_dir.resolve()
        manifest = json.loads((kit / "manifest.json").read_text())
        if manifest.get("os") != "Linux" or manifest.get("arch") != platform.machine():
            raise ValueError("The dependency kit must match the target Linux CPU architecture")
        if manifest.get("npmLockSha256") != digest(source / "qdata-ui/package-lock.json"):
            raise ValueError("The dependency kit does not match package-lock.json; create a fresh kit")
        if (kit / "maven/tech/qiantong").exists():
            raise ValueError("Dependency kits must exclude qData private modules; rebuild them from source")
        shutil.copytree(kit / "maven", repository,
                        ignore=shutil.ignore_patterns("_remote.repositories", "*.lastUpdated", "*.part", "*.lock"))
        if arguments.offline:
            shutil.copytree(kit / "node_modules", source / "qdata-ui/node_modules", symlinks=True)
    elif arguments.offline:
        raise ValueError("--offline requires --dependency-dir with third-party dependencies")
    environment.setdefault("NODE_OPTIONS", "--max-old-space-size=6144")
    if not arguments.offline:
        run([npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=source / "qdata-ui", env=environment)
    run([sys.executable, str(root / "deploy/tools/prepare_frontend.py"), str(source / "qdata-ui"), "--node", node], env=environment)
    run([node, str(root / "deploy/tools/verify_browser_crypto.cjs"), str(source / "qdata-ui")], env=environment)
    run([npm, "run", "build:prod"], cwd=source / "qdata-ui", env=environment)
    for name in ("static", "dist/admin"):
        target = source / "qdata-server/src/main/resources" / name
        shutil.copytree(source / "qdata-ui/dist", target, dirs_exist_ok=True)
    favicon = source / "qdata-ui/dist/favicon.ico"
    if favicon.exists():
        shutil.copy2(favicon, source / "qdata-server/src/main/resources/dist/admin/assets/favicon.ico")
    command = [maven, "-B", "-Dmaven.test.skip=true", f"-Dmaven.repo.local={repository}"]
    if arguments.offline:
        command.append("-o")
    if arguments.maven_settings:
        command.extend(["-s", str(arguments.maven_settings.resolve())])
    run(command + ["clean", "install"], cwd=source, env=environment)
    products = {
        "qdata-server.jar": source / "qdata-server/target/qdata-server.jar",
        "qdata-service-quality.jar": source / "qdata-service-quality/target/qdata-service-quality.jar",
        "qdata-executor-etl.jar": source / "qdata-executor-etl/target/qdata-executor-etl.jar",
    }
    if not arguments.skip_ai:
        run(command + ["clean", "package"], cwd=source / "qdata-service-ai", env=environment)
        products["qdata-ai-server.jar"] = source / "qdata-service-ai/qdata-ai-server/target/qdata-ai-server.jar"
    for name, product in products.items():
        if not product.is_file() or not zipfile.is_zipfile(product):
            raise ValueError(f"Missing or invalid build output: {product}")
        shutil.copy2(product, artifacts / name)
    with zipfile.ZipFile(products["qdata-server.jar"]) as main_jar:
        entries = main_jar.namelist()
        if "BOOT-INF/classes/dist/admin/index.html" not in entries or not any(
                member.startswith("BOOT-INF/classes/dist/admin/assets/") and member.endswith(".js")
                for member in entries):
            raise ValueError("The main JAR must contain the actual Thymeleaf admin template and Vue assets")
    security_directory = artifacts / "tools/security-jars"
    security_directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(products["qdata-server.jar"]) as main_jar:
        security_jars = [name for name in main_jar.namelist() if name.startswith("BOOT-INF/lib/") and
                         Path(name).name.startswith(("spring-security-crypto-", "spring-jcl-"))]
        for member in security_jars:
            (security_directory / Path(member).name).write_bytes(main_jar.read(member))
    if len(security_jars) != 2:
        raise ValueError("Cannot locate BCrypt runtime dependencies in the new main service JAR")
    classpath = os.pathsep.join(str(security_directory / Path(member).name) for member in security_jars)
    run([javac, "-cp", classpath, "-d", str(artifacts), str(root / "deploy/tools/PasswordHash.java")], env=environment)
    artifact_paths = {name: artifacts / name for name in products}
    artifact_paths["PasswordHash.class"] = artifacts / "PasswordHash.class"
    for member in security_jars:
        filename = "tools/security-jars/" + Path(member).name
        artifact_paths[filename] = artifacts / filename
    report = {
        "builtAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "os": "Linux", "arch": platform.machine(), "java": java_version.splitlines()[0],
        "node": node_version, "aiIncluded": not arguments.skip_ai,
        "frontendAesSha256": hashlib.sha256(aes_key.encode()).hexdigest(),
        "frontendRsaPublicSha256": hashlib.sha256(public_key.encode()).hexdigest(),
        "frontendRsaPurpose": "browser-rememberme",
        "frontendApiBase": frontend_api_base,
        "frontendTemplate": "BOOT-INF/classes/dist/admin/index.html",
        "frontendAssets": "BOOT-INF/classes/dist/admin/assets/",
        "buildSource": str(source),
        "artifacts": {name: digest(path) for name, path in artifact_paths.items()},
    }
    (artifacts / "build-manifest.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Build complete: {artifacts}; AI service included: {report['aiIncluded']}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        sys.exit(1)
