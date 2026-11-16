"""T04 image-library cancellation cases using the reviewed P04 lifecycle runner; no main APK update or UI action. Retains every synthetic artifact/output and shares the exact existing allocator. Optimized Python is rejected by the imported base before operational initialization."""

from collections import Counter
import json
from pathlib import Path
import re

from run_picker_adapter import ADB, ROOT, PickerAdapterRun


class ImportCancellationRun(PickerAdapterRun):
    def install_and_test(self):
        record = self.owned()
        row = self.exact(self.inventory())
        assert [int(row[5]), int(row[6])] == [identity[0] for identity in record["runtime_processes"]]
        for pid, start in record["runtime_processes"]:
            process = self.process(pid)
            assert process and process["start"] == start and process["running"]
        artifact = self.input["artifacts"]["test"]
        package = "app.pristinepage.debug.test"
        before = self.installed_hash(package)
        assert before in (self.previous["test"], artifact["sha256"])
        if before != artifact["sha256"]:
            assert "Success" in self.command([ADB, "-s", self.serial, "install", "-r", artifact["retained"]], 120)
        assert self.installed_hash(package) == artifact["sha256"]
        dump = self.command([ADB, "-s", self.serial, "shell", "dumpsys", "package", package])
        self.path("test-package.txt").write_text(dump, encoding="utf-8")
        assert re.search(r"versionCode=" + str(artifact["metadata"]["elements"][0]["versionCode"]) + r"\b", dump)
        registry = self.command([ADB, "-s", self.serial, "shell", "pm", "list", "instrumentation", package])
        self.path("registry.txt").write_text(registry, encoding="utf-8")
        assert f"instrumentation:{package}/androidx.test.runner.AndroidJUnitRunner (target={package})" in registry
        self.save(self.path("install.json"), {"priorSha256": before, "installedSha256": artifact["sha256"], "metadata": artifact["metadata"], "mainPackageChanged": False})
        classes = ["app.pristinepage.image." + name for name in ("OrientationDecodeTest", "RoomNormalizedIngestTest", "RoomCommittedSourceDecodeTest", "RoomImportSelectionTest", "RoomImportCancellationTest")]
        expected = Counter((cls, name) for cls in classes for name in re.findall(r"@Test\s+fun\s+(\w+)", (ROOT / "image-android/src/androidTest/kotlin" / (cls.replace(".", "/") + ".kt")).read_text()))
        assert len(expected) == 29
        output = self.command([ADB, "-s", self.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(classes), package + "/androidx.test.runner.AndroidJUnitRunner"], 180)
        self.path("instrumentation.txt").write_text(output, encoding="utf-8")
        outcomes = {code: Counter() for code in ("1", "0", "-2", "-1")}
        for block, code in re.findall(r"(.*?)(?:^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$)", output, re.M | re.S):
            cls = re.search(r"^INSTRUMENTATION_STATUS: class=(.+)$", block, re.M)
            test = re.search(r"^INSTRUMENTATION_STATUS: test=(.+)$", block, re.M)
            assert cls and test and code in outcomes
            outcomes[code][(cls.group(1).strip(), test.group(1).strip())] += 1
        passed = outcomes["1"] == outcomes["0"] == expected and not outcomes["-2"] and not outcomes["-1"] and "OK (29 tests)" in output
        self.save(self.path("outcomes.json"), {"passed": passed, "outcomes": {code: [{"class": cls, "test": test, "count": count} for (cls, test), count in values.items()] for code, values in outcomes.items()}})
        print(json.dumps({"started": sum(outcomes["1"].values()), "passed": sum(outcomes["0"].values()), "failures": sum(outcomes["-2"].values()), "errors": sum(outcomes["-1"].values())}), flush=True)
        assert passed, "Inspect retained native failures before any source change"


if __name__ == "__main__":
    ImportCancellationRun("picker-adapter-import-cancel", {"test": "e86d797559980463810834516b5e30e382c3eb1c9b7fea82a010ca7200fbc4d1"}, 0).run()
