#!/usr/bin/env python3
"""Persist real Nexus raw/Helm/OCI data across automatic cluster failovers.

Run seed before a fault, verify after automatic takeover, and verify again after
catch-up. This runner only targets the private local Kubernetes test context.
Credentials stay in a private local file and never enter reports or command args.
"""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/cluster-application-proof'
CONTEXT = 'kind-nexus-cluster-ha'
NAMESPACE = 'nexus-cluster-test'


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE, timeout=30).strip()


def sha(data):
    return hashlib.sha256(data).hexdigest()


class Proof:
    def __init__(self, args):
        if args.context != CONTEXT or args.namespace != NAMESPACE:
            raise ValueError('Only the isolated local Nexus cluster test context is authorized')
        self.args = args
        self.password = ''
        WORK.mkdir(parents=True, exist_ok=True)
        self.secret_file = WORK / 'admin-password'
        if self.secret_file.exists():
            self.password = self.secret_file.read_text().strip()
            if '\n' in self.password:
                self.password = ''

    def kube(self, *args):
        return run('kubectl', '--kubeconfig', str(self.args.kubeconfig), '--context', CONTEXT,
                   '-n', NAMESPACE, *args)

    def api(self, path, method='GET', data=None, content_type='application/octet-stream', extra=None, registry=False):
        headers = dict(extra or {})
        headers['Authorization'] = 'Basic ' + base64.b64encode(('admin:' + self.password).encode()).decode()
        if isinstance(data, (dict, list)):
            data = json.dumps(data).encode()
            content_type = 'application/json'
        if data is not None:
            headers['Content-Type'] = content_type
        base = self.args.registry_url if registry else self.args.url
        request = urllib.request.Request(base.rstrip('/') + path, method=method, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors='replace').replace(self.password, '[redacted]')
            raise AssertionError(f'{method} {path}: HTTP {error.code}: {detail[:1200]}') from None

    def wait(self, predicate, label, timeout=900):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value:
                    return value
            except (urllib.error.URLError, AssertionError, OSError, subprocess.CalledProcessError):
                pass
            time.sleep(2)
        raise RuntimeError('Timed out: ' + label)

    def discover_password(self):
        if self.password:
            return
        pods = json.loads(self.kube('get', 'pods', '-l',
            'app.kubernetes.io/instance=cluster,app.kubernetes.io/component=nexus', '-o', 'json'))['items']
        for pod in pods:
            try:
                value = self.kube('exec', pod['metadata']['name'], '-c', 'nexus', '--',
                                  'cat', '/var/lib/nexus/admin.password')
                if value:
                    self.password = value
                    fd = os.open(self.secret_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                    with os.fdopen(fd, 'w') as output:
                        output.write(value + '\n')
                    self.secret_file.chmod(0o600)
                    return True
            except subprocess.CalledProcessError:
                pass
        return False

    def repository(self, format_name, name):
        settings = {'name': name, 'online': True, 'storage': {'blobStoreName': 'default',
            'strictContentTypeValidation': format_name != 'raw', 'writePolicy': 'ALLOW'}}
        if format_name == 'docker':
            settings['docker'] = {'httpPort': 8090, 'forceBasicAuth': True, 'v1Enabled': False, 'pathEnabled': True}
        self.api('/service/rest/v1/repositories/' + format_name + '/hosted', 'POST', settings)

    def seed(self):
        if (WORK / 'proof.json').exists():
            raise RuntimeError('Existing test state: verify it or explicitly choose a fresh isolated cluster')
        self.wait(lambda: bool(self.password) or self.discover_password(), 'initial test Core')
        self.wait(lambda: self.api('/service/rest/v1/status/writable')[0] == 200, 'Nexus writer')
        formats = json.loads((ROOT / 'config/required-recipes.json').read_text())['formats']
        required = {fmt + '-' + kind for fmt, kinds in formats.items() for kind in kinds}
        recipes = json.loads(self.api('/service/extdirect', 'POST', {
            'action': 'coreui_Repository', 'method': 'readRecipes', 'data': [], 'type': 'rpc', 'tid': 1})[2])
        if isinstance(recipes, list):
            recipes = recipes[0]
        result = recipes['result']
        rows = result.get('data', []) if isinstance(result, dict) else result
        registered = {row.get('id', row.get('name')) for row in rows}
        assert not required - registered, sorted(required - registered)
        suffix = uuid.uuid4().hex[:12]
        prefix = 'cluster-proof-' + suffix
        repositories = {kind: prefix + '-' + kind for kind in ('raw', 'helm', 'docker')}
        for kind, name in repositories.items():
            self.repository(kind, name)
        entries = []
        payload = os.urandom(2 * 1024 * 1024) + b'\nNexus integrated replication proof\n'
        raw_path = '/repository/' + repositories['raw'] + '/before-failure.bin'
        self.api(raw_path, 'PUT', payload)
        entries.append({'path': raw_path, 'sha256': sha(payload), 'size': len(payload)})
        chart_buffer = io.BytesIO()
        with tarfile.open(fileobj=chart_buffer, mode='w:gz') as archive:
            for name, data in {
                'proof/Chart.yaml': b'apiVersion: v2\nname: proof\nversion: 1.0.0\n',
                'proof/values.yaml': b'proof: integrated-replication\n',
                'proof/templates/configmap.yaml': b'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: proof\ndata:\n  value: {{ .Values.proof | quote }}\n',
            }.items():
                entry = tarfile.TarInfo(name)
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        chart = chart_buffer.getvalue()
        chart_path = '/repository/' + repositories['helm'] + '/proof-1.0.0.tgz'
        self.api(chart_path, 'PUT', chart, 'application/gzip')
        entries.append({'path': chart_path, 'sha256': sha(chart), 'size': len(chart)})
        self.wait(lambda: b'proof' in self.api('/repository/' + repositories['helm'] + '/index.yaml')[2], 'Helm generated index')
        # An OCI manifest and both immutable payload descriptors use the Registry protocol.
        docker_base = '/v2/proof/image'
        layer_buffer = io.BytesIO()
        layer_payload = b'cluster-registry-layer\n' * 10000
        with tarfile.open(fileobj=layer_buffer, mode='w') as archive:
            entry = tarfile.TarInfo('proof.txt')
            entry.size = len(layer_payload)
            archive.addfile(entry, io.BytesIO(layer_payload))
        layer = layer_buffer.getvalue()
        config = json.dumps({'architecture': 'amd64', 'os': 'linux', 'rootfs': {
            'type': 'layers', 'diff_ids': ['sha256:' + sha(layer)]}}, separators=(',', ':')).encode()
        descriptors = []
        for content, media in [(config,
            'application/vnd.oci.image.config.v1+json'), (layer,
            'application/vnd.oci.image.layer.v1.tar')]:
            _, headers, _ = self.api(docker_base + '/blobs/uploads/', 'POST', b'', registry=True)
            location = headers['Location']
            parsed = urllib.parse.urlsplit(location)
            path = parsed.path + (('?' + parsed.query) if parsed.query else '')
            digest = 'sha256:' + sha(content)
            path += ('&' if '?' in path else '?') + 'digest=' + digest
            self.api(path, 'PUT', content, registry=True)
            descriptors.append({'mediaType': media, 'digest': digest, 'size': len(content)})
            entries.append({'path': docker_base + '/blobs/' + digest, 'sha256': sha(content), 'size': len(content), 'registry': True})
        manifest = json.dumps({'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.manifest.v1+json',
            'config': descriptors[0], 'layers': descriptors[1:]}, separators=(',', ':')).encode()
        manifest_path = docker_base + '/manifests/proof'
        self.api(manifest_path, 'PUT', manifest, 'application/vnd.oci.image.manifest.v1+json', registry=True)
        # The registry may normalize JSON; store the bytes it actually serves.
        stored = self.api(manifest_path, extra={'Accept': 'application/vnd.oci.image.manifest.v1+json'}, registry=True)[2]
        entries.append({'path': manifest_path, 'sha256': sha(stored), 'size': len(stored), 'registry': True})
        # A Registry PATCH acknowledgement must survive takeover too, not only completed manifests.
        first_part = b'confirmed-before-takeover\n' * 1000
        final_part = b'continued-after-takeover\n' * 1000
        _, headers, _ = self.api(docker_base + '/blobs/uploads/', 'POST', b'', registry=True)
        location = urllib.parse.urlsplit(headers['Location'])
        upload_path = location.path + (('?' + location.query) if location.query else '')
        self.api(upload_path, 'PATCH', first_part, registry=True)
        pending_upload = {'path': upload_path, 'tail_base64': base64.b64encode(final_part).decode(),
            'blob_path': docker_base + '/blobs/sha256:' + sha(first_part + final_part),
            'sha256': sha(first_part + final_part), 'size': len(first_part + final_part)}
        state = {'repositories': repositories, 'entries': entries, 'registered_recipes': len(required),
            'created_at': time.time(), 'pending_upload': pending_upload, 'checks': []}
        (WORK / 'proof.json').write_text(json.dumps(state, indent=2) + '\n')
        self.verify('before-failure')

    def verify(self, label):
        self.discover_password()
        state = json.loads((WORK / 'proof.json').read_text())
        self.wait(lambda: self.api('/service/rest/v1/status/writable')[0] == 200, 'automatic Nexus takeover')
        for kind, name in state['repositories'].items():
            settings = json.loads(self.api('/service/rest/v1/repositories/' + name)[2])
            assert settings['name'] == name
        for entry in state['entries']:
            value = self.api(entry['path'], extra={'Accept': 'application/vnd.oci.image.manifest.v1+json'}, registry=entry.get('registry', False))[2]
            assert sha(value) == entry['sha256'] and len(value) == entry['size'], entry['path']
        if label != 'before-failure' and state.get('pending_upload'):
            pending = state['pending_upload']
            path = pending['path'] + ('&' if '?' in pending['path'] else '?') + 'digest=sha256:' + pending['sha256']
            # Registry uploads are append-only. A failed finalization may already
            # have durably stored its final chunk. Query the confirmed offset rather
            # than resending that chunk and corrupting the expected digest.
            tail = base64.b64decode(pending['tail_base64'])
            for attempt in range(3):
                _, progress, _ = self.api(pending['path'], registry=True)
                offset = int(progress['Range'].split('-')[-1]) + 1
                first_size = pending['size'] - len(tail)
                assert first_size <= offset <= pending['size'], ('Unexpected Registry upload offset', offset, pending['size'])
                remainder = tail[offset - first_size:]
                extra = {'Content-Range': str(offset) + '-' + str(pending['size'] - 1)} if remainder else {}
                try:
                    self.api(path, 'PUT', remainder, registry=True, extra=extra)
                    break
                except AssertionError as error:
                    if attempt == 2 or not any('HTTP ' + str(code) + ':' in str(error) for code in (500, 502, 503, 504)):
                        raise
                    print(json.dumps({'registry_finalization_retry': attempt + 1, 'confirmed_offset': offset}), flush=True)
                    time.sleep(1)
            value = self.api(pending['blob_path'], registry=True)[2]
            assert sha(value) == pending['sha256'] and len(value) == pending['size']
            state['entries'].append({'path': pending['blob_path'], 'sha256': pending['sha256'], 'size': pending['size'], 'registry': True})
            state['docker_upload_resumed_after_takeover'] = True
            state.pop('pending_upload')
        payload = os.urandom(256 * 1024) + label.encode()
        path = '/repository/' + state['repositories']['raw'] + '/continuity-' + uuid.uuid4().hex + '.bin'
        status = self.api(path, 'PUT', payload)[0]
        assert status in (200, 201, 204)
        assert sha(self.api(path)[2]) == sha(payload)
        state['entries'].append({'path': path, 'sha256': sha(payload), 'size': len(payload)})
        state['checks'].append({'label': label, 'time': time.time(), 'verified_artifacts': len(state['entries']),
            'configuration_preserved': True, 'successful_new_write': True})
        (WORK / 'proof.json').write_text(json.dumps(state, indent=2) + '\n')
        print(json.dumps(state['checks'][-1], indent=2))

    def prepare_pending_upload(self, label):
        state = json.loads((WORK / 'proof.json').read_text())
        if state.get('pending_upload'):
            raise RuntimeError('An existing Registry upload still needs verification')
        first = (label + ': confirmed-before-failure\n').encode() * 1000
        tail = (label + ': continued-after-failure\n').encode() * 1000
        base = '/v2/proof/image'
        _, headers, _ = self.api(base + '/blobs/uploads/', 'POST', b'', registry=True)
        location = urllib.parse.urlsplit(headers['Location'])
        path = location.path + (('?' + location.query) if location.query else '')
        status, headers, _ = self.api(path, 'PATCH', first, registry=True)
        assert status == 202
        state['pending_upload'] = {'path': path, 'tail_base64': base64.b64encode(tail).decode(),
            'blob_path': base + '/blobs/sha256:' + sha(first + tail),
            'sha256': sha(first + tail), 'size': len(first + tail), 'prepared_for': label,
            'degraded_at_patch': headers.get('X-Nexus-Cluster-Degraded', 'false') == 'true'}
        (WORK / 'proof.json').write_text(json.dumps(state, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['seed', 'verify'])
    parser.add_argument('--label', default='after-failure')
    parser.add_argument('--url', default='http://127.0.0.1:18150')
    parser.add_argument('--registry-url', default='http://127.0.0.1:18151')
    parser.add_argument('--kubeconfig', type=Path, default=ROOT / 'build/nexus-cluster-test.kubeconfig')
    parser.add_argument('--context', default=CONTEXT)
    parser.add_argument('--namespace', default=NAMESPACE)
    args = parser.parse_args()
    proof = Proof(args)
    if args.phase == 'seed':
        proof.seed()
    else:
        proof.verify(args.label)


if __name__ == '__main__':
    main()
