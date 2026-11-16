"""Exercise the actual catalog read SQL on Room-exported DDL; bounded synthetic values only. Native Room materialization/reopen remains a separate gate."""

from pathlib import Path
import re
import unittest
from uuid import UUID

import test_room_schema


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt"


class DocumentCatalogBoundsTest(unittest.TestCase):
    def setUp(self):
        fixture = test_room_schema.RoomSchemaTest()
        self.db = fixture.open_schema()
        self.addCleanup(fixture.doCleanups)
        # Keep exported constraints intact but defer cross-row checks in this uncommitted corruption fixture. The read guard must inspect malformed stored bindings before any attempted repair or typed row projection.
        self.db.execute("PRAGMA defer_foreign_keys=ON")
        self.doc, self.source, self.blob, self.page = [str(UUID(int=i)) for i in (1, 2, 3, 4)]
        self.db.execute("INSERT INTO Document VALUES (?,'Synthetic',1,'ACTIVE',0,0,NULL)", (self.doc,))
        self.db.execute("INSERT INTO SourceBlob VALUES (?,?,4,'image/png',3,2,?,'READY',1,?,0)", (self.blob, "a" * 64, f"sources/{self.blob}/original.bin", str(UUID(int=5))))
        self.db.execute("INSERT INTO Source VALUES (?,?,?,3,2,1,'srgb-v1',NULL,0,'READY',0)", (self.source, self.blob, "b" * 64))
        self.insert_page(self.page, 0)

    def insert_page(self, page, ordinal):
        self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',?,NULL,0)", (page, self.doc, self.source, ordinal))
        self.db.execute("INSERT INTO RecipeRevision VALUES (?,1,?,1,?,?,NULL,0)", (page, self.source, b"retained metadata", "c" * 64))

    def sql(self, method):
        source = DAO.read_text(encoding="utf-8")
        match = re.search(r'@Query\((?:"""((?:(?!""").)*)"""|"([^"\n]*)"|([A-Z_]+))\)\s*abstract override fun ' + method + r'\(', source, re.S)
        self.assertIsNotNone(match, "Bounded document catalog SQL is absent: " + method)
        if match.group(3) is not None:
            constant = re.search(r'const val ' + match.group(3) + r'\s*=\s*"""((?:(?!""").)*)"""', source, re.S)
            self.assertIsNotNone(constant, "Referenced catalog SQL constant is absent")
            return constant.group(1)
        return match.group(1) if match.group(1) is not None else match.group(2)

    def query(self, method, **bindings):
        return self.db.execute(self.sql(method), bindings)

    def document_gate(self, document=None):
        row = self.query("documentReadIsBounded", documentId=self.doc if document is None else document).fetchone()
        return None if row is None else row[0]

    def pages_gate(self):
        return self.query("activePagesReadIsBounded", documentId=self.doc).fetchone()[0]

    def test_missing_document_is_not_an_integrity_failure_and_unicode_title_fits(self):
        self.assertEqual(1, self.document_gate())
        self.assertIsNone(self.document_gate(str(UUID(int=99))))
        self.db.execute("UPDATE Document SET title=?", ("é" * 160,))
        self.assertEqual(1, self.document_gate())

    def test_document_raw_types_revision_and_title_bounds_precede_materialization(self):
        for field, bad, original in [("title", b"bad", "Synthetic"), ("title", "x" * 161, "Synthetic"), ("documentRevision", 0, 1), ("documentRevision", 1.5, 1), ("documentRevision", "9223372036854775808", 1), ("createdAt", b"bad", 0), ("updatedAt", 1.5, 0), ("lifecycle", "UNKNOWN", "ACTIVE"), ("deletedAt", b"bad", None)]:
            with self.subTest(field=field, value=repr(bad)[:40]):
                self.db.execute(f"UPDATE Document SET {field}=?", (bad,))
                self.assertEqual(0, self.document_gate())
                self.db.execute(f"UPDATE Document SET {field}=?", (original,))

    def test_nul_suffix_cannot_bypass_title_or_identity_byte_bounds(self):
        for field, original in [("title", "Synthetic"), ("documentId", self.doc)]:
            with self.subTest(field=field):
                value = original + "\0" + "x" * 4096
                self.db.execute(f"UPDATE Document SET {field}=?", (value,))
                self.assertEqual(0, self.document_gate(value if field == "documentId" else None))
                self.db.execute(f"UPDATE Document SET {field}=?", (original,))

    def test_keyset_page_is_bounded_ordered_and_excludes_inactive_documents(self):
        for i, state in [(10, "ACTIVE"), (11, "TOMBSTONED"), (12, "ACTIVE")]:
            self.db.execute("INSERT INTO Document VALUES (?,'Other',1,?,0,0,NULL)", (str(UUID(int=i)), state))
        ids = lambda after, limit: [r[0] for r in self.query("activeDocumentIdsAfter", after=after, limit=limit)]
        self.assertEqual([self.doc, str(UUID(int=10))], ids(None, 2))
        self.assertEqual([str(UUID(int=12))], ids(str(UUID(int=10)), 2))

    def test_oversized_or_blob_projected_id_returns_small_invalid_sentinel_not_omission(self):
        for malformed in ["x" * 4096, b"x" * 4096, "short", "x" * 36 + "\0" + "y" * 4096]:
            with self.subTest(kind=type(malformed).__name__):
                self.db.execute("UPDATE Document SET documentId=?", (malformed,))
                rows = list(self.query("activeDocumentIdsAfter", after=None, limit=2))
                self.assertEqual([("",)], rows)
                self.db.execute("UPDATE Document SET documentId=?", (self.doc,))

    def test_count_and_contiguity_guard_rejects_oversized_or_gapped_active_set(self):
        self.assertEqual(1, self.query("activePageCount", documentId=self.doc).fetchone()[0])
        self.assertEqual(1, self.pages_gate())
        self.db.execute("UPDATE Page SET ordinal=2")
        self.assertEqual(0, self.pages_gate())
        self.db.execute("UPDATE Page SET ordinal=0")
        for ordinal in range(1, 100):
            self.insert_page(str(UUID(int=1000 + ordinal)), ordinal)
        self.assertEqual(100, self.query("activePageCount", documentId=self.doc).fetchone()[0])
        self.assertEqual(1, self.pages_gate())
        self.insert_page(str(UUID(int=2000)), 100)
        self.assertEqual(101, self.query("activePageCount", documentId=self.doc).fetchone()[0])
        self.assertEqual(0, self.pages_gate())

    def test_page_raw_types_int_aliases_and_revision_bounds_refuse(self):
        for field, bad, original in [("ordinal", 4294967296, 0), ("ordinal", -1, 0), ("ordinal", None, 0), ("ordinal", 0.5, 0), ("currentRecipeRevision", 0, 1), ("currentRecipeRevision", 1.5, 1), ("sourceId", b"bad", self.source), ("pageId", self.page + "\0" + "x" * 4096, self.page), ("createdAt", b"bad", 0), ("removedAt", 1, None)]:
            with self.subTest(field=field):
                self.db.execute(f"UPDATE Page SET {field}=?", (bad,))
                self.assertEqual(0, self.pages_gate())
                self.db.execute(f"UPDATE Page SET {field}=?", (original,))

    def test_empty_and_removed_pages_do_not_become_active_rows(self):
        self.db.execute("UPDATE Page SET state='REMOVED',ordinal=NULL,removedAt=1")
        self.assertEqual(0, self.query("activePageCount", documentId=self.doc).fetchone()[0])
        self.assertEqual(1, self.pages_gate())

    def test_first_and_continuation_keysets_do_not_sort_the_whole_active_library(self):
        sql = self.sql("activeDocumentIdsAfter")
        for after in [None, self.doc]:
            with self.subTest(after=after):
                plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + sql, {"after": after, "limit": 1})]
                self.assertFalse(any("TEMP B-TREE" in row for row in plan), "Keyset pagination sorts the library: " + repr(plan))
        # Mixed states and both cursor modes: appending distant documents must not increase the work of returning the same one-row page. This measures VM steps, not device latency or a bound on skipped inactive gaps.
        def steps(after):
            count = 0
            def tick():
                nonlocal count
                count += 1
                return 0
            self.db.set_progress_handler(tick, 1)
            try:
                rows = list(self.db.execute(sql, {"after": after, "limit": 1}))
                self.assertEqual(1, len(rows))
                return count
            finally:
                self.db.set_progress_handler(None, 0)
        for i in range(10, 20):
            self.db.execute("INSERT INTO Document VALUES (?,'Other',1,?,0,0,NULL)", (str(UUID(int=i)), "ACTIVE" if i % 3 else "TOMBSTONED"))
        small = {after: steps(after) for after in [None, self.doc]}
        for i in range(20, 1020):
            self.db.execute("INSERT INTO Document VALUES (?,'Other',1,?,0,0,NULL)", (str(UUID(int=i)), "ACTIVE" if i % 3 else "TOMBSTONED"))
        for after in small:
            self.assertLessEqual(steps(after), small[after] + 200, "Distant rows inflated bounded keyset work")

    def test_bounded_leading_and_interior_nul_titles_match_the_existing_writer_domain(self):
        for title in ["\0Synthetic", "Syn\0thetic", "\0", "é" * 160, "😀" * 80]:
            with self.subTest(title=repr(title)):
                self.db.execute("UPDATE Document SET title=?", (title,))
                self.assertEqual(1, self.document_gate())

    def test_pending_import_discovery_is_document_scoped_and_excludes_terminal_or_item_rows(self):
        batch = str(UUID(int=100))
        for identity, kind, state in [(batch, "BATCH", "WAITING_SELECTION"), (str(UUID(int=101)), "ITEM", "RESERVED"), (str(UUID(int=102)), "BATCH", "CANCELED")]:
            self.db.execute("INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt) VALUES (?,?,?,?,?,0,1)", (identity, kind, self.doc, state, "d" * 64))
        ids = lambda doc: [row[0] for row in self.query("activeImportIds", documentId=doc)]
        self.assertEqual([batch], ids(self.doc))
        self.assertEqual([], ids(str(UUID(int=999))))

    def test_pending_identity_projection_caps_candidates_and_never_materializes_an_oversized_id(self):
        for i in range(100, 103):
            self.db.execute("INSERT INTO ImportSession(sessionId,kind,documentId,state,expectedMembershipToken,createdAt,expiresAt) VALUES (?,'BATCH',?,'WAITING_SELECTION',?,0,1)", (str(UUID(int=i)), self.doc, "d" * 64))
        self.assertEqual(2, len(list(self.query("activeImportIds", documentId=self.doc))))
        self.db.execute("UPDATE ImportSession SET sessionId=? WHERE sessionId=?", ("x" * 4096, str(UUID(int=100))))
        self.db.execute("UPDATE ImportSession SET state='CANCELED' WHERE sessionId<>?", ("x" * 4096,))
        self.assertEqual([("",)], list(self.query("activeImportIds", documentId=self.doc)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
