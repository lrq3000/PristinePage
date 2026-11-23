"""Use original v1 exported Room tables and the app's exact additive migration SQL."""

from pathlib import Path
import re
import json
from uuid import UUID
import sqlite3
import unittest

import test_room_schema


ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data/src/main/kotlin/app/pristinepage/data/db"


def migrate(db):
    migration = DB / "AtelierDraftMigration.kt"
    if not migration.is_file():
        raise AssertionError("The Atelier staging migration is absent")
    statements = re.findall(r'db\.execSQL\("""(.*?)"""\)', migration.read_text(encoding="utf-8"), re.S)
    if not statements:
        raise AssertionError("No testable actual migration DDL")
    with db:
        for statement in statements:
            db.execute(statement)


class AtelierStageSchemaTest(unittest.TestCase):
    def setUp(self):
        self.fixture = test_room_schema.RoomSchemaTest()
        self.db = self.fixture.open_schema()
        self.addCleanup(self.fixture.doCleanups)

    def test_additive_migration_keeps_original_bindings_recipes_and_pending_batch(self):
        self.fixture.seed_source(self.db)
        with self.db:
            self.fixture.insert_page(self.db)
            self.db.execute("INSERT INTO SourceReference VALUES ('PAGE','page','source',0)")
            self.db.execute("""INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt)
                VALUES ('batch','BATCH','doc','WAITING_SELECTION',?,0,10)""", ("b" * 64,))
        tables = ("Document", "Source", "SourceBlob", "Page", "RecipeRevision", "SourceReference", "ImportSession")
        before = {table: self.db.execute(f"SELECT * FROM {table}").fetchall() for table in tables}
        migrate(self.db)
        self.assertEqual(before, {table: self.db.execute(f"SELECT * FROM {table}").fetchall() for table in tables})
        self.assertEqual([], self.db.execute("SELECT * FROM AtelierDraft").fetchall())
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_staging_documents_are_unique_and_must_exist(self):
        migrate(self.db)
        values = lambda i: (str(UUID(int=i)), str(UUID(int=1)), None, None, "ACQUIRING", 1, 0, None, None, 0)
        insert = """INSERT INTO AtelierDraft
            (draftId,stagingDocumentId,targetDocumentId,targetRevision,state,generation,step,selectedPageId,committedDocumentId,createdAt)
            VALUES (?,?,?,?,?,?,?,?,?,?)"""
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute(insert, values(2))
        with self.db:
            self.db.execute("INSERT INTO Document VALUES (?,'Scan',1,'ACTIVE',0,0,NULL)", (str(UUID(int=1)),))
            self.db.execute(insert, values(2))
        with self.assertRaises(sqlite3.IntegrityError), self.db:
            self.db.execute(insert, values(3))

    def test_late_ingest_is_refused_after_freeze_and_for_a_previous_batch_after_reopen(self):
        migrate(self.db)
        self.db.execute("INSERT INTO Document VALUES ('stage','Scan',1,'ACTIVE',0,0,NULL)")
        self.db.execute("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt,activeBatchId)
            VALUES ('draft','stage','ACQUIRING',1,0,0,'first')""")
        source = (DB / "AtelierDraftDao.kt").read_text(encoding="utf-8")
        hidden = re.search(r'HIDDEN_ATELIER_DOCUMENT = """(.*?)"""', source, re.S)[1]
        sql = next(q for q in re.findall(r'@Query\("""(.*?)"""\)', source, re.S) if "AND NOT EXISTS" in q)
        sql = sql.replace("$HIDDEN_ATELIER_DOCUMENT", hidden)
        refused = lambda batch: self.db.execute(sql, {"id": "stage", "batchId": batch}).fetchone()[0]
        self.assertEqual(0, refused("first"))
        self.db.execute("UPDATE AtelierDraft SET state='EDITING', generation=2, activeBatchId=NULL")
        self.assertEqual(1, refused("first"))
        self.db.execute("UPDATE AtelierDraft SET state='ACQUIRING', generation=3, activeBatchId='second'")
        self.assertEqual(1, refused("first"))
        self.assertEqual(0, refused("second"))

    def test_room_exported_v2_and_v1_migration_have_equivalent_staging_layout(self):
        migrate(self.db)
        exported = test_room_schema.SCHEMA.with_name("2.json")
        entity = next(e for e in json.loads(exported.read_text(encoding="utf-8"))["database"]["entities"] if e["tableName"] == "AtelierDraft")
        with sqlite3.connect(":memory:") as generated:
            generated.execute(entity["createSql"].replace("${TABLE_NAME}", "AtelierDraft"))
            for index in entity["indices"]:
                generated.execute(index["createSql"].replace("${TABLE_NAME}", "AtelierDraft"))
            for pragma in ("table_info", "foreign_key_list", "index_list"):
                self.assertEqual(
                    self.db.execute(f"PRAGMA {pragma}(AtelierDraft)").fetchall(),
                    generated.execute(f"PRAGMA {pragma}(AtelierDraft)").fetchall(),
                )

    def test_copy_attempt_count_keeps_retired_and_partial_owners_charged(self):
        migrate(self.db)
        self.db.executemany("INSERT INTO Document VALUES (?,'Scan',1,'ACTIVE',0,0,NULL)", [("stage",), ("unrelated",)])
        self.db.executemany("""INSERT INTO ImportSession
            (sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt) VALUES (?,?,?,'INGESTING',?,0,10)""",
            [(owner, kind, album, "a" * 64) for owner, kind, album in
             [("one", "ITEM", "stage"), ("two", "ITEM", "stage"), ("elsewhere", "ITEM", "unrelated"), ("batch", "BATCH", "stage")]])
        self.db.executemany("""INSERT INTO FileTransaction
            (transactionId,kind,ownerId,state,payload,attempt,updatedAt) VALUES (?,?,?,?,X'01',?,0)""",
            [("a", "INGEST", "one", "QUARANTINED", 1), ("b", "INGEST", "one", "RETRYABLE", 2),
             ("c", "INGEST", "two", "CANCELED", 1), ("d", "INGEST", "elsewhere", "COPYING", 1),
             ("e", "EXPORT", "one", "COPYING", 1), ("f", "INGEST", "batch", "COPYING", 1)])
        source = (DB / "AtelierDraftDao.kt").read_text(encoding="utf-8")
        query = re.search(r'@Query\("""([^"]+)"""\)\s*abstract fun retainedCopyAttempts', source)
        self.assertIsNotNone(query, "No registered SQLite attempt-count query")
        self.assertEqual((3,), self.db.execute(query[1], {"id": "stage"}).fetchone())
        self.assertEqual((1,), self.db.execute(query[1], {"id": "unrelated"}).fetchone())

    def test_only_the_frozen_generation_resolves_a_document_for_draft_editing(self):
        migrate(self.db)
        document = str(UUID(int=1))
        self.db.execute("INSERT INTO Document VALUES (?,'Scan',1,'ACTIVE',0,0,NULL)", (document,))
        self.db.execute("""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
            VALUES ('draft',?,'EDITING',4,1,0)""", (document,))
        source = (DB / "AtelierDraftDao.kt").read_text(encoding="utf-8")
        query = re.search(r'@Query\("""([^"]+)"""\)\s*abstract fun editingDocument', source)
        self.assertIsNotNone(query, "No registered draft-generation editing query")
        resolve = lambda owner, generation: self.db.execute(query[1], {"id": owner, "generation": generation}).fetchone()
        self.assertEqual((document,), resolve("draft", 4))
        self.assertIsNone(resolve("draft", 3))
        self.assertIsNone(resolve("other", 4))
        self.db.execute("UPDATE AtelierDraft SET state='ACQUIRING',generation=5")
        self.assertIsNone(resolve("draft", 5))
        self.db.execute("UPDATE AtelierDraft SET state='EDITING',generation=6")
        self.assertEqual((document,), resolve("draft", 6))
        self.assertIsNone(resolve("draft", 4))

    def test_publication_requires_exact_page_and_recipe_source_owners(self):
        migrate(self.db)
        self.fixture.seed_source(self.db)
        self.fixture.insert_page(self.db)
        source = (DB / "AtelierDraftDao.kt").read_text(encoding="utf-8")
        query = re.search(r'@Query\("""([^"]+)"""\)\s*abstract fun publicationHasPins', source)
        self.assertIsNotNone(query, "No registered exact publication ownership query")
        def has_pins():
            return self.db.execute(query[1], {"source": "source", "page": "page", "history": "recipe-v1/page/1"}).fetchone()[0]
        self.assertEqual(0, has_pins())
        self.db.execute("INSERT INTO SourceReference VALUES ('PAGE','page','source',0)")
        self.db.execute("INSERT INTO SourceReference VALUES ('RECIPE_HISTORY','recipe-v1/page/2','source',0)")
        self.assertEqual(0, has_pins())
        self.db.execute("INSERT INTO SourceReference VALUES ('RECIPE_HISTORY','recipe-v1/page/1','source',0)")
        self.assertEqual(1, has_pins())

    def test_receipt_guard_rejects_a_non_integer_generation_before_room_can_narrow_it(self):
        migrate(self.db)
        document, draft = (str(UUID(int=i)) for i in (1, 2))
        self.db.execute("INSERT INTO Document VALUES (?,'Scan',1,'ACTIVE',0,0,NULL)", (document,))
        self.db.execute("""INSERT INTO AtelierDraft
            (draftId,stagingDocumentId,state,generation,step,createdAt,committedDocumentId,receiptPageIds)
            VALUES (?,?,'COMMITTED',5,2,0,?,'receipt')""", (draft, document, document))
        source = (DB / "AtelierDraftDao.kt").read_text(encoding="utf-8")
        query = re.search(r'@Query\("""([^"]+)"""\)\s*abstract fun publicationReceiptIsBounded', source)
        self.assertIsNotNone(query)
        bounded = lambda: self.db.execute(query[1], {"id": draft, "limit": 1400}).fetchone()[0]
        self.assertEqual(1, bounded())
        self.db.execute("UPDATE AtelierDraft SET generation=5.25")
        self.assertEqual(0, bounded())

    def test_retention_budget_charges_copy_attempts_across_drafts_not_draft_rows(self):
        migrate(self.db)
        source = (DB / "AtelierDraftDao.kt").read_text(encoding="utf-8")
        query = re.search(r'@Query\("""([^"]+)"""\)\s*abstract fun retainedStagingCopyAttempts', source)
        self.assertIsNotNone(query)
        count = lambda limit=16: self.db.execute(query[1], {"limit": limit}).fetchone()[0]
        self.db.execute("INSERT INTO Document VALUES ('stage','Scan',1,'ACTIVE',0,0,NULL)")
        self.db.execute("""INSERT INTO AtelierDraft(draftId,stagingDocumentId,state,generation,step,createdAt)
             VALUES ('draft','stage','ACQUIRING',1,0,0)""")
        self.assertEqual(0, count(), "An empty active draft owns no copy")
        self.db.execute("UPDATE AtelierDraft SET state='ABANDONED'")
        self.assertEqual(0, count())
        self.db.execute("""INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt)
            VALUES ('item','ITEM','stage','COMMITTED',?,0,10)""", ("a" * 64,))
        self.db.execute("""INSERT INTO FileTransaction(transactionId,kind,ownerId,state,payload,attempt,updatedAt)
            VALUES ('saved','INGEST','item','COMMITTED',X'01',1,0)""")
        self.assertEqual(1, count(), "Abandoned originals still belong to the draft")
        self.db.execute("""UPDATE AtelierDraft SET state='COMMITTED',generation=2,step=2,targetDocumentId='target',
            targetRevision=1,committedDocumentId='target'""")
        self.assertEqual(1, count(), "An unsupported COMMITTED row cannot release a charge")
        self.db.execute("UPDATE AtelierDraft SET receiptPageIds=?", ("atelier-publish-v1\n1\ntarget\n" + "a" * 200,))
        self.assertEqual(0, count(), "An appended original already belongs to the saved library")
        self.db.execute("""INSERT INTO FileTransaction(transactionId,kind,ownerId,state,payload,attempt,updatedAt)
            VALUES ('failed','INGEST','item','QUARANTINED',X'01',2,0)""")
        self.assertEqual(1, count(), "A successful album does not uncharge a retained partial")
        self.db.execute("INSERT INTO Document VALUES ('stage2','Scan',1,'ACTIVE',0,0,NULL)")
        self.db.execute("""INSERT INTO AtelierDraft(draftId,stagingDocumentId,state,generation,step,createdAt)
             VALUES ('draft2','stage2','ACQUIRING',1,0,0)""")
        self.db.execute("""INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt)
            VALUES ('active-item','ITEM','stage2','INGESTING',?,0,10)""", ("a" * 64,))
        self.db.executemany("""INSERT INTO FileTransaction(transactionId,kind,ownerId,state,payload,attempt,updatedAt)
            VALUES (?,'INGEST','active-item','INTENT',X'01',?,0)""",
            [(f"active-{i}", i) for i in range(1, 16)])
        self.assertEqual(16, count(), "An outstanding reservation counts before copying any bytes")
        self.assertEqual(15, count(15), "The admission query stops after finding the needed number of rows")


if __name__ == "__main__":
    unittest.main()
