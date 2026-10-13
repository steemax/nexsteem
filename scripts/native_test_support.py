"""Shared helpers for acceptance tests against a disposable local Nexus."""
import base64
import json
import os
import subprocess
import urllib.error
import urllib.request
import uuid
import time
from urllib.parse import urlsplit


class NexusTest:
    def __init__(self, url, container):
        self.url = url.rstrip("/")
        self.container = container
        self.password = os.environ.get("NEXUS_ADMIN_PASSWORD") or subprocess.check_output(
            ["docker", "exec", container, "cat", "/var/lib/nexus/admin.password"], text=True).strip()
        self.basic = base64.b64encode(("admin:" + self.password).encode()).decode()
        self.created = []
        self.suffix = uuid.uuid4().hex[:12]
        self.ssrf_original = None

    def request(self, path, method="GET", data=None, content_type="application/octet-stream"):
        headers = {"Authorization": "Basic " + self.basic}
        if isinstance(data, (dict, list)):
            data = json.dumps(data).encode()
            content_type = "application/json"
        if data is not None:
            headers["Content-Type"] = content_type
        req = urllib.request.Request(self.url + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace").replace(self.password, "[redacted]")
            raise AssertionError(f"{method} {path}: HTTP {error.code}: {detail[:4000]}") from None

    def repository(self, format_name, kind, members=None, remote=None):
        name = f"native-{format_name}-{kind}-{self.suffix}"
        settings = {"name": name, "online": True,
                    "storage": {"blobStoreName": "default", "strictContentTypeValidation": True}}
        if kind == "hosted":
            settings["storage"]["writePolicy"] = "ALLOW"
        elif kind == "group":
            settings["group"] = {"memberNames": members}
        else:
            # An exact Docker hostname is needed for our local proxy fixture. Keep protection enabled.
            host = urlsplit(remote).hostname
            if host == self.container and self.container.startswith("nexus-research-"):
                _, raw = self.request("/service/rest/v1/security/ssrf-protection")
                original = json.loads(raw)
                if host not in original.get("allowedDomains", []):
                    self.ssrf_original = original
                    updated = dict(original)
                    updated["allowedDomains"] = list(original.get("allowedDomains", [])) + [host]
                    self.request("/service/rest/v1/security/ssrf-protection", "PUT", updated)
            settings.update({
                "proxy": {"remoteUrl": remote, "contentMaxAge": 1440, "metadataMaxAge": 1440},
                "negativeCache": {"enabled": True, "timeToLive": 1440},
                "httpClient": {"blocked": False, "autoBlock": False, "authentication": {
                    "type": "username", "username": "admin", "password": self.password}},
            })
            if format_name == "npm":
                settings["npm"] = {"removeQuarantined": False}
        self.created.append(name)
        self.request(f"/service/rest/v1/repositories/{format_name}/{kind}", "POST", settings)
        return name

    def run(self, command, cwd=None, env=None, input=None):
        result = subprocess.run(command, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, input=input, timeout=120)
        output = result.stdout.replace(self.password, "[redacted]").replace(self.basic, "[redacted]")
        if result.returncode:
            raise AssertionError(f"{command[0]} failed ({result.returncode}):\n{output[-6000:]}")
        return output

    def wait_content(self, path, predicate, timeout=45):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                _, content = self.request(path)
                if predicate(content):
                    return content
            except AssertionError as error:
                if "HTTP 404:" not in str(error):
                    raise
            time.sleep(0.5)
        raise AssertionError(f"Timed out waiting for generated metadata: {path}")

    def cleanup(self):
        try:
            for name in reversed(self.created):
                try:
                    self.request(f"/service/rest/v1/repositories/{name}", "DELETE")
                except AssertionError as error:
                    if "HTTP 404:" not in str(error):
                        raise
        finally:
            if self.ssrf_original is not None:
                self.request("/service/rest/v1/security/ssrf-protection", "PUT", self.ssrf_original)
