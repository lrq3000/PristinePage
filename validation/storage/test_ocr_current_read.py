"""Exercise the actual OCR current-pointer and saved-input guards on retained Room DDL."""

from pathlib import Path
import re
import sqlite3
import unittest

import test_ocr_index as fixture
import test_ocr_work_migration as work


CURRENT, DIGEST = work.CURRENT, work.DIGEST


SRC = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/OcrPageWorkDao.kt"


class OcrCurrentReadTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SRC.is_file(), "Missing OCR current-work DAO")
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript(";\n".join([*fixture.create_tables(2), fixture.SEED,
                                        *work.OcrWorkMigrationTest().migration(), work.job(CURRENT, 1, "PENDING")]))
        self.db.execute("INSERT INTO OcrPageWork VALUES (?, ?, 1, ?, 429, NULL, NULL)", (fixture.PAGE, CURRENT, DIGEST))

    def scalar(self, method, **args):
        pattern = rf'@Query\("""((?:(?!""").)*)"""\)\s*abstract fun {method}\('
        sql, = re.findall(pattern, SRC.read_text(encoding="utf-8"), re.S)
        return self.db.execute(sql, args).fetchall()

    def guard(self, page=fixture.PAGE):
        return self.scalar("pointerIsBounded", page=page)

    def live(self, recipe=1, document=fixture.DOC, source=fixture.SOURCE):
        return self.scalar("liveInputMatches", page=fixture.PAGE, document=document, source=source,
                           revision=recipe, recipeSha="c" * 64, sourceSha="b" * 64)

    def test_pointer_admission_distinguishes_missing_from_malformed(self):
        self.assertEqual([(1,)], self.guard())
        self.assertEqual([], self.guard(CURRENT))
        for field, bad, original in [
            ("jobId", b"x" * 36, CURRENT),
            ("generation", 0, 1),
            ("inputSha256", DIGEST + "\0tail", DIGEST),
            ("inputSha256", DIGEST.upper(), DIGEST),
            ("inputBytes", 2**32 + 429, 429),
            ("inputBytes", 3533, 429),
            ("resultJobId", CURRENT, None),
            ("projectionVersion", 1, None),
        ]:
            with self.subTest(field=field):
                self.db.execute(f"UPDATE OcrPageWork SET {field}=?", (bad,))
                self.assertEqual([(0,)], self.guard())
                self.db.execute(f"UPDATE OcrPageWork SET {field}=?", (original,))

    def test_live_input_matches_only_the_current_visible_owned_revision(self):
        self.assertEqual([(1,)], self.live())
        for args in ({"recipe": 2}, {"document": CURRENT}, {"source": CURRENT}):
            self.assertEqual([(0,)], self.live(**args))
        for table, field, changed, original in [
            ("Page", "ordinal", 2**32, 0),
            ("Page", "state", "REMOVED", "ACTIVE"),
            ("RecipeRevision", "recipeSha256", "e" * 64, "c" * 64),
            ("Source", "pixelSha256", "e" * 64, "b" * 64),
            ("SourceBlob", "state", "DELETING", "READY"),
            ("Document", "lifecycle", "TOMBSTONED", "ACTIVE"),
        ]:
            with self.subTest(table=table, field=field):
                self.db.execute(f"UPDATE {table} SET {field}=?", (changed,))
                self.assertEqual([(0,)], self.live())
                self.db.execute(f"UPDATE {table} SET {field}=?", (original,))
        self.assertEqual([(1,)], self.live())
