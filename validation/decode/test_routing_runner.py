"""Pure named-inventory check; no emulator allocation/subprocess or filesystem mutation."""

from collections import Counter
import unittest

from run_photo_routing import PhotoRoutingRun
from run_application_composition import ApplicationCompositionRun


class RoutingRunnerTest(unittest.TestCase):
    def test_adds_exactly_the_seven_routing_cases_to_the_retained_composition_inventory(self):
        runner = object.__new__(PhotoRoutingRun)
        prior = object.__new__(ApplicationCompositionRun)
        added = Counter(("app.pristinepage.PhotoResultRoutingTest", name) for name in (
            "earlierCallbackKeepsItsDocumentAfterANewerRequestIsRegistered",
            "idOnlySavedRequestAndRegistryRestoreKeepTheOriginalRoomBatch",
            "absentOrMalformedSavedIdentityNeverInventsANewRequest",
            "pickerCancellationClosesOnlyItsUnselectedBatchWithoutAPageOrProviderRead",
            "expiredPickerCancellationClosesItsReservationAndAllowsANewBatch",
            "rejectedOverSelectionClosesItsReservationWithoutImportingAPrefix",
            "restoredCanceledRequestCannotAttachALateSelectionToANewerBatch",
        ))
        self.assertEqual(prior.expected_tests() + added, runner.expected_tests())
        self.assertEqual(23, runner.case_count)


if __name__ == "__main__":
    unittest.main(verbosity=2)
