#!/usr/bin/env python3
"""Real Keycloak client-credentials token -> Nexus realm -> repository RBAC."""
import base64
import json
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

NEXUS = "http://127.0.0.1:8081"
KEYCLOAK = "http://127.0.0.1:18080/realms/nexus-local-test"
password = subprocess.check_output(["docker", "exec", "nexus-open-nexus-1", "cat",
                                    "/var/lib/nexus/admin.password"], text=True).strip()
admin = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()


def call(path, auth, method="GET", data=None):
    headers = {"Authorization": auth}
    if isinstance(data, (dict, list)):
        data = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    elif isinstance(data, bytes):
        headers["Content-Type"] = "application/octet-stream"
    request = urllib.request.Request(NEXUS + path, headers=headers, data=data, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as response:
        return response.code, response.read()


status, available = call("/service/rest/v1/security/realms/available", admin)
assert status == 200, status
assert "KeycloakRealm" in {entry["id"] for entry in json.loads(available)}, "KeycloakRealm not registered"
status, active = call("/service/rest/v1/security/realms/active", admin)
assert status == 200
active = json.loads(active)
if "KeycloakRealm" not in active:
    status, _ = call("/service/rest/v1/security/realms/active", admin, "PUT", active + ["KeycloakRealm"])
    assert status in [200, 204], status
print("Nexus KeycloakRealm registration and activation: PASS")

payload = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "nexus",
                                 "client_secret": "local-integration-test-only"}).encode()
request = urllib.request.Request(KEYCLOAK + "/protocol/openid-connect/token", data=payload)
with urllib.request.urlopen(request, timeout=15) as response:
    access_token = json.load(response)["access_token"]
bearer = "Bearer " + access_token

name = "keycloak-smoke-" + uuid.uuid4().hex[:12]
created = False
try:
    settings = {"name": name, "online": True, "storage": {"blobStoreName": "default",
                "strictContentTypeValidation": True, "writePolicy": "ALLOW"}}
    status, _ = call("/service/rest/v1/repositories/raw/hosted", admin, "POST", settings)
    assert status in [200, 201, 204], status
    created = True
    path = f"/repository/{name}/probe.txt"
    status, _ = call(path, admin, "PUT", b"keycloak-rbac-test\n")
    assert status in [200, 201, 204], status
    status, content = call(path, bearer)
    assert status == 200 and content == b"keycloak-rbac-test\n", status
    print("Real Keycloak access token + mapped reader role: HTTP 200 PASS")
    status, _ = call(path, bearer, "PUT", b"must-not-be-written\n")
    assert status == 403, status
    print("Reader write forbidden: HTTP 403 PASS")
    status, _ = call(path, "Bearer invalid-token")
    assert status == 401, status
    print("Invalid token rejected: HTTP 401 PASS")
    status, content = call(path, admin)
    assert status == 200 and content == b"keycloak-rbac-test\n"
    print("Local admin remains functional; artifact unchanged: PASS")
finally:
    if created:
        status, _ = call(f"/service/rest/v1/repositories/{name}", admin, "DELETE")
        assert status in [200, 204], status
