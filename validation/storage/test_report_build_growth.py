"""Exercise the read-only growth report with real files and a baseline."""

import json
from pathlib import Path
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


REPORT = Path(__file__).with_name("report_build_growth.py")


class BuildGrowthReportTest(unittest.TestCase):
    def run_report(self, root, *args):
        return subprocess.run([sys.executable, "-B", str(REPORT), "--root", str(root), *args],
                              capture_output=True, text=True, timeout=10)

    def test_reports_growth_and_shrink_by_output_group_without_writing_into_the_checkout(self):
        with TemporaryDirectory() as place:
            root = Path(place)
            scratch = root / "data/build/test-scratch"
            scratch.mkdir(parents=True)
            (scratch / "spill.bin").write_bytes(b"123")
            before = self.run_report(root, "data/build")
            self.assertEqual(0, before.returncode, before.stderr)
            baseline = root / "before.json"
            baseline.write_text(before.stdout, encoding="utf-8")
            (scratch / "spill.bin").write_bytes(b"1")
            (scratch.parent / "metadata").write_bytes(b"abcd")
            after = self.run_report(root, "--baseline", str(baseline), "data/build")
            self.assertEqual(0, after.returncode, after.stderr)
            report = json.loads(after.stdout)
            self.assertEqual({"data/build/metadata": 4, "data/build/test-scratch": -2}, report["delta_bytes"])
            self.assertEqual({"data/build/metadata": 4, "data/build/test-scratch": 1}, report["logical_bytes"])
            self.assertGreaterEqual(report["volume_free_bytes"], 0)
            self.assertEqual({"metadata", "test-scratch"}, {path.name for path in scratch.parent.iterdir()})

    def test_rejects_overlapping_or_escaping_output_roots(self):
        with TemporaryDirectory() as place:
            root = Path(place)
            for args, message in ((("../build",), "outside"), (("data/build", "data/build/tmp"), "overlap")):
                with self.subTest(args=args):
                    result = self.run_report(root, *args)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stderr)

    def test_report_does_not_follow_a_symbolic_link_when_supported(self):
        with TemporaryDirectory() as place:
            root = Path(place)
            build = root / "data/build"
            build.mkdir(parents=True)
            (root / "retained.bin").write_bytes(b"must not be counted")
            link = build / "linked.bin"
            try:
                link.symlink_to(root / "retained.bin")
            except OSError as error:
                self.skipTest(f"Local fixture cannot create a symlink: {error}")
            result = self.run_report(root, "data/build")
            self.assertEqual(0, result.returncode, result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual({}, report["logical_bytes"])
            self.assertEqual(["data/build/linked.bin"], report["skipped_links"])
            self.assertTrue(link.is_symlink())
