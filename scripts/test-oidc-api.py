#!/usr/bin/env python3
"""Real configured OAuth provider token -> OAuth2Realm -> repository RBAC."""
import base64
import argparse
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18084")
parser.add_argument("--container", default="nexus-research-oidc")
parser.add_argument("--keycloak", default="http://127.0.0.1:18085/realms/nexus-open-oidc-test")
args = parser.parse_args()
NEXUS = args.url.rstrip("/")
KEYCLOAK = args.keycloak.rstrip("/")
password = os.environ.get("NEXUS_ADMIN_PASSWORD") or subprocess.check_output(
    ["docker", "exec", args.container, "cat", "/var/lib/nexus/admin.password"], text=True).strip()
admin = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
def call(path, auth=admin, method="GET", data=None):
    headers = {"Authorization": auth}
    if isinstance(data, (dict, list)):
        data = json.dumps(data).encode(); headers["Content-Type"] = "application/json"
    elif isinstance(data, bytes):
        headers["Content-Type"] = "text/plain"
    request = urllib.request.Request(NEXUS + path, headers=headers, data=data, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response: return response.status, response.read()
    except urllib.error.HTTPError as response: return response.code, response.read()

body = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "nexus",
                              "client_secret": "local-integration-test-only"}).encode()
with urllib.request.urlopen(urllib.request.Request(KEYCLOAK + "/protocol/openid-connect/token", data=body), timeout=15) as response:
    token = json.load(response)["access_token"]
bearer = "Bearer " + token
name = "oidc-smoke-" + uuid.uuid4().hex[:10]
created = False
try:
    code, content = call("/service/rest/v1/repositories/raw/hosted", method="POST", data={"name": name, "online": True,
        "storage": {"blobStoreName": "default", "strictContentTypeValidation": True, "writePolicy": "ALLOW"}})
    assert code in [200, 201, 204], (code, content); created = True
    path = "/repository/" + name + "/probe.txt"
    assert call(path, method="PUT", data=b"oidc-rbac-test\n")[0] in [200, 201, 204]
    code, content = call(path, bearer)
    assert code == 200 and content == b"oidc-rbac-test\n", (code, content)
    assert call(path, bearer, "PUT", b"must-fail")[0] == 403
    assert call(path, "Bearer invalid-token")[0] == 401
    assert call("/service/rest/internal/ui/oauth2", bearer)[0] == 403
    print("OAuth2Realm configured through UI API: token read 200 / write 403 / bad token 401 / admin config 403 PASS")
    code, content = call("/service/rest/v1/security/users?source=OAuth2")
    assert code == 200 and any(user["userId"] == "service-account-nexus" for user in json.loads(content)), (code, content)
    print("OIDC user profiles integrated with Nexus user management PASS")
finally:
    if created: assert call("/service/rest/v1/repositories/" + name, method="DELETE")[0] in [200, 204]
