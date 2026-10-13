#!/usr/bin/env python3
"""Assert the exact required recipe matrix against the running Nexus administration API."""
import argparse
import json
from pathlib import Path
from native_test_support import NexusTest

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:18083")
parser.add_argument("--container", default="nexus-research-formats")
parser.add_argument("--report", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
required = json.loads((root / "config/required-recipes.json").read_text())["formats"]
expected = {format + "-" + kind for format, kinds in required.items() for kind in kinds}
test = NexusTest(args.url, args.container)
_, raw = test.request("/service/extdirect", "POST", {
    "action": "coreui_Repository", "method": "readRecipes", "data": [], "type": "rpc", "tid": 1})
response = json.loads(raw)
if isinstance(response, list):
    response = response[0]
if response.get("type") == "exception":
    raise AssertionError(response.get("message", "Recipe listing failed"))
result = response.get("result", {})
rows = result.get("data", []) if isinstance(result, dict) else result
actual = {row.get("id", row.get("name")) for row in rows}
missing = sorted(expected - actual)
report = {"required_count": len(expected), "registered_count": len(expected & actual),
          "missing": missing, "registered": sorted(expected & actual), "url": args.url}
if args.report:
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
assert not missing, "Missing required recipes: " + ", ".join(missing)
