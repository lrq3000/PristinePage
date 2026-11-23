"""Exercise OCR generation replacement against the actual DAO query and Room DDL."""

from pathlib import Path
import re
import sqlite3
import unittest

import test_ocr_index as fixture
import test_ocr_work_migration as work


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/OcrPageWorkDao.kt"


class OcrEnqueueCasTest(unittest.TestCase):
    def setUp(self):
        sql = re.findall(
            r'@Query\("""((?:(?!""").)*)"""\)\s*abstract fun replaceCurrent\(',
            DAO.read_text(encoding="utf-8"), re.S,
        )
        self.assertEqual(1, len(sql), "Missing same-generation OCR pointer replacement")
        self.replace = sql[0]
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript(";\n".join(["PRAGMA foreign_keys=ON", *fixture.create_tables(2), fixture.SEED,
                                        *work.OcrWorkMigrationTest().migration(), work.job(work.CURRENT, 1, "SUCCEEDED"),
                                        work.job(work.NEXT, 2, "PENDING")]))
        self.db.execute("INSERT INTO OcrPageWork VALUES (?,?,1,?,429,?,1)",
                        (fixture.PAGE, work.CURRENT, work.DIGEST, work.CURRENT))

    def swap(self, **overrides):
        args = dict(page=fixture.PAGE, oldJob=work.CURRENT, oldGeneration=1,
                    oldDigest=work.DIGEST, oldExtent=429,
                    newJob=work.NEXT, newGeneration=2, newDigest="f" * 64, newExtent=431)
        args.update(overrides)
        return self.db.execute(self.replace, args).rowcount

    def read(self):
        return self.db.execute(
            "SELECT jobId,generation,inputSha256,inputBytes,resultJobId,projectionVersion FROM OcrPageWork"
        ).fetchone()

    def test_stale_pointer_does_not_replace_current_or_producing_identity(self):
        for args in ({"oldGeneration": 2}, {"oldJob": work.NEXT}, {"oldDigest": "d" * 64}, {"oldExtent": 428}):
            self.assertEqual(0, self.swap(**args))
        self.assertEqual((work.CURRENT, 1, work.DIGEST, 429, work.CURRENT, 1), self.read())

    def test_matching_pointer_replaces_only_current_generation_and_does_not_replay(self):
        self.assertEqual(1, self.swap())
        self.assertEqual((work.NEXT, 2, "f" * 64, 431, work.CURRENT, 1), self.read())
        self.assertEqual(0, self.swap())

    def test_pointer_trigger_cannot_hide_a_stale_count_behind_an_exact_job_pin(self):
        # A matched UPDATE changed a row; it did not prove the counter after the later pointer mutation.
        guards = re.findall(r'@Query\("""((?:(?!""").)*)"""\)\s*abstract fun sourcePinCountMatches\(',
                            DAO.read_text(encoding="utf-8"), re.S)
        self.assertEqual(1, len(guards), "Missing exact final source-pin counter guard")
        sql = guards[0]
        args = dict(source=fixture.SOURCE, expected=1)
        self.db.execute("INSERT INTO SourceReference VALUES ('JOB',?,?,0)", (work.NEXT, fixture.SOURCE))
        self.assertEqual(1, self.db.execute("UPDATE Source SET refCount=1 WHERE sourceId=?", (fixture.SOURCE,)).rowcount)
        self.assertEqual([(1,)], self.db.execute(sql, args).fetchall())
        self.db.execute("""CREATE TRIGGER undo_count AFTER UPDATE ON OcrPageWork
            BEGIN UPDATE Source SET refCount=0; END""")
        self.assertEqual(1, self.swap())
        self.assertEqual([(0,)], self.db.execute(sql, args).fetchall())
