"""T04 request-result boundary; all allocation, artifact, paired install, outcomes and shutdown guards are inherited. External picker UI is not opened by these tests."""

from run_application_composition import ApplicationCompositionRun


class PhotoRoutingRun(ApplicationCompositionRun):
    classes = ApplicationCompositionRun.classes + ("app.pristinepage.PhotoResultRoutingTest",)
    case_count = 23
    missing_marker = "Request-scoped photo-result routing is absent"
