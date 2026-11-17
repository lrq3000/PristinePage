"""Exact original-preview consumers in the cumulative native suite; no runtime allocation."""

from collections import Counter
import unittest

from run_library_ui import LibraryUiRun


class OriginalPreviewRunnerTest(unittest.TestCase):
    def test_original_preview_inventory_preserves_prior_cases_and_all_four_real_consumers(self):
        try:
            from run_original_preview import OriginalPreviewRun
        except ModuleNotFoundError:
            self.fail("Original preview runner is absent")
        added = Counter(("app.pristinepage.OriginalPreviewIntegrationTest", name) for name in (
            "importedOriginalProducesVerifiedAspectPreservingPreviewWithoutProviderReopenOrFileWrites",
            "changedPrivateOriginalCannotBecomeAPreviewOrBeRepairedFromTheProvider",
        ))
        added.update(("app.pristinepage.OriginalPreviewBitmapIntegrationTest", name) for name in (
            "bitmapKeepsExactOpaqueSrgbSamplesAndClosingItsOwnerNeverRecyclesAnIssuedDrawReference",
            "competingBudgetOwnerRefusesDisplayWorkWithoutLosingItsReservationOrOriginal",
        ))
        self.assertEqual(object.__new__(LibraryUiRun).expected_tests() + added, object.__new__(OriginalPreviewRun).expected_tests())
        self.assertEqual(41, OriginalPreviewRun.case_count)


if __name__ == "__main__":
    unittest.main()
