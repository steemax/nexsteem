# syntax=docker/dockerfile:1.7
FROM nexus-extension-builder:3.96.4-01
ARG FORMAT=npm
COPY extensions/formats/ /ports/
RUN --mount=type=cache,target=/root/.m2 \
    cd /src && mvn -B -ntp -f /ports/nexus-repository-${FORMAT}/pom.xml -Dpublic '-Ppublic,!githook-checks' -DskipTests compile
