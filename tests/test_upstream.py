import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("upstream", Path(__file__).resolve().parents[1] / "scripts/upstream.py")
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)


class ReleaseSelectionTest(unittest.TestCase):
    def test_version_sorting_is_numeric(self):
        values = ["release-3.9.0-01", "release-3.96.4-01", "release-3.100.0-01", "release-3.96.4-12"]
        self.assertEqual("release-3.100.0-01", max(values, key=upstream.release_key))

    def test_selects_peeled_commit_not_annotated_tag_object(self):
        refs = "a refs/tags/release-3.96.4-01\nb refs/tags/release-3.96.4-01^{}\nc refs/tags/release-2.15.1-02^{}"
        self.assertEqual({"release-3.96.4-01": "b"}, upstream.releases(refs))

    def test_rejects_unexpected_refs(self):
        for ref in ["main", "release-3.96.4-01;rm -rf /", "release-3.96.4-SNAPSHOT", "--help"]:
            with self.assertRaises(ValueError):
                upstream.release_key(ref)

    def test_lock_matches_checkout(self):
        lock = upstream.verify()
        self.assertEqual("EPL-1.0", lock["source_license"])
        self.assertEqual(lock["tag"].removeprefix("release-"), lock["version"])


if __name__ == "__main__":
    unittest.main()
