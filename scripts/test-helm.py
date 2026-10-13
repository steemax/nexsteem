#!/usr/bin/env python3
"""Exercise hosted/proxy repositories with the real Helm CLI, preserving user data."""
import argparse
import hashlib
import os
from pathlib import Path
import tempfile

from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--internal-url", default="http://nexus-research-formats:8081")
args = parser.parse_args()
test = NexusTest(args.url, args.container)
try:
    hosted = test.repository("helm", "hosted")
    proxy = test.repository("helm", "proxy", remote=args.internal_url + "/repository/" + hosted + "/")
    with tempfile.TemporaryDirectory(prefix="nexus-helm-") as directory:
        root = Path(directory)
        env = dict(os.environ, HELM_CACHE_HOME=str(root / "cache"), HELM_CONFIG_HOME=str(root / "config"),
                   HELM_DATA_HOME=str(root / "data"))
        chart = root / "chart"
        (chart / "templates").mkdir(parents=True)
        (chart / "Chart.yaml").write_text("apiVersion: v2\nname: nexus-probe\nversion: 1.0.0\ndescription: Native client acceptance fixture\n")
        (chart / "values.yaml").write_text("message: repository-test\n")
        (chart / "templates" / "configmap.yaml").write_text(
            'apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: {{ .Release.Name }}\ndata:\n  message: {{ .Values.message | quote }}\n')
        test.run(["helm", "lint", str(chart)], env=env)
        test.run(["helm", "package", str(chart), "--destination", directory], env=env)
        package = root / "nexus-probe-1.0.0.tgz"
        content = package.read_bytes()
        test.request(f"/repository/{hosted}/{package.name}", "PUT", content, "application/gzip")
        index = test.wait_content(f"/repository/{hosted}/index.yaml", lambda value: b"nexus-probe" in value)
        assert hashlib.sha256(content).hexdigest().encode() in index, "index must contain the chart SHA-256 digest"
        for name, repository in [("hosted", hosted), ("proxy", proxy)]:
            test.run(["helm", "repo", "add", name, args.url + f"/repository/{repository}/",
                      "--username", "admin", "--password", test.password], env=env)
            test.run(["helm", "repo", "update", name], env=env)
            assert "nexus-probe" in test.run(["helm", "search", "repo", name], env=env)
            download = root / name
            download.mkdir()
            test.run(["helm", "pull", name + "/nexus-probe", "--version", "1.0.0", "--destination", str(download)], env=env)
            actual = (download / package.name).read_bytes()
            assert hashlib.sha256(actual).digest() == hashlib.sha256(content).digest()
            assert "repository-test" in test.run(["helm", "template", "acceptance", str(download / package.name)], env=env)
            print(f"helm {name}: create / index / helm repo update / search / pull / template / SHA-256 PASS", flush=True)
        # Metadata and chart already cached: make the upstream unavailable and pull again from proxy.
        _, settings = test.request(f"/service/rest/v1/repositories/helm/hosted/{hosted}")
        import json
        settings = json.loads(settings)
        settings["online"] = False
        test.request(f"/service/rest/v1/repositories/helm/hosted/{hosted}", "PUT", settings)
        test.run(["helm", "pull", "proxy/nexus-probe", "--version", "1.0.0", "--destination", str(root)], env=env)
        assert package.read_bytes() == content
        print("helm proxy: cached pull with upstream offline PASS", flush=True)
finally:
    test.cleanup()
