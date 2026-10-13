#!/usr/bin/env python3
"""Owned Docker proof of Patroni auto failover; chart config, real WAL and TLS."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import time
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'build/cluster-postgres-r2-proof'
PREFIX = 'nexus-cluster-pg-r2'
NETWORK = PREFIX + '-network'
PG_IMAGE = 'nexus-cluster-postgres:17-patroni4.1.5-r2'
PG_PRIMARY_IMAGE = 'nexus-cluster-postgres:17-patroni4.1.5-r2-amd64'
PG_NAMES = [PREFIX + '-pg-' + str(i) for i in range(3)]
ETCD_NAMES = [PREFIX + '-etcd-' + str(i) for i in range(3)]

def run(*argv, input=None, timeout=120):
    return subprocess.check_output(argv, input=input, text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()

def wait(fn, label, seconds=180):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            value = fn()
            if value:
                return value
        except (subprocess.CalledProcessError, OSError, json.JSONDecodeError) as error:
            last = str(error)
        time.sleep(2)
    raise RuntimeError(label + ': timeout; last=' + str(last))

def documents():
    return list(yaml.safe_load_all(run('helm', 'template', 'proof', str(ROOT/'deploy/helm/nexsteem'),
                '-n', 'cluster-test', '--set', 'nexus.enabled=false', '--set', 'placement.requiredAntiAffinity=false')))

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        path.chmod(0o600)
    path.write_text(data)
    path.chmod(0o444)

def sql(statement, host='proof-cluster-postgres-rw.cluster-test.svc.cluster.local', container=None):
    # Password is read inside a throwaway trusted client, never printed.
    source = """import json, psycopg
from pathlib import Path
password=Path('/run/nexus-postgres/secrets/postgres-password').read_text().strip()
with psycopg.connect(host=%s, port=5432, dbname='postgres', user='postgres', password=password,
    sslmode='verify-full', sslrootcert='/run/nexus-postgres/tls/ca.crt', connect_timeout=3,
    autocommit=True, options='-c statement_timeout=5000') as c:
    r=c.execute(%s)
    print(json.dumps(r.fetchall() if r.description else []))
