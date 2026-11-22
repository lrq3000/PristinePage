"""Run generated Room FTS4 DDL and actual migration/query on a local SQLite CLI (not Android)."""

import json
from pathlib import Path
import re
import shutil
import subprocess
import unicodedata
import unittest


ROOT = Path(__file__).resolve().parents[2]
DB = ROOT / "data/src/main/kotlin/app/pristinepage/data/db"
SCHEMAS = ROOT / "data/schemas/app.pristinepage.data.db.PristineDatabase"
DOC, SOURCE, BLOB, PAGE, DRAFT = [
    f"aaaaaaaa-aaaa-4aaa-8aaa-{i:012d}" for i in range(1, 6)
]
SEED = f"""
BEGIN;
INSERT INTO Document VALUES ('{DOC}','Existing album',1,'ACTIVE',0,0,NULL);
INSERT INTO SourceBlob VALUES ('{BLOB}','{'a' * 64}',4,'image/png',3,2,'sources/{BLOB}/original.bin','READY',1,'{DRAFT}',0);
INSERT INTO Source VALUES ('{SOURCE}','{BLOB}','{'b' * 64}',3,2,1,'srgb-v1',NULL,0,'READY',0);
INSERT INTO Page VALUES ('{PAGE}','{DOC}','{SOURCE}',1,'ACTIVE',0,NULL,0);
INSERT INTO RecipeRevision VALUES ('{PAGE}',1,'{SOURCE}',1,X'010203','{'c' * 64}',NULL,0);
COMMIT;
"""
INSERT_TEXT = f"""
INSERT INTO OcrResult (pageId,recipeRevision,sourcePixelSha256,engineId,engineVersion,layout,indexedText)
VALUES ('{PAGE}',1,'{'b' * 64}','fixture',1,'AUTOMATIC','École invoice');
"""


def literals(path, pattern):
    return re.findall(pattern, path.read_text(encoding="utf-8"), re.S)


def exported(version):
    schema = json.loads((SCHEMAS / f"{version}.json").read_text(encoding="utf-8"))
    return schema["database"]["entities"]


def create_tables(version):
    return [sql.replace("${TABLE_NAME}", entity["tableName"]) for entity in exported(version)
            for sql in [entity["createSql"], *(i["createSql"] for i in entity.get("indices", []))]]


def sync_triggers():
    return next(e["contentSyncTriggers"] for e in exported(3) if e["tableName"] == "OcrResultFts")


def match():
    pattern = r'@Query\("""((?:(?!""").)*)"""\)\s*protected abstract fun selectMatches\('
    clause, = literals(DB / "OcrResultDao.kt", pattern)
    hidden, = literals(DB / "AtelierDraftDao.kt", r'HIDDEN_ATELIER_DOCUMENT = """(.*?)"""')
    return clause.replace("$HIDDEN_ATELIER_DOCUMENT", hidden)


