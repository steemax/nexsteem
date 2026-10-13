# Cluster coordinator

The always-running supervisor starts Core only while it owns the current
writer epoch and a fresh etcd/PostgreSQL authority proof. Passive members keep
the replica agent and frontend running while Core is stopped. The public
frontend routes requests to the current ready writer; Core listens on loopback.

`ClusterSupervisor` manages leadership, Core lifecycle, HTTP admission and
readiness. `AuthorityProbe` ties a linearizable etcd snapshot and lease to the
connected PostgreSQL primary term. `ControlStore` manages the SQL lease, release
gate and durability policy. `JdbcEpoch` binds Core connections to a fixed owner
and epoch; SQL guards cover statements, transaction completion and DDL.

A stale managed writer must fail closed. Losing authority, the PostgreSQL term
or release eligibility stops Core. Acquiring a new epoch does not automatically
permit startup: release eligibility, committed blob catch-up, durable peer fences
and the configured SQL/blob durability policy must also hold.

`/_cluster/status` reports the member, current writer, replication and database
state. `/_cluster/health/member` is member readiness;
`/_cluster/health/writer` is local writer readiness, so passive members do not
pass the latter check. The signed monitor endpoint is diagnostic only and cannot
supply write authority.

Normal mode requires remote blob copies and PostgreSQL synchronous apply.
Degraded mode is an explicit reduced-copy policy after authority is established;
it cannot create leadership. Returning peers catch up before being counted, and
normal mode resumes after remote data and database synchronization recover.

Use `scripts/upgrade-cluster.py` for passive-first application replacement and
an atomic SQL release-gate change. Same-application restarts do not change the
gate. An interrupted upgrade requires state inspection; there is no automatic
resume workflow. PostgreSQL/etcd updates use a separate infrastructure coordinator.

See [architecture](../../docs/architecture.md),
[cluster contract](../../docs/kubernetes-cluster-contract.md) and
[upgrades](../../docs/UPGRADING.md) for operator and protocol details.
