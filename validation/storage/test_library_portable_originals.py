"""Exercise the raw portable-original admission query against the actual Room catalog."""

import re
import unittest

import test_library_catalog_reader as catalog
from test_ocr_index import BLOB, SEED


class LibraryPortableOriginalsTest(unittest.TestCase):
    def admitted_rows(self, update=None):
        source = catalog.ROOT / "data/src/main/kotlin/app/pristinepage/data/library/LibraryPortableOriginals.kt"
        self.assertTrue(source.exists(), "Missing bounded portable-original query")
        constants = dict(re.findall(r'const val (\w+) = """(.*?)"""', source.read_text(encoding="utf-8"), re.S))
        query = constants["READ_ORIGINALS"].replace("$ADMITTED", constants["ADMITTED"]).replace("$ROW_LIMIT", "4097")
        return catalog.LibraryCatalogReaderSqlTest().sqlite(SEED, *([update] if update else []), query)

    def test_projects_only_verified_shape_identity_and_extent_not_a_storage_path(self):
        self.assertEqual([{"valid": 1, "blobId": BLOB, "byteSha": "a" * 64, "byteCount": 4}], self.admitted_rows())

    def test_malformed_references_cannot_be_hidden_or_materialize_unbounded_strings(self):
        for change in (
            "relativePath='../../private'",
            "byteSha256='aaa' || char(0) || hex(zeroblob(4096))",
            "byteCount=1.5",
            "byteCount=104857601",
            "encodedWidth=12000,encodedHeight=12000",
            "mime='application/pdf'",
            "state='DELETING'",
        ):
            with self.subTest(change=change):
                self.assertEqual(
                    [{"valid": 0, "blobId": None, "byteSha": None, "byteCount": None}],
                    self.admitted_rows("UPDATE SourceBlob SET " + change),
                )
