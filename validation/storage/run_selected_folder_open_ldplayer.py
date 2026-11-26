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
from run_bound_recipe_readback_ldplayer import DETACHED, WRITER, WRITER_SHA256, readback


BASELINE = "picker-adapter-library-tree-08be480f914d"
BASELINE_SHA = "e25e685fa3e8aa5bb59f4c97fabb978ce4d506b0491ea660a2583d67da392996"
CONTENTION = "picker-adapter-selected-open-6a7fc64cfdfb"
CONTENTION_SHA = "9adc674e5eaad0defb01d2857384684431c3af4bf11e4cef227ef1120287d7d9"
STAGING_STOP = "picker-adapter-selected-open-cdcd541e5823"
STAGING_STOP_SHA = "bba85bee569bc935edc8ddffa78630f663b0a5402e7fd01060fe44ceb428cb78"
TEARDOWN_STOP = "picker-adapter-selected-open-8f4e46542f1e"
TEARDOWN_STOP_SHA = "6c28baa2732db0ecc835481d744820daed50aff77ca04b6159b4ee496a7b09ca"
FIRST_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#confirmedOpenSwitchesToTheSelectedFilesCatalogAndRevocationDoesNotFallBack"
CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#opensSelectedAlbumGridAndVerifiedOriginalWhileBrowsing"


def verify_teardown_failure(record, claim, prior_generation, folder, pair, outcome, preservation, transcript, trace):
    commands = [row for row in trace if "instrument" in row["argv"]]
    expected = ["-s", claim["serial"], "shell", "am", "instrument", "-w", "-r", "-e", "class", CASE,
                "-e", "libraryFixtureFolder", folder, checks.RUNNER]
    checks.need(record["predecessor"] == STAGING_STOP_SHA and record["reusedReadFixture"] is True and
                record["guest"] == claim["name"] and record["previous"] == pair and
                claim["ownership_history"][-1]["generation"] == prior_generation and
                outcome == dict(passed=[], failed=[CASE], installedSha256=pair) and
                preservation == dict(externalFixturesUnchanged=True, advancedReceiptUnchanged=True,
                                     persistedGrantCount=0, ordinaryPointerAbsent=True) and
                "java.lang.AssertionError: expected:<0> but was:<56>" in transcript and
                "LibraryFolderFilesJourneyTest.kt:1023" in transcript and
                len(commands) == 1 and commands[0]["argv"][1:] == expected and commands[0]["exit"] == 0 and
                not any({"install", "push"}.intersection(row["argv"]) for row in trace),
                "The exact read-only teardown predecessor is not verified")


