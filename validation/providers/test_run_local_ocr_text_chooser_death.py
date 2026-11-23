import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, call, patch

from run_local_ocr_text_chooser_death import APP, APP_SHA, OPEN, WRITE, interrupted, main


CONSENT = "15d339ef-1703-4cda-a432-8986417c6589"
TRANSCRIPT = (
    f"INSTRUMENTATION_STATUS: class={OPEN[0]}\n"
    f"INSTRUMENTATION_STATUS: test={OPEN[1]}\n"
    "INSTRUMENTATION_STATUS_CODE: 1\n"
    f"INSTRUMENTATION_STATUS: ocrTextDeathConsent={CONSENT}\n"
    "INSTRUMENTATION_STATUS: ocrTextDeathPid=4017\n"
    "INSTRUMENTATION_STATUS_CODE: 67\n"
    "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
    "INSTRUMENTATION_CODE: 0\n"
)


class ExactInterruptedChooserTest(unittest.TestCase):
    def test_binds_the_consent_and_process_to_the_named_terminal_report(self):
        self.assertEqual((CONSENT, "4017"), interrupted(TRANSCRIPT))

    def test_rejects_ordinary_failure_wrong_case_or_duplicate_owner_marker(self):
        for changed in (
            TRANSCRIPT.replace("Process crashed.", "Test failed."),
            TRANSCRIPT.replace(OPEN[1], "unrelatedMethod"),
            TRANSCRIPT.replace("INSTRUMENTATION_STATUS_CODE: 67", "INSTRUMENTATION_STATUS_CODE: 0"),
            TRANSCRIPT.replace("4017\n", "4017\nINSTRUMENTATION_STATUS: ocrTextDeathPid=4018\n"),
        ):
            with self.subTest(output=changed), self.assertRaises(RuntimeError):
                interrupted(changed)

    def test_closed_file_death_cannot_reuse_the_chooser_marker(self):
        digest = "a" * 64
        write = (TRANSCRIPT.replace(OPEN[0], WRITE[0]).replace(OPEN[1], WRITE[1])
                 .replace("INSTRUMENTATION_STATUS_CODE: 67", (
                     f"INSTRUMENTATION_STATUS: ocrTextClosedSha={digest}\n"
                     "INSTRUMENTATION_STATUS: ocrTextClosedBytes=36\n"
                     "INSTRUMENTATION_STATUS_CODE: 69"
                 )))
        self.assertEqual((CONSENT, "4017", "36", digest), interrupted(write, controlled_file=True))
        for invalid in (TRANSCRIPT, write.replace("36\n", "0\n"), write.replace(digest, "x" * 64)):
            with self.subTest(output=invalid), self.assertRaises(RuntimeError):
                interrupted(invalid, controlled_file=True)

    def test_audit_and_mutating_modes_fail_before_allocator_setup(self):
        args = ["runner", "agent-pristinepage-ocr-000000111111", "pristinepage_2026-01-01_00-00-00",
                "a" * 64, "b" * 64, "--audit-downloads", "000000222222"]
        for mode in (["--processing-details"], ["--controlled-file", "000000333333"],
                     ["--resume", "picker-adapter-ocr-text-chooser-death-000000444444"]):
            with self.subTest(mode=mode), patch.object(sys, "argv", args + mode), \
                    patch("run_local_ocr_text_chooser_death.load_allocator") as allocator, \
                    self.assertRaisesRegex(RuntimeError, "read-only destination audit"):
                main()
            allocator.assert_not_called()

    def test_audit_empty_downloads_checks_once_and_returns_before_test_package_access(self):
        args = ["runner", "agent-pristinepage-ocr-000000111111", "pristinepage_2026-01-01_00-00-00",
                "a" * 64, "b" * 64, "--audit-downloads", "000000222222"]
        # Replace only the host/device boundary. The real dispatcher must stop before even reading the test package.
        run = Mock(serial="verified-test-serial")
        run.installed_hash.return_value = APP_SHA
        run.command.return_value = ""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / ".worktrees" / "ocr-current-validation"
            (root / "validation/raw/storage").mkdir(parents=True)
            module = "run_local_ocr_text_chooser_death"
            with contextlib.ExitStack() as stack:
                for target, stub in (
                    (f"{module}.Path.cwd", dict(return_value=root)),
                    (f"{module}.exact_apk", dict(return_value={"mocked": True})),
                    (f"{module}.load_allocator", dict(return_value={"ADB": "scoped-adb"})),
                    (f"{module}.verify_target", {}),
                    (f"{module}.run_type", dict(return_value=lambda *_: run)),
                ):
                    stack.enter_context(patch(target, **stub))
                stack.enter_context(patch.object(sys, "argv", args))
                output = stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
                main()
            self.assertTrue(output.getvalue().rstrip().endswith(json.dumps({"present": False})))
        self.assertEqual([call(APP)], run.installed_hash.call_args_list)
        run.command.assert_called_once_with(["scoped-adb", "-s", "verified-test-serial", "shell", "ls", "-A",
                                             "/sdcard/Download/PristinePage-Export-000000222222"])
        run.save.assert_called_once_with(run.path.return_value, {"present": False}, durable=True)
        run.close_owned.assert_called_once_with()
