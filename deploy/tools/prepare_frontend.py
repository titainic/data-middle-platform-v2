#!/usr/bin/env python3
"""仅运行已审阅并钉住 SHA256 的 Vue 3 依赖适配脚本。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def prepare(frontend, node):
    frontend = Path(frontend)
    manifest = json.loads((Path(__file__).resolve().parents[1] / "frontend-install-scripts.json").read_text())
    if hashlib.sha256((frontend / "package-lock.json").read_bytes()).hexdigest() != manifest["lockSha256"]:
        raise ValueError("package-lock.json changed; review and pin installation scripts before building")
    commands = []
    # 全部校验成功后才运行任何入口；不执行 npm 包的其他生命周期脚本。
    for script in manifest["scripts"]:
        package = frontend / "node_modules" / script["package"]
        for relative, expected in script["files"].items():
            actual = hashlib.sha256((package / relative).read_bytes()).hexdigest()
            if actual != expected:
                raise ValueError(f"Installation script SHA256 mismatch: {script['package']}/{relative}")
        commands.append((package, script["entry"]))
    for package, entry in commands:
        subprocess.run([node, entry], cwd=package, check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("frontend", type=Path)
    parser.add_argument("--node", default="node")
    arguments = parser.parse_args()
    prepare(arguments.frontend.resolve(), arguments.node)
