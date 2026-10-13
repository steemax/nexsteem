#!/usr/bin/env python3
"""Check project files for runtime state and common credential signatures.

The official upstream submodule is verified separately by scripts/upstream.py.
Known local fixture credentials are intentionally documented in README.md.
This signature check is not a complete secret or dependency-license audit.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP = {'.git', 'build', 'dist', 'upstream', 'target', '__pycache__', 'node_modules'}
SUFFIXES = {'.kubeconfig', '.p12', '.pfx', '.jks', '.hprof', '.jfr', '.deb'}
PATTERNS = {
    'private key': re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{60,}'),
    'AWS access key': re.compile(rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'GitHub token': re.compile(rb'\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{40,})\b'),
    'API secret token': re.compile(rb'\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{40,}\b'),
    'personal macOS path': re.compile(rb'/Users/[A-Za-z0-9._-]+/[^\s"\x27<>]+|/var/folders/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/'),
}


def main():
    problems = []
    checked = 0
    for path in sorted(ROOT.rglob('*')):
        relative = path.relative_to(ROOT)
        if any(part in SKIP for part in relative.parts):
            continue
        if path.is_symlink():
            try:
                path.resolve(strict=True).relative_to(ROOT)
            except (ValueError, OSError):
                problems.append((str(relative), 'external or broken symlink'))
            continue
        if not path.is_file():
            continue
        if (path.name == 'admin.password' or path.name == '.env' or path.name.startswith('.env.')
                and not path.name.endswith('.example') or path.suffix in SUFFIXES):
            problems.append((str(relative), 'runtime/credential file'))
            continue
        data = path.read_bytes()
        checked += 1
        for name, pattern in PATTERNS.items():
            if pattern.search(data):
                # Never print the matching value.
                problems.append((str(relative), name))
    if problems:
        for path, name in problems:
            print(f'{path}: {name}', file=sys.stderr)
        return 1
    print(f'PASS: {checked} project files checked; no forbidden files or matching credential signatures')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
