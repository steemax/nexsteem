#!/usr/bin/env python3
# SPDX-License-Identifier: EPL-1.0
# Copyright (c) 2026 steemax.
"""Package project notices and corresponding-source information without rebuilding code."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from urllib.parse import urlsplit


SOURCE_URL = "https://github.com/steemax/nexsteem"
SOURCE_ARCHIVE_URL = (
    SOURCE_URL + "/releases/download/3.96.4-01-ext3-k8s10/"
    "nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz"
)
EXCLUDED = {".git", "build", "dist", "target", "node_modules", "__pycache__", ".pytest_cache"}


def checked_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError("Source links must be HTTPS URLs without embedded credentials")
    if any(character.isspace() for character in value):
        raise ValueError("Source links must not contain whitespace")
    return value


def checked_digest(value):
    if value and (len(value) != 64 or any(c not in "0123456789abcdefABCDEF" for c in value)):
        raise ValueError("Source SHA-256 must contain exactly 64 hexadecimal characters")
    return value.lower() if value else None


def source_snapshot(root, excluded_output=None):
    """Fingerprint source inputs; this is deliberately not a source archive checksum."""
    root = Path(root).resolve()
    excluded_output = Path(excluded_output).resolve() if excluded_output else None
    inputs = {}
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root)
        if EXCLUDED.intersection(relative.parts) or path.is_symlink() or not path.is_file():
            continue
        if excluded_output and (path == excluded_output or excluded_output in path.parents):
            continue
        if path.suffix in {".pyc", ".class"}:
            continue
        inputs[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    if not inputs:
        raise ValueError("Source snapshot must contain source inputs")
    digest = hashlib.sha256(json.dumps(inputs, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return digest


def package(project_root, output, *, source_url=SOURCE_URL, archive_url=SOURCE_ARCHIVE_URL,
            snapshot_sha256=None, snapshot_root=None, archive_sha256=None, component="NexSteem"):
    project_root, output = Path(project_root).resolve(), Path(output).resolve()
    source_url, archive_url = checked_url(source_url), checked_url(archive_url)
    snapshot_sha256, archive_sha256 = checked_digest(snapshot_sha256), checked_digest(archive_sha256)
    files = [project_root / name for name in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md")]
    # Validate all inputs before writing so a failed invocation cannot leave a partial notice set.
    for path in files:
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing regular legal input: {path.name}")
    if output == project_root or output in project_root.parents:
        raise ValueError("Output must not replace the source directory")
    if project_root / "licenses" == output or project_root / "licenses" in output.parents:
        raise ValueError("Output must not be inside the license inputs")
    if snapshot_sha256:
        snapshot_scope = "Publisher-provided corresponding source snapshot"
    else:
        snapshot_sha256 = source_snapshot(snapshot_root or project_root, output)
        snapshot_scope = "Build input files; excludes Git metadata and generated build outputs"
    info = {
        "component": component,
        "originator_of_project_additions": "steemax",
        "source_repository_url": source_url,
        "corresponding_source_archive_url": archive_url,
        "source_snapshot_sha256": snapshot_sha256,
        "source_snapshot_scope": snapshot_scope,
        "source_archive_sha256": archive_sha256,
        "distribution_note": (
            "The distributor must provide the corresponding source archive alongside the binary release. "
            "These embedded links do not assert that an unpublished release is already available. "
            "Component licenses and original notices continue to apply."
        ),
    }
    output.mkdir(parents=True, exist_ok=True)
    for path in files:
        shutil.copyfile(path, output / path.name)
    licenses = project_root / "licenses"
    if licenses.is_dir():
        shutil.copytree(licenses, output / "licenses", dirs_exist_ok=True)
    (output / "SOURCE-INFO.json").write_text(json.dumps(info, indent=2) + "\n")
    (output / "SOURCE-INFO.txt").write_text(
        f"{component} — corresponding source information\n\n"
        f"Project additions: steemax.\nSource repository: {source_url}\n"
        f"Corresponding source archive: {archive_url}\n"
        f"Source snapshot SHA-256: {snapshot_sha256}\nSnapshot scope: {snapshot_scope}\n"
        f"Source archive SHA-256: {archive_sha256 or 'See the release SHA256SUMS; not embedded in this build.'}\n\n"
        + info["distribution_note"] + "\n"
    )
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source-url", default=SOURCE_URL)
    parser.add_argument("--source-archive-url", default=SOURCE_ARCHIVE_URL)
    parser.add_argument("--source-snapshot-sha256")
    parser.add_argument("--source-archive-sha256")
    parser.add_argument("--snapshot-root", type=Path)
    parser.add_argument("--component", default="NexSteem")
    args = parser.parse_args()
    package(args.project_root, args.output, source_url=args.source_url,
            archive_url=args.source_archive_url, snapshot_sha256=args.source_snapshot_sha256,
            snapshot_root=args.snapshot_root, archive_sha256=args.source_archive_sha256,
            component=args.component)


if __name__ == "__main__":
    main()
