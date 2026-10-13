#!/usr/bin/env python3
"""Exercise automatic Nexus takeover and degraded writes in the isolated Kind fixture.

This deliberately targets only kind-nexus-cluster-ha / nexus-cluster-test. It
never promotes databases, copies blob directories, removes PVCs or modifies the
unrelated Nexus installations. Every paused process/node resumes in finally.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('proof', ROOT / 'scripts/test-cluster-application.py')
application = importlib.util.module_from_spec(spec)
spec.loader.exec_module(application)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fault', required=True, choices=['supervisor', 'dc1', 'dc2', 'database'])
    parser.add_argument('--url', default='http://127.0.0.1:18150')
    parser.add_argument('--registry-url', default='http://127.0.0.1:18151')
    parser.add_argument('--kubeconfig', type=Path, default=ROOT / 'build/nexus-cluster-test.kubeconfig')
    parser.add_argument('--context', default=application.CONTEXT)
    parser.add_argument('--namespace', default=application.NAMESPACE)
    args = parser.parse_args()
    proof = application.Proof(args)
    proof.discover_password()
    selector = 'app.kubernetes.io/instance=cluster,app.kubernetes.io/component=nexus'
    def pods():
        return json.loads(proof.kube('get', 'pods', '-l', selector, '-o', 'json'))['items']
    def status(name):
        return json.loads(proof.kube('exec', name, '-c', 'nexus', '--', '/bin/sh', '-ec',
            'curl -fsS --max-time 5 "http://${NX_CLUSTER_PUBLIC_BIND_ADDRESS}:8081/_cluster/status"'))
    def outer_process(name, container):
        pod = json.loads(proof.kube('get', 'pod', name, '-o', 'json'))
        outer = pod['spec']['nodeName']
        node_info = json.loads(application.run('docker', 'inspect', outer))[0]
        if node_info['Config']['Labels'].get('io.x-k8s.kind.cluster') != 'nexus-cluster-ha':
            raise RuntimeError('Refusing to signal a process in an unrelated Docker node')
        identifier = next(c for c in pod['status']['containerStatuses'] if c['name'] == container)['containerID'].split('://')[1]
        inspected = json.loads(application.run('docker', 'exec', outer, 'crictl', 'inspect', identifier))
        pid = int(inspected['info']['pid'])
        if pid < 2 or inspected['status']['state'] != 'CONTAINER_RUNNING':
            raise RuntimeError('Test container is not running')
        return outer, identifier, pid
    def wait(predicate, label, seconds=600):
        deadline = time.monotonic() + seconds
        last_notice = 0
        while time.monotonic() < deadline:
            try:
                value = predicate()
                if value:
                    return value
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError, KeyError):
                pass
            if time.monotonic() - last_notice >= 30:
                print(json.dumps({'waiting': label}), flush=True)
                last_notice = time.monotonic()
            time.sleep(2)
        raise RuntimeError('Timed out: ' + label)
    def healthy():
        states = {pod['metadata']['name']: status(pod['metadata']['name']) for pod in pods()}
        if len(states) != 2 or sum(s.get('writerReady') is True for s in states.values()) != 1:
            return False
        if any(s.get('catchup') is not True or s.get('degraded') is not False for s in states.values()):
            return False
        writer = next(s for s in states.values() if s.get('writerReady'))
        return states if writer.get('pgRemoteSync') is True and writer.get('availableCopies', 0) >= 2 else False
    before = wait(healthy, 'normal remote-durable two-member cluster')
    original = next(name for name, s in before.items() if s['writerReady'])
    old_epoch = before[original]['epoch']
    old_pg = before[original]['pgNode']
    node = None
    paused = False
    stopped = False
    process = None
    kubelet_suspended = False
    label = args.fault + '-' + uuid.uuid4().hex[:8]
    proof.prepare_pending_upload(label)
    pending = json.loads((application.WORK / 'proof.json').read_text())['pending_upload']
    assert pending['degraded_at_patch'] is False, 'Pre-failure PATCH must be confirmed with remote durability'
    started = time.monotonic()
    events = {'fault': args.fault, 'label': label, 'old_writer': original, 'old_epoch': old_epoch,
        'old_pg': old_pg, 'manual_promotion': False, 'manual_blob_copy': False, 'pvc_deleted': False,
        'pre_failure_patch_confirmed_normal': True}
    try:
        if args.fault in ('dc1', 'dc2'):
            node = 'nexus-cluster-ha-worker' + ('2' if args.fault == 'dc2' else '')
            info = json.loads(application.run('docker', 'inspect', node))[0]
            if info['Config']['Labels'].get('io.x-k8s.kind.cluster') != 'nexus-cluster-ha':
                raise RuntimeError('Refusing to pause an unrelated Docker node')
            affected = {p['metadata']['name'] for p in pods() if p['spec']['nodeName'] == node}
            if original not in affected:
                raise RuntimeError('This DC fault must remove the active writer; choose the active DC')
            surviving = next(name for name in before if name not in affected)
            application.run('docker', 'pause', node)
            paused = True
        elif args.fault == 'supervisor':
            surviving = next(name for name in before if name != original)
            process = outer_process(original, 'nexus')
            outer, identifier, pid = process
            # Keep the old Core alive: suspend kubelet probe enforcement in this
            # owned test node before freezing its supervisor. Without this, Kubernetes
            # would kill the whole container before the cold standby starts Core.
            application.run('docker', 'exec', outer, 'systemctl', 'kill', '--signal=SIGSTOP', 'kubelet.service')
            kubelet_suspended = True
            events['kubelet_probe_enforcement_suspended'] = True
            # Linux protects namespace PID1 from unhandled signals sent inside
            # its own namespace. Use the owned Kind node's ancestor namespace.
            application.run('docker', 'exec', outer, '/bin/sh', '-ec', f'kill -STOP {pid}')
            stopped = True
            stat = application.run('docker', 'exec', outer, 'cat', f'/proc/{pid}/stat')
            assert stat.rsplit(')', 1)[1].split()[0] in ('T', 't'), 'Fault must actually stop supervisor'
            events['fault_injection_verified'] = True
        else:
            surviving = None
            outer, identifier, pid = outer_process(old_pg, 'postgres')
            application.run('docker', 'exec', outer, '/bin/sh', '-ec', f'kill -KILL {pid}')
        def took_over():
            candidates = [surviving] if surviving else list(before)
            for name in candidates:
                state = status(name)
                if state.get('writerReady') is True and state.get('epoch', 0) > old_epoch:
                    if args.fault == 'database' and state.get('pgNode') == old_pg:
                        continue
                    return name, state
            return False
        current, state = wait(took_over, 'automatic Nexus/Core takeover')
        events.update({'new_writer': current, 'new_epoch': state['epoch'], 'new_pg': state['pgNode'],
            'takeover_seconds': round(time.monotonic() - started, 2), 'takeover_state': state})
        if args.fault != 'database':
            assert state['degraded'] is True and state['requiredCopies'] == 1
        if args.fault == 'supervisor':
            # PID1 is frozen while its old Core child continues. Check SQL fencing
            # at the old loopback directly, bypassing the unavailable frontend.
            raw_repo = json.loads((application.WORK / 'proof.json').read_text())['repositories']['raw']
            path = '/repository/' + raw_repo + '/stale-' + label + '.txt'
            command = '''probe_config=$(mktemp)
trap 'rm -f "$probe_config"' EXIT
cat >"$probe_config"
curl --silent --max-time 5 -o /dev/null -w '%{http_code}\\n' http://127.0.0.1:19081/
curl --silent --max-time 5 --config "$probe_config" -o /dev/null -w '%{http_code}\\n' -X PUT -H 'Content-Type: text/plain' --data-binary stale-owner http://127.0.0.1:19081''' + path + ' || [ "$?" -eq 28 ]'
            config = 'user = "admin:' + proof.password.replace('\\', '\\\\').replace('"', '\\"') + '"\n'
            result = subprocess.run(['docker', 'exec', '-i', outer, 'crictl', 'exec', '-i', identifier,
                '/bin/sh', '-ec', command],
                input=config, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
            codes = result.stdout.strip().splitlines()
            assert result.returncode == 0 and len(codes) == 2 and codes[0].startswith(('2', '3')), ('Old Core static HTTP probe failed', result.returncode, codes)
            assert not codes[1].startswith('2'), 'Old fixed epoch must reject writes'
            try:
                proof.api(path)
                raise AssertionError('Stale Core write reached current SQL repository')
            except AssertionError as error:
                assert 'HTTP 404:' in str(error), str(error)
            events.update({'old_core_alive_during_takeover': True, 'old_core_put_status': int(codes[1]),
                'old_core_write_timed_out_fail_closed': codes[1] == '000',
                'stale_core_sql_write_rejected': True})
        proof.verify(label)
        payload = (label + ': successful during failure\n').encode() * 1000
        raw_repo = json.loads((application.WORK / 'proof.json').read_text())['repositories']['raw']
        _, headers, _ = proof.api('/repository/' + raw_repo + '/degraded-' + label + '.txt', 'PUT', payload, 'text/plain')
        if args.fault != 'database':
            assert headers.get('X-Nexus-Cluster-Degraded') == 'true', dict(headers)
        state_file = application.WORK / 'proof.json'
        artifacts = json.loads(state_file.read_text())
        artifacts['entries'].append({'path': '/repository/' + raw_repo + '/degraded-' + label + '.txt',
            'sha256': application.sha(payload), 'size': len(payload)})
        state_file.write_text(json.dumps(artifacts, indent=2) + '\n')
        events.update({'confirmed_artifacts_preserved': True, 'registry_patch_resumed': True,
            'new_write_during_failure': True, 'degraded_explicit': args.fault != 'database'})
        subprocess.run(['python3', str(ROOT / 'scripts/test-cluster-clients.py'), 'verify', '--label', label],
            cwd=ROOT, check=True)
    finally:
        if paused:
            application.run('docker', 'unpause', node)
        try:
            if stopped:
                outer, identifier, pid = process
                inspected = json.loads(application.run('docker', 'exec', outer, 'crictl', 'inspect', identifier))
                if inspected['status']['state'] == 'CONTAINER_RUNNING' and int(inspected['info']['pid']) == pid:
                    application.run('docker', 'exec', outer, '/bin/sh', '-ec', f'kill -CONT {pid}')
        finally:
            if kubelet_suspended:
                application.run('docker', 'exec', process[0], 'systemctl', 'kill', '--signal=SIGCONT', 'kubelet.service')
    after = wait(healthy, 'automatic full catch-up and return to normal', seconds=900)
    proof.verify('recovered-' + label)
    events.update({'automatic_replication_restore': True, 'normal_after_restore': True,
        'recovery_seconds': round(time.monotonic() - started, 2), 'restored_states': after, 'completed': True})
    path = ROOT / ('build/logs/cluster-application-failover-' + args.fault + '.json')
    path.write_text(json.dumps(events, indent=2) + '\n')
    print(json.dumps({key: value for key, value in events.items() if key not in ('takeover_state', 'restored_states')}, indent=2), flush=True)


if __name__ == '__main__':
    main()
