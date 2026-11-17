"""Forty-one affected Application/persistence/display cases, including scoped Compose editor/retained-host cases, on the exact P04 target. Reuse existing ownership/artifact/shutdown guards; no media, grants, settings, reset or deletion."""

from run_editor_persistence import EditorPersistenceRun
from run_picker_adapter import ADB


class EditorDisplayRun(EditorPersistenceRun):
    classes = EditorPersistenceRun.classes + ("app.pristinepage.OriginalPreviewBitmapIntegrationTest", "app.pristinepage.RenderedPreviewBitmapIntegrationTest", "app.pristinepage.EditorSessionIntegrationTest", "app.pristinepage.EditorScreenTest", "app.pristinepage.EditorHostTest")
    case_count = 41
    missing_marker = "Retained editor host is absent"

    def command(self, args, timeout=15, allowed=(0,)):
        # The observed three-case Compose batch took170.514seconds. The coherent41-case editor batch includes those Activity consumers plus background/recreation, so the inherited180second whole-instrumentation deadline has insufficient headroom. Only this exact owned-target test command gets360seconds; readiness/install/step/quit bounds remain unchanged.
        if hasattr(self, "serial") and list(map(str, args[:7])) == [str(ADB), "-s", self.serial, "shell", "am", "instrument", "-w"]:
            timeout = 360
        return super().command(args, timeout, allowed)
