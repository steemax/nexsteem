#!/usr/bin/env python3
"""Provision only the isolated Kind integration cluster; exercise chart DB HA.

Application protocol/fault acceptance belongs to test-cluster-application.py.
No command ever uses the operator's default kubeconfig or current context.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
NAME='nexus-cluster-ha'
CONFIG=ROOT/'build/nexus-cluster-test.kubeconfig'
NAMESPACE='nexus-cluster-test'
RELEASE='cluster'
PREFIX='cluster-cluster'
NODE_IMAGE='kindest/node:v1.36.1@sha256:3489c7674813ba5d8b1a9977baea8a6e553784dab7b84759d1014dbd78f7ebd5'
PG_IMAGE='nexus-cluster-postgres:17-patroni4.1.5-r2'
ETCD_IMAGE='gcr.io/etcd-development/etcd:v3.6.14@sha256:a491baeaa0cb0c9cd89c0062ac44ece53886e3e5bddad18d2daf36678ce665b6'
ROUTER_IMAGE='haproxy:3.2-alpine@sha256:5d97434a423c2533cfeb42874d45cf6d69a840b60334582dfbfd5008e94c80be'

def run(*argv,timeout=300):
    return subprocess.check_output(argv,text=True,stderr=subprocess.STDOUT,timeout=timeout).strip()
def kube(*argv,timeout=300):
    return run('kubectl','--kubeconfig',str(CONFIG),'--context','kind-'+NAME,'-n',NAMESPACE,*argv,timeout=timeout)
def wait(fn,label,seconds=600):
    print(label,flush=True)
    deadline=time.monotonic()+seconds
    last=None
    while time.monotonic()<deadline:
        try:
            value=fn()
            if value:return value
        except (subprocess.CalledProcessError,json.JSONDecodeError,OSError) as error:last=type(error).__name__
        time.sleep(3)
    raise RuntimeError(label+' timeout ('+str(last)+')')
def pg_names():return [PREFIX+'-postgres-'+str(i)+'-0' for i in range(3)]
def pg_state(name):
    return json.loads(kube('exec',name,'--','curl','-fsS','--max-time','3','--cacert','/run/nexus-postgres/tls/ca.crt','https://localhost:8008/patroni'))
def primary(except_name=None):
    leaders=[]
    for name in pg_names():
        if name==except_name:continue
        try:
            if pg_state(name).get('role') in ('primary','master'):leaders.append(name)
        except subprocess.CalledProcessError:pass
    return leaders[0] if len(leaders)==1 else None
def healthy_database():
    states=[pg_state(name) for name in pg_names()]
    leaders=[(pg_names()[i],state) for i,state in enumerate(states) if state.get('role') in ('primary','master')]
    if len(leaders)!=1 or sum(x.get('role')=='replica' and x.get('state')=='running' for x in states)!=2:return False
    name,leader=leaders[0];dc=leader.get('tags',{}).get('dc')
    members={state.get('patroni',{}).get('name'):state for state in states}
    actual={peer.get('application_name') for peer in leader.get('replication',[]) if peer.get('state')=='streaming'
            and peer.get('sync_state')=='sync' and members.get(peer.get('application_name'),{}).get('tags',{}).get('dc') not in (None,dc)}
    if not actual:return False
    cluster=json.loads(kube('exec',name,'--','curl','-fsS','--max-time','3','--cacert','/run/nexus-postgres/tls/ca.crt','https://localhost:8008/cluster'))
    return any(member.get('name') in actual and member.get('role')=='sync_standby' for member in cluster.get('members',[]))
def kill_pg_container(pod):
    # Namespace PID1 ignores SIGKILL sent from the same PID namespace.
    # Signal its real PID from the owned kind node's parent PID namespace.
    status=json.loads(kube('get','pod',pod,'-o','json'))
    node=status['spec']['nodeName']
    if node not in run('kind','get','nodes','--name',NAME).splitlines():raise RuntimeError('Refusing to signal an unowned node')
    container=next(x for x in status['status']['containerStatuses'] if x['name']=='postgres')
    cid=container['containerID'].split('://',1)[1]
    info=json.loads(run('docker','exec',node,'crictl','inspect',cid))
    pid=int(info['info']['pid'])
    if pid<=1:raise RuntimeError('Invalid outer container PID')
    run('docker','exec',node,'kill','-KILL',str(pid))
    def restarted():
        candidate=json.loads(kube('get','pod',pod,'-o','json'))
        new=next((x.get('containerID') for x in candidate['status'].get('containerStatuses',[]) if x['name']=='postgres'),None)
        return new if new and new!=container['containerID'] else None
    new=wait(restarted,'actual PostgreSQL container restart after outer PID SIGKILL',seconds=90)
    return {'pod':pod,'outer_pid_signalled':pid,'signal':'SIGKILL','previous_container_id':container['containerID'],'restarted_container_id':new}
def sql(statement,database='nexus'):
    source="""import json, psycopg
