"""Named native inventory; no emulator allocation or subprocess work."""

from collections import Counter
import unittest

from run_photo_routing import PhotoRoutingRun


class CatalogRunnerTest(unittest.TestCase):
    def test_catalog_adds_five_exact_consuming_cases_without_dropping_prior_routing(self):
        try:
            from run_document_catalog import DocumentCatalogRun
        except ModuleNotFoundError:
            self.fail("Catalog runner is absent")
        runner = object.__new__(DocumentCatalogRun)
        prior = object.__new__(PhotoRoutingRun)
        added = Counter(("app.pristinepage.DocumentCatalogIntegrationTest", name) for name in (
            "committedMixedSelectionReopensWithExactOrderedPageMetadataWithoutProviderReads",
            "pendingEmptyBatchAndItsAbandonmentRemainMetadataNotSavedPageSuccess",
            "nativeRawTitleAndOrdinalBoundsRefuseBeforeTypedProjectionWithoutChangingSources",
            "missingInactiveAndMalformedIdentityRemainDistinctWithoutFalseEmptyLibrary",
            "nativeKeysetPlanAndAcceptedTitleCommandsStayCompatibleWithTheReader",
        ))
        self.assertEqual(prior.expected_tests() + added, runner.expected_tests())
        self.assertEqual(28, runner.case_count)


if __name__ == "__main__":
    unittest.main()
