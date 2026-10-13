# syntax=docker/dockerfile:1.7
ARG POSTGRES_IMAGE=postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3
FROM ${POSTGRES_IMAGE}
ARG PATRONI_VERSION=4.1.5
ARG IMAGE_VERSION=17-patroni4.1.5-r3-amd64
ARG SOURCE_URL=https://github.com/steemax/nexsteem
ARG SOURCE_ARCHIVE_URL=https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz
ARG SOURCE_SNAPSHOT_SHA256=""
ARG SOURCE_ARCHIVE_SHA256=""
LABEL org.opencontainers.image.title="NexSteem PostgreSQL / Patroni integration" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.documentation="${SOURCE_URL}/blob/main/docs/INSTALL.md" \
      org.opencontainers.image.licenses="EPL-1.0 AND PostgreSQL AND MIT"
COPY requirements.lock /opt/nexus-postgres/requirements.lock
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv ca-certificates curl \
 && python3 -m venv /opt/patroni \
 && /opt/patroni/bin/pip install --no-cache-dir -r /opt/nexus-postgres/requirements.lock \
 && /opt/patroni/bin/python -c "import importlib.metadata; assert importlib.metadata.version('patroni') == '${PATRONI_VERSION}'" \
 && /opt/patroni/bin/pip freeze > /opt/patroni/requirements-installed.txt \
 && rm -rf /var/lib/apt/lists/*
COPY entrypoint.py bootstrap.py probe.py etcd-auth.py sync-policy.py /opt/nexus-postgres/
COPY legal/ /opt/nexus-postgres/licenses/project/
RUN --mount=type=bind,source=.,target=/nexsteem-postgres-source,readonly <<'SH'
set -eu
mkdir -p /opt/nexus-postgres/licenses/components
cp /usr/share/doc/postgresql-17/copyright /opt/nexus-postgres/licenses/components/PostgreSQL-copyright.txt
/opt/patroni/bin/python - <<'PY'
import hashlib, json, os
from pathlib import Path
from urllib.parse import urlsplit
output = Path('/opt/nexus-postgres/licenses/project')
source_url, archive_url = os.environ['SOURCE_URL'], os.environ['SOURCE_ARCHIVE_URL']
for value in (source_url, archive_url):
    parsed = urlsplit(value)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.username or parsed.password or any(c.isspace() for c in value):
        raise ValueError('Source links must be HTTPS URLs without credentials or whitespace')
snapshot = os.environ.get('SOURCE_SNAPSHOT_SHA256', '')
archive_sha = os.environ.get('SOURCE_ARCHIVE_SHA256', '')
for value in (snapshot, archive_sha):
    if value and (len(value) != 64 or any(c not in '0123456789abcdefABCDEF' for c in value)):
        raise ValueError('Source SHA-256 must contain 64 hexadecimal characters')
scope = 'Publisher-provided corresponding source snapshot'
if not snapshot:
    root = Path('/nexsteem-postgres-source')
    inputs = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
              for p in sorted(root.rglob('*')) if p.is_file() and not p.is_symlink()
              and not {'__pycache__', '.git', 'build', 'dist', 'target'}.intersection(p.relative_to(root).parts)}
    snapshot = hashlib.sha256(json.dumps(inputs, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    scope = 'PostgreSQL container build context; excludes Git metadata and generated build outputs'
note = ('The distributor must provide the corresponding source archive alongside the binary release. '
        'These links do not assert that an unpublished release is already available. Component licenses continue to apply.')
info = {'component': 'NexSteem PostgreSQL / Patroni integration', 'originator_of_project_additions': 'steemax',
        'source_repository_url': source_url, 'corresponding_source_archive_url': archive_url,
        'source_snapshot_sha256': snapshot.lower(), 'source_snapshot_scope': scope,
        'source_archive_sha256': archive_sha.lower() or None, 'distribution_note': note}
(output / 'SOURCE-INFO.json').write_text(json.dumps(info, indent=2) + '\n')
(output / 'SOURCE-INFO.txt').write_text(
    f"NexSteem PostgreSQL / Patroni — corresponding source information\n\nProject additions: steemax.\n"
    f"Source repository: {source_url}\nCorresponding source archive: {archive_url}\n"
    f"Source snapshot SHA-256: {snapshot.lower()}\nSnapshot scope: {scope}\n"
    f"Source archive SHA-256: {archive_sha.lower() or 'See the release SHA256SUMS; not embedded in this build.'}\n\n{note}\n")
PY
SH
RUN chmod 755 /opt/nexus-postgres/bootstrap.py
ENV PATH=/opt/patroni/bin:$PATH
USER postgres
ENTRYPOINT ["/opt/patroni/bin/python", "/opt/nexus-postgres/entrypoint.py"]