""" % (repr(host), repr(statement))
    if container:
        return json.loads(run('docker', 'exec', '-i', container, '/opt/patroni/bin/python', '-c', source, timeout=15))
    return json.loads(run('docker', 'run', '--rm', '--network', NETWORK,
        '--mount', f'type=bind,src={WORK}/secrets,dst=/run/nexus-postgres/secrets,readonly',
        '--mount', f'type=bind,src={WORK}/postgres-tls,dst=/run/nexus-postgres/tls,readonly',
        '--entrypoint', '/opt/patroni/bin/python', PG_IMAGE, '-c', source, timeout=15))

def status(container):
    return json.loads(run('docker', 'exec', container, 'curl', '-fsS', '--cacert',
        '/run/nexus-postgres/tls/ca.crt', 'https://localhost:8008/patroni', timeout=8))

def primary(excluding=None):
    leaders = []
    for name in PG_NAMES:
        if name == excluding:
            continue
        try:
            if status(name).get('role') in ('primary', 'master'):
                leaders.append(name)
        except subprocess.CalledProcessError:
            pass
    return leaders[0] if len(leaders) == 1 else None

def ready_replicas():
    states = [status(x) for x in PG_NAMES]
    if sum(x.get('role') == 'replica' and x.get('state') == 'running' for x in states) != 2:return False
    leaders=[x for x in states if x.get('role') in ('primary','master')]
    if len(leaders)!=1:return False
    leader=leaders[0];dc=leader.get('tags',{}).get('dc')
    member_dc={x.get('patroni',{}).get('name'):x.get('tags',{}).get('dc') for x in states}
    actual={x.get('application_name') for x in leader.get('replication',[]) if x.get('state')=='streaming' and x.get('sync_state')=='sync' and member_dc.get(x.get('application_name')) not in (None,dc)}
    if not actual:return False
    leader_container=next(PG_NAMES[i] for i,x in enumerate(states) if x.get('role') in ('primary','master'))
    cluster=json.loads(run('docker','exec',leader_container,'curl','-fsS','--max-time','3','--cacert','/run/nexus-postgres/tls/ca.crt','https://localhost:8008/cluster'))
    return any(x.get('name') in actual and x.get('role')=='sync_standby' for x in cluster.get('members',[]))

def save_logs(suffix):
    for name in PG_NAMES + ETCD_NAMES + [PREFIX+'-router']:
        try:
            (ROOT/'build/logs'/f'{name}-{suffix}.log').write_text(run('docker', 'logs', '--tail', '250', name))
        except subprocess.CalledProcessError:
            pass

def prepare():
    existing = run('docker', 'ps', '-a', '--format', '{{.Names}}').splitlines()
    if any(name.startswith(PREFIX+'-') for name in existing):
        raise RuntimeError('This owned fixture already exists; inspect it, then use --reuse')
    WORK.mkdir(parents=True, exist_ok=True)
    WORK.chmod(0o700)
    docs = documents()
    secrets = next(x for x in docs if x and x['kind']=='Secret' and x['metadata']['name'].endswith('-secrets'))
    tls = next(x for x in docs if x and x['kind']=='Secret' and x['metadata']['name'].endswith('-tls'))
    for key, value in secrets['data'].items():
        write(WORK/'secrets'/key, base64.b64decode(value).decode())
    for component in ['postgres','etcd']:
        for key, filename in [(component+'-ca.crt','ca.crt'),(component+'.crt','tls.crt'),(component+'.key','tls.key')]:
            write(WORK/(component+'-tls')/filename, base64.b64decode(tls['data'][key]).decode())
    for key,filename in [('etcd-ca.crt','ca.crt'),('etcd-client.crt','tls.crt'),('etcd-client.key','tls.key')]:
        write(WORK/'etcd-client-tls'/filename,base64.b64decode(tls['data'][key]).decode())
    configs = {x['metadata']['name']:x['data'] for x in docs if x and x['kind']=='ConfigMap'}
    for filename, content in configs['proof-cluster-postgres'].items():
        write(WORK/'config'/filename, content)
    write(WORK/'router/haproxy.cfg', configs['proof-cluster-router']['haproxy.cfg'])
    networks=run('docker','network','ls','--format','{{.Name}}').splitlines()
    if NETWORK not in networks:
        run('docker','network','create',NETWORK)
    etcd_spec = next(x for x in docs if x and x['kind']=='StatefulSet' and x['metadata']['name']=='proof-cluster-etcd-0')['spec']['template']['spec']['containers'][0]
    for i,name in enumerate(ETCD_NAMES):
        pod = 'proof-cluster-etcd-'+str(i)+'-0'
        run('docker','volume','create',name+'-data')
        run('docker','run','-d','--name',name,'--network',NETWORK,
            '--network-alias',pod+'.proof-cluster-etcd.cluster-test.svc.cluster.local',
            '--mount',f'type=volume,src={name}-data,dst=/var/lib/etcd',
            '--mount',f'type=bind,src={WORK}/etcd-tls,dst=/run/tls,readonly',
            etcd_spec['image'],*etcd_spec['command'],*[x.replace('$(POD_NAME)',pod) for x in etcd_spec['args']])
    wait(lambda:'is healthy' in run('docker','exec',ETCD_NAMES[0],'/usr/local/bin/etcdctl',
         '--endpoints=https://localhost:2379','--cacert=/run/tls/ca.crt','--cert=/run/tls/tls.crt','--key=/run/tls/tls.key','endpoint','health'),'etcd quorum')
    endpoints=['https://proof-cluster-etcd-'+str(i)+'-0.proof-cluster-etcd.cluster-test.svc.cluster.local:2379' for i in range(3)]
    run('docker','run','--rm','--network',NETWORK,
        '-e','ETCD_ENDPOINTS='+json.dumps(endpoints),'-e','ETCD_SCOPE=/nexus-pg/proof-cluster/','-e','ETCD_CERT_USER=proof-cluster-etcd',
        '--mount',f'type=bind,src={WORK}/secrets,dst=/run/nexus-postgres/secrets,readonly',
        '--mount',f'type=bind,src={WORK}/etcd-client-tls,dst=/run/nexus-postgres/etcd,readonly',
        '--entrypoint','/opt/patroni/bin/python',PG_IMAGE,'/opt/nexus-postgres/etcd-auth.py')
    start_postgres()
    start_router(docs)

def start_router(docs=None):
    if docs is None:docs=documents()
    router = next(x for x in docs if x and x['kind']=='Deployment' and x['metadata']['name']=='proof-cluster-router-0')['spec']['template']['spec']['containers'][0]
    run('docker','run','-d','--name',PREFIX+'-router','--network',NETWORK,
        '--network-alias','proof-cluster-postgres-rw','--publish','127.0.0.1:18140:5432',
        '--network-alias','proof-cluster-postgres-rw.cluster-test.svc.cluster.local',
        '--mount',f'type=bind,src={WORK}/router,dst=/usr/local/etc/haproxy,readonly',
        '--mount',f'type=bind,src={WORK}/postgres-tls,dst=/run/tls,readonly',router['image'],*router['args'])

def start_postgres():
    for i,name in enumerate(PG_NAMES):
        pod = 'proof-cluster-postgres-'+str(i)+'-0'
        run('docker','volume','create',name+'-data')
        # Docker named volumes initially belong to root; initialize only owned volumes.
        run('docker','run','--rm','--user','0','--mount',f'type=volume,src={name}-data,dst=/data',
            '--entrypoint','chown',PG_IMAGE,'999:999','/data')
        run('docker','run','-d','--name',name,'--network',NETWORK,
            '--network-alias',pod+'.proof-cluster-postgres.cluster-test.svc.cluster.local',
            '-e','POD_NAME='+pod,'-e','PG_HEADLESS=proof-cluster-postgres.cluster-test.svc.cluster.local',
            '-e','PG_DC='+('dc1' if i==0 else 'dc2'),
            '--mount',f'type=volume,src={name}-data,dst=/var/lib/postgresql/data',
            '--mount',f'type=bind,src={WORK}/config,dst=/etc/nexus-postgres,readonly',
            '--mount',f'type=bind,src={WORK}/secrets,dst=/run/nexus-postgres/secrets,readonly',
            '--mount',f'type=bind,src={WORK}/postgres-tls,dst=/run/nexus-postgres/tls,readonly',
            '--mount',f'type=bind,src={WORK}/etcd-client-tls,dst=/run/nexus-postgres/etcd,readonly',PG_PRIMARY_IMAGE if i==0 else PG_IMAGE)
        if i==0:wait(lambda:status(name).get('role') in ('primary','master'),'production AMD64 initial primary')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reuse',action='store_true')
    parser.add_argument('--setup-only',action='store_true')
    args=parser.parse_args()
    if not args.reuse:
        prepare()
    try:
        leader=wait(primary,'one automatic primary')
        wait(ready_replicas,'two streaming replicas')
        wait(lambda:sql("SELECT current_setting('synchronous_commit')")==[['remote_apply']],'stable write endpoint')
        if args.setup_only:
            print(json.dumps({'primary':leader,'setup':True}));return
        sql('CREATE TABLE IF NOT EXISTS cluster_acceptance (id integer PRIMARY KEY, payload text)')
        sql("INSERT INTO cluster_acceptance VALUES (1,'acknowledged-before-crash') ON CONFLICT (id) DO NOTHING")
        before=time.monotonic()
        run('docker','kill','--signal','KILL',leader)
        next_leader=wait(lambda:primary(excluding=leader),'automatic crash failover')
        wait(lambda:sql('SELECT payload FROM cluster_acceptance WHERE id=1')==[['acknowledged-before-crash']],'acknowledged SQL survived crash')
        sql("INSERT INTO cluster_acceptance VALUES (2,'new-write-after-crash') ON CONFLICT (id) DO NOTHING")
        crash_seconds=round(time.monotonic()-before,2)
        run('docker','start',leader)
        wait(ready_replicas,'former primary rejoined using WAL/rewind')
        # Lose every DCS/replication route of the old writer. Promotion is automatic.
        old=wait(primary,'writer before partition')
        run('docker','network','disconnect',NETWORK,old)
        before=time.monotonic()
        promoted=wait(lambda:primary(excluding=old),'automatic partition failover')
        wait(lambda:sql('SELECT count(*) FROM cluster_acceptance')==[[2]],'SQL retained after partition')
        sql("INSERT INTO cluster_acceptance VALUES (3,'new-write-after-partition') ON CONFLICT (id) DO NOTHING")
        partition_seconds=round(time.monotonic()-before,2)
        stale_rejected=False
        try:
            sql("INSERT INTO cluster_acceptance VALUES (999,'stale-writer')",host='localhost',container=old)
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired):
            stale_rejected=True
        assert stale_rejected,'isolated old writer acknowledged a transaction'
        # Reattach its original FQDN; Docker loses network aliases on disconnect.
        i=PG_NAMES.index(old)
        run('docker','network','connect','--alias',f'proof-cluster-postgres-{i}-0.proof-cluster-postgres.cluster-test.svc.cluster.local',NETWORK,old)
        wait(ready_replicas,'partitioned primary rejoined')
        assert sql('SELECT id FROM cluster_acceptance ORDER BY id')==[[1],[2],[3]]
        # DCS minority must fail closed instead of creating another leader.
        run('docker','stop',ETCD_NAMES[1],ETCD_NAMES[2])
        wait(lambda:all(status(x).get('role') not in ('primary','master') for x in PG_NAMES),'DCS quorum loss demotion',seconds=90)
        rejected=False
        try:sql("INSERT INTO cluster_acceptance VALUES (998,'no-quorum')")
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired):rejected=True
        assert rejected
        run('docker','start',ETCD_NAMES[1],ETCD_NAMES[2])
        wait(primary,'DCS recovery elects primary')
        wait(lambda:sql('SELECT id FROM cluster_acceptance ORDER BY id')==[[1],[2],[3]],'recovery retains acknowledged writes')
        report={'automatic_failover':True,'manual_promotion':False,'tls':True,'synchronous_commit':'remote_apply',
                'strict_sync':False,'automatic_degraded_allowed':True,'crash_acknowledged_sql_preserved':True,'new_write_after_crash':True,
                'network_partition_failover':True,'old_writer_rejected':True,'former_primary_rejoined':True,
                'dcs_quorum_loss_fails_closed':True,'dcs_recovery':True,'crash_seconds':crash_seconds,
                'partition_seconds':partition_seconds,'kernel_watchdog':'not tested in Docker',
                'postgres_image_id':run('docker','image','inspect',PG_IMAGE,'--format','{{.Id}}')}
        report['production_amd64_image_id']=run('docker','image','inspect',PG_PRIMARY_IMAGE,'--format','{{.Id}}')
        report['production_amd64_primary_bootstrap']=True
        report['production_amd64_cpu_emulation']=run('docker','info','--format','{{.Architecture}}') not in ('amd64','x86_64')
        report['sql_test_role']='trusted PostgreSQL superuser; Nexus application fencing tested separately'
        report['sql_test_database']='postgres; Nexus metadata/epoch guards are tested separately in running Core'
        (ROOT/'build/logs/cluster-postgres-r2-amd64-runtime.json').write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps(report,indent=2))
    finally:
        save_logs('runtime')

if __name__=='__main__':main()
