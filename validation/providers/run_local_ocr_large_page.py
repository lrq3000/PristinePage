"""One dense-page instrumentation witness on the exact retained local OCR app; no destination, radio or app replacement."""

import argparse
import json
import os
import re
import uuid
from pathlib import Path

from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, verify_target
from run_local_ocr_guest import APP, GUEST_PATTERN, RUNNER, TEST, exact_apk, install_flags, load_allocator, need, passed_case, run_type, sha


CASE = ("app.pristinepage.ocr.LocalOcrStartupBackfillTest", "largerSavedPageCompletesItsExactWakeAndOpensFromTheFooter")
APP_SHA = "d412ad7bb8113d97308f9e18129f4d516cff14dc527fba59d9b3e30885355429"
TEST_PREDECESSOR = "0c5ac42310137ab098f2754dd3789f172dfd42c948a67b0d91fc2edd0372b49b"
SAMPLE = re.compile(r"(?m)^INSTRUMENTATION_STATUS: ocrLargePageSample=([A-Za-z0-9=;_,:]+)\nINSTRUMENTATION_STATUS_CODE: 66\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("guest", help="The already authorized, released synthetic OCR guest")
    parser.add_argument("test_sha")
    args = parser.parse_args()
    need(re.fullmatch(GUEST_PATTERN, args.guest), "Require the exact authorized OCR guest name")
    need(re.fullmatch(r"[a-f0-9]{64}", args.test_sha) and args.test_sha != TEST_PREDECESSOR, "Require the reviewed larger-page test APK digest")
    need(not any(os.environ.get(name) for name in LOCAL_ADB_OVERRIDES), "Refusing ADB overrides")
    root = Path.cwd().resolve()
    need(root.name == "ocr-current-validation" and root.parent.name == ".worktrees", "Unexpected OCR worktree")
    providers = root / "validation/raw/providers"
    stage = providers / "ocr-app-main-f28885a"
    retained = providers / "ocr-publication-reviewed-apks"
    test = retained / f"{args.test_sha}.apk"
    proofs = [exact_apk(retained / f"{APP_SHA}.apk", APP_SHA, stage, False), exact_apk(test, args.test_sha, stage, True)]
    prefix = "picker-adapter-ocr-large-page-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    ns = load_allocator(root, args.guest, raw, "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"])
    # The existing allocator reads this exact artifact receipt during construction, before it can claim a guest.
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as receipt:
        json.dump({"apks": proofs, "driverSha256": sha(Path(__file__))}, receipt, indent=2)
    run = run_type(ns)(prefix, None, 0)
    print(f"Dense-page evidence: {raw.relative_to(root)}", flush=True)
    try:
        run.prepare(False, None, False)
        verify_target(run, ns)
        target = [ns["ADB"], "-s", run.serial]
        need(run.installed_hash(APP) == APP_SHA, "Refusing to replace or downgrade the app")
        predecessor = run.installed_hash(TEST)
        flags = install_flags(TEST, predecessor, args.test_sha, TEST_PREDECESSOR, require_installed=True)
        if flags is not None:
            verify_target(run, ns)
            need(sha(test) == args.test_sha, "Test APK changed before install")
            result = run.command([*target, "install", *flags, "--no-streaming", test], timeout=130)
            need(result.strip().splitlines()[-1] == "Success", "Scoped test replacement failed; reconcile installed bytes before retry")
        need(run.installed_hash(APP) == APP_SHA and run.installed_hash(TEST) == args.test_sha and
             run.command([*target, "shell", "pm", "list", "instrumentation", APP]).splitlines() ==
             [f"instrumentation:{RUNNER} (target={APP})"], "Installed APK bytes or runner drifted")
        run.save(run.path("installed.json"), {"app": APP_SHA, "test": args.test_sha, "testBefore": predecessor}, durable=True)
        need(not run.command([*target, "shell", "pidof", APP], allowed=(0, 1)).strip(), "The ordinary app process must start cold")
        output = run.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class", "#".join(CASE),
                              "-e", "ocrLargePage", "1", RUNNER], timeout=245)
        run.path("witness.txt").write_text(output, encoding="utf-8")
        normalized = output.replace("\r\n", "\n")
        samples = SAMPLE.findall(normalized)
        ready_ms = re.findall(r"(?m)^INSTRUMENTATION_RESULT: ocrLargePageReadyMs=([1-9]\d{0,6})$", normalized)
        need(passed_case(SAMPLE.sub("", normalized), CASE) and len(ready_ms) == 1 and
             re.findall(r"(?m)^INSTRUMENTATION_RESULT: ocrLargePageWitness=(\w+)$", normalized) ==
             ["EXACT_DENSE_PAGE_WAKE_AND_REOPEN"] and samples and
             re.fullmatch(r"phase=READY;starts=1;error=null;wakes=SUCCEEDED:\d+", samples[-1]),
             "The exact dense-page witness did not pass; preserve the observed wake and Room state")
        print(f"Dense page reached READY and exact Home reopen; launch-to-ready observation: {ready_ms[0]} ms", flush=True)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
