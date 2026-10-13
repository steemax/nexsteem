#!/usr/bin/env python3
"""Publish and install packages through npm hosted, proxy and group with the npm CLI."""
import argparse
import json
from pathlib import Path
import tempfile
from urllib.parse import urlsplit
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--internal-url", default="http://nexus-research-formats:8081")
args = parser.parse_args()
test = NexusTest(args.url, args.container)
try:
    hosted = test.repository("npm", "hosted")
    proxy = test.repository("npm", "proxy", remote=args.internal_url + "/repository/" + hosted + "/")
    group = test.repository("npm", "group", members=[proxy, hosted])
    with tempfile.TemporaryDirectory(prefix="nexus-npm-") as directory:
        root = Path(directory)
        config = root / "npmrc"
        scope = urlsplit(args.url).netloc
        config.write_text("".join(f"//{scope}/repository/{repo}/:_auth={test.basic}\n" for repo in [hosted, proxy, group])
                          + "audit=false\nfund=false\n")
        config.chmod(0o600)
        common = ["--userconfig", str(config), "--cache", str(root / "cache")]
        for package_name in ["nexus-probe", "@nexus-open/probe"]:
            package = root / package_name.replace("/", "-").replace("@", "")
            package.mkdir()
            (package / "package.json").write_text(json.dumps({"name": package_name, "version": "1.0.0", "main": "index.js"}))
            (package / "index.js").write_text('module.exports = "native-nexus-test";\n')
            test.run(["npm", "publish", str(package), "--registry", args.url + f"/repository/{hosted}/", *common], cwd=root)
            for kind, repository in [("hosted", hosted), ("proxy", proxy), ("group", group)]:
                registry = args.url + f"/repository/{repository}/"
                metadata = json.loads(test.run(["npm", "view", package_name, "--json", "--registry", registry, *common], cwd=root))
                assert metadata["version"] == "1.0.0", metadata
                assert metadata["dist"]["tarball"].startswith(registry), metadata["dist"]["tarball"]
                install = root / (kind + package.name)
                install.mkdir()
                (install / "package.json").write_text('{"name":"acceptance-consumer","version":"1.0.0"}')
                test.run(["npm", "install", package_name + "@1.0.0", "--registry", registry, "--ignore-scripts",
                          "--userconfig", str(config), "--cache", str(install / "cache")], cwd=install)
                assert "native-nexus-test" in (install / "node_modules" / package_name / "index.js").read_text()
                print(f"npm {kind}: {package_name} publish / packument / install / integrity PASS", flush=True)
        registry = args.url + f"/repository/{hosted}/"
        test.run(["npm", "dist-tag", "add", "nexus-probe@1.0.0", "stable", "--registry", registry, *common], cwd=root)
        tags = json.loads(test.run(["npm", "view", "nexus-probe", "dist-tags", "--json", "--registry", registry, *common], cwd=root))
        assert tags["stable"] == "1.0.0", tags
        print("npm hosted: dist-tags PASS", flush=True)
        for kind, repository in [("hosted", hosted), ("group", group)]:
            result = json.loads(test.run(["npm", "search", "nexus-probe", "--json", "--registry",
                                          args.url + f"/repository/{repository}/", *common], cwd=root))
            assert any(item["name"] == "nexus-probe" for item in result), result
            print(f"npm {kind}: native search returns the published package PASS", flush=True)
        # Verify the native npm API key alongside the available OIDC factory, then restore active realms.
        _, realms_raw = test.request("/service/rest/v1/security/realms/active")
        original_realms = json.loads(realms_raw)
        token = None
        try:
            test.request("/service/rest/v1/security/realms/active", "PUT", list(dict.fromkeys(original_realms + ["NpmToken"])))
            _, login = test.request(f"/repository/{hosted}/-/user/org.couchdb.user:admin", "PUT",
                                   {"name": "admin", "password": test.password})
            token = json.loads(login)["token"]
            token_config = root / "token-npmrc"
            token_config.write_text(f"//{scope}/repository/{hosted}/:_authToken={token}\naudit=false\nfund=false\n")
            token_config.chmod(0o600)
            metadata = json.loads(test.run(["npm", "view", "nexus-probe", "--json", "--registry", registry,
                                            "--userconfig", str(token_config), "--cache", str(root / "token-cache")], cwd=root))
            assert metadata["version"] == "1.0.0"
            print("npm: native bearer token authentication with the OIDC factory installed PASS", flush=True)
        finally:
            if token is not None:
                test.request(f"/repository/{hosted}/-/user/token/{token}", "DELETE")
            test.request("/service/rest/v1/security/realms/active", "PUT", original_realms)
finally:
    test.cleanup()
