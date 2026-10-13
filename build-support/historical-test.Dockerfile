# syntax=docker/dockerfile:1.7
FROM nexus-core-extended:3.96.4-01 AS baseline
FROM nexus-extension-builder:3.96.4-01 AS builder
COPY --from=baseline /opt/nexus/bin/ /out/assembly/bin/
ARG FORMATS="pypi"
COPY build-support/set-extension-version.py build-support/embed-extension.py build-support/extension-provenance.py build-support/historical-module.sh /usr/local/bin/
COPY extensions/formats/nexus-repository-pypi/ /src/historical/nexus-repository-pypi/
RUN --mount=type=cache,target=/root/.m2 FORMATS="${FORMATS}" sh /usr/local/bin/historical-module.sh pypi
COPY extensions/formats/nexus-repository-conda/ /src/historical/nexus-repository-conda/
RUN --mount=type=cache,target=/root/.m2 FORMATS="${FORMATS}" sh /usr/local/bin/historical-module.sh conda
COPY extensions/formats/nexus-repository-p2/ /src/historical/nexus-repository-p2/
RUN --mount=type=cache,target=/root/.m2 FORMATS="${FORMATS}" sh /usr/local/bin/historical-module.sh p2
COPY extensions/formats/nexus-repository-r/ /src/historical/nexus-repository-r/
RUN --mount=type=cache,target=/root/.m2 FORMATS="${FORMATS}" sh /usr/local/bin/historical-module.sh r
COPY extensions/formats/nexus-repository-cocoapods/ /src/historical/nexus-repository-cocoapods/
RUN --mount=type=cache,target=/root/.m2 FORMATS="${FORMATS}" sh /usr/local/bin/historical-module.sh cocoapods
COPY extensions/formats/nexus-repository-conan/ /src/historical/nexus-repository-conan/
RUN --mount=type=cache,target=/root/.m2 FORMATS="${FORMATS}" sh /usr/local/bin/historical-module.sh conan
RUN python3 /usr/local/bin/extension-provenance.py /out/assembly
FROM nexus-core-extended:3.96.4-01
USER root
COPY --from=builder /out/assembly/bin/ /opt/nexus/bin/
COPY --from=builder /out/assembly/EXTENSIONS-PROVENANCE.json /out/assembly/webresources-cache-key /opt/nexus/
USER 200:200
