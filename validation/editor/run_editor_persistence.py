"""Thirty-three affected Application composition, pipeline and editor persistence cases on the existing exact P04 target. Operation-private synthetic data only; no UI/media/grants/settings/reset/deletion."""

from run_application_renderer import ApplicationRendererRun


class EditorPersistenceRun(ApplicationRendererRun):
    classes = ApplicationRendererRun.classes + ("app.pristinepage.EditorPersistenceIntegrationTest",)
    case_count = 33
    missing_marker = "Application-backed editor persistence is unavailable"
