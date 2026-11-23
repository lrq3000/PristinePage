"""Exercise one real synthetic text Save as against a fresh guest-only Downloads directory."""

import argparse
import json
import os
import re
import subprocess
import time
import uuid
from pathlib import Path

from local_jpeg_destination import matches_local_downloads
from local_ocr_files_journey import FILES, LocalFilesJourney
from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, verify_target
from run_local_ocr_guest import APP, GUEST_PATTERN, RUNNER, TEST, exact_apk, install_flags, load_allocator, need, passed_case, run_type, sha
from run_local_ocr_large_page import APP_SHA
from run_local_ocr_text_chooser_death import ALBUM, DIGEST, OPEN


EXPECTED_STATUS = re.compile(
    r"(?m)^((?:INSTRUMENTATION_STATUS: ocrTextExpected(?:Bytes|Sha)=[a-z0-9]+\n){2})"
    r"INSTRUMENTATION_STATUS_CODE: 68\n"
)
LEAF = "recognized-text.txt"
EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
BLANK = (OPEN[0], "recognizedBlankPageCanSaveAnEmptyTextDocument")


def expected_output(transcript, *, blank=False):
    marks = EXPECTED_STATUS.findall(transcript)
    if not marks:
        return None
    need(len(marks) == 1, "Duplicate chooser-ready checkpoint")
    length = re.findall(r"(?m)^INSTRUMENTATION_STATUS: ocrTextExpectedBytes=(0|[1-9]\d{0,5})$", marks[0])
    digest = re.findall(rf"(?m)^INSTRUMENTATION_STATUS: ocrTextExpectedSha=({DIGEST})$", marks[0])
    need(len(length) == len(digest) == 1 and int(length[0]) <= 393_216, "Malformed bounded synthetic producer identity")
    need((int(length[0]) == 0 and digest[0] == EMPTY_SHA) if blank else int(length[0]) > 0,
         "The producer checkpoint does not match the selected blank/nonblank case")
    return int(length[0]), digest[0]


