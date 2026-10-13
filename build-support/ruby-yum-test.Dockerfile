FROM nexus-extension-builder:3.96.4-01
COPY extensions/formats/nexus-repository-ruby-yum /src/ruby-yum-test
COPY build-support/set-extension-version.py /usr/local/bin/set-extension-version.py
RUN --mount=type=cache,target=/root/.m2 python3 /usr/local/bin/set-extension-version.py /src/BUILD-PROVENANCE.json /src/ruby-yum-test/pom.xml && mvn -B -ntp -f /src/ruby-yum-test/pom.xml -Dpublic '-Ppublic,!githook-checks' test
