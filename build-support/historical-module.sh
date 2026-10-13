#!/bin/sh
set -eu
format=$1
case " ${FORMATS:-pypi} " in *" $format "*) ;; *) exit 0 ;; esac
module=/src/historical/nexus-repository-$format
python3 /usr/local/bin/set-extension-version.py /src/BUILD-PROVENANCE.json "$module/pom.xml"
mvn -B -ntp -f "$module/pom.xml" -Dpublic '-Ppublic,!githook-checks' -DskipTests install
mvn -B -ntp -f "$module/pom.xml" dependency:copy-dependencies -DincludeArtifactIds=swagger-annotations,jsoup,javax.mail,alphanumeric-comparator -DincludeScope=runtime -DoutputDirectory="$module/target/runtime-libs"
python3 /usr/local/bin/embed-extension.py /out/assembly "$module/target" --artifact "nexus-repository-$format" --extra-dir "$module/target/runtime-libs"
