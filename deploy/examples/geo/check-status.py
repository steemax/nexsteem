#!/usr/bin/env python3
"""Read per-member status; never install, promote, restart or change a cluster."""
import argparse
import json
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig', required=True)
    parser.add_argument('--context', required=True)
    parser.add_argument('--namespace', default='nexus')
    parser.add_argument('--release', default='nexus')
    args = parser.parse_args()
    kubectl = ['kubectl', '--kubeconfig', args.kubeconfig, '--context', args.context,
               '-n', args.namespace]

    def run(*arguments):
        result = subprocess.run([*kubectl, *arguments], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr.strip()[:1200])
        return result.stdout

    pods = json.loads(run('get', 'pods', '-l',
        'app.kubernetes.io/instance=' + args.release + ',app.kubernetes.io/component=nexus',
        '-o', 'json'))['items']
    members = []
    for pod in pods:
        name = pod['metadata']['name']
        state = json.loads(run('exec', name, '-c', 'nexus', '--', '/bin/sh', '-ec',
            'curl -fsS --max-time 10 "http://${NX_CLUSTER_PUBLIC_BIND_ADDRESS}:8081/_cluster/status"'))
        member = {'pod': name, 'dc': pod['metadata']['labels'].get('nexus.cluster/dc')}
        for key in ('writerReady', 'coreRunning', 'epoch', 'degraded', 'requiredCopies',
                    'availableCopies', 'catchup', 'pgNode', 'pgRemoteSync', 'pgTerm',
                    'corePgTerm', 'epochPgTerm', 'detail'):
            member[key] = state.get(key)
        if state.get('writerReady'):
            code = run('exec', name, '-c', 'nexus', '--', '/bin/sh', '-ec',
                'curl -s --max-time 10 -o /dev/null -w "%{http_code}" '
                '"http://${NX_CLUSTER_PUBLIC_BIND_ADDRESS}:8081/_cluster/health/writer"').strip()
            member['freshWriterHealthCode'] = code
        members.append(member)
    writers = [member for member in members if member['writerReady']]
    normal = (len(members) >= 2 and len(writers) == 1
        and all(member['catchup'] is True and member['degraded'] is False for member in members)
        and writers[0]['pgRemoteSync'] is True and writers[0]['requiredCopies'] == 2
        and writers[0]['availableCopies'] >= 2 and writers[0].get('freshWriterHealthCode') == '200'
        and writers[0]['pgTerm'] == writers[0]['corePgTerm'] == writers[0]['epochPgTerm'])
    print(json.dumps({'normal': normal, 'writer_count': len(writers), 'members': members},
                     ensure_ascii=False, indent=2))
    return 0 if normal else 1


if __name__ == '__main__':
    raise SystemExit(main())
