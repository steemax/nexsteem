#!/bin/sh
set -eu
cd /cluster-src
mkdir -p /out/cluster/lib
cp /out/assembly/BUILD-PROVENANCE.json BUILD-PROVENANCE.json
for module in cluster-api cluster-control cluster-storage cluster-web; do
  artifact="nexus-$module"
  directory="extensions/$module"
  python3 /cluster-tools/set-extension-version.py BUILD-PROVENANCE.json "$directory/pom.xml"
  mvn -B -ntp -f "$directory/pom.xml" -Dpublic '-Ppublic,!githook-checks' install
  mvn -B -ntp -f "$directory/pom.xml" dependency:copy-dependencies \
    -DincludeScope=runtime "-DoutputDirectory=/cluster-src/$directory/target/runtime-libs"
  python3 /cluster-tools/embed-extension.py /out/assembly "$directory/target" \
    --artifact "$artifact" --extra-dir "$directory/target/runtime-libs"
  for file in "$directory"/target/"$artifact"-*.jar "$directory"/target/runtime-libs/*.jar; do
    [ -f "$file" ] || continue
    case "$file" in *-sources.jar|*-javadoc.jar|*-tests.jar) continue;; esac
    cp "$file" /out/cluster/lib/
  done
done
python3 /cluster-tools/package-notices.py --project-root /cluster-src/project-notices \
  --output /out/assembly/licenses/project --snapshot-root /cluster-src \
  --component 'NexSteem with Kubernetes replication' \
  --source-url "${SOURCE_URL:-https://github.com/steemax/nexsteem}" \
  --source-archive-url "${SOURCE_ARCHIVE_URL:-https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz}" \
  --source-snapshot-sha256 "${SOURCE_SNAPSHOT_SHA256:-}" \
  --source-archive-sha256 "${SOURCE_ARCHIVE_SHA256:-}"
mkdir -p /out/cluster/licenses
cp -a /out/assembly/licenses/project /out/cluster/licenses/
python3 /cluster-tools/extension-provenance.py /out/assembly
python3 /cluster-tools/cluster-provenance.py /cluster-src /out/cluster/CLUSTER-PROVENANCE.json
cp /out/assembly/BUILD-PROVENANCE.json /out/cluster/
cp /out/assembly/EXTENSIONS-PROVENANCE.json /out/cluster/
cp /out/assembly/licenses/NEXUS-CORE-LICENSE.txt /out/cluster/LICENSE.txt
