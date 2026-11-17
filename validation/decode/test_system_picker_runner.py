"""Real system-picker consumers retain the prior native suite; no UI or emulator allocation."""

from collections import Counter
import unittest

from run_visible_original import VisibleOriginalRun


class SystemPickerRunnerTest(unittest.TestCase):
    def test_system_picker_inventory_preserves_prior_cases_and_names_the_real_boundary(self):
        try:
            from run_system_picker import SystemPickerRun
        except ModuleNotFoundError:
            self.fail("System picker runner is absent")
        added = Counter(("app.pristinepage.SystemPickerJourneyTest", name) for name in (
            "testDocumentProviderIsRestrictedBeforeAnyUserSelection",
            "actualSystemPickerCancellationRetainsTheOriginalDocumentAndClosesItsReservation",
            "fixtureProviderLoadsInItsOwnPackageWithoutTargetApplicationDependencies",
        ))
        self.assertEqual(object.__new__(VisibleOriginalRun).expected_tests() + added, object.__new__(SystemPickerRun).expected_tests())
        self.assertEqual(50, SystemPickerRun.case_count)

    def test_focused_system_picker_run_selects_only_the_three_affected_cases(self):
        try:
            from run_system_picker import SystemPickerFocusedRun
        except ImportError:
            self.fail("Focused system picker runner is absent")
        self.assertEqual(("app.pristinepage.SystemPickerJourneyTest",), SystemPickerFocusedRun.classes)
        self.assertEqual(3, SystemPickerFocusedRun.case_count)
        self.assertEqual(3, len(object.__new__(SystemPickerFocusedRun).expected_tests()))


if __name__ == "__main__":
    unittest.main()
