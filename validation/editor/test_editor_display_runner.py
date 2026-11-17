"""Memory-only command delegation: no allocator, filesystem write, subprocess or device activity."""

from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
for directory in ("decode", "pipeline", "editor"):
    sys.path.insert(0, str(ROOT / "validation" / directory))

from run_editor_display import EditorDisplayRun, ADB
from run_editor_persistence import EditorPersistenceRun


class EditorDisplayDeadlineTest(unittest.TestCase):
    def test_boot_inventory_delegates_without_an_as_yet_unobserved_serial(self):
        run = object.__new__(EditorDisplayRun)
        args = ["ldconsole.exe", "list2"]
        with patch.object(EditorPersistenceRun, "command", return_value="inventory") as parent:
            self.assertEqual("inventory", run.command(args))
            parent.assert_called_once_with(args, 15, (0,))

    def test_only_the_exact_owned_instrumentation_receives_the_larger_deadline(self):
        run = object.__new__(EditorDisplayRun)
        run.serial = "emulator-observed"
        for serial, action, expected in [(run.serial, "instrument", 360), ("not-owned", "instrument", 180), (run.serial, "start", 180)]:
            args = [ADB, "-s", serial, "shell", "am", action, "-w"]
            with patch.object(EditorPersistenceRun, "command", return_value="delegated") as parent:
                self.assertEqual("delegated", run.command(args, 180))
                parent.assert_called_once_with(args, expected, (0,))


if __name__ == "__main__":
    unittest.main()
