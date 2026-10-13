# Kubernetes replication reference

The cluster profile combines Nexus blob replication, PostgreSQL 17/Patroni and
three-member etcd in **one Kubernetes cluster**. It supports two data centers
and an independent coordination witness. Installation and operational commands
are maintained in [INSTALL.md](INSTALL.md) and [UPGRADING.md](UPGRADING.md);
chart settings are documented in the [chart reference](../deploy/helm/nexsteem/README.md).

## Building from source

Run from a checkout with the pinned submodule initialized:

```sh
git submodule update --init --recursive
make verify
make build-cluster-from-source

docker build --platform linux/amd64 \
  -t steemax/nexsteem:postgres-17-patroni4.1.5-r2-amd64 \
  deploy/cluster/postgres
```

The full target builds Core and extensions from the pin. `make build-cluster`
is the faster extension assembly path when the required Core/builder images
already exist. Build and push your own image references, then set them in an
external values file. A Docker image digest and the assembled application's
SHA-256 in provenance are different identifiers.

## Topology and storage

Each Nexus member has its own `ReadWriteOnce` PVC. `ClusterLocal` exchanges
immutable content and manifests over authenticated TLS, verifies checksums and
length, and fsyncs files before acknowledgement. PostgreSQL stores versions,
committed heads, acknowledgements, PVC generations and tombstones. Blob
replication does not rely on a shared RWX filesystem or manual file copying.

Only `ClusterLocal` blob stores are supported in this profile. Attaching an
existing File/H2 data directory does not migrate it into a cluster. Migration
from a standalone installation needs a separately tested procedure.

Default topology is three Nexus, three PostgreSQL, two PostgreSQL routers and
three etcd members. Two Nexus members, one per data center, are also supported.
Etcd spans data center 1, data center 2 and an independent witness. PostgreSQL
and routers span both data centers; the witness does not store Nexus artifacts.

The `dc` declarations must match physical node placement. Selectors, anti-affinity,
CSI placement and the Kubernetes control plane must enforce that layout. The
chart does not build Kubernetes or connect independent Kubernetes clusters.
See [etcd quorum](https://etcd.io/docs/v3.6/faq/) and
[Patroni replication modes](https://patroni.readthedocs.io/en/latest/replication_modes.html).

## Durability and writer ownership

| Mode | Successful write requirement | Recovery |
|---|---|---|
| Normal | Blob fsync in both data centers and PostgreSQL `remote_apply` to the selected remote standby | Already remote durable |
| Degraded | An authorized current writer may acknowledge a locally durable copy under the configured reduced-copy policy | Automatic SQL/blob catch-up and remote-sync restoration |
| No coordination authority | No successful new writes | Quorum and current-primary authority must return |

`postgres.allowDegradedLocalWrites` controls whether writes may continue after
a data-center loss. Further loss of the remaining copy before catch-up can lose
new degraded writes. Remote replica count alone does not establish durability:
Nexus checks DCS membership and actual PostgreSQL synchronous streaming state.

A supervisor and replica agent stay running on every member. One fenced
owner/epoch starts Core, migrations and scheduled jobs. Passive members catch
up and healthy frontends route requests to the active Core. Takeover includes
Core startup time; measure it for the accepted deployment.

Core JDBC connections use a fixed owner/epoch and PostgreSQL primary term.
SQL DML/DDL/commit guards, durable replica fences and fresh DCS checks before
successful HTTP responses reject stale controlled writers. Lease expiry alone
is insufficient evidence of authority. This mechanism does not promise fencing
of arbitrary SQL clients, a compromised superuser or physical hardware.

A recovered or replacement PVC must verify committed heads before it may write.
Acknowledgements from an old PVC generation cannot certify the replacement.
Garbage collection respects SQL references, pending publications and retention.

## Authentication and repository traffic

OAuth/OIDC settings, user roles, repository configuration and encrypted sessions
are in the common SQL database. The encryption key is in a retained common
Kubernetes Secret; preserve it with backups. LDAP is separate from OIDC.

Docker and Helm remain repository protocols. Configure Docker connector ports
in Nexus and publish matching `service.dockerPorts` entries. The supervisor
listens on the Pod IP; Core listens on loopback. For the main HTTP endpoint,
a path-based Docker tag uses `HOST/REPOSITORY/IMAGE:TAG`, not a Nexus browser
URL containing `/repository/`. A dedicated connector chooses its repository
by port and uses `HOST/IMAGE:TAG`. Ingress must preserve `/v2/` and upload
`Location` paths. See the [installation guide](INSTALL.md) for ingress choices.

## Diagnostics and updates

- `/_cluster/status`: current owner/epoch, application identity, replica state,
  durability policy and PostgreSQL term/sync state.
- `/_cluster/health/member`: replica/member readiness; passive members can be Ready.
- `/_cluster/health/writer`: active Core and writer readiness.
- `X-Nexus-Cluster-Degraded: true`: the successful response used the reduced-copy mode.
- System → Nodes: administrative overview; `nx-nodes-read` protects its Core API.
  Inter-member monitor replies are authenticated and bound to a fresh request.

The chart mounts writable disk-backed `/tmp` for Nexus caches and private
keystores separately. HTTP concurrency, connection/header limits and timeouts
are configured under `nexus.http`. Client streams and health capacity have
separate admission limits; large artifacts use streaming.

Application updates stage passive members, verify catch-up, commit a SQL
release barrier and replace the old writer. `OnDelete` does not perform these
steps by itself. An idempotent Helm hook applies compatible extension SQL without
recreating users, passwords or data. The coordinator has no automatic resume
for every partially completed update, and Helm rollback does not undo migrations.
PostgreSQL/etcd image updates use their separate ordered workflow; PostgreSQL
major upgrades need an explicit data migration.

## Acceptance boundary

[Verification](verification.md) records historical coverage. The test scripts
use isolated Docker/Kind fixtures and explicit contexts. Run them for each
release and deployment: source presence, compilation and a successful Helm
install are insufficient fault acceptance.

Tests on one physical host do not establish behavior under physical kernel
hangs, power loss, real WAN characteristics or a production CSI/CNI provider.
Backups remain necessary because replication also propagates deletions.
