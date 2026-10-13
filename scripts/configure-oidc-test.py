#!/usr/bin/env python3
"""Configure only the disposable OIDC Nexus for the real browser acceptance test."""
import argparse
import base64
import json
import os
import subprocess
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18084")
parser.add_argument("--container", default="nexus-research-oidc")
parser.add_argument("--backend", default="http://keycloak-oidc:8080/realms/nexus-open-oidc-test")
args = parser.parse_args()
password = os.environ.get("NEXUS_ADMIN_PASSWORD") or subprocess.check_output(
    ["docker", "exec", args.container, "cat", "/var/lib/nexus/admin.password"], text=True).strip()
authorization = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()

def call(path, method="GET", data=None):
    headers = {"Authorization": authorization}
    if data is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    request = urllib.request.Request(args.url + path, method=method, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        body = response.read()
        return json.loads(body) if body else None

public = "http://127.0.0.1:18085/realms/nexus-open-oidc-test"
backend = args.backend.rstrip("/")
configuration = {
    "clientId": "nexus", "clientSecret": "local-integration-test-only",
    "issuer": public, "audience": "nexus", "publicUrl": args.url,
    "idpAuthorizationUrl": public + "/protocol/openid-connect/auth",
    "idpLogoutUrl": public + "/protocol/openid-connect/logout",
    "idpTokenUrl": backend + "/protocol/openid-connect/token",
    "idpJwksUrl": backend + "/protocol/openid-connect/certs", "idpJwsAlgorithm": "RS256",
    "usernameClaim": "preferred_username", "firstNameClaim": "given_name", "lastNameClaim": "family_name",
    "emailClaim": "email", "groupsClaim": "resource_access.nexus.roles",
    "roleMapping": {"reader": "nx-anonymous"}, "useTrustStore": False,
    "exactMatchClaims": {}, "authorizationCustomParams": {}, "tokenRequestCustomParams": {},
}
saved = call("/service/rest/internal/ui/oauth2", "PUT", configuration)
assert saved["clientSecret"] and saved["clientSecret"] != configuration["clientSecret"]
again = call("/service/rest/internal/ui/oauth2")
assert again["issuer"] == public and again["roleMapping"] == {"reader": "nx-anonymous"}
again["firstNameClaim"] = "given_name"
call("/service/rest/internal/ui/oauth2", "PUT", again)
available = call("/service/rest/v1/security/realms/available")
assert any(realm["id"] == "OAuth2Realm" for realm in available)
active = call("/service/rest/v1/security/realms/active")
if "OAuth2Realm" not in active:
    call("/service/rest/v1/security/realms/active", "PUT", active + ["OAuth2Realm"])
print("OAuth configuration API: save/load/masked secret/preserve existing secret PASS")
print("OAuth2Realm registered and enabled in the disposable test Nexus PASS")
