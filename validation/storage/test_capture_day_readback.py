"""Run ingest capture-day readback against real SQLite values and generated Room DDL."""

import sqlite3
import unittest

import test_ocr_index as fixture


class CaptureDayReadbackTest(unittest.TestCase):
    def test_only_the_bound_scalar_projection_matches(self):
        with sqlite3.connect(":memory:") as db:
            self.addCleanup(db.close)
            date_schema = [sql for sql in fixture.create_tables(5) if "`SourceCaptureDay`" in sql]
            db.executescript(";\n".join([*fixture.create_tables(2), *date_schema, fixture.SEED]))
            sql, = fixture.literals(fixture.DB / "IngestCompletionDao.kt",
                                    r'@Query\("""((?:(?!""").)*)"""\)\s*abstract override fun captureProjectionMatches\(')

            def matches(day):
                return db.execute(sql, dict(blobId=fixture.BLOB, byteSha256="a" * 64, epochDay=day)).fetchone()[0]

            self.assertFalse(matches(None))
            db.execute("INSERT INTO SourceCaptureDay VALUES (?, ?, NULL)", (fixture.BLOB, "a" * 64))
            self.assertTrue(matches(None))
            self.assertFalse(matches(19782))
            db.execute("UPDATE SourceCaptureDay SET epochDay=19782")
            self.assertTrue(matches(19782))
            self.assertFalse(matches(None))
            db.execute("UPDATE SourceCaptureDay SET epochDay=19782.25")
            self.assertFalse(matches(19782))
            db.execute("UPDATE SourceCaptureDay SET epochDay=19782,byteSha256=CAST(byteSha256 AS BLOB)")
            self.assertFalse(matches(19782))
            db.execute("UPDATE SourceCaptureDay SET byteSha256=?", ("a" * 64,))
            self.assertTrue(matches(19782))
            db.execute("UPDATE SourceBlob SET byteSha256=?", ("b" * 64,))
            self.assertFalse(matches(19782))
