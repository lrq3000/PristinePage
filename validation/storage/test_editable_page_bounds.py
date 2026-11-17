"""Actual DAO recipe guards on the exported Room schema; no emulated Kotlin materialization or large allocation."""

import unittest
from uuid import UUID

import test_document_catalog_bounds as catalog_bounds


class EditablePageBoundsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = catalog_bounds.DocumentCatalogBoundsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.db = self.fixture.db

    def gate(self, page=None, revision=1):
        row = self.fixture.query("recipeReadIsBounded", pageId=self.fixture.page if page is None else page, revision=revision).fetchone()
        return None if row is None else row[0]

    def test_bounded_recipe_and_missing_revision_are_distinct_before_projection(self):
        self.assertEqual(1, self.gate())
        self.assertIsNone(self.gate(revision=2))
        self.assertIsNone(self.gate(str(UUID(int=999))))

    def test_blob_extent_type_and_integer_schema_aliases_refuse(self):
        for field, bad, original in [("canonicalRecipe", "not-a-blob", b"retained metadata"), ("canonicalRecipe", b"", b"retained metadata"), ("canonicalRecipe", b"x" * 524289, b"retained metadata"), ("recipeSchemaVersion", 4294967297, 1), ("recipeSchemaVersion", 0, 1), ("recipeSchemaVersion", 1.5, 1), ("createdAt", b"bad", 0), ("parentRevision", 1, None)]:
            with self.subTest(field=field, kind=type(bad).__name__):
                self.db.execute(f"UPDATE RecipeRevision SET {field}=?", (bad,))
                self.assertEqual(0, self.gate())
                self.db.execute(f"UPDATE RecipeRevision SET {field}=?", (original,))
        self.db.execute("UPDATE RecipeRevision SET recipeSchemaVersion=2")
        self.assertEqual(1, self.gate(), "Bounded future versions reach explicit UNSUPPORTED handling rather than Int narrowing")

    def test_nul_suffix_hash_and_identity_bytes_cannot_bypass_guard(self):
        for field, original in [("recipeSha256", "c" * 64), ("sourceId", self.fixture.source), ("pageId", self.fixture.page)]:
            with self.subTest(field=field):
                bad = original + "\0" + "x" * 4096
                self.db.execute(f"UPDATE RecipeRevision SET {field}=?", (bad,))
                self.assertEqual(0, self.gate(bad if field == "pageId" else None))
                self.db.execute(f"UPDATE RecipeRevision SET {field}=?", (original,))

    def test_mask_presence_is_exact_revision_scoped_without_mask_projection(self):
        query = lambda revision: self.fixture.query("recipeHasMasks", pageId=self.fixture.page, revision=revision).fetchone()[0]
        self.assertEqual(0, query(1))
        mask = str(UUID(int=100))
        self.db.execute("INSERT INTO Mask VALUES (?,?,'PROTECTION',?,3,2,?,1,1,'READY')", (mask, self.fixture.source, "d" * 64, f"masks/{mask}/mask.bin"))
        self.db.execute("INSERT INTO RecipeMask VALUES (?,1,?,'PROTECTION')", (self.fixture.page, mask))
        self.assertEqual(1, query(1))
        self.assertEqual(0, query(2))

    def test_selected_page_scalar_guard_precedes_writes_and_typed_projection(self):
        gate = lambda: self.fixture.query("pageReadIsBounded", pageId=self.fixture.page).fetchone()[0]
        self.assertEqual(1, gate())
        for field, bad, original in [("sourceId", self.fixture.source + "\0" + "x" * 4096, self.fixture.source), ("ordinal", 4294967296, 0), ("currentRecipeRevision", 1.5, 1), ("createdAt", b"bad", 0)]:
            with self.subTest(field=field):
                self.db.execute(f"UPDATE Page SET {field}=?", (bad,))
                self.assertEqual(0, gate())
                self.db.execute(f"UPDATE Page SET {field}=?", (original,))


if __name__ == "__main__":
    unittest.main(verbosity=2)
