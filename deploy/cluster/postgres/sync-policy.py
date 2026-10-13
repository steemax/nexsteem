#!/opt/patroni/bin/python
"""Prefer a healthy standby in another DC, using an authoritative DCS snapshot.

This changes only Patroni's local nosync tag. Patroni retains responsibility for
durable synchronous membership and promotion. No process promotes PostgreSQL.
"""
import base64
import json
import os
from pathlib import Path
import signal
import ssl
import time
import urllib.request
from urllib.parse import urlsplit
import yaml

configuration=Path('/tmp/patroni.yaml')
initial=yaml.safe_load(configuration.read_text())
name=initial['name'];dc=initial['tags']['dc']
prefix=(initial['namespace'].rstrip('/')+'/'+initial['scope']+'/').encode()
end=prefix[:-1]+bytes([prefix[-1]+1])
dcs=ssl.create_default_context(cafile=initial['etcd3']['cacert'])
dcs.load_cert_chain(initial['etcd3']['cert'],initial['etcd3']['key'])
pg=ssl.create_default_context(cafile='/run/nexus-postgres/tls/ca.crt')
endpoints=['https://'+host for host in initial['etcd3']['hosts'].split(',')]
token=None
def call(endpoint,path,body):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']=token
    with urllib.request.urlopen(urllib.request.Request(endpoint+'/v3/'+path,data=json.dumps(body).encode(),headers=headers),context=dcs,timeout=2) as response:
        return json.load(response)
def snapshot():
    global token
    for endpoint in endpoints:
        try:
            if not token:
                token=call(endpoint,'auth/authenticate',{'name':initial['etcd3']['username'],'password':initial['etcd3']['password']})['token']
            answer=call(endpoint,'kv/range',{'key':base64.b64encode(prefix).decode(),'range_end':base64.b64encode(end).decode(),'serializable':False})
            return {base64.b64decode(kv['key']).decode()[len(prefix):]:base64.b64decode(kv['value']).decode() for kv in answer.get('kvs',[])}
        except Exception:
            token=None
    return None
def healthy(member):
    if member.get('state')!='running' or member.get('role')!='replica':return False
    # Patroni advertises api_url ending in /patroni. Appending /health would
    # dispatch the generic status handler, which can return 200 for stopped PG.
    url=urlsplit(member.get('api_url',''))._replace(path='/health',query='',fragment='').geturl()
    try:
        with urllib.request.urlopen(url,context=pg,timeout=1) as response:
            status=json.load(response)
            return response.status==200 and status.get('state')=='running' and status.get('role')=='replica'
    except Exception:return False

last=None
while True:
    try:
        state=snapshot()
        if state and state.get('leader'):
            leader_name=state['leader']
            leader=json.loads(state.get('members/'+leader_name,'{}'))
            leader_dc=leader.get('tags',{}).get('dc')
            if leader_dc:
                remote=any(json.loads(value).get('tags',{}).get('dc') not in (None,leader_dc) and healthy(json.loads(value))
                           for key,value in state.items() if key.startswith('members/'))
                excluded=name!=leader_name and dc==leader_dc and remote
                if excluded!=last:
                    current=yaml.safe_load(configuration.read_text())
                    current['tags']['nosync']=excluded
                    temporary=configuration.with_suffix('.new')
                    temporary.write_text(yaml.safe_dump(current));temporary.chmod(0o600)
                    temporary.replace(configuration)
                    os.kill(1,signal.SIGHUP)
                    last=excluded
                    print('Cross-DC sync policy: nosync='+str(excluded),flush=True)
    except Exception:
        # A failed authority read does not change eligibility or promote anyone.
        pass
    time.sleep(3)
