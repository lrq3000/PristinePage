"""Guarded on-screen Files import from one verified synthetic PNG on the owned OCR guest."""

from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from prepare_ocr_local_app import verify_file


CONTROL_SHA256 = "f4585967198db8a51a7d8afae50e449bdead2ad3b4122d1478afffc5fbd5608f"
FILES = {"com.android.documentsui", "com.google.android.documentsui"}


class LocalFilesJourney:
    def __init__(self, run, target, app, source):
        self.run = run
        self.target = target
        self.app = app
        self.source = source
        self.nonce = uuid.uuid4().hex[:12]
        self.name = f"ocr-{self.nonce}.png"
        self.snapshots = 0

    def command(self, *args, timeout=20, allowed=(0,)):
        return self.run.command([*self.target, *args], timeout=timeout, allowed=allowed)

    def read_tree_output(self):
        argv = ("exec-out", "uiautomator", "dump", "/dev/tty")
        try:
            readback = self.command(*argv, timeout=20)
        except subprocess.TimeoutExpired:
            # A deadlined read is not evidence that the prior tap failed. Observe again, without replaying input.
            return self.command(*argv, timeout=20)
        if readback.strip() == "ERROR: null root node returned by UiTestAutomationBridge.":
            return self.command(*argv, timeout=20)
        return readback

    def tree(self):
        raw = self.read_tree_output()
        xml = re.search(r"<\?xml\b.*</hierarchy>", raw, re.S)
        if not xml:
            raise RuntimeError("No complete UI hierarchy")
        self.snapshots += 1
        self.run.path(f"files-ui-{self.snapshots:02d}.xml").write_text(xml[0], encoding="utf-8")
        tree = ET.fromstring(xml[0])
        top = tree.findall("node")
        if len(top) != 1:
            raise RuntimeError("Unidentified foreground window")
        return tree, top[0].get("package")

    @staticmethod
    def point(tree, label, document_row=False):
        labels = (label,) if isinstance(label, str) else label
        parents = {child: parent for parent in tree.iter() for child in parent}
        actionable = set()
        for node in tree.iter("node"):
            if not any(value in (node.get("text"), node.get("content-desc")) for value in labels):
                continue
            if document_row and node.get("resource-id") == "android:id/title" and node.get("enabled") == "true":
                owner = node.get("package")
                if owner in FILES:
                    row = node
                    while row in parents:
                        parent = parents[row]
                        if parent.get("resource-id") == owner + ":id/dir_list":
                            # API 28 DocumentsUI delegates row taps to the list and exposes focusability, not clickability, on the row.
                            if row.get("focusable") == row.get("enabled") == "true" and row.get("package") == owner:
                                actionable.add(row)
                            break
                        row = parent
                    continue
            while node.get("clickable") != "true" and node in parents:
                node = parents[node]
            if node.get("clickable") == node.get("enabled") == "true":
                actionable.add(node)
        if not actionable:
            return None
        if len(actionable) > 1:
            raise RuntimeError(f"Ambiguous visible control: {label}")
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", actionable.pop().get("bounds", ""))
        if not bounds:
            raise RuntimeError("Invalid control bounds")
        x1, y1, x2, y2 = map(int, bounds.groups())
        if x2 <= x1 or y2 <= y1:
            raise RuntimeError("Control not visible")
        return str((x1 + x2) // 2), str((y1 + y2) // 2)

    def wait(self, packages, check, seconds=20):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            tree, package = self.tree()
            if package not in packages:
                raise RuntimeError(f"Unmatched foreground package {package}; no further input")
            match = check(tree, package)
            if match is not None:
                return match
            time.sleep(0.4)
        raise RuntimeError("Timed out waiting for the expected on-screen state")

    def tap(self, label, packages=None, document_row=False):
        x, y = self.wait(packages or {self.app}, lambda tree, _: self.point(tree, label, document_row))
        self.command("shell", "input", "tap", x, y)

    @staticmethod
    def labels(tree):
        return {node.get("text") for node in tree.iter("node") if node.get("text")}

    def launch(self):
        result = self.command("shell", "am", "start", "-W", "-n", f"{self.app}/app.pristinepage.MainActivity", timeout=30)
        if "Status: ok" not in result:
            raise RuntimeError("Could not launch the verified app")

    def enter(self):
        path = Path(self.source)
        verify_file(path.absolute(), path.stat().st_size, CONTROL_SHA256)
        guest_dir = f"/sdcard/Download/PristinePage-{self.nonce}"
        self.command("shell", "mkdir", guest_dir)
        guest_file = f"{guest_dir}/{self.name}"
        self.command("push", path, guest_file)
        if self.command("shell", "sha256sum", guest_file).split()[0] != CONTROL_SHA256:
            raise RuntimeError("The staged synthetic scan does not match")
        self.launch()
        self.tap("New scan")
        entry = self.wait({self.app}, lambda tree, _: (
            "resume" if "Resume an unfinished scan?" in self.labels(tree) else
            "empty" if "Imported: 0" in self.labels(tree) else None))
        if entry == "resume":
            self.tap("Resume scan")
            empty = self.wait({self.app}, lambda tree, _: self.labels(tree) if "Imported: 0" in self.labels(tree) else None)
            if "Cancel unfinished import" in empty:
                # The only resumable pending request here is the empty synthetic picker batch. Use the app's visible cancellation, not app-data deletion.
                self.tap("Cancel unfinished import")
        self.tap("Import photos")
        self.tap("From files")
        self.wait({self.app, *FILES}, lambda _, owner: owner if owner in FILES else None)
        # Open only the hash-checked uniquely named file, whether the picker exposes it in Recents or under Downloads.
        drawer = ("Show roots", "Afficher les répertoires racines")
        def locate_fixture(tree, _):
            return self.point(tree, self.name, document_row=True) or (("drawer",) if self.point(tree, drawer) else None)

        destination = self.wait(FILES, locate_fixture)
        if destination == ("drawer",):
            self.tap(drawer, FILES)
            self.tap(("Downloads", "Téléchargements"), FILES)
            self.tap(f"PristinePage-{self.nonce}", FILES, document_row=True)
            self.tap(self.name, FILES, document_row=True)
        else:
            self.command("shell", "input", "tap", *destination)
        self.tap("Review pages")
        self.tap("Finish importing")
        self.tap("Skip perspective")
        self.wait({self.app}, lambda tree, _: True if "Use the whole image?" in self.labels(tree) else None)
        self.tap("Skip perspective")
        self.wait({self.app}, lambda tree, _: True if any(s.startswith("Step 2 of 3") for s in self.labels(tree)) else None)
        self.tap("Next")
        self.wait({self.app}, lambda tree, _: True if any(s.startswith("Step 3 of 3") for s in self.labels(tree)) else None)
        self.tap("Natural lightness")
        self.tap("Save album")

        def album_title(tree, _):
            labels = self.labels(tree)
            candidates = [label.removesuffix("  ✎") for label in labels
                          if label.startswith("pristinepage_") and label.endswith("  ✎")]
            return candidates[0] if "All pages" in labels and len(candidates) == 1 else None

        title = self.wait({self.app}, album_title, seconds=35)
        self.run.save(self.run.path("files-saved-album.json"), {"album": title, "fixtureSha256": CONTROL_SHA256,
                                                                "correction": "Natural lightness"}, durable=True)
        self.tap("Back to my scans")
        return title


class LocalOcrSettingsRestart(LocalFilesJourney):
    """Exercise the installed app's Settings without instrumentation, a guest reset or a new build."""

    BASE = "Recognition order: English, French"
    REVERSED = "Recognition order: French, English"
    FRENCH_ONLY = "Recognition order: French"
    ENGLISH_ONLY = "Recognition order: English"

    def __init__(self, run, target, app):
        super().__init__(run, target, app, None)

    def open_languages(self):
        for label in ("Settings", "Text recognition (OCR)", "OCR languages"):
            self.tap(label)

    def order(self):
        def read(tree, _):
            labels = self.labels(tree)
            orders = {label for label in labels if label.startswith("Recognition order: ")}
            if len(orders) > 1:
                raise RuntimeError("Ambiguous OCR recognition order")
            return next(iter(orders)) if orders else None

        return self.wait({self.app}, read)

    def expect(self, order):
        if self.order() != order:
            raise RuntimeError(f"Expected the OCR Settings UI to display {order}")

    def process(self):
        pid = self.command("shell", "pidof", self.app, allowed=(0, 1)).strip()
        if pid and not re.fullmatch(r"[1-9][0-9]*", pid):
            raise RuntimeError("App process identity is ambiguous")
        return pid

    def stop_process(self):
        self.command("shell", "am", "force-stop", self.app)
        if self.process():
            raise RuntimeError("App process did not exit")

    def restore(self):
        # Start at Home after any interruption, then use only the exact observed Settings state to choose the corrective tap.
        self.stop_process()
        self.launch()
        self.open_languages()
        order = self.order()
        if order == self.FRENCH_ONLY:
            self.tap("English")
            self.expect(self.REVERSED)
            order = self.REVERSED
        if order == self.REVERSED:
            self.tap("French")
            self.expect(self.ENGLISH_ONLY)
            order = self.ENGLISH_ONLY
        if order == self.ENGLISH_ONLY:
            self.tap("French")
        self.expect(self.BASE)

    def verify(self):
        if self.process():
            raise RuntimeError("Settings witness requires the app process to be absent at entry")
        self.launch()
        self.open_languages()
        self.expect(self.BASE)
        first_pid = self.process()
        if not first_pid:
            raise RuntimeError("No initial app process")
        try:
            self.tap("English")
            self.expect(self.FRENCH_ONLY)
            self.tap("English")
            self.expect(self.REVERSED)
            self.stop_process()
            self.launch()
            second_pid = self.process()
            if not second_pid or second_pid == first_pid:
                raise RuntimeError("No distinct cold app process")
            self.open_languages()
            self.expect(self.REVERSED)
        finally:
            self.restore()
        return {"initial": ["eng", "fra"], "afterColdStart": ["fra", "eng"],
                "restored": ["eng", "fra"], "distinctAppProcesses": True}
