#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'HELP'
Usage: bash deploy/prepare-datax.sh --dest DIR [--archive FILE] [--sha256 SHA256]

Install the official Alibaba DataX 202309 tool into a new Linux directory.
Without --archive, download the HTTPS distribution linked by Alibaba's user guide.
The fixed upstream package is checked against recorded SHA-256 and Content-MD5.
--archive supports an offline copy; --sha256 additionally verifies its SHA-256.
An existing destination is never overwritten. No database or cluster task runs.
HELP
}

destination=''
archive_file=''
expected_sha256=''
while (($#)); do
    case "$1" in
        --dest|--archive|--sha256)
            if (($# < 2)); then printf 'Missing value for %s\n' "$1" >&2; exit 2; fi
            case "$1" in
                --dest) destination=$2 ;;
                --archive) archive_file=$2 ;;
                --sha256) expected_sha256=$2 ;;
            esac
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

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

python3 - "$archive_file" "$destination" "$upstream_url" "$actual_sha256" "$actual_md5" <<'PY'
import datetime
import json
import os
import pathlib
import sys
import tarfile
import tempfile

archive_file, destination, upstream_url, sha256, md5 = sys.argv[1:]
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
    manifest = {
        "tool": "Alibaba DataX",
        "distribution": "202309",
        "upstream": upstream_url,
        "archiveSha256": sha256,
        "archiveMd5": md5,
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
