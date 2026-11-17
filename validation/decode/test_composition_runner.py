"""Memory-only checks: no allocator, subprocess, browser or Android access."""

from collections import Counter
import unittest

from run_application_composition import ApplicationCompositionRun


class CompositionRunnerTest(unittest.TestCase):
    def test_one_case_success_uses_junits_singular_summary_without_accepting_the_wrong_count(self):
        check = getattr(ApplicationCompositionRun, "completed_summary", None)
        self.assertTrue(callable(check), "Single-case JUnit success-summary boundary is absent")
        self.assertTrue(check("Time: 60,776\n\nOK (1 test)\n", 1))
        self.assertTrue(check("OK (41 tests)", 41))
        self.assertFalse(check("OK (1 test)", 41))
        self.assertFalse(check("FAILURES!!! Tests run: 1, Failures: 1", 1))

    def test_inventory_accepts_all_ten_consumer_cases_and_eight_existing_picker_cases(self):
        runner = object.__new__(ApplicationCompositionRun)
        expected = Counter(("app.pristinepage.ApplicationCompositionTest", name) for name in (
            "orderedMixedResultsSurviveReopenWithoutReopeningAProvider",
            "canceledRemainderKeepsCommittedReceiptThroughTheRealControllerAndReopen",
            "retainedDecodedPixelsConsumeTheSameAllowanceAsSubsequentIngressAndDecode",
            "recoveryReportsUnsealedWorkWithoutCallingItsTemporaryProviderAgain",
            "concurrentConsumersReceiveOneInitializedStoreAndBudget",
            "installedApplicationOwnsTheServicesRatherThanEachActivity",
            "failedRecoveryDoesNotCacheReadinessAndTheSameRootCanRetry",
            "cancellationDuringIoDeliveryLeavesNoCachedCanceledGraphAndAllowsRetry",
            "applicationRendererRetainsTheSharedAllowanceAndReadsOnlyCommittedSources",
            "concurrentApplicationConsumersShareOneRendererNotIndependentProcessingQueues",
        ))
        expected.update(("app.pristinepage.PhotoPickerContractTest", name) for name in (
            "imageIntentUsesInstalledAndroidXAvailabilityAndOpenableFallbackWithoutBroadPermissions",
            "returnedClipDataRetainsOrdinalDuplicatesAndOnlyOfferedReadGrantFlags",
            "singleDataUriIsAcceptedWithoutInventingAPersistableReadGrant",
            "canceledResultIsDistinctFromMalformedSuccess",
            "fallbackOverSelectionCannotSilentlyImportFirstHundredItems",
            "textOnlyClipAndNonContentReferencesCannotBecomePrivateFileImports",
            "clipDataIsAuthoritativeWhenIntentAlsoHasAnUnrelatedDataUri",
            "actualAndroidUriControllerAdapterRetainsOrderAndChildBindings",
        ))
        self.assertTrue(callable(getattr(runner, "expected_tests", None)), "Named-case inventory boundary is absent")
        self.assertEqual(expected, runner.expected_tests())
        self.assertEqual(len(expected), runner.case_count, "The default runner must accept its complete named inventory before starting Android")


if __name__ == "__main__":
    unittest.main(verbosity=2)
