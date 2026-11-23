"""Exercise one exact self-target OCR probe on a released synthetic LDPlayer guest."""

import argparse
import json
import os
import re
import subprocess
import uuid
from pathlib import Path
from zipfile import ZipFile

from prepare_ocr_local_app import NATIVE, identity, verify_ocr_members
from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, verify_target
from run_local_ocr_guest import (
    APP, GUEST_PATTERN, LANGUAGE_RECEIPT, RUNNER, SIGNER, TEST, install_flags, load_allocator, need,
    passed_case, run_type, sha, verify_stage,
)


CASE = ("app.pristinepage.image.LocalSavedOcrIntegrationTest", "classifiesMissingPackNativeInitializationSinks")
SMOKE_CASE = ("app.pristinepage.image.LocalTesseractProbeTest", "readsTwentyCommittedSyntheticScans")
SMOKE_LANGUAGES = ("eng", "fra", "jpn", "chi_sim")
SMOKE_STYLES = ("clean", "receipt", "tint", "tilt", "perspective")
DIGEST = r"[0-9a-f]{64}"
SINK_RESULT = (r"(?m)^INSTRUMENTATION_RESULT: ocrNativeInitSinks="
               r"stderrLines=(\d{1,5});stderrPathLines=(\d{1,5});logcatLines=(\d{1,4});logcatPathLines=(\d{1,4})[ \r]*$")


def exact_probe(stage, apk, digest, *, require_smoke=False):
    verify_stage(stage, LANGUAGE_RECEIPT)
    need(sha(apk) == digest, "Probe APK bytes changed")
    sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/build-tools/36.0.0"
    env = dict(os.environ, JAVA_HOME="C:/Program Files/Android/Android Studio/jbr")

    def read(tool, *arguments):
        return subprocess.run([sdk / tool, *arguments, apk], env=env, check=True, capture_output=True,
                              text=True, encoding="utf-8", timeout=45).stdout

    badging = read("aapt2.exe", "dump", "badging")
    manifest = read("aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml")
    signer = read("apksigner.bat", "verify", "--print-certs")
    read("zipalign.exe", "-c", "-P", "16", "4")
    need(re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$", signer) == [SIGNER] and
         re.search(rf"(?m)^package: name='{re.escape(TEST)}' ", badging) and
         '"androidx.test.runner.AndroidJUnitRunner"' in manifest and
         re.search(rf'android:targetPackage.*"{re.escape(TEST)}"', manifest) and
         not re.search(r"android.permission.(INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE)", manifest),
         "Probe is not the development-signed, offline self-target instrument")
    assets = json.loads((stage / "receipt.json").read_bytes())["assets"]
    expected = {f"assets/{name}": pin for name, pin in assets.items()}
    with ZipFile(stage / "tesseract4android.aar") as aar:
        expected.update({f"lib/arm64-v8a/{name}": identity(aar.read(f"jni/arm64-v8a/{name}")) for name in NATIVE})
    with ZipFile(apk) as archive:
        if require_smoke or any(name.startswith("assets/ocr-probe/smoke/") for name in archive.namelist()):
            smoke = Path(__file__).with_name("android-ocr-probe-assets") / "ocr-probe" / "smoke"
            references = (smoke / "references.json").read_bytes()
            cases = json.loads(references)
            ids = [f"{lang}-{style}" for lang in SMOKE_LANGUAGES for style in SMOKE_STYLES]
            need([case["id"] for case in cases] == ids and {p.name for p in smoke.iterdir()} ==
                 {"references.json", *(f"{id}.png" for id in ids)}, "Wrong checked-in smoke inventory")
            expected["assets/ocr-probe/smoke/references.json"] = identity(references)
            for case in cases:
                pin = (case["bytes"], case["sha256"])
                need(0 < pin[0] <= 128 * 1024 and re.fullmatch(DIGEST, pin[1]) and
                     identity((smoke / f'{case["id"]}.png').read_bytes()) == pin, "Checked-in smoke fixture differs from reference")
                expected[f'assets/ocr-probe/smoke/{case["id"]}.png'] = pin
        verify_ocr_members(archive, expected)
    return {"package": TEST, "sha256": digest, "manifest": manifest, "badging": badging}


