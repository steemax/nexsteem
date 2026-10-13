# syntax=docker/dockerfile:1.7
FROM nexus-extension-builder:3.96.4-01
COPY extensions/cluster-api/ /src/cluster-storage-api/
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f /src/cluster-storage-api/pom.xml -Dpublic '-Ppublic,!githook-checks' install
COPY extensions/cluster-storage/ /src/cluster-storage/
COPY extensions/cluster-control/src/main/resources/sql/cluster-control.sql /src/cluster-control.sql
ARG TEST_POSTGRES=false
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=storage-pg-env,required=false \
    if [ "$TEST_POSTGRES" = true ]; then \
      . /run/secrets/storage-pg-env; \
      export NX_STORAGE_TEST_PASSWORD="$POSTGRES_PASSWORD" NX_STORAGE_TEST_JDBC_URL=jdbc:postgresql://host.docker.internal:18155/nx_storage_native; \
    fi; \
    mvn -B -ntp -f /src/cluster-storage/pom.xml -Dpublic '-Ppublic,!githook-checks' test install
