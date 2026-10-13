# syntax=docker/dockerfile:1.7
FROM nexus-extension-builder:3.96.4-01
COPY extensions/platform/ /src/platform-check/
COPY build-support/set-extension-version.py /usr/local/bin/
RUN --mount=type=cache,target=/root/.m2 python3 /usr/local/bin/set-extension-version.py /src/BUILD-PROVENANCE.json /src/platform-check/pom.xml && mvn -B -ntp -f /src/platform-check/pom.xml -Dpublic '-Ppublic,!githook-checks' install
