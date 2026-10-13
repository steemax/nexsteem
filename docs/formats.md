# Repository format matrix

The required matrix contains **22 formats and 51 recipes**. The authoritative
list is [required-recipes.json](../config/required-recipes.json). The coverage
below describes historical acceptance of specific development builds; it does
not certify every subsequent build. See [verification](verification.md).

| Format | Required recipes | Historical client coverage |
|---|---|---|
| apt | hosted, proxy | apt-get update/download, signed GPG Release, package SHA-256 |
| cargo | hosted, proxy, group | cargo publish, sparse resolution, crate checksum, compile/run |
| cocoapods | proxy | pod install, CDN indexes/specs, source ZIP/checksum, credential isolation |
| composer | proxy | Composer v2 metadata, distribution ZIP, install/autoload |
| conan | hosted, proxy, group | Conan 2 create/upload/list/download, invalid SHA-1 rejection |
| conda | proxy | micromamba repodata/solve/download/extraction/checksum |
| docker | hosted, proxy, group | Docker login/push/pull, OCI/multiarch, upload resume, digest validation, public/private upstreams |
| gitlfs | hosted | git-lfs track/push/batch/pull, SHA-256, persistent locks |
| go | proxy, group | GOPROXY list/info/mod/zip, module download/checksum, merged versions |
| helm | hosted, proxy | Helm package/index, update/search/pull/template, checksum, offline proxy |
| huggingface | proxy | Hub file/snapshot downloads, commit cache, SafeTensors, offline SDK access |
| maven2 | hosted, proxy, group | Maven JAR/POM resolution, metadata and checksums |
| npm | hosted, proxy, group | npm publish/install, scoped packages, dist-tags/search, token authentication |
| nuget | hosted, proxy, group | .NET pack/push/restore/build/search, SHA-512, API keys, unlist/relist, group RBAC, public upstream |
| p2 | proxy | Tycho target-platform resolution/mirroring, metadata and JAR checksums |
| pypi | hosted, proxy, group | Twine upload, pip indexes/install, XML-RPC search |
| r | hosted, proxy, group | Rscript install.packages, PACKAGES indexes, version selection |
| raw | hosted, proxy, group | PUT/GET/Range and SHA-256 |
| rubygems | hosted, proxy, group | gem/Bundler push/install/dependencies, API keys/revocation, offline proxy, invalid archive rejection |
| swift | hosted, proxy, group | SwiftPM registry resolution, Package.swift, ZIP extraction/checksum |
| terraform | hosted, proxy, group | terraform init, provider/module registries, package checksums and PGP signatures |
| yum | hosted, proxy, group | DNF RPM upload/solve/install, checksums, invalid archive rejection, offline proxy |

Recipe registration is necessary but insufficient: test the native client,
metadata, permissions, error handling and stored bytes. A generic Raw endpoint
is not an implementation of another package protocol.

Format modules live in [extensions/formats](../extensions/formats/). They are
built separately from the unchanged upstream checkout. Historical open source
JARs are adapted to the current Spring/Jakarta/SQL interfaces; newer adapters
are independently implemented. Source provenance and applicable notices are
retained in each module's `SOURCE-ORIGIN.json` or `source-origins` directory.
Commercial binaries are not an implementation dependency, and the matrix does
not promise all Pro features. Details for the additional registry protocols are
in [modern-formats.md](modern-formats.md).
