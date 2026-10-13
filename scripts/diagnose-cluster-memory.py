#!/usr/bin/env python3
"""Read container/Java memory without credentials, JVM attach, forced GC or dumps.

Requires Python 3 locally and kubectl. JVM counters come from HotSpot's existing
hsperfdata files; their used values are sampled counters, not a live-object census.
Always select the Kubernetes context explicitly. No Kubernetes resources change.
"""
import argparse
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import struct
import subprocess
import time


REMOTE = r'''
date -u +TIME=%Y-%m-%dT%H:%M:%SZ
for name in memory.current memory.max memory.peak memory.events memory.stat; do
  file=/sys/fs/cgroup/$name
  if [ -r "$file" ]; then
    echo "BEGIN $name"
    cat "$file"
    echo END
  fi
done
if [ ! -r /sys/fs/cgroup/memory.current ]; then
  for name in memory.usage_in_bytes memory.limit_in_bytes memory.max_usage_in_bytes memory.failcnt memory.stat; do
    file=/sys/fs/cgroup/memory/$name
    if [ -r "$file" ]; then echo "BEGIN $name"; cat "$file"; echo END; fi
  done
fi
for directory in /proc/[0-9]*; do
  [ -r "$directory/comm" ] || continue
  [ "$(cat "$directory/comm")" = java ] || continue
  pid=${directory##*/}
  role=java
  if tr '\000' '\n' < "$directory/cmdline" | grep -q 'org.sonatype.nexus.cluster.control.ClusterSupervisor'; then
    role=coordinator
  elif tr '\000' '\n' < "$directory/cmdline" | grep -q 'nexus-repository-core'; then
    role=core
  fi
  echo "BEGIN process $pid $role"
  sed -n '/^Pid:/p; /^VmRSS:/p; /^VmHWM:/p; /^RssAnon:/p; /^RssFile:/p; /^Threads:/p' "$directory/status"
  if [ -r "$directory/stat" ]; then echo "START $(cat "$directory/stat")"; fi
  echo END
  for data in /tmp/hsperfdata_*/"$pid"; do
    if [ -r "$data" ]; then
      echo "BEGIN perf $pid"
      base64 "$data"
      echo END
      break
    fi
  done
done
'''


def perf_counters(data):
    if len(data) < 32 or data[4] not in (0, 1) or data[5] != 2 or data[7] != 1:
        raise ValueError('Unsupported or inaccessible HotSpot perfdata')
    endian = '<' if data[4] == 1 else '>'
    # HotSpot stores the magic in big endian, independently of counter byte order.
    if struct.unpack_from('>I', data)[0] != 0xcafec0c0:
        raise ValueError('Invalid HotSpot perfdata magic')
    used = struct.unpack_from(endian + 'i', data, 8)[0]
    offset, count = struct.unpack_from(endian + 'ii', data, 24)
    if not 32 <= offset <= used <= len(data) or not 0 <= count <= 4096:
        raise ValueError('Invalid HotSpot perfdata bounds')
    counters = {}
    for _ in range(count):
        if offset + 20 > used:
            raise ValueError('Truncated perfdata entry')
        size, name_offset, length = struct.unpack_from(endian + 'iii', data, offset)
        data_offset = struct.unpack_from(endian + 'i', data, offset + 16)[0]
        if size < 20 or offset + size > used or not 20 <= name_offset < size or not 20 <= data_offset < size:
            raise ValueError('Invalid perfdata entry bounds')
        name_start = offset + name_offset
        name = data[name_start:data.index(0, name_start, offset + size)].decode('ascii')
        # Never export VM arguments, system properties or raw perfdata.
        allowed = (name.startswith('sun.gc.generation.') or name.startswith('sun.gc.collector.')
                   or name.startswith('sun.gc.metaspace.') or name.startswith('java.threads.')
                   or name in ('java.cls.loadedClasses', 'java.cls.unloadedClasses', 'sun.gc.policy.name'))
        kind = chr(data[offset + 12])
        if allowed and kind == 'J' and length == 0 and data_offset + 8 <= size:
            counters[name] = struct.unpack_from(endian + 'q', data, offset + data_offset)[0]
        elif allowed and kind == 'B' and 0 < length <= size - data_offset:
            counters[name] = data[offset + data_offset:offset + data_offset + length].split(b'\0')[0].decode('ascii')
        offset += size
    return counters


