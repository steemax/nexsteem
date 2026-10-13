#!/usr/bin/env python3
"""Export the complete open source project, including a standalone pinned upstream Git checkout."""
import gzip
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / "upstream.lock.json").read_text())
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=ROOT / 'dist')
parser.add_argument('--name', default=f"nexsteem-{lock['version']}-source")
args = parser.parse_args()
if not args.name or '/' in args.name or '\\' in args.name or args.name in ('.', '..'):
    parser.error('Source archive name must be a single directory name')
subprocess.run([sys.executable, str(ROOT / "scripts/upstream.py"), "verify"], check=True, stdout=subprocess.DEVNULL)
name = args.name
destination = args.output
destination.mkdir(parents=True, exist_ok=True)
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="source-export-", dir=ROOT / "build") as directory:
    tree = Path(directory) / name
    tree.mkdir()
    for entry in ["README.md", "LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md", "CONTRIBUTING.md", "SECURITY.md", "licenses", "branding", ".github", "charts", ".nojekyll", "Makefile", ".dockerignore", ".gitignore", "upstream.lock.json",
                  "extensions", "build-support", "config", "deploy", "docs", "scripts", "tests"]:
        source = ROOT / entry
        if source.is_dir():
            shutil.copytree(source, tree / entry, ignore=shutil.ignore_patterns("target", "__pycache__", ".DS_Store", "node_modules"))
        else:
            shutil.copy2(source, tree / entry)
    upstream = tree / "upstream/nexus-public"
    subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", str(ROOT / "upstream/nexus-public"), str(upstream)], check=True)
    subprocess.run(["git", "remote", "set-url", "origin", lock["repository"]], cwd=upstream, check=True)
    subprocess.run(["git", "checkout", "--quiet", "--detach", lock["commit"]], cwd=upstream, check=True)
    # Verify that extraction produces an immediately buildable project, not a broken submodule pointer.
    subprocess.run([sys.executable, str(tree / "scripts/upstream.py"), "verify"], cwd=tree, check=True, stdout=subprocess.DEVNULL)
    shutil.rmtree(upstream / ".git/logs", ignore_errors=True)
    output = destination / (name + ".tar.gz")
    with output.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed, tarfile.open(fileobj=compressed, mode="w") as archive:
        def normalize(info):
            info.uid = info.gid = 0
            info.uname = info.gname = "root"
            info.mtime = 0
            return info
        archive.add(tree, arcname=name, filter=normalize)
print(output)
