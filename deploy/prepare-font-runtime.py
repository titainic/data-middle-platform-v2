#!/usr/bin/env python3
"""Prepare a private-directory Ubuntu 20.04 amd64 font runtime without apt/dpkg installation."""

import argparse
import datetime
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from xml.sax.saxutils import escape


PACKAGES = (
    {"package": "fontconfig", "version": "2.13.1-2ubuntu3", "size": 171216,
     "path": "pool/main/f/fontconfig/fontconfig_2.13.1-2ubuntu3_amd64.deb",
     "sha256": "76e1692f4d1fce850b3fa0c7208d187396d8ee821762dcf9e365ed6ec7229035"},
    {"package": "fontconfig-config", "version": "2.13.1-2ubuntu3", "size": 28780,
     "path": "pool/main/f/fontconfig/fontconfig-config_2.13.1-2ubuntu3_all.deb",
     "sha256": "80b090925c52aff3f4681b361c9823b041c42c57cca58b5baf2541bafc25fed9"},
    {"package": "libfontconfig1", "version": "2.13.1-2ubuntu3", "size": 114072,
     "path": "pool/main/f/fontconfig/libfontconfig1_2.13.1-2ubuntu3_amd64.deb",
     "sha256": "7ca5523936a9b8e42f60c0f01c232145809387e5680b05c7bce0bea31844a522"},
    {"package": "fonts-dejavu-core", "version": "2.37-1", "size": 1040712,
     "path": "pool/main/f/fonts-dejavu/fonts-dejavu-core_2.37-1_all.deb",
     "sha256": "f2b3f7f51e23e0493e8e642c82003fe75cf42bc95fda545cc96b725a69adb515"},
)
ORIGIN = "https://archive.ubuntu.com/ubuntu/"


class PreparationError(RuntimeError):
    pass


def data_member(package_bytes):
    stream = io.BytesIO(package_bytes)
    if stream.read(8) != b"!<arch>\n":
        raise PreparationError("Invalid Debian package archive header")
    while True:
        header = stream.read(60)
        if not header:
            break
        if len(header) != 60 or header[58:60] != b"`\n":
            raise PreparationError("Invalid Debian package member header")
        name = header[:16].decode("ascii").strip().rstrip("/")
        try:
            size = int(header[48:58].decode("ascii").strip())
        except ValueError as exception:
            raise PreparationError("Invalid Debian package member size") from exception
        if size < 0:
            raise PreparationError("Negative Debian package member size")
        content = stream.read(size)
        if len(content) != size:
            raise PreparationError("Truncated Debian package archive")
        if size % 2:
            stream.read(1)
        if name.startswith("data.tar"):
            return io.BytesIO(content)
    raise PreparationError("Debian package is missing its data payload")


def relative_path(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise PreparationError("Package path escapes the new runtime directory")
    return Path(*path.parts)


def inside(path, root):
    return path == root or root in path.parents


def extract_payload(package_bytes, root):
    with tarfile.open(fileobj=data_member(package_bytes), mode="r:*") as archive:
        links = []
        for member in archive.getmembers():
            target = root / relative_path(member.name)
            if not inside(target.resolve(), root.resolve()):
                raise PreparationError("Package path follows a link outside the runtime directory")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True, mode=0o755)
            elif member.isfile():
                if target.is_symlink():
                    raise PreparationError("Package tries to overwrite a symbolic link")
                target.parent.mkdir(parents=True, exist_ok=True)
                payload = archive.extractfile(member)
                if payload is None:
                    raise PreparationError("Package file payload is missing")
                with target.open("wb") as output:
                    shutil.copyfileobj(payload, output)
                target.chmod(0o755 if member.mode & 0o111 else 0o644)
            elif member.issym() or member.islnk():
                links.append((member, target))
            else:
                raise PreparationError("Package contains an unsupported special file")
        for member, target in links:
            if member.islnk():
                destination = root / relative_path(member.linkname)
            else:
                link = PurePosixPath(member.linkname)
                destination = (root / str(link).lstrip("/")) if link.is_absolute() else target.parent / str(link)
            destination = destination.resolve()
            if not inside(destination, root.resolve()):
                raise PreparationError("Package link escapes the new runtime directory")
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.is_symlink() or target.exists():
                raise PreparationError("Package contains conflicting links")
            if member.islnk():
                os.link(destination, target)
            else:
                target.symlink_to(os.path.relpath(destination, target.parent))


