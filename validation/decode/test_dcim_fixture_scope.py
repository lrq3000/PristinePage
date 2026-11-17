"""Memory-only guest-scope gate tests; no filesystem, allocator, subprocess or emulator operations."""

import importlib
import unittest


class DcimScopeTest(unittest.TestCase):
    def scope(self, physical):
        try:
            kind = importlib.import_module("prepare_dcim_fixtures").DcimFixtureRun
        except ModuleNotFoundError:
            raise AssertionError("Non-aliased P06 fixture scope gate is absent")
        runner = object.__new__(kind)
        runner.serial = "emulator-observed"
        runner.generation = "00000000-0000-0000-0000-000000000001"
        def command(args, timeout=15, allowed=(0,)):
            self.assertEqual(["-s", "emulator-observed", "shell"], list(map(str,args[1:4])))
            if "sh" in args:
                return physical + "\n"
            self.assertEqual(["test", "-L"], args[4:6])
            self.assertEqual((1,), allowed)
            return ""
        runner.command = command
        return runner

    def test_guest_parent_produces_only_the_fresh_generation_child_without_writing(self):
        runner = self.scope("/storage/emulated/0/DCIM")
        self.assertEqual("/storage/emulated/0/DCIM/PristinePage-QA-00000000-0000-0000-0000-000000000001",runner.prove_parent())

    def test_shared_alias_is_refused_before_any_fixture_write(self):
        runner = self.scope("/mnt/shared/DCIM")
        with self.assertRaises(AssertionError):
            runner.prove_parent()


if __name__ == "__main__":
    unittest.main()