def checked_smoke(output):
    lines = re.findall(r"(?m)^INSTRUMENTATION_RESULT: ocrSmokeMetrics=(\[[^\r\n]+\])[ \r]*$", output)
    need(len(lines) == 1, "No complete 20-case smoke result")
    records = json.loads(lines[0])
    ids = [f"{language}-{style}" for language in SMOKE_LANGUAGES for style in SMOKE_STYLES]
    need(isinstance(records, list) and len(records) == len(ids), "Wrong number of smoke results")
    result = {}
    for id, item in zip(ids, records, strict=True):
        match = isinstance(item, str) and re.fullmatch(re.escape(id) + r":(\d{1,3})/([1-9]\d{0,2}):([01])([01])", item)
        need(bool(match), "Malformed or reordered smoke case")
        edits, chars, anchor, identifier = map(int, match.groups())
        result[id] = {"edits": edits, "characters": chars, "scriptAnchor": bool(anchor),
                      "identifier": bool(identifier), "passed": bool(anchor and identifier and 100 * edits <= 15 * chars)}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("guest")
    parser.add_argument("app_sha")
    parser.add_argument("prior_test_sha")
    parser.add_argument("probe_sha")
    parser.add_argument("--smoke", action="store_true", help="Run only the 20 committed synthetic scans, not the failure-sink case")
    args = parser.parse_args()
    need(re.fullmatch(GUEST_PATTERN, args.guest) and all(re.fullmatch(DIGEST, digest) for digest in
         (args.app_sha, args.prior_test_sha, args.probe_sha)) and not any(os.environ.get(n) for n in LOCAL_ADB_OVERRIDES),
         "Require the exact authorized guest and artifact identities with no ADB override")
    root = Path.cwd().resolve()
    need(root.name == "ocr-current-validation" and root.parent.name == ".worktrees", "Wrong OCR worktree")
    providers = root / "validation/raw/providers"
    probe = providers / "ocr-publication-reviewed-apks" / f"{args.probe_sha}.apk"
    proof = exact_probe(providers / "ocr-engine-current-script-controls", probe, args.probe_sha, require_smoke=args.smoke)
    prefix = ("picker-adapter-ocr-smoke-" if args.smoke else "picker-adapter-ocr-native-init-") + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as receipt:
        json.dump({"apks": [proof], "driverSha256": sha(Path(__file__)), "stageReceiptSha256": LANGUAGE_RECEIPT}, receipt, indent=2)
    ns = load_allocator(root, args.guest, raw, "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"])
    run = run_type(ns)(prefix, None, 0)
    print(f"OCR probe evidence: {raw.relative_to(root)}", flush=True)
    try:
        run.prepare(False, None, False)
        verify_target(run, ns)
        target = [ns["ADB"], "-s", run.serial]
        need(run.installed_hash(APP) == args.app_sha, "Refusing to change the retained application")
        predecessor = run.installed_hash(TEST)
        flags = install_flags(TEST, predecessor, args.probe_sha, args.prior_test_sha, require_installed=True)
        if flags is not None:
            verify_target(run, ns)
            need(sha(probe) == args.probe_sha, "Probe APK changed before installation")
            install = run.command([*target, "install", *flags, "--no-streaming", probe], timeout=130)
            need(install.strip().splitlines()[-1] == "Success", "Scoped probe installation was not acknowledged")
        need(run.installed_hash(TEST) == args.probe_sha and
             run.command([*target, "shell", "pm", "list", "instrumentation", TEST]).splitlines() ==
             [f"instrumentation:{RUNNER} (target={TEST})"], "Wrong installed self-target probe")
        run.save(run.path("installed.json"), {"app": args.app_sha, "test": args.probe_sha, "testBefore": predecessor}, durable=True)
        need(not run.command([*target, "shell", "pidof", APP, TEST], allowed=(0, 1)).strip(), "Expected cold test and app processes")
        case = SMOKE_CASE if args.smoke else CASE
        output = run.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class", "#".join(case), RUNNER],
                             timeout=180 if args.smoke else 105, allowed=(0, 1) if args.smoke else (0,))
        if args.smoke:
            result = checked_smoke(output)
            run.save(run.path("outcomes.json"), result, durable=True)
            for id, metrics in result.items():
                print(f"{id}: {metrics['edits']}/{metrics['characters']} edits; "
                      f"anchor={metrics['scriptAnchor']}; identifier={metrics['identifier']}", flush=True)
            need(passed_case(output, case) and all(m["passed"] for m in result.values()), "One or more smoke cases failed")
            return
        counts = re.findall(SINK_RESULT, output)
        need(passed_case(output, CASE) and len(counts) == 1, "No complete scoped initialization-sink witness")
        stderr, stderr_paths, logcat, logcat_paths = map(int, counts[0])
        need(stderr_paths <= stderr and logcat_paths <= logcat, "Path counts exceed observed output")
        result = dict(stderrLines=stderr, stderrPathLines=stderr_paths, logcatLines=logcat, logcatPathLines=logcat_paths)
        run.save(run.path("outcomes.json"), result, durable=True)
        print(json.dumps(result), flush=True)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
