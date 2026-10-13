# NexSteem: third-party sources and licenses

Project additions are attributed to **steemax**. Original copyright and license
notices remain intact. [LICENSE.txt](LICENSE.txt) covers EPL-licensed project
code and does not replace the terms of individual dependencies.

| Component | Source | License and attribution |
|---|---|---|
| Nexus Repository Core 3.96.4-01 | `upstream/nexus-public`, commit `e60cdf28e5808a25c2524edb8b1550a0c7f08d82` | EPL-1.0; original notices are retained in upstream and `licenses/` |
| npm, PyPI, Helm, R, P2, Conda, CocoaPods, Conan | Historical public Sonatype sources JARs from Maven Central | EPL-1.0; module `SOURCE-ORIGIN.json` records URL, version and SHA-256 |
| RubyGems Marshal/YAML and RPM/XML codecs | kkRepo commit `9c2b87ca3851a180e929f8139803212846412a9c` | Apache-2.0; LICENSE/NOTICE in `extensions/formats/nexus-repository-ruby-yum/src/main/resources/META-INF` |
| NuGetVersions | The same kkRepo commit | Apache-2.0; attribution and license in `extensions/formats/nexus-repository-openformats/source-origins` |
| Sencha Ext JS used by upstream | Nexus Core frontend dependency | Original notice describes GPLv3 and the upstream Sonatype/Sencha FLOSS exception; see unchanged `licenses/upstream-NOTICE.txt` |
| Java runtime, build tools, frontend packages and container OS packages | Pinned build images and dependency descriptors | Component-specific licenses apply; retain their license and notice files in binary releases |

The complete text of the historical Sonatype/Sencha FLOSS agreement was not found in this checkout. Its applicability to the independently modified NexSteem distribution has **not been established**. Preserving the upstream notices does not resolve that license question, create a new exception, or relicense Ext JS. No commercial Sencha SDK or Sonatype Community Edition/Pro server binaries are included in the source tree.

`build-support/build-extensions.sh` copies imported module source-origin,
LICENSE and NOTICE records into the application distribution. Binary releases
must also identify where to obtain their corresponding source version.

## Corresponding source for ext3 / k8s10

- Source repository: [steemax/nexsteem](https://github.com/steemax/nexsteem).
- Designated release: [3.96.4-01-ext3-k8s10](https://github.com/steemax/nexsteem/releases/tag/3.96.4-01-ext3-k8s10).
- Complete archive: [nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz](https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz).

The release and archive links identify the intended publication location; availability begins when the maintainer publishes the matching sources. Include the corresponding source archive, its checksum, build instructions, and all retained license/notice files with the binary release. Match it to the image's source and application provenance. The historical ext2/k8s9 binaries must retain their own matching reference sources; they are not rebuilt NexSteem ext3/k8s10 images.
