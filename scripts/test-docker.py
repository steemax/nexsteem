#!/usr/bin/env python3
"""Use Docker login/push/pull and Registry API contracts on a disposable local Nexus."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import urllib.error
import urllib.request
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--registry-host", default="127.0.0.1")
parser.add_argument("--hosted-port", type=int, default=18086)
parser.add_argument("--proxy-port", type=int, default=18087)
parser.add_argument("--group-port", type=int, default=18088)
args = parser.parse_args()
test = NexusTest(args.url, args.container)
tags = []
reader = reader_role = None


def repository(kind, port, members=None, remote=None):
    name = f"native-docker-{kind}-{test.suffix}"
    settings = {"name": name, "online": True,
                "storage": {"blobStoreName": "default", "strictContentTypeValidation": True},
                "docker": {"httpPort": port, "forceBasicAuth": True, "v1Enabled": False, "pathEnabled": True}}
    if kind == "hosted":
        settings["storage"]["writePolicy"] = "ALLOW"
    elif kind == "group":
        settings["group"] = {"memberNames": members}
    else:
        settings.update({"proxy": {"remoteUrl": remote, "contentMaxAge": 1440, "metadataMaxAge": 1440},
                         "negativeCache": {"enabled": True, "timeToLive": 1440},
                         "httpClient": {"blocked": False, "autoBlock": False, "authentication": {
                             "type": "username", "username": "admin", "password": test.password}}})
        _, raw = test.request("/service/rest/v1/security/ssrf-protection")
        original = json.loads(raw)
        if args.container not in original.get("allowedDomains", []):
            test.ssrf_original = original
            updated = dict(original)
            updated["allowedDomains"] = list(original.get("allowedDomains", [])) + [args.container]
            test.request("/service/rest/v1/security/ssrf-protection", "PUT", updated)
    test.request("/service/rest/v1/repositories/docker/" + kind, "POST", settings)
    test.created.append(name)
    return name


def registry(path, method="GET", data=None, content_type="application/octet-stream", expected=None):
    headers = {"Authorization": "Basic " + test.basic, "Content-Type": content_type,
               "Accept": "application/vnd.docker.distribution.manifest.v2+json,application/vnd.oci.image.index.v1+json"}
    req = urllib.request.Request(f"http://{args.registry_host}:{args.hosted_port}" + path, method=method, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=60) as response:
        if expected is not None:
            assert response.status == expected, response.status
        return response.status, response.headers, response.read()


def blob(name, value):
    digest = "sha256:" + hashlib.sha256(value).hexdigest()
    _, headers, _ = registry(f"/v2/{name}/blobs/uploads/", "POST", b"", expected=202)
    location = headers["Location"]
    registry(location + "?digest=" + digest, "PUT", value, expected=201)
    return digest


try:
    hosted = repository("hosted", 8090)
    proxy = repository("proxy", 8091, remote=f"http://{args.container}:8090/")
    group = repository("group", 8092, members=[proxy, hosted])
    with tempfile.TemporaryDirectory(prefix="nexus-docker-") as directory:
        root = Path(directory)
        config = root / "client-config"
        config.mkdir(mode=0o700)
        docker = ["docker", "--config", str(config)]
        image = "nexus-open/acceptance-" + test.suffix
        (root / "payload.txt").write_text("native-nexus-registry-test\n")
        (root / "Dockerfile").write_text("FROM scratch\nCOPY payload.txt /payload.txt\n")
        origin = f"{args.registry_host}:{args.hosted_port}/{image}:native"
        tags.append(origin)
        test.run([*docker, "build", "--platform", "linux/amd64", "-t", origin, directory])
        for port in [args.hosted_port, args.proxy_port, args.group_port]:
            test.run([*docker, "login", f"{args.registry_host}:{port}", "--username", "admin", "--password-stdin"], input=test.password + "\n")
        test.run([*docker, "push", origin])
        _, _, raw = registry(f"/v2/{image}/manifests/native")
        manifest = json.loads(raw)
        for descriptor in [manifest["config"], *manifest["layers"]]:
            _, headers, value = registry(f"/v2/{image}/blobs/{descriptor['digest']}")
            assert headers["Docker-Content-Digest"] == descriptor["digest"]
            assert hashlib.sha256(value).hexdigest() == descriptor["digest"].split(":")[1]
        for kind, port in [("hosted", args.hosted_port), ("proxy", args.proxy_port), ("group", args.group_port)]:
            target = f"{args.registry_host}:{port}/{image}:native"
            if target not in tags:
                tags.append(target)
            test.run([*docker, "pull", "--platform", "linux/amd64", target])
            inspect = json.loads(test.run([*docker, "image", "inspect", target]))[0]
            assert inspect["Architecture"] == "amd64", inspect["Architecture"]
            print(f"docker {kind}: docker login / push / pull / image inspect / layer SHA-256 PASS", flush=True)
        # Build a valid arm64 variant: the fixture contains only architecture-independent text.
        _, _, config_bytes = registry(f"/v2/{image}/blobs/{manifest['config']['digest']}")
        arm_config = json.loads(config_bytes)
        arm_config["architecture"] = "arm64"
        arm_bytes = json.dumps(arm_config, separators=(",", ":")).encode()
        arm_digest = blob(image, arm_bytes)
        arm_manifest = dict(manifest)
        arm_manifest["config"] = dict(manifest["config"], digest=arm_digest, size=len(arm_bytes))
        arm_manifest_bytes = json.dumps(arm_manifest, separators=(",", ":")).encode()
        arm_manifest_digest = "sha256:" + hashlib.sha256(arm_manifest_bytes).hexdigest()
        registry(f"/v2/{image}/manifests/arm64", "PUT", arm_manifest_bytes, manifest["mediaType"], 201)
        index = {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [
            {"mediaType": manifest["mediaType"], "size": len(raw), "digest": "sha256:" + hashlib.sha256(raw).hexdigest(), "platform": {"os": "linux", "architecture": "amd64"}},
            {"mediaType": manifest["mediaType"], "size": len(arm_manifest_bytes), "digest": arm_manifest_digest, "platform": {"os": "linux", "architecture": "arm64"}},
        ]}
        registry(f"/v2/{image}/manifests/multi", "PUT", json.dumps(index, separators=(",", ":")).encode(), index["mediaType"], 201)
        target = f"{args.registry_host}:{args.hosted_port}/{image}:multi"
        tags.append(target)
        test.run([*docker, "pull", "--platform", "linux/arm64", target])
        assert json.loads(test.run([*docker, "image", "inspect", target]))[0]["Architecture"] == "arm64"
        print("docker hosted: multi-platform OCI index / arm64 Docker pull PASS", flush=True)
        _, _, tags_body = registry(f"/v2/{image}/tags/list?n=1")
        assert len(json.loads(tags_body)["tags"]) == 1
        _, _, catalog = registry("/v2/_catalog")
        assert image in json.loads(catalog)["repositories"]
        print("docker hosted: catalog / tags / pagination PASS", flush=True)
        reader_role = "docker-group-reader-" + test.suffix
        test.request("/service/rest/v1/security/roles", "POST", {
            "id": reader_role, "name": reader_role, "description": "Temporary group-only read acceptance",
            "privileges": [f"nx-repository-view-docker-{group}-read", f"nx-repository-view-docker-{group}-browse"], "roles": []})
        reader = "docker-reader-" + test.suffix
        reader_password = "disposable-reader-" + test.suffix
        test.request("/service/rest/v1/security/users", "POST", {"userId": reader, "firstName": "Docker", "lastName": "Reader",
            "emailAddress": "docker-reader@example.invalid", "password": reader_password, "status": "active", "roles": [reader_role]})
        reader_basic = base64.b64encode((reader + ":" + reader_password).encode()).decode()
        def reader_get(port, path):
            req = urllib.request.Request(f"http://{args.registry_host}:{port}" + path, headers={"Authorization": "Basic " + reader_basic})
            try:
                with urllib.request.urlopen(req, timeout=30) as response:
                    return response.status, response.read()
            except urllib.error.HTTPError as response:
                return response.code, response.read()
        assert reader_get(args.hosted_port, f"/v2/{image}/manifests/native")[0] == 403
        assert reader_get(args.group_port, f"/v2/{image}/manifests/native")[0] == 200
        assert image in json.loads(reader_get(args.group_port, "/v2/_catalog")[1])["repositories"]
        assert "native" in json.loads(reader_get(args.group_port, f"/v2/{image}/tags/list")[1])["tags"]
        print("docker group-only reader: manifest/catalog/tags 200, direct hosted 403 PASS", flush=True)
finally:
    if reader is not None:
        test.request("/service/rest/v1/security/users/" + reader, "DELETE")
    if reader_role is not None:
        test.request("/service/rest/v1/security/roles/" + reader_role, "DELETE")
    for tag in tags:
        try:
            test.run(["docker", "image", "rm", tag])
        except AssertionError:
            pass
    test.cleanup()
