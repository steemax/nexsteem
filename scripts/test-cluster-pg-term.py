#!/usr/bin/env python3
"""Freeze Patroni with PostgreSQL alive in the isolated Kind integration fixture.

The node kubelet is also frozen briefly so its liveness probe cannot replace
the intentionally stopped Patroni process. Both exact processes resume in
finally. No promotion, Pod deletion, image change or PVC change is performed.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import signal
import subprocess
import time
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('application', ROOT / 'scripts/test-cluster-application.py')
application = importlib.util.module_from_spec(spec)
spec.loader.exec_module(application)
NAME = 'nexus-cluster-ha'
REPORT = ROOT / 'build/logs/cluster-pg-term-fault-runtime.json'


class TermProof:
    def __init__(self, args):
        if args.kubeconfig.resolve() != (ROOT / 'build/nexus-cluster-test.kubeconfig').resolve():
            raise ValueError('Only the private fixture kubeconfig is allowed')
        if args.url not in ('http://localhost:18150', 'http://127.0.0.1:18150'):
            raise ValueError('Only the fixture Nexus endpoint is allowed')
        if args.registry_url not in ('http://localhost:18151', 'http://127.0.0.1:18151'):
            raise ValueError('Only the fixture Registry endpoint is allowed')
        self.proof = application.Proof(args)
        self.proof.discover_password()
        self.image_id = args.image_id if args.image_id.startswith('sha256:') else 'sha256:' + args.image_id
        self.application_sha = args.application_sha256
        self.report = {'completed': False, 'context': args.context, 'namespace': args.namespace,
            'image_id': self.image_id, 'application_sha256': self.application_sha,
            'manual_promotion': False, 'pvc_deleted': False, 'images_changed': False, 'events': []}
        self.event('preflight_started')

    def event(self, name, **values):
        self.report['events'].append({'event': name, 'time': time.time(), **values})
        REPORT.write_text(json.dumps(self.report, indent=2) + '\n')
        print(json.dumps(self.report['events'][-1]), flush=True)

    def wait(self, predicate, label, seconds=180):
        deadline = time.monotonic() + seconds
        notice = 0
        while time.monotonic() < deadline:
            try:
                result = predicate()
                if result:
                    return result
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired,
                    ValueError, KeyError, OSError, urllib.error.URLError, AssertionError):
                pass
            if time.monotonic() - notice >= 30:
                print(json.dumps({'waiting': label}), flush=True)
                notice = time.monotonic()
            time.sleep(2)
        raise RuntimeError('Timed out: ' + label)

    def process(self, pod_name, component):
        pod = json.loads(self.proof.kube('get', 'pod', pod_name, '-o', 'json'))
        node = pod['spec']['nodeName']
        owner = json.loads(application.run('docker', 'inspect', node))[0]
        if owner['Config']['Labels'].get('io.x-k8s.kind.cluster') != NAME:
            raise RuntimeError('Refusing to signal an unrelated Docker node')
        item = next(c for c in pod['status']['containerStatuses'] if c['name'] == component)
        cid = item['containerID'].split('://', 1)[1]
        inspected = json.loads(application.run('docker', 'exec', node, 'crictl', 'inspect', cid))
        pid = int(inspected['info']['pid'])
        if pid <= 1 or inspected['status']['state'] != 'CONTAINER_RUNNING':
            raise RuntimeError('Target container is not running')
        return {'pod': pod_name, 'node': node, 'cid': cid, 'pid': pid}

    def procstat(self, node, pid):
        tail = application.run('docker', 'exec', node, 'cat', f'/proc/{pid}/stat').rsplit(')', 1)[1].split()
        return {'state': tail[0], 'start_tick': tail[19]}

    def child(self, process, name):
        # ProcessBuilder/Patroni may spawn from a worker thread. Linux records
        # children per task, rather than only under the main thread's task.
        pending = [(process['pid'], 0)]
        seen = {process['pid']}
        while pending:
            parent, depth = pending.pop(0)
            children = application.run('docker', 'exec', process['node'], '/bin/sh', '-ec',
                f'cat /proc/{parent}/task/*/children').split()
            for item in children:
                pid = int(item)
                if pid in seen:
                    continue
                seen.add(pid)
                if application.run('docker', 'exec', process['node'], 'cat', f'/proc/{pid}/comm') == name:
                    return {'pid': pid, **self.procstat(process['node'], pid)}
                if depth < 3:
                    pending.append((pid, depth + 1))
        raise RuntimeError('Expected running child: ' + name)

    def status(self, process):
        return json.loads(application.run('docker', 'exec', process['node'], 'crictl', 'exec',
            process['cid'], '/bin/sh', '-ec',
            'curl -fsS --max-time 5 "http://${NX_CLUSTER_PUBLIC_BIND_ADDRESS}:8081/_cluster/status"'))

    def states(self, nexus):
        return {name: self.status(process) for name, process in nexus.items()}

    @staticmethod
    def normal(states):
        writers = [s for s in states.values() if s.get('writerReady')]
        return len(writers) == 1 and writers[0].get('pgRemoteSync') is True and all(
            s.get('catchup') is True and s.get('degraded') is False and s.get('availableCopies', 0) >= 2
            for s in states.values())

    def verify_artifacts(self):
        state = json.loads((application.WORK / 'proof.json').read_text())
        assert len(state['entries']) >= 19, 'This scenario requires the already seeded full fixture'
        for name in state['repositories'].values():
            assert json.loads(self.proof.api('/service/rest/v1/repositories/' + name)[2])['name'] == name
        for item in state['entries']:
            value = self.proof.api(item['path'], extra={'Accept': 'application/vnd.oci.image.manifest.v1+json'},
                registry=item.get('registry', False))[2]
            assert application.sha(value) == item['sha256'] and len(value) == item['size'], item['path']
        return len(state['entries'])

    def execute(self):
        pods = json.loads(self.proof.kube('get', 'pods', '-l',
            'app.kubernetes.io/instance=cluster,app.kubernetes.io/component=nexus', '-o', 'json'))['items']
        if len(pods) != 2:
            raise RuntimeError('This fixture requires exactly two Nexus members')
        for pod in pods:
            image = next(c['imageID'] for c in pod['status']['containerStatuses'] if c['name'] == 'nexus')
            assert self.image_id.split(':', 1)[1] in image, 'This proof requires the requested immutable image'
        nexus = {pod['metadata']['name']: self.process(pod['metadata']['name'], 'nexus') for pod in pods}
        before = self.wait(lambda: (s if self.normal(s) else False) if (s := self.states(nexus)) else False,
            'normal two-copy cluster before fault')
        old_writer = next(name for name, s in before.items() if s['writerReady'])
        assert all(s['applicationSHA256'] == self.application_sha for s in before.values())
        old = before[old_writer]
        old_core = self.child(nexus[old_writer], 'java')
        pg = self.process(old['pgNode'], 'postgres')
        # Patroni runs an init/reaper when its entrypoint is namespace PID1.
        # Freezing that wrapper leaves the actual daemon renewing its DCS lease.
        patroni = self.child(pg, 'patroni')
        postgres = self.child(pg, 'postgres')
        kubelet_pid = int(application.run('docker', 'exec', pg['node'], 'systemctl', 'show', 'kubelet',
            '--property=MainPID', '--value'))
        if kubelet_pid <= 1:
            raise RuntimeError('Owned node kubelet has no running PID')
        kubelet_stat = self.procstat(pg['node'], kubelet_pid)
        self.report.update({'old_pg': old['pgNode'], 'old_pg_term': old['pgTerm'],
            'old_epoch': old['epoch'], 'old_writer': old_writer, 'old_core_pid': old_core['pid'],
            'verified_artifacts_before': self.verify_artifacts()})
        stopped_pg = stopped_kubelet = False
        started = time.monotonic()
        def expired(_signum, _frame):
            # RuntimeError must escape wait(), which retries transport OSError.
            raise RuntimeError('Fault lifetime exceeded 180 seconds; restoring both processes')
        previous_alarm = signal.signal(signal.SIGALRM, expired)
        try:
            signal.setitimer(signal.ITIMER_REAL, 180)
            stopped_kubelet = True
            application.run('docker', 'exec', pg['node'], 'kill', '-STOP', str(kubelet_pid))
            assert self.procstat(pg['node'], kubelet_pid)['state'] in ('T', 't')
            stopped_pg = True
            application.run('docker', 'exec', pg['node'], 'kill', '-STOP', str(patroni['pid']))
            assert self.procstat(pg['node'], patroni['pid'])['state'] in ('T', 't')
            assert self.procstat(pg['node'], postgres['pid'])['state'] not in ('T', 't', 'Z', 'X', 'x')
            self.event('fault_injected', patroni_pid=patroni['pid'], container_init_pid=pg['pid'], postgresql_pid=postgres['pid'],
                kubelet_pid=kubelet_pid, node=pg['node'])
            def changed():
                for name, state in self.states(nexus).items():
                    if state.get('writerReady') and state.get('epoch', 0) > old['epoch'] \
                            and state.get('pgNode') != old['pgNode'] and state.get('pgTerm') != old['pgTerm']:
                        assert state['corePgTerm'] == state['epochPgTerm'] == state['pgTerm']
                        return name, state
                return False
            name, current = self.wait(changed, 'new PG term and new Core epoch', seconds=180)
            alive = self.procstat(pg['node'], postgres['pid'])
            assert alive['start_tick'] == postgres['start_tick'] and alive['state'] not in ('T', 't', 'Z', 'X', 'x')
            try:
                old_alive = self.procstat(nexus[old_writer]['node'], old_core['pid'])['start_tick'] == old_core['start_tick']
            except subprocess.CalledProcessError:
                old_alive = False
            assert not old_alive, 'Core bound to old PG term must be stopped before new epoch serves'
            self.report.update({'new_pg': current['pgNode'], 'new_pg_term': current['pgTerm'],
                'new_epoch': current['epoch'], 'new_writer': name, 'old_core_stopped': True,
                'postgresql_child_alive_during_takeover': True, 'takeover_seconds': round(time.monotonic() - started, 2),
                'verified_artifacts_during_fault': self.verify_artifacts(), 'takeover_state': current})
            fixture = application.WORK / 'proof.json'
            saved = json.loads(fixture.read_text())
            path = '/repository/' + saved['repositories']['raw'] + '/pg-term-' + uuid.uuid4().hex + '.bin'
            payload = ('PG term automatic takeover ' + current['pgTerm'] + '\n').encode() * 1024
            code, headers, _ = self.proof.api(path, 'PUT', payload)
            assert code in (200, 201, 204)
            assert headers.get('X-Nexus-Cluster-PG-Term') == current['pgTerm']
            assert int(headers.get('X-Nexus-Cluster-Epoch', '0')) == current['epoch']
            assert application.sha(self.proof.api(path)[2]) == application.sha(payload)
            saved['entries'].append({'path': path, 'sha256': application.sha(payload), 'size': len(payload)})
            fixture.write_text(json.dumps(saved, indent=2) + '\n')
            self.report.update({'new_owned_write': True, 'positive_fresh_headers': True})
            self.event('automatic_takeover_verified', pg_term=current['pgTerm'], epoch=current['epoch'])
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, previous_alarm)
            # Never signal a reused PID or a replacement container.
            try:
                if stopped_pg:
                    live = json.loads(application.run('docker', 'exec', pg['node'], 'crictl', 'inspect', pg['cid']))
                    if live['status']['state'] != 'CONTAINER_RUNNING' or int(live['info']['pid']) != pg['pid']:
                        raise RuntimeError('Stopped Patroni was replaced; cannot claim same-process resume')
                    assert self.procstat(pg['node'], patroni['pid'])['start_tick'] == patroni['start_tick']
                    application.run('docker', 'exec', pg['node'], 'kill', '-CONT', str(patroni['pid']))
                    self.report['patroni_same_process_resumed'] = True
            finally:
                if stopped_kubelet:
                    current_pid = int(application.run('docker', 'exec', pg['node'], 'systemctl', 'show', 'kubelet', '--property=MainPID', '--value'))
                    assert current_pid == kubelet_pid and self.procstat(pg['node'], kubelet_pid)['start_tick'] == kubelet_stat['start_tick']
                    application.run('docker', 'exec', pg['node'], 'kill', '-CONT', str(kubelet_pid))
                    self.report['kubelet_same_process_resumed'] = True
                self.event('fault_processes_resumed')
        after = self.wait(lambda: (s if self.normal(s) else False) if (s := self.states(nexus)) else False,
            'remote SQL sync and full two-copy blob catch-up', seconds=900)
        self.report.update({'normal_after_restore': True, 'automatic_replication_restore': True,
            'verified_artifacts_after_restore': self.verify_artifacts(), 'restored_states': after,
            'completed': True, 'total_seconds': round(time.monotonic() - started, 2)})
        self.event('pg_term_fault_complete')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:18150')
    parser.add_argument('--registry-url', default='http://127.0.0.1:18151')
    parser.add_argument('--kubeconfig', type=Path, default=ROOT / 'build/nexus-cluster-test.kubeconfig')
    parser.add_argument('--context', default=application.CONTEXT)
    parser.add_argument('--namespace', default=application.NAMESPACE)
    parser.add_argument('--image-id', required=True, help='Immutable Docker config ID, 64 lowercase hex digits with optional sha256: prefix')
    parser.add_argument('--application-sha256', required=True, help='Exact application SHA256 from image provenance')
    args = parser.parse_args()
    if not re.fullmatch(r'(?:sha256:)?[a-f0-9]{64}', args.image_id):
        parser.error('--image-id must contain 64 lowercase hex digits')
    if not re.fullmatch(r'[a-f0-9]{64}', args.application_sha256):
        parser.error('--application-sha256 must contain 64 lowercase hex digits')
    TermProof(args).execute()


if __name__ == '__main__':
    main()
