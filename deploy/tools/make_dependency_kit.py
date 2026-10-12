#!/usr/bin/env python3
import argparse
import hashlib
import json
import platform
import shutil
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Create an optional Linux third-party-only dependency kit")
    parser.add_argument("--maven-repo", type=Path, required=True)
    parser.add_argument("--node-modules", type=Path, required=True)
    parser.add_argument("--lock-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    if platform.system() != "Linux":
        raise ValueError("Create the dependency kit on Linux with the same CPU architecture as the target")
    if arguments.output.exists():
        raise ValueError("Choose a new output directory; an existing dependency kit is never overwritten")
    arguments.output.mkdir(parents=True)
    def ignore(directory, names):
        if Path(directory).resolve() == arguments.maven_repo.resolve() / "tech":
            return [name for name in names if name == "qiantong"]
        return [name for name in names if name == "_remote.repositories" or name.endswith((".lastUpdated", ".part", ".lock"))]
    shutil.copytree(arguments.maven_repo, arguments.output / "maven", ignore=ignore)
    shutil.copytree(arguments.node_modules, arguments.output / "node_modules", symlinks=True)
    report = {"os": "Linux", "arch": platform.machine(),
              "npmLockSha256": hashlib.sha256(arguments.lock_file.read_bytes()).hexdigest(),
              "qdataModulesIncluded": False}
    (arguments.output / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Third-party dependency kit ready: {arguments.output}")


if __name__ == "__main__":
    main()
