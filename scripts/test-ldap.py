#!/usr/bin/env python3
"""Actual Nexus CRUD/Direct/LDAP authentication and repository permissions."""
import argparse
import base64
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18082")
parser.add_argument("--container", default="nexus-research-npm")
parser.add_argument("--ldap-host", default="nexus-ldap-fixture")
args = parser.parse_args()
password = os.environ.get("NEXUS_ADMIN_PASSWORD") or subprocess.check_output(
    ["docker", "exec", args.container, "cat", "/var/lib/nexus/admin.password"], text=True).strip()

def basic(user, password):
    return "Basic " + base64.b64encode((user + ":" + password).encode()).decode()

admin = basic("admin", password)
reader = basic("reader", "reader-secret")

def call(path, method="GET", data=None, auth=admin):
    headers = {"Authorization": auth}
    if isinstance(data, (dict, list)):
        headers["Content-Type"] = "application/json"
        data = json.dumps(data).encode()
    elif isinstance(data, bytes):
        headers["Content-Type"] = "text/plain"
    request = urllib.request.Request(args.url + path, headers=headers, data=data, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as response:
        return response.code, response.read()

def direct(method, *data):
    code, body = call("/service/extdirect", "POST", {"action": "ldap_LdapServer", "method": method,
        "data": list(data), "type": "rpc", "tid": 1})
    assert code == 200, (code, body)
    result = json.loads(body)
    if isinstance(result, list):
        result = result[0]
    assert result["type"] == "rpc" and result["result"].get("success", True), result
    return result["result"].get("data")

suffix = uuid.uuid4().hex[:10]
name, repo, role = "ldap-smoke-" + suffix, "ldap-raw-" + suffix, "readers"
settings = {"name": name, "protocol": "ldap", "host": args.ldap_host, "port": 1389,
    "searchBase": "dc=example,dc=org", "authScheme": "simple", "authUsername": "cn=manager,dc=example,dc=org",
    "authPassword": "system-secret", "userBaseDn": "ou=people", "userSubtree": True,
    "userObjectClass": "inetOrgPerson", "userIdAttribute": "uid", "userRealNameAttribute": "cn",
    "userEmailAddressAttribute": "mail", "ldapGroupsAsRoles": True, "groupType": "static",
    "groupBaseDn": "ou=groups", "groupSubtree": True, "groupIdAttribute": "cn",
    "groupObjectClass": "groupOfNames", "groupMemberAttribute": "member", "groupMemberFormat": "${dn}",
    "connectionTimeout": 5, "connectionRetryDelay": 60, "maxIncidentsCount": 3}
created_server = created_repo = created_role = False
code, body = call("/service/rest/v1/security/realms/active")
assert code == 200
old_realms = json.loads(body)
try:
    assert any(r["id"] == "LdapRealm" for r in json.loads(call("/service/rest/v1/security/realms/available")[1]))
    direct("verifyConnection", settings)
    users = direct("verifyUserMapping", settings)
    assert users[0]["username"] == "reader" and "readers" in users[0]["membership"], users
    direct("verifyLogin", settings, base64.b64encode(b"reader").decode(), base64.b64encode(b"reader-secret").decode())
    server = direct("create", settings)
    created_server = True
    assert server["authPassword"] != "system-secret" and "passwordSecret" not in server
    assert any(s["id"] == server["id"] for s in direct("read"))
    server["name"] = name + "-renamed"
    updated = direct("update", server)
    assert updated["name"] == server["name"]
    direct("verifyConnection", updated)
    direct("changeOrder", [s["id"] for s in direct("read")])
    direct("clearCache")
    print("LDAP Direct API: list/templates/create/update/order/connection/mapping/login PASS")
    assert len(direct("readTemplates")) >= 4
    code, body = call("/service/rest/v1/security/ldap")
    assert code == 200 and any(s["id"] == server["id"] for s in json.loads(body))
    code, _ = call("/service/rest/v1/security/realms/active", "PUT", old_realms + ([] if "LdapRealm" in old_realms else ["LdapRealm"]))
    assert code in [200, 204]
    code, body = call("/service/rest/v1/security/roles", "POST", {"id": role, "name": role, "description": "Temporary LDAP test mapping",
        "privileges": ["nx-repository-view-raw-*-browse", "nx-repository-view-raw-*-read"], "roles": []})
    assert code in [200, 201, 204], (code, body)
    created_role = True
    code, body = call("/service/rest/v1/repositories/raw/hosted", "POST", {"name": repo, "online": True,
        "storage": {"blobStoreName": "default", "strictContentTypeValidation": True, "writePolicy": "ALLOW"}})
    assert code in [200, 201, 204], (code, body)
    created_repo = True
    path = "/repository/" + repo + "/probe.txt"
    assert call(path, "PUT", b"ldap-test\n")[0] in [200, 201, 204]
    code, body = call(path, auth=reader)
    assert code == 200 and body == b"ldap-test\n", (code, body)
    assert call(path, "PUT", b"must-fail", auth=reader)[0] == 403
    assert call(path, auth=basic("reader", "wrong"))[0] == 401
    print("LDAP bind -> mapped group -> read 200 / write 403 / invalid password 401 PASS")
    code, body = call("/service/rest/v1/security/users?source=LDAP&userId=rea")
    assert code == 200 and any(u["userId"] == "reader" for u in json.loads(body)), (code, body)
    print("LDAP users and groups integrated with Nexus security PASS")
finally:
    if created_repo: assert call("/service/rest/v1/repositories/" + repo, "DELETE")[0] in [200, 204]
    if created_role: assert call("/service/rest/v1/security/roles/" + role, "DELETE")[0] in [200, 204]
    if created_server: direct("remove", server["id"])
    assert call("/service/rest/v1/security/realms/active", "PUT", old_realms)[0] in [200, 204]
