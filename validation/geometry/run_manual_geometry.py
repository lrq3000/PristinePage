"""Nine affected packaged CPU geometry/appearance cases; reuse exact P04 paired-install/ownership/shutdown guards. No UI, grants, media, settings, app reset or deletion."""

from run_application_composition import ApplicationCompositionRun


class ManualGeometryRun(ApplicationCompositionRun):
    classes = ("app.pristinepage.PerspectiveGeometryIntegrationTest", "app.pristinepage.AppearancePipelineIntegrationTest", "app.pristinepage.ManualGeometryIntegrationTest")
    case_count = 9
    missing_marker = "Admitted direct manual raster path is unavailable"
