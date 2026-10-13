# Architecture and scope

This project builds the pinned public Nexus Core with separate open extensions
for repository formats, Keycloak/OIDC, storage and deployment. The release tag,
commit, Java version and build images are recorded in
[upstream.lock.json](../upstream.lock.json). The pin is a release input, not a
claim that it will remain the latest upstream release.

## Source boundaries

[Public Nexus Core](https://github.com/sonatype/nexus-public) is distinct from
the former OSS binary distribution and the Community Edition binary product.
The pinned Core exposes Maven2, Raw and APT server implementations with H2 by
default. Public frontend forms do not implement the missing server protocols.
The extensions provide the [required format matrix](formats.md); no edition or
license flag enables those implementations.

The upstream Git submodule is preserved. Preparation creates a build copy from
its pinned source; integration and extension assembly occur in that copy.
Extensions remain under `extensions`, with source origins recorded where code
is reused. Changes to upstream APIs may require adapter updates: binary
compatibility across Core releases is not promised.

For build and deployment commands, use [INSTALL.md](INSTALL.md). For selecting
and accepting a new upstream pin, use [UPGRADING.md](UPGRADING.md).

## Authentication

`extensions/keycloak` verifies access-token signatures against configured JWKS,
issuer, audience, subject and time/type claims. It does not retrieve URLs from
untrusted JWT claims. Explicit mappings translate the configured client's roles
into existing Nexus role IDs; unmapped roles do not grant privileges.

`extensions/oidc` supplies browser Authorization Code login with PKCE S256,
state/nonce, refresh and RP logout. User profiles and encrypted sessions are
stored in SQL. The shared encryption key must be backed up with the data.
LDAP is a separate authentication mechanism and is not a Keycloak substitute.
Package clients use their own supported credential mechanisms; browser SSO does
not automatically convert every package client's authentication to Bearer.

## Deployment profiles

| Profile | Storage and writer ownership | Boundary |
|---|---|---|
| Standalone Docker/Compose | One Core with H2 or the PostgreSQL adapter; file blobs on retained storage | No built-in multi-node replication in this profile |
| Kubernetes cluster | PostgreSQL/Patroni, three-member etcd, `ClusterLocal` blobs on independent PVCs; one active Core | One Kubernetes cluster across failure domains; Nexus performs blob replication |

Copying a live H2 database or running multiple Core processes against one data
directory is not HA. The launcher holds a local filesystem writer lock; that
lock does not replace ownership and fencing across independent hosts.

### Kubernetes cluster

Each Nexus Pod runs a supervisor and replica agent. Only the current fenced
owner launches Core, its migrations and background jobs. Other members catch
up and route frontend requests to the active writer. PostgreSQL has a stable
write endpoint through two routers placed in different data centers.

Normal acknowledgements require durable blob copies in two data centers and
actual PostgreSQL `remote_apply` to a selected remote synchronous standby.
The configured degraded policy can permit locally durable new writes after
loss of a data center; a further failure before catch-up can lose those new
writes. Loss of coordination authority prevents successful new writes.

The supervisor binds Core to a PostgreSQL primary term and fixed owner/epoch.
SQL statement, commit and DDL guards, persistent replica fences and fresh DCS
checks protect controlled Nexus processes. This does not fence arbitrary SQL
clients, a compromised superuser or hardware independently of the application.

See [replication reference](kubernetes-replication.md) and
[module contract](kubernetes-cluster-contract.md). The chart does not create
Kubernetes itself, join two independent Kubernetes clusters, or validate a CSI
provider. Control-plane quorum, DNS, ingress and storage must survive the
intended failure domains.

## Health and upgrades

Deep health probes check SQL writability, storage space and a small fsync
operation. Member readiness and active Core readiness are distinct; a passive
frontend can be Ready while its Core is stopped.

Cluster upgrades stage passive members, apply compatible extension SQL, commit
a release barrier and replace the old writer. StatefulSets use `OnDelete`;
ordinary Helm updates alone do not complete a coordinated application upgrade.
The release barrier blocks old code from resuming migrations after a change.
Automatic database-schema rollback is not provided: restore a consistent data
backup together with the appropriate code when rollback requires it.
