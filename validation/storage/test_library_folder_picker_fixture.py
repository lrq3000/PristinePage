"""Check the Android Files witness fixture without opening or writing any real library."""

from contextlib import closing
from hashlib import sha256
from pathlib import Path
import sqlite3
from tempfile import mkdtemp
import unittest

from make_library_folder_picker_fixture import GENERATION, LIBRARY, make_fixture


class LibraryFolderPickerFixtureTest(unittest.TestCase):
    def test_exact_closed_catalog_and_independent_fixture_album(self):
        parent = Path(mkdtemp(prefix="pristinepage-library-files-test-"))
        root = parent / "fixture"
        hashes = make_fixture(root)
        catalog = root / f"catalogs/{GENERATION}.sqlite"
        marker = root / f"generations/{GENERATION}.complete"
        self.assertEqual({catalog.relative_to(root).as_posix(), marker.relative_to(root).as_posix()}, set(hashes))
        for relative, digest in hashes.items():
            self.assertEqual(digest, sha256((root / relative).read_bytes()).hexdigest())
        fields = marker.read_text(encoding="ascii").splitlines()
        self.assertEqual(["pristinepage-library-v1", LIBRARY, GENERATION, "-", "8",
                          str(catalog.stat().st_size), hashes[catalog.relative_to(root).as_posix()]], fields)
        with closing(sqlite3.connect(catalog.as_uri() + "?mode=ro", uri=True)) as db:
            # Host Python SQLite may omit FTS4. The independent CLI checks the full catalog at creation.
            self.assertEqual([("Synthetic folder test",)], db.execute("SELECT title FROM Document").fetchall())
            self.assertEqual((0,), db.execute("SELECT count(*) FROM Page").fetchone())
        self.assertFalse(Path(str(catalog) + "-wal").exists())

    def test_populated_catalog_names_and_authenticates_an_exact_png_and_its_history(self):
        parent = Path(mkdtemp(prefix="pristinepage-library-files-scan-test-"))
        root = parent / "fixture"
        hashes = make_fixture(root, with_scan=True)
        catalog = root / f"catalogs/{GENERATION}.sqlite"
        with closing(sqlite3.connect(catalog.as_uri() + "?mode=ro", uri=True)) as db:
            name, digest, count, current = db.execute("""
                SELECT n.filename, b.byteSha256, b.byteCount, p.currentRecipeRevision
                FROM LibraryScanName n JOIN Page p USING(pageId)
                JOIN Source s USING(sourceId) JOIN SourceBlob b USING(sourceBlobId)
                """).fetchone()
            self.assertEqual(("Synthetic.png", 2), (name, current))
            self.assertEqual([(1, None), (2, 1)], db.execute(
                "SELECT recipeRevision,parentRevision FROM RecipeRevision ORDER BY recipeRevision"
            ).fetchall())
        original = (root / "objects" / digest).read_bytes()
        self.assertEqual(b"\x89PNG\r\n\x1a\n", original[:8])
        self.assertEqual(count, len(original))
        self.assertEqual(digest, sha256(original).hexdigest())
        self.assertEqual(digest, hashes[f"objects/{digest}"])
        unowned = [relative for relative in hashes if relative.startswith("objects/") and relative != f"objects/{digest}"]
        self.assertEqual(1, len(unowned))
        self.assertEqual(hashes[unowned[0]], sha256((root / unowned[0]).read_bytes()).hexdigest())

    def test_existing_folder_is_left_untouched(self):
        root = Path(mkdtemp(prefix="pristinepage-library-files-refusal-"))
        other = root / "keep.txt"
        other.write_bytes(b"unrelated data")
        with self.assertRaises(FileExistsError):
            make_fixture(root)
        self.assertEqual([other], list(root.iterdir()))
        self.assertEqual(b"unrelated data", other.read_bytes())
