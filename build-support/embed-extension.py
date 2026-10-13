#!/usr/bin/env python3
"""Embed a source-built extension in the executable Spring Boot assembly."""
import argparse
import os
import tempfile
import zipfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("assembly", type=Path)
parser.add_argument("extension", type=Path)
parser.add_argument("--extra-dir", type=Path)
parser.add_argument("--artifact", default="nexus-open-platform")
args = parser.parse_args()
if args.extension.is_dir():
    args.extension, = [path for path in args.extension.glob(args.artifact + "-*.jar")
                        if not path.name.endswith(("-sources.jar", "-javadoc.jar", "-tests.jar"))]
jar, = args.assembly.glob("bin/nexus-repository-core-*.jar")
libraries = [args.extension]
if args.extra_dir:
    libraries += sorted(args.extra_dir.glob("*.jar"))
entries = ["BOOT-INF/lib/" + library.name for library in libraries]
with tempfile.TemporaryDirectory(dir=jar.parent) as directory:
    work = Path(directory)
    (work / "BOOT-INF/lib").mkdir(parents=True)
    for entry, library in zip(entries, libraries):
        (work / entry).write_bytes(library.read_bytes())
    update = entries.copy()
    with zipfile.ZipFile(jar) as original:
        if "BOOT-INF/classpath.idx" in original.namelist():
            index = original.read("BOOT-INF/classpath.idx").decode()
            for entry in entries:
                line = '- "' + entry + '"\n'
                if line not in index:
                    index += line
            (work / "BOOT-INF/classpath.idx").write_text(index)
            update.append("BOOT-INF/classpath.idx")
    import subprocess
    subprocess.run(["jar", "--update", "--no-compress", "--file", str(jar.resolve()), *update], cwd=work, check=True)
with zipfile.ZipFile(jar) as result:
    for entry in entries:
        assert result.getinfo(entry).compress_type == zipfile.ZIP_STORED
print("Embedded:", ", ".join(library.name for library in libraries))
