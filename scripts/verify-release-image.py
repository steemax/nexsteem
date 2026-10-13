#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-1.0
# Copyright (c) 2026 steemax.
"""Inspect real release images for branding, notices and provenance without starting services."""
import argparse
import email.parser
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tarfile
import tempfile
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE_URL = "https://github.com/steemax/nexsteem"
SHA256 = re.compile(r"[0-9a-f]{64}")


def docker(*args, optional=False):
    result = subprocess.run(["docker", *args], capture_output=True, text=True)
    if result.returncode and not optional:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()}")
    return result


def constant_string_field(data, field_name):
    """Read a Java ConstantValue field from the compiled class, without executing Java."""
    stream = io.BytesIO(data)
    def number(size):
        raw = stream.read(size)
        if len(raw) != size:
            raise ValueError("Truncated Java class")
        return int.from_bytes(raw, "big")
    if number(4) != 0xCAFEBABE:
        raise ValueError("Invalid Java class")
    stream.read(4)
    count, constants, index = number(2), {}, 1
    while index < count:
        tag = number(1)
        if tag == 1:
            constants[index] = (tag, stream.read(number(2)).decode("utf-8", "replace"))
        elif tag in (7, 8, 16, 19, 20):
            constants[index] = (tag, number(2))
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            stream.read(4)
        elif tag in (5, 6):
            stream.read(8)
            index += 1
        elif tag == 15:
            stream.read(3)
        else:
            raise ValueError(f"Unsupported Java constant-pool tag {tag}")
        index += 1
    stream.read(6)
    stream.read(2 * number(2))
    for _ in range(number(2)):
        number(2)
        name = constants[number(2)][1]
        number(2)
        for _ in range(number(2)):
            attribute = constants[number(2)][1]
            payload = stream.read(number(4))
            if name == field_name and attribute == "ConstantValue":
                constant = constants[struct.unpack(">H", payload)[0]]
                if constant[0] != 8:
                    raise ValueError("Expected a Java String ConstantValue")
                return constants[constant[1]][1]
    raise ValueError(f"Compiled field not found: {field_name}")


