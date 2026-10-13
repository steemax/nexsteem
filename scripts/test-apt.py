#!/usr/bin/env python3
"""Verify a signed hosted APT repository using disposable local fixtures."""
import base64
import hashlib
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
password = subprocess.check_output(["docker", "exec", "nexus-open-nexus-1", "cat",
                                    "/var/lib/nexus/admin.password"], text=True).strip()
headers = {"Authorization": "Basic " + base64.b64encode(("admin:" + password).encode()).decode()}
name = "apt-smoke-" + uuid.uuid4().hex[:12]
url = "http://127.0.0.1:8081"


def request(path, method="GET", data=None):
    current_headers = dict(headers)
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        current_headers["Content-Type"] = "application/json"
    elif data is not None:
        current_headers["Content-Type"] = "application/octet-stream"
    req = urllib.request.Request(url + path, headers=current_headers, data=data, method=method)
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.status, response.read()


settings = {"name": name, "online": True,
            "storage": {"blobStoreName": "default", "strictContentTypeValidation": True, "writePolicy": "ALLOW"},
            "apt": {"distribution": "bookworm", "flat": False},
            "aptSigning": {"keypair": (root / "build/apt-smoke-key.asc").read_text(), "passphrase": ""}}
status, _ = request("/service/rest/v1/repositories/apt/hosted", "POST", settings)
assert status in [200, 201, 204]
try:
    package = (root / "build/nexus-smoke_1.0.0_all.deb").read_bytes()
    status, _ = request(f"/repository/{name}/", "POST", package)
    assert status == 201, status
    deadline = time.monotonic() + 45
    while True:
        try:
            status, packages = request(f"/repository/{name}/dists/bookworm/main/binary-all/Packages")
            break
        except urllib.error.HTTPError as error:
            if error.code != 404 or time.monotonic() >= deadline:
                raise
            time.sleep(1)
    fields = dict(line.split(": ", 1) for line in packages.decode().splitlines() if ": " in line)
    assert fields["Package"] == "nexus-smoke"
    assert fields["Version"] == "1.0.0"
    assert fields["SHA256"] == hashlib.sha256(package).hexdigest()
    status, downloaded = request(f"/repository/{name}/" + fields["Filename"])
    assert status == 200 and downloaded == package
    print("APT create / .deb upload / Packages index / download / SHA-256: PASS")
    def wait_metadata(filename):
        while True:
            try:
                return request(f"/repository/{name}/dists/bookworm/{filename}")[1]
            except urllib.error.HTTPError as error:
                if error.code != 404 or time.monotonic() >= deadline:
                    raise
                time.sleep(1)
    release = wait_metadata("Release")
    signature = wait_metadata("Release.gpg")
    (root / "build/apt-release").write_bytes(release)
    (root / "build/apt-release.gpg").write_bytes(signature)
    subprocess.run(["gpg", "--homedir", str(root / "build/gpg-smoke"), "--batch", "--verify",
                    str(root / "build/apt-release.gpg"), str(root / "build/apt-release")], check=True)
    print("APT Release GPG signature: PASS")
finally:
    request(f"/service/rest/v1/repositories/{name}", "DELETE")
    print("APT temporary repository cleanup: PASS")
