# syntax=docker/dockerfile:1.7
ARG BUILDER_IMAGE=nexus-extension-builder:3.96.4-01
FROM ${BUILDER_IMAGE} AS test
COPY extensions/ldap/ /ports/ldap/
COPY build-support/set-extension-version.py /ports/set-extension-version.py
RUN --mount=type=cache,target=/root/.m2 \
    cd /src && python3 /ports/set-extension-version.py BUILD-PROVENANCE.json /ports/ldap/pom.xml \
    && mvn -B -ntp -f /ports/ldap/pom.xml -Dpublic '-Ppublic,!githook-checks' test
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -f /ports/ldap/pom.xml dependency:copy-dependencies -DincludeArtifactIds=unboundid-ldapsdk \
    -DincludeScope=test -DoutputDirectory=/fixture/lib
COPY tests/ldap/DirectoryFixture.java /fixture/
RUN javac -cp '/fixture/lib/*' -d /fixture /fixture/DirectoryFixture.java
ENTRYPOINT ["java", "-cp", "/fixture:/fixture/lib/*", "DirectoryFixture"]
