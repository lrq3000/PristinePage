"""Memory-only query boundary tests; no allocator, files, subprocess, scanner or device action."""

import shlex
import sqlite3
import unittest

from observe_media_scan import ObservedMediaScan


class ObservedScanQueryTest(unittest.TestCase):
    def test_images_query_executes_without_the_files_only_media_type_column(self):
        self.exercise("images/media", "CREATE TABLE media(_id INTEGER,_data TEXT,media_type INTEGER,mime_type TEXT,width INTEGER,height INTEGER); CREATE VIEW images AS SELECT _id,_data,mime_type,width,height FROM media", "images")

    def test_files_query_retains_media_type_and_the_exact_sql_path_after_shell_parsing(self):
        self.exercise("file", "CREATE TABLE files(_id INTEGER,_data TEXT,media_type INTEGER,mime_type TEXT,width INTEGER,height INTEGER)", "files")

    def exercise(self, collection, schema, table):
        runner = object.__new__(ObservedMediaScan)
        runner.serial = "emulator-explicit"
        with sqlite3.connect(":memory:") as database:
            database.executescript(schema)
            def command(args):
                self.assertEqual(["-s", "emulator-explicit", "shell", "content", "query"], list(map(str, args[1:6])))
                projection = args[args.index("--projection") + 1].split(":")
                predicate = shlex.split(args[args.index("--where") + 1])
                self.assertEqual(["_data='/storage/emulated/0/Pictures/PristinePage-QA-981904ac-9b2b-4019-b6c1-fe95f44e7ebb/source-a.png'"], predicate)
                rows = database.execute("SELECT " + ",".join(projection) + " FROM " + table + " WHERE " + predicate[0]).fetchall()
                return "No result found.\n" if not rows else str(rows)
            runner.command = command
            self.assertEqual("No result found.\n", runner.exact_rows(collection))


if __name__ == "__main__":
    unittest.main()