def confirmation_point(tree, owner, folder):
    def exact(nodes, klass, text):
        return [n for n in nodes if n.get("class") == klass and n.get("text") == text]

    if owner not in FILES:
        return None
    titles = [n for n in tree.iter("node") if n.get("package") == owner and n.get("resource-id") == "android:id/title"]
    heading = exact(titles, "android.widget.TextView", folder)
    filename = exact(titles, "android.widget.EditText", LEAF)
    if len(heading) != 1 or len(filename) != 1 or filename[0].get("enabled") != "true":
        return None
    buttons = [n for n in tree.iter("node") if n.get("package") == owner and n.get("resource-id") == "android:id/button1"
               and n.get("enabled") == n.get("clickable") == "true" and (n.get("text") or "").casefold() in {"save", "enregistrer"}]
    if len(buttons) != 1:
        return None
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", buttons[0].get("bounds", ""))
    if not bounds:
        return None
    x1, y1, x2, y2 = map(int, bounds.groups())
    return (str((x1 + x2) // 2), str((y1 + y2) // 2)) if x1 < x2 and y1 < y2 else None


def exercise(run, ns, target, title, token, *, blank=False):
    journey = LocalFilesJourney(run, target, APP, None)
    folder = f"PristinePage-Export-{token}"
    directory = f"/sdcard/Download/{folder}"
    destination = f"{directory}/{LEAF}"
    journey.command("shell", "mkdir", directory)
    real = journey.command("shell", f"cd {directory} && pwd -P").strip()
    need(re.fullmatch(rf"/(?:storage/emulated/0|mnt/shell/emulated/0)/Download/{folder}", real) and
         not journey.command("shell", "ls", "-A", directory).strip(), "Expected an exact new empty guest directory")
    out = run.path("instrumentation.txt")
    case = BLANK if blank else OPEN
    extras = ["-e", "ocrBlankTextSave", "1"] if blank else ["-e", "filesSavedAlbum", title, "-e", "filesInspectTextChooser", "4"]
    argv = [*target, "shell", "am", "instrument", "-w", "-r", "-e", "class", "#".join(case), *extras, RUNNER]
    with out.open("xb") as f:
        proc = subprocess.Popen(argv, stdout=f, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + (115 if blank else 70)
            while True:
                transcript = out.read_text(encoding="utf-8", errors="replace")
                expected = expected_output(transcript, blank=blank)
                if expected:
                    need(proc.poll() is None, "The producer checkpoint no longer owns the live chooser")
                    break
                need(proc.poll() is None and time.monotonic() < deadline, "No app-side chooser-ready witness")
                time.sleep(.3)
            owner = journey.wait({APP, *FILES}, lambda _, pkg: pkg if pkg in FILES else None)
            journey.tap(("Show roots", "Afficher les répertoires racines"), FILES)
            journey.tap(("Downloads", "Téléchargements"), FILES)
            journey.tap(folder, FILES, document_row=True)
            point = journey.wait({owner}, lambda tree, _: confirmation_point(tree, owner, folder), seconds=20)
            block = journey.command("shell", "dumpsys", "activity", "top", "-a", timeout=18)
            component, = [part for part in (f"{owner}/.picker.PickActivity", f"{owner}/{owner}.picker.PickActivity")
                          if matches_local_downloads(block, part, real)]
            window = journey.command("shell", "dumpsys", "window", "windows")
            focus = re.findall(r"(?m)^\s*mCurrentFocus=Window\{[^\r\n]*\bu0\s+([^}\s]+)\}", window)
            need(focus in ([component], [f"{owner}/{owner}.picker.PickActivity"]), "Wrong focused picker")
            tree, foreground = journey.tree()
            need(foreground == owner and confirmation_point(tree, owner, folder) == point and
                 not journey.command("shell", "ls", "-A", directory).strip() and proc.poll() is None,
                 "The exact filename or new local destination changed")
            verify_target(run, ns)
            run.save(run.path("pre-confirmation.json"), {"component": component, "directory": real, "leaf": LEAF,
                     "expectedBytes": expected[0], "expectedSha256": expected[1]}, durable=True)
            # Record the confirmation boundary before the sole tap. A timeout afterwards never authorizes replay.
            journey.command("shell", "input", "tap", *point)
            need(proc.wait(timeout=42) == 0, "The app-side verification did not finish")
            result = out.read_text(encoding="utf-8", errors="replace")
            need(passed_case(EXPECTED_STATUS.sub("", result), case) and
                 expected_output(result, blank=blank) == expected and
                 re.findall(r"(?m)^INSTRUMENTATION_RESULT: filesWitness=(\w+)$", result) == ["LOCAL_TEXT_APP_VERIFIED"],
                 "The app did not acknowledge verified text readback")
            need(journey.command("shell", "ls", "-A", directory).splitlines() == [LEAF], "Unexpected guest output")
            size = journey.command("shell", "stat", "-c", "%s", destination).strip()
            actual = journey.command("shell", "sha256sum", destination).split()
            need(size == str(expected[0]) and actual == [expected[1], destination], "Independent closed text bytes did not match")
            run.save(run.path("text-readback.json"), {"bytes": expected[0], "sha256": expected[1], "appVerified": True}, durable=True)
            print(f"Verified one synthetic local .txt with independent readback: {expected[0]} bytes, SHA-256 {expected[1]}", flush=True)
        finally:
            if proc.poll() is None:
                # This is the exact host ADB client, not the daemon. The shared allocator next stops its own guest.
                proc.terminate()
                proc.wait(timeout=12)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ("guest", "synthetic_files_album", "test_sha", "fresh_token"):
        parser.add_argument(field)
    parser.add_argument("--blank", action="store_true", help="Exercise only the independently generated successful blank OCR control")
    parser.add_argument("--previous-test", help="Exact installed predecessor for the test-only update")
    args = parser.parse_args()
    need(re.fullmatch(GUEST_PATTERN, args.guest) and re.fullmatch(ALBUM, args.synthetic_files_album) and
         re.fullmatch(DIGEST, args.test_sha) and re.fullmatch(r"[a-f0-9]{12}", args.fresh_token), "Require exact synthetic scope")
    need(not any(os.environ.get(name) for name in LOCAL_ADB_OVERRIDES), "Refusing ADB overrides")
    need(args.previous_test is None or (re.fullmatch(DIGEST, args.previous_test) and args.previous_test != args.test_sha),
         "Invalid exact test predecessor")
    root = Path.cwd().resolve()
    need(root.name == "ocr-current-validation" and root.parent.name == ".worktrees", "Unexpected OCR worktree")
    providers = root / "validation/raw/providers"
    stage, retained = providers / "ocr-app-main-f28885a", providers / "ocr-publication-reviewed-apks"
    test = retained / f"{args.test_sha}.apk"
    proofs = [exact_apk(retained / f"{APP_SHA}.apk", APP_SHA, stage, False), exact_apk(test, args.test_sha, stage, True)]
    prefix = "picker-adapter-ocr-text-save-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as f:
        json.dump({"apks": proofs, "driverSha256": sha(Path(__file__))}, f, indent=2)
    ns = load_allocator(root, args.guest, raw, "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"])
    run = run_type(ns)(prefix, None, 0)
    print(f"Text Save as evidence: {raw.relative_to(root)}", flush=True)
    try:
        run.prepare(False, None, False)
        verify_target(run, ns)
        target = [ns["ADB"], "-s", run.serial]
        need(run.installed_hash(APP) == APP_SHA, "Refusing to replace the exact app APK")
        previous = run.installed_hash(TEST)
        flags = install_flags(TEST, previous, args.test_sha, args.previous_test, require_installed=True)
        if flags:
            verify_target(run, ns)
            need(sha(test) == args.test_sha, "Test APK changed before install")
            install = run.command([*target, "install", *flags, "--no-streaming", test], timeout=130)
            need(install.strip().splitlines()[-1] == "Success", "Scoped test installation was not acknowledged")
        need(run.installed_hash(APP) == APP_SHA and run.installed_hash(TEST) == args.test_sha and
             run.command([*target, "shell", "pm", "list", "instrumentation", APP]).splitlines() ==
             [f"instrumentation:{RUNNER} (target={APP})"], "Refusing to operate against an unverified installed pair")
        run.save(run.path("installed.json"), {"app": APP_SHA, "test": args.test_sha, "testBefore": previous}, durable=True)
        exercise(run, ns, target, args.synthetic_files_album, args.fresh_token, blank=args.blank)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
