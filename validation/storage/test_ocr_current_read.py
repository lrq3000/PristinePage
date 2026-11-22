"""Exercise the actual OCR current-pointer and saved-input guards on retained Room DDL."""

from pathlib import Path
import re
import sqlite3
import unittest
from uuid import UUID

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
        hidden = r'HIDDEN_ATELIER_DOCUMENT = """(.*?)"""'
        if "$HIDDEN_ATELIER_DOCUMENT" in sql:
            clause, = fixture.literals(fixture.DB / "AtelierDraftDao.kt", hidden)
            sql = sql.replace("$HIDDEN_ATELIER_DOCUMENT", clause)
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

    def test_backfill_discovery_is_keyset_bounded_and_excludes_hidden_or_removed_pages(self):
        later = "bbbbbbbb-bbbb-4bbb-8bbb-000000000001"
        for ordinal, page in enumerate(("0" * 36, later), 1):
            self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',?,NULL,0)", (page, fixture.DOC, fixture.SOURCE, ordinal))
            self.db.execute("INSERT INTO RecipeRevision VALUES (?,1,?,1,X'010203',?,NULL,0)", (page, fixture.SOURCE, "c" * 64))

        def discover(after, limit):
            return self.scalar("scanSavedPages", after=after, limit=limit)

        self.assertEqual([(fixture.DOC, fixture.PAGE)], discover(None, 1))
        self.assertEqual([(fixture.DOC, later)], discover(fixture.PAGE, 1))
        self.assertEqual([], discover(later, 1))
        for oversize in (0, -1, 51):
            self.assertEqual([], discover(None, oversize))
        self.db.execute("UPDATE Page SET state='REMOVED',ordinal=NULL,removedAt=10 WHERE pageId=?", (fixture.PAGE,))
        self.assertEqual([(fixture.DOC, later)], discover(None, 5))
        self.db.execute("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
            VALUES (?,?,'EDITING',1,1,0)""", (fixture.DRAFT, fixture.DOC))
        self.assertEqual([], discover(None, 5))

    def test_backfill_continuation_seeks_near_end_without_growing_with_earlier_albums(self):
        def last_page_work(total):
            for number in range(previous[0] + 1, total + 1):
                page, document = str(UUID(int=number)), str(UUID(int=10_000 + number))
                self.db.execute("INSERT INTO Document VALUES (?,'Synthetic',1,'ACTIVE',0,0,NULL)", (document,))
                self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',0,NULL,0)", (page, document, fixture.SOURCE))
                self.db.execute("INSERT INTO RecipeRevision VALUES (?,1,?,1,X'010203',?,NULL,0)", (page, fixture.SOURCE, "c" * 64))
            previous[0] = total
            steps = 0

            def count():
                nonlocal steps
                steps += 1
                return 0

            self.db.set_progress_handler(count, 1)
            try:
                hit = self.scalar("scanSavedPages", after=str(UUID(int=total - 1)), limit=1)
            finally:
                self.db.set_progress_handler(None, 0)
            self.assertEqual([(str(UUID(int=10_000 + total)), str(UUID(int=total)))], hit)
            return steps

        previous = [0]
        near_end_of_small_corpus = last_page_work(10)
        near_end_of_large_corpus = last_page_work(1000)
        self.assertLessEqual(near_end_of_large_corpus, near_end_of_small_corpus * 3 + 100,
                             "Continuation did work proportional to albums preceding its cursor")
