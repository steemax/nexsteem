PYTHON ?= python3
PLATFORM ?= linux/amd64
VERSION := $(shell $(PYTHON) -c 'import json; print(json.load(open("upstream.lock.json"))["version"])')
MAVEN_IMAGE := $(shell $(PYTHON) -c 'import json; print(json.load(open("upstream.lock.json"))["maven_image"])')
RUNTIME_IMAGE := $(shell $(PYTHON) -c 'import json; print(json.load(open("upstream.lock.json"))["runtime_image"])')
IMAGE ?= steemax/nexsteem:$(VERSION)-standalone-ext$(EXTENSION_REVISION)
EXTENSION_REVISION ?= 3
CLUSTER_REVISION ?= 10
CLUSTER_IMAGE ?= steemax/nexsteem:$(VERSION)-cluster-k8s$(CLUSTER_REVISION)
CLUSTER_BUILDER_IMAGE ?= nexus-extension-builder:$(VERSION)
POSTGRES_IMAGE ?= steemax/nexsteem:postgres-17-patroni4.1.5-r3-amd64
SOURCE_URL ?= https://github.com/steemax/nexsteem
RELEASE_TAG ?= $(VERSION)-ext$(EXTENSION_REVISION)-k8s$(CLUSTER_REVISION)
SOURCE_ARCHIVE_URL ?= $(SOURCE_URL)/releases/download/$(RELEASE_TAG)/nexsteem-$(RELEASE_TAG)-source.tar.gz
SOURCE_SNAPSHOT_SHA256 ?=
SOURCE_ARCHIVE_SHA256 ?=
SOURCE_BUILD_ARGS = --build-arg SOURCE_URL="$(SOURCE_URL)" \
  --build-arg SOURCE_ARCHIVE_URL="$(SOURCE_ARCHIVE_URL)" \
  --build-arg SOURCE_SNAPSHOT_SHA256="$(SOURCE_SNAPSHOT_SHA256)" \
  --build-arg SOURCE_ARCHIVE_SHA256="$(SOURCE_ARCHIVE_SHA256)"

.PHONY: help verify latest update prepare prepare-core build build-cluster build-cluster-from-source build-postgres source test test-keycloak test-ldap test-core up down smoke
help:
	@echo 'verify          Verify pinned sources and build configuration'
	@echo 'latest          Find the latest published open-source Core release'
	@echo 'update          Update upstream (TAG=release-X.Y.Z-NN selects a release)'
	@echo 'build           Build source and a linux/amd64 Docker image'
	@echo 'chart           Package and index the GitHub Pages Helm repository'
	@echo 'build-cluster   Build the separate Kubernetes image with internal replication'
	@echo 'build-cluster-from-source Build the complete Kubernetes image from source'
	@echo 'build-postgres  Build the pinned PostgreSQL / Patroni image'
	@echo 'SOURCE_URL / RELEASE_TAG / SOURCE_ARCHIVE_URL Set corresponding source links'
	@echo 'SOURCE_SNAPSHOT_SHA256 / SOURCE_ARCHIVE_SHA256 Optional source checksums'
	@echo 'test            Test the updater and Keycloak verifier in Docker'
	@echo 'up / smoke      Start local Core and check its REST API'
	@echo 'down            Stop the local installation while retaining data'

