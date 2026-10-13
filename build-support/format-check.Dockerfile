# syntax=docker/dockerfile:1.7
FROM nexus-extension-builder:3.96.4-01
ARG FORMAT
COPY extensions/formats/ /src/format-check/
COPY build-support/set-extension-version.py /usr/local/bin/set-extension-version.py
RUN --mount=type=cache,target=/root/.m2 \
    python3 /usr/local/bin/set-extension-version.py /src/BUILD-PROVENANCE.json /src/format-check/nexus-repository-${FORMAT}/pom.xml \
    && mvn -B -ntp -f /src/format-check/nexus-repository-${FORMAT}/pom.xml -Dpublic '-Ppublic,!githook-checks' -DskipTests install
