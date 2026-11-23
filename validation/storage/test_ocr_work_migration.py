"""Exercise the additive OCR work-pointer migration with real FTS4 DDL and populated v3 data."""

import sqlite3
import unittest

import test_ocr_index as fixture


CURRENT = "aaaaaaaa-aaaa-4aaa-8aaa-000000000009"
NEXT = "aaaaaaaa-aaaa-4aaa-8aaa-000000000010"
DIGEST = "c" * 64


def job(job_id, generation, state):
    return f"""INSERT INTO Job (jobId,kind,idempotenceKey,canonicalInputSha256,input,state,
        attempt,leaseGeneration,createdAt,updatedAt) VALUES ('{job_id}','PAGE_OCR',
        'ocr-page-v2/{fixture.PAGE}/{generation}','{DIGEST}',X'00','{state}',0,0,0,0)"""


class OcrWorkMigrationTest(unittest.TestCase):
    def run_sql(self, statements):
        return fixture.OcrIndexSqlTest().run_sql(statements)

    def migration(self):
        source = fixture.DB / "OcrWorkMigration.kt"
        self.assertTrue(source.is_file(), "Missing OCR work-pointer migration")
        sql = fixture.literals(source, r'db\.execSQL\("""(.*?)"""\)')
        self.assertTrue(sql, "No migration SQL statements")
        return sql

    def current_store(self):
        return ["PRAGMA foreign_keys=ON", *fixture.create_tables(3), *fixture.sync_triggers(), fixture.SEED,
                fixture.INSERT_TEXT, "BEGIN", *self.migration(), "COMMIT"]

    def test_upgrade_preserves_catalog_and_v3_fts_without_inventing_work(self):
        query = fixture.OcrIndexSqlTest().query()
        self.assertEqual(["Existing album|010203", f"{fixture.DOC}|{fixture.PAGE}", "0", "0"], self.run_sql([
            *self.current_store(),
            "SELECT title,hex(canonicalRecipe) FROM Document JOIN Page USING(documentId) JOIN RecipeRevision USING(pageId)",
            query, "SELECT COUNT(*) FROM OcrPageWork", "SELECT COUNT(*) FROM Job",
            "PRAGMA foreign_key_check",
        ]))

    def test_fresh_room_schema_matches_upgraded_pointer_shape(self):
        migrated = self.migration()
        inspect = ["PRAGMA table_info(OcrPageWork)", "PRAGMA foreign_key_list(OcrPageWork)",
                   "PRAGMA index_list(OcrPageWork)", "PRAGMA index_info(index_OcrPageWork_jobId)",
                   "PRAGMA index_info(index_OcrPageWork_resultJobId)"]
        self.assertEqual(self.run_sql([*fixture.create_tables(4), *inspect]),
                         self.run_sql([*fixture.create_tables(3), *migrated, *inspect]))

    def test_rerun_can_advance_current_job_without_relabeling_the_last_result(self):
        self.assertEqual([f"{NEXT}|2|{CURRENT}|1"], self.run_sql([
            *self.current_store(), job(CURRENT, 1, "SUCCEEDED"), job(NEXT, 2, "PENDING"),
            f"""INSERT INTO OcrPageWork (pageId,jobId,generation,inputSha256,inputBytes,resultJobId,projectionVersion)
                VALUES ('{fixture.PAGE}','{CURRENT}',1,'{DIGEST}',1,'{CURRENT}',1)""",
            f"UPDATE OcrPageWork SET jobId='{NEXT}',generation=2 WHERE pageId='{fixture.PAGE}'",
            "SELECT jobId,generation,resultJobId,projectionVersion FROM OcrPageWork",
            "PRAGMA foreign_key_check",
        ]))

    def test_page_purge_cascades_pointer_but_both_job_references_are_restrictive(self):
        # This isolated FK witness does not need FTS4. Its tables come from Room's retained v2 DDL and the actual v4 migration statements.
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.executescript(";\n".join(["PRAGMA foreign_keys=ON", *fixture.create_tables(2), fixture.SEED,
                                   *self.migration(), job(CURRENT, 1, "SUCCEEDED"), job(NEXT, 2, "PENDING")]))
        missing = "aaaaaaaa-aaaa-4aaa-8aaa-000000000099"
        insert = "INSERT INTO OcrPageWork VALUES (?, ?, 2, ?, 1, ?, 1)"
        for page, active, result in [(missing, NEXT, CURRENT), (fixture.PAGE, missing, CURRENT),
                                     (fixture.PAGE, NEXT, missing)]:
            with self.subTest(page=page, active=active, result=result):
                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute(insert, (page, active, DIGEST, result))
        db.execute(insert, (fixture.PAGE, NEXT, DIGEST, CURRENT))
        for referenced in CURRENT, NEXT:
            with self.subTest(referenced=referenced):
                with self.assertRaises(sqlite3.IntegrityError):
                    db.execute("DELETE FROM Job WHERE jobId=?", (referenced,))
        db.execute("DELETE FROM RecipeRevision WHERE pageId=?", (fixture.PAGE,))
        db.execute("DELETE FROM Page WHERE pageId=?", (fixture.PAGE,))
        self.assertEqual(0, db.execute("SELECT COUNT(*) FROM OcrPageWork").fetchone()[0])
        self.assertEqual(2, db.execute("SELECT COUNT(*) FROM Job").fetchone()[0])
        self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