class OcrIndexSqlTest(unittest.TestCase):
    def run_sql(self, statements):
        cli = shutil.which("sqlite3")
        self.assertIsNotNone(cli, "An existing FTS4-capable sqlite3 CLI is required")
        result = subprocess.run(
            [cli, "-batch", "-bail", ":memory:"],
            input=";\n".join(s.rstrip().rstrip(";") for s in statements) + ";\n",
            capture_output=True, text=True, encoding="utf-8", timeout=20,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    def query(self, term="ecole", limit=2):
        self.assertRegex(term, r"\A[a-z]+\Z")
        return match().replace(":expression", f"""'"{term}"'""").replace(":afterPageId", "NULL").replace(":limit", str(limit))

    def test_malformed_page_id_does_not_consume_the_first_match_slot(self):
        malformed = "0" * 36
        self.assertEqual([f"{DOC}|{PAGE}"], self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT,
            "BEGIN",
            f"INSERT INTO Page VALUES ('{malformed}','{DOC}','{SOURCE}',1,'ACTIVE',1,NULL,0)",
            f"INSERT INTO RecipeRevision VALUES ('{malformed}',1,'{SOURCE}',1,X'010203','{'c' * 64}',NULL,0)",
            INSERT_TEXT.replace(PAGE, malformed), "COMMIT",
            self.query(limit=1),
            "PRAGMA foreign_key_check",
        ]))

    def test_producer_metadata_guard_rejects_malformed_result_storage(self):
        sql, = literals(DB / "OcrResultDao.kt", r'@Query\("""((?:(?!""").)*)"""\)\s*abstract fun matchesProducerMetadata\(')
        for name, value in (
            ("page", f"'{PAGE}'"), ("revision", "1"), ("sourceSha", f"'{'b' * 64}'"),
            ("engineVersion", "1"), ("engine", "'fixture'"), ("layout", "'AUTOMATIC'"),
        ):
            sql = sql.replace(f":{name}", value)
        for field, bad in (
            ("indexedText", "CAST('invoice' AS BLOB)"),
            ("indexedText", "'invoice' || char(0) || 'suffix'"),
            ("indexedText", "replace(hex(zeroblob(262145)), '00', '界')"),
            ("verbatimText", "CAST('invoice' AS BLOB)"),
            ("verbatimText", "'invoice' || char(0) || 'suffix'"),
            ("verbatimText", "replace(hex(zeroblob(131073)), '00', '界')"),
        ):
            with self.subTest(field=field, bad=bad):
                self.assertEqual(["1", "0", "1"], self.run_sql([
                    "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT, sql,
                    "BEGIN", f"UPDATE OcrResult SET {field}={bad}", sql, "ROLLBACK", sql,
                ]))

    def test_populated_v2_upgrade_preserves_recipe_and_has_no_invented_text(self):
        migration = DB / "OcrIndexMigration.kt"
        self.assertTrue(migration.exists(), "OCR index migration is absent")
        sql = literals(migration, r'db\.execSQL\("""(.*?)"""\)')
        self.assertGreaterEqual(len(sql), 3)
        self.assertEqual(["Existing album|010203", "0", "0"], self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(2), SEED, "BEGIN", *sql, "COMMIT", *sync_triggers(),
            "SELECT title,hex(canonicalRecipe) FROM Document JOIN Page USING(documentId) JOIN RecipeRevision USING(pageId)",
            "SELECT COUNT(*) FROM OcrResult", "SELECT COUNT(*) FROM OcrResultFts WHERE OcrResultFts MATCH 'invoice'",
            "PRAGMA foreign_key_check",
        ]))
        inspection = [
            """SELECT name,type,CASE WHEN name='OcrResultFts' THEN sql ELSE '' END FROM sqlite_master
                WHERE name IN ('OcrResult','OcrResultFts','index_OcrResult_pageId') ORDER BY name""",
            "PRAGMA table_info(OcrResult)", "PRAGMA foreign_key_list(OcrResult)", "PRAGMA index_list(OcrResult)",
        ]
        # FTS option syntax is part of Room's table identity; compare the generated and migrated statements too.
        self.assertEqual(self.run_sql([*create_tables(3), *inspection]), self.run_sql([*create_tables(2), *sql, *inspection]))

    def test_fresh_index_fences_stale_removed_and_hidden_pages_and_rolls_back(self):
        self.assertTrue((SCHEMAS / "3.json").exists(), "Room has not exported the OCR schema")
        marker = "SELECT 'hidden-or-absent'"
        self.assertEqual([f"{DOC}|{PAGE}", f"{DOC}|{PAGE}", f"{DOC}|{PAGE}"] + ["hidden-or-absent"] * 6, self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT, self.query(),
            "BEGIN", "UPDATE OcrResult SET indexedText='transient'", self.query("transient"),
            "ROLLBACK", self.query(), self.query("transient"),
            "UPDATE OcrResult SET recipeRevision=2", self.query(), marker,
            "UPDATE OcrResult SET recipeRevision=1",
            f"UPDATE OcrResult SET sourcePixelSha256='{'f' * 64}'", self.query(), marker,
            f"UPDATE OcrResult SET sourcePixelSha256='{'b' * 64}'",
            "UPDATE Page SET state='REMOVED',ordinal=NULL,removedAt=1", self.query(), marker,
            "UPDATE Page SET state='ACTIVE',ordinal=0,removedAt=NULL",
            "UPDATE Page SET ordinal=-1", self.query(), marker, "UPDATE Page SET ordinal=0",
            f"""INSERT INTO AtelierDraft (draftId,stagingDocumentId,state,generation,step,createdAt)
                VALUES ('{DRAFT}','{DOC}','EDITING',1,1,0)""",
            self.query(), marker,
            "DELETE FROM AtelierDraft", "UPDATE Document SET lifecycle='TOMBSTONED',deletedAt=2", self.query(), marker,
        ]))

    def test_cascading_page_purge_removes_result_and_index_entries(self):
        self.assertEqual(["1", "0", "0"], self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT,
            "SELECT COUNT(*) FROM OcrResultFts WHERE OcrResultFts MATCH 'invoice'",
            "BEGIN", "DELETE FROM RecipeRevision", "DELETE FROM Page", "COMMIT",
            "SELECT COUNT(*) FROM OcrResult", "SELECT COUNT(*) FROM OcrResultFts WHERE OcrResultFts MATCH 'invoice'",
        ]))

    def test_normalized_search_can_find_a_page_with_decomposed_original_text(self):
        original = "o\u0323\u0302"
        nfc = unicodedata.normalize("NFC", original)
        self.assertEqual([f"{DOC}|{PAGE}", original], self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT,
            f"UPDATE OcrResult SET indexedText='{nfc}',verbatimText='{original}'",
            match().replace(":expression", f"""'"{nfc}"'""").replace(":afterPageId", "NULL").replace(":limit", "2"),
            "SELECT COALESCE(verbatimText,indexedText) FROM OcrResult",
        ]))

    def test_literal_cjk_phrase_finds_an_inner_term_but_not_skipped_characters(self):
        self.assertEqual([f"{DOC}|{PAGE}", "東京都の領収書です", f"{DOC}|{PAGE}"], self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT,
            "UPDATE OcrResult SET indexedText='東 京 都 の 領 収 書 で す',verbatimText='東京都の領収書です'",
            match().replace(":expression", """'"領 収 書"'""").replace(":afterPageId", "NULL").replace(":limit", "2"),
            match().replace(":expression", """'"領 書"'""").replace(":afterPageId", "NULL").replace(":limit", "2"),
            "SELECT COALESCE(verbatimText,indexedText) FROM OcrResult",
            "UPDATE OcrResult SET indexedText='領、収 書',verbatimText='領、収書'",
            match().replace(":expression", """'"領 収 書"'""").replace(":afterPageId", "NULL").replace(":limit", "2"),
        ]))

    def test_literal_voiced_kana_finds_combined_character_only(self):
        voiced = match().replace(":expression", """'"ガ"'""").replace(":afterPageId", "NULL").replace(":limit", "2")
        self.assertEqual(["ｶ ｷﾞ", f"{DOC}|{PAGE}", "ｷｶﾞｺ"], self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT,
            "UPDATE OcrResult SET indexedText='カ ギ',verbatimText='ｶ ｷﾞ'", voiced,
            "SELECT COALESCE(verbatimText,indexedText) FROM OcrResult",
            "UPDATE OcrResult SET indexedText='キ ガ コ',verbatimText='ｷｶﾞｺ'", voiced,
            "SELECT COALESCE(verbatimText,indexedText) FROM OcrResult",
        ]))

    def test_mixed_script_queries_require_each_literal_and_keep_cjk_token_order(self):
        def query(phrase):
            return match().replace(":expression", f"'{phrase}'").replace(":afterPageId", "NULL").replace(":limit", "2")

        self.assertEqual([f"{DOC}|{PAGE}"] * 3, self.run_sql([
            "PRAGMA foreign_keys=ON", *create_tables(3), *sync_triggers(), SEED, INSERT_TEXT,
            "UPDATE OcrResult SET indexedText='北 京 市 の 領 収 書 영 수 증 입 니 다 Invoice فاتورة 𠀀 林'",
            query('"京 市" "invoice" "فاتورة"'), query('"수 증"'), query('"𠀀 林"'),
            query('"市 京" "invoice"'), query('"영 증"'), query('"京 市" "invoices" "فاتورة"'),
        ]))


if __name__ == "__main__":
    unittest.main()
