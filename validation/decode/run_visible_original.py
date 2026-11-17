"""Own-app visible-original Compose consumers plus prior raster/lifecycle checks. Uses explicit private synthetic graph/session in MainActivity content; no external picker or native grant experiment."""

from run_original_preview import OriginalPreviewRun


class VisibleOriginalRun(OriginalPreviewRun):
    classes = OriginalPreviewRun.classes + ("app.pristinepage.OriginalPreviewScreenTest",)
    case_count = 47
    missing_marker = "Visible original preview is absent"
