# Kubernetes cluster module contract

This is an implementation reference for the optional cluster modules. It does
not replace release acceptance or the [installation](INSTALL.md) and
[upgrade](UPGRADING.md) procedures. See
[replication behavior](kubernetes-replication.md) for deployment boundaries.

Every Nexus Pod runs a supervisor and replica agent against an independent PVC.
Only the current owner/epoch launches Core. Passive members receive and verify
committed content without running Core migrations or scheduled jobs.

## Coordination and SQL guards

| Object | Responsibility |
|---|---|
| `nx_cluster_leader` | Owner, epoch, endpoint and phase: FENCING → STARTING → READY |
| `nx_cluster_lease` | Authoritative lease deadline; heartbeat updates this table rather than the leader row under a publication lock |
| `nx_cluster_policy` | Epoch-bound copy count, remote-copy requirement and degraded state |
| `nx_cluster_release` | Accepted application SHA/version; prevents superseded code from reacquiring Core ownership |

`nx_cluster_begin_write(cluster, owner, epoch)` takes a shared transaction
advisory lock and validates owner, epoch, phase and the SQL-clock deadline.
`nx_cluster_assert_current` checks current authority including the lease table.
Epoch changes take the exclusive lock. Core connections are bound to the fixed
owner/epoch; statement, commit and DDL guards are in `cluster-control`.

The supervisor independently validates a linearizable DCS snapshot, exact
leader-key lease and current PostgreSQL primary. Core is bound to that primary
term; a term change requires a new Core incarnation/epoch. Successful frontend
responses require fresh authority checks. Lease expiry alone cannot grant
ownership, and a stale isolated primary is not an authority source.

## Module interfaces and configuration

`extensions/cluster-api` defines `ReplicaAgentFactory.create(Properties)` for
ServiceLoader. The agent exposes `start`, `isCaughtUp`, `fencePeers`, `status` and
`close`. `ReplicaStatus` reports member readiness, catch-up, durable epoch,
required/available copies and degraded state.

Common environment properties include `NX_CLUSTER_ID`, `NX_CLUSTER_NODE_ID`,
`NX_CLUSTER_OWNER_ID`, `NX_CLUSTER_EPOCH`, `NX_CLUSTER_PEERS_JSON`,
`NX_CLUSTER_ROOT`, `NX_CLUSTER_SECRET_FILE`, `NX_CLUSTER_SQL_URL`,
`NX_CLUSTER_SQL_USER` and `NX_CLUSTER_SQL_PASSWORD_FILE`. Supervisor-only DCS
and control credentials are separate from the child Core environment.

Replica TLS uses `NX_CLUSTER_TLS_KEYSTORE_FILE`, `NX_CLUSTER_TLS_PASSWORD_FILE`
and `NX_CLUSTER_TLS_CA_FILE`. Plain HTTP requires an explicit isolated-test
flag. Configured membership binds stable Pod names, replica URLs and data
centers; persisted PVC generations invalidate old acknowledgements after PVC loss.

Default ports are supervisor 8081, loopback Core 19081 and replica TLS 8095.
Member and writer health are distinct. The authenticated read-only
`/_cluster/monitor` signs observations for the caller's nonce/time and expected
node; it is diagnostic data and never supplies writer authority.

## Publication, catch-up and deletion

Blob bytes are immutable content-addressed objects with checksummed manifests.
Local and remote fsync acknowledgements precede publication of the committed
head in PostgreSQL. Failed later metadata attachment leaves an orphan rather
than a reference to missing bytes. Confirmed SQL and blob state must be tested
together through failover.

Attributes, tombstones and version history are replicated. Physical deletion
must account for SQL references, prepared publications, retention and member
state. A lagging or replaced PVC cannot write until committed heads are verified.

The configured degraded policy may reduce required copies only under fresh
coordinator authority. A local environment flag does not bypass ownership or
fencing. Catch-up and actual remote SQL sync restore normal durability; loss of
authority prohibits successful new writes. Application fencing does not cover
arbitrary SQL clients, compromised superusers or independent hardware failure.
