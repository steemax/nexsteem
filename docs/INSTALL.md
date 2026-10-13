# Installation

NexSteem has two deployment profiles: **standalone Docker/Compose**, or **Kubernetes HA with built-in replication**. Both target Linux `amd64` and include Java 25. The application name is **NexSteem**; upstream component names and attribution are retained. Source repository: [steemax/nexsteem](https://github.com/steemax/nexsteem).

## Standalone or HA

For one instance, use `steemax/nexsteem:3.96.4-01-standalone-ext3` with Docker/Compose and a persistent data volume. This profile does not need the cluster PostgreSQL/Patroni, etcd or replica services. It retains the repository formats and Keycloak/OIDC support; it does not provide cluster replication, automatic failover or the cluster Nodes dashboard.

The `nexsteem` Helm chart installs the HA profile only: at least two Nexus members, three PostgreSQL members and three etcd members. One Core writer is active while passive Nexus frontends route requests to it. There is no standalone-mode switch in this chart; `nexus.replicas: 1` is rejected. A standalone Kubernetes Helm chart is not included in the current public distribution. Use the standalone Docker/Compose instructions below for a single instance, rather than changing the HA chart's image or replica count.

## Images and publication status

All project images use the Docker Hub repository **`steemax/nexsteem`**. The new built targets are:

| Component | Image tag | Application SHA-256 |
|---|---|---|
| Standalone NexSteem | `steemax/nexsteem:3.96.4-01-standalone-ext3` | `d5902a98b4380f787164cb75418c34d41bc346f5695cfd7a6b6f22ab2921c84f` |
| Kubernetes NexSteem | `steemax/nexsteem:3.96.4-01-cluster-k8s10` | `75ffeab5f73e072e8034a4a7a35ff7bc3dd94ecc2663168eb22dbfd11235ee95` |
| PostgreSQL / Patroni | `steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64` | Separate PostgreSQL image provenance |

Exact local image IDs:

- Standalone: `sha256:f12707e1a977b43cffcd97c1ee35629896eae31f3b84bf688fe7e68753d7cdf3`.
- Cluster: `sha256:58a0e6e8c7d8abd7dff76059af3924c0432950eff6f5ec71096349fa375be6af`.
- PostgreSQL r3: `sha256:307b88691f321b6fbae0da46629046c9d8d362dbbd09e94b807cc66e99ca76c8`.

The build rebuilt 115 Core Maven modules, all repository-format modules, and two UI Maven modules after the wizard repair. The Core build skips its test suite; twelve Python unit tests and the PostgreSQL image verifier passed. The five PostgreSQL integration Python files are byte-for-byte unchanged.

Runtime verification covered the standalone UI/login/onboarding and all 51 recipe registrations. Exact cluster boot and ordered upgrade passed with both members ready, matching hashes, remote PostgreSQL sync and two blob copies. The full artifact-client and fault matrix was not rerun for this UI/legal rebuild. See [verification](verification.md) for scope. Core/formats were compiled from source while pinned OS/JDK and PostgreSQL dependency layers were reused; bit-for-bit reproducibility is not established.

Docker Hub tags, GitHub release assets and the online Helm repository remain **publication pending**. Pull commands work after publication; before then, load the matching local release archives or build from source. The new images include NexSteem branding, retained notices and source links. Older ext2/k8s9 reference images are preserved separately with their own matching sources.

Corresponding-source release: [3.96.4-01-ext3-k8s10](https://github.com/steemax/nexsteem/releases/tag/3.96.4-01-ext3-k8s10), archive [nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz](https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz). Those links become available when the matching release is published.

A Docker image ID, a Docker archive checksum, a registry digest, and an assembled application SHA-256 identify different objects. Pin deployed images using **published registry digests**, and use the **application SHA-256** for the cluster release gate. Do not substitute a local image ID for a registry digest.

## Standalone Docker / Compose

### Standalone image from Docker Hub

After publication:

```sh
git clone https://github.com/steemax/nexsteem.git
cd nexsteem
export NEXUS_IMAGE=steemax/nexsteem:3.96.4-01-standalone-ext3
docker pull --platform linux/amd64 "$NEXUS_IMAGE"
docker compose -f deploy/compose.yaml up -d
```

No Core submodule checkout or Java compilation is needed to use an existing image. For an immutable deployment, replace the tag with the release's actual reference, `steemax/nexsteem@sha256:PUBLISHED_STANDALONE_DIGEST`.

### Standalone image from a Docker archive

Download the standalone archive and manifest supplied with the selected GitHub release or directly by the maintainer. Verify the supplied checksums:

```sh
sha256sum --check SHA256SUMS
```

Then load the image under its published component tag:

```sh
docker load --input /path/to/nexsteem-3.96.4-01-standalone-ext3-amd64.docker.tar.gz
export NEXUS_IMAGE=steemax/nexsteem:3.96.4-01-standalone-ext3
docker compose -f deploy/compose.yaml up -d
```

Replace `/path/to` with the download directory. Confirm the loaded tag against the release manifest. The archive must match the standalone image ID and application SHA above. A tag alone does not establish provenance.

### First startup and persistence

The included Compose file binds HTTP to `127.0.0.1:8081` and stores data in a named volume. After startup:

```sh
docker compose -f deploy/compose.yaml exec nexus cat /var/lib/nexus/admin.password
```

Log in as `admin` at `http://localhost:8081` and change the initial password. Check `/service/rest/v1/open/health` for readiness. Stop with:

```sh
docker compose -f deploy/compose.yaml down
```

This retains the named volume. Adding `--volumes` deletes it. Keep the Compose project name and data volume stable across updates.

For production, configure HTTPS at a reverse proxy, the public URL, credentials, and external backups. The supplied Compose file allows HTTP OIDC only for localhost development. The standalone image runs one Core writer: its default H2 database and file blob store are not replicated. External PostgreSQL support alone does not replicate blobs.

## Build from source

Host tools: Git, Python 3.12 or newer, Make, and Docker with BuildKit. Kubernetes deployment additionally requires Helm and kubectl. Java 25, Maven, and Node run in pinned containers; no host Java installation is required. ARM hosts need `amd64` emulation. Allocate sufficient memory and disk for compilation and dependency caches.

```sh
git clone --recurse-submodules https://github.com/steemax/nexsteem.git
cd nexsteem
make verify
```

For an existing clone, run `git submodule update --init --recursive` before verification. GitHub's automatically generated source ZIP omits the Core submodule contents. Use a recursive Git clone, or the complete source archive supplied for the intended release.

The foundation is Core `3.96.4-01`, commit `e60cdf28e5808a25c2524edb8b1550a0c7f08d82`, in [upstream.lock.json](../upstream.lock.json). Leave `upstream/nexus-public` unmodified; preparation copies it into `build/source` and integrates the extensions there.

### Standalone source build

```sh
make build IMAGE=steemax/nexsteem:3.96.4-01-standalone-ext3
make up IMAGE=steemax/nexsteem:3.96.4-01-standalone-ext3
```

These commands build and start NexSteem from the selected source, rather than retagging an older image. Verify your resulting image before production deployment. `make verify` checks pins, Python tests, and shell syntax; it is not a running-Nexus acceptance test.

### Cluster source build

```sh
make build-cluster-from-source \
  CLUSTER_IMAGE=steemax/nexsteem:3.96.4-01-cluster-k8s10

make build-postgres
```

The first command builds Core and extensions before assembling the optional cluster modules. `make build-postgres` builds the pinned PostgreSQL/Patroni integration image and passes the corresponding-source metadata. The shorter `make build-cluster` target requires matching Core and builder images already present. A local rebuild is not assumed to match a previously tested binary byte for byte.

Publish a newly accepted image under an appropriate immutable tag before selecting it in Kubernetes. `docker push steemax/nexsteem:TAG` requires the maintainer's Docker Hub permissions. Do not overwrite a published reference tag with an unverified rebuild. Local test clusters may load images directly on every participating worker instead.

### Corresponding-source build metadata

Make derives `RELEASE_TAG` from the pinned Core version and extension/cluster revisions, and derives `SOURCE_ARCHIVE_URL` from that tag. The current defaults point to release `3.96.4-01-ext3-k8s10` and its complete source archive. Override these values for a fork or another release:

```sh
make build \
  SOURCE_URL=https://github.com/YOUR_ACCOUNT/YOUR_REPOSITORY \
  RELEASE_TAG=YOUR_RELEASE_TAG
```

`SOURCE_ARCHIVE_URL` can be supplied explicitly when your archive uses another location. The same variables apply to `make build-cluster-from-source`, `make build-cluster` and `make build-postgres`. Make passes `SOURCE_URL`, `SOURCE_ARCHIVE_URL`, `SOURCE_SNAPSHOT_SHA256` and `SOURCE_ARCHIVE_SHA256` as Docker build arguments.

The two SHA-256 variables are optional and accept a real 64-character hexadecimal checksum, not a placeholder. If supplied, distinguish the publisher's source snapshot from the compressed archive hash. If omitted, inspect the computed snapshot scope in image provenance and use the release's `SHA256SUMS` for the archive. Embedded links identify the intended source location; they do not make an unpublished archive available or settle third-party license questions.

## Kubernetes with built-in replication

### Get the chart

The chart is named **`nexsteem`**, at `deploy/helm/nexsteem`. Its version is `0.1.5`; defaults select the newly built `k8s10` Nexus and r3 PostgreSQL images. Exact-image boot and ordered upgrade passed; publication is pending. The two image components are Nexus and PostgreSQL/Patroni from the table above; etcd and HAProxy use their pinned official images.

The planned online Helm repository is **`https://steemax.github.io/nexsteem/charts`**. It becomes available only after the repository is pushed and GitHub Pages is enabled. Register it after publication:

```sh
helm repo add nexsteem https://steemax.github.io/nexsteem/charts
helm repo update
```

Other installation routes:

- Clone `https://github.com/steemax/nexsteem.git` and use `deploy/helm/nexsteem`. **No Git submodule initialization is needed for chart-only installation.**
- Download `nexsteem-0.1.5.tgz` from the selected GitHub release, when uploaded.

Helm OCI distribution is optional; GitHub Pages, a Git checkout, or a downloaded chart package is sufficient. The project Docker images are distributed through Docker Hub.

### Cluster images from Docker archives

The cluster release bundle contains these image archives once packaged:

```sh
docker load --input /path/to/nexsteem-3.96.4-01-cluster-k8s10-amd64.docker.tar.gz
docker load --input /path/to/nexsteem-postgres-17-patroni4.1.5-r3-amd64.docker.tar.gz
```

Loading images on a workstation does not make them available to remote Kubernetes workers. The maintainer must push the component tags to Docker Hub, or a local test setup must load the images on every participating worker. Publication commands are in [.github/REPOSITORY-SETUP.md](../.github/REPOSITORY-SETUP.md).

### Topology and values

Use **one Kubernetes cluster** with three data workers across two data centers and an independent witness worker. PostgreSQL needs three separate data hosts with required anti-affinity enabled; two Nexus members, one per site, are supported. etcd spans both sites and the witness. Two database routers are placed in separate sites.

Assign labels based on real placement, using the intended kubectl context:

```sh
kubectl label node YOUR_DC1_WORKER nexus.cluster/dc=dc1
kubectl label node YOUR_DC2_WORKER_A nexus.cluster/dc=dc2
kubectl label node YOUR_DC2_WORKER_B nexus.cluster/dc=dc2
kubectl label node YOUR_WITNESS_WORKER nexus.cluster/dc=witness
```

Create `production-values.yaml` outside the checkout. The README contains a [short two-member example](../README.md#kubernetes-quick-start). Start from [chart values](../deploy/helm/nexsteem/values.yaml) for full configuration:

| Values | Set for your environment |
|---|---|
| `nexus.image`, `postgres.image` | Matching `steemax/nexsteem` component tags or published digests |
| `etcd.nodes`, `postgres.nodes`, `nexus.nodes`, `router.nodes` | Actual failure domains and node selectors |
| `nexus.replicas` | Number of entries in `nexus.nodes` |
| `*.storageClass`, `*.storageSize` | Durable PVC provisioning and capacity |
| `nexus.resources`, `postgres.resources`, `etcd.resources`, `router.resources` | Per-container CPU/memory requests and limits |
| `nexus.initResources`, `jobs.etcdAuth.resources`, `jobs.schemaUpgrade.resources` | Init and Job container budgets |
| `nexus.javaOpts` | Core JVM heap/direct-memory settings within the Nexus container budget |
| `clusterDomain` | Actual Kubernetes DNS suffix |
| `imagePullSecrets` | Docker Hub pull credentials when needed |
| `ingress.*` | Hostname, ingress class, and public TLS Secret |
| `networkPolicy.clientNamespaceSelector` | Trusted client/ingress namespaces |
| `secrets.existingSecret`, `tls.existingSecret` | Optional supplied installation/internal-TLS Secrets |
| `postgres.allowDegradedLocalWrites` | Whether to accept local writes after remote site loss |

See the [resource examples](../deploy/helm/nexsteem/README.md#resource-budgets) for all workload containers. Resource values apply per container; changing a memory limit does not resize the JVM automatically.

Each member has its own PVC; Nexus creates the remote blob copy. Keep production anti-affinity enabled. A `dc` declaration without enforced placement does not create a failure domain. Kubernetes control plane, DNS, ingress, storage, and network must also survive the intended failure. The witness stores coordination, not artifact bytes. The chart does not create Kubernetes or federate independent clusters.

Normal mode requires two durable blob copies including the remote site and actual remote PostgreSQL synchronous replication. Degraded writes are enabled by default: verified authority permits one local copy during remote-site loss. A further loss before catch-up can lose newly written data from that period. Disable degraded writes if that tradeoff is unsuitable.

### Install and check

Ensure the images are published and reachable from every worker. Prepare the public ingress TLS Secret in namespace `nexus`, or use an existing LoadBalancer/TLS setup. The chart generates and reuses installation credentials, a shared Nexus encryption key, and internal TLS Secrets unless supplied explicitly. Back them up securely; retention annotations do not protect against namespace deletion. Plan certificate rotation before expiry.

Using the online repository after publication:

```sh
helm upgrade --install nexus nexsteem/nexsteem --version 0.1.5 \
  --namespace nexus --create-namespace \
  --values /path/to/production-values.yaml --wait --timeout 15m
```

Or from the parent directory containing your checkout:

```sh
helm upgrade --install nexus ./nexsteem/deploy/helm/nexsteem \
  --namespace nexus --create-namespace \
  --values /path/to/production-values.yaml --wait --timeout 15m
```

Or use the downloaded chart:

```sh
helm upgrade --install nexus /path/to/nexsteem-0.1.5.tgz \
  --namespace nexus --create-namespace \
  --values /path/to/production-values.yaml --wait --timeout 15m
```

Use `--kubeconfig` and `--kube-context` to target a specific cluster when needed. On existing installations, retain the **Helm release name, namespace, chart resource prefix, Secrets and PVCs**. The Git/Docker project name does not require renaming Kubernetes resources. See [UPGRADING](UPGRADING.md) before using Helm against an existing release.

Check successful bootstrap/authentication jobs, one PostgreSQL primary, remote sync, and Nexus members. `/_cluster/status` identifies the writer, epoch, application hash, copy policy, catch-up, and PostgreSQL/Core terms. `/_cluster/health/member` measures member readiness; `/_cluster/health/writer` measures active Core readiness. A passive frontend stays ready and forwards to the writer.

Open `System → Nodes` for the cluster view. Create a small hosted repository, upload and download an artifact, and verify its SHA-256. Validate required clients and failure scenarios separately before production traffic; successful Helm installation is not failure acceptance.

Cluster mode permits `ClusterLocal` blob stores. Changing the image of an existing File/H2 installation or sharing its data directory does not migrate it into a replicated cluster; that requires a separate tested procedure.

## Docker and Helm endpoints

On the main Nexus hostname, the Docker reference includes the repository name:

```text
nexus.example.org/docker-hosted/my-app:1.0
```

Do not insert `/repository/` into that image reference. A dedicated Docker connector selects its repository by port, for example `nexus.example.org:8090/my-app:1.0`. Configure the same connector in Nexus and chart values:

```yaml
service:
  dockerPorts:
    - {port: 8090, corePort: 8090}
```

The cluster frontend accepts the public request; Core listens on loopback. Expose the controlled frontend using client-facing TLS. Helm chart URLs use ordinary repository paths such as `https://nexus.example.org/repository/helm-hosted/`; Helm OCI uses Docker/OCI endpoints.

## Keycloak and backups

Configure `Security → OAuth 2.0` with issuer/endpoints, confidential client, audience, and explicit Nexus role mappings. Enable Keycloak Standard Flow and PKCE S256; use `<public Nexus URL>/oidc/callback` as the redirect URI. Activate `OAuth2Realm` and verify a non-admin account's permissions. Preserve the public HTTPS host/protocol through ingress. LDAP remains independent.

Settings and encrypted sessions are stored in SQL; retain the shared encryption key across restarts and upgrades. Keep a coordinated external backup of metadata, blobs, configuration and keys, and test restore. Replication propagates deletions and cannot replace backup. See [UPGRADING](UPGRADING.md) for source changes, schema hooks and ordered upgrades.
