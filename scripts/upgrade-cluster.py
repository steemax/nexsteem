#!/usr/bin/env python3
"""Update passive Nexus members, commit the SQL release gate, then replace the writer.

The target kubeconfig/context/namespace/release are always explicit. PVCs, blobs,
secrets and database contents are retained. This script upgrades Nexus only;
PostgreSQL/etcd image changes use their separate ordered infrastructure workflow.
"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]


def run(*args, timeout=120):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()


class Upgrade:
    def __init__(self, args):
        self.args = args
        self.kube_args = ['kubectl', '--kubeconfig', str(args.kubeconfig), '--context', args.context,
            '-n', args.namespace]
        self.selector = 'app.kubernetes.io/instance=' + args.release + ',app.kubernetes.io/component=nexus'
        self.report = {'target': {'context': args.context, 'namespace': args.namespace, 'release': args.release},
            'new_image': args.image, 'new_application_sha256': args.application_sha256, 'version': args.version,
            'events': [], 'data_copied_manually': False, 'volumes_deleted': False}

    def kube(self, *args, timeout=120):
        return run(*self.kube_args, *args, timeout=timeout)

    def pods(self):
        items = json.loads(self.kube('get', 'pods', '-l', self.selector, '-o', 'json'))['items']
        if len(items) < 2:
            raise RuntimeError('At least two Nexus members are required for a coordinated upgrade')
        return {pod['metadata']['name']: pod for pod in items}

    def status(self, pod):
        return json.loads(self.kube('exec', pod, '-c', 'nexus', '--', '/bin/sh', '-ec',
            'exec curl --fail --silent --max-time 12 "http://${NX_CLUSTER_PUBLIC_BIND_ADDRESS:-127.0.0.1}:8081/_cluster/status"',
            timeout=25))

    def wait(self, predicate, label, timeout=900):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value:
                    return value
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, KeyError, ValueError):
                pass
            time.sleep(2)
        raise RuntimeError('Timed out: ' + label)

    def event(self, action, **values):
        event = {'action': action, 'at': time.time(), **values}
        self.report['events'].append(event)
        print(json.dumps(event), flush=True)
        if self.args.report:
            self.args.report.parent.mkdir(parents=True, exist_ok=True)
            self.args.report.write_text(json.dumps(self.report, indent=2) + '\n')

    def helm_upgrade(self, values):
        # Helm's sparse --set on an array can replace earlier entries with nil.
        # Pass the complete current values, preserving topology and member fields.
        with tempfile.TemporaryDirectory(prefix='nexus-cluster-upgrade-') as directory:
            path = Path(directory) / 'values.json'
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'w') as output:
                json.dump(values, output)
            return run('helm', 'upgrade', self.args.release, str(self.args.chart),
                '--kubeconfig', str(self.args.kubeconfig), '--kube-context', self.args.context,
                '--namespace', self.args.namespace, '--values', str(path), '--timeout', '15m', timeout=950)

    def replace(self, pod, old_uid):
        self.kube('delete', 'pod', pod, '--wait=false')
        def caught_up_new_member():
            candidate = self.pods().get(pod)
            if not candidate or candidate['metadata']['uid'] == old_uid:
                return False
            state = self.status(pod)
            replica = state.get('replica', {})
            return (state.get('applicationSHA256') == self.args.application_sha256
                and state.get('applicationVersion') == self.args.version
                and state.get('catchup') is True and replica.get('memberReady') is True)
        self.wait(caught_up_new_member, 'updated and caught-up member ' + pod)
        self.event('member_updated', pod=pod)

    def execute(self):
        pods = self.pods()
        states = {name: self.status(name) for name in pods}
        writers = [name for name, state in states.items() if state.get('writerReady') is True]
        if len(writers) != 1:
            raise RuntimeError('Expected exactly one ready writer before upgrade')
        writer = writers[0]
        old_hash = states[writer].get('applicationSHA256')
        if not old_hash or not re.fullmatch('[a-f0-9]{64}', old_hash):
            raise RuntimeError('Current writer does not expose immutable application provenance')
        if old_hash == self.args.application_sha256:
            raise RuntimeError('Target application is already active; inspect or finish a previous upgrade')
        if any(state.get('catchup') is not True for state in states.values()):
            raise RuntimeError('Every existing member must finish blob catch-up before upgrade')
        self.report['old_application_sha256'] = old_hash
        self.event('preflight_complete', writer=writer, members=sorted(pods))
        writer_index = pods[writer]['metadata']['labels']['nexus.cluster/member']
        old_image = next(container['image'] for container in pods[writer]['spec']['containers']
                         if container['name'] == 'nexus')
        values = json.loads(run('helm', 'get', 'values', self.args.release, '--all', '-o', 'json',
            '--kubeconfig', str(self.args.kubeconfig), '--kube-context', self.args.context,
            '--namespace', self.args.namespace))
        values = copy.deepcopy(values)
        values['nexus']['image'] = self.args.image
        for node in values['nexus']['nodes']:
            node.pop('image', None)
        values['nexus']['nodes'][int(writer_index)]['image'] = old_image
        # Reuse the installed topology and Secrets; the chart keeps Pods until the ordered replacements below.
        self.helm_upgrade(values)
        for name, pod in sorted(pods.items()):
            if name != writer:
                if self.status(writer).get('writerReady') is not True:
                    raise RuntimeError('Writer disappeared before the release barrier; deployment requires inspection')
                self.replace(name, pod['metadata']['uid'])
        # Fresh authority and SQL compare-and-set expire the old lease before a new binary may migrate Core.
        self.kube('exec', writer, '-c', 'nexus', '--', '/opt/nexus-java/bin/java',
            '-Djdk.httpclient.allowRestrictedHeaders=host', '-cp', '/opt/nexus-cluster/lib/*',
            'org.sonatype.nexus.cluster.control.ClusterUpgradeCommand', old_hash,
            self.args.application_sha256, self.args.version, timeout=180)
        self.event('release_barrier_committed')
        values['nexus']['nodes'][int(writer_index)].pop('image', None)
        self.helm_upgrade(values)
        self.replace(writer, pods[writer]['metadata']['uid'])
        def new_writer():
            states_now = {name: self.status(name) for name in self.pods()}
            ready = [name for name, state in states_now.items() if state.get('writerReady') is True]
            if len(ready) != 1 or any(s.get('applicationSHA256') != self.args.application_sha256 for s in states_now.values()):
                return False
            return ready[0]
        current = self.wait(new_writer, 'one writer on the new application release')
        self.report['completed'] = True
        self.event('upgrade_complete', writer=current)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', required=True, type=Path)
    parser.add_argument('--context', required=True)
    parser.add_argument('--namespace', required=True)
    parser.add_argument('--release', required=True)
    parser.add_argument('--chart', type=Path, default=ROOT / 'deploy/helm/nexsteem')
    parser.add_argument('--image', required=True)
    parser.add_argument('--application-sha256', required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if not re.fullmatch('[a-f0-9]{64}', args.application_sha256):
        parser.error('application SHA256 must be a complete lowercase hexadecimal digest')
    if not re.fullmatch('[A-Za-z0-9._+:-]{1,100}', args.version):
        parser.error('invalid release version')
    Upgrade(args).execute()


if __name__ == '__main__':
    main()
