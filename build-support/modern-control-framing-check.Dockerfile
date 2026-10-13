# syntax=docker/dockerfile:1.7
FROM nexus-extension-builder:3.96.4-01
COPY extensions/cluster-api/ /src/cluster-api/
COPY extensions/cluster-control/ /src/cluster-control/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f /src/cluster-api/pom.xml -Dpublic '-Ppublic,!githook-checks' install && \
    mvn -B -ntp -f /src/cluster-control/pom.xml -Dpublic '-Ppublic,!githook-checks' -Dtest=FrontendRequestFramingTest test
