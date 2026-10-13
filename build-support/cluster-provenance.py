#!/usr/bin/env python3
"""Record the optional cluster source snapshot independently of immutable Core."""
import hashlib
import json
from pathlib import Path
import sys

source, output = map(Path, sys.argv[1:])
paths = []
for module in ('cluster-api', 'cluster-control', 'cluster-storage', 'cluster-web'):
    for path in (source / 'extensions' / module).rglob('*'):
        if path.is_file() and not {'target', '__pycache__'}.intersection(path.parts):
            paths.append(path)
paths += sorted(source.glob('BUILD.*'))
inputs = {str(path.relative_to(source)): hashlib.sha256(path.read_bytes()).hexdigest()
          for path in sorted(paths)}
snapshot = hashlib.sha256(json.dumps(inputs, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
output.write_text(json.dumps({'scope': 'optional Kubernetes modules', 'source_snapshot_sha256': snapshot,
    'inputs': inputs}, indent=2) + '\n')
