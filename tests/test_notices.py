# SPDX-License-Identifier: EPL-1.0
# Copyright (c) 2026 steemax.
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("notices", ROOT / "build-support/package-notices.py")
notices = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notices)


class DistributionNoticesTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "build").mkdir(exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix="notices-test-", dir=ROOT / "build")
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.source = self.base / "source"
        self.source.mkdir()
        for name in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md"):
            (self.source / name).write_text("Original " + name + "\n")
        (self.source / "licenses").mkdir()
        (self.source / "licenses/upstream-NOTICE.txt").write_text("Original upstream terms\n")
        self.output = self.base / "assembly/licenses/project"

    def test_packages_source_links_without_overwriting_upstream_notices(self):
        self.output.parent.parent.mkdir(parents=True)
        upstream_notice = self.output.parent.parent / "NOTICE.txt"
        upstream_notice.write_text("Runtime upstream notice\n")
        info = notices.package(self.source, self.output, snapshot_sha256="a" * 64,
                               archive_sha256="b" * 64)
        self.assertEqual("Runtime upstream notice\n", upstream_notice.read_text())
        for name in ("LICENSE.txt", "NOTICE.txt", "THIRD-PARTY-NOTICES.md", "licenses/upstream-NOTICE.txt"):
            self.assertEqual((self.source / name).read_bytes(), (self.output / name).read_bytes())
        stored = json.loads((self.output / "SOURCE-INFO.json").read_text())
        self.assertEqual(info, stored)
        self.assertEqual("a" * 64, stored["source_snapshot_sha256"])
        self.assertEqual("b" * 64, stored["source_archive_sha256"])
        self.assertIn("/releases/download/", stored["corresponding_source_archive_url"])
        self.assertIn("do not assert", stored["distribution_note"])

    def test_snapshot_changes_only_for_source_inputs(self):
        (self.source / "module.py").write_text("version = 1\n")
        before = notices.source_snapshot(self.source)
        (self.source / "target").mkdir()
        (self.source / "target/generated.class").write_bytes(b"compiled")
        (self.source / ".git").mkdir()
        (self.source / ".git/config").write_text("private local metadata")
        self.assertEqual(before, notices.source_snapshot(self.source))
        notices.package(self.source, self.output)
        notices.package(self.source, self.output)
        self.assertEqual(before, json.loads((self.output / "SOURCE-INFO.json").read_text())["source_snapshot_sha256"])
        (self.source / "module.py").write_text("version = 2\n")
        self.assertNotEqual(before, notices.source_snapshot(self.source))

    def test_invalid_metadata_or_missing_legal_input_fails_before_writing(self):
        for options in ({"snapshot_sha256": "bad"}, {"archive_url": "https://user:secret@example.org/source"},
                        {"source_url": "http://example.org/source"}):
            with self.assertRaises(ValueError):
                notices.package(self.source, self.output, **options)
            self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(ValueError, "license inputs"):
            notices.package(self.source, self.source / "licenses/project")
        (self.source / "NOTICE.txt").unlink()
        with self.assertRaisesRegex(ValueError, "legal input"):
            notices.package(self.source, self.output)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
