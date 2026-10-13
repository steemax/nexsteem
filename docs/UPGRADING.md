# Upgrading

Two changes are distinct: **selecting and building new source code**, and **upgrading a running deployment**. Changing the upstream pin does not update a server. A newer image must pass compatibility and data-migration checks before it is deployed.

The application name is **NexSteem**. Ext3/k8s10 contains updated UI identity, branding, notices and corresponding-source links. Standalone UI/login/onboarding and all 51 recipe registrations were verified. Exact cluster boot and ordered upgrade passed with both members ready, matching provenance, remote PostgreSQL sync and two copies. The full artifact-client and fault matrix was not rerun for this UI/legal rebuild. Historical ext2/k8s9 images remain unchanged; retagging them does not apply the new source changes.

The designated release is [3.96.4-01-ext3-k8s10](https://github.com/steemax/nexsteem/releases/tag/3.96.4-01-ext3-k8s10), with planned [complete corresponding-source archive](https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz). Those links are not available until publication. Verify the exact image and application provenance before deployment. Chart 0.1.5 selects the new k8s10 and PostgreSQL r3 images; keep the existing Helm release name, namespace and resource/PVC identities.

## Update the NexSteem repository and its submodule

The source repository is `https://github.com/steemax/nexsteem.git`. When checking out a project release:

```sh
git fetch --tags origin
git checkout YOUR_PROJECT_RELEASE_TAG
git submodule update --init --recursive
make verify
```

The superproject records the exact upstream submodule commit. Do not use a moving upstream branch as a substitute for the lock file, and do not edit the upstream checkout. Extensions are maintained separately in `extensions/`; build preparation integrates them into `build/source`.

### Select a newer upstream Core

Work on a branch, then inspect the available public release and select a specific tag:

```sh
git switch -c update-core
make latest
make update TAG=release-X.Y.Z-NN
git diff -- upstream.lock.json upstream/nexus-public
make verify
make test
```

`make latest` queries the public upstream release tags. `make update` verifies the selected tag, fetches its commit, checks the expected source layout and Java requirement, and updates `upstream.lock.json` together with the submodule checkout. It does not deploy anything or establish extension compatibility.

If the new Core changes its Java toolchain, source layout, SQL interfaces, frontend APIs, or security behavior, adapt the integration and pinned build dependencies deliberately. The current release uses Java 25 and Core `3.96.4-01`, commit `e60cdf28e5808a25c2524edb8b1550a0c7f08d82`.

Build the required distributions:

```sh
make build
make build-cluster-from-source
make build-postgres
```

Verify the resulting release in a separate Nexus environment. Include native repository clients, Docker and Helm, Keycloak login and role checks, database migrations, restart persistence, and the required failover/catch-up scenarios. `make verify` and `make test` cover only part of this validation; compilation alone is not acceptance. Keep reports associated with the exact built image and application SHA-256.

Commit the reviewed lock file and submodule pointer together, along with any required extension adaptations:

```sh
git add upstream.lock.json upstream/nexus-public extensions config build-support
git diff --cached
git commit -m "Update pinned Nexus Core and compatible extensions"
```

Core version and extension revision are separate. The Makefile derives the image's Core version from `upstream.lock.json`; `EXTENSION_REVISION` distinguishes standalone updates and `CLUSTER_REVISION` distinguishes cluster assemblies. Current defaults are `steemax/nexsteem:3.96.4-01-standalone-ext3` and `steemax/nexsteem:3.96.4-01-cluster-k8s10`, separate from the tested ext2/k8s9 references. Publish new immutable image references and provenance when extensions change, even if the Core version is unchanged.

### Keep source links aligned with the new release

Make derives `RELEASE_TAG` from the pinned Core version and extension/cluster revisions. `SOURCE_URL` defaults to the project repository, and `SOURCE_ARCHIVE_URL` derives the complete corresponding-source download URL from that release tag. These values are passed into all three image builds, including `make build-postgres`.

For a fork or different publication location, override `SOURCE_URL`, `RELEASE_TAG` or `SOURCE_ARCHIVE_URL`. `SOURCE_SNAPSHOT_SHA256` and `SOURCE_ARCHIVE_SHA256` optionally embed real 64-character checksums, with their distinct scopes. Update these values and retained notices consistently with the source release actually published alongside the images. See [INSTALL](INSTALL.md#corresponding-source-build-metadata).

The current images compile Core and repository formats from source while reusing pinned OS/JDK and PostgreSQL dependency layers. That does not establish bit-for-bit reproducibility, or permit substituting an older application image for newly compiled source.

### Existing browser-title settings

A saved title under **UI: Settings** overrides the new NexSteem default. If your existing installation still shows a previous custom title, change that setting manually to NexSteem if desired. The image upgrade does not force a rename of an operator's saved UI title, Helm release, namespace or data resources.

## Before changing a running deployment

Retain the installed image, chart, and their corresponding source/provenance. Take a coordinated, restorable backup of database metadata, blobs, configuration, and encryption keys before migrations. External backup automation is the operator's responsibility; replication and Helm hooks are not backups.

Check the release's migration requirements and test restore on the intended platform. A migrated database may not be compatible with an older binary. Recovery may require restoring the old database and blob snapshot together with the old application and keys.

## Standalone Docker

A standalone deployment has one writer. Stop it completely before replacing the application or making an offline copy of its H2 database. Never copy a live H2 database to another writer.

For Compose, stop the service while keeping the volume, set the new immutable image reference, and start it:

```sh
docker compose -f deploy/compose.yaml down
export NEXUS_IMAGE='steemax/nexsteem@sha256:PUBLISHED_NEW_STANDALONE_DIGEST'
docker compose -f deploy/compose.yaml up -d
```

Replace the placeholder with the actual release reference. If using a Docker archive, load the new archive first and use its declared tag. Do not add `--volumes` to the stop command.

## Upgrade a running Kubernetes cluster

Use the cluster application coordinator for application changes. Nexus and infrastructure images have separate upgrade procedures. Preserve the existing Helm release name, namespace and chart resource prefix: renaming the project repository or image does not require renaming Kubernetes resources, Secrets or PVCs. The chart is `deploy/helm/nexsteem`; its package name is `nexsteem`.

### Application upgrade

Prepare a compatible chart and image, make the image accessible to every worker, and obtain the **assembled application SHA-256** and **Core version** from its release manifest/provenance. The application SHA is not the registry digest.

Before starting, require one ready writer and all existing Nexus members caught up. Check healthy remote PostgreSQL synchronization, expected blob copies, and quorum. Keep the release's shared credentials and encryption key unchanged.

```sh
python3 scripts/upgrade-cluster.py \
  --kubeconfig /path/to/cluster.kubeconfig --context production \
  --namespace nexus --release nexus \
  --chart deploy/helm/nexsteem \
  --image steemax/nexsteem@sha256:PUBLISHED_NEW_CLUSTER_DIGEST \
  --application-sha256 FULL_64_CHARACTER_APPLICATION_SHA256 \
  --version NEW_CORE_VERSION \
  --report /path/to/private-reports/nexus-upgrade.json
```

The coordinator:

1. Reads current topology and values, preserves the old writer image, and updates desired manifests.
2. Replaces passive members one at a time and waits for the new application version and blob catch-up.
3. Uses fresh authority to atomically change the SQL release gate and expire the old writer's lease.
4. Replaces the previous writer and waits for exactly one ready writer on the new application.

The release gate prevents an old binary from restarting Core after the cluster has moved to the new application. StatefulSets use `OnDelete`; a plain `helm upgrade` changes desired manifests but does not complete this sequence. Do not manually roll every member at once.

On Helm upgrades, the post-upgrade schema Job applies idempotent extension guards and journal indexes after checking the current PostgreSQL primary and DCS. Bootstrap creates these schemas on initial installation. A failed hook stops the upgrade; inspect it before proceeding. The hook does not recreate the database or replace backups, and it is separate from Core's own migrations.

The coordinator writes a progress report. It does not offer automatic resume of an interrupted run. If it stops, inspect the report, actual member images, catch-up, writer state, and release gate before taking further action. Do not blindly rerun it or use `helm rollback` to undo a database migration.

After completion, verify `/_cluster/status`, matching application hashes and Core/PostgreSQL terms, normal copy policy, artifact checksums, new uploads, Docker/Helm clients, and identity-provider role behavior. Preserve the report with the release provenance.

### Changing resource budgets

Set per-container `resources.requests` and `resources.limits` in the component's values, including `nexus.initResources` and `jobs.*.resources` for init and Job containers. Coordinate the Nexus container memory budget with `nexus.javaOpts`; every member must be able to become the writer. See the [resource reference](../deploy/helm/nexsteem/README.md#resource-budgets).

A resource-only update keeps the existing image and application hash, so it does not require changing the SQL release gate. Apply the desired Helm values to the existing release. Nexus, PostgreSQL and etcd StatefulSets use `OnDelete`, so new budgets take effect only when their Pods are recreated. Replace Nexus passive members one at a time, wait for catch-up and health, and replace the writer last. For PostgreSQL replace healthy replicas first and the primary last using a controlled handover; replace etcd members individually while preserving quorum. Do not restart all members at once. Router Deployments use their normal rolling update.

### Moving an unchanged image to Docker Hub

Retagging the historical reference image from a local name to `steemax/nexsteem:3.96.4-01-cluster-k8s9` leaves its application SHA-256 unchanged. This is an **image-reference change**, not an application upgrade. `upgrade-cluster.py` rejects a target whose application hash is already active; do not invent a new hash or flip the release gate to bypass that check.

For an image-reference-only move, verify that the target image and application identity match the installed artifact. Update the installed Helm values while preserving all topology, Secrets, namespace and release-name settings, then replace members in a controlled sequence: passive members one at a time, wait for catch-up and health after each, and the writer last. Verify exactly one ready writer and normal remote-copy policy after the handover. Keep the existing SQL release gate unchanged. Apply the same distinction to an unchanged PostgreSQL image reference, using its ordered infrastructure procedure where appropriate.

Use the application coordinator when the assembled application hash actually changes, including the newly named NexSteem ext3/k8s10 build after it is accepted. Updated UI assets and notices produce a rebuilt application; an old reference image cannot stand in for that upgrade. Neither an image-only procedure nor the coordinator offers automatic resume after an interrupted run.

### PostgreSQL and etcd images

Use the separate infrastructure coordinator for compatible image updates:

```sh
python3 scripts/upgrade-cluster-infrastructure.py \
  --kubeconfig /path/to/cluster.kubeconfig --context production \
  --namespace nexus --release nexus \
  --chart deploy/helm/nexsteem \
  --postgres-image steemax/nexsteem@sha256:PUBLISHED_NEW_POSTGRES_DIGEST \
  --report /path/to/private-reports/postgres-upgrade.json
```

Nexus and PostgreSQL use different tags in the same Docker Hub repository, `steemax/nexsteem`. The reference PostgreSQL image is `steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64`. For etcd, use `--etcd-image` with its actual pinned official target reference; do not substitute a Nexus image tag. The script verifies running versions and health, then replaces members in order. PostgreSQL replicas are updated before the primary, with actual remote synchronous replication checked between steps; etcd members are replaced individually while preserving quorum. PVCs are retained.

This workflow rejects PostgreSQL major-version changes and etcd minor-version transitions. Those require separate, tested data migration procedures. Keep application and infrastructure upgrades separate so each resulting state can be verified.

## Automation and release records

The Make targets and upgrade scripts can be invoked from your CI system after release validation and operational approval. This repository does not provide a universal end-to-end GitHub Actions or n8n deployment pipeline, automated migration rollback, or automatic upgrade resume.

Record source commit, upstream pin, toolchain, extension revisions, registry digests, application SHA-256, chart version, backup reference, corresponding-source archive, and acceptance results for each deployment. Keep binary NOTICE/source URLs aligned with the actual release; do not claim that preserved Sencha notices settle the historical exception question. Keep credentials, kubeconfig files, and private reports outside the public repository.
