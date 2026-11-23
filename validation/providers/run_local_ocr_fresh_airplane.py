"""First launch of the exact approved OCR APK in an isolated, newly provisioned LDPlayer guest."""

import argparse
import json
import os
import re
import shutil
import uuid
from pathlib import Path

from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, exercise, verify_target
from run_local_ocr_guest import APP, GUEST_PATTERN, LANGUAGE_RECEIPT, TEST, exact_apk, load_allocator, need, run_type, sha, verify_stage
from run_local_ocr_large_page import APP_SHA


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("test_sha")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--recover", nargs=2, metavar=("GUEST", "GENERATION"),
                      help="Reconcile and use the stopped registration left by the previous add; do not add again")
    mode.add_argument("--installed", metavar="GUEST", help="Reuse the exact released fresh guest only if its app was never launched")
    args = parser.parse_args()
    need(re.fullmatch(r"[a-f0-9]{64}", args.test_sha) and not any(os.environ.get(k) for k in LOCAL_ADB_OVERRIDES),
         "Require an exact reviewed test and no ADB overrides")
    if args.recover:
        name, generation = args.recover
        need(re.fullmatch(GUEST_PATTERN, name) and str(uuid.UUID(generation)) == generation, "Invalid exact provisioning identity")
    need(args.installed is None or re.fullmatch(GUEST_PATTERN, args.installed), "Invalid exact fresh guest name")
    root = Path.cwd().resolve()
    need(root.name == "ocr-current-validation" and root.parent.name == ".worktrees", "Wrong OCR worktree")
    providers = root / "validation/raw/providers"
    stage, retained = providers / "ocr-app-main-f28885a", providers / "ocr-publication-reviewed-apks"
    controls = providers / "ocr-engine-current-script-controls"
    verify_stage(controls, LANGUAGE_RECEIPT)
    app = retained / f"{APP_SHA}.apk"
    test = retained / f"{args.test_sha}.apk"
    hashes = {APP: APP_SHA, TEST: args.test_sha}
    proofs = [exact_apk(app, APP_SHA, stage, False), exact_apk(test, args.test_sha, stage, True)]
    prefix = "picker-adapter-ocr-fresh-airplane-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as receipt:
        json.dump({"apks": proofs, "driverSha256": sha(Path(__file__)), "retainedPrelaunchInstall": bool(args.installed)}, receipt, indent=2)
    guest = args.installed or (args.recover[0] if args.recover else "agent-pristinepage-ocr-" + uuid.uuid4().hex[:12])
    ns = load_allocator(root, guest, raw, "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"])
    # Leave extra space above the allocator's normal 12 GiB floor for this new virtual disk and first boot.
    need(args.installed or min(shutil.disk_usage(root).free, shutil.disk_usage(ns["CONSOLE"].parent / "vms").free) >= 16 * 1024**3,
         "Fresh guest requires 16 GiB before provisioning")
    run = run_type(ns)(prefix, None, 0)
    print(f"Fresh offline Files evidence: {raw.relative_to(root)}; exact OCR guest {guest}", flush=True)
    try:
        run.prepare(not args.recover and not args.installed, args.recover[1] if args.recover else None, not args.installed)
        verify_target(run, ns)
        target = [ns["ADB"], "-s", run.serial]
        if not args.installed:
            need(not run.command([*target, "shell", "pm", "list", "packages", APP]).strip(), "Fresh guest already contains a PristinePage package")
            run.save(run.path("preinstall.json"), {"appInstalled": False, "testInstalled": False}, durable=True)
            for package, artifact, flags in ((APP, app, []), (TEST, test, ["-t"])):
                verify_target(run, ns)
                need(sha(artifact) == hashes[package], "Fresh-install artifact bytes changed")
                install = run.command([*target, "install", *flags, "--no-streaming", artifact], timeout=130)
                need(install.strip().splitlines()[-1] == "Success", "Scoped fresh install did not acknowledge success")
        state = run.command([*target, "shell", "dumpsys", "package", APP])
        need(bool(re.search(r"(?m)^\s*User 0: .*?\bstopped=true\b[^\r\n]*\bnotLaunched=true\b", state)),
             "No positive proof of an installed but never-launched app")
        run.save(run.path("never-launched.json"), {"stopped": True, "notLaunched": True}, durable=True)
        exercise(run, ns, controls / "assets/ocr-probe/controls/eng.png", hashes, test, None, diagnostic_shell=bool(args.installed))
        print("First app launch completed the actual Files correction/save/search journey in airplane mode.", flush=True)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
