#!/usr/bin/env python3
"""Ordered minor-version PostgreSQL/etcd image updates with PVC retention.

Every target is explicit. PostgreSQL major versions and etcd minor-version
transitions require a separately tested data migration and are rejected here.
Nexus application updates use upgrade-cluster.py and its SQL release barrier.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT=Path(__file__).resolve().parents[1]

class Upgrade:
    def __init__(self,args):
        self.args=args
        self.k=['kubectl','--kubeconfig',str(args.kubeconfig),'--context',args.context,'-n',args.namespace]
        self.h=['helm','--kubeconfig',str(args.kubeconfig),'--kube-context',args.context,'-n',args.namespace]
        self.report={'context':args.context,'namespace':args.namespace,'release':args.release,'events':[],'volumes_deleted':False,'manual_promotion':False}
    def run(self,*argv,timeout=120):
        return subprocess.check_output(argv,text=True,stderr=subprocess.STDOUT,timeout=timeout).strip()
    def kube(self,*argv,timeout=120):return self.run(*self.k,*argv,timeout=timeout)
    def pods(self,component):
        selector='app.kubernetes.io/instance='+self.args.release+',app.kubernetes.io/component='+component
        return json.loads(self.kube('get','pods','-l',selector,'-o','json'))['items']
    def wait(self,fn,label,seconds=600):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            try:
                value=fn()
                if value:return value
            except (subprocess.CalledProcessError,subprocess.TimeoutExpired,ValueError,KeyError):pass
            time.sleep(3)
        raise RuntimeError('Timed out: '+label)
    def state(self,name):
        return json.loads(self.kube('exec',name,'-c','postgres','--','curl','-fsS','--max-time','4','--cacert','/run/nexus-postgres/tls/ca.crt','https://localhost:8008/patroni'))
    def healthy(self,component):
        pods=self.pods(component)
        if len(pods)<3:return False
        for pod in pods:
            if not any(c.get('type')=='Ready' and c.get('status')=='True' for c in pod['status'].get('conditions',[])):return False
        if component=='postgres':
            states={p['metadata']['name']:self.state(p['metadata']['name']) for p in pods}
            leaders=[x for x in states.values() if x.get('role') in ('primary','master')]
            if len(leaders)!=1 or not all(x.get('state')=='running' for x in states.values()):return False
            leader=leaders[0];dc=leader.get('tags',{}).get('dc')
            actual={peer.get('application_name') for peer in leader.get('replication',[]) if peer.get('state')=='streaming'
                and peer.get('sync_state')=='sync' and states.get(peer.get('application_name'),{}).get('tags',{}).get('dc') not in (None,dc)}
            if not actual:return False
            leader_name=next(name for name,state in states.items() if state.get('role') in ('primary','master'))
            cluster=json.loads(self.kube('exec',leader_name,'-c','postgres','--','curl','-fsS','--max-time','4',
                '--cacert','/run/nexus-postgres/tls/ca.crt','https://localhost:8008/cluster'))
            return any(member.get('name') in actual and member.get('role')=='sync_standby' for member in cluster.get('members',[]))
        return True
    def event(self,action,**values):
        self.report['events'].append({'action':action,'at':time.time(),**values})
        print(json.dumps(self.report['events'][-1]),flush=True)
        if self.args.report:
            self.args.report.parent.mkdir(parents=True,exist_ok=True)
            self.args.report.write_text(json.dumps(self.report,indent=2)+'\n')
    def validate_version(self,component,image,old_version):
        # No database, network credentials or PVC are mounted into the probe.
        name='nexus-infra-version-'+str(time.time_ns())
        command=['postgres','--version'] if component=='postgres' else ['/usr/local/bin/etcd','--version']
        pod={'apiVersion':'v1','kind':'Pod','metadata':{'name':name},'spec':{'restartPolicy':'Never','automountServiceAccountToken':False,
             'containers':[{'name':'version','image':image,'imagePullPolicy':'IfNotPresent','command':command,
             'resources':{'requests':{'cpu':'50m','memory':'32Mi'},'limits':{'cpu':'500m','memory':'128Mi'}},
             'securityContext':{'allowPrivilegeEscalation':False,'capabilities':{'drop':['ALL']},'readOnlyRootFilesystem':True}}]}}
        with tempfile.TemporaryDirectory(prefix='nexus-infra-version-') as directory:
            source=Path(directory)/'pod.json';source.write_text(json.dumps(pod))
            self.kube('create','-f',str(source))
        try:
            self.wait(lambda:json.loads(self.kube('get','pod',name,'-o','json'))['status'].get('phase') in ('Succeeded','Failed'),'image version probe',seconds=180)
            version=self.kube('logs',name)
        finally:self.kube('delete','pod',name,'--wait=false')
        old=re.search(r'(?:PostgreSQL\)|etcd Version:)\s*(\d+)\.(\d+)',old_version)
        new=re.search(r'(?:PostgreSQL\)|etcd Version:)\s*(\d+)\.(\d+)',version)
        if not old or not new:raise RuntimeError('Cannot determine '+component+' image version')
        if component=='postgres' and old.group(1)!=new.group(1):raise RuntimeError('PostgreSQL major upgrade requires a separate data migration')
        if component=='etcd' and old.groups()!=new.groups():raise RuntimeError('Etcd minor transition requires a separate tested migration')
        self.event('version_validated',component=component,new_version=version.splitlines()[0])
    def helm(self,component,image):
        values=json.loads(self.run(*self.h,'get','values',self.args.release,'-o','json')) or {}
        values.setdefault(component,{})['image']=image
        with tempfile.TemporaryDirectory(prefix='nexus-infra-values-') as directory:
            source=Path(directory)/'values.json';source.write_text(json.dumps(values))
            self.run(*self.h,'upgrade',self.args.release,str(self.args.chart),'-f',str(source),'--timeout','15m',timeout=950)
    def replace(self,component,pod,image):
        name=pod['metadata']['name'];uid=pod['metadata']['uid']
        self.kube('delete','pod',name,'--wait=false')
        def replaced():
            candidate=json.loads(self.kube('get','pod',name,'-o','json'))
            if candidate['metadata']['uid']==uid:return False
            if next(c for c in candidate['spec']['containers'] if c['name']==component)['image']!=image:return False
            return any(c.get('type')=='Ready' and c.get('status')=='True' for c in candidate['status'].get('conditions',[]))
        self.wait(replaced,'updated '+name)
        self.wait(lambda:self.healthy(component),'all '+component+' members healthy')
        self.event('member_updated',component=component,pod=name,image=image)
    def execute(self):
        for component,image in [('etcd',self.args.etcd_image),('postgres',self.args.postgres_image)]:
            if not image:continue
            self.wait(lambda:self.healthy(component),'healthy '+component+' preflight')
            pods=self.pods(component)
            command=['postgres','--version'] if component=='postgres' else ['/usr/local/bin/etcd','--version']
            old=self.kube('exec',pods[0]['metadata']['name'],'-c',component,'--',*command)
            self.validate_version(component,image,old)
            self.helm(component,image)
            if component=='postgres':
                # Replicas first; re-read roles before every deletion. A changed
                # leader is deferred rather than trusting an old role snapshot.
                pending={p['metadata']['name']:p for p in pods}
                while pending:
                    replicas=[name for name in pending if self.state(name).get('role')=='replica']
                    leader=next(self.state(p['metadata']['name']) for p in self.pods(component) if self.state(p['metadata']['name']).get('role') in ('primary','master'))
                    sync={peer['application_name'] for peer in leader.get('replication',[]) if peer.get('sync_state')=='sync'}
                    name=sorted(replicas,key=lambda candidate:(candidate in sync,candidate))[0] if replicas else sorted(pending)[0]
                    self.replace(component,pending.pop(name),image)
            else:
                for pod in sorted(pods,key=lambda p:p['metadata']['name']):self.replace(component,pod,image)
        self.report['completed']=True
        self.event('infrastructure_upgrade_complete')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kubeconfig',required=True,type=Path)
    parser.add_argument('--context',required=True)
    parser.add_argument('--namespace',required=True)
    parser.add_argument('--release',required=True)
    parser.add_argument('--postgres-image')
    parser.add_argument('--etcd-image')
    parser.add_argument('--chart',type=Path,default=ROOT/'deploy/helm/nexsteem')
    parser.add_argument('--report',type=Path)
    args=parser.parse_args()
    if not args.postgres_image and not args.etcd_image:parser.error('Specify at least one image')
    Upgrade(args).execute()

if __name__=='__main__':main()
