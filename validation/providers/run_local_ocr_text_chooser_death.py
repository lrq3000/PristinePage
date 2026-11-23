"""End the exact synthetic OCR app process with the local chooser open, without selecting any destination."""

import argparse
import json
import os
import re
import uuid
from pathlib import Path

from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, verify_target
from run_local_ocr_guest import APP, GUEST_PATTERN, RUNNER, TEST, exact_apk, install_flags, load_allocator, need, passed_case, run_type, sha
from run_local_ocr_large_page import APP_SHA


OPEN = ("app.pristinepage.ocr.LocalOcrImportJourneyTest", "coldHomeSearchReopensTheSavedFilesPageAndCreatesJpeg")
RECOVER = ("app.pristinepage.ocr.LocalOcrTextDeathTest", "coldLaunchReportsInterruptedChooserWithoutReopeningIt")
DIGEST = r"[a-f0-9]{64}"
PID = r"[1-9][0-9]{0,8}"
CONSENT = r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
ALBUM = r"pristinepage_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}"


def interrupted(output):
    def status(key, value):
        return re.findall(rf"(?m)^INSTRUMENTATION_STATUS: {key}=({value})[ \r]*$", output)

    consent, pid = status("ocrTextDeathConsent", CONSENT), status("ocrTextDeathPid", PID)
    need(status("class", r"[\w.]+") == [OPEN[0]] and status("test", r"\w+") == [OPEN[1]] and
         len(consent) == len(pid) == 1 and
         re.findall(r"(?m)^INSTRUMENTATION_STATUS_CODE: (-?\d+)[ \r]*$", output) == ["1", "67"] and
         re.findall(r"(?m)^INSTRUMENTATION_RESULT: shortMsg=([^\r\n]+)", output) == ["Process crashed."] and
         re.findall(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)[ \r]*$", output) == ["0"],
         "Missing exact acknowledged chooser checkpoint and ensuing process exit")
    return consent[0], pid[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("guest")
    parser.add_argument("synthetic_files_album")
    parser.add_argument("previous_test")
    parser.add_argument("test_sha")
    parser.add_argument("--audit-downloads", help="Read only the outcome of a previously interrupted synthetic destination")
    parser.add_argument("--resume", help="Use the interrupted marker already retained by this worktree, without ending another process")
    args = parser.parse_args()
    need(re.fullmatch(GUEST_PATTERN, args.guest) and re.fullmatch(ALBUM, args.synthetic_files_album) and
         all(re.fullmatch(DIGEST, d) for d in (args.previous_test, args.test_sha)) and args.test_sha != args.previous_test,
         "Require exact authorized guest, saved synthetic album and test APK identities")
    need(not any(os.environ.get(name) for name in LOCAL_ADB_OVERRIDES), "Refusing ADB overrides")
    need(args.audit_downloads is None or re.fullmatch(r"[a-f0-9]{12}", args.audit_downloads), "Invalid synthetic destination token")
    root = Path.cwd().resolve()
    need(root.name == "ocr-current-validation" and root.parent.name == ".worktrees", "Unexpected OCR worktree")
    providers = root / "validation/raw/providers"
    prior = None
    if args.resume:
        need(re.fullmatch(r"picker-adapter-ocr-text-chooser-death-[a-f0-9]{12}", args.resume), "Invalid retained interruption run")
        prior = root / "validation/raw/storage" / args.resume
        manifest = json.loads((prior / f"{args.resume}-final-manifest.json").read_bytes())
        interruption = prior / f"{args.resume}-interrupted.txt"
        anchors = manifest["evidence"] + manifest["ancillaryEvidence"]
        matching = [entry for entry in anchors if Path(entry["path"]).resolve() == interruption]
        need(len(matching) == 1 and matching[0]["sha256"] == sha(interruption) and
             matching[0]["bytes"] == interruption.stat().st_size, "Previously observed interruption evidence changed")
        prior = interrupted(interruption.read_text(encoding="utf-8"))
    stage, retained = providers / "ocr-app-main-f28885a", providers / "ocr-publication-reviewed-apks"
    test = retained / f"{args.test_sha}.apk"
    proofs = [exact_apk(retained / f"{APP_SHA}.apk", APP_SHA, stage, False), exact_apk(test, args.test_sha, stage, True)]
    prefix = "picker-adapter-ocr-text-chooser-death-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as receipt:
        json.dump({"apks": proofs, "driverSha256": sha(Path(__file__))}, receipt, indent=2)
    ns = load_allocator(root, args.guest, raw, "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"])
    run = run_type(ns)(prefix, None, 0)
    print(f"Interrupted chooser evidence: {raw.relative_to(root)}", flush=True)
    try:
        run.prepare(False, None, False)
        verify_target(run, ns)
        target = [ns["ADB"], "-s", run.serial]
        need(run.installed_hash(APP) == APP_SHA, "Refusing to replace the exact app")
        if args.audit_downloads:
            directory = f"/sdcard/Download/PristinePage-Export-{args.audit_downloads}"
            leaf = f"{directory}/recognized-text.txt"
            entries = run.command([*target, "shell", "ls", "-A", directory]).splitlines()
            if entries == ["recognized-text.txt"]:
                size = run.command([*target, "shell", "stat", "-c", "%s", leaf]).strip()
                digest = run.command([*target, "shell", "sha256sum", leaf]).split()
                need(re.fullmatch(r"\d{1,7}", size) and len(digest) == 2 and re.fullmatch(DIGEST, digest[0]) and digest[1] == leaf,
                     "Existing synthetic file could not be verified; do not replay")
                observation = {"present": True, "bytes": int(size), "sha256": digest[0]}
            else:
                need(not entries, "Unexpected existing file; leave the synthetic directory intact")
                observation = {"present": False}
            run.save(run.path("synthetic-destination-observation.json"), observation, durable=True)
            print(f"Previous synthetic destination (read-only): {json.dumps(observation)}", flush=True)
        previous = run.installed_hash(TEST)
        flags = install_flags(TEST, previous, args.test_sha, args.previous_test, require_installed=True)
        if flags:
            verify_target(run, ns)
            need(sha(test) == args.test_sha, "Test APK changed before install")
            output = run.command([*target, "install", *flags, "--no-streaming", test], timeout=130)
            need(output.strip().splitlines()[-1] == "Success", "Scoped test installation was not acknowledged")
        need(run.installed_hash(APP) == APP_SHA and run.installed_hash(TEST) == args.test_sha and
             run.command([*target, "shell", "pm", "list", "instrumentation", APP]).splitlines() ==
             [f"instrumentation:{RUNNER} (target={APP})"], "Installed app/test identity or runner drifted")
        run.save(run.path("installed.json"), {"app": APP_SHA, "test": args.test_sha, "testBefore": previous}, durable=True)
        def require_cold():
            need(not run.command([*target, "shell", "pidof", APP], allowed=(0, 1)).strip(),
                 "The app process is not absent; refusing the next test stimulus")
        require_cold()
        base = [*target, "shell", "am", "instrument", "-w", "-r", "-e", "class"]
        if prior is None:
            first = run.command([*base, "#".join(OPEN), "-e", "filesSavedAlbum", args.synthetic_files_album,
                                 "-e", "filesInspectTextChooser", "3", RUNNER], timeout=245, allowed=(0, 1))
            run.path("interrupted.txt").write_text(first, encoding="utf-8")
            consent, old_pid = interrupted(first)
            require_cold()
            print("Observed durable chooser consent and exact app exit with no destination selected.", flush=True)
        else:
            consent, old_pid = prior
            print("Reusing the exact previously interrupted chooser consent without reopening it.", flush=True)
        second = run.command([*base, "#".join(RECOVER), "-e", "ocrTextDeathConsent", consent, RUNNER], timeout=90)
        run.path("recovered.txt").write_text(second, encoding="utf-8")
        new_pid = re.findall(rf"(?m)^INSTRUMENTATION_RESULT: ocrTextRecoveryPid=({PID})[ \r]*$", second)
        witness = re.findall(r"(?m)^INSTRUMENTATION_RESULT: ocrTextRecoveryWitness=(\w+)[ \r]*$", second)
        need(passed_case(second, RECOVER) and len(new_pid) == 1 and new_pid[0] != old_pid and
             witness == ["ENDED_CHOOSER_NO_AUTOMATIC_DESTINATION"], "No exact cold recovery/no-replay witness")
        run.save(run.path("outcomes.json"), {"ended": True, "recovered": True, "automaticDestination": False}, durable=True)
        print("The new app process reported the interrupted consent without reopening a chooser.", flush=True)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
