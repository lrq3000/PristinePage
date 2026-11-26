"""Execute the expected new scan-name migration on the actual exported Room v7 schema."""

import json
import re
import shutil
import subprocess
import unittest

from test_ocr_index import DB, SCHEMAS, create_tables, sync_triggers


class LibraryScanNameMigrationSqlTest(unittest.TestCase):
    ALBUMS = """
    BEGIN;
    INSERT INTO Document VALUES ('doc','Synthetic',1,'ACTIVE',0,0,NULL);
    INSERT INTO Document VALUES ('other','Other',1,'ACTIVE',0,0,NULL);
    INSERT INTO SourceBlob VALUES ('blob',printf('%064d',0),3,'image/png',1,1,'sources/blob/original.bin','READY',1,'original',0);
    INSERT INTO Source VALUES ('source','blob',printf('%064d',0),1,1,1,'srgb',NULL,1,'READY',0);
    INSERT INTO Page VALUES ('page','doc','source',1,'ACTIVE',0,NULL,0);
    INSERT INTO Page VALUES ('page2','doc','source',1,'ACTIVE',1,NULL,0);
    INSERT INTO Page VALUES ('page3','other','source',1,'ACTIVE',0,NULL,0);
    INSERT INTO RecipeRevision VALUES ('page',1,'source',1,X'12',printf('%064d',0),NULL,0);
    INSERT INTO RecipeRevision VALUES ('page2',1,'source',1,X'12',printf('%064d',0),NULL,0);
    INSERT INTO RecipeRevision VALUES ('page3',1,'source',1,X'12',printf('%064d',0),NULL,0);
    COMMIT
    """

    def sqlite(self, *commands, succeeds=True, latest=False, before_migration=()):
        cli = shutil.which("sqlite3")
        self.assertIsNotNone(cli)
        version = 8 if latest else 7
        database = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))["database"]
        migration = DB / "LibraryScanNameMigration.kt"
        self.assertTrue(migration.is_file(), "Missing expected scan-name migration")
        statements = re.findall(r'db\.execSQL\("""(.*?)"""\)', migration.read_text(encoding="utf-8"), re.S)
        self.assertTrue(statements)
        scripts = create_tables(version) + sync_triggers() + database["setupQueries"]
        result = subprocess.run(
            [cli, "-batch", "-bail", ":memory:"],
            input=";\n".join(["PRAGMA foreign_keys=ON", *scripts, *before_migration, *([] if latest else statements), *commands]) + ";\n",
            text=True, encoding="utf-8", capture_output=True, timeout=20,
        )
        if succeeds:
            self.assertEqual(0, result.returncode, result.stderr)
        else:
            self.assertNotEqual(0, result.returncode)
        return result

    def test_new_table_does_not_manufacture_names_or_modify_existing_pages(self):
        output = self.sqlite(
            "SELECT (SELECT count(*) FROM Page), (SELECT count(*) FROM LibraryScanName)",
            "SELECT pageId,ordinal FROM Page ORDER BY pageId",
            "SELECT consentId,state,targetUri FROM OcrTextPublication ORDER BY consentId",
            "PRAGMA foreign_key_check",
            before_migration=(self.ALBUMS,
                "INSERT INTO FileTransaction VALUES ('txt','OCR_TEXT','artifact','COMMITTED',NULL,NULL,NULL,NULL,0,X'12',1,NULL,0)",
                "INSERT INTO OcrTextPublication VALUES ('consent-a','txt','owner',printf('%064d',0),0,'NEEDS_VERIFICATION','content://synthetic/first',1)",
                "INSERT INTO OcrTextPublication VALUES ('consent-b','txt','owner',printf('%064d',0),0,'WAITING_DESTINATION',NULL,2)"),
        )
        self.assertEqual(["3|0", "page|0", "page2|1", "page3|0", "consent-a|NEEDS_VERIFICATION|content://synthetic/first",
                          "consent-b|WAITING_DESTINATION|"], output.stdout.splitlines())

    def test_an_unowned_name_cannot_be_inserted(self):
        output = self.sqlite("INSERT INTO LibraryScanName VALUES ('page','album','Test.png','test.png')", succeeds=False)
        self.assertIn("FOREIGN KEY constraint failed", output.stderr)

    def test_a_page_cannot_claim_a_filename_in_another_album(self):
        output = self.sqlite(self.ALBUMS, "INSERT INTO LibraryScanName VALUES ('page','other','Test.png','test.png')", succeeds=False)
        self.assertIn("FOREIGN KEY constraint failed", output.stderr)

    def test_names_are_unique_per_album_not_per_library(self):
        first = "INSERT INTO LibraryScanName VALUES ('page','doc','Test.png','test.png')"
        good = self.sqlite(self.ALBUMS, first, "INSERT INTO LibraryScanName VALUES ('page3','other','test.png','test.png')",
                           "SELECT count(*) FROM LibraryScanName")
        self.assertEqual("2", good.stdout.strip())
        rejected = self.sqlite(self.ALBUMS, first, "INSERT INTO LibraryScanName VALUES ('page2','doc','test.png','test.png')", succeeds=False)
        self.assertIn("UNIQUE constraint failed", rejected.stderr)

    def test_migrated_scan_name_layout_matches_fresh_room_data_definition(self):
        query = """SELECT sql FROM sqlite_master WHERE name='LibraryScanName' OR name LIKE 'index_LibraryScanName_%'
                   OR name='index_Page_pageId_documentId' ORDER BY name"""
        self.assertEqual(self.sqlite(query, latest=True).stdout, self.sqlite(query).stdout)

    def test_soft_removal_retains_name_and_hard_purge_only_removes_purged_page_name(self):
        result = self.sqlite(
            self.ALBUMS,
            "INSERT INTO LibraryScanName VALUES ('page','doc','One.png','one.png'),('page2','doc','Two.png','two.png')",
            "UPDATE Page SET ordinal=NULL,removedAt=3,state='REMOVED' WHERE pageId='page'",
            "SELECT pageId,filename FROM LibraryScanName ORDER BY pageId",
            "BEGIN",
            "DELETE FROM RecipeRevision WHERE pageId='page'",
            "DELETE FROM Page WHERE pageId='page'",
            "COMMIT",
            "SELECT pageId,filename FROM LibraryScanName ORDER BY pageId",
            "PRAGMA foreign_key_check",
        )
        self.assertEqual(["page|One.png", "page2|Two.png", "page2|Two.png"], result.stdout.splitlines())

    def test_name_collision_uses_an_exact_index_seek_in_the_owning_album(self):
        sql = (DB / "LibraryScanNameDao.kt").read_text(encoding="utf-8")
        queries = re.findall(r'@Query\("""(.*?)"""\)\s+abstract fun (\w+)\(', sql, re.S)
        lookup = next((statement for statement, name in queries if name == "containsKey"), None)
        self.assertIsNotNone(lookup, "New ingest must not enumerate retained album names")
        statement = lookup.replace(":document", "'doc'").replace(":key", "'two.png'")
        result = self.sqlite(
            self.ALBUMS,
            "INSERT INTO LibraryScanName VALUES ('page2','doc','Two.png','two.png'),('page3','other','Else.png','else.png')",
            "UPDATE Page SET ordinal=NULL,removedAt=3,state='REMOVED' WHERE pageId='page2'",
            statement,
            lookup.replace(":document", "'other'").replace(":key", "'two.png'"),
            "EXPLAIN QUERY PLAN " + statement,
        )
        self.assertEqual(["1", "0"], result.stdout.splitlines()[:2])
        self.assertIn("index_LibraryScanName_documentId_comparisonKey", result.stdout)
