# syntax=docker/dockerfile:1.7
# Keep the completed test directory server without retaining an obsolete full Core builder.
ARG FIXTURE_IMAGE=nexus-ldap-test
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre@sha256:bcc1a99b4bc676717bdc9841c363072ce10e3f34462c4017f7df08e730f485b0
FROM ${FIXTURE_IMAGE} AS fixture
FROM ${RUNTIME_IMAGE}
COPY --from=fixture /fixture/ /fixture/
ENTRYPOINT ["java", "-cp", "/fixture:/fixture/lib/*", "DirectoryFixture"]
