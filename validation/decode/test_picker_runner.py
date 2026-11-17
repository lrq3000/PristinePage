"""Memory-only regressions for the P-04 runner. No process, emulator, filesystem write, shared allocator or SDK operation occurs; only below-business IO boundaries are substituted."""

import json
from pathlib import Path
import unittest

import run_picker_adapter as runner


class MemoryPath:
    def __init__(self, value=None):
        self.value = value

    def read_text(self):
        return json.dumps(self.value)


class MemoryLock:
    def __init__(self):
        self.held = False

    def mkdir(self):
        if self.held:
            raise AssertionError("Lock was stolen")
        self.held = True

    def rmdir(self):
        if not self.held:
            raise AssertionError("Unowned lock released")
        self.held = False


class FailedVerification(runner.PickerAdapterRun):
    def __init__(self):
        # Do not call real initialization: it inspects the worktree and loads an artifact record. Only actual shutdown/ownership/error bookkeeping logic is under test.
        self.generation = "synthetic-generation"
        self.record = MemoryPath({"owner": runner.OWNER, "generation": self.generation, "keep_running": False, "handoff_to": None, "runtime_processes": [[11, 101], [22, 202]], "cleanup_attempt": None, "reservedKiB": 4194304})
        self.slot = MemoryPath()
        self.transcript = MemoryPath()
        self.lock = MemoryLock()
        self.quit_finished = False

    def inventory(self):
        if self.quit_finished:
            raise OSError("synthetic post-quit inventory unavailable")
        return [["2", runner.NAME, "0", "0", "1", "11", "22"]]

    def process(self, pid):
        return {"pid": pid, "start": {11: 101, 22: 202}[pid], "running": True, "wait": 258, "exit": 259}

    def command(self, args, timeout=15, allowed=(0,)):
        if args != [runner.CONSOLE, "quit", "--index", "2"]:
            raise AssertionError("Unexpected side effect in failure regression")
        self.quit_finished = True
        return ""

    def path(self, suffix):
        return MemoryPath()

    def evidence(self, path):
        return {"path": "synthetic-retained-transcript", "bytes": 0, "sha256": "a" * 64}

    def save(self, path, value):
        path.value = json.loads(json.dumps(value))


class PickerRunnerSafetyTest(unittest.TestCase):
    def test_same_pid_with_replaced_start_identity_cannot_be_quit(self):
        run = FailedVerification()
        # Keep the current inventory's numeric IDs identical; only the real process-identity boundary reports a replacement. No emulator or OS process is created or stopped.
        run.process = lambda pid: {"pid": pid, "start": {11: 999, 22: 202}[pid], "running": True, "wait": 258, "exit": 259}
        try:
            run.shutdown()
        except (AssertionError, OSError):
            pass
        self.assertFalse(run.quit_finished, "A replacement process pair reached the native quit command")
        self.assertEqual("shutdown_failed", run.record.value["state"])
        self.assertEqual(runner.OWNER, run.record.value["owner"])
        self.assertEqual([[11, 101], [22, 202]], run.record.value["runtime_processes"])
        self.assertEqual(4194304, run.record.value["reservedKiB"])
        self.assertFalse(run.lock.held)

    def test_finished_failed_cleanup_is_not_advertised_as_a_live_attempt(self):
        run = FailedVerification()
        with self.assertRaisesRegex(OSError, "post-quit inventory"):
            run.shutdown()
        record = run.record.value
        self.assertTrue(run.quit_finished)
        self.assertEqual("failed", record["cleanup_attempt"]["state"])
        self.assertTrue(record["cleanup_attempt"]["finished"])
        self.assertEqual(runner.OWNER, record["owner"])
        self.assertEqual("shutdown_failed", record["state"])
        self.assertFalse(record["shutdown_verified"])
        self.assertEqual([[11, 101], [22, 202]], record["shutdown_processes"])
        self.assertEqual(4194304, record["reservedKiB"])
        self.assertEqual("released-with-unresolved-shutdown", run.slot.value["state"])
        self.assertFalse(run.lock.held)

    def test_optimized_module_execution_refuses_before_operational_code(self):
        source = Path(runner.__file__).read_text(encoding="utf-8")
        namespace = {"__name__": "memory_only_optimization_probe"}
        with self.assertRaisesRegex(RuntimeError, "[Oo]ptimized"):
            # Executing as a non-main module defines code only: no CLI/runner initialization. A proper non-assert guard must still fire when optimize=1 eliminates assertions.
            exec(compile(source, runner.__file__, "exec", optimize=1), namespace)


if __name__ == "__main__":
    unittest.main(verbosity=2)
