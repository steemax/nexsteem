"""Idempotent extension schema upgrade; no roles, databases or Core data reset."""
import base64
import hashlib
import json
import os
from pathlib import Path
import ssl
import time
import urllib.error
import urllib.request
import psycopg

prefix=os.environ['ETCD_SCOPE'].encode()
endpoints=json.loads(os.environ['ETCD_ENDPOINTS'])
context=ssl.create_default_context(cafile='/run/proof/tls/ca.crt')
context.load_cert_chain('/run/proof/tls/tls.crt','/run/proof/tls/tls.key')
token=None
def call(endpoint,path,body):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']=token
    request=urllib.request.Request(endpoint+'/v3/'+path,data=json.dumps(body).encode(),headers=headers)
    with urllib.request.urlopen(request,context=context,timeout=3) as response:return json.load(response)
def authority():
    global token
    key=prefix+b'leader'
    for endpoint in endpoints:
        try:
            if not token:token=call(endpoint,'auth/authenticate',{'name':'nx-proof','password':Path('/run/proof/secrets/etcd-proof-password').read_text().strip()})['token']
            result=call(endpoint,'kv/range',{'key':base64.b64encode(key).decode(),'serializable':False})
            leader=result['kvs'][0]
            lease=call(endpoint,'lease/timetolive',{'ID':leader['lease'],'keys':True})
            if int(lease.get('TTL',0))<=3 or base64.b64encode(key).decode() not in lease.get('keys',[]):raise RuntimeError('No live PG authority')
            return base64.b64decode(leader['value']).decode(),leader['lease'],result['header']['revision']
        except Exception:token=None
    raise RuntimeError('DCS majority unavailable')

schema=Path('/etc/nexus-postgres/control.sql').read_text()
for attempt in range(120):
    try:
        before=authority()
        with psycopg.connect(host=os.environ['PG_HOST'],dbname='nexus',user='postgres',
             password=Path('/run/proof/secrets/postgres-password').read_text().strip(),
             sslmode='verify-full',sslrootcert='/run/proof/pg/ca.crt',connect_timeout=3,
             options='-c lock_timeout=10000 -c statement_timeout=120000') as connection:
            node,recovery=connection.execute("SELECT current_setting('nx_cluster.node_id'),pg_is_in_recovery()").fetchone()
            if recovery or node!=before[0]:raise RuntimeError('PG connection is not DCS primary')
            connection.execute(schema,prepare=False)
            if authority()[:2]!=before[:2]:raise RuntimeError('PG authority changed before schema commit')
            connection.commit()
            if authority()[:2]!=before[:2]:raise RuntimeError('PG authority changed after schema commit; retry on current primary')
        print(json.dumps({'schema_sha256':hashlib.sha256(schema.encode()).hexdigest(),'pg_primary':before[0],'authority_revision':before[2],'schema_applied':True}),flush=True)
        break
    except Exception as error:
        # Never print DSN, passwords, authorization tokens or raw SQL exceptions.
        print('Waiting for safe schema upgrade: '+type(error).__name__,flush=True)
        if attempt==119:raise SystemExit('Schema upgrade did not complete')
        time.sleep(3)
