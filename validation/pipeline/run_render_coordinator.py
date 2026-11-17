"""Sixteen affected packaged CPU/source-composition cases on the exact P04 target. Synthetic operation-private stores only; no UI, grants, media, settings, reset or deletion."""

from run_application_composition import ApplicationCompositionRun


class RenderCoordinatorRun(ApplicationCompositionRun):
    classes = ("app.pristinepage.PerspectiveGeometryIntegrationTest", "app.pristinepage.AppearancePipelineIntegrationTest", "app.pristinepage.ManualGeometryIntegrationTest", "app.pristinepage.RenderCoordinatorIntegrationTest")
    case_count = 16
    missing_marker = "Default FAIL render coordinator is absent"
