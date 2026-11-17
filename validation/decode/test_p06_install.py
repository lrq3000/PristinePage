"""Memory-only paired-install guards for P-06; no ADB, claims, files or emulator is changed."""

import unittest

from run_picker_adapter import PickerAdapterRun


class P06InstallTest(unittest.TestCase):
    def installer(self, run):
        method = getattr(run, "install_pair", None)
        self.assertTrue(callable(method), "Paired install cannot yet be reused without replaying unrelated native tests")
        return method

    def test_only_changed_owned_artifacts_are_installed_and_proven(self):
        run = MemoryRun()
        self.installer(run)()
        self.assertEqual(run.installed, {"app.pristinepage.debug": "new-app", "app.pristinepage.debug.test": "new-test"})
        self.assertEqual(run.install_targets, ["app.apk", "test.apk"])
        self.assertEqual(run.saved["install.json"]["app"]["priorSha256"], "old-app")

    def test_already_exact_pair_is_verified_without_reinstall(self):
        run = MemoryRun()
        run.installed = {"app.pristinepage.debug": "new-app", "app.pristinepage.debug.test": "new-test"}
        self.installer(run)()
        self.assertEqual(run.install_targets, [])
        self.assertEqual(run.saved["install.json"]["test"]["installedSha256"], "new-test")

    def test_unknown_existing_package_hash_refuses_before_any_install(self):
        run = MemoryRun()
        run.installed["app.pristinepage.debug"] = "unknown-artifact"
        install = self.installer(run)
        with self.assertRaises(AssertionError):
            install()
        self.assertEqual(run.install_targets, [])

    def test_replaced_process_identity_refuses_before_any_install(self):
        run = MemoryRun()
        run.process_start = 999
        install = self.installer(run)
        with self.assertRaises(AssertionError):
            install()
        self.assertEqual(run.install_targets, [])

    def test_failed_post_install_identity_never_reports_success(self):
        run = MemoryRun()
        run.corrupt_install = True
        install = self.installer(run)
        with self.assertRaises(AssertionError):
            install()
        self.assertNotIn("install.json", run.saved)

    def test_input_verification_refuses_changed_bytes_before_claim(self):
        run = MemoryRun()
        method = getattr(run, "verify_inputs", None)
        self.assertTrue(callable(method), "Artifact/source checks are not available before the P-06 claim")
        run.input = {"sources": {"validation/decode/test_p06_install.py": "0" * 64}, "artifacts": {}}
        with self.assertRaises(AssertionError):
            method()
        self.assertEqual(run.install_targets, [])


class MemorySink:
    def __init__(self, owner, name):
        self.owner, self.name = owner, name

    def write_text(self, value, **kwargs):
        self.owner.saved[self.name] = value


class MemoryRun(PickerAdapterRun):
    def __init__(self):
        self.serial = "synthetic-target"
        self.process_start = 100
        self.saved = {}
        self.install_targets = []
        self.corrupt_install = False
        self.previous = {"app": "old-app", "test": "old-test"}
        self.installed = {"app.pristinepage.debug": "old-app", "app.pristinepage.debug.test": "old-test"}
        self.input = {"artifacts": {role: {"retained": role + ".apk", "sha256": "new-" + role, "metadata": {"applicationId": "app.pristinepage.debug" + (".test" if role == "test" else ""), "elements": [{"versionCode": index + 1}]}} for index, role in enumerate(("app", "test"))}}

    def owned(self):
        return {"runtime_processes": [[10, 100], [20, 100]]}

    def inventory(self):
        return [["2", "synthetic", "0", "0", "1", "10", "20"]]

    def exact(self, rows):
        return rows[0]

    def process(self, pid):
        return {"start": self.process_start, "running": True}

    def installed_hash(self, package):
        return self.installed[package]

    def path(self, name):
        return MemorySink(self, name)

    def save(self, sink, value):
        self.saved[sink.name] = value

    def command(self, arguments, timeout=15, allowed=(0,)):
        args = [str(item) for item in arguments]
        if "install" in args:
            target = args[-1]
            role = target[:-4]
            self.install_targets.append(target)
            package = self.input["artifacts"][role]["metadata"]["applicationId"]
            self.installed[package] = "corrupt" if self.corrupt_install else "new-" + role
            return "Success"
        if "dumpsys" in args:
            return "versionCode=" + ("2" if args[-1].endswith(".test") else "1")
        if "instrumentation" in args:
            return "instrumentation:app.pristinepage.debug.test/androidx.test.runner.AndroidJUnitRunner (target=app.pristinepage.debug)"
        raise AssertionError("Unexpected command in bounded pair verification: " + repr(args))


if __name__ == "__main__":
    unittest.main()
