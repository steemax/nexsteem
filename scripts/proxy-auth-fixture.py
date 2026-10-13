#!/usr/bin/env python3
"""Private two-origin HTTP fixture; logs only credential-presence booleans."""
import http.server
import json
import os
from pathlib import Path
import threading
import urllib.parse
import urllib.error
import urllib.request

LOG = Path('/tmp/proxy-auth-events.jsonl')
LOCK = threading.Lock()
BODY = b'nexus-proxy-origin-scoping-proof\n'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


class ProxyHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        target = urllib.parse.urlsplit(self.path)
        proxy_auth = self.headers.get('Proxy-Authorization', '')
        matched = proxy_auth == os.environ['FIXTURE_PROXY_AUTH']
        with LOCK:
            with LOG.open('a') as output:
                output.write(json.dumps({'port': 8092, 'path': target.path, 'target_port': target.port,
                                         'proxy_credentials_match': matched}) + '\n')
        if not matched:
            self.send_response(407); self.send_header('Proxy-Authenticate', 'Basic realm="forward-transport"'); self.end_headers(); return
        # This fixture never contacts the Internet or any other local service.
        if target.hostname != os.environ['FIXTURE_HOST'] or target.port not in (8090, 8091):
            self.send_response(502); self.end_headers(); return
        headers = {name: value for name, value in self.headers.items() if name.lower() not in ('proxy-authorization', 'connection', 'host')}
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        try:
            response = opener.open(urllib.request.Request(self.path, headers=headers), timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            self.send_response(response.status)
            for name, value in response.headers.items():
                if name.lower() not in ('transfer-encoding', 'connection', 'content-length', 'server', 'date'):
                    self.send_header(name, value)
            self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        path = urllib.parse.urlsplit(self.path).path
        if path == '/health':
            self.send_response(200); self.end_headers(); return
        auth = self.headers.get('Authorization', '')
        action = path.rsplit('/', 1)[-1].split('-proof.', 1)[0] if '/gems/' in path else path.rsplit('/', 2)[-2]
        event = {'port': self.server.server_port, 'path': path,
                 'action': action,
                 'authorization_present': bool(auth), 'cookie_present': bool(self.headers.get('Cookie')),
                 'basic_matches': auth == os.environ['FIXTURE_BASIC'],
                 'bearer_matches': auth == 'Bearer ' + os.environ['FIXTURE_BEARER']}
        with LOCK:
            with LOG.open('a') as output:
                output.write(json.dumps(event) + '\n')
        if self.server.server_port == 8091:
            if action == 'trap':
                self.send_response(401); self.send_header('WWW-Authenticate', 'Basic realm="foreign"'); self.end_headers(); return
            self.send_response(200); self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(len(BODY))); self.end_headers(); self.wfile.write(BODY); return
        expected = ('Bearer ' + os.environ['FIXTURE_BEARER']) if '/bearer/' in path else os.environ['FIXTURE_BASIC']
        if auth != expected:
            self.send_response(401); self.send_header('WWW-Authenticate', 'Basic realm="origin"')
            # Cookies ignore port boundaries, so a foreign port must explicitly reject these.
            self.send_header('Set-Cookie', 'repository-session=origin-only; Path=/'); self.end_headers(); return
        if action == 'same-final':
            self.send_response(200); self.send_header('Content-Length', str(len(BODY)))
            self.end_headers(); self.wfile.write(BODY); return
        if action == 'loop':
            location = path
        elif action == 'same':
            location = path.replace('/same/', '/same-final/').replace('/gems/same-', '/gems/same-final-')
        elif action == 'blocked':
            location = 'http://127.0.0.1:18000/should-never-be-contacted'
        else:
            location = f'http://{os.environ["FIXTURE_HOST"]}:8091{path}'
        self.send_response(302); self.send_header('Location', location)
        self.send_header('Set-Cookie', 'repository-session=origin-only; Path=/'); self.end_headers()


for port in (8090, 8091):
    server = http.server.ThreadingHTTPServer(('0.0.0.0', port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
server = http.server.ThreadingHTTPServer(('0.0.0.0', 8092), ProxyHandler)
threading.Thread(target=server.serve_forever, daemon=True).start()
threading.Event().wait()
