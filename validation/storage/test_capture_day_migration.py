"""Verify the actual capture-day migration, schema export and blob ownership using local SQLite."""

import sqlite3
import unittest

import test_ocr_index as fixture


class CaptureDayMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript("PRAGMA foreign_keys=ON;\n" + ";\n".join([*fixture.create_tables(2), fixture.SEED]))

    def migrate(self):
        statements = fixture.literals(fixture.DB / "SourceCaptureDayMigration.kt", r'db\.execSQL\("""(.*?)"""\)')
        self.assertEqual(2, len(statements))
        self.db.executescript(";\n".join(statements))

    def test_migration_starts_empty_without_modifying_populated_source_and_matches_fresh_room_ddl(self):
        before = self.db.execute("SELECT * FROM SourceBlob").fetchall()
        self.migrate()
        self.assertEqual([], self.db.execute("SELECT * FROM SourceCaptureDay").fetchall())
        self.assertEqual(before, self.db.execute("SELECT * FROM SourceBlob").fetchall())
        fresh = sqlite3.connect(":memory:")
        self.addCleanup(fresh.close)
        for statement in fixture.create_tables(5):
            if "`SourceCaptureDay`" in statement:
                fresh.execute(statement)
        for query in ("PRAGMA table_info(SourceCaptureDay)", "PRAGMA foreign_key_list(SourceCaptureDay)",
                      "PRAGMA index_info(index_SourceCaptureDay_epochDay_sourceBlobId)"):
            self.assertEqual(fresh.execute(query).fetchall(), self.db.execute(query).fetchall())

    def test_projection_has_one_row_per_owned_blob_and_present_dates_have_an_indexed_lookup(self):
        self.migrate()
        self.db.execute("INSERT INTO SourceCaptureDay VALUES (?, ?, ?)", (fixture.BLOB, "a" * 64, None))
        self.assertEqual([(None,)], self.db.execute("SELECT epochDay FROM SourceCaptureDay WHERE sourceBlobId=?", (fixture.BLOB,)).fetchall())
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO SourceCaptureDay VALUES (?, ?, ?)", (fixture.BLOB, "a" * 64, 19782))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO SourceCaptureDay VALUES (?, ?, ?)", (fixture.DOC, "a" * 64, 19782))
        self.db.execute("UPDATE SourceCaptureDay SET epochDay=19782")
        query = "SELECT sourceBlobId FROM SourceCaptureDay WHERE epochDay=? ORDER BY sourceBlobId"
        self.assertEqual([(fixture.BLOB,)], self.db.execute(query, (19782,)).fetchall())
        plan = self.db.execute("EXPLAIN QUERY PLAN " + query, (19782,)).fetchall()
        self.assertTrue(any("SEARCH" in row[-1] and "index_SourceCaptureDay_epochDay_sourceBlobId" in row[-1] for row in plan))
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())
