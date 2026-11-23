import unittest

from run_local_ocr_text_chooser_death import OPEN, interrupted


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
