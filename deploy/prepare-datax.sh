#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'HELP'
Usage: bash deploy/prepare-datax.sh --dest DIR [--archive FILE] [--sha256 SHA256] [--mysql-driver-jar FILE] [--hive-driver-jar FILE]

Install the official Alibaba DataX 202309 tool into a new Linux directory.
Without --archive, download the HTTPS distribution linked by Alibaba's user guide.
The fixed upstream package is checked against recorded SHA-256 and Content-MD5.
--archive supports an offline copy; --sha256 additionally verifies its SHA-256.
Offline installs require --mysql-driver-jar (Connector/J 9.7.0) and
--hive-driver-jar (Hive JDBC 4.2.0 standalone), matching qData dependencies.
Online installs download both pinned drivers from Maven Central. Both generic
RDBMS plugins are configured for MySQL and Hive2 before publication.
An existing destination is never overwritten. No database or cluster task runs.
HELP
}

destination=''
archive_file=''
expected_sha256=''
mysql_driver_jar=''
hive_driver_jar=''
while (($#)); do
    case "$1" in
        --dest|--archive|--sha256|--mysql-driver-jar|--hive-driver-jar)
            if (($# < 2)); then printf 'Missing value for %s\n' "$1" >&2; exit 2; fi
            case "$1" in
                --dest) destination=$2 ;;
                --archive) archive_file=$2 ;;
                --sha256) expected_sha256=$2 ;;
                --mysql-driver-jar) mysql_driver_jar=$2 ;;
                --hive-driver-jar) hive_driver_jar=$2 ;;
            esac
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

if [[ -n $archive_file && ( -z $mysql_driver_jar || -z $hive_driver_jar ) ]]; then
    printf 'Offline preparation requires --mysql-driver-jar mysql-connector-j-9.7.0.jar and --hive-driver-jar hive-jdbc-4.2.0-standalone.jar.\n' >&2
    exit 2
fi
if [[ $(uname -s) != Linux ]]; then printf 'Run this preparation on the target Linux host.\n' >&2; exit 2; fi
if [[ -z $destination ]]; then usage >&2; exit 2; fi
if [[ -e $destination || -L $destination ]]; then printf 'Destination already exists; select a new directory.\n' >&2; exit 2; fi
if [[ -n $expected_sha256 && ! $expected_sha256 =~ ^[[:xdigit:]]{64}$ ]]; then printf 'Invalid SHA-256 value.\n' >&2; exit 2; fi
for required_tool in python3 sha256sum md5sum tar; do
    command -v "$required_tool" >/dev/null || { printf 'Missing tool: %s\n' "$required_tool" >&2; exit 2; }
done

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/qdata-datax.XXXXXXXX")
trap 'rm -rf -- "$temporary_dir"' EXIT
upstream_url='https://datax-opensource.oss-cn-hangzhou.aliyuncs.com/202309/datax.tar.gz'
official_md5='490a7b694eae93334b11e9dc07683348'
official_sha256='5f3d8cbdf25fd8476c8124f6ca3c892a7f4c2286e55da629771848adff944788'
mysql_driver_sha256='0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44'
hive_driver_sha256='c89c6224df9d465ddf96060bc3e289522f1f6e64b01a28e01f94413edeb3d43a'
hive_driver_url='https://repo.maven.apache.org/maven2/org/apache/hive/hive-jdbc/4.2.0/hive-jdbc-4.2.0-standalone.jar'
if [[ -z $mysql_driver_jar ]]; then
    command -v curl >/dev/null || { printf 'Missing tool: curl\n' >&2; exit 2; }
    mysql_driver_jar="$temporary_dir/mysql-connector-j-9.7.0.jar"
    curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 \
        'https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.7.0/mysql-connector-j-9.7.0.jar' -o "$mysql_driver_jar"
fi
if [[ ! -f $mysql_driver_jar ]]; then printf 'MySQL JDBC driver is not a regular file.\n' >&2; exit 2; fi
if [[ $(sha256sum "$mysql_driver_jar" | cut -d ' ' -f 1) != "$mysql_driver_sha256" ]]; then
    printf 'MySQL Connector/J 9.7.0 SHA-256 mismatch.\n' >&2
    exit 1
fi
if [[ -z $hive_driver_jar ]]; then
    command -v curl >/dev/null || { printf 'Missing tool: curl\n' >&2; exit 2; }
    hive_driver_jar="$temporary_dir/hive-jdbc-4.2.0-standalone.jar"
    curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 \
        "$hive_driver_url" -o "$hive_driver_jar"
fi
if [[ ! -f $hive_driver_jar ]]; then printf 'Hive JDBC driver is not a regular file.\n' >&2; exit 2; fi
if [[ $(sha256sum "$hive_driver_jar" | cut -d ' ' -f 1) != "$hive_driver_sha256" ]]; then
    printf 'Hive JDBC 4.2.0 standalone SHA-256 mismatch.\n' >&2
    exit 1
