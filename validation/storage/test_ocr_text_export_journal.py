"""Run actual OCR file journal scalar queries and CAS against existing Room DDL."""

import json
import sqlite3
import unittest

import test_export_render_completion as completion
from test_room_schema import SCHEMA


class OcrTextExportJournalSqlTest(unittest.TestCase):
    def setUp(self):
        schema = json.loads(SCHEMA.with_name("5.json").read_text(encoding="utf-8"))["database"]
        self.assertEqual(5, schema["version"])
        journal = next(row for row in schema["entities"] if row["tableName"] == "FileTransaction")
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute(journal["createSql"].replace("${TABLE_NAME}", "FileTransaction"))
        for index in journal["indices"]:
            self.db.execute(index["createSql"].replace("${TABLE_NAME}", "FileTransaction"))
        harness = completion.ExportRenderCompletionSqlTest()
        harness.db = self.db
        self.query = harness.query
        tx, artifact, producer = (completion.uid(i) for i in (146, 147, 148))
        sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        payload = "\0".join(("pristinepage-ocr-text-export-v1", tx, artifact, producer, "a" * 64, sha, "0")).encode()
        self.exact = dict(id=tx, artifact=artifact, stage=f"staging/{tx}/derived.part",
                          final=f"ocr-text-exports/{artifact}/text.bin", payload=payload, hash=sha, size=0)
        self.initialize()

    def initialize(self, kind="OCR_TEXT"):
        self.db.execute("""INSERT OR REPLACE INTO FileTransaction VALUES
            (:id,:kind,:artifact,'INTENT',:stage,:final,NULL,:hash,:size,:payload,1,NULL,0)""", self.exact | dict(kind=kind))

    def matches(self, phase, seal=None, **drift):
        return self.query("ocrTextJournalMatches", self.exact | dict(phase=phase, seal=seal) | drift).fetchone()

    def advance(self, previous, next_phase, previous_seal=None, seal=None, **drift):
        return self.query("advanceOcrTextJournal", self.exact | dict(previous=previous, next=next_phase,
                          previousSeal=previous_seal, seal=seal, audit=1) | drift).rowcount

    def test_zero_byte_artifact_can_only_advance_through_the_ordered_phases(self):
        seal = "b" * 64
        self.assertEqual((1,), self.matches("INTENT"))
        self.assertEqual(0, self.advance("INTENT", "SEALED", seal=seal))
        self.assertEqual(0, self.advance("INTENT", "COPYING", seal=seal))
        self.assertEqual(1, self.advance("INTENT", "COPYING"))
        self.assertEqual(0, self.advance("INTENT", "COPYING"))
        self.assertEqual(0, self.advance("COPYING", "COMMITTED", seal=seal))
        self.assertEqual(0, self.advance("COPYING", "SEALED", seal="B" * 64))
        self.assertEqual(1, self.advance("COPYING", "SEALED", seal=seal))
        self.assertEqual(0, self.advance("SEALED", "MOVED", seal, "c" * 64))
        self.assertEqual(1, self.advance("SEALED", "MOVED", seal, seal))
        self.assertEqual((0,), self.matches("COMMITTED", seal))
        self.assertEqual(1, self.advance("MOVED", "COMMITTED", seal, seal))
        self.assertEqual((1,), self.matches("COMMITTED", seal))
        self.assertEqual(0, self.advance("COMMITTED", "COPYING", seal))

    def test_drift_in_any_exact_field_prevents_readback_and_advancement(self):
        producer_drift = self.exact["payload"].replace(b"a" * 64, b"f" * 64, 1)
        for drift in (dict(artifact=completion.uid(149)), dict(final="other"), dict(stage="other"),
                      dict(payload=b"changed"), dict(payload=producer_drift), dict(size=1), dict(hash="c" * 64)):
            with self.subTest(drift=drift):
                self.assertEqual((0,), self.matches("INTENT", **drift))
                self.assertEqual(0, self.advance("INTENT", "COPYING", **drift))
        self.assertEqual((1,), self.matches("INTENT"))
        for kind in ("EXPORT", "RENDER"):
            self.initialize(kind=kind)
            self.assertEqual((0,), self.matches("INTENT"))
            self.assertEqual(0, self.advance("INTENT", "COPYING"))

    def test_malformed_expected_extent_cannot_authorize_the_next_phase(self):
        self.db.execute("UPDATE FileTransaction SET expectedByteCount=? WHERE transactionId=?", (b"0", self.exact["id"]))
        self.assertEqual((0,), self.matches("INTENT", size=b"0"))
        self.assertEqual(0, self.advance("INTENT", "COPYING", size=b"0"))
        self.db.execute("UPDATE FileTransaction SET expectedByteCount=393217 WHERE transactionId=?", (self.exact["id"],))
        self.assertEqual((0,), self.matches("INTENT", size=393217))
        self.assertEqual(0, self.advance("INTENT", "COPYING", size=393217))

    def test_exact_ceiling_is_admitted(self):
        sha = "c" * 64
        prefix = self.exact["payload"].rsplit(b"\0", 2)[0]
        self.exact |= dict(size=393216, hash=sha, payload=prefix + b"\0" + sha.encode() + b"\0" + b"393216")
        self.initialize()
        self.assertEqual((1,), self.matches("INTENT"))
        self.assertEqual(1, self.advance("INTENT", "COPYING"))
