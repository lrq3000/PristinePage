"""Default-graph MainActivity/system-picker consumers plus prior preview/lifecycle checks. Inherit exact P04 allocation, paired install and shutdown; UI input is guarded by the app-intent-resolved external package. No physical phone, settings changes or external sharing."""

from run_visible_original import VisibleOriginalRun


class SystemPickerRun(VisibleOriginalRun):
    classes = VisibleOriginalRun.classes + ("app.pristinepage.SystemPickerJourneyTest",)
    case_count = 50
    missing_marker = "System picker fixture provider is absent"


class SystemPickerFocusedRun(SystemPickerRun):
    """Only affected external-picker/provider cases; retain the same operational guards."""

    classes = ("app.pristinepage.SystemPickerJourneyTest",)
    case_count = 3
