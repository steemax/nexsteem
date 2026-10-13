# syntax=docker/dockerfile:1.7
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre@sha256:bcc1a99b4bc676717bdc9841c363072ce10e3f34462c4017f7df08e730f485b0

FROM node:22.19.0-bookworm-slim@sha256:4a4884e8a44826194dff92ba316264f392056cbe243dcc9fd3551e71cea02b90 AS node
FROM ${MAVEN_IMAGE} AS builder
COPY --from=node /usr/local/ /usr/local/
RUN apt-get update && apt-get install -y --no-install-recommends python3 git \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY build/source/ /src/
COPY build-support/build-core.sh /usr/local/bin/build-core
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.cache/node/corepack \
    --mount=type=cache,target=/root/.yarn \
    sh /usr/local/bin/build-core

FROM ${RUNTIME_IMAGE} AS java-runtime
FROM builder AS extension-builder
ARG INCLUDE_FORMATS=true
ARG FORMAT_MODULES=""
ARG SOURCE_URL=https://github.com/steemax/nexsteem
ARG SOURCE_ARCHIVE_URL=https://github.com/steemax/nexsteem/releases/download/3.96.4-01-ext3-k8s10/nexsteem-3.96.4-01-ext3-k8s10-source.tar.gz
ARG SOURCE_SNAPSHOT_SHA256=""
ARG SOURCE_ARCHIVE_SHA256=""
COPY LICENSE.txt NOTICE.txt THIRD-PARTY-NOTICES.md /src/project-notices/
COPY licenses/ /src/project-notices/licenses/
COPY extensions/oidc/ /src/extensions/oidc/
COPY extensions/ldap/ /src/extensions/ldap/
COPY extensions/platform/ /src/extensions/platform/
COPY extensions/formats/ /src/extensions/formats/
COPY build-support/embed-extension.py /usr/local/bin/embed-extension.py
COPY build-support/set-extension-version.py /usr/local/bin/set-extension-version.py
COPY build-support/extension-provenance.py /usr/local/bin/extension-provenance.py
COPY build-support/package-notices.py /usr/local/bin/package-notices.py
COPY build-support/build-extensions.sh /usr/local/bin/build-extensions.sh
RUN --mount=type=cache,target=/root/.m2 \
    INCLUDE_FORMATS=${INCLUDE_FORMATS} FORMAT_MODULES="${FORMAT_MODULES}" sh /usr/local/bin/build-extensions.sh
FROM debian:12-slim AS runtime
ARG NEXUS_VERSION=3.96.4-01
ARG EXTENSION_REVISION=1
ARG SOURCE_URL=https://github.com/steemax/nexsteem
LABEL org.opencontainers.image.title="NexSteem artifact repository" \
      org.opencontainers.image.version="${NEXUS_VERSION}" \
      org.opencontainers.image.revision="${NEXUS_VERSION}+ext${EXTENSION_REVISION}" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.documentation="${SOURCE_URL}/blob/main/docs/INSTALL.md" \
      org.opencontainers.image.licenses="EPL-1.0"
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl openssl util-linux libstdc++6 zlib1g tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 200 nexus && useradd --uid 200 --gid 200 --home-dir /var/lib/nexus --no-create-home nexus \
    && mkdir -p /var/lib/nexus/etc /opt/nexus \
    && chown -R 200:200 /var/lib/nexus
COPY --from=java-runtime /opt/java/openjdk /opt/nexus-java
COPY --from=extension-builder /out/assembly/ /opt/nexus/
COPY --from=builder /src/BUILD-PROVENANCE.json /opt/nexus/BUILD-PROVENANCE.json
COPY build-support/nexus-launch /usr/local/bin/nexus-launch
ENV JAVA_HOME=/opt/nexus-java \
    NEXUS_HOME=/opt/nexus \
    NEXUS_DATA=/var/lib/nexus \
    NEXUS_JAVA_OPTS="-Xms512m -Xmx2g -XX:MaxDirectMemorySize=2g"
RUN chmod 0755 /usr/local/bin/nexus-launch && /opt/nexus-java/bin/java -version
WORKDIR /opt/nexus
USER 200:200
EXPOSE 8081
VOLUME ["/var/lib/nexus"]
HEALTHCHECK --interval=30s --timeout=7s --start-period=180s \
  CMD curl --fail --silent http://127.0.0.1:8081/service/rest/v1/open/health || exit 1
ENTRYPOINT ["/usr/local/bin/nexus-launch"]
