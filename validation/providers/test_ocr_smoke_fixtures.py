"""Check the corpus in the usual provider test pass without loading native OCR."""

import hashlib
import json
import re
import struct
import unittest
from pathlib import Path

from run_local_ocr_native_init_sinks import checked_smoke


ROOT = Path(__file__).with_name("android-ocr-probe-assets") / "ocr-probe" / "smoke"
LANGUAGES = ("eng", "fra", "jpn", "chi_sim")
STYLES = ("clean", "receipt", "tint", "tilt", "perspective")


class OcrSmokeFixturesTest(unittest.TestCase):
    def test_exact_corpus_and_transcriptions_match_the_checked_pngs(self):
        rows = json.loads((ROOT / "references.json").read_bytes())
        ids = [f"{lang}-{style}" for lang in LANGUAGES for style in STYLES]
        self.assertEqual(ids, [r["id"] for r in rows])
        self.assertEqual({f"{id}.png" for id in ids} | {"references.json"}, {p.name for p in ROOT.iterdir()})
        self.assertEqual(20, len({r["sha256"] for r in rows}))
        self.assertEqual(20, len({r["text"] for r in rows}))
        self.assertEqual(20, len({r["identifier"] for r in rows}))
        for row in rows:
            with self.subTest(image=row["id"]):
                self.assertEqual(row["id"], f'{row["language"]}-{row["style"]}')
                self.assertIn(row["anchor"], row["text"])
                self.assertRegex(row["identifier"], r"^[0-9]{4}$")
                self.assertIn(row["identifier"], row["text"])
                self.assertRegex(row["anchor"], r"[\u3040-\u30ff\u3400-\u9fff]" if row["language"] in ("jpn", "chi_sim") else r"[A-Za-z]")
                png = (ROOT / f'{row["id"]}.png').read_bytes()
                self.assertLessEqual(len(png), 128 * 1024)
                self.assertEqual(len(png), row["bytes"])
                self.assertEqual(hashlib.sha256(png).hexdigest(), row["sha256"])
                self.assertEqual(b"\x89PNG\r\n\x1a\n", png[:8])
                self.assertEqual(b"IHDR", png[12:16])
                self.assertEqual((1100, 480), struct.unpack(">II", png[16:24]))
        self.assertLess(sum(r["bytes"] for r in rows), 1024 * 1024)
        self.assertTrue(all(re.search(r"[\u3040-\u30ff]", r["text"]) for r in rows if r["language"] == "jpn"))

    def test_metrics_keep_a_missing_identifier_and_character_regression_visible(self):
        ids = [f"{lang}-{style}" for lang in LANGUAGES for style in STYLES]
        rows = [f"{id}:0/40:11" for id in ids]
        rows[0] = f"{ids[0]}:0/40:10"
        rows[-1] = f"{ids[-1]}:7/40:11"
        def report(values):
            return "INSTRUMENTATION_RESULT: ocrSmokeMetrics=" + json.dumps(values) + "\r\n"
        outcomes = checked_smoke(report(rows))
        self.assertEqual([ids[0], ids[-1]], [id for id, m in outcomes.items() if not m["passed"]])
        self.assertFalse(outcomes[ids[0]]["identifier"])
        self.assertEqual(7, outcomes[ids[-1]]["edits"])
        with self.assertRaises(RuntimeError):
            checked_smoke(report(rows[:-1]))
        with self.assertRaises(RuntimeError):
            checked_smoke(report(rows[::-1]))