class Inspection:
    def __init__(self, image, component):
        self.report = {"image": image, "component": component, "checks": [],
                       "scope": "Image configuration and actual filesystem artifacts; no image entrypoint or service is started"}

    def check(self, name, passed, detail=None):
        self.report["checks"].append({"name": name, "status": "PASS" if passed else "FAIL", "detail": detail})

    def skip(self, name, reason):
        self.report["checks"].append({"name": name, "status": "SKIP", "detail": reason})

    def copy(self, container, remote, local):
        docker("cp", f"{container}:{remote}", str(local))
        return Path(local)

    def legal(self, container, remote, destination, expected):
        directory = self.copy(container, remote, destination)
        for name in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md", "SOURCE-INFO.txt", "SOURCE-INFO.json"):
            path = directory / name
            self.check(f"{remote}/{name}", path.is_file() and path.stat().st_size > 0)
        for name in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md"):
            self.check(f"legal artifact matches checkout: {remote}/{name}",
                       (directory / name).read_bytes() == (expected / name).read_bytes())
        info = json.loads((directory / "SOURCE-INFO.json").read_text())
        source, archive = info.get("source_repository_url"), info.get("corresponding_source_archive_url", "")
        self.check(f"{remote}: corresponding source location",
                   source == SOURCE_URL and archive.startswith(SOURCE_URL + "/releases/download/")
                   and archive.endswith("-source.tar.gz"), {"repository": source, "archive": archive})
        self.check(f"{remote}: source snapshot",
                   bool(SHA256.fullmatch(info.get("source_snapshot_sha256", "")))
                   and info.get("originator_of_project_additions") == "steemax")
        archive_sha = info.get("source_archive_sha256")
        self.check(f"{remote}: archive checksum format",
                   archive_sha is None or bool(SHA256.fullmatch(archive_sha)), archive_sha)
        text = (directory / "SOURCE-INFO.txt").read_text()
        self.check(f"{remote}: human-readable source instructions", source in text and archive in text)
        return info

    def core(self, container, temporary, component, version):
        info = self.legal(container, "/opt/nexus/licenses/project", temporary / "project", ROOT)
        if component == "cluster":
            cluster_info = self.legal(container, "/opt/nexus-cluster/licenses/project", temporary / "cluster-project", ROOT)
            self.check("Core and cluster corresponding source agree",
                       info["corresponding_source_archive_url"] == cluster_info["corresponding_source_archive_url"]
                       and info["source_repository_url"] == cluster_info["source_repository_url"])
            if info.get("source_snapshot_scope") == cluster_info.get("source_snapshot_scope") == "Publisher-provided corresponding source snapshot":
                self.check("Publisher-provided Core and cluster source snapshots agree",
                           info["source_snapshot_sha256"] == cluster_info["source_snapshot_sha256"])
        notice = self.copy(container, "/opt/nexus/NOTICE.txt", temporary / "upstream-NOTICE.txt")
        self.check("Original Sonatype NOTICE retained",
                   notice.read_bytes() == (ROOT / "licenses/upstream-NOTICE.txt").read_bytes())
        core_license = self.copy(container, "/opt/nexus/licenses/NEXUS-CORE-LICENSE.txt", temporary / "core-LICENSE.txt")
        self.check("Core EPL-1.0 license retained", core_license.read_text().strip() == (ROOT / "LICENSE.txt").read_text().strip())
        provenance = json.loads(self.copy(container, "/opt/nexus/EXTENSIONS-PROVENANCE.json", temporary / "extensions.json").read_text())
        core_provenance = json.loads(self.copy(container, "/opt/nexus/BUILD-PROVENANCE.json", temporary / "core.json").read_text())
        self.check("Core version and upstream origin", core_provenance.get("version") == version
                   and core_provenance.get("repository") == "https://github.com/sonatype/nexus-public.git")
        application = self.copy(container, f"/opt/nexus/bin/nexus-repository-core-{version}.jar", temporary / "application.jar")
        with application.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        cache_key = self.copy(container, "/opt/nexus/webresources-cache-key", temporary / "cache-key").read_text().strip()
        self.check("Actual Core JAR SHA-256 matches provenance and UI cache key",
                   digest == provenance.get("application_sha256") == cache_key, digest)
        self.report["application_sha256"] = digest
        with zipfile.ZipFile(application) as archive:
            rapture_names = [n for n in archive.namelist() if n.startswith("BOOT-INF/lib/nexus-rapture-") and n.endswith(".jar")]
            coreui_names = [n for n in archive.namelist() if n.startswith("BOOT-INF/lib/nexus-coreui-plugin-") and n.endswith(".jar")]
            if len(rapture_names) != 1 or len(coreui_names) != 1:
                raise ValueError("Expected exactly one actual Rapture and Core UI JAR")
            with zipfile.ZipFile(io.BytesIO(archive.read(rapture_names[0]))) as rapture:
                title = constant_string_field(rapture.read("org/sonatype/nexus/rapture/settings/RaptureSettings.class"), "DEFAULT_TITLE")
                self.check("Compiled Core DEFAULT_TITLE", title == "NexSteem", title)
                template = rapture.read("org/sonatype/nexus/rapture/internal/index.vm")
                self.check("Actual browser title and SVG favicon consumer",
                           b"<title>NexSteem</title>" in template and b'type="image/svg+xml"' in template)
                for name in ("static/rapture/resources/favicon.svg", "static/rapture/resources/favicon-white.svg",
                             "static/rapture/resources/images/sonatype-repository-logo-loading.svg",
                             "static/rapture/resources/icons/x16/sonatype-repository-icon.svg",
                             "static/rapture/resources/icons/x100/sonatype-repository-icon.svg"):
                    self.check("Actual branded artwork: " + name, b"NexSteem" in rapture.read(name))
                strings = rapture.read("static/rapture/NX/app/PluginStrings.js")
                self.check("Classic UI and About branding", b"About NexSteem" in strings and b"NexSteem" in rapture.read("static/rapture/NX/view/AboutWindow.js"))
                vendor_root = ROOT / "upstream/nexus-public/public/common/components/nexus-rapture/src/main/resources/static/rapture"
                for name in ("baseapp-prod.js", "baseapp-debug.js"):
                    original = vendor_root / name
                    if original.is_file():
                        got = hashlib.sha256(rapture.read("static/rapture/" + name)).hexdigest()
                        self.check("Pinned official Ext JS vendor bundle unchanged: " + name,
                                   got == hashlib.sha256(original.read_bytes()).hexdigest(), got)
                    else:
                        self.skip("Official vendor comparison: " + name, "Initialize the pinned upstream submodule to compare this artifact")
            with zipfile.ZipFile(io.BytesIO(archive.read(coreui_names[0]))) as coreui:
                for name in ("static/fonts/logo-core-edition-header.svg", "static/fonts/logo-core-edition-header-dark.svg"):
                    svg = coreui.read(name)
                    self.check("Actual Core header SVG: " + name, b"NexSteem" in svg and b"ARTIFACT REPOSITORY" in svg)
                for name in ("static/nexus-coreui-bundle.js", "static/nexus-coreui-bundle.debug.js"):
                    self.check("Compiled React Core branding: " + name, b"NexSteem" in coreui.read(name))

    def postgres(self, container, temporary):
        legal = ROOT / "deploy/cluster/postgres/legal"
        self.legal(container, "/opt/nexus-postgres/licenses/project", temporary / "project", legal)
        duplicate = self.copy(container, "/opt/nexus-postgres/licenses/components/PostgreSQL-copyright.txt", temporary / "postgres-copyright-copy")
        original = self.copy(container, "/usr/share/doc/postgresql-17/copyright", temporary / "postgres-copyright-original")
        self.check("Original PostgreSQL packaged copyright retained", duplicate.read_bytes() == original.read_bytes())
        self.check("PostgreSQL component license identifies upstream",
                   b"PostgreSQL" in duplicate.read_bytes() and b"Permission to use, copy, modify" in duplicate.read_bytes())
        project_mit = temporary / "project/licenses/Patroni-MIT.txt"
        self.check("Patroni MIT license packaged", project_mit.read_bytes() == (legal / "licenses/Patroni-MIT.txt").read_bytes())
        installed = self.copy(container, "/opt/patroni/requirements-installed.txt", temporary / "requirements-installed.txt").read_text()
        locked = self.copy(container, "/opt/nexus-postgres/requirements.lock", temporary / "requirements.lock").read_text()
        expected_match = re.search(r"^patroni==([^\s]+)$", locked, re.M | re.I)
        installed_match = re.search(r"^patroni==([^\s]+)$", installed, re.M | re.I)
        self.check("Installed Patroni version matches lock", bool(expected_match and installed_match)
                   and expected_match[1] == installed_match[1], installed_match[1] if installed_match else None)
        # Stream only metadata from the actual virtualenv; do not start Python or PostgreSQL in the image.
        process = subprocess.Popen(["docker", "cp", f"{container}:/opt/patroni/lib", "-"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        metadata, mit = None, None
        try:
            with tarfile.open(fileobj=process.stdout, mode="r|") as stream:
                for member in stream:
                    if member.isfile() and re.search(r"/patroni-[^/]+\.dist-info/", member.name):
                        if member.name.endswith("/METADATA"):
                            metadata = email.parser.Parser().parsestr(stream.extractfile(member).read().decode())
                        elif member.name.endswith("/licenses/LICENSE") or member.name.endswith("/LICENSE"):
                            mit = stream.extractfile(member).read()
        finally:
            process.stdout.close()
            stderr = process.stderr.read().decode()
            process.stderr.close()
            if process.wait():
                raise RuntimeError("Cannot inspect actual Patroni package metadata: " + stderr.strip())
        self.check("Actual Patroni wheel metadata version", metadata is not None and expected_match is not None
                   and metadata.get("Name", "").lower() == "patroni" and metadata.get("Version") == expected_match[1],
                   {key: metadata.get(key) for key in ("Name", "Version", "License")} if metadata is not None else None)
        self.check("Original installed Patroni MIT license retained", mit is not None
                   and mit.strip() == project_mit.read_bytes().strip())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True, help="Existing local image name or immutable image ID; no image is pulled")
    parser.add_argument("--component", required=True, choices=("standalone", "cluster", "postgres"))
    parser.add_argument("--report", type=Path, help="Write the artifact inspection report as JSON")
    args = parser.parse_args()
    inspection = Inspection(args.image, args.component)
    container = "nexsteem-release-verify-" + uuid.uuid4().hex
    created = False
    try:
        config, = json.loads(docker("image", "inspect", args.image).stdout)
        inspection.report["image_id"] = config["Id"]
        labels = config.get("Config", {}).get("Labels") or {}
        user = config.get("Config", {}).get("User", "")
        inspection.check("Linux amd64 image", config.get("Architecture") == "amd64" and config.get("Os") == "linux")
        inspection.check("NexSteem OCI title and source", "NexSteem" in labels.get("org.opencontainers.image.title", "")
                         and labels.get("org.opencontainers.image.source") == SOURCE_URL)
        inspection.check("OCI documentation URL", labels.get("org.opencontainers.image.documentation", "").startswith(SOURCE_URL + "/"))
        inspection.check("Non-root runtime user", user.split(":", 1)[0] not in ("", "0", "root"), user)
        version = labels.get("org.opencontainers.image.version", "")
        inspection.check("OCI release version present", bool(version), version)
        # Attempt cleanup even if the CLI is interrupted after the daemon has created the container.
        created = True
        docker("create", "--name", container, "--network", "none", "--entrypoint", "/bin/true", config["Id"])
        with tempfile.TemporaryDirectory(prefix="nexsteem-release-inspection-") as temporary:
            if args.component == "postgres":
                inspection.postgres(container, Path(temporary))
            elif re.fullmatch(r"[0-9][0-9.-]*", version):
                inspection.core(container, Path(temporary), args.component, version)
            else:
                inspection.check("Valid Core release version", False, version)
    except Exception as error:
        inspection.check("Inspection completed", False, str(error))
    finally:
        if created:
            result = docker("rm", "-v", container, optional=True)
            inspection.check("Own temporary container and anonymous volumes removed", result.returncode == 0,
                             result.stderr.strip() or None)
    inspection.report["passed"] = all(c["status"] != "FAIL" for c in inspection.report["checks"])
    text = json.dumps(inspection.report, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text)
    print(text, end="")
    return 0 if inspection.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
