"""Read the real unchanged production picker after guarded Import photos, then Back. No image selection, forced picker, raw result injection, build/install, grants, media writes or settings action."""

if not __debug__:
    raise RuntimeError("The operational evidence guards require assertions enabled")

import json
import argparse
import re
import time
import xml.etree.ElementTree as ET

from probe_media_provider import MediaProviderProbe
from run_picker_adapter import ADB, ROOT, sha


class RealPickerInspection(MediaProviderProbe):
    prefix = "picker-adapter-p06-real-picker-inspection"
    app = "app.pristinepage.debug"

    def __init__(self, inspect_album=False):
        if inspect_album:
            self.prefix = "picker-adapter-p06-real-picker-album-inspection"
        super().__init__()
        self.inspect_album = inspect_album
        self.input["sources"]["validation/decode/inspect_real_picker.py"] = sha(ROOT / "validation/decode/inspect_real_picker.py")
        self.input["purpose"] = "Unchanged installed production picker UI inspection and guarded Back, without selecting any photo"
        self.save(self.path("artifacts.json"), self.input)
        self.snapshots = 0

    def tree(self):
        text = self.command([ADB, "-s", self.serial, "exec-out", "uiautomator", "dump", "/dev/tty"], timeout=30)
        start = text.find("<?xml")
        end = text.rfind("</hierarchy>")
        assert start >= 0 and end >= start, "No complete observed UI hierarchy"
        xml = text[start:end + len("</hierarchy>")]
        self.snapshots += 1
        with self.path(f"ui-{self.snapshots:02d}.xml").open("x", encoding="utf-8") as output:
            output.write(xml)
        return ET.fromstring(xml)

    @staticmethod
    def package(tree):
        nodes = tree.findall("node")
        assert len(nodes) == 1
        return nodes[0].get("package")

    def text_control(self, tree, text, package):
        assert self.package(tree) == package
        parents = {child: parent for parent in tree.iter() for child in parent}
        candidates = set()
        for node in tree.iter("node"):
            if node.get("text") == text:
                while node.get("clickable") != "true" and node in parents:
                    node = parents[node]
                if node.get("clickable") == "true" and node.get("enabled") == "true":
                    candidates.add(node)
        if not candidates:
            return None
        assert len(candidates) == 1, "Requested text control is not uniquely identified"
        node = candidates.pop()
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        assert bounds
        left, top, right, bottom = map(int, bounds.groups())
        assert right > left and bottom > top
        return (left + right) // 2, (top + bottom) // 2

    def import_control(self, tree):
        return self.text_control(tree, "Import photos", self.app)

    def install_and_test(self):
        self.install_pair()  # Previous=current Q hashes, so a different installation refuses before any update.
        args = [ADB, "-s", self.serial]
        result = self.command(args + ["shell", "pm", "resolve-activity", "--brief", "-a", "androidx.activity.result.contract.action.PICK_IMAGES", "-t", "image/\\*"])
        matches = re.findall(r"^([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)$", result, re.M)
        assert len(matches) == 1, result
        expected = matches[0][0]
        assert expected not in (self.app, "com.android.permissioncontroller", "com.android.settings")
        launch = self.command(args + ["shell", "am", "start", "-W", "-n", self.app + "/app.pristinepage.MainActivity"], timeout=30)
        assert "Status: ok" in launch
        tree = self.tree()
        point = self.import_control(tree)
        if point is None:
            tree = self.tree()
            point = self.import_control(tree)
        assert point is not None, "Observed app has no enabled unique import action"
        self.command(args + ["shell", "input", "tap", *map(str, point)])
        tree = self.tree()
        if self.package(tree) == self.app:
            tree = self.tree()
        assert self.package(tree) == expected, {"expected": expected, "actual": self.package(tree)}
        nodes = [{key: node.get(key) for key in ("text", "content-desc", "resource-id", "class", "clickable", "enabled", "selected", "bounds")} for node in tree.iter("node")]
        self.save(self.path("picker-inspection.json"), {"generation": self.generation, "resolvedPackage": expected, "observedPackage": self.package(tree), "nodes": nodes, "selectedImages": 0, "noGrantClaim": True})
        self.command(args + ["shell", "dumpsys", "activity", "-h"])
        if self.inspect_album:
            registered = json.loads((ROOT / "validation/raw/decode/picker-adapter-p06-dcim-fixtures-registered-fixtures.json").read_text(encoding="utf-8"))
            album = registered["guestDirectory"].rsplit("/", 1)[-1]
            assert album == "PristinePage-QA-" + registered["generation"] and len(registered["registered"]) == 2
            point = self.text_control(tree, "Albums", expected)
            assert point is not None, "Observed picker has no unique enabled Albums control"
            self.command(args + ["shell", "input", "tap", *map(str, point)])
            tree = self.tree()
            point = self.text_control(tree, album, expected)
            self.save(self.path("album-list.json"), {"album": album, "observedPackage": self.package(tree), "visibleText": [node.get("text") for node in tree.iter("node") if node.get("text")], "uniquelyMatched": point is not None, "selectedImages": 0})
            if point is not None:
                self.command(args + ["shell", "input", "tap", *map(str, point)])
                tree = self.tree()
                assert self.package(tree) == expected
                self.save(self.path("album-contents.json"), {"album": album, "nodes": [{key: node.get(key) for key in ("text", "content-desc", "resource-id", "class", "clickable", "enabled", "selected", "bounds")} for node in tree.iter("node")], "selectedImages": 0})
        # Back only after the exact capability-selected package was observed. No settings/permission dialog or image receives input.
        self.command(args + ["shell", "input", "keyevent", "4"])
        returned = self.tree()
        if self.package(returned) == expected and self.inspect_album:
            self.command(args + ["shell", "input", "keyevent", "4"])
            returned = self.tree()
        assert self.package(returned) == self.app, "Picker Back did not return to the app; do not claim cancellation"
        self.save(self.path("inspection-outcome.json"), {"generation": self.generation, "returnedToApp": True, "selectedImages": 0, "limitations": "UI inspection/Back only; not native session-state, grant or private-copy acceptance"})
        print(json.dumps({"pickerPackage": expected, "visibleText": [node["text"] for node in nodes if node["text"]], "returnedToApp": True, "selectedImages": 0}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--album", action="store_true")
    RealPickerInspection(parser.parse_args().album).run()
