# Nexus cluster chart

This chart deploys the Kubernetes cluster profile: Nexus internal blob
replication, PostgreSQL/Patroni and etcd. Build and installation commands are in
[INSTALL.md](../../../docs/INSTALL.md); coordinated updates are in
[UPGRADING.md](../../../docs/UPGRADING.md).

## Source build

From the repository root:

```sh
git submodule update --init --recursive
make verify
make build-cluster-from-source
docker build --platform linux/amd64 -t steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64 deploy/cluster/postgres
```

Publish the resulting images to a registry you control and set their references
in your values. Build provenance identifies the assembled application separately
from the container digest. The production PostgreSQL image is AMD64; local ARM
fixtures are test profiles.

## Configuration reference

| Values | Purpose |
|---|---|
| `nexus.nodes`, `postgres.nodes`, `etcd.nodes`, `router.nodes` | Member data center and physical `nodeSelector` placement |
| `placement.requiredAntiAffinity`, `placement.topologyKey` | Independent hosts for members of each component |
| `*.image`, `*.pullPolicy`, `imagePullSecrets` | Accepted application/infrastructure images and registry access |
| `*.storageClass`, `*.storageSize` | Independent persistent volumes; no cross-site PVC relocation is performed |
| `postgres.allowDegradedLocalWrites` | Permit authorized writes with reduced remote durability after a site loss |
| `secrets.existingSecret`, `tls.existingSecret` | Use externally managed common secrets/certificates |
| `tls.validityDays` | Initial generated certificate lifetime; existing Secrets are reused |
| `service`, `ingress`, `networkPolicy.clientNamespaceSelector` | Client reachability, TLS termination and allowed ingress namespaces |
| `service.dockerPorts` | Frontend/Core connector pairs, normally the same port number |
| `nexus.javaOpts`, `nexus.http`, `*.resources` | JVM, HTTP concurrency/timeouts and Kubernetes resource budgets |

Defaults are three Nexus, three PostgreSQL, two routers and three etcd members.
The minimum Nexus layout is two members across two data centers. Etcd requires
three independent coordination domains, including a witness. Default selectors
use `nexus.cluster/dc`; assign real labels and storage placement accordingly.
Three PostgreSQL members with required host anti-affinity need three data hosts.

Each member keeps its own PVC. Kubernetes, DNS, ingress, control-plane quorum
and the storage provider must survive the required failures. The chart does not
provision Kubernetes or combine two independent clusters. PodDisruptionBudgets
limit voluntary evictions; they do not prevent node failure or replace the
ordered upgrade coordinator.

## Runtime behavior

Only the current fenced owner launches Core. Passive frontends are member-Ready
and route requests to that writer. A Ready Pod therefore does not prove that a
Core writer exists: check `/_cluster/health/writer` or the status checker after
installation. `OnDelete` prevents uncoordinated simultaneous replacements.

Normal successful writes require remote durable blobs and actual remote SQL
synchronous replication. Degraded local writes can continue only when enabled
and authority is current; a second failure before catch-up may lose new writes.
No successful writes are allowed without DCS/primary authority. For details,
see [replication](../../../docs/kubernetes-replication.md) and the
[module contract](../../../docs/kubernetes-cluster-contract.md).

Writable disk-backed `/tmp` supports Nexus cache creation with UID/GID 200.
Private keystores have a separate memory-backed volume. Do not size temporary
storage or Pod memory below the intended cache/upload workload.

## Secrets, TLS and networking

Generated passwords, the replica authentication key and the Nexus encryption
key are retained and reused across Helm updates. `helm.sh/resource-policy: keep`
does not protect against deletion of the namespace. Back up the key and secrets
with the database and blob state. Existing Secret keys are listed in the
`secrets.yaml` and `tls.yaml` templates.

Nexus peer, PostgreSQL and etcd traffic use separate CAs. The etcd HTTP gateway
uses a client certificate with empty CN and critical SAN, plus scoped
username/token authentication. `nx-proof` reads the configured DCS prefix and
lease TTL; it cannot mutate coordination. Core does not receive DCS/control
credentials in its environment.

NetworkPolicy is enabled by default. Allow the ingress controller's namespace
and validate your CNI enforcement. The local Kind fixture explicitly disables
these policies because kindnet does not enforce them. The default Service is
ClusterIP; expose HTTPS through the configured ingress or your LoadBalancer.
Docker connector publication does not create repositories or choose their ports
in Nexus automatically.

## Upgrade and acceptance boundaries

Helm's post-upgrade hook applies compatible extension SQL and journal indexes
through an authenticated current primary; it retains users, credentials, release
state and content. A failed hook prevents the coordinated workflow from
continuing. Core changes use the release barrier; infrastructure changes update
healthy replicas before the primary. See [UPGRADING.md](../../../docs/UPGRADING.md)
for procedures and partial-update recovery limits.

Use [verification](../../../docs/verification.md) and the isolated integration
scripts to accept your built images. Local tests do not validate physical
power fencing, a real cross-site network or a production CSI/CNI provider.

## Resource budgets

CPU and memory are configurable for every workload container through values:

| Value | Container |
|---|---|
| `nexus.resources` | Each Nexus member, including Core and the cluster supervisor |
| `postgres.resources` | Each PostgreSQL/Patroni member |
| `etcd.resources` | Each etcd member |
| `router.resources` | Each HAProxy router |
| `nexus.initResources` | `prepare-private-keystores` init container |
| `jobs.etcdAuth.resources` | `initialize-auth` container in the etcd bootstrap Job |
| `jobs.schemaUpgrade.resources` | `schema` container in the post-upgrade Job |

For example, add these overrides to your installation values:

```yaml
nexus:
  javaOpts: -Xms256m -Xmx2g -XX:MaxDirectMemorySize=1g
  resources:
    requests: {cpu: "1", memory: 2Gi}
    limits: {cpu: "4", memory: 4Gi}
  initResources:
    requests: {cpu: 50m, memory: 64Mi}
    limits: {cpu: 500m, memory: 128Mi}
postgres:
  resources:
    requests: {cpu: 250m, memory: 512Mi}
    limits: {cpu: "2", memory: 1Gi}
etcd:
  resources:
    requests: {cpu: 100m, memory: 128Mi}
    limits: {cpu: "1", memory: 512Mi}
router:
  resources:
    requests: {cpu: 50m, memory: 32Mi}
    limits: {cpu: 500m, memory: 128Mi}
jobs:
  etcdAuth:
    resources:
      requests: {cpu: 50m, memory: 64Mi}
      limits: {cpu: 500m, memory: 128Mi}
  schemaUpgrade:
    resources:
      requests: {cpu: 50m, memory: 64Mi}
      limits: {cpu: "1", memory: 256Mi}
```

These are per-container budgets; each member receives the selected component's settings. Helm merges partial overrides with chart defaults. The maps are passed to Kubernetes ResourceRequirements, including `ephemeral-storage` when supplied. Existing installations with `--reuse-values` and no new init/Job keys retain their previous resource budgets.

Allow memory for the Core JVM heap, direct/native allocations and the cluster supervisor; changing `nexus.resources.limits.memory` does not automatically resize `nexus.javaOpts`. Size every Nexus member to take over the writer role. Storage capacities remain separate under `*.storageSize`.

Nexus, PostgreSQL and etcd StatefulSets use `OnDelete`. Helm changes their desired Pod templates; updated resource budgets take effect when members are recreated in a controlled sequence. Preserve the release gate when only resources change and follow the ordered upgrade guidance.
