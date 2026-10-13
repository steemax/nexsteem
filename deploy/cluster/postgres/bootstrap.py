#!/opt/patroni/bin/python
"""Patroni post_init, run once on the initial leader before accepting traffic."""
from pathlib import Path
import sys
import psycopg
from psycopg import sql

password = (Path('/run/nexus-postgres/secrets')/'nexus-password').read_text().strip()
control_password = (Path('/run/nexus-postgres/secrets')/'control-password').read_text().strip()
with psycopg.connect(sys.argv[1], autocommit=True) as connection:
    connection.execute(sql.SQL('CREATE ROLE nexus LOGIN PASSWORD {}').format(sql.Literal(password)))
    # Preserve acknowledged transactions; this is also the server default.
    connection.execute("ALTER ROLE nexus SET synchronous_commit TO 'remote_apply'")
    connection.execute('CREATE DATABASE nexus OWNER nexus')
    connection.execute(sql.SQL('CREATE ROLE nxcontrol LOGIN PASSWORD {}').format(sql.Literal(control_password)))
    connection.execute('GRANT CONNECT ON DATABASE nexus TO nxcontrol')
    dsn = psycopg.conninfo.conninfo_to_dict(sys.argv[1])
    dsn['dbname'] = 'nexus'
    ddl = Path('/etc/nexus-postgres/control.sql')
    if ddl.exists() and ddl.read_text().strip():
        with psycopg.connect(**dsn, autocommit=True) as nexus:
            nexus.execute(ddl.read_text())