def package_bytes(metadata, directory):
    filename = Path(metadata["path"]).name
    if directory:
        source = directory / filename
        if source.is_symlink() or not source.is_file():
            raise PreparationError("Missing regular offline Debian package: " + filename)
        payload = source.read_bytes()
    else:
        with urllib.request.urlopen(ORIGIN + metadata["path"], timeout=60) as response:
            if not response.geturl().startswith("https://"):
                raise PreparationError("Package download redirected outside HTTPS")
            payload = response.read(metadata["size"] + 1)
    if len(payload) != metadata["size"] or hashlib.sha256(payload).hexdigest() != metadata["sha256"]:
        raise PreparationError("Ubuntu package size or SHA-256 differs: " + filename)
    return payload


def prepare(arguments):
    if sys.platform != "linux" or platform.machine().lower() not in ("x86_64", "amd64"):
        raise PreparationError("This pinned offline runtime requires Linux x86_64 with glibc; use OS font packages on other architectures")
    target = Path(os.path.abspath(arguments.output))
    if target.exists() or target.is_symlink():
        raise PreparationError("Output already exists; choose a new directory")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".qdata-font-runtime-", dir=str(target.parent)) as directory:
        payload = Path(directory) / "payload"
        payload.mkdir(mode=0o755)
        packages_directory = payload / "packages"
        packages_directory.mkdir(mode=0o755)
        source_metadata = []
        for metadata in PACKAGES:
            content = package_bytes(metadata, arguments.debs_dir)
            (packages_directory / Path(metadata["path"]).name).write_bytes(content)
            extract_payload(content, payload)
            source_metadata.append(dict(metadata, origin=ORIGIN + metadata["path"]))
        for relative in ("usr/bin/fc-list", "usr/lib/x86_64-linux-gnu/libfontconfig.so.1", "usr/share/fonts"):
            if not (payload / relative).exists():
                raise PreparationError("The extracted runtime is incomplete: " + relative)
        probe_configuration = Path(directory) / "fonts.conf"
        probe_configuration.write_text('<fontconfig><dir>' + escape(str(payload / "usr/share/fonts"))
                                       + '</dir><cachedir>' + escape(str(Path(directory) / "cache"))
                                       + '</cachedir></fontconfig>\n', encoding="utf-8")
        environment = os.environ.copy()
        environment.update({"FONTCONFIG_FILE": str(probe_configuration),
                            "FONTCONFIG_PATH": str(payload / "etc/fonts"),
                            "LD_LIBRARY_PATH": str(payload / "usr/lib/x86_64-linux-gnu")})
        probe = subprocess.run([str(payload / "usr/bin/fc-list")], env=environment, capture_output=True, text=True)
        if probe.returncode or not probe.stdout.strip():
            raise PreparationError("Font runtime probe failed; install its platform libraries such as libfreetype6 and libexpat1")
        configuration = ('<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig><dir>'
                         + escape(str(target / "usr/share/fonts")) + '</dir><cachedir>'
                         + escape(str(target / "cache")) + '</cachedir></fontconfig>\n')
        (payload / "fonts.conf").write_text(configuration, encoding="utf-8")
        (payload / "cache").mkdir(mode=0o755)
        manifest = {"tool": "Fontconfig and DejaVu", "platform": "Ubuntu 20.04 amd64 / compatible glibc Linux",
                    "preparedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    "packages": source_metadata, "fontListProbe": "PASS", "scope": "Private runtime only; no OS packages installed"}
        (payload / "qdata-font-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        if target.exists() or target.is_symlink():
            raise PreparationError("Output appeared during preparation; refusing overwrite")
        os.rename(payload, target)
    print("Font runtime prepared: " + str(target))
    print("Use deploy/install.py --font-runtime " + str(target))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--debs-dir", type=Path)
    try:
        prepare(parser.parse_args())
    except (PreparationError, OSError, ValueError) as exception:
        print("Font preparation failed: " + str(exception), file=sys.stderr)
        sys.exit(1)