fi
if [[ -z $archive_file ]]; then
    command -v curl >/dev/null || { printf 'Missing tool: curl\n' >&2; exit 2; }
    archive_file="$temporary_dir/datax.tar.gz"
    printf 'Downloading official DataX 202309 (about 1.56 GiB).\n'
    curl --fail --location --proto '=https' --proto-redir '=https' --retry 3 "$upstream_url" -o "$archive_file"
fi
if [[ ! -f $archive_file ]]; then printf 'Archive is not a regular file.\n' >&2; exit 2; fi
actual_sha256=$(sha256sum "$archive_file" | cut -d ' ' -f 1)
actual_md5=$(md5sum "$archive_file" | cut -d ' ' -f 1)
if [[ -n $expected_sha256 ]]; then
    if [[ ${actual_sha256,,} != ${expected_sha256,,} ]]; then printf 'DataX SHA-256 mismatch.\n' >&2; exit 1; fi
elif [[ $actual_md5 != $official_md5 || $actual_sha256 != $official_sha256 ]]; then
    printf 'The archive differs from the recorded official 202309 package. Provide an independently reviewed --sha256 for another package.\n' >&2
    exit 1
fi

python3 - "$archive_file" "$destination" "$upstream_url" "$actual_sha256" "$actual_md5" "$mysql_driver_jar" "$mysql_driver_sha256" "$hive_driver_jar" "$hive_driver_sha256" "$hive_driver_url" <<'PY'
import datetime
import json
import os
import pathlib
import shutil
import sys
import tarfile
import tempfile

archive_file, destination, upstream_url, sha256, md5, driver_jar, driver_sha256, hive_jar, hive_sha256, hive_url = sys.argv[1:]
target = pathlib.Path(os.path.abspath(destination))
target.parent.mkdir(parents=True, exist_ok=True)
if target.exists() or target.is_symlink():
    raise SystemExit("Destination appeared during preparation; refusing overwrite.")
with tempfile.TemporaryDirectory(prefix=".qdata-datax-", dir=str(target.parent)) as staging_dir:
    staging = pathlib.Path(staging_dir)
    with tarfile.open(archive_file, "r:gz") as archive:
        members = archive.getmembers()
        for member in members:
            name = pathlib.PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or not name.parts or name.parts[0] != "datax":
                raise SystemExit("Unexpected path in DataX archive.")
            if not (member.isdir() or member.isfile()):
                raise SystemExit("DataX archive contains unsupported links or special files.")
        archive.extractall(str(staging), members=members)
    payload = staging / "datax"
    for relative in ("bin/datax.py", "lib", "plugin", "conf"):
        if not (payload / relative).exists():
            raise SystemExit("Incomplete DataX distribution: " + relative)
    for kind in ("reader", "writer"):
        plugin = payload / "plugin" / kind / ("rdbms" + kind)
        registration = plugin / "plugin.json"
        metadata = json.loads(registration.read_text(encoding="utf-8"))
        drivers = metadata.get("drivers", [])
        if not isinstance(drivers, list):
            raise SystemExit("Invalid DataX driver registration: " + kind)
        libraries = plugin / "libs"
        libraries.mkdir(exist_ok=True)
        if list(libraries.glob("mysql-connector*.jar")):
            raise SystemExit("Distribution already contains a MySQL driver; review it before preparing a new installation.")
        if list(libraries.glob("hive-jdbc*.jar")):
            raise SystemExit("Distribution already contains a Hive JDBC driver; review it before preparing a new installation.")
        shutil.copyfile(driver_jar, libraries / "mysql-connector-j-9.7.0.jar")
        shutil.copyfile(hive_jar, libraries / "hive-jdbc-4.2.0-standalone.jar")
        if "com.mysql.cj.jdbc.Driver" not in drivers:
            drivers.append("com.mysql.cj.jdbc.Driver")
        if "org.apache.hive.jdbc.HiveDriver" not in drivers:
            drivers.append("org.apache.hive.jdbc.HiveDriver")
        metadata["drivers"] = drivers
        registration.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    manifest = {
        "tool": "Alibaba DataX",
        "distribution": "202309",
        "upstream": upstream_url,
        "archiveSha256": sha256,
        "archiveMd5": md5,
        "mysqlConnectorVersion": "9.7.0",
        "mysqlConnectorSha256": driver_sha256,
        "hiveJdbcVersion": "4.2.0",
        "hiveJdbcClassifier": "standalone",
        "hiveJdbcSha256": hive_sha256,
        "hiveJdbcUpstream": hive_url,
        "installedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "scope": "Tool distribution only; no qData credentials or deployment jobs imported.",
    }
    (payload / "qdata-tool-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if target.exists() or target.is_symlink():
        raise SystemExit("Destination appeared during extraction; refusing overwrite.")
    os.rename(str(payload), str(target))
print("DataX installed: " + str(target))
print("Archive SHA-256: " + sha256)
print("Configure datax.home and validate required reader/writer plugins before business tasks.")
PY
