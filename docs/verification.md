# Verification scope

The source publication contains the pinned Core and the base/cluster extension
assemblies. Source preparation, Python unit checks, shell syntax and Helm
lint/render are useful publication checks. They are not a fresh Java/frontend
build, protocol acceptance run or sustained load test. Private development
reports, credentials and environment details are not part of this repository.

## Historical runtime coverage

The following results describe previously exercised development baselines.
They do not certify arbitrary future builds, configurations or storage providers.
Image identities and private logs are deliberately not reproduced here; retain
provenance and reports for each release you build and accept.

| Baseline | Historically exercised scope | Limit |
|---|---|---|
| Base extensions (ext2) | Native clients for the 51 required recipes, H2/PostgreSQL storage, API/browser Keycloak/OIDC, LDAP and repository RBAC in single-Core containers | No built-in cluster replication in this base distribution; not every upstream test was run |
| Early cluster baseline (k8s4) | Automatic takeover after loss of either simulated data center, degraded writes, SQL/blob catch-up and artifact continuity | Docker/Kind failure domains shared one physical host |
| Cluster baseline k8s8 | Supervisor freeze with old Core alive, PostgreSQL primary-term change with old PostgreSQL alive, fixed-term Core restart, native Docker/Helm, resumed uploads and artifact checksums | The earlier full data-center matrix was not repeated on this baseline |
| Cluster baseline k8s9 | Coordinated upgrade, artifact continuity, native Docker/Helm, Nodes API permissions and rendered Nodes page | The full earlier fault matrix was not repeated |

Short memory observations do not exclude leaks under long-running workloads.
Bit-for-bit reproducibility of independent builds has not been established,
although dependency/frontend inputs are pinned.

## Repeating acceptance

Start with [INSTALL.md](INSTALL.md) and [UPGRADING.md](UPGRADING.md). Run tests
against dedicated fixtures, retain their provenance, and restore any temporary
repository/security settings. Test scripts have explicit fixture guards; do not
remove those guards to target an unrelated installation.

| Area | Test entry points | Required evidence |
|---|---|---|
| Recipes and clients | `scripts/test-recipes.py`, `scripts/test-core-matrix.py`, format-specific `test-*.py` | Registration plus publish/resolve/download, metadata, checksums, RBAC and upstream errors |
| Modern protocols | `scripts/test-modern-final.sh` and its protocol/native/private-proxy/Hub helpers | Supported recipe variants, authenticated upstreams and credential isolation |
| Authentication | `scripts/test-oidc-api.py`, `scripts/test-keycloak-api.py`, `scripts/test-ldap.py` | Positive login, expiry/invalid credentials, refresh/logout and denied writes |
| Application health | Docker HEALTHCHECK and cluster member/writer readiness probes | Readiness loss/recovery and preserved data/configuration |
| Kubernetes cluster | `scripts/test-cluster-kubernetes.py`, `test-cluster-application.py`, `test-cluster-clients.py`, `test-cluster-failover.py`, `test-cluster-pg-term.py` | One writer, real fault injection, SQL/blob/configuration preservation, new writes and recovery |

For fault tests, verify the process or network fault actually occurred. Linux
namespace PID1 wrappers can leave a coordinator child running, and Kubernetes
liveness may replace a deliberately stopped process. Do not report a timeout,
uninjected fault or partial test as a successful takeover. Record recovery time
and the configured durability mode, including degraded exposure.

## Deployment limits

- Repository support is an independent open implementation, not a promise of
  every commercial feature.
- The cluster profile runs one active Core. Replicas receive blobs and route
  requests; they are not concurrent Core writers.
- Normal remote durability depends on real failure-domain placement and a
  selected, caught-up synchronous standby, not just a configured replica count.
- Degraded writes have fewer copies until recovery; this is not RPO zero.
- Application fencing covers the controlled Nexus processes, not a compromised
  database superuser or physical power fencing.
- Local Kind does not validate a real cross-site network, kernel/power failure,
  CSI durability or NetworkPolicy enforcement with its default kindnet CNI.
- Healthy Kubernetes control plane, DNS, ingress and durable PVCs remain deployment
  requirements. Helm rollback does not reverse a database migration.

## NexSteem branding and embedded-notice release

The designated release `3.96.4-01-ext3-k8s10` contains newly compiled standalone ext3 and cluster k8s10 applications, and the PostgreSQL/Patroni r3 packaging update. All target Linux amd64. The earlier ext2/k8s9 native-client and fault results above remain historical evidence for their specific revisions.

- The Core source build completed across 115 Maven modules; Core test execution was skipped. Two UI Maven modules were incrementally rebuilt after an onboarding branding correction. All format and project modules were compiled, and twelve Python unit tests passed.
- The three final images passed actual-artifact inspection: their own logo/title resources, default Java title, retained original vendor bundles, embedded project licenses/notices, source repository/archive links, image architecture and provenance. The installed Patroni MIT license and PostgreSQL copyright were inspected inside the PostgreSQL image.
- The exact standalone image was started. Its NexSteem header/login/browser title and onboarding mark/text were observed in a real browser. All 51 required recipes were registered. The temporary standalone UI account was removed after the check.
- The exact cluster image completed an ordered application upgrade in the isolated local Kind deployment. The application SHA release gate was committed and PVCs were retained. The observed status showed a READY writer, the new application SHA, two available blob copies and remote synchronous PostgreSQL replication.
- The new runtime checks cover branding, recipe registration, cluster boot and ordered upgrade. Authenticated raw/Helm/OCI client continuity and the full fault matrix were not repeated for this branding/notices rebuild. The earlier Nodes image remains a labelled historical branding preview; the new standalone onboarding image is an actual final-image capture.
- PostgreSQL r3 retains the tested r2 runtime. The five integration Python files were byte-identical and installed Patroni remained 4.1.5. Only legal/release metadata changed. Reused pinned OS/JDK/PostgreSQL layers do not establish bit-for-bit build reproducibility.

No complete new fault matrix is claimed for this UI/legal rebuild. The actual image IDs, application hashes and scoped reports are in the release manifest and validation assets. No Docker Hub/GitHub/Pages publication was performed by this preparation. The unresolved Sencha downstream-license question remains in THIRD-PARTY-NOTICES.md.
