#!/usr/bin/env python3
"""Real Docker Hub Bearer exchange and official library-image rebasing on a disposable Nexus."""
import argparse
import json
from pathlib import Path
import tempfile
import subprocess
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--registry", default="127.0.0.1:18087")
args = parser.parse_args()
test = NexusTest(args.url, args.container)
name = "docker-hub-" + test.suffix
tag = args.registry + "/busybox:1.37.0"
try:
    test.created.append(name)
    test.request("/service/rest/v1/repositories/docker/proxy", "POST", {
        "name": name, "online": True,
        "storage": {"blobStoreName": "default", "strictContentTypeValidation": True},
        "docker": {"httpPort": 8091, "forceBasicAuth": True, "pathEnabled": True},
        "proxy": {"remoteUrl": "https://registry-1.docker.io/", "contentMaxAge": 1440, "metadataMaxAge": 1440},
        "negativeCache": {"enabled": True, "timeToLive": 1440},
        "httpClient": {"blocked": False, "autoBlock": False}})
    with tempfile.TemporaryDirectory(prefix="nexus-docker-hub-") as directory:
        config = Path(directory) / "credentials"
        config.mkdir(mode=0o700)
        command = ["docker", "--config", str(config)]
        test.run([*command, "login", args.registry, "--username", "admin", "--password-stdin"], input=test.password + "\n")
        test.run([*command, "pull", "--platform", "linux/amd64", tag])
        result = json.loads(test.run([*command, "image", "inspect", tag]))[0]
        assert result["Architecture"] == "amd64"
        _, raw = test.request("/service/rest/v1/components?repository=" + name)
        assert any(item["name"] == "busybox" for item in json.loads(raw)["items"])
        print("PASS Docker Hub: genuine token service / busybox→library/busybox / amd64 pull / cached SQL component")
finally:
    if subprocess.run(["docker", "image", "inspect", tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
        test.run(["docker", "image", "rm", tag])
    test.cleanup()
