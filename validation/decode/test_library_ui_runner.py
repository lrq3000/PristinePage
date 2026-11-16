"""Exact own-Activity native inventory; no allocation, UI or subprocess execution."""

from collections import Counter
import unittest

from run_library_lifecycle import LibraryLifecycleRun


class LibraryUiRunnerTest(unittest.TestCase):
    def test_ui_inventory_retains_prior_cases_and_the_exact_activity_recreation_case(self):
        try:
            from run_library_ui import LibraryUiRun
        except ModuleNotFoundError:
            self.fail("Library UI runner is absent")
        runner = object.__new__(LibraryUiRun)
        prior = object.__new__(LibraryLifecycleRun)
        added = Counter({("app.pristinepage.LibraryActivityIntegrationTest", "realLibraryActivityCreatesAnEmptyDocumentAndRetainsItsSessionAcrossRecreation"): 1})
        self.assertEqual(prior.expected_tests() + added, runner.expected_tests())
        self.assertEqual(37, runner.case_count)


if __name__ == "__main__":
    unittest.main()
