"""Exercise the portable-catalog reader's exact SQL on Room's exported DDL using the installed FTS4-capable SQLite CLI."""

import json
from pathlib import Path
import re
import shutil
import subprocess
import unittest

from test_ocr_index import create_tables, sync_triggers


ROOT = Path(__file__).resolve().parents[2]
READER = ROOT / "data/src/main/kotlin/app/pristinepage/data/library/RoomLibraryCatalogReader.kt"
SCHEMA = ROOT / "data/schemas/app.pristinepage.data.db.PristineDatabase/8.json"


class LibraryCatalogReaderSqlTest(unittest.TestCase):
    def sqlite(self, *statements):
        cli = shutil.which("sqlite3")
        self.assertIsNotNone(cli, "The repository's FTS4-capable SQLite CLI is required")
        schema = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]
        statements = create_tables(schema["version"]) + sync_triggers() + schema["setupQueries"] + list(statements)
        process = subprocess.run(
            [cli, "-batch", "-bail", "-json", ":memory:"],
            input=";\n".join(sql.strip().rstrip(";") for sql in statements) + ";\n",
            text=True, encoding="utf-8", capture_output=True, timeout=15,
        )
        self.assertEqual(0, process.returncode, process.stderr)
        return json.loads(process.stdout) if process.stdout else []

    def actual_queries(self):
        values = dict(re.findall(r'const val (\w+) = "([^"\n]*)"', READER.read_text(encoding="utf-8")))
        bounds = values["SCHEMA_BOUNDS"]
        self.assertIsNotNone(bounds)
        return values["SCHEMA_QUERY"].replace("$SCHEMA_BOUNDS", bounds), values["ROOM_IDENTITY"]

    def test_exported_room_schema_fits_the_raw_schema_guard_and_includes_fts_triggers(self):
        query, identity = self.actual_queries()
        records = self.sqlite(query)
        self.assertTrue(100 < len(records) < 128)
        self.assertTrue(all(list(row.values())[0] == 1 for row in records))
        self.assertTrue(any("room_fts_content_sync_OcrResultFts" in str(row) for row in records))
        expected = json.loads(SCHEMA.read_text(encoding="utf-8"))["database"]["identityHash"]
        self.assertEqual([{"identity_hash": expected}], self.sqlite(identity))

    def test_oversized_schema_sql_is_refused_without_materializing_the_sql(self):
        query, _ = self.actual_queries()
        huge_trigger = "CREATE TRIGGER added AFTER UPDATE ON Document BEGIN SELECT '" + "a" * 17_000 + "'; END"
        rejected = [list(row.values()) for row in self.sqlite(huge_trigger, query) if list(row.values())[0] == 0]
        self.assertEqual([[0, None, None, None, None]], rejected)

    def test_unexpected_trigger_is_observable_even_with_the_same_room_identity(self):
        query, identity = self.actual_queries()
        add_trigger = "CREATE TRIGGER added AFTER UPDATE ON Document BEGIN SELECT 1; END"
        self.assertEqual(self.sqlite(identity), self.sqlite(add_trigger, identity))
        records = self.sqlite(add_trigger, query)
        self.assertTrue(any(list(row.values())[1:4] == ["trigger", "added", "Document"] for row in records))
        self.assertNotEqual(self.sqlite(query), records)


if __name__ == "__main__":
    unittest.main()
