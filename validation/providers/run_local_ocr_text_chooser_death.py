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
WRITE = (RECOVER[0], "endProcessAfterClosingTheControlledTextFileBeforeAcknowledgment")
RECOVER_WRITE = (RECOVER[0], "coldLaunchKeepsControlledTextFileWithoutAcknowledgingOrReplayingIt")
DIGEST = r"[a-f0-9]{64}"
PID = r"[1-9][0-9]{0,8}"
CONSENT = r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
ALBUM = r"pristinepage_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}"


def interrupted(output, controlled_file=False):
    def status(key, value):
        return re.findall(rf"(?m)^INSTRUMENTATION_STATUS: {key}=({value})[ \r]*$", output)

    consent, pid = status("ocrTextDeathConsent", CONSENT), status("ocrTextDeathPid", PID)
    case, code = (WRITE, "69") if controlled_file else (OPEN, "67")
    need(status("class", r"[\w.]+") == [case[0]] and status("test", r"\w+") == [case[1]] and
         len(consent) == len(pid) == 1 and
         re.findall(r"(?m)^INSTRUMENTATION_STATUS_CODE: (-?\d+)[ \r]*$", output) == ["1", code] and
         re.findall(r"(?m)^INSTRUMENTATION_RESULT: shortMsg=([^\r\n]+)", output) == ["Process crashed."] and
         re.findall(r"(?m)^INSTRUMENTATION_CODE: (-?\d+)[ \r]*$", output) == ["0"],
         "Missing exact acknowledged chooser checkpoint and ensuing process exit")
    if controlled_file:
        length, digest = status("ocrTextClosedBytes", r"[1-9]\d{0,5}"), status("ocrTextClosedSha", DIGEST)
        need(len(length) == len(digest) == 1 and int(length[0]) <= 393_216, "Missing bounded pre-acknowledgment file proof")
        return consent[0], pid[0], length[0], digest[0]
    return consent[0], pid[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("guest")
    parser.add_argument("synthetic_files_album")
    parser.add_argument("previous_test")
    parser.add_argument("test_sha")
    parser.add_argument("--audit-downloads", help="Read only the outcome of a previously interrupted synthetic destination")
    parser.add_argument("--resume", help="Use the interrupted marker already retained by this worktree, without ending another process")
    parser.add_argument("--controlled-file", help="Observe death after closing one new app-private synthetic file, before acknowledgment")
    parser.add_argument("--processing-details", action="store_true", help="Check the ordinary opt-in OCR panel; do not stop the app or select a destination")
    args = parser.parse_args()
    need(re.fullmatch(GUEST_PATTERN, args.guest) and re.fullmatch(ALBUM, args.synthetic_files_album) and
         all(re.fullmatch(DIGEST, d) for d in (args.previous_test, args.test_sha)) and args.test_sha != args.previous_test,
         "Require exact authorized guest, saved synthetic album and test APK identities")
    need(not any(os.environ.get(name) for name in LOCAL_ADB_OVERRIDES), "Refusing ADB overrides")
    need(args.audit_downloads is None or re.fullmatch(r"[a-f0-9]{12}", args.audit_downloads), "Invalid synthetic destination token")
    need(not args.audit_downloads or not (args.resume or args.controlled_file or args.processing_details),
         "A read-only destination audit cannot launch instrumentation")
    need(args.controlled_file is None or (not args.audit_downloads and re.fullmatch(r"[a-f0-9]{12}", args.controlled_file)),
         "Controlled-file death requires an exact separate synthetic token")
    need(not args.processing_details or not (args.controlled_file or args.resume or args.audit_downloads),
         "The processing-details check is independent of interruption and external output")
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
        prior = interrupted(interruption.read_text(encoding="utf-8"), controlled_file=bool(args.controlled_file))
        manifest_args = json.loads((interruption.parent / f"{args.resume}-artifacts.json").read_bytes())
        if args.controlled_file:
            need(manifest_args.get("controlledFileToken") == args.controlled_file, "The prior controlled file is not this synthetic token")
    stage, retained = providers / "ocr-app-main-f28885a", providers / "ocr-publication-reviewed-apks"
    test = retained / f"{args.test_sha}.apk"
    proofs = [exact_apk(retained / f"{APP_SHA}.apk", APP_SHA, stage, False), exact_apk(test, args.test_sha, stage, True)]
    prefix = "picker-adapter-ocr-text-chooser-death-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as receipt:
        json.dump({"apks": proofs, "driverSha256": sha(Path(__file__)), "controlledFileToken": args.controlled_file}, receipt, indent=2)
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
            return
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
        if args.processing_details:
            output = run.command([*base, "#".join(OPEN), "-e", "filesSavedAlbum", args.synthetic_files_album,
                                  "-e", "filesInspectOcrDetails", "1", RUNNER], timeout=150)
            run.path("processing-details.txt").write_text(output, encoding="utf-8")
            witness = re.findall(r"(?m)^INSTRUMENTATION_RESULT: filesWitness=(\w+)[ \r]*$", output)
            need(passed_case(output, OPEN) and witness == ["OCR_DETAILS_OPT_IN_SAME_PRODUCER"],
                 "No exact ordinary Processing details opt-in witness")
            print("Verified opt-in Processing details, no verbatim text, same saved producer, and settings restoration.", flush=True)
            return
        if args.controlled_file:
            leaf = f"files/ocr-closed-control-{args.controlled_file}.txt"
            if prior is None:
                first = run.command([*base, "#".join(WRITE), "-e", "filesSavedAlbum", args.synthetic_files_album,
                                     "-e", "ocrTextOwnedFile", args.controlled_file, RUNNER], timeout=125, allowed=(0, 1))
                run.path("interrupted.txt").write_text(first, encoding="utf-8")
                consent, old_pid, extent, expected = interrupted(first, controlled_file=True)
            else:
                consent, old_pid, extent, expected = prior
            require_cold()

            def snapshot():
                identity = run.command([*target, "shell", "run-as", APP, "stat", "-c", "%s:%i:%Y", leaf]).strip()
                digest = run.command([*target, "shell", "run-as", APP, "sha256sum", leaf]).split()
                need(re.fullmatch(rf"{extent}:\d+:\d+", identity) and digest == [expected, leaf],
                     "Controlled closed bytes changed or could not be independently verified")
                return identity

            before = snapshot()
            second = run.command([*base, "#".join(RECOVER_WRITE), "-e", "ocrTextDeathConsent", consent,
                                  "-e", "ocrTextOwnedFile", args.controlled_file, "-e", "ocrTextClosedBytes", extent,
                                  "-e", "ocrTextClosedSha", expected, RUNNER], timeout=90)
            run.path("recovered.txt").write_text(second, encoding="utf-8")
            new_pid = re.findall(rf"(?m)^INSTRUMENTATION_RESULT: ocrTextRecoveryPid=({PID})[ \r]*$", second)
            witness = re.findall(r"(?m)^INSTRUMENTATION_RESULT: ocrTextRecoveryWitness=(\w+)[ \r]*$", second)
            need(passed_case(second, RECOVER_WRITE) and len(new_pid) == 1 and new_pid[0] != old_pid and
                 witness == ["CLOSED_CONTROLLED_FILE_UNCHANGED"] and snapshot() == before,
                 "No exact closed controlled file recovery/unchanged-byte witness")
            run.save(run.path("outcomes.json"), {"ended": True, "recovered": True, "controlledFile": True,
                     "closedBytes": int(extent), "sha256": expected}, durable=True)
            print("Recovered the actual interrupted WRITING row; the controlled file identity and bytes were unchanged.", flush=True)
            return
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
