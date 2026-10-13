#!/usr/bin/env python3
"""Exercise an actual Nexus REST endpoint without mutating repositories."""
import argparse
import base64
import json
import http.client
import os
import subprocess
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", required=True)
parser.add_argument("--timeout", type=int, default=600)
parser.add_argument("--docker-container", help="Read the initial admin password from this local test container")
args = parser.parse_args()
url = args.url.rstrip("/")
deadline = time.monotonic() + args.timeout
while True:
    try:
        with urllib.request.urlopen(url + "/service/rest/v1/status", timeout=5) as response:
            if response.status == 200:
                break
    except (OSError, http.client.HTTPException):
        if time.monotonic() > deadline:
            raise SystemExit("Nexus не запустился в отведенное время")
        time.sleep(3)
print("Nexus status: HTTP 200")
headers = {}
if os.environ.get("NEXUS_ACCESS_TOKEN"):
    headers["Authorization"] = "Bearer " + os.environ["NEXUS_ACCESS_TOKEN"]
elif os.environ.get("NEXUS_ADMIN_PASSWORD"):
    credentials = "admin:" + os.environ["NEXUS_ADMIN_PASSWORD"]
    headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()
elif args.docker_container:
    password = subprocess.check_output(["docker", "exec", args.docker_container,
                                       "cat", "/var/lib/nexus/admin.password"], text=True).strip()
    credentials = "admin:" + password
    headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()
else:
    raise SystemExit("Для проверки форматов задайте NEXUS_ADMIN_PASSWORD или NEXUS_ACCESS_TOKEN")
request = urllib.request.Request(url + "/service/rest/v1/repositories", headers=headers)
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        settings = json.load(response)
except urllib.error.HTTPError as error:
    raise SystemExit(f"Проверка авторизованного API: HTTP {error.code}")
print(json.dumps(settings, ensure_ascii=False, indent=2))
