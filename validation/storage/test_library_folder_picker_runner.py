from subprocess import TimeoutExpired
import unittest
from unittest.mock import patch

from run_library_folder_picker_ldplayer import check_fixture_root, recheck_target
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
