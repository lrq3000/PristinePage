"""Exercise the actual additive DDL and exact OCR text consent CAS with SQLite."""

import json
import re
import sqlite3
import unittest

from test_room_schema import SCHEMA


DB = SCHEMA.parents[2] / "src/main/kotlin/app/pristinepage/data/db"
SOURCE = DB / "OcrTextPublicationDao.kt"
MIGRATION = DB / "OcrTextPublicationMigration.kt"
URI = "content://fixture/document/fresh"
DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"


def statements():
    return re.findall(r'db\.execSQL\("""(.*?)"""\)', MIGRATION.read_text(encoding="utf-8"), re.S)


class OcrTextPublicationSqlTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue(MIGRATION.is_file(), "The distinct OCR text publication migration is absent")
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.execute("PRAGMA foreign_keys=ON")
        entities = json.loads(SCHEMA.with_name("5.json").read_bytes())["database"]["entities"]
        journal = next(row for row in entities if row["tableName"] == "FileTransaction")
        self.db.execute(journal["createSql"].replace("${TABLE_NAME}", journal["tableName"]))
        self.db.execute("""INSERT INTO FileTransaction VALUES
            ('tx','OCR_TEXT','artifact','COMMITTED','stage','final','seal',?,0,X'00',1,NULL,7)""", (DIGEST,))
        for statement in statements():
            self.db.execute(statement)
        self.identity = dict(id="consent", tx="tx", owner="owner", digest=DIGEST, size=0)

    def query(self, name, **bind):
        source = SOURCE.read_text(encoding="utf-8")
        match = re.search(r'@Query\("""((?:(?!""").)*?)"""\)\s+(?:internal\s+)?abstract fun ' + name + r'\(', source, re.S)
        self.assertIsNotNone(match, name)
        return self.db.execute(match.group(1), bind)

    def insert(self):
        self.db.execute("""INSERT INTO OcrTextPublication VALUES
            (:id,:tx,:owner,:digest,:size,'WAITING_DESTINATION',NULL,8)""", self.identity)

    def advance(self, previous, next_state, before=None, after=None, **changed):
        return self.query("advance", **(self.identity | dict(previous=previous, next=next_state,
                                                            previousUri=before, uri=after, now=9) | changed)).rowcount

    def test_additive_migration_preserves_existing_private_journal_and_invents_no_consent(self):
        self.assertEqual([("tx", "COMMITTED", DIGEST, 0, b"\0")],
                         self.db.execute("SELECT transactionId,state,expectedByteSha256,expectedByteCount,payload FROM FileTransaction").fetchall())
        self.assertEqual((0,), self.db.execute("SELECT count(*) FROM OcrTextPublication").fetchone())
        self.insert()
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("DELETE FROM FileTransaction")

    def test_generated_room_schema_matches_its_migration(self):
        fresh = sqlite3.connect(":memory:")
        self.addCleanup(fresh.close)
        entities = json.loads(SCHEMA.with_name("7.json").read_bytes())["database"]["entities"]
        entity = next(row for row in entities if row["tableName"] == "OcrTextPublication")
        fresh.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for index in entity["indices"]:
            fresh.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        for query in (
            "PRAGMA table_info(OcrTextPublication)",
            "PRAGMA foreign_key_list(OcrTextPublication)",
            "PRAGMA index_info(index_OcrTextPublication_transactionId)",
            "PRAGMA index_info(index_OcrTextPublication_state_consentId)",
        ):
            self.assertEqual(fresh.execute(query).fetchall(), self.db.execute(query).fetchall())

    def test_exact_zero_byte_consent_cannot_be_replayed_or_redirected(self):
        self.insert()
        self.assertEqual(0, self.advance("WAITING_DESTINATION", "READBACK_VERIFIED", after=URI))
        for drift in (dict(owner="foreign"), dict(tx="other"), dict(id="stale"), dict(digest="f" * 64), dict(size=1)):
            self.assertEqual(0, self.advance("WAITING_DESTINATION", "WRITING", after=URI, **drift))
        self.assertEqual(0, self.advance("WAITING_DESTINATION", "WRITING", after="file:///not-a-document"))
        self.assertEqual(1, self.advance("WAITING_DESTINATION", "WRITING", after=URI))
        self.assertEqual(0, self.advance("WAITING_DESTINATION", "WRITING", after=URI))
        self.assertEqual(0, self.advance("WRITING", "READBACK_VERIFIED", URI, URI + "-different"))
        self.assertEqual(1, self.advance("WRITING", "READBACK_VERIFIED", URI, URI))
        self.assertEqual(0, self.advance("READBACK_VERIFIED", "WRITING", URI, URI))
        self.assertEqual(("READBACK_VERIFIED", 0, URI),
                         self.db.execute("SELECT state,byteCount,targetUri FROM OcrTextPublication").fetchone())

    def test_recovery_is_exact_and_never_claims_a_write_completed(self):
        self.insert()
        def recover(owner, phase):
            return self.query("endAbandoned", id="consent", owner=owner, previous=phase, now=9).rowcount
        self.assertEqual(0, recover("other", "WAITING_DESTINATION"))
        self.assertEqual(1, self.advance("WAITING_DESTINATION", "WRITING", after=URI))
        self.assertEqual(0, recover("owner", "WAITING_DESTINATION"))
        self.assertEqual(1, recover("owner", "WRITING"))
        self.assertEqual(0, recover("owner", "NEEDS_VERIFICATION"))
        self.assertEqual(("NEEDS_VERIFICATION", URI),
                         self.db.execute("SELECT state,targetUri FROM OcrTextPublication").fetchone())

    def test_abandoned_chooser_can_have_created_an_unknown_destination(self):
        self.insert()
        self.assertEqual(1, self.query("endAbandoned", id="consent", owner="owner", previous="WAITING_DESTINATION", now=9).rowcount)
        self.assertEqual(("NEEDS_VERIFICATION", None),
                         self.db.execute("SELECT state,targetUri FROM OcrTextPublication").fetchone())

    def test_refusal_without_a_write_is_not_partial_or_verified(self):
        self.insert()
        self.assertEqual((0,), self.query("canStart", tx="tx").fetchone())
        self.assertEqual(1, self.advance("WAITING_DESTINATION", "WRITING", after=URI))
        self.assertEqual(1, self.advance("WRITING", "DESTINATION_REFUSED", URI, URI))
        self.assertEqual((1,), self.query("canStart", tx="tx").fetchone())
        self.assertEqual(("DESTINATION_REFUSED", URI),
                         self.db.execute("SELECT state,targetUri FROM OcrTextPublication").fetchone())
