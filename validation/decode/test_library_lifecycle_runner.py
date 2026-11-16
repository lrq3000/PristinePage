"""Exact consuming native inventory; no emulator allocation or subprocess work."""

from collections import Counter
import unittest

from run_document_catalog import DocumentCatalogRun


class LibraryLifecycleRunnerTest(unittest.TestCase):
    def test_lifecycle_inventory_retains_prior_cases_and_the_eight_named_consumers(self):
        try:
            from run_library_lifecycle import LibraryLifecycleRun
        except ModuleNotFoundError:
            self.fail("Library lifecycle runner is absent")
        runner = object.__new__(LibraryLifecycleRun)
        prior = object.__new__(DocumentCatalogRun)
        added = Counter(("app.pristinepage.ImportLibraryIntegrationTest", name) for name in (
            "lostUiStateCanRediscoverAndAbandonOnlyItsOwnDurableInsertionAfterReopen",
            "nativeDiscoveryRefusesAmbiguousOrOversizedBatchIdentitiesWithoutMaterializingPrivateReferences",
            "realApplicationAdapterPublishesOrderedMixedSessionOutcomesAndOwnedSavedPages",
            "realCancellationAcknowledgesTheFenceButWaitsForBlockedSourceIoAndKeepsTheFirstPage",
            "readFailureAndDuplicateResultCannotDetachTheActualBlockedConsumerFromCancellation",
            "missingAndWrongKindSavedRequestsRecoverThroughRealMetadataWithoutProviderIo",
        ))
        added.update(("app.pristinepage.LibraryPickerBindingTest", name) for name in (
            "parceledQueuedRawResultIsConsumedSynchronouslyDuringRestoredRegistrationWithoutRelaunch",
            "launchIsClaimedOnlyOnceAndARealLaunchFailureRemainsBoundToItsExactRequest",
        ))
        self.assertEqual(prior.expected_tests() + added, runner.expected_tests())
        self.assertEqual(36, runner.case_count)


if __name__ == "__main__":
    unittest.main()
