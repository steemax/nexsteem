# syntax=docker/dockerfile:1.7
ARG BASE_RUNTIME=nexus-core-extended:3.96.4-01
ARG BUILDER_IMAGE=nexus-extension-builder:3.96.4-01
FROM ${BUILDER_IMAGE} AS modern-builder
COPY extensions/formats/nexus-repository-openformats/ /src/modern/
COPY build-support/set-extension-version.py build-support/embed-extension.py build-support/extension-provenance.py /src/modern-tools/
RUN --mount=type=cache,target=/root/.m2 python3 /src/modern-tools/set-extension-version.py /src/BUILD-PROVENANCE.json /src/modern/pom.xml \
    && mvn -B -ntp -f /src/modern/pom.xml -Dpublic '-Ppublic,!githook-checks' -DskipTests=false install
FROM ${BASE_RUNTIME} AS modern-base
FROM modern-builder AS modern-embed
COPY --from=modern-base /opt/nexus/ /modern-assembly/
RUN python3 /src/modern-tools/embed-extension.py /modern-assembly /src/modern/target --artifact nexus-repository-openformats \
    && python3 /src/modern-tools/extension-provenance.py /modern-assembly
FROM modern-base AS runtime
COPY --from=modern-embed /modern-assembly/ /opt/nexus/
