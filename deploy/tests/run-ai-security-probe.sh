#!/usr/bin/env bash
set -euo pipefail
if [[ $(uname -s) != Linux ]]; then printf 'Run this HTTP probe on Linux with JDK 21.\n' >&2; exit 2; fi
if (($# != 1)); then printf 'Usage: bash deploy/tests/run-ai-security-probe.sh /path/to/qdata-ai-server.jar\n' >&2; exit 2; fi
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if ! java -version 2>&1 | grep -Eq 'version "21([.]|")'; then printf 'JDK 21 is required.\n' >&2; exit 2; fi
ai_jar=$(realpath "$1")
test_source=$(cd -- "$(dirname -- "$0")" && pwd)/AiChatSecurityHttpProbe.java
test_directory=$(mktemp -d "${TMPDIR:-/tmp}/qdata-ai-security.XXXXXXXX")
trap 'rm -rf -- "$test_directory"' EXIT
python3 - "$ai_jar" "$test_directory" <<'PY'
import pathlib
import sys
import zipfile
root = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(sys.argv[1]) as archive:
    for member in archive.infolist():
        if member.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
            path = pathlib.PurePosixPath(member.filename)
            if '..' in path.parts or path.is_absolute():
                raise SystemExit('Invalid JAR path')
            archive.extract(member, root)
PY
classpath="$test_directory/BOOT-INF/classes:$test_directory/BOOT-INF/lib/*"
javac -proc:none -cp "$classpath" -d "$test_directory" "$test_source"
java -Djava.awt.headless=true -cp "$test_directory:$classpath" AiChatSecurityHttpProbe
