# syntax=docker/dockerfile:1.7
ARG BUILDER_IMAGE=nexus-extension-builder:3.96.4-01
FROM ${BUILDER_IMAGE}
COPY extensions/oidc/ /ports/oidc/
COPY build-support/set-extension-version.py /ports/set-extension-version.py
RUN --mount=type=cache,target=/root/.m2 \
    cd /src && python3 /ports/set-extension-version.py BUILD-PROVENANCE.json /ports/oidc/pom.xml \
    && mvn -B -ntp -f /ports/oidc/pom.xml -Dpublic '-Ppublic,!githook-checks' test
