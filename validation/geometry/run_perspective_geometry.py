"""Three packaged CPU geometry cases only. Reuses the exact P04 paired install, allocator, source/signer checks and corrected original-process shutdown guards; no UI, picker, grants, source files, settings, reset or deletion."""

from run_application_composition import ApplicationCompositionRun


class PerspectiveGeometryRun(ApplicationCompositionRun):
    classes = ("app.pristinepage.PerspectiveGeometryIntegrationTest",)
    case_count = 3
    missing_marker = "Validated perspective raster path is unavailable"
