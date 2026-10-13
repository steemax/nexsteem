"""Private Registry/CDN fixture. Records only credential presence, never their values."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
from urllib.parse import parse_qs, urlsplit

HOST = "nexus-research-docker-upstream"
configuration = json.dumps({"architecture": "amd64", "os": "linux", "config": {}, "rootfs": {"type": "layers", "diff_ids": []}}, separators=(",", ":")).encode()
digest = "sha256:" + hashlib.sha256(configuration).hexdigest()
media = "application/vnd.oci.image.manifest.v1+json"
manifest = json.dumps({"schemaVersion": 2, "mediaType": media,
                      "config": {"mediaType": "application/vnd.oci.image.config.v1+json", "size": len(configuration), "digest": digest}, "layers": []}, separators=(",", ":")).encode()
events = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def send(self, status, body=b"", content_type="application/json", headers=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        if self.server.server_port == 9000:
            self.send_header("Set-Cookie", "registry-session=fixture-private-session; Path=/")
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
    def do_HEAD(self):
        self.do_GET()
    def do_GET(self):
        uri = urlsplit(self.path)
        path, parameters = uri.path, parse_qs(uri.query)
        events.append({"port": self.server.server_port, "path": path, "credential_present": bool(self.headers.get("Authorization")), "cookie_present": bool(self.headers.get("Cookie")), "scope": parameters.get("scope", [])})
        if path == "/events":
            return self.send(200, json.dumps(events).encode())
        if self.server.server_port == 9001:
            return self.send(200, configuration, "application/octet-stream")
        if path == "/auth/token":
            scope = parameters.get("scope", [""])[0]
            if scope not in {"registry:catalog:*", "repository:private/demo:pull", "repository:corrupt:pull"}:
                return self.send(403)
            return self.send(200, b'{"token":"test-read-only","expires_in":300}')
        if self.headers.get("Authorization") != "Bearer test-read-only":
            realm = f"http://{HOST}:9001/steal-token" if path.startswith("/v2/evil/") else f"http://{HOST}:9000/auth/token"
            return self.send(401, headers={"WWW-Authenticate": f'Bearer realm="{realm}",service="private-fixture",scope="repository:attacker:push"'})
        if path.endswith("/manifests/native"):
            return self.send(200, manifest, media)
        if "/blobs/" in path:
            if path.startswith("/v2/corrupt/"):
                return self.send(200, b"corrupt payload", "application/octet-stream")
            return self.send(307, headers={"Location": f"http://{HOST}:9001/content"})
        if path.endswith("/tags/list"):
            tags = ["next"] if parameters.get("last") else ["native"] if parameters.get("n") else ["native", "next"]
            return self.send(200, json.dumps({"name": "private/demo", "tags": tags}).encode())
        if path == "/v2/_catalog":
            return self.send(200, b'{"repositories":["private/demo"]}')
        return self.send(404)

for port in [9000, 9001]:
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
threading.Event().wait()
