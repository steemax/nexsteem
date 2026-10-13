#!/usr/bin/env python3
"""Run inside the isolated audit Job, with only read-only proof credentials."""
import base64
import json
from pathlib import Path
import os
import ssl
import urllib.error
import urllib.request

ctx=ssl.create_default_context(cafile='/run/proof/tls/ca.crt')
ctx.load_cert_chain('/run/proof/tls/tls.crt','/run/proof/tls/tls.key')
endpoint=os.environ['ENDPOINT']
token=None
def call(path,body):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']=token
    request=urllib.request.Request(endpoint+'/v3/'+path,data=json.dumps(body).encode(),headers=headers)
    with urllib.request.urlopen(request,context=ctx,timeout=5) as response:return json.load(response)
def b64(value):return base64.b64encode(value).decode()
token=call('auth/authenticate',{'name':'nx-proof','password':Path('/run/proof/secrets/password').read_text().strip()})['token']
prefix=os.environ['SCOPE'].encode();end=prefix[:-1]+bytes([prefix[-1]+1])
snapshot=call('kv/range',{'key':b64(prefix),'range_end':b64(end),'serializable':False})
entries={base64.b64decode(x['key']).decode()[len(prefix):]:x for x in snapshot.get('kvs',[])}
leader=entries['leader'];lease=call('lease/timetolive',{'ID':leader['lease'],'keys':True})
assert int(lease['TTL'])>0
assert b64(prefix+b'leader') in lease.get('keys',[])
denied=False
try:call('kv/put',{'key':b64(prefix+b'acceptance-forbidden-write'),'value':b64(b'forbidden')})
except urllib.error.HTTPError as error:
    result=json.loads(error.read());denied=error.code==403 or result.get('code')==7
assert denied,'read-only proof user was able to write coordination state'
print(json.dumps({'linearizable_snapshot_revision':snapshot['header']['revision'],
                  'leader':base64.b64decode(leader['value']).decode(),
                  'lease_ttl_seconds':int(lease['TTL']),'write_denied':True,
                  'tls_client_common_name':'empty; identity comes from explicit authenticated ACL user'}))
