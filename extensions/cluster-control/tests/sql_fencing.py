#!/usr/bin/env python3
"""Real PostgreSQL admission/commit/DDL fencing acceptance; no mocked DB semantics."""
import subprocess
import time

CONTAINER = 'nexus-cluster-control-sql'
APP_HASH='a'*64


def command(role, sql):
    return ['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-U', role, '-d', 'nexus',
            '-v', 'ON_ERROR_STOP=1', '-A', '-t', '-c', sql]


def sql(role, text, denied=False):
    result = subprocess.run(command(role, text), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    if denied:
        assert result.returncode != 0 and 'NX_CLUSTER_' in result.stdout, result.stdout
    else:
        assert result.returncode == 0, result.stdout
    return result.stdout.strip()


def acquire(owner, seconds=30):
    statement=f"SELECT nx_cluster_try_acquire('proof','{owner}','http://member:8081','etcd-revision-proof',clock_timestamp()+interval '50 seconds',{seconds},'{APP_HASH}','test-v1');"
    return int(sql('nxcontrol',statement).splitlines()[-1])


def bind(owner, epoch):
    return "SELECT nx_cluster_bind('proof','" + owner + "'," + str(epoch) + ');'


epoch = acquire('owner-a')
sql('nxcontrol', "SELECT nx_cluster_phase('proof','owner-a'," + str(epoch) + ",'STARTING');")
sql('nexus', bind('owner-a', epoch) + 'CREATE TABLE core_fence_proof(id int PRIMARY KEY,value text); INSERT INTO core_fence_proof VALUES(1,\'first\');')
assert sql('postgres', 'SELECT count(*) FROM nx_cluster_tx_guard;') == '0'
sql('nexus', "INSERT INTO core_fence_proof VALUES(2,'unbound');", denied=True)
assert 'nx_cluster_statement_guard' in sql('postgres', "SELECT tgname FROM pg_trigger WHERE tgrelid='core_fence_proof'::regclass;")
print('PASS actual Core role bound DDL/DML, new-table trigger, unbound rejection and zero guard-row accumulation', flush=True)

writer = subprocess.Popen(command('nexus', "BEGIN;" + bind('owner-a', epoch) + "UPDATE core_fence_proof SET value='during-heartbeat' WHERE id=1; SELECT pg_sleep(3); COMMIT;"), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(.5)
start = time.monotonic()
assert sql('nxcontrol', "SELECT nx_cluster_renew('proof','owner-a'," + str(epoch) + ",'new-proof',clock_timestamp()+interval '50 seconds',30,'" + APP_HASH + "');") == 't'
assert time.monotonic() - start < 1.5
output, _ = writer.communicate(timeout=10)
assert writer.returncode == 0, output
print('PASS heartbeat remains unblocked while a mutator holds shared fencing locks', flush=True)

# Deferred commit checks DB time, not the admission-time lease snapshot.
sql('postgres', "UPDATE nx_cluster_lease SET lease_until=clock_timestamp()+interval '2 seconds' WHERE cluster_id='proof';")
sql('nexus', "BEGIN;" + bind('owner-a', epoch) + "UPDATE core_fence_proof SET value='must-rollback' WHERE id=1; SELECT pg_sleep(3); COMMIT;", denied=True)
assert sql('postgres', 'SELECT value FROM core_fence_proof WHERE id=1;') == 'during-heartbeat'
assert sql('nxcontrol', "SELECT nx_cluster_renew('proof','owner-a'," + str(epoch) + ",'late-proof',clock_timestamp()+interval '50 seconds',30,'" + APP_HASH + "');") == 'f'
print('PASS expired commit rolls back and expired lease cannot be resurrected by renewal', flush=True)

next_epoch = acquire('owner-b')
assert next_epoch > epoch
sql('nxcontrol', "SELECT nx_cluster_phase('proof','owner-b'," + str(next_epoch) + ",'STARTING');")
sql('nexus', bind('owner-a', epoch) + "UPDATE core_fence_proof SET value='stale';", denied=True)
sql('nexus', bind('owner-b', next_epoch) + "UPDATE core_fence_proof SET value='new-owner' WHERE id=1;")
print('PASS takeover advances epoch and the former fixed owner/epoch cannot bind or write', flush=True)

index_command=command('nexus','CREATE INDEX CONCURRENTLY core_fence_value ON core_fence_proof(value);')
index_command[3:3]=['-e','PGOPTIONS=-c nexus.cluster.id=proof -c nexus.cluster.owner=owner-b -c nexus.cluster.epoch='+str(next_epoch)]
result=subprocess.run(index_command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
assert result.returncode==0,result.stdout
drop_command=command('nexus','DROP INDEX CONCURRENTLY core_fence_value;')
drop_command[3:3]=['-e','PGOPTIONS=-c nexus.cluster.id=proof -c nexus.cluster.owner=owner-b -c nexus.cluster.epoch='+str(next_epoch)]
result=subprocess.run(drop_command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
assert result.returncode==0,result.stdout
assert sql('postgres', "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND granted AND mode='ShareLock';") == '0'
print('PASS CREATE and DROP INDEX CONCURRENTLY remain compatible and release session fencing locks', flush=True)

sql('nxcontrol', "SELECT nx_cluster_set_policy('proof','owner-b'," + str(next_epoch) + ",1,false,true,'linear-dcs-fence-proof');")
assert sql('postgres', "SELECT required_copies||':'||degraded FROM nx_cluster_policy WHERE cluster_id='proof';") == '1:true'
sql('nxcontrol', "SELECT nx_cluster_set_policy('proof','owner-b'," + str(next_epoch) + ",1,false,true,'');", denied=True)
print('PASS degraded policy requires an authoritative nonempty fencing proof', flush=True)

writer = subprocess.Popen(command('nexus', "BEGIN;" + bind('owner-b', next_epoch) + "UPDATE core_fence_proof SET value='killed-owner' WHERE id=1; SELECT pg_sleep(30); COMMIT;"), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(.5)
sql('postgres', "UPDATE nx_cluster_lease SET lease_until=clock_timestamp()-interval '1 second' WHERE cluster_id='proof';")
start=time.monotonic()
assert int(sql('nxcontrol', "SELECT nx_cluster_fence_expired('proof');")) >= 1
output, _ = writer.communicate(timeout=5)
assert writer.returncode != 0, output
assert time.monotonic()-start < 3
assert sql('postgres', 'SELECT value FROM core_fence_proof WHERE id=1;') == 'new-owner'
final_epoch=acquire('owner-c')
assert final_epoch > next_epoch
sql('nxcontrol', "SELECT nx_cluster_phase('proof','owner-c'," + str(final_epoch) + ",'STARTING');")
print('PASS expired long transaction is terminated, rolled back, and drained before takeover', flush=True)
next_hash='b'*64
sql('nxcontrol', f"SELECT nx_cluster_release_set('proof','wrong','{next_hash}','test-v2','new-proof',clock_timestamp()+interval '50 seconds');", denied=True)
sql('nxcontrol', f"SELECT nx_cluster_release_set('proof','{APP_HASH}','{next_hash}','test-v2','new-proof',clock_timestamp()+interval '50 seconds');")
sql('nexus', bind('owner-c', final_epoch)+"UPDATE core_fence_proof SET value='old-code';", denied=True)
assert sql('nxcontrol', f"SELECT nx_cluster_try_acquire('proof','old-code','http://member:8081','proof',clock_timestamp()+interval '50 seconds',30,'{APP_HASH}','test-v1');") == ''
release_epoch=int(sql('nxcontrol', f"SELECT nx_cluster_try_acquire('proof','new-code','http://member:8081','proof',clock_timestamp()+interval '50 seconds',30,'{next_hash}','test-v2');"))
assert release_epoch>final_epoch
sql('nxcontrol', f"SELECT nx_cluster_phase('proof','new-code',{release_epoch},'STARTING');")
assert sql('postgres', 'SELECT value FROM core_fence_proof WHERE id=1;') == 'new-owner'
print('PASS release barrier atomically expires old lease and refuses old application hash after migration handoff', flush=True)
sql('nexus', bind('new-code', release_epoch) + 'TRUNCATE core_fence_proof; DROP TABLE core_fence_proof;')
print('PASS PostgreSQL SQL/DDL fencing acceptance completed', flush=True)
