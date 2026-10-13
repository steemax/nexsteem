#!/bin/sh
set -eu
cd /src
build_module() {
  module=$1
  artifact=$2
  runtime_ids=$3
  python3 /usr/local/bin/set-extension-version.py BUILD-PROVENANCE.json "$module/pom.xml"
  mvn -B -ntp -f "$module/pom.xml" -Dpublic '-Ppublic,!githook-checks' -DskipTests install
  mvn -B -ntp -f "$module/pom.xml" dependency:copy-dependencies \
    "-DincludeArtifactIds=$runtime_ids" -DincludeScope=runtime "-DoutputDirectory=/src/$module/target/runtime-libs"
  python3 /usr/local/bin/embed-extension.py /out/assembly \
    "$module/target" --artifact "$artifact" --extra-dir "$module/target/runtime-libs"
  license_dir="/out/assembly/licenses/open-extensions/$artifact"
  mkdir -p "$license_dir"
  for origin in "$module"/SOURCE-ORIGIN.json "$module"/source-origins/* "$module"/src/main/resources/META-INF/LICENSE* "$module"/src/main/resources/META-INF/NOTICE*; do
    if [ -f "$origin" ]; then cp "$origin" "$license_dir/"; fi
  done
}
build_module extensions/platform nexus-open-platform postgresql
build_module extensions/ldap nexus-ldap-plugin none
build_module extensions/oidc nexus-open-oidc none
if [ "${INCLUDE_FORMATS:-true}" = true ]; then
for module in extensions/formats/nexus-repository-*; do
  format=${module##*/nexus-repository-}
  if [ -n "${FORMAT_MODULES:-}" ]; then
    case " $FORMAT_MODULES " in *" $format "*) ;; *) continue ;; esac
  fi
  runtime_ids=swagger-annotations
  case "$format" in
    pypi) runtime_ids=swagger-annotations,jsoup,javax.mail ;;
    r) runtime_ids=swagger-annotations,javax.mail,alphanumeric-comparator ;;
  esac
  build_module "$module" "$(basename "$module")" "$runtime_ids"
done
fi
python3 /usr/local/bin/package-notices.py --project-root /src/project-notices \
  --output /out/assembly/licenses/project --snapshot-root /src \
  --source-url "${SOURCE_URL:-https://github.com/steemax/nexsteem}" \
  --source-archive-url "${SOURCE_ARCHIVE_URL:-https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz}" \
  --source-snapshot-sha256 "${SOURCE_SNAPSHOT_SHA256:-}" \
  --source-archive-sha256 "${SOURCE_ARCHIVE_SHA256:-}"
python3 /usr/local/bin/extension-provenance.py /out/assembly