from pathlib import Path
with psycopg.connect(host=%s,dbname=%s,user='postgres',password=Path('/run/nexus-postgres/secrets/postgres-password').read_text().strip(),sslmode='verify-full',sslrootcert='/run/nexus-postgres/tls/ca.crt',connect_timeout=3,autocommit=True) as c:
 r=c.execute(%s);print(json.dumps(r.fetchall() if r.description else []))
"""%(repr(PREFIX+'-postgres-rw.'+NAMESPACE+'.svc.cluster.local'),repr(database),repr(statement))
    # Any PG Pod can be a trusted client, including while another Pod is gone.
    failures=[]
    for name in pg_names():
        try:return json.loads(kube('exec',name,'--','/opt/patroni/bin/python','-c',source,timeout=20))
        except subprocess.CalledProcessError as failure:failures.append(failure)
    raise failures[-1]
def import_image(image,architecture,targets=None):
    targets=targets or run('kind','get','nodes','--name',NAME).splitlines()
    image_arch=run('docker','image','inspect',image,'--format','{{.Architecture}}')
    image_id=run('docker','image','inspect',image,'--format','{{.Id}}').split(':',1)[1]
    archive=ROOT/'build/cluster-import-image.tar'
    # Saving an @index-digest reference omits RepoTags from Docker's archive.
    # Use its verified local tag so containerd retains the deployment name.
    saved=image.split('@')[0]
    if run('docker','image','inspect',saved,'--format','{{.Id}}')!=run('docker','image','inspect',image,'--format','{{.Id}}'):
        raise RuntimeError('Local tag differs from the requested pinned image')
    run('docker','image','save','--platform','linux/'+image_arch,'--output',str(archive),saved)
    try:
        if image_arch!=architecture and (image_arch!='amd64' or architecture!='arm64'):raise RuntimeError('Unsupported emulation combination')
        for node in targets:
            # Stream from the host filesystem instead of consuming a second
            # archive-sized copy inside Docker's capacity-limited VM.
            with archive.open('rb') as source:
                subprocess.run(['docker','exec','-i',node,'ctr','--namespace','k8s.io','images','import','--all-platforms','--no-unpack','-'],stdin=source,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True,timeout=300)
            if image_arch!=architecture:
                mount_directory='/var/lib/nexus-cluster-rootfs-'+image_id[:16]
                run('docker','exec',node,'mkdir','-p',mount_directory)
                repository=image if '/' in image else 'docker.io/library/'+image
                run('docker','exec',node,'ctr','--namespace','k8s.io','images','mount','--platform','linux/amd64',repository,mount_directory,timeout=300)
                run('docker','exec',node,'ctr','--namespace','k8s.io','images','unmount','--rm',mount_directory)
    finally:archive.unlink(missing_ok=True)
def install(image,architecture,application,minimal=False,allow_http_oidc=False):
    # Assert the delivered schema is identical to the optional module resources.
    for module,filename in [('cluster-control','control-bootstrap.sql'),('cluster-storage','storage-bootstrap.sql')]:
        source=ROOT/'extensions'/module/'src/main/resources/sql'/('cluster-control.sql' if module=='cluster-control' else 'cluster-storage.sql')
        target=ROOT/'deploy/helm/nexsteem/files'/filename
        if source.read_bytes()!=target.read_bytes():raise RuntimeError('Chart bootstrap SQL is stale: '+filename)
    nodes=[{'dc':'dc1','nodeSelector':{'nexus.cluster/dc':'dc1'}},
           {'dc':'dc2','nodeSelector':{'nexus.cluster/dc':'dc2'}},
           {'dc':'dc2','nodeSelector':{'nexus.cluster/dc':'dc2'}}]
    etcd_nodes=[nodes[0],nodes[1],{'dc':'witness','nodeSelector':{'nexus.cluster/dc':'witness'}}]
    nexus_nodes=nodes[:2] if minimal else nodes
    values={'nodeSelector':{'kubernetes.io/arch':architecture},'etcd':{'image':ETCD_IMAGE.split('@')[0],'nodes':etcd_nodes,'pullPolicy':'Never','storageSize':'512Mi'},
            'postgres':{'image':PG_IMAGE,'nodes':nodes,'pullPolicy':'Never','storageSize':'2Gi','resources':{'requests':{'cpu':'100m','memory':'192Mi'},'limits':{'cpu':'2','memory':'768Mi'}}},
            'router':{'image':ROUTER_IMAGE.split('@')[0],'pullPolicy':'Never'},'nexus':{'enabled':application,'image':image,'replicas':len(nexus_nodes),'nodes':nexus_nodes,'pullPolicy':'Never','storageSize':'2Gi','javaOpts':'-Xms256m -Xmx1g -XX:MaxDirectMemorySize=512m',
            'resources':{'requests':{'cpu':'100m','memory':'384Mi'},'limits':{'cpu':'3','memory':'2Gi'}}},
            'service':{'type':'NodePort','nodePort':30081,'dockerPorts':[{'port':8090,'corePort':8090,'nodePort':30090}]},'networkPolicy':{'enabled':False}}
    if minimal:values['placement']={'requiredAntiAffinity':False}
    if allow_http_oidc:values['nexus']['javaOpts']+=' -Dnexus.oidc.allow-http=true'
    # kindnet does not enforce NetworkPolicy. Production chart enables it.
    path=ROOT/'build/nexus-cluster-test-values.json';path.write_text(json.dumps(values,indent=2))
    run('helm','upgrade','--install',RELEASE,str(ROOT/'deploy/helm/nexsteem'),'--kubeconfig',str(CONFIG),
        '--kube-context','kind-'+NAME,'-n',NAMESPACE,'--create-namespace','-f',str(path))
    # Verify the DB/DCS explicitly before checking application membership.
    kube('wait','--for=condition=complete','job/'+PREFIX+'-etcd-auth-'+str(json.loads(run('helm','status',RELEASE,'--kubeconfig',str(CONFIG),'--kube-context','kind-'+NAME,'-n',NAMESPACE,'-o','json'))['version']),'--timeout=400s',timeout=420)
    wait(healthy_database,'three PG nodes healthy with a DCS-selected remote sync replica',seconds=600)
    wait(lambda:sql("select count(*) from pg_tables where tablename='nx_cluster_leader'")==[[1]],'control schema installed')
    if application:
        ensure_docker_gateway()
        for i in range(len(nexus_nodes)):
            kube('wait','--for=condition=Ready','pod/'+PREFIX+'-nexus-'+str(i)+'-0','--timeout=600s',timeout=620)

def ensure_docker_gateway():
    name='nexus-cluster-docker-gateway'
    existing=run('docker','ps','-a','--format','{{.Names}}').splitlines()
    if name in existing:
        owner=run('docker','inspect',name,'--format','{{index .Config.Labels "io.nexus.fixture"}}')
        if owner!=NAME:raise RuntimeError('Docker gateway fixture label does not match this suite')
        if run('docker','inspect',name,'--format','{{.State.Running}}')=='true':return
        run('docker','rm',name)
    configuration=ROOT/'build/nexus-cluster-docker-gateway.cfg'
    configuration.write_text('''global
  log stdout format raw local0
defaults
  mode tcp
  timeout connect 5s
  timeout client 10m
  timeout server 10m
frontend docker
  bind :8090
  default_backend kind_docker
backend kind_docker
  server witness nexus-cluster-ha-control-plane:30090 check
''')
    run('docker','run','-d','--name',name,'--label','io.nexus.fixture='+NAME,'--network','kind',
        '--publish','127.0.0.1:18151:8090','--mount',f'type=bind,src={configuration},dst=/usr/local/etc/haproxy/haproxy.cfg,readonly',
        ROUTER_IMAGE.split('@')[0],'-W','-db','-f','/usr/local/etc/haproxy/haproxy.cfg')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image',default='nexus-core-cluster:3.96.4-01-k8s8')
    parser.add_argument('--reuse-cluster',action='store_true')
    parser.add_argument('--database-only',action='store_true')
    parser.add_argument('--setup-only',action='store_true')
    parser.add_argument('--minimal-fixture',action='store_true',help='Three kind nodes and two Nexus members; collocate the two DC2 PostgreSQL Pods only in this local test profile')
    parser.add_argument('--allow-http-oidc',action='store_true',help='Allow an HTTP-only integration identity-provider fixture in this local test; production remains HTTPS-only')
    args=parser.parse_args()
    exists=NAME in run('kind','get','clusters').splitlines()
    if exists and not args.reuse_cluster:raise RuntimeError('Owned cluster exists: inspect before --reuse-cluster')
    CONFIG.parent.mkdir(exist_ok=True)
    if not exists:
        configuration=ROOT/'build/nexus-cluster-kind.yaml'
        kind_config='''kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    labels: {nexus.cluster/dc: witness}
    extraPortMappings:
      - {containerPort: 30081, hostPort: 18150, listenAddress: "127.0.0.1"}
  - role: worker
    labels: {nexus.cluster/dc: dc1}
  - role: worker
    labels: {nexus.cluster/dc: dc2}
'''
        if not args.minimal_fixture:kind_config+='  - role: worker\n    labels: {nexus.cluster/dc: dc2}\n'
        configuration.write_text(kind_config)
        print('Creating isolated '+str(3 if args.minimal_fixture else 4)+'-node kind cluster',flush=True)
        run('kind','create','cluster','--name',NAME,'--image',NODE_IMAGE,'--config',str(configuration),'--kubeconfig',str(CONFIG),'--wait','180s',timeout=300)
        CONFIG.chmod(0o600)
        kube('taint','nodes',NAME+'-control-plane','node-role.kubernetes.io/control-plane:NoSchedule-')
    architecture=json.loads(kube('get','nodes','-o','json'))['items'][0]['status']['nodeInfo']['architecture']
    print('Loading PostgreSQL/etcd/router images',flush=True)
    for image in [PG_IMAGE,ETCD_IMAGE,ROUTER_IMAGE]:import_image(image,architecture)
    if not args.database_only:
        workers=[x for x in run('kind','get','nodes','--name',NAME).splitlines() if 'control-plane' not in x]
        import_image(args.image,architecture,targets=workers)
    install(args.image,architecture,not args.database_only,minimal=args.minimal_fixture,allow_http_oidc=args.allow_http_oidc)
    initial=wait(primary,'one primary before acceptance')
    if args.setup_only:
        print(json.dumps({'setup':True,'namespace':NAMESPACE,'kubeconfig':str(CONFIG),'context':'kind-'+NAME,'primary':initial,'application':not args.database_only},indent=2));return
    infra=lambda statement:sql(statement,database='postgres')
    infra('CREATE TABLE IF NOT EXISTS cluster_infra_acceptance(id integer PRIMARY KEY,payload text)')
    infra("INSERT INTO cluster_infra_acceptance VALUES(1,'confirmed-before-crash') ON CONFLICT(id) DO NOTHING")
    started=time.monotonic()
    # Kill the actual container process; restart policy is automatic, no promotion API.
    crash=kill_pg_container(initial)
    # RestartAlways may recover the same primary before its DCS lease expires.
    # A changed primary is a takeover; a real new container on the same primary
    # is crash recovery. Sustained DC outages exercise takeover separately.
    new=wait(primary,'automatic PostgreSQL crash recovery or takeover',seconds=180)
    wait(lambda:infra('SELECT payload FROM cluster_infra_acceptance WHERE id=1')==[['confirmed-before-crash']],'confirmed SQL retained')
    infra("INSERT INTO cluster_infra_acceptance VALUES(2,'confirmed-after-crash') ON CONFLICT(id) DO NOTHING")
    wait(healthy_database,'all PostgreSQL nodes rejoined')
    # Report actual sync topology instead of presenting local sync as cross-DC.
    current=wait(primary,'current primary')
    state=pg_state(current)
    report={'kind_context':'kind-'+NAME,'node_count':3 if args.minimal_fixture else 4,'minimal_fixture':args.minimal_fixture,'node_architecture':architecture,
            'postgres_image_architecture':run('docker','image','inspect',PG_IMAGE,'--format','{{.Architecture}}'),
            'postgres_auto_crash_failover':new!=initial,'postgres_auto_crash_recovery':True,'actual_crash':crash,
            'manual_promotion':False,'confirmed_sql_survived':True,'new_write_after_crash':True,
            'infrastructure_sql_database':'postgres; actual Nexus metadata/artifacts verified separately',
            'crash_seconds':round(time.monotonic()-started,2),'current_primary':current,'actual_replication':state.get('replication',[]),
            'dc':state.get('tags',{}).get('dc'),'application_acceptance':'separate test-cluster-application.py',
            'network_policy_runtime':'kindnet does not enforce NetworkPolicy; test profile explicitly disables it'}
    (ROOT/'build/logs/cluster-kubernetes-infra-runtime.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2),flush=True)

if __name__=='__main__':main()
