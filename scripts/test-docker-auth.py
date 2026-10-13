#!/usr/bin/env python3
"""Verify private Registry token scopes, CDN credential isolation and digest validation."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--registry", default="http://127.0.0.1:18087")
args = parser.parse_args()
test = NexusTest(args.url, args.container)
fixture = "nexus-research-docker-upstream"
root = Path(__file__).resolve().parents[1]
created_fixture = False
name = "docker-private-" + test.suffix
try:
    subprocess.run(["docker", "run", "-d", "--platform", "linux/amd64", "--name", fixture, "--network", "nexus-open_default", "-p", "127.0.0.1:18089:9000",
                    "--mount", f"type=bind,src={root / 'tests/fixtures/docker-token-server.py'},dst=/fixture.py,readonly",
                    "nexus-modern-clients:debian13", "python3", "/fixture.py"], check=True, stdout=subprocess.PIPE)
    created_fixture = True
    for _ in range(30):
        try:
            urllib.request.urlopen("http://127.0.0.1:18089/events", timeout=2).close()
            break
        except OSError:
            time.sleep(1)
    _, raw = test.request("/service/rest/v1/security/ssrf-protection")
    test.ssrf_original = json.loads(raw)
    settings = dict(test.ssrf_original)
    settings["allowedDomains"] = list(dict.fromkeys(settings.get("allowedDomains", []) + [fixture]))
    test.request("/service/rest/v1/security/ssrf-protection", "PUT", settings)
    test.created.append(name)
    test.request("/service/rest/v1/repositories/docker/proxy", "POST", {
        "name": name, "online": True, "storage": {"blobStoreName": "default", "strictContentTypeValidation": True},
        "docker": {"httpPort": 8091, "forceBasicAuth": True, "pathEnabled": True},
        "proxy": {"remoteUrl": f"http://{fixture}:9000/", "contentMaxAge": 1440, "metadataMaxAge": 1440},
        "negativeCache": {"enabled": True, "timeToLive": 1440},
        "httpClient": {"blocked": False, "autoBlock": False, "authentication": {"type": "username", "username": "fixture", "password": "registry-fixture-only", "preemptive": True}}})
    def get(path):
        request = urllib.request.Request(args.registry + path, headers={"Authorization": "Basic " + test.basic})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as response:
            return response.code, response.read()
    status, body = get("/v2/private/demo/manifests/native")
    assert status == 200, (status, body)
    digest = json.loads(body)["config"]["digest"]
    status, body = get("/v2/private/demo/blobs/" + digest)
    assert status == 200 and "sha256:" + hashlib.sha256(body).hexdigest() == digest, status
    assert json.loads(get("/v2/_catalog")[1])["repositories"] == ["private/demo"]
    assert json.loads(get("/v2/private/demo/tags/list?n=1")[1])["tags"] == ["native"]
    assert json.loads(get("/v2/private/demo/tags/list?n=1&last=native")[1])["tags"] == ["next"]
    assert json.loads(get("/v2/private/demo/tags/list?n=1")[1])["tags"] == ["native"]
    assert get("/v2/corrupt/blobs/" + digest)[0] >= 400
    assert get("/v2/evil/manifests/native")[0] >= 400
    with urllib.request.urlopen("http://127.0.0.1:18089/events", timeout=5) as response:
        events = json.load(response)
    cdn = [event for event in events if event["port"] == 9001]
    assert cdn and all(not event["credential_present"] and not event["cookie_present"] and event["path"] == "/content" for event in cdn), cdn
    scopes = {scope for event in events if event["path"] == "/auth/token" for scope in event["scope"]}
    assert "registry:catalog:*" in scopes and "repository:private/demo:pull" in scopes and all(":push" not in scope for scope in scopes), scopes
    _, raw = test.request("/service/rest/v1/assets?repository=" + name)
    assert all("/corrupt/" not in asset["path"] for asset in json.loads(raw)["items"])
    print("PASS Docker: private Bearer exchange / derived read-only scopes / catalog and paginated tag auth / distinct page cache / foreign CDN credential isolation / untrusted token realm rejection / corrupt digest not stored")
    # Core's REST format uses bearerToken; its encrypted configuration uses bearerTokenId.
    test.request("/service/rest/v1/repositories/" + name, "DELETE")
    test.created.remove(name)
    name = "docker-bearer-" + test.suffix
    test.created.append(name)
    test.request("/service/rest/v1/repositories/docker/proxy", "POST", {
        "name": name, "online": True, "storage": {"blobStoreName": "default", "strictContentTypeValidation": True},
        "docker": {"httpPort": 8091, "forceBasicAuth": True, "pathEnabled": True},
        "proxy": {"remoteUrl": f"http://{fixture}:9000/", "contentMaxAge": 1440, "metadataMaxAge": 1440},
        "negativeCache": {"enabled": True, "timeToLive": 1440},
        "httpClient": {"blocked": False, "autoBlock": False, "authentication": {"type": "bearerToken", "bearerToken": "test-read-only"}}})
    assert get("/v2/private/demo/manifests/native")[0] == 200
    assert get("/v2/private/demo/blobs/" + digest)[0] == 200
    print("PASS Docker: standard REST upstream Bearer secret / encryption / reuse of a closed connector port")
finally:
    test.cleanup()
    if created_fixture:
        subprocess.run(["docker", "rm", "-f", fixture], check=True, stdout=subprocess.PIPE)
