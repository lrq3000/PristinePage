"""Six affected packaged CPU geometry/appearance cases only; reuse P04 exact-pair/ownership/shutdown guards without UI, grants, media, app-data reset, settings or deletion."""

from run_application_composition import ApplicationCompositionRun


class OrderedAppearanceRun(ApplicationCompositionRun):
    classes = ("app.pristinepage.PerspectiveGeometryIntegrationTest", "app.pristinepage.AppearancePipelineIntegrationTest")
    case_count = 6
    missing_marker = "Budgeted ordered appearance consumer is absent"