verify:
	$(PYTHON) scripts/upstream.py verify
	$(PYTHON) -m unittest discover -s tests -v
	@for script in build-support/*.sh build-support/nexus-launch; do sh -n "$$script" || exit; done

latest:
	$(PYTHON) scripts/upstream.py latest

update:
	$(PYTHON) scripts/upstream.py update $(if $(TAG),--tag $(TAG),)

prepare:
	$(PYTHON) scripts/upstream.py prepare

prepare-core:
	$(PYTHON) scripts/upstream.py prepare --core-only

build: prepare
	docker build --platform $(PLATFORM) --target runtime \
	  $(SOURCE_BUILD_ARGS) \
	  --build-arg NEXUS_VERSION=$(VERSION) \
	  --build-arg EXTENSION_REVISION=$(EXTENSION_REVISION) \
	  --build-arg MAVEN_IMAGE=$(MAVEN_IMAGE) --build-arg RUNTIME_IMAGE=$(RUNTIME_IMAGE) \
	  -f build-support/Dockerfile -t $(IMAGE) .

# Optional Kubernetes assembly; separate from the standalone image.
build-cluster:
	docker build --platform $(PLATFORM) \
	  $(SOURCE_BUILD_ARGS) \
	  --build-arg CORE_IMAGE=$(IMAGE) --build-arg BUILDER_IMAGE=$(CLUSTER_BUILDER_IMAGE) \
	  --build-arg NEXUS_VERSION=$(VERSION) --build-arg CLUSTER_REVISION=$(CLUSTER_REVISION) \
	  -f build-support/Dockerfile.cluster -t $(CLUSTER_IMAGE) .

build-cluster-from-source: prepare
	docker build --platform $(PLATFORM) --target extension-builder \
	  $(SOURCE_BUILD_ARGS) \
	  --build-arg MAVEN_IMAGE=$(MAVEN_IMAGE) --build-arg RUNTIME_IMAGE=$(RUNTIME_IMAGE) \
	  -f build-support/Dockerfile -t nexus-cluster-builder:$(VERSION) .
	docker build --platform $(PLATFORM) --target runtime \
	  $(SOURCE_BUILD_ARGS) \
	  --build-arg NEXUS_VERSION=$(VERSION) --build-arg EXTENSION_REVISION=$(EXTENSION_REVISION) \
	  --build-arg MAVEN_IMAGE=$(MAVEN_IMAGE) --build-arg RUNTIME_IMAGE=$(RUNTIME_IMAGE) \
	  -f build-support/Dockerfile -t nexus-cluster-base:$(VERSION) .
	$(MAKE) build-cluster IMAGE=nexus-cluster-base:$(VERSION) CLUSTER_BUILDER_IMAGE=nexus-cluster-builder:$(VERSION)

build-postgres:
	docker build --platform $(PLATFORM) \
	  $(SOURCE_BUILD_ARGS) \
	  -f deploy/cluster/postgres/Dockerfile -t $(POSTGRES_IMAGE) deploy/cluster/postgres

source:
	$(PYTHON) scripts/package-source.py

test-keycloak:
	mkdir -p build/logs
	docker run --rm --platform $(PLATFORM) \
	  --mount type=bind,src=$(CURDIR)/extensions/keycloak,dst=/input,readonly \
	  --mount type=bind,src=$(CURDIR)/build/logs,dst=/results \
	  $(MAVEN_IMAGE) \
	  sh -c 'cp -a /input /tmp/keycloak && cd /tmp/keycloak && mvn -B -ntp test > /results/keycloak-tests.log 2>&1; result=$$?; tail -40 /results/keycloak-tests.log; exit "$$result"'

test: verify test-keycloak

test-ldap: prepare
	docker build --platform $(PLATFORM) --target builder \
	  --build-arg MAVEN_IMAGE=$(MAVEN_IMAGE) -f build-support/Dockerfile \
	  -t nexus-extension-builder:$(VERSION) .
	docker build --platform $(PLATFORM) \
	  --build-arg BUILDER_IMAGE=nexus-extension-builder:$(VERSION) \
	  -f build-support/ldap-test.Dockerfile -t nexus-ldap-test .

test-core:
	$(PYTHON) scripts/test-core.py

up:
	NEXUS_IMAGE=$(IMAGE) docker compose -f deploy/compose.yaml up -d

down:
	docker compose -f deploy/compose.yaml down

smoke:
	$(PYTHON) scripts/smoke.py --url http://127.0.0.1:8081

.PHONY: chart
CHART_URL ?= https://steemax.github.io/nexsteem/charts
chart:
	mkdir -p charts
	helm lint deploy/helm/nexsteem
	helm package deploy/helm/nexsteem --destination charts
	helm repo index charts --url $(CHART_URL)