def parse_snapshot(raw):
    result = {'processes': [], 'cgroup': {}}
    blocks = re.findall(r'^BEGIN ([^\n]+)\n(.*?)^END$', raw, re.M | re.S)
    stamp = re.search(r'^TIME=(.*)$', raw, re.M)
    result['sampledAt'] = stamp[1] if stamp else datetime.now(timezone.utc).isoformat()
    processes = {}
    for title, body in blocks:
        if title.startswith('process '):
            _, pid, role = title.split()
            process = {'pid': int(pid), 'role': role}
            for key, value in re.findall(r'^(\w+):\s+(\d+)', body, re.M):
                if key != 'Pid':
                    process[key] = int(value) * (1024 if key != 'Threads' else 1)
            start = re.search(r'^START (.*)$', body, re.M)
            if start:
                fields = start[1].rsplit(')', 1)[1].split()
                process['startTicks'] = int(fields[19])
            processes[pid] = process
            result['processes'].append(process)
        elif title.startswith('perf '):
            process = processes[title.split()[1]]
            try:
                counters = perf_counters(base64.b64decode(''.join(body.split()), validate=True))
                process['jvm'] = counters
                process['heapUsedSampled'] = sum(value for key, value in counters.items()
                    if re.fullmatch(r'sun\.gc\.generation\.\d+\.space\.\d+\.used', key))
                process['heapCommittedSampled'] = sum(value for key, value in counters.items()
                    if re.fullmatch(r'sun\.gc\.generation\.\d+\.capacity', key))
                maximums = [value for key, value in counters.items()
                    if re.fullmatch(r'sun\.gc\.generation\.\d+\.maxCapacity', key)]
                process['heapMaxGenerationCapacity'] = max(maximums) if maximums else None
            except (ValueError, UnicodeError, struct.error) as error:
                process['perfError'] = str(error)
        else:
            content = body.strip()
            result['cgroup'][title] = ({key: int(value) for key, value in re.findall(r'^(\w+) (\d+)$', content, re.M)}
                                      if title.endswith(('stat', 'events')) else int(content) if content.isdigit() else content)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    parser.add_argument('--kubeconfig', type=Path)
    parser.add_argument('--namespace', default='nexus')
    parser.add_argument('--pod', action='append', required=True)
    parser.add_argument('--samples', type=int, default=1)
    parser.add_argument('--interval', type=float, default=60)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not 1 <= args.samples <= 1000 or not 1 <= args.interval <= 3600:
        parser.error('samples must be 1..1000; interval must be 1..3600 seconds')
    command = ['kubectl', '--context', args.context]
    if args.kubeconfig:
        command += ['--kubeconfig', str(args.kubeconfig)]
    command += ['-n', args.namespace]
    report = {'schemaVersion': 1, 'context': args.context, 'namespace': args.namespace,
              'readOnly': True, 'forcedGC': False, 'samples': []}
    for index in range(args.samples):
        for pod in args.pod:
            try:
                response = subprocess.run(command + ['exec', pod, '-c', 'nexus', '--', 'sh', '-c', REMOTE],
                                          capture_output=True, text=True, timeout=30, check=True)
                snapshot = parse_snapshot(response.stdout)
                snapshot['pod'] = pod
            except (subprocess.SubprocessError, ValueError) as error:
                snapshot = {'pod': pod, 'error': type(error).__name__, 'sampledAt': datetime.now(timezone.utc).isoformat()}
            report['samples'].append(snapshot)
            print(json.dumps(snapshot, ensure_ascii=False), flush=True)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        if index + 1 < args.samples:
            time.sleep(args.interval)
    return 1 if any('error' in sample for sample in report['samples']) else 0


if __name__ == '__main__':
    raise SystemExit(main())
