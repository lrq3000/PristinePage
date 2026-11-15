"""Exercise actual Room-exported version-1 DDL in the installed host SQLite, not a handwritten schema mirror. Android Room/open/migration/process-death checks remain separate mandatory gates."""

import json
from pathlib import Path
import sqlite3
import unittest


SCHEMA = Path(__file__).resolve().parents[2] / "data/schemas/app.pristinepage.data.db.PristineDatabase/1.json"
TABLES = {"Document", "SourceBlob", "Source", "Page", "RecipeRevision", "Mask", "RecipeMask", "MaskReference", "ImportSession", "UndoEntry", "ExportSnapshot", "ExportPage", "SourceReference", "AssetPin", "Job", "RenderedPage", "RenderReference", "ExportArtifact", "FileTransaction", "Publication", "ShareLease", "Confirmation"}


class RoomSchemaTest(unittest.TestCase):
    def open_schema(self):
        self.assertTrue(SCHEMA.is_file(), "Required exported Room schema is absent; no database implementation is accepted")
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
        self.assertEqual(1, schema["version"])
        db = sqlite3.connect(":memory:")
        self.addCleanup(db.close)
        db.execute("PRAGMA foreign_keys=ON")
        for entity in schema["entities"]:
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity["indices"]:
                db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        return db

    def seed_source(self, db):
        db.execute("INSERT INTO Document VALUES ('doc','Synthetic',1,'ACTIVE',0,0,NULL)")
        db.execute("INSERT INTO SourceBlob VALUES ('blob',?,4,'image/png',3,2,'sources/blob/original.bin','READY',1,'tx',0)", ("a" * 64,))
        db.execute("INSERT INTO Source VALUES ('source','blob',?,3,2,1,'srgb-v1',NULL,0,'READY',0)", ("b" * 64,))
        db.commit()

    def insert_page(self, db, page="page", ordinal=0, state="ACTIVE", revision=1):
        db.execute("INSERT INTO Page VALUES (?, 'doc','source',?,?,?,NULL,0)", (page, revision, state, ordinal))
        db.execute("INSERT INTO RecipeRevision VALUES (?,?,'source',1,?,?,NULL,0)", (page, revision, b"retained canonical bytes", "c" * 64))

    def test_all_ownership_tables_are_exported(self):
        db = self.open_schema()
        actual = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertEqual(TABLES, actual)

    def test_page_recipe_cycle_is_deferred_but_enforced_at_commit(self):
        db = self.open_schema()
        self.seed_source(db)
        db.execute("BEGIN")
        self.insert_page(db)
        db.commit()
        self.assertEqual([], list(db.execute("PRAGMA foreign_key_check")))
        db.execute("BEGIN")
        db.execute("INSERT INTO Page VALUES ('dangling','doc','source',9,'ACTIVE',1,NULL,0)")
        with self.assertRaises(sqlite3.IntegrityError):
            db.commit()
        db.rollback()
        self.assertEqual([("page",)], list(db.execute("SELECT pageId FROM Page")))

    def test_every_foreign_key_has_a_leading_index(self):
        db = self.open_schema()
        for table in TABLES:
            foreign = {}
            for row in db.execute(f'PRAGMA foreign_key_list("{table}")'):
                foreign.setdefault(row[0], []).append((row[1], row[3]))
            indexes = []
            for row in db.execute(f'PRAGMA index_list("{table}")'):
                indexes.append([item[2] for item in db.execute(f'PRAGMA index_info("{row[1]}")')])
            for rows in foreign.values():
                columns = [column for _, column in sorted(rows)]
                self.assertTrue(any(index[: len(columns)] == columns for index in indexes), (table, columns, indexes))

    def test_active_order_is_unique_and_removed_ordinals_are_nullable(self):
        db = self.open_schema()
        self.seed_source(db)
        db.execute("BEGIN")
        self.insert_page(db)
        self.insert_page(db, "removed-a", None, "REMOVED")
        self.insert_page(db, "removed-b", None, "REMOVED")
        db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            db.execute("INSERT INTO Page VALUES ('duplicate','doc','source',1,'ACTIVE',0,NULL,0)")
        db.rollback()
        self.assertEqual(3, db.execute("SELECT COUNT(*) FROM Page").fetchone()[0])

    def test_referenced_source_and_blob_cannot_be_deleted(self):
        db = self.open_schema()
        self.seed_source(db)
        db.execute("BEGIN")
        self.insert_page(db)
        db.commit()
        for table in ("Source", "SourceBlob"):
            with self.assertRaises(sqlite3.IntegrityError):
                db.execute(f'DELETE FROM "{table}"')
            db.rollback()
        self.assertEqual(1, db.execute("SELECT COUNT(*) FROM Source").fetchone()[0])

    def test_job_key_is_unique_before_any_execution_exists(self):
        db = self.open_schema()
        columns = {row[1] for row in db.execute('PRAGMA table_info("Job")')}
        self.assertTrue({"idempotenceKey", "canonicalInputSha256", "input", "leaseGeneration"} <= columns)
        unique = []
        for row in db.execute('PRAGMA index_list("Job")'):
            if row[2]:
                unique.append([item[2] for item in db.execute(f'PRAGMA index_info("{row[1]}")')])
        self.assertIn(["idempotenceKey"], unique)


if __name__ == "__main__":
    unittest.main(verbosity=2)
