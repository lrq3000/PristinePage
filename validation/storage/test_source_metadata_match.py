"""Exercise the live saved-source metadata selector against actual Room DDL."""

from pathlib import Path
import re
import sqlite3
import unittest

import test_ocr_index as fixture


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt"


class SavedSourceMetadataTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.executescript(";\n".join([*fixture.create_tables(2), fixture.SEED]))

    def match(self, *, mime=None, orientation=None, document=fixture.DOC):
        text = DAO.read_text(encoding="utf-8")
        pattern = r'@Query\("""((?:(?!""").)*)"""\)\s*internal abstract fun metadataMatchForSavedPage\('
        sql, = re.findall(pattern, text, re.S)
        return self.db.execute(sql, dict(page=fixture.PAGE, document=document, mime=mime, orientation=orientation)).fetchall()

    def test_candidate_discovery_pages_only_matching_saved_sources_in_key_order(self):
        later = "bbbbbbbb-bbbb-4bbb-8bbb-000000000001"
        self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',1,NULL,0)", (later, fixture.DOC, fixture.SOURCE))
        self.db.execute("INSERT INTO RecipeRevision VALUES (?,1,?,1,X'010203',?,NULL,0)", (later, fixture.SOURCE, "c" * 64))
        text = DAO.read_text(encoding="utf-8")
        sql, = re.findall(r'@Query\("""((?:(?!""").)*)"""\)\s*internal abstract fun scanSourceMetadataCandidates\(', text, re.S)
        hidden, = fixture.literals(fixture.DB / "AtelierDraftDao.kt", r'HIDDEN_ATELIER_DOCUMENT = """(.*?)"""')
        sql = sql.replace("$HIDDEN_ATELIER_DOCUMENT", hidden)

        def find(after=None, limit=1, mime="image/png"):
            return self.db.execute(sql, dict(after=after, limit=limit, mime=mime, orientation=None)).fetchall()

        self.assertEqual([(fixture.DOC, fixture.PAGE)], find())
        self.assertEqual([(fixture.DOC, later)], find(fixture.PAGE))
        self.assertEqual([], find(later))
        self.assertEqual([], find(mime="image/jpeg"))
        self.assertEqual([], find(limit=51))
        self.db.execute("UPDATE Page SET state='REMOVED',ordinal=NULL,removedAt=10 WHERE pageId=?", (fixture.PAGE,))
        self.assertEqual([(fixture.DOC, later)], find())

    def test_selects_accepted_orientation_or_actual_format_without_materializing_source_fields(self):
        mime, = self.db.execute("SELECT mime FROM SourceBlob").fetchone()
        self.db.execute("UPDATE Source SET exifOrientation=8,width=2,height=3")
        expected = [(fixture.SOURCE, 1, fixture.BLOB, "a" * 64, "b" * 64, "c" * 64, 3)]
        self.assertEqual(expected, self.match(orientation=8))
        self.assertEqual(expected, self.match(mime=mime))
        for args in ({}, {"orientation": 1}, {"mime": "image/gif"}, {"mime": mime, "orientation": 8}):
            self.assertEqual([], self.match(**args))

    def test_never_emits_an_invalid_stale_or_foreign_page_binding(self):
        expected = [(fixture.SOURCE, 1, fixture.BLOB, "a" * 64, "b" * 64, "c" * 64, 3)]
        self.assertEqual(expected, self.match(orientation=1))
        self.assertEqual([], self.match(orientation=1, document=fixture.DRAFT))
        for table, field, bad, valid in (
            ("Page", "removedAt", 1, None),
            ("Page", "ordinal", 2**32, 0),
            ("Page", "currentRecipeRevision", 2, 1),
            ("Document", "deletedAt", 1, None),
            ("Source", "exifOrientation", 2**32 + 1, 1),
            ("Source", "width", 2, 3),
            ("SourceBlob", "state", "DELETING", "READY"),
            ("SourceBlob", "byteSha256", "a" * 64 + "\0", "a" * 64),
            ("RecipeRevision", "recipeSha256", "z" * 64, "c" * 64),
            ("RecipeRevision", "canonicalRecipe", "abc", b"\x01\x02\x03"),
        ):
            with self.subTest(field=field):
                self.db.execute(f"UPDATE {table} SET {field}=?", (bad,))
                self.assertEqual([], self.match(orientation=1))
                self.db.execute(f"UPDATE {table} SET {field}=?", (valid,))
        self.assertEqual(expected, self.match(orientation=1))
