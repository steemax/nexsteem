#!/bin/sh
set -eu
cd /src
# This public tag does not ship mvnw, .mvn, .yarnrc.yml or a Yarn lockfile.
# Supply Yarn's workspace linker in the disposable build tree.
if [ -f yarn.lock ]; then immutable=true; else immutable=false; fi
cat > .yarnrc.yml <<EOF
nodeLinker: node-modules
enableImmutableInstalls: $immutable
enableTelemetry: false
EOF
if [ "$immutable" = true ]; then corepack yarn install --immutable; else corepack yarn install; fi
# Frontend linting uses private Sonatype Git/config files absent from the export.
# The actual frontend compilation and Java test compilation still run.
mvn -B -ntp -Dpublic '-Ppublic,!githook-checks' -DskipTests -Dnpm.skipTests=true \
  -Dnpm.build=build-no-lint clean install
assembly=public/selfhosted/assemblies/nexus-repository-core/target/assembly
test -d "$assembly/etc"
find "$assembly/bin" -name 'nexus-repository-core-*.jar' -type f | head -1 | grep -q .
mkdir -p /out/assembly
cp -a "$assembly/." /out/assembly/
cp yarn.lock /out/yarn.lock
mkdir -p /out/assembly/licenses
cp LICENSE.txt /out/assembly/licenses/NEXUS-CORE-LICENSE.txt
