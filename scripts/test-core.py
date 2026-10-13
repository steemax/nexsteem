#!/usr/bin/env python3
"""Create disposable hosted repositories, upload artifacts and verify bytes."""
import argparse
import base64
import hashlib
import io
import json
import subprocess
import urllib.request
import uuid
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:8081")
parser.add_argument("--container", default="nexus-open-nexus-1")
args = parser.parse_args()
password = subprocess.check_output(["docker", "exec", args.container, "cat",
                                    "/var/lib/nexus/admin.password"], text=True).strip()
authorization = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
url = args.url.rstrip("/")


def request(path, method="GET", data=None, content_type="application/octet-stream"):
    headers = {"Authorization": authorization}
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        content_type = "application/json"
    if data is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.status, response.read()


def verify_blob(path, value):
    status, _ = request(path, "PUT", value)
    assert status in [200, 201, 204], status
    status, actual = request(path)
    assert status == 200
    assert hashlib.sha256(actual).digest() == hashlib.sha256(value).digest()


suffix = uuid.uuid4().hex[:12]
created = []
try:
    for kind in ["raw", "maven"]:
        name = f"smoke-{kind}-{suffix}"
        settings = {
            "name": name, "online": True,
            "storage": {"blobStoreName": "default", "strictContentTypeValidation": True,
                        "writePolicy": "ALLOW"},
        }
        if kind == "maven":
            settings["maven"] = {"versionPolicy": "RELEASE", "layoutPolicy": "STRICT",
                                  "contentDisposition": "INLINE"}
        status, _ = request(f"/service/rest/v1/repositories/{kind}/hosted", "POST", settings)
        assert status in [200, 201, 204], status
        created.append(name)
        if kind == "raw":
            verify_blob(f"/repository/{name}/probe.bin", b"nexus-source-build-smoke\n")
        else:
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, "w") as jar:
                jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
            path = f"/repository/{name}/org/nexus/extensions/probe/1.0.0/probe-1.0.0"
            verify_blob(path + ".jar", archive.getvalue())
            pom = b'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>org.nexus.extensions</groupId><artifactId>probe</artifactId><version>1.0.0</version></project>'''
            verify_blob(path + ".pom", pom)
        print(f"{kind}: create / upload / download / SHA-256 PASS")
finally:
    for name in reversed(created):
        request(f"/service/rest/v1/repositories/{name}", "DELETE")
        print(f"{name}: cleanup PASS")
