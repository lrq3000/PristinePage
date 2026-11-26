"""Exact successor pair; a new synthetic Files Open and editor preview, with no provider write."""

from argparse import ArgumentParser
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid

import run_library_folder_picker_ldplayer as checks
from run_bound_recipe_readback_ldplayer import readback


BASELINE = "picker-adapter-library-tree-08be480f914d"
BASELINE_SHA = "e25e685fa3e8aa5bb59f4c97fabb978ce4d506b0491ea660a2583d67da392996"
CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#confirmedOpenSwitchesToTheSelectedFilesCatalogAndRevocationDoesNotFallBack"


def run_open(commit, app_sha, test_sha):
    root = checks.ROOT
    checks.need(__debug__ and Path.cwd().resolve() == root and not any(os.environ.get(key) for key in checks.LOCAL_ADB_OVERRIDES),
                "Use the exact worktree and the ordinary scoped transport")
    checks.need(re.fullmatch(r"[a-f0-9]{40}", commit) and
                subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == commit and
                not subprocess.check_output(["git", "diff", commit, "--", *checks.INPUTS]) and
                not subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", *checks.INPUTS]),
                "The production or instrumentation inputs do not match the named candidate")
    history = root / "validation/raw/storage"
    prior = history / BASELINE
    manifest = prior / f"{BASELINE}-final-manifest.json"
    anchor = checks.settings_run_anchors(prior, BASELINE_SHA)
    artifacts = json.loads(anchor("artifacts.json"))
    claim = json.loads(anchor("claim.json"))
    snapshot = json.loads(manifest.read_bytes())["recordSnapshot"]
    guest = claim["name"]
    previous = {package: artifacts["proof"][package]["sha256"] for package in (checks.APP, checks.TEST)}
    checks.need(artifacts["guest"] == guest and artifacts["selectedAdvancedRecipe"] is True and
                json.loads(anchor("outcomes.json")) == {"passed": [checks.ADVANCED_RECIPE_CASE], "failed": [], "installedSha256": previous} and
                all(checks.sha(prior / f"{package}.apk") == digest for package, digest in previous.items()),
                "The installed predecessor must be the exact successful advanced pair")
    preserved = json.loads(anchor("selected-recipe-attempt.json"))
    report = json.loads(anchor("selected-recipe-readback.json"))

    paths = {checks.APP: root / "app/build/outputs/apk/debug/app-debug.apk",
             checks.TEST: root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
    pair = {checks.APP: app_sha, checks.TEST: test_sha}
    checks.need(shutil.disk_usage(root).free >= 12 * 1024**3 + sum(path.stat().st_size for path in paths.values()),
                "The retained guest and APK evidence need more disk space")
    inputs = root / "validation/raw/providers/ocr-app-standard-full"
    proof = {package: checks.exact_apk(path, pair[package], inputs, package == checks.TEST) for package, path in paths.items()}
    checks.require_independent_manifests(proof.values())
    prefix = "picker-adapter-selected-open-" + uuid.uuid4().hex[:12]
    raw = history / prefix
    raw.mkdir()
    saved = {package: raw / f"{package}.apk" for package in paths}
    for package, path in paths.items():
        shutil.copyfile(path, saved[package])
        checks.need(checks.sha(saved[package]) == pair[package], "Candidate changed during evidence retention")
    (raw / f"{prefix}-artifacts.json").write_text(
        json.dumps(dict(source=commit, proof=proof, previous=previous, predecessor=BASELINE_SHA, guest=guest)), encoding="ascii")
    ns = checks.load_allocator(root, guest, raw, "pristinepage-tree-" + os.environ["OPENCODE_SESSION_ID"])
    base = checks.run_type(ns, purpose="pristinepage-library-app-tree", operation="PristinePage selected catalog and frame",
                           admit_released=lambda record: checks.verify_bound_recipe_release(claim, record, manifest, snapshot))

    class OpenRun(base):
        def process(self, pid):
            return checks.inspect_prior_pid(super().process, pid)

    runner = OpenRun(prefix, None, 0)
    print(f"Selected Open evidence: {raw.relative_to(root)}", flush=True)
    try:
        checks.wait_for_allocator(runner, lambda: runner.prepare(False, None, False), before_claim=True)
        checks.recheck_target(runner, ns)
        checks.need(runner.owned()["api"] == 28, "The native preview fixture is specific to Android API 28")

        def adb(*args, **options):
            return runner.command([ns["ADB"], "-s", runner.serial, *args], **options)

        def check_protected():
            checks.recheck_target(runner, ns)
            checks.need(not checks.read_app_persisted_grants(adb) and "library-settings" not in checks.recipe_private_scopes(adb),
                        "The ordinary pointer or grant inventory changed")
            observed = readback(runner, adb, ns, artifacts["fixture"], preserved,
                                (report["catalogBytes"], report["catalogSha256"]), advanced=True)
            checks.need(observed["receiptSha256"] == report["boundReceiptSha256"] and observed["moveSpends"] == report["moveSpends"],
                        "The protected advanced receipt no longer matches")

        packages = adb("shell", "pm", "list", "packages", checks.APP).splitlines()
        checks.need(set(packages) == {f"package:{package}" for package in pair}, "The package inventory changed")
        installed = {package: runner.installed_hash(package) for package in pair}
        checks.need(installed == previous, "The installed pair does not match the exact stopped predecessor")
        checks.require_cold_backup_app(adb)
        check_protected()
        for package, path in saved.items():
            flags = checks.install_flags(package, installed[package], pair[package], previous[checks.TEST], previous[checks.APP])
            if flags is not None:
                checks.need(checks.sha(path) == pair[package], "Candidate changed before installation")
                result = adb("install", *flags, "--no-streaming", path, timeout=130)
                checks.need(result.strip().splitlines()[-1:] == ["Success"], "APK replacement did not complete")
            checks.need(runner.installed_hash(package) == pair[package], "The installed package differs")
        checks.need(adb("shell", "pm", "list", "instrumentation", checks.APP).splitlines() ==
                    [f"instrumentation:{checks.RUNNER} (target={checks.APP})"], "The instrumentation entry does not match")
        fingerprint = adb("shell", "getprop", "ro.build.fingerprint").strip()
        checks.need(re.fullmatch(r"[A-Za-z0-9_.+:/,-]{12,256}", fingerprint), "The exact Android build fingerprint is unavailable")
        policy = f"android-imagedecoder-v2/api28/{checks.sha256(fingerprint.encode()).hexdigest()[:16]}/srgb-straight-white-exif-v1"
        fixture = raw / "synthetic-library"
        files = checks.make_fixture(fixture, with_scan=True, conversion=policy)
        folder = "PristinePage-tree-" + uuid.uuid4().hex[:12]
        parent = f"/sdcard/Documents/{folder}"

        def admit_fixture():
            checks.recheck_target(runner, ns)
            checks.require_cold_backup_app(adb)
            checks.need(not checks.read_app_persisted_grants(adb), "A different Files grant appeared")

        checks.stage_independent_fixture_pair(adb, parent, folder, ((fixture, files), (fixture, files)), admit_fixture)
        runner.save(runner.path("selected-open-attempt.json"), dict(folder=folder, files=files, conversion=policy), durable=True)
        try:
            admit_fixture()
            output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", CASE,
                         "-e", "libraryFixtureFolder", folder, checks.RUNNER, timeout=190)
            runner.path("instrumentation.txt").write_text(output, encoding="utf-8")
            passed, failed = checks.read_results(output, (CASE,))
            runner.save(runner.path("outcomes.json"), dict(passed=passed, failed=failed, installedSha256=pair), durable=True)
        finally:
            checks.recheck_target(runner, ns)
            children = checks.independent_fixture_children(folder)
            checks.need(adb("shell", "ls", "-A", "-1", parent).splitlines() == sorted(children),
                        "The exact synthetic parent gained an unowned entry")
            for child in children:
                checks.verify_unchanged_settings_destination(adb, f"{parent}/{child}", files)
            check_protected()
            runner.save(runner.path("preservation.json"), dict(externalFixturesUnchanged=True, advancedReceiptUnchanged=True,
                                                              persistedGrantCount=0, ordinaryPointerAbsent=True), durable=True)
        print(f"Selected Files Open and original preview: {len(passed)} passed, {len(failed)} failed.", flush=True)
        return bool(failed)
    finally:
        if runner.claimed:
            checks.close_owned(runner)


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("commit")
    parser.add_argument("app_sha")
    parser.add_argument("test_sha")
    args = parser.parse_args()
    raise SystemExit(run_open(args.commit, args.app_sha, args.test_sha))