def run_open(commit, app_sha, test_sha, build_receipt, *, after_contention=False, reuse_read_fixture=False, after_teardown=False):
    root = checks.ROOT
    checks.need(__debug__ and Path.cwd().resolve() == root and not any(os.environ.get(key) for key in checks.LOCAL_ADB_OVERRIDES),
                "Use the exact worktree and the ordinary scoped transport")
    checks.need(re.fullmatch(r"[a-f0-9]{40}", commit), "The candidate must name a complete source commit")
    if not reuse_read_fixture:
        checks.need(subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == commit and
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
    earlier_bound = checks.settings_run_anchors(history / WRITER, WRITER_SHA256)
    earlier_detached = checks.settings_run_anchors(history / DETACHED, checks.DETACHED_RECIPE_PASSED)
    cold_bound = checks.settings_run_anchors(history / "picker-adapter-library-tree-2e4518656e0a", checks.BOUND_RECIPE_READBACK)
    bound_report = json.loads(cold_bound("bound-recipe-readback.json"))
    detached_baseline = (json.loads(earlier_detached("artifacts.json"))["fixture"],
                         json.loads(earlier_detached("selected-recipe-attempt.json")),
                         json.loads(earlier_detached("selected-recipe-readback.json")))
    bound_baseline = (json.loads(earlier_bound("artifacts.json"))["fixture"],
                      json.loads(earlier_bound("selected-recipe-attempt.json")),
                      (bound_report["catalogBytes"], bound_report["catalogSha256"]))
    failed_fixture = None
    untouched_pending = None
    predecessor = BASELINE_SHA
    if after_contention or reuse_read_fixture or after_teardown:
        failed = history / CONTENTION
        latest_manifest = failed / f"{CONTENTION}-final-manifest.json"
        latest = checks.settings_run_anchors(failed, CONTENTION_SHA)
        recorded = json.loads(latest("artifacts.json"))
        latest_claim = json.loads(latest("claim.json"))
        latest_pair = {package: recorded["proof"][package]["sha256"] for package in previous}
        checks.need(recorded["predecessor"] == BASELINE_SHA and recorded["previous"] == previous and
                    recorded["guest"] == guest == latest_claim["name"] and
                    latest_claim["ownership_history"][-1]["generation"] == claim["generation"] and
                    "A library working catalog is already open" in latest("instrumentation.txt") and
                    json.loads(latest("outcomes.json")) == dict(passed=[], failed=[FIRST_CASE], installedSha256=latest_pair) and
                    json.loads(latest("preservation.json")) == dict(externalFixturesUnchanged=True, advancedReceiptUnchanged=True,
                                                                   persistedGrantCount=0, ordinaryPointerAbsent=True) and
                    all(checks.sha(failed / f"{package}.apk") == digest for package, digest in latest_pair.items()),
                    "The named prior contention case is not a verified nonwriting failure")
        failed_fixture = json.loads(latest("selected-open-attempt.json"))
        previous = latest_pair
        manifest, claim = latest_manifest, latest_claim
        snapshot = json.loads(manifest.read_bytes())["recordSnapshot"]
        predecessor = CONTENTION_SHA
    if reuse_read_fixture or after_teardown:
        stopped = history / STAGING_STOP
        staged = checks.settings_run_anchors(stopped, STAGING_STOP_SHA)
        record = json.loads(staged("artifacts.json"))
        stopped_claim = json.loads(staged("claim.json"))
        stopped_pair = {package: record["proof"][package]["sha256"] for package in previous}
        pending = json.loads(staged("selected-open-attempt.json"))
        folder = pending["folder"]
        left = checks.independent_fixture_children(folder)[0]
        trace = [json.loads(line) for line in staged("commands.jsonl").splitlines()]
        pushes = [row for row in trace if "push" in row["argv"]]
        first_file = f"/sdcard/Documents/{folder}/{left}/" + next(path for path in pending["files"] if path.startswith("catalogs/"))
        checks.need((after_teardown or (commit == record["source"] and {checks.APP: app_sha, checks.TEST: test_sha} == stopped_pair)) and
                    record["predecessor"] == CONTENTION_SHA and record["previous"] == previous and
                    record["guest"] == guest == stopped_claim["name"] and
                    stopped_claim["ownership_history"][-1]["generation"] == claim["generation"] and
                    len(pushes) == 1 and pushes[0].get("exit") is None and pushes[0]["argv"][-1] == first_file and
                    not any("instrument" in row["argv"] for row in trace) and
                    all(checks.sha(stopped / f"{package}.apk") == digest for package, digest in stopped_pair.items()),
                    "The earlier transport refusal cannot be distinguished from a spent instrumentation attempt")
        untouched_pending = folder
        previous = stopped_pair
        manifest = stopped / f"{STAGING_STOP}-final-manifest.json"
        claim = stopped_claim
        snapshot = json.loads(manifest.read_bytes())["recordSnapshot"]
        predecessor = STAGING_STOP_SHA
    if after_teardown:
        stopped = history / TEARDOWN_STOP
        failed = checks.settings_run_anchors(stopped, TEARDOWN_STOP_SHA)
        record = json.loads(failed("artifacts.json"))
        released = json.loads(failed("claim.json"))
        fixture = json.loads(failed("selected-open-attempt.json"))
        checks.need(released["name"] == guest and fixture == failed_fixture | {"siblingFiles": failed_fixture["files"]} and
                    {package: record["proof"][package]["sha256"] for package in previous} == previous and
                    all(checks.sha(stopped / f"{package}.apk") == digest for package, digest in previous.items()),
                    "The teardown predecessor does not bind the complete fixture and installed pair")
        verify_teardown_failure(record, released, claim["generation"], fixture["folder"], previous,
                                json.loads(failed("outcomes.json")), json.loads(failed("preservation.json")),
                                failed("instrumentation.txt"), [json.loads(line) for line in failed("commands.jsonl").splitlines()])
        manifest = stopped / f"{TEARDOWN_STOP}-final-manifest.json"
        claim = released
        snapshot = json.loads(manifest.read_bytes())["recordSnapshot"]
        predecessor = TEARDOWN_STOP_SHA

    paths = {checks.APP: root / "app/build/outputs/apk/debug/app-debug.apk",
             checks.TEST: root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
    if reuse_read_fixture:
        paths = {package: history / STAGING_STOP / f"{package}.apk" for package in paths}
    pair = {checks.APP: app_sha, checks.TEST: test_sha}
    receipt = json.loads(build_receipt.read_bytes())
    checks.need(receipt == dict(source=commit, sha256=pair),
                "The retained exact build receipt does not bind this source and APK pair")
    checks.need(shutil.disk_usage(root).free >= 12 * 1024**3 + sum(path.stat().st_size for path in paths.values()),
                "The retained guest and APK evidence need more disk space")
    inputs = root / "validation/raw/providers/ocr-app-standard-full"
    proof = {package: checks.exact_apk(path, pair[package], inputs, package == checks.TEST) for package, path in paths.items()}
    checks.need(reuse_read_fixture or pair[checks.TEST] != previous[checks.TEST], "The selected preview requires the current test APK, not the old test")
    checks.require_independent_manifests(proof.values())
    prefix = "picker-adapter-selected-open-" + uuid.uuid4().hex[:12]
    raw = history / prefix
    raw.mkdir()
    saved = {package: raw / f"{package}.apk" for package in paths}
    for package, path in paths.items():
        shutil.copyfile(path, saved[package])
        checks.need(checks.sha(saved[package]) == pair[package], "Candidate changed during evidence retention")
    (raw / f"{prefix}-artifacts.json").write_text(
        json.dumps(dict(source=commit, proof=proof, previous=previous,
                         predecessor=predecessor, guest=guest, reusedReadFixture=reuse_read_fixture or after_teardown)), encoding="ascii")
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
            bound = readback(runner, adb, ns, *bound_baseline)
            checks.need(bound == {key: bound_report[key] for key in bound}, "The earlier bound edit changed")
            checks.preserve_detached_recipe(adb, *detached_baseline)
            if failed_fixture:
                old_folder = failed_fixture["folder"]
                for child in checks.independent_fixture_children(old_folder):
                    checks.verify_unchanged_settings_destination(adb, f"/sdcard/Documents/{old_folder}/{child}", failed_fixture["files"])

        packages = adb("shell", "pm", "list", "packages", checks.APP).splitlines()
        checks.need(set(packages) == {f"package:{package}" for package in pair}, "The package inventory changed")
        installed = {package: runner.installed_hash(package) for package in pair}
        checks.need(installed == previous, "The installed pair does not match the exact stopped predecessor")
        checks.require_cold_backup_app(adb)
        check_protected()
        # Run the protected-witness checks even when installation or fixture setup stops partway through.
        # No command may rewrite or adopt the uncertain partial staging from the transport refusal.
        if untouched_pending:
            checks.need(failed_fixture["folder"] != untouched_pending, "The retained complete and uncertain partial fixtures overlap")
            pending_root = f"/sdcard/Documents/{untouched_pending}"
            pending_before = adb("shell", "stat", "-c", "%d:%i:%s:%Y", pending_root).strip()
            checks.need(re.fullmatch(r"[0-9]+:[1-9][0-9]*:[0-9]+:[0-9]+", pending_before),
                        "The retained partial fixture directory is unavailable")
        try:
            passed, failed = exercise(runner, adb, ns, raw, saved, pair, previous, installed,
                                      failed_fixture if reuse_read_fixture or after_teardown else None)
        finally:
            check_protected()
            if untouched_pending:
                checks.need(adb("shell", "stat", "-c", "%d:%i:%s:%Y", pending_root).strip() == pending_before,
                            "The retained partial fixture directory changed during the read-only batch")
        runner.save(runner.path("preservation.json"), dict(externalFixturesUnchanged=True, advancedReceiptUnchanged=True,
                                                          persistedGrantCount=0, ordinaryPointerAbsent=True), durable=True)
        print(f"Selected Files Open and original preview: {len(passed)} passed, {len(failed)} failed; preservation verified.", flush=True)
        return bool(failed)
    finally:
        if runner.claimed:
            checks.close_owned(runner)


def exercise(runner, adb, ns, raw, saved, pair, previous, installed, read_fixture):
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
    def admit_fixture():
        checks.recheck_target(runner, ns)
        checks.require_cold_backup_app(adb)
        checks.need(not checks.read_app_persisted_grants(adb), "A different Files grant appeared")

    if read_fixture:
        checks.need(read_fixture["conversion"] == policy, "The retained complete fixture belongs to another decode policy")
        folder, files = read_fixture["folder"], read_fixture["files"]
        sibling_files = files
    else:
        fixture = raw / "synthetic-library"
        files = checks.make_fixture(fixture, with_scan=True, conversion=policy)
        folder = "PristinePage-tree-" + uuid.uuid4().hex[:12]
        sibling = raw / "synthetic-sibling"
        sibling_files = checks.make_fixture(sibling, with_scan=True, alternate_scan=True, conversion=policy)
    parent = f"/sdcard/Documents/{folder}"
    runner.save(runner.path("selected-open-attempt.json"), dict(folder=folder, files=files, siblingFiles=sibling_files, conversion=policy), durable=True)
    if not read_fixture:
        checks.stage_independent_fixture_pair(adb, parent, folder, ((fixture, files), (sibling, sibling_files)), admit_fixture)
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
        for child, expected in zip(children, (files, sibling_files)):
            checks.verify_unchanged_settings_destination(adb, f"{parent}/{child}", expected)
    return passed, failed


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("commit")
    parser.add_argument("app_sha")
    parser.add_argument("test_sha")
    parser.add_argument("--build-receipt", required=True, type=Path)
    resume = parser.add_mutually_exclusive_group()
    resume.add_argument("--after-contention", action="store_true", help="Require the exact stopped no-write contention predecessor")
    resume.add_argument("--reuse-read-fixture", action="store_true", help="Use the installed pair from the exact staging timeout and read the earlier complete fixture without reinstalling or staging new files")
    resume.add_argument("--after-teardown", action="store_true", help="Use the exact teardown failure as the immediate predecessor without staging new files")
    args = parser.parse_args()
    raise SystemExit(run_open(args.commit, args.app_sha, args.test_sha, args.build_receipt,
                              after_contention=args.after_contention, reuse_read_fixture=args.reuse_read_fixture, after_teardown=args.after_teardown))
