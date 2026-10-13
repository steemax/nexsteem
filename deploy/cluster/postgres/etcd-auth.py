#!/opt/patroni/bin/python
"""Idempotent etcd ACL bootstrap over authenticated HTTPS; no secret logging."""
import base64
import json
import os
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.request

context=ssl.create_default_context(cafile='/run/nexus-postgres/etcd/ca.crt')
context.load_cert_chain('/run/nexus-postgres/etcd/tls.crt','/run/nexus-postgres/etcd/tls.key')
endpoints=json.loads(os.environ['ETCD_ENDPOINTS'])
scope=os.environ['ETCD_SCOPE'].encode()
prefix_end=scope[:-1]+bytes([scope[-1]+1])
token=None
def b64(value):return base64.b64encode(value).decode()
def password(name):return (Path('/run/nexus-postgres/secrets')/name).read_text().strip()
def call(path,body):
    error=None
    for endpoint in endpoints:
        try:
            headers={'Content-Type':'application/json'}
            if token:headers['Authorization']=token
            request=urllib.request.Request(endpoint+'/v3/'+path,data=json.dumps(body).encode(),headers=headers)
            with urllib.request.urlopen(request,context=context,timeout=5) as response:
                result=json.load(response)
                if 'error' in result:raise RuntimeError(result['error'])
                return result
        except (OSError,RuntimeError) as failure:error=failure
    raise RuntimeError('etcd bootstrap request failed: '+path) from error
def ensure(path,body):
    try:return call(path,body)
    except RuntimeError as error:
        cause=error.__cause__
        if isinstance(cause,urllib.error.HTTPError):
            # Ignore only the deterministic already-exists condition.
            message=json.loads(cause.read()).get('message','')
            if 'already exists' in message:return {}
        raise

for attempt in range(120):
    try:
        try:token=call('auth/authenticate',{'name':'root','password':password('etcd-root-password')})['token']
        except RuntimeError:
            ensure('auth/user/add',{'name':'root','password':password('etcd-root-password')})
            ensure('auth/role/add',{'name':'root'})
            call('auth/user/grant',{'user':'root','role':'root'})
        for name,perm,key,range_end in [('patroni',2,scope,prefix_end),('proof',0,scope,prefix_end),('health',0,b'health',b'')]:
            ensure('auth/role/add',{'name':name})
            call('auth/role/grant',{'name':name,'perm':{'permType':perm,'key':b64(key),'range_end':b64(range_end)}})
        for name,key,roles in [('patroni','etcd-patroni-password',['patroni','health']),
                               ('nx-proof','etcd-proof-password',['proof','health']),
                               (os.environ['ETCD_CERT_USER'],'etcd-patroni-password',['patroni','health'])]:
            ensure('auth/user/add',{'name':name,'password':password(key)})
            for role in roles:call('auth/user/grant',{'user':name,'role':role})
        call('auth/enable',{})
        print('etcd scoped authentication ready')
        break
    except RuntimeError:
        if attempt==119:raise
        time.sleep(2)
