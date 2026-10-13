# Additional repository protocols

`extensions/formats/nexus-repository-openformats` provides seven formats and
fourteen recipes below, plus NuGet hosted/proxy/group. These are independent
open implementations built on Nexus SQL ContentFacet, blob storage,
repository-view permissions, proxy caching and routing. See the full
[format matrix](formats.md) and [historical verification scope](verification.md).

| Format | Recipes | Protocol surface |
|---|---|---|
| Cargo | hosted, proxy, group | Sparse registry, framed publish, crate SHA-256, yank/unyank, merged index |
| Git LFS | hosted | Basic transfer, batch upload/download, SHA-256, verify and persistent locks |
| Go | proxy, group | GOPROXY list/latest/info/mod/zip and merged versions |
| Composer | proxy | Composer v2 metadata and cached distribution archives |
| Hugging Face | proxy | Hub metadata, resolve/raw, HEAD/GET, commit/ETag caching and binary models |
| Swift | hosted, proxy, group | Registry v1 releases/manifests/ZIP, SHA-256 and multipart publish |
| Terraform | hosted, proxy, group | Provider/module registries, signed SHA256SUMS, platforms and source archives |

UI forms use the corresponding public Core frontend forms. Server handlers and
per-repository permissions remain necessary; a form or recipe name alone is
not protocol support.

## Publishing and client endpoints

- Cargo uses `cargo publish` with `sparse+https://HOST/repository/NAME/`.
  Its Basic credential token is `Basic BASE64(USER:PASSWORD)`; use the realm's
  supported credential model for external identities.
- Git LFS uses `https://HOST/repository/NAME/info/lfs`.
- Swift accepts `PUT /repository/NAME/SCOPE/PACKAGE/VERSION` with multipart
  `source-archive`, optional `metadata` and `source-archive-signature`.
- Terraform provider ZIPs use
  `PUT /repository/NAME/v1/providers/NAMESPACE/TYPE/VERSION/OS/ARCH/package.zip`.
  Configure the Terraform Signing key to generate SHA256SUMS and its detached
  GPG signature. Module TGZ uploads use
  `PUT /repository/NAME/v1/modules/NAMESPACE/MODULE/PROVIDER/VERSION/archive.tar.gz`.
  Point Terraform CLI host/services configuration at the Nexus repository;
  repository-local discovery is available at `/.well-known/terraform.json`.

## Acceptance tools

`ProtocolValidationTest` covers metadata/routing limits, checksums and unsafe
archive entries. The `test-modern-*` scripts exercise real Nexus instances,
native clients, private upstream credentials and artifact origins.
`modern-upstream-fixture.py` provides deterministic upstream content;
`modern-runtime.Dockerfile` builds an isolated module/runtime fixture.

Historical runs included Cargo compile/resolve, Git LFS push/pull and locks,
Composer autoload, Go downloads, SwiftPM resolution, Terraform provider signature
verification/module installation, and Hub file/snapshot/offline-cache access.
Component names/versions, pagination and cross-origin credential isolation were
also exercised. NuGet has its own native .NET SDK and RBAC tests.

These results belong to the accepted development baselines, not every future
module change. Fixtures use isolated repositories, credentials and storage;
retain new provenance/reports and restore temporary security exceptions after
running them. Private development image IDs and logs are not distributed here.
