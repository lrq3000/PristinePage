"""Check capture-day discovery and exact-page matching on the actual Room queries."""

import sqlite3
import unittest
from uuid import UUID

import test_ocr_index as fixture


class CaptureDayDiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        date_schema = [sql for sql in fixture.create_tables(5) if "`SourceCaptureDay`" in sql]
        self.db.executescript(";\n".join([*fixture.create_tables(2), *date_schema, fixture.SEED]))

    def query(self, method):
        sql, = fixture.literals(fixture.DB / "CatalogDao.kt",
                                rf'@Query\("""((?:(?!""").)*)"""\)\s*internal abstract fun {method}\(')
        hidden, = fixture.literals(fixture.DB / "AtelierDraftDao.kt", r'HIDDEN_ATELIER_DOCUMENT = """(.*?)"""')
        return sql.replace("$HIDDEN_ATELIER_DOCUMENT", hidden)

    def test_day_seek_resumes_inside_one_shared_blob_and_does_not_scan_unrelated_days(self):
        query = self.query("scanCaptureDayCandidates")
        later = str(UUID(int=2**128 - 1))
        self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',1,NULL,0)", (later, fixture.DOC, fixture.SOURCE))
        self.db.execute("INSERT INTO RecipeRevision VALUES (?,1,?,1,X'010203',?,NULL,0)", (later, fixture.SOURCE, "c" * 64))
        self.db.execute("INSERT INTO SourceCaptureDay VALUES (?,?,19782)", (fixture.BLOB, "a" * 64))

        def find(day=19782, blob=None, page=None):
            params = dict(epochDay=day, afterBlob=blob, afterPage=page, limit=1)
            return self.db.execute(query, params).fetchall()

        self.assertEqual([(fixture.DOC, fixture.PAGE, fixture.BLOB)], find())
        self.assertEqual([(fixture.DOC, later, fixture.BLOB)], find(blob=fixture.BLOB, page=fixture.PAGE))
        self.assertEqual([], find(blob=fixture.BLOB, page=later))
        self.assertEqual([], find(day=19783))
        plan = self.db.execute("EXPLAIN QUERY PLAN " + query, dict(epochDay=19783, afterBlob=None, afterPage=None, limit=1)).fetchall()
        self.assertTrue(any("SEARCH" in row[-1] and "index_SourceCaptureDay_epochDay_sourceBlobId" in row[-1] for row in plan))
        self.db.execute("UPDATE SourceCaptureDay SET byteSha256=?", ("b" * 64,))
        self.assertEqual([], find())

    def test_exact_match_distinguishes_a_bound_day_from_absence_or_changed_digest(self):
        query = self.query("metadataMatchForSavedPage")

        def matches():
            return self.db.execute(query, dict(page=fixture.PAGE, document=fixture.DOC, mime=None,
                                               orientation=None, epochDay=19782)).fetchall()

        self.assertEqual([], matches())
        self.db.execute("INSERT INTO SourceCaptureDay VALUES (?,?,NULL)", (fixture.BLOB, "a" * 64))
        self.assertEqual([], matches())
        self.db.execute("UPDATE SourceCaptureDay SET epochDay=19782")
        self.assertEqual([(fixture.SOURCE, 1, fixture.BLOB, "a" * 64, "b" * 64, "c" * 64, 3)], matches())
        self.db.execute("UPDATE SourceCaptureDay SET byteSha256=CAST(byteSha256 AS BLOB)")
        self.assertEqual([], matches())

    def test_cursor_orders_blobs_before_pages_and_seeks_from_the_blob_lower_bound(self):
        query = self.query("scanCaptureDayCandidates")
        blob = str(UUID(int=2**128 - 1))
        source = str(UUID(int=1))
        page = str(UUID(int=2))
        conversion = str(UUID(int=3))
        converted_page = str(UUID(int=2**128 - 2))
        self.db.execute("""INSERT INTO SourceBlob SELECT ?,byteSha256,byteCount,mime,encodedWidth,encodedHeight,?,
                         state,1,ingestTransactionId,createdAt FROM SourceBlob WHERE sourceBlobId=?""",
                        (blob, f"sources/{blob}/original.bin", fixture.BLOB))
        self.db.execute("""INSERT INTO Source SELECT ?,?,pixelSha256,width,height,exifOrientation,?,
                         profileSha256,refCount,state,createdAt FROM Source WHERE sourceId=?""",
                        (source, blob, "srgb-v1", fixture.SOURCE))
        self.db.execute("""INSERT INTO Source SELECT ?,sourceBlobId,pixelSha256,width,height,exifOrientation,?,
                         profileSha256,refCount,state,createdAt FROM Source WHERE sourceId=?""",
                        (conversion, "srgb-v2", fixture.SOURCE))
        self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',1,NULL,0)", (page, fixture.DOC, source))
        self.db.execute("INSERT INTO Page VALUES (?,?,?,1,'ACTIVE',2,NULL,0)", (converted_page, fixture.DOC, conversion))
        self.db.executemany("INSERT INTO SourceCaptureDay VALUES (?,?,19782)",
                            [(fixture.BLOB, "a" * 64), (blob, "a" * 64)])
        after_first = dict(epochDay=19782, afterBlob=fixture.BLOB, afterPage=fixture.PAGE, limit=1)
        self.assertEqual([(fixture.DOC, converted_page, fixture.BLOB)], self.db.execute(query, after_first).fetchall())
        after_conversion = dict(after_first, afterPage=converted_page)
        self.assertEqual([(fixture.DOC, page, blob)], self.db.execute(query, after_conversion).fetchall())
        plan = self.db.execute("EXPLAIN QUERY PLAN " + query, after_conversion).fetchall()
        self.assertTrue(any("SEARCH" in row[-1] and "index_SourceCaptureDay_epochDay_sourceBlobId" in row[-1]
                            and "epochDay=?" in row[-1] and "sourceBlobId>?" in row[-1] for row in plan))
