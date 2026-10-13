# Repository and release setup

The source repository is **`https://github.com/steemax/nexsteem.git`**. Project container images use **Docker Hub `steemax/nexsteem`**, with component-specific tags. The Helm repository is served separately through GitHub Pages. The following commands are maintainer publication instructions; image and chart endpoints become usable only after publication.

## GitHub About

Suggested description, within GitHub's 350-character limit:

> NexSteem: source-built Nexus Repository Core with 22 repository formats, Keycloak OIDC and Kubernetes active/passive HA. TLS blob replication, PostgreSQL/Patroni, automatic failover, Nodes monitoring, Docker/Compose and Helm charts.

Suggested topics:

```text
nexsteem nexus-repository artifact-repository package-registry docker-registry
docker-compose helm kubernetes high-availability replication geo-replication
keycloak oidc oauth2 postgresql patroni etcd devops container-registry open-source
```

Use the project's own documentation or website for the About link. Describe actual capabilities rather than unmeasured performance, unlimited scaling, or commercial feature parity. The application name is NexSteem; preserve the upstream Core attribution and third-party notices separately from the project branding.

## First source publication

Create the empty GitHub repository `steemax/nexsteem` if it does not exist. Do not generate another README, license or .gitignore. Use `main` and the author's chosen public name with the noreply email shown in GitHub settings:

```sh
export GITHUB_NOREPLY_EMAIL='YOUR_GITHUB_NOREPLY_EMAIL'
git config user.name steemax
git config user.email "$GITHUB_NOREPLY_EMAIL"
```

These commands affect only this repository's Git identity. Preserve the upstream submodule, license files and notices. Review the source tree before committing:

```sh
python3 scripts/check-public-tree.py
make verify
git add .
git diff --cached --stat
git diff --cached --submodule=short
git commit -m "Initial public source release"
git remote add origin https://github.com/steemax/nexsteem.git
git push -u origin main
```

If `origin` already exists, verify its URL rather than adding it again. Review staged files before the final push. Keep kubeconfig files, deployment Secrets, private values, logs, data and Docker archives outside Git. Verify that `docs/images/logo.svg` and `docs/images/cluster-nodes.png` exist and contain no private information.

A recursive Git clone is required for a Core source build. Chart-only installation and prebuilt Docker deployment do not require the Core submodule. GitHub's automatic source ZIP omits submodule contents; supply a complete corresponding source archive with binary releases.

## Publish accepted NexSteem images

The final standalone ext3, cluster k8s10 and PostgreSQL r3 images have been built with verified notices and source metadata. Standalone UI/login/onboarding and all 51 recipe registrations were verified. Exact cluster boot and ordered upgrade passed with both members ready, matching hashes, remote PostgreSQL sync and two copies. The full artifact-client and fault matrix was not rerun for this UI/legal rebuild. Publication remains pending; the local image IDs below are not published registry digests.

Keep accepted Docker archives and their manifests/checksums outside the Git checkout. Verify archive checksums and image provenance before loading. After acceptance, authenticate with a Docker Hub account allowed to push to `steemax/nexsteem`:

```sh
docker login
```

| Role | Target Docker Hub tag | Status |
|---|---|---|
| Standalone NexSteem | `steemax/nexsteem:3.96.4-01-standalone-ext3` | Built; final standalone runtime accepted |
| Kubernetes NexSteem | `steemax/nexsteem:3.96.4-01-cluster-k8s10` | Boot/ordered upgrade accepted; both members ready, two copies, remote PostgreSQL sync |
| PostgreSQL / Patroni | `steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64` | Built; verifier passed; five integration scripts unchanged |

Use the exact archive paths from the finalized release manifest. The following commands publish the identified images **after finalized release packaging, with the accepted and pending scope stated in release notes**. Replace `/path/to` with the prepared release directory, `release-assets/nexsteem-3.96.4-01-ext3-k8s10/`, outside Git:

```sh
docker load --input /path/to/nexsteem-3.96.4-01-standalone-ext3-amd64.docker.tar.gz
docker tag sha256:f12707e1a977b43cffcd97c1ee35629896eae31f3b84bf688fe7e68753d7cdf3 steemax/nexsteem:3.96.4-01-standalone-ext3
docker push steemax/nexsteem:3.96.4-01-standalone-ext3

docker load --input /path/to/nexsteem-3.96.4-01-cluster-k8s10-amd64.docker.tar.gz
docker tag sha256:58a0e6e8c7d8abd7dff76059af3924c0432950eff6f5ec71096349fa375be6af steemax/nexsteem:3.96.4-01-cluster-k8s10
docker push steemax/nexsteem:3.96.4-01-cluster-k8s10

docker load --input /path/to/nexsteem-postgres-17-patroni4.1.5-r3-amd64.docker.tar.gz
docker tag sha256:307b88691f321b6fbae0da46629046c9d8d362dbbd09e94b807cc66e99ca76c8 steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64
docker push steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64
```

