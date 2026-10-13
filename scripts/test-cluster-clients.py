#!/usr/bin/env python3
"""Real Docker and Helm clients against the isolated Kubernetes acceptance fixture.

Seed once, then verify after upgrades and automatic failovers. Test repositories
and image tags are isolated; credentials are read from the private fixture file.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cluster_application_proof', ROOT / 'scripts/test-cluster-application.py')
application = importlib.util.module_from_spec(spec)
spec.loader.exec_module(application)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['seed', 'verify'])
    parser.add_argument('--label', default='after-failure')
    parser.add_argument('--push-new', action='store_true', help='Build and push a fresh owned image during verification')
    parser.add_argument('--url', default='http://127.0.0.1:18150')
    parser.add_argument('--registry-url', default='http://127.0.0.1:18151')
    parser.add_argument('--kubeconfig', type=Path, default=ROOT / 'build/nexus-cluster-test.kubeconfig')
    parser.add_argument('--context', default=application.CONTEXT)
    parser.add_argument('--namespace', default=application.NAMESPACE)
    args = parser.parse_args()
    proof = application.Proof(args)
    proof.discover_password()
    state_path = application.WORK / 'clients.json'
    state = json.loads(state_path.read_text()) if state_path.exists() else None
    if args.phase == 'seed' and state is not None:
        raise RuntimeError('Client fixture already exists; use verify')
    if args.phase == 'verify' and state is None:
        raise RuntimeError('Seed client fixture first')
    base = json.loads((application.WORK / 'proof.json').read_text())
    registry = urllib.parse.urlsplit(args.registry_url).netloc
    with tempfile.TemporaryDirectory(prefix='nexus-cluster-clients-') as directory:
        root = Path(directory)
        (root / 'docker-config').mkdir(mode=0o700)
        docker = ['docker', '--config', str(root / 'docker-config')]
        def run(command, input=None, env=None):
            result = subprocess.run(command, input=input, text=True, env=env,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
            if result.returncode:
                output = result.stdout.replace(proof.password, '[redacted]')
                raise AssertionError(f'{command[0]}: {result.returncode}: {output[-4000:]}')
            return result.stdout
        run([*docker, 'login', registry, '--username', 'admin', '--password-stdin'], proof.password + '\n')
        if args.phase == 'seed' or args.push_new:
            previous_checks = state.get('checks', []) if state else []
            previous_images = (state.get('previous_images', []) + [state['image']]) if state else []
            image = registry + '/cluster-client/' + uuid.uuid4().hex[:12] + ':native'
            (root / 'payload.txt').write_text('Nexus cluster real Docker client ' + uuid.uuid4().hex + '\n')
            (root / 'Dockerfile').write_text('FROM scratch\nCOPY payload.txt /payload.txt\n')
            run([*docker, 'build', '--platform', 'linux/amd64', '-t', image, str(root)])
            run([*docker, 'push', image])
            chart = proof.api('/repository/' + base['repositories']['helm'] + '/proof-1.0.0.tgz')[2]
            state = {'image': image, 'image_id': json.loads(run([*docker, 'image', 'inspect', image]))[0]['Id'],
                'helm_repository': base['repositories']['helm'], 'helm_sha256': hashlib.sha256(chart).hexdigest(),
                'checks': previous_checks, 'previous_images': previous_images}
        run([*docker, 'pull', '--platform', 'linux/amd64', state['image']])
        image = json.loads(run([*docker, 'image', 'inspect', state['image']]))[0]
        assert image['Id'] == state['image_id'] and image['Architecture'] == 'amd64'
        # Write the CLI's credentials to a private config rather than command arguments.
        helm_config = root / 'helm-config'
        helm_config.mkdir(mode=0o700)
        repository_config = helm_config / 'repositories.yaml'
        repository_config.write_text(json.dumps({'apiVersion': 'v1', 'repositories': [{
            'name': 'cluster', 'url': args.url + '/repository/' + state['helm_repository'] + '/',
            'username': 'admin', 'password': proof.password}]}))
        repository_config.chmod(0o600)
        env = dict(os.environ, HELM_CONFIG_HOME=str(helm_config), HELM_CACHE_HOME=str(root / 'helm-cache'),
            HELM_DATA_HOME=str(root / 'helm-data'))
        run(['helm', 'repo', 'update', 'cluster'], env=env)
        assert 'proof' in run(['helm', 'search', 'repo', 'cluster'], env=env)
        run(['helm', 'pull', 'cluster/proof', '--version', '1.0.0', '--destination', str(root)], env=env)
        chart = root / 'proof-1.0.0.tgz'
        assert hashlib.sha256(chart.read_bytes()).hexdigest() == state['helm_sha256']
        assert 'integrated-replication' in run(['helm', 'template', 'acceptance', str(chart)], env=env)
        state['checks'].append({'label': 'before-failure' if args.phase == 'seed' else args.label,
            'time': time.time(), 'docker_login_push_pull': True if args.phase == 'seed' or args.push_new else 'login_pull',
            'docker_config_digest_preserved': True, 'helm_update_search_pull_template_sha256': True})
        state_path.write_text(json.dumps(state, indent=2) + '\n')
        print(json.dumps(state['checks'][-1], indent=2))


if __name__ == '__main__':
    main()
