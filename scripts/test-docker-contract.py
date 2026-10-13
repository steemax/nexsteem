#!/usr/bin/env python3
"""Verify resumable uploads across restart, digest rejection and dedicated-port isolation."""
import argparse
import hashlib
import http.client
import json
import subprocess
import time
import urllib.error
import urllib.request
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--registry", default="http://127.0.0.1:18086")
args = parser.parse_args()
test = NexusTest(args.url, args.container)
name = "docker-resume-" + test.suffix
try:
    test.request("/service/rest/v1/repositories/docker/hosted", "POST", {
        "name": name, "online": True, "storage": {"blobStoreName": "default", "strictContentTypeValidation": True, "writePolicy": "ALLOW"},
        "docker": {"httpPort": 8090, "pathEnabled": True, "forceBasicAuth": True}})
    test.created.append(name)
    def request(path, method="GET", body=None, auth=True, headers=None):
        values = {"Content-Type": "application/octet-stream"}
        if auth:
            values["Authorization"] = "Basic " + test.basic
        values.update(headers or {})
        req = urllib.request.Request(args.registry + path, data=body, method=method, headers=values)
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.status, response.headers, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.headers, error.read()
    assert request("/v2/", auth=False)[0] == 401
    assert request("/service/rest/v1/repositories")[0] == 404
    payload = b"resume-with-integrity-" * 500
    digest = "sha256:" + hashlib.sha256(payload).hexdigest()
    status, headers, _ = request("/v2/fixture/hello/blobs/uploads/", "POST", b"")
    assert status == 202, status
    location = headers["Location"]
    status, _, _ = request(location, "PATCH", payload[:2000], headers={"Content-Range": "0-1999"})
    assert status == 202, status
    subprocess.run(["docker", "restart", "-t", "180", args.container], check=True, stdout=subprocess.PIPE)
    # The upload and its owner must survive a full Nexus/Jetty restart.
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            status, _, _ = request("/v2/")
            if status == 200:
                break
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(2)
    status, headers, _ = request(location)
    assert status == 204 and headers["Range"] == "0-1999", (status, dict(headers))
    status, _, _ = request(location, "PATCH", b"wrong-range", headers={"Content-Range": "0-10"})
    assert status == 416, status
    status, _, _ = request(location + "?digest=sha256:" + "0" * 64, "PUT", payload[2000:])
    assert status == 400, status
    status, _, _ = request(location + "?digest=" + digest, "PUT", b"")
    assert status == 201, status
    status, headers, downloaded = request("/v2/fixture/hello/blobs/" + digest)
    assert status == 200 and downloaded == payload and headers["Docker-Content-Digest"] == digest
    status, _, _ = request("/v2/fixture/hello/manifests/latest", "PUT", b'{"schemaVersion":2,"layers":[]}', headers={"Content-Type": "text/plain"})
    assert status == 415, status
    _, raw = test.request(f"/v2/{name}/fixture/hello/blobs/{digest}")
    assert raw == payload
    print("PASS Docker: partial upload / restart persistence / offset guard / wrong digest rejection / correct finish / path routing / isolated registry port", flush=True)
finally:
    test.cleanup()