A tag or logo preview does not establish a rebuilt image. Preserve the historical `3.96.4-01-standalone-ext2` / `3.96.4-01-cluster-k8s9` tags and their own matching reference sources; do not replace their bytes or describe them as new branded assemblies.

Record registry digests from the actual pushes; they differ from image IDs and archive checksums. Publish the matching source release alongside the binaries, and verify pull access before advertising Kubernetes installation. Official etcd and HAProxy dependencies remain independently pinned.

## Publish the online Helm repository

The chart package is **`nexsteem`**, source directory **`deploy/helm/nexsteem`**, version **`0.1.5`**. Chart 0.1.5 defaults select the new k8s10 and PostgreSQL r3 images. Publish it with the documented accepted scope, after Docker Hub pull access is verified. Keep its existing Kubernetes resource naming and label conventions: renaming a chart package does not require renaming Helm releases or PVCs.

Prepare the package and index:

```sh
make chart
```

This prepares `charts/nexsteem-0.1.5.tgz` and `charts/index.yaml`. Keep the root `.nojekyll` file. These small Helm artifacts are intentionally committed to Git for static hosting; large Docker archives remain outside Git.

On GitHub, open repository **Settings → Pages**. Under Build and deployment, choose **Deploy from a branch**, select **`main`** and **`/(root)`**, then save. After pushing the prepared files and completing the Pages deployment, the Helm repository URL is:

```text
https://steemax.github.io/nexsteem/charts
```

See [GitHub's publishing-source instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site). Verify the served `index.yaml` and chart archive before marking the endpoint available. There is no online Helm repository until the push and Pages configuration are complete.

Users can then run:

```sh
helm repo add nexsteem https://steemax.github.io/nexsteem/charts
helm repo update
helm upgrade --install nexus nexsteem/nexsteem --version 0.1.5 \
  --namespace nexus --create-namespace --values production-values.yaml
```

Alternatively, use a Git checkout or GitHub Release chart package. OCI chart distribution is optional and is not required by this publication workflow.

## GitHub release assets and provenance

The designated new release tag is **`3.96.4-01-ext3-k8s10`**. Publish the accepted standalone and cluster artifacts with their corresponding sources, license/notice files, checksums and provenance:

- Release: [3.96.4-01-ext3-k8s10](https://github.com/steemax/nexsteem/releases/tag/3.96.4-01-ext3-k8s10).
- Complete corresponding sources: [nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz](https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz).

These links are intended publication locations; the release and assets are not claimed to be available yet. Include PostgreSQL r3 and chart 0.1.5 with the accepted cluster release. Identify each component's architecture, image tag/digest and application SHA-256. The application hash controls the SQL release gate; a registry digest does not replace it. Check that the embedded notices and source URLs match the published complete source archive.

The historical ext2/k8s9 binaries retain their matching reference sources. The Nodes screenshot remains an edited branding preview based on historical k8s9 and is not proof of the new Nodes runtime. The separate `nexsteem-onboarding.jpg` is an actual final standalone ext3 capture. Keep these captions distinct and replace the Nodes preview only after obtaining a genuine new-image capture. Do not label source-tree checks as runtime acceptance.

Preserving historical Sencha Ext JS notices does not establish that the Sonatype/Sencha FLOSS exception applies to NexSteem. The question remains unresolved in [THIRD-PARTY-NOTICES](../THIRD-PARTY-NOTICES.md); do not describe notices or source links as legal clearance.

Record the exact tested revisions and scenarios. The ext3 and k8s10 application SHA-256 values are `d5902a98b4380f787164cb75418c34d41bc346f5695cfd7a6b6f22ab2921c84f` and `75ffeab5f73e072e8034a4a7a35ff7bc3dd94ecc2663168eb22dbfd11235ee95`. These are application identities, not registry digests. A retagged image with an unchanged application SHA does not need a new application release gate; see [UPGRADING](../docs/UPGRADING.md). Preserve imported authorship and licenses; NOTICE grants no new third-party exception or trademark rights.

## Private vulnerability reporting

For the public repository, enable **Private vulnerability reporting** in its security settings when the maintainer is ready to monitor reports. Verify that the Security/Advisories page offers a private reporting link; disable the setting only if another private channel is documented. See [GitHub's configuration instructions](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting/configure-for-a-repository).

## Maintenance

Keep [INSTALL](../docs/INSTALL.md), [UPGRADING](../docs/UPGRADING.md), image tags, chart packages and release provenance aligned. Add working download links only after assets are uploaded. Keep deployment reports and credentials outside the public repository.
