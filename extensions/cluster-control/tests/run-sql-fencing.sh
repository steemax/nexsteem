#!/bin/sh
# Isolated PostgreSQL semantics test; no Nexus/Core/HA production data is accessed.
set -eu
cd "$(dirname "$0")/../../.."
container=nexus-cluster-control-sql
if docker container inspect "$container" >/dev/null 2>&1; then
  echo "The isolated test container already exists: $container" >&2
  exit 1
fi
cleanup() { docker stop "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker run --rm -d --platform linux/amd64 --name "$container" \
  -e POSTGRES_PASSWORD=cluster-test-only -e POSTGRES_DB=nexus \
  --tmpfs /var/lib/postgresql/data \
  -v "$PWD/extensions/cluster-control/src/main/resources/sql/cluster-control.sql:/control.sql:ro" \
  "${CONTROL_TEST_PG_IMAGE:-postgres:17@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3}" >/dev/null
attempt=0
until docker exec "$container" pg_isready -U postgres -d nexus >/dev/null 2>&1; do
  attempt=$((attempt+1)); test "$attempt" -lt 60 || exit 1; sleep 1
done
docker exec -i "$container" psql -X -U postgres -d nexus -v ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE nexus LOGIN NOSUPERUSER;
CREATE ROLE nxcontrol LOGIN NOSUPERUSER;
GRANT CONNECT,CREATE ON DATABASE nexus TO nexus;
GRANT CREATE,USAGE ON SCHEMA public TO nexus;
SQL
docker exec "$container" psql -X -U postgres -d nexus -v ON_ERROR_STOP=1 -f /control.sql
python3 extensions/cluster-control/tests/sql_fencing.py
