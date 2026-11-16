"""Own-app Activity/Compose lifecycle checks plus prior bounded consumers on P04. No external picker, grant, system setting or unrelated data operation is performed."""

from run_library_lifecycle import LibraryLifecycleRun


class LibraryUiRun(LibraryLifecycleRun):
    classes = LibraryLifecycleRun.classes + ("app.pristinepage.LibraryActivityIntegrationTest",)
    case_count = 37
    missing_marker = "Visible lifecycle library is absent"
