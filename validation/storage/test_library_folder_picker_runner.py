from argparse import Namespace
from contextlib import closing
from hashlib import sha256
from pathlib import Path
import sqlite3
import shutil
from subprocess import TimeoutExpired, run
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from run_library_folder_picker_ldplayer import (
    check_fixture_root, recheck_target, select_backup_case, select_settings_picker_case, verify_settings_catalog,
)
from run_local_ocr_guest import read_boot_serial


class LibraryFolderPickerRunnerTest(unittest.TestCase):
    @patch("run_library_folder_picker_ldplayer.verify_target")
    def test_once_only_read_retry_for_a_timeout(self, verify):
        verify.side_effect = [TimeoutExpired("get-serialno", 12), None]
        recheck_target("claim", "coordinator")
        self.assertEqual(2, verify.call_count)

    @patch("run_library_folder_picker_ldplayer.verify_target")
    def test_positive_mismatch_never_retried(self, verify):
        verify.side_effect = RuntimeError("different guest")
        with self.assertRaisesRegex(RuntimeError, "different guest"):
            recheck_target("claim", "coordinator")
        verify.assert_called_once_with("claim", "coordinator")

    def test_boot_serial_timeout_remains_inside_the_existing_readiness_budget(self):
        def deadline():
            raise TimeoutExpired("get-serialno", 12)

        self.assertIsNone(read_boot_serial(deadline))
        self.assertEqual("emulator-5558", read_boot_serial(lambda: " emulator-5558\n"))
        self.assertIsNone(read_boot_serial(lambda: "error: device not found"))

    def test_complete_backup_allows_only_one_new_canonical_child(self):
        original = {"catalogs", "generations", "objects"}
        backup = "PristinePage-backup-10000000-aaaa-4aaa-8aaa-000000000001"
        result = original | {backup}
        self.assertEqual(backup, check_fixture_root(original, result, False, False, True, True))
        for wrong in (original, result | {"unrelated"}, result | {backup[:-1] + "2"}, result - {"objects"}):
            with self.assertRaises(RuntimeError):
                check_fixture_root(original, wrong, False, False, True, True)
        with self.assertRaises(RuntimeError):
            check_fixture_root(original, result, False, False, False, False)
        self.assertEqual(backup, check_fixture_root(original, result, False, False, True, False))

    def test_settings_backup_does_not_run_on_a_retained_or_mixed_guest_batch(self):
        flags = dict(settings_backup=True, fresh=True, backup_export=False, probe_write=False, ocr_activation=False,
                     working_catalog=False, probe_directory=False, activation=False, selected_catalog=False,
                     activation_compatibility=False, expect_home=False, settings_picker_only=False)
        self.assertEqual("app.pristinepage.LibraryFolderFilesJourneyTest#settingsPublishesOneBackupOfTheSyntheticDefaultLibrary",
                         select_backup_case(Namespace(**flags)))
        for key in flags.keys() - {"settings_backup"}:
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "fresh.*standalone"):
                select_backup_case(Namespace(**(flags | {key: not flags[key]})))
        self.assertIsNone(select_backup_case(Namespace(**(flags | {"settings_backup": False}))))

    def test_picker_cancellation_is_standalone_on_the_retained_synthetic_guest(self):
        flags = dict(settings_picker_only=True, settings_backup=False, fresh=False, backup_export=False,
                     probe_write=False, ocr_activation=False, working_catalog=False, probe_directory=False,
                     activation=False, selected_catalog=False, activation_compatibility=False, expect_home=False)
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#settingsOpensAndCancelsFilesWithoutStartingABackup"
        self.assertEqual(case, select_settings_picker_case(Namespace(**flags)))
        for key in flags.keys() - {"settings_picker_only"}:
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "retained.*standalone"):
                select_settings_picker_case(Namespace(**(flags | {key: True})))
        self.assertIsNone(select_settings_picker_case(Namespace(**(flags | {"settings_picker_only": False}))))

    def test_settings_readback_rejects_a_valid_catalog_with_another_source_binding(self):
        first, second = (b'{"geometry":{"outputQuarterTurns":0}}', b'{"geometry":{"outputQuarterTurns":1}}')
        original = "5" * 64
        expected = ("album", "page", "source", "pixels", sha256(first).hexdigest(), sha256(second).hexdigest())
        with TemporaryDirectory() as root:
            path = Path(root) / "closed.sqlite"
            with closing(sqlite3.connect(path)) as db:
                db.executescript("""
                    CREATE TABLE Document(documentId TEXT PRIMARY KEY, title TEXT, documentRevision INTEGER);
                    CREATE TABLE SourceBlob(sourceBlobId TEXT PRIMARY KEY, byteSha256 TEXT, byteCount INTEGER);
                    CREATE TABLE Source(sourceId TEXT PRIMARY KEY, sourceBlobId TEXT REFERENCES SourceBlob, pixelSha256 TEXT);
                    CREATE TABLE Page(pageId TEXT PRIMARY KEY, documentId TEXT REFERENCES Document,
                        sourceId TEXT REFERENCES Source, currentRecipeRevision INTEGER, ordinal INTEGER);
                    CREATE TABLE LibraryScanName(pageId TEXT REFERENCES Page, documentId TEXT REFERENCES Document, filename TEXT);
                    CREATE TABLE RecipeRevision(pageId TEXT REFERENCES Page, sourceId TEXT REFERENCES Source,
                        recipeRevision INTEGER, parentRevision INTEGER, canonicalRecipe BLOB);
                    CREATE TABLE UndoEntry(documentId TEXT REFERENCES Document, action TEXT, appliedDocumentRevision INTEGER, state TEXT);
                    INSERT INTO Document VALUES ('album','Synthetic Settings backup',3);
                    INSERT INTO Page VALUES ('page','album','source',2,0);
                    INSERT INTO Source VALUES ('source','blob','pixels');
                    INSERT INTO LibraryScanName VALUES ('page','album','Scan page.png');
                    INSERT INTO UndoEntry VALUES ('album','RECIPE_EDIT',3,'AVAILABLE');
                """)
                db.execute("INSERT INTO SourceBlob VALUES ('blob',?,75)", (original,))
                db.executemany("INSERT INTO RecipeRevision VALUES ('page','source',?,?,?)", [(1,None,first),(2,1,second)])
                db.commit()
                # Exercise the real FTS4 capability required by a Room backup, not just the host Python build's regular tables.
                run([shutil.which("sqlite3"), "-batch", "-bail", str(path), "CREATE VIRTUAL TABLE FixtureSearch USING fts4(text)"],
                    check=True, timeout=10, capture_output=True)
                verify_settings_catalog(path, original, 75, expected)
                db.execute("UPDATE Source SET pixelSha256 = 'another normalized binding'")
                db.commit()
                self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
                with self.assertRaisesRegex(RuntimeError, "scan.*binding"):
                    verify_settings_catalog(path, original, 75, expected)
