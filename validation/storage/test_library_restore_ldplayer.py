"""Check the library restore runner's exact named-outcome parser without a guest."""

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from run_library_restore_ldplayer import close_owned, inspect_prior_pid, main, read_results, wait_for_allocator


class LibraryRestoreRuntimeEvidenceTest(unittest.TestCase):
    APPROVED_COMMIT = "304f257cd9ba689efd6392f1145191a42da97ae9"
    APPROVED_APK = "dd89fca5caf988c7d8c6cb92fd0689f4cebf365bfc7fd75e1f3db5cb6e436e9a"
    FIRST = "app.pristinepage.data.RoomLibraryLocalRestoreTest#aClosedBackupRestoresOrderedOriginalsEditsAndSearchWithoutRerunningRecognition"
    SECOND = "app.pristinepage.data.RoomLibraryLocalRestoreTest#aBackupWithADamagedOriginalLeavesTheFreshTargetUnclaimed"

    def block(self, case, code):
        cls, method = case.split("#")
        return f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: {code}\n"

    def test_accounts_for_each_named_success_and_failure(self):
        first = self.block(self.FIRST, 1) + self.block(self.FIRST, 0)
        second = self.block(self.SECOND, 1) + self.block(self.SECOND, 0)
        end = "\nINSTRUMENTATION_RESULT: stream=\nOK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual((sorted([self.FIRST, self.SECOND]), []), read_results(first + second + end, [self.FIRST, self.SECOND]))
        failure = "\nINSTRUMENTATION_RESULT: stream=\nFAILURES!!!\nTests run: 2,  Failures: 1\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual(([self.FIRST], [self.SECOND]),
                         read_results(first + self.block(self.SECOND, 1) + self.block(self.SECOND, -2) + failure, [self.FIRST, self.SECOND]))

    def test_no_green_for_missing_unexpected_or_unterminated_cases(self):
        first = self.block(self.FIRST, 1) + self.block(self.FIRST, 0)
        second = self.block(self.SECOND, 1) + self.block(self.SECOND, 0)
        end = "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n"
        for output in (first + end, first + first + end, first + second, first + second + end + "trailing"):
            with self.subTest(output=output[-100:]), self.assertRaises(RuntimeError):
                read_results(output, [self.FIRST, self.SECOND])

    def test_success_requires_ordered_statuses_and_the_terminal_result_block(self):
        start = self.block(self.FIRST, 1)
        finish = self.block(self.FIRST, 0)
        result = "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        for output in (finish + start + result, start + finish + start.split("INSTRUMENTATION_STATUS_CODE:")[0] + result,
                       start + finish + "OK (1 test)\n" + result.replace("OK (1 test)\n", ""), start + finish + "INSTRUMENTATION_STATUS_CODE: bogus\n" + result):
            with self.subTest(output=output), self.assertRaises(RuntimeError):
                read_results(output, [self.FIRST])

    def test_caller_chosen_artifact_digest_is_refused_before_verification(self):
        with patch.object(sys, "argv", ["runner", self.APPROVED_COMMIT, "0" * 64]), patch("run_library_restore_ldplayer.verify_apk") as verify:
            with self.assertRaisesRegex(RuntimeError, "Unapproved"):
                main()
            verify.assert_not_called()

    def test_unrelated_released_guest_cannot_be_started(self):
        with patch.object(sys, "argv", ["runner", self.APPROVED_COMMIT, self.APPROVED_APK, "agent-pristinepage-ocr-aaaaaaaaaaaa"]), \
                 patch.object(Path, "mkdir", side_effect=AssertionError("Rejected guest attempted to stage runtime evidence")):
            with self.assertRaisesRegex(RuntimeError, "dedicated"):
                main()

    def test_only_the_lock_collision_can_retry_before_ownership(self):
        run = SimpleNamespace(lock=Path("fixture.lock"), claimed=False)
        attempts = []

        def contend():
            attempts.append("attempt")
            if len(attempts) == 1:
                raise FileExistsError(17, "synthetic test collision", str(run.lock))
            return "admitted"

        with patch("run_library_restore_ldplayer.time.sleep", return_value=None):
            self.assertEqual("admitted", wait_for_allocator(run, contend, before_claim=True))
        self.assertEqual(["attempt", "attempt"], attempts)

    def test_a_claimed_operation_or_a_different_filename_is_never_replayed(self):
        for claimed, filename in ((True, "fixture.lock"), (False, "different.file")):
            run = SimpleNamespace(lock=Path("fixture.lock"), claimed=claimed)
            attempts = []

            def failure():
                attempts.append("attempt")
                raise FileExistsError(17, "synthetic refusal", filename)

            with self.subTest(claimed=claimed, filename=filename), self.assertRaises(FileExistsError):
                wait_for_allocator(run, failure, before_claim=True)
            self.assertEqual(["attempt"], attempts)

    def test_prior_string_pid_requires_real_process_exit_not_a_cross_epoch_timestamp_comparison(self):
        def stopped(pid):
            self.assertEqual(123, pid)
            return {"running": False, "start": 999}

        self.assertEqual(stopped(123), inspect_prior_pid(stopped, "123"))
        with self.assertRaises(RuntimeError):
            inspect_prior_pid(lambda _: {"running": True, "start": 1}, "123")
        with self.assertRaises(RuntimeError):
            inspect_prior_pid(lambda _: self.fail("Invalid PID reached the host"), "0")

    def test_cleanup_contention_keeps_exact_ownership_and_records_the_pending_exit(self):
        identities = [[43, 500], [44, 600]]
        record = {"name": "test-guest", "index": 1, "runtime_processes": identities}
        receipts = {}
        path = Path("allocation.lock")

        def contended():
            raise FileExistsError(17, "allocator is in use", str(path))

        def save(leaf, payload, *, durable):
            self.assertTrue(durable)
            receipts[leaf] = payload

        run = SimpleNamespace(lock=path, close_owned=contended, owned=lambda: record, path=Path, save=save, generation="fixture-generation")
        with patch("run_library_restore_ldplayer.time.monotonic", side_effect=[0, 301]):
            with self.assertRaisesRegex(RuntimeError, "still owned"):
                close_owned(run)
        self.assertEqual({"generation": "fixture-generation", "guest": "test-guest", "index": 1,
                          "originalProcesses": [[43, 500], [44, 600]], "reason": "allocation-lock-contention"},
                         receipts[Path("cleanup-pending.json")])
