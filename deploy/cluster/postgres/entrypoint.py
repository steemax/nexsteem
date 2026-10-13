#!/usr/bin/env python3
"""Keep passwords out of command lines/environment and render Patroni in tmpfs."""
import os
from pathlib import Path
import subprocess
import yaml

def secret(name):
    return (Path('/run/nexus-postgres/secrets') / name).read_text().strip()

config = yaml.safe_load(Path('/etc/nexus-postgres/patroni.yaml').read_text())
# Kubernetes projected keys and local Docker bind files have different owners.
# PostgreSQL requires a private key owned by itself (0600) or root (0640).
private_key = Path('/tmp/postgres-tls.key')
private_key.write_bytes(Path('/run/nexus-postgres/tls/tls.key').read_bytes())
private_key.chmod(0o600)
config['restapi']['keyfile'] = str(private_key)
config['bootstrap']['dcs']['postgresql']['parameters']['ssl_key_file'] = str(private_key)
config['postgresql'].setdefault('parameters', {})['ssl_key_file'] = str(private_key)
name = os.environ['POD_NAME']
address = name + '.' + os.environ['PG_HEADLESS']
config['name'] = name
if (Path('/run/nexus-postgres/secrets')/'etcd-patroni-password').exists():
    config['etcd3']['username'] = 'patroni'
    config['etcd3']['password'] = secret('etcd-patroni-password')
config['restapi']['connect_address'] = address + ':8008'
config['restapi']['authentication'] = {'username': 'patroni', 'password': secret('patroni-password')}
config['postgresql']['connect_address'] = address + ':5432'
config['postgresql']['parameters']['nx_cluster.node_id'] = name
config['postgresql']['parameters']['nx_cluster.dc'] = os.environ.get('PG_DC', 'dc1')
config.setdefault('tags', {})['dc'] = os.environ.get('PG_DC', 'dc1')
config['postgresql']['authentication'] = {
    'superuser': {'username': 'postgres', 'password': secret('postgres-password')},
    'replication': {'username': 'replicator', 'password': secret('replication-password'),
                    'sslmode': 'verify-full', 'sslrootcert': '/run/nexus-postgres/tls/ca.crt'},
    'rewind': {'username': 'rewind', 'password': secret('rewind-password'),
               'sslmode': 'verify-full', 'sslrootcert': '/run/nexus-postgres/tls/ca.crt'},
}
destination = Path('/tmp/patroni.yaml')
destination.write_text(yaml.safe_dump(config))
destination.chmod(0o600)
if os.environ.get('PG_PREFER_REMOTE_SYNC','true').lower()=='true' and 'username' in config['etcd3']:
    subprocess.Popen(['/opt/patroni/bin/python','/opt/nexus-postgres/sync-policy.py'])
os.execv('/opt/patroni/bin/patroni', ['patroni', str(destination)])
