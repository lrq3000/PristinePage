"""Exact visible-original Compose consumers; inventory only, no UI or runtime work."""

from collections import Counter
import unittest

from run_original_preview import OriginalPreviewRun


class VisibleOriginalRunnerTest(unittest.TestCase):
    def test_visible_original_inventory_preserves_prior_cases_and_all_six_ui_consumers(self):
        try:
            from run_visible_original import VisibleOriginalRun
        except ModuleNotFoundError:
            self.fail("Visible original runner is absent")
        added = Counter(("app.pristinepage.OriginalPreviewScreenTest", name) for name in (
            "visibleOriginalUsesTheVerifiedImageAspectAndReleasesItsOwnerWhenTheContentLeaves",
            "lateUninterruptibleOriginalCannotReplaceANewerPageOrRetainAnUndisplayedOwner",
            "sharedResourceRefusalShowsNoImageAndExplicitRetryUsesTheSamePrivateOriginal",
            "backgroundingReleasesTheDisplayAndResumeReloadsWithoutReopeningTheProvider",
            "admittedTallAndWideOriginalsStayInsideAFiniteViewportWithReachablePageControls",
            "selectedPageRemainsIdentifiedWhileItsOwnPreviewLoadsFailsAndRetries",
        ))
        self.assertEqual(object.__new__(OriginalPreviewRun).expected_tests() + added, object.__new__(VisibleOriginalRun).expected_tests())
        self.assertEqual(47, VisibleOriginalRun.case_count)


if __name__ == "__main__":
    unittest.main()
