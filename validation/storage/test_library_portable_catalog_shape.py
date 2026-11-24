"""Check portable-table ownership and retention policy against the exported Room schema."""

import re
import shutil
import subprocess
import unittest

import test_library_catalog_reader as catalog
from test_ocr_index import BLOB, DOC, INSERT_TEXT, PAGE, SEED, SOURCE


class LibraryPortableCatalogShapeTest(unittest.TestCase):
    PRODUCER = "aaaaaaaa-aaaa-4aaa-8aaa-000000000008"
    SUCCESSFUL_PRODUCER = (
        f"INSERT INTO Job VALUES ('{PRODUCER}','PAGE_OCR','terminal','{'d' * 64}',NULL,NULL,X'01',"
        "'SUCCEEDED',1,NULL,NULL,1,NULL,NULL,0,0)",
        f"INSERT INTO OcrPageWork VALUES ('{PAGE}','{PRODUCER}',1,'{'d' * 64}',1,'{PRODUCER}',1)",
        INSERT_TEXT,
    )
    CANONICAL_ROWS = (
        SEED,
        f"INSERT INTO LibraryScanName VALUES ('{PAGE}','{DOC}','Invoice.png','invoice.png')",
        f"INSERT INTO SourceReference VALUES ('PAGE','{PAGE}','{SOURCE}',0)",
        f"INSERT INTO SourceReference VALUES ('RECIPE_HISTORY','recipe-v1/{PAGE}/1','{SOURCE}',0)",
        "UPDATE Source SET refCount=2",
    )

    def refused(self, *changes):
        path = catalog.ROOT / "data/src/main/kotlin/app/pristinepage/data/library/LibraryPortableCatalogShape.kt"
        self.assertTrue(path.exists(), "Missing portable catalog ownership admission")
        query, = re.findall(r'const val REFUSED = """(.*?)"""', path.read_text(encoding="utf-8"), re.S)
        result = catalog.LibraryCatalogReaderSqlTest().sqlite(*self.CANONICAL_ROWS, *changes, query)
        return bool(result[0]["refused"])

    def test_live_or_retained_removed_scan_has_exact_owners_and_original(self):
        self.assertFalse(self.refused())
        self.assertFalse(self.refused("UPDATE Page SET state='REMOVED', ordinal=NULL, removedAt=7"))

    def test_rows_that_would_disappear_on_projection_are_refused(self):
        for change in (
            "UPDATE Document SET lifecycle='TOMBSTONED',deletedAt=1",
            "DELETE FROM LibraryScanName",
            "UPDATE Page SET ordinal=2",
            INSERT_TEXT,
            "INSERT INTO FileTransaction(transactionId,kind,ownerId,state,payload,attempt,updatedAt) VALUES ('local','RENDER','owner','INTENT',X'00',1,0)",
        ):
            with self.subTest(change=change):
                self.assertTrue(self.refused(change))

    def test_balanced_counters_do_not_authorize_wrong_source_owners(self):
        self.assertTrue(self.refused("UPDATE SourceReference SET ownerId='wrong' WHERE ownerType='RECIPE_HISTORY'"))
        self.assertTrue(self.refused("UPDATE SourceReference SET ownerType='JOB' WHERE ownerType='PAGE'"))
        self.assertTrue(self.refused("UPDATE Source SET refCount=3"))

    def test_unowned_original_is_not_a_valid_extra_payload(self):
        self.assertTrue(self.refused(f"""INSERT INTO SourceBlob SELECT replace(sourceBlobId,'000003','000007'),
            byteSha256,byteCount,mime,encodedWidth,encodedHeight,replace(relativePath,'000003','000007'),
            state,0,ingestTransactionId,createdAt FROM SourceBlob WHERE sourceBlobId='{BLOB}'"""))

    def test_saved_ocr_has_a_separate_terminal_owner_without_a_worker_pin(self):
        self.assertFalse(self.refused(*self.SUCCESSFUL_PRODUCER))
        self.assertTrue(self.refused(*self.SUCCESSFUL_PRODUCER, "UPDATE OcrPageWork SET resultJobId=NULL"))
        self.assertTrue(self.refused(*self.SUCCESSFUL_PRODUCER, "DELETE FROM OcrResult"))

    def test_nonempty_utf8_fingerprint_still_has_to_pass_the_live_source_guard(self):
        self.assertFalse(self.refused("UPDATE Source SET conversionFingerprint='sRGB-É'"))
        self.assertTrue(self.refused("UPDATE Source SET conversionFingerprint=char(0)||'unreadable-in-editor'"))

    def test_stage_fts_command_detects_postings_mismatch_and_requires_write_access(self):
        writer = catalog.ROOT / "data/src/main/kotlin/app/pristinepage/data/library/RoomLibrarySavedCatalog.kt"
        action, = re.findall(r'sql\.execSQL\("(INSERT INTO OcrResultFts.*integrity-check.*)"\)', writer.read_text(encoding="utf-8"))
        cli = shutil.which("sqlite3")
        self.assertIsNotNone(cli)
        healthy = (
            "CREATE TABLE OcrResult(indexedText TEXT NOT NULL);"
            "CREATE VIRTUAL TABLE OcrResultFts USING FTS4(indexedText,content=OcrResult,tokenize=unicode61);"
            "INSERT INTO OcrResult VALUES('correct index terms');"
            "INSERT INTO OcrResultFts(rowid,indexedText) VALUES(1,'correct index terms');"
        )

        def verify(extra):
            return subprocess.run([cli, "-batch", "-bail", ":memory:"], input=healthy + extra + action + ";",
                                  text=True, capture_output=True, timeout=10)

        good = verify("")
        self.assertEqual(0, good.returncode, good.stderr)
        bad = verify("UPDATE OcrResult SET indexedText='different search terms';")
        self.assertNotEqual(0, bad.returncode)
        self.assertIn("malformed", bad.stderr)
        self.assertIn("readonly", verify("PRAGMA query_only=ON;").stderr)
