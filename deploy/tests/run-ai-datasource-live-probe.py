#!/usr/bin/env python3
"""Run the real standalone AI/JDBC probe on an explicitly selected Linux test install."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile
import zipfile


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            result.update(chunk)
    return result.hexdigest()


def protected_file(path, allow_service_group=False):
    allowed = (0o600, 0o640) if allow_service_group else (0o600,)
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o777 not in allowed:
        raise ValueError("Credential files must be protected regular files (tokens: 600; service environment: 600/640)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ai-jar", type=Path, required=True)
    parser.add_argument("--install-dir", type=Path, required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--evidence-file", type=Path, required=True)
    parser.add_argument("--allow-synthetic-mysql", action="store_true",
                        help="Create and retain a new MySQL test table, project, datasource and memberships")
    args = parser.parse_args()
    if platform.system() != "Linux" or not args.java_home or not args.allow_synthetic_mysql:
        parser.error("Linux/JDK 21 and explicit --allow-synthetic-mysql are required")
    root = args.install_dir.resolve(strict=True)
    jar = args.ai_jar.resolve(strict=True)
    tokens = args.token_file.resolve(strict=True)
    protected_file(args.token_file)
    protected_file(root / "runtime/runtime.env", allow_service_group=True)
    if args.evidence_file.exists():
        parser.error("Use a new evidence filename; previous results are retained")
    installed = root / "lib/qdata-ai-server.jar"
    jar_hash = digest(jar)
    if not installed.is_file() or jar_hash != digest(installed):
        parser.error("The probe JAR must match the selected installation's actual AI JAR")
    environment = os.environ.copy()
    for line in (root / "runtime/runtime.env").read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
            raise ValueError("Invalid installation environment entry")
        environment[key] = str(json.loads(value))
    environment["JAVA_HOME"] = str(args.java_home)
    java_source = Path(__file__).resolve().with_name("AiDatasourceLiveProbe.java")
    log = args.evidence_file.with_suffix(".log")
    descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    returncode = 1
    with os.fdopen(descriptor, "w") as output, tempfile.TemporaryDirectory(prefix="qdata-ai-live-") as directory:
        target = Path(directory)
        with zipfile.ZipFile(jar) as archive:
            for member in archive.infolist():
                if member.filename.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")):
                    relative = Path(member.filename)
                    if relative.is_absolute() or ".." in relative.parts:
                        raise ValueError("Invalid JAR entry")
                    archive.extract(member, target)
        classpath = str(target / "BOOT-INF/classes") + ":" + str(target / "BOOT-INF/lib/*")
        compile_result = subprocess.run([str(args.java_home / "bin/javac"), "-proc:none", "-cp", classpath,
                                         "-d", str(target), str(java_source)], env=environment,
                                        stdout=output, stderr=subprocess.STDOUT)
        if compile_result.returncode == 0:
            result = subprocess.run([str(args.java_home / "bin/java"), "-Xms128m", "-Xmx512m",
                                     "-Djava.awt.headless=true", "-Duser.dir=" + str(root),
                                     "-cp", str(target) + ":" + classpath, "AiDatasourceLiveProbe", str(tokens),
                                     "--spring.profiles.active=prod",
                                     "--spring.config.additional-location=file:" + str(root / "runtime/application-ai.properties")
                                     + ",optional:file:" + str(root / "runtime/external.properties")],
                                    env=environment, cwd=root, stdout=output, stderr=subprocess.STDOUT)
            returncode = result.returncode
        else:
            returncode = compile_result.returncode
    match = re.search(r"AI_DATASOURCE_LIVE_PROBE_PASS table=([a-z0-9_]+) project=(\d+) datasource=(\d+)"
                      r" member=(\d+) nonmember=(\d+) columns=(\d+)", log.read_text())
    passed = returncode == 0 and match is not None
    report = {"status": "PASS" if passed else "FAIL", "sourceCommit": args.source_commit,
              "aiArtifactSha256": jar_hash, "installedAiArtifactMatches": True,
              "scope": "REAL_STANDALONE_SPRING_SHARED_REDIS_JWT_MYSQL_DATASOURCE_PROJECT_AUTHORIZATION",
              "fixtureAuthentication": False, "llmInference": "NOT_EXECUTED", "hiveHdfs": "NOT_ACCESSED",
              "createdTestData": "RETAINED", "temporaryService": "CLOSED_ON_PROCESS_EXIT",
              "configurationLocation": str(root / "runtime"), "protectedLogLocation": str(log),
              "exitCode": returncode}
    if match:
        report["retainedMetadata"] = dict(zip(("table", "projectId", "datasourceId", "memberUserId",
                                                "nonmemberUserId", "columnCount"),
                                               [match[1]] + [int(value) for value in match.groups()[1:]]))
    descriptor = os.open(args.evidence_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(report, output, indent=2)
        output.write("\n")
    print(json.dumps({"status": report["status"], "evidence": str(args.evidence_file),
                      "retainedMetadata": report.get("retainedMetadata")}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
