#!/usr/bin/env python3
"""Record the exact assembled runtime and invalidate generated UI API caches on updates."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

assembly = Path(sys.argv[1])
application, = assembly.glob("bin/nexus-repository-core-*.jar")
digest = hashlib.file_digest(application.open("rb"), "sha256").hexdigest()
modules = []
with zipfile.ZipFile(application) as archive:
    for entry in sorted(archive.namelist()):
        if entry.startswith("BOOT-INF/lib/nexus-") and entry.endswith(".jar"):
            with archive.open(entry) as content:
                modules.append({"artifact": entry.removeprefix("BOOT-INF/lib/"),
                                "sha256": hashlib.file_digest(content, "sha256").hexdigest()})
(assembly / "EXTENSIONS-PROVENANCE.json").write_text(json.dumps({
    "application_sha256": digest, "modules": modules}, indent=2) + "\n")
(assembly / "webresources-cache-key").write_text(digest + "\n")
