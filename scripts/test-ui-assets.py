#!/usr/bin/env python3
"""Verify every required extension bundle referenced by the actual Nexus bootstrap HTML."""
import argparse
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import urljoin, urlsplit
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--report", type=Path)
args = parser.parse_args()
test = NexusTest(args.url, args.container)
class Scripts(HTMLParser):
    def __init__(self):
        super().__init__(); self.sources = []
    def handle_starttag(self, tag, attrs):
        if tag == "script":
            source = dict(attrs).get("src")
            if source:
                self.sources.append(source)
_, html = test.request("/")
document = Scripts(); document.feed(html.decode())
required = {"nexus-repository-" + module + "-prod.js" for module in ["npm", "pypi", "openformats", "ruby-yum"]}
found = set(); checked = []
for source in document.sources:
    uri = urlsplit(urljoin(args.url, source))
    if uri.netloc != urlsplit(args.url).netloc:
        continue
    if uri.path.startswith("/static/"):
        path = uri.path + ("?" + uri.query if uri.query else "")
        code, content = test.request(path)
        assert code == 200 and content, path
        filename = uri.path.rsplit("/", 1)[1]
        if filename in required:
            found.add(filename)
        checked.append({"path": uri.path, "http": code, "bytes": len(content)})
assert required <= found, "Extension bundles absent from actual bootstrap HTML: " + str(required - found)
report = {"required_extension_bundles": sorted(required), "registered_and_served": sorted(found), "all_static_bootstrap_scripts": checked}
if args.report:
    args.report.write_text(json.dumps(report, indent=2) + "\n")
print("PASS actual Nexus bootstrap: all four extension production bundles and all referenced static scripts HTTP200")
