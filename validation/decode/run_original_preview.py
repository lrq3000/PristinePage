"""Verified original-preview raster consumers plus prior lifecycle/UI cases. Inherit exact P04 allocation, paired artifact, named outcome and shutdown guards; no provider grant, external picker or new display is exercised."""

from run_library_ui import LibraryUiRun


class OriginalPreviewRun(LibraryUiRun):
    classes = LibraryUiRun.classes + ("app.pristinepage.OriginalPreviewIntegrationTest", "app.pristinepage.OriginalPreviewBitmapIntegrationTest")
    case_count = 41
    missing_marker = "Budgeted original bitmap bridge is absent"
