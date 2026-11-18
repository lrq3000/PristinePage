"""Execute CatalogDao scalar SQL over exported Room v1 DDL; Kotlin admission and native extraction are separate."""

import unittest
from uuid import UUID

import test_document_catalog_bounds as catalog_bounds


class ExportFreezePrepareBoundsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = catalog_bounds.DocumentCatalogBoundsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.db = self.fixture.db

    def extent(self, page, revision):
        row = self.fixture.query("recipeBlobByteCount", pageId=page, revision=revision).fetchone()
        return None if row is None else row[0]

    def test_selected_extent_preserves_missing_wrong_storage_and_exact_byte_length(self):
        page = self.fixture.page
        self.assertIsNone(self.extent(page, 2))
        self.assertIsNone(self.extent(str(UUID(int=999)), 1))
        for value, expected in [(b"a\0b", 3), (b"", 0), ("a\0b", -1), (123, -1)]:
            with self.subTest(value=value):
                self.db.execute("UPDATE RecipeRevision SET canonicalRecipe=?", (value,))
                self.assertEqual(expected, self.extent(page, 1))
        sql = self.fixture.sql("recipeBlobByteCount")
        plan = self.db.execute("EXPLAIN QUERY PLAN " + sql, {"pageId": page, "revision": 1}).fetchall()
        self.assertTrue(any("SEARCH RecipeRevision" in row[3] and "pageId=?" in row[3] and "recipeRevision=?" in row[3] for row in plan), plan)

    def test_selected_sql_extents_straddle_the_v1_charge_without_counting_history(self):
        count = 16
        ids = [self.fixture.page] + [str(UUID(int=100 + i)) for i in range(1, count)]
        for ordinal, page in enumerate(ids[1:], 1):
            self.fixture.insert_page(page, ordinal)
        # The larger retained history of page 1 must not be part of the selected keys.
        self.db.execute("INSERT INTO RecipeRevision VALUES (?,2,?,1,zeroblob(524288),?,1,0)", (ids[0], self.fixture.source, "d" * 64))
        self.db.execute("UPDATE RecipeRevision SET canonicalRecipe=zeroblob(524288) WHERE recipeRevision=1")
        # Independent v1 PNG/FAIL settings and literal ordinary profile/mask encodings; not the estimator being validated in the JVM suite.
        profile = b'{"backend":"reference-longer-backend","encodingVersion":"rgb8-v1","precision":"binary64","providers":[],"version":1}'
        overhead = 4096 + (49 + count * 64) + count * (4096 + len(b"[]") + len(profile))
        last = 8388608 - overhead - (count - 1) * 524288
        for delta in (-1, 0, 1):
            with self.subTest(delta=delta):
                self.db.execute("UPDATE RecipeRevision SET canonicalRecipe=zeroblob(?) WHERE pageId=? AND recipeRevision=1", (last + delta, ids[-1]))
                actual = []
                for page in ids:
                    args = {"pageId": page, "revision": 1}
                    self.assertEqual((1,), self.fixture.query("recipeReadIsBounded", **args).fetchone())
                    actual.append(self.extent(page, 1))
                self.assertEqual([524288] * 15 + [last + delta], actual)
                self.assertEqual(8388608 + delta, overhead + sum(actual))

    def test_existing_scalar_guards_distinguish_missing_selected_rows(self):
        unrelated_page = str(UUID(int=120))
        self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',1,NULL,0)", (unrelated_page, self.fixture.doc, self.fixture.source))
        self.assertIsNone(self.fixture.query("recipeReadIsBounded", pageId=unrelated_page, revision=1).fetchone())
        self.assertEqual((1,), self.fixture.query("recipeReadIsBounded", pageId=self.fixture.page, revision=1).fetchone())
        self.assertIsNone(self.fixture.query("sourceReadIsBounded", id=str(UUID(int=121))).fetchone())
        self.assertEqual((1,), self.fixture.query("sourceReadIsBounded", id=self.fixture.source).fetchone())

    def test_joined_guard_false_distinguishes_exact_missing_blob_from_invalid_chain(self):
        source, original = self.fixture.source, self.fixture.blob
        missing = "00000000-0000-0000-0000-0000000000a1"
        self.assertEqual((1,), self.fixture.query("sourceReadIsBounded", id=source).fetchone())
        # Both cases keep an unrelated valid Blob, so any-table existence would give the wrong missing answer.
        for link, expected in [(missing, 1), (missing.upper(), 0), ("z" + missing[1:], 0), (missing.encode("ascii"), 0), (missing + "\0tail", 0)]:
            with self.subTest(link=link):
                self.db.execute("UPDATE Source SET sourceBlobId=? WHERE sourceId=?", (link, source))
                self.assertEqual((0,), self.fixture.query("sourceReadIsBounded", id=source).fetchone())
                self.assertEqual((expected,), self.fixture.query("sourceBlobLinkIsMissing", id=source).fetchone())

        self.db.execute("UPDATE Source SET sourceBlobId=? WHERE sourceId=?", (original, source))
        self.db.execute("UPDATE SourceBlob SET encodedWidth=4294967299 WHERE sourceBlobId=?", (original,))
        self.assertEqual((0,), self.fixture.query("sourceReadIsBounded", id=source).fetchone())
        self.assertEqual((0,), self.fixture.query("sourceBlobLinkIsMissing", id=source).fetchone())

    def test_blob_link_diagnostic_is_scalar_and_uses_both_exact_key_indexes(self):
        # Proposed contract: only a canonical TEXT UUID link to an absent exact backing key yields true; present valid chains are false, absent Sources return no row. The producer needs this diagnostic only after the joined guard returned false.
        source = self.fixture.source
        self.assertEqual((0,), self.fixture.query("sourceBlobLinkIsMissing", id=source).fetchone())
        self.assertIsNone(self.fixture.query("sourceBlobLinkIsMissing", id=str(UUID(int=121))).fetchone())
        sql = self.fixture.sql("sourceBlobLinkIsMissing")
        plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + sql, {"id": source})]
        for key in ("sourceId=?", "sourceBlobId=?"):
            with self.subTest(key=key):
                self.assertTrue(any("SEARCH " in step and key in step for step in plan), plan)


if __name__ == "__main__":
    unittest.main(verbosity=2)
