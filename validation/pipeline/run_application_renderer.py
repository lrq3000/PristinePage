"""Twenty-six affected Application composition and CPU render cases using the existing exact P04 claim/install/shutdown guards. No UI, grants, media, settings, reset or deletion."""

from run_application_composition import ApplicationCompositionRun


class ApplicationRendererRun(ApplicationCompositionRun):
    classes = ("app.pristinepage.ApplicationCompositionTest", "app.pristinepage.PerspectiveGeometryIntegrationTest", "app.pristinepage.AppearancePipelineIntegrationTest", "app.pristinepage.ManualGeometryIntegrationTest", "app.pristinepage.RenderCoordinatorIntegrationTest")
    case_count = 26
    missing_marker = "Application-scoped render coordinator is absent"
