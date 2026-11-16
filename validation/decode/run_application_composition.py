"""T04 app composition cases on the exact P04 target. Reuse the existing allocator/shutdown guards; install only verified paired APKs and retain every output. No UI, grants, host settings or deletion."""

from collections import Counter
import json
import re

from run_picker_adapter import ADB, ROOT, PickerAdapterRun


class ApplicationCompositionRun(PickerAdapterRun):
    classes = ("app.pristinepage.PhotoPickerContractTest", "app.pristinepage.ApplicationCompositionTest")
    case_count = 16
    missing_marker = "Application-scoped composition is absent"

    def expected_tests(self):
        return Counter((cls, name) for cls in self.classes for name in re.findall(r"@Test\s+fun\s+(\w+)", (ROOT / "app/src/androidTest/kotlin" / (cls.replace(".", "/") + ".kt")).read_text()))

    def install_and_test(self):
        record = self.owned()
        row = self.exact(self.inventory())
        assert [int(row[5]), int(row[6])] == [identity[0] for identity in record["runtime_processes"]]
        for pid, start in record["runtime_processes"]:
            state = self.process(pid)
            assert state and state["start"] == start and state["running"]
        installs = {}
        for role in ("app", "test"):
            artifact = self.input["artifacts"][role]
            package = artifact["metadata"]["applicationId"]
            before = self.installed_hash(package)
            assert before in (self.previous[role], artifact["sha256"]), (package, before)
            if before != artifact["sha256"]:
                assert "Success" in self.command([ADB, "-s", self.serial, "install", "-r", artifact["retained"]], 120)
            assert self.installed_hash(package) == artifact["sha256"]
            dump = self.command([ADB, "-s", self.serial, "shell", "dumpsys", "package", package])
            self.path(role + "-package.txt").write_text(dump, encoding="utf-8")
            assert re.search(r"versionCode=" + str(artifact["metadata"]["elements"][0]["versionCode"]) + r"\b", dump)
            installs[role] = {"priorSha256": before, "installedSha256": artifact["sha256"], "metadata": artifact["metadata"]}
        registry = self.command([ADB, "-s", self.serial, "shell", "pm", "list", "instrumentation", "app.pristinepage.debug"])
        self.path("registry.txt").write_text(registry, encoding="utf-8")
        assert "instrumentation:app.pristinepage.debug.test/androidx.test.runner.AndroidJUnitRunner (target=app.pristinepage.debug)" in registry
        self.save(self.path("install.json"), installs)
        expected = self.expected_tests()
        assert len(expected) == self.case_count
        output = self.command([ADB, "-s", self.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(self.classes), "app.pristinepage.debug.test/androidx.test.runner.AndroidJUnitRunner"], 180)
        self.path("instrumentation.txt").write_text(output, encoding="utf-8")
        outcomes = {code: Counter() for code in ("1", "0", "-2", "-1")}
        missing_assertions = 0
        for block, code in re.findall(r"(.*?)(?:^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$)", output, re.M | re.S):
            cls = re.search(r"^INSTRUMENTATION_STATUS: class=(.+)$", block, re.M)
            name = re.search(r"^INSTRUMENTATION_STATUS: test=(.+)$", block, re.M)
            assert cls and name and code in outcomes
            outcomes[code][(cls.group(1).strip(), name.group(1).strip())] += 1
            if code == "-2" and self.missing_marker in block:
                missing_assertions += 1
        assert outcomes["1"] == expected and outcomes["0"] + outcomes["-2"] + outcomes["-1"] == expected
        assert not outcomes["-1"] and sum(outcomes["-2"].values()) == self.missing and missing_assertions == self.missing
        if not self.missing:
            assert f"OK ({len(expected)} tests)" in output
        self.save(self.path("outcomes.json"), {"expectedMissingAssertions": self.missing, "outcomes": {code: [{"class": cls, "test": name, "count": count} for (cls, name), count in items.items()] for code, items in outcomes.items()}})
        print(json.dumps({"started": len(expected), "passed": sum(outcomes["0"].values()), "intendedMissing": self.missing}), flush=True)
