"""Run the no-write Settings picker control on its exact released pre-selection fixture."""

from argparse import ArgumentParser
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid

import run_library_folder_picker_ldplayer as checks


CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#mainSettingsCancelsTheExactHintedFilesLocationWithoutChangingTheLibrary"
TEST_SOURCE = "app/src/androidTest/kotlin/app/pristinepage/LibraryFolderFilesJourneyTest.kt"
PREDECESSOR_MANIFEST = "cb7bbbb4e67044d15176e4660a73c7b93b77ef8561c23baf07565920e81a735e"
DIRECT_REFUSAL_MANIFEST = "2130affcbd7cccf44de1ba1da85d25c443f3a8b495f5118c048b07b33b8123ff"
DIRECT_PASSED_MANIFEST = "3275e564e13a387491ae5c2db11718c26a1852479f3f112e0604393ffd1a833b"
RECOVERY_PASSED_MANIFEST = "ea9f0a1c69708ec6fa2f3e301882cc132afad77a9e7cef4c07afa30eb170f0cb"


def prior_control_release(manifest, history, guest, fixture, protected, *, passed):
    manifest = manifest.resolve(strict=True)
    raw = manifest.parent
    checks.need(raw.parent == history.resolve() and manifest.name == f"{raw.name}-final-manifest.json",
                "Require the exact local Settings control release")
    digest = DIRECT_PASSED_MANIFEST if passed else DIRECT_REFUSAL_MANIFEST
    anchor = checks.settings_run_anchors(raw, digest)
    artifacts, claim, outcomes = (json.loads(anchor(leaf)) for leaf in ("artifacts.json", "claim.json", "outcomes.json"))
    pair = {package: item["sha256"] for package, item in artifacts["proof"].items()}
    trace = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    launched = [entry for entry in trace if "instrument" in entry["argv"]]
    output = anchor("instrumentation.txt")
    expected = ([CASE], []) if passed else ([], [CASE])
    checks.need(artifacts["guest"] == claim["name"] == guest and artifacts["predecessor"] == PREDECESSOR_MANIFEST
                and artifacts["fixture"] == fixture and artifacts["protected"] == protected
                and outcomes == dict(passed=expected[0], failed=expected[1], installedSha256=pair, pointerGrantsAndBothFixturesUnchanged=True)
                and checks.read_results(output, (CASE,)) == expected
                and (passed or "Files did not reach exact destination before cancellation; foreground=app.pristinepage.debug;" in output)
                and len(launched) == 1 and launched[0]["exit"] == 0 and launched[0]["stdout"] == output
                and launched[0]["argv"][1:] == ["-s", claim["serial"], "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                               CASE, "-e", "libraryFixtureFolder", fixture["folder"], checks.RUNNER]
                and all("push" not in row["argv"] for row in trace), "The last control did not prove no-write preservation")
    return manifest, digest, claim, pair, artifacts["testSourceSha256"]


def prior_recovery_release(manifest, history, guest, fixture, protected):
    manifest = manifest.resolve(strict=True)
    raw = manifest.parent
    checks.need(raw.parent == history.resolve() and manifest.name == f"{raw.name}-final-manifest.json",
                "Require the exact local pointer recovery release")
    anchor = checks.settings_run_anchors(raw, RECOVERY_PASSED_MANIFEST)
    artifacts, claim, result, attempt, readback = (json.loads(anchor(leaf)) for leaf in
        ("artifacts.json", "claim.json", "pointer-recovery-outcomes.json", "selected-recipe-attempt.json", "selected-recipe-readback.json"))
    pair = {package: item["sha256"] for package, item in artifacts["proof"].items()}
    trace = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    launched = [entry for entry in trace if "instrument" in entry["argv"]]
    output = anchor("selected-recipe-instrumentation.txt")
    checks.need(artifacts["guest"] == claim["name"] == guest and artifacts["predecessor"] == PREDECESSOR_MANIFEST
                and artifacts["immediateRelease"] == DIRECT_PASSED_MANIFEST and artifacts["continuedRecovery"]
                and artifacts["fixture"] == fixture and artifacts["protected"] == protected
                and result == dict(passed=[checks.BOUND_RECIPE_CASE], failed=[], installedSha256=pair)
                and checks.read_results(output, (checks.BOUND_RECIPE_CASE,)) == ([checks.BOUND_RECIPE_CASE], [])
                and len(launched) == 1 and launched[0]["exit"] == 0 and launched[0]["stdout"] == output
                and launched[0]["argv"][1:] == ["-s", claim["serial"], "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                               checks.BOUND_RECIPE_CASE, "-e", "libraryFixtureFolder", attempt["folder"], "-e",
                                               "librarySelectedAppendOperation", attempt["operation"], "-e",
                                               "libraryRecoverSelectedRecipePointer", "true", checks.RUNNER]
                and not any("install" in row["argv"] for row in trace),
                "The recovery release did not prove the exact Settings-preserving isolated edit")
    synthetic = raw / "synthetic-recovery-library"
    checks.need(all(checks.sha(synthetic / leaf) == digest for leaf, digest in fixture["inventory"].items()),
                "The host's exact predecessor catalog or objects changed")
    return manifest, claim, pair, synthetic, attempt, readback


def run(app_sha, test_sha, manifest, predecessor_sha, *, qualify_pointer_recovery=False,
        prior_direct_refusal=None, prior_direct_pass=None, prior_recovery_pass=None):
    root = checks.ROOT
    checks.need(__debug__ and Path.cwd().resolve() == root
                and not any(os.environ.get(key) for key in checks.LOCAL_ADB_OVERRIDES),
                "Require the exact worktree and default scoped ADB transport")
    history = root / "validation/raw/storage"
    manifest = manifest.resolve(strict=True)
    previous_run = manifest.parent
    checks.need(previous_run.parent == history.resolve() and manifest.name == f"{previous_run.name}-final-manifest.json" and
                predecessor_sha == PREDECESSOR_MANIFEST, "Require the exact retained pre-selection release manifest")
    anchor = checks.settings_run_anchors(previous_run, predecessor_sha)
    artifacts, claim, fixture, boundary = (json.loads(anchor(name)) for name in
        ("artifacts.json", "claim.json", "settings-open-fixture.json", "settings-apply-boundary.json"))
    read_only = checks.settings_run_anchors(history / checks.SETTINGS_OPEN_PASS_RUN, checks.SETTINGS_OPEN_PASS_MANIFEST)
    protected = json.loads(read_only("settings-open-fixture.json"))
    guest, folder = checks.SETTINGS_OPEN_CANCELED_GUEST, fixture["folder"]
    checks.need(guest == claim["name"] == artifacts["guest"] and folder != protected["folder"] and
                all(re.fullmatch(r"PristinePage-tree-[0-9a-f]{12}", f["folder"]) for f in (fixture, protected)) and
                boundary["instrumentationTerminated"] and not boundary["observationErrors"] and boundary["predecessorFilesUnchanged"] and
                boundary["pointer"]["activation"] == "3" and boundary["grants"] == {"count": 1, "priorUnchanged": True},
                "The earlier refusal does not prove an untouched Default selection")
    trace = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    grant_reads = [checks.parse_app_persisted_grants(call["stdout"]) for call in trace
                   if call["exit"] == 0 and call["argv"][1:4] == ["-s", claim["serial"], "shell"]
                   and len(call["argv"]) == 5 and call["argv"][4].startswith(f"set -o pipefail; dumpsys -t 10 activity -a -p {checks.APP} | ")]
    checks.need(len(grant_reads) == 3 and all(row == grant_reads[0] for row in grant_reads),
                "The retained run did not observe equal exact grants across selection")
    expected_grants = grant_reads[-1]
    checks.require_settings_apply_grants(expected_grants, expected_grants, protected["folder"])
    checks.need(checks.sha(manifest) == predecessor_sha, "The retained release manifest changed")
    recovery_authority = sha256(("selected-pointer-recovery-v1:" + guest).encode("ascii")).hexdigest()
    if qualify_pointer_recovery:
        checks.need(not (history / f"settings-retry-spent-{recovery_authority}.json").exists(),
                    "The selected-pointer recovery already has a spent operation; reconcile it without another write")
    paths = {checks.APP: root / "app/build/outputs/apk/debug/app-debug.apk",
             checks.TEST: root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
    pair = dict(zip(paths, (app_sha, test_sha)))
    previous = {package: artifacts["proof"][package]["sha256"] for package in paths}
    release_digest = predecessor_sha
    source_sha = checks.sha(root / TEST_SOURCE)
    continued_recovery = prior_direct_pass is not None
    apply_edit = prior_recovery_pass is not None
    checks.need(not continued_recovery or (qualify_pointer_recovery and prior_direct_refusal is None),
                "A passed control authorizes only the unstarted recovery continuation")
    checks.need(not apply_edit or not (qualify_pointer_recovery or continued_recovery or prior_direct_refusal),
                "Foreground Apply is a separate, one-use instrumentation case")
    if continued_recovery or prior_direct_refusal is not None:
        manifest, release_digest, claim, previous, prior_source = prior_control_release(
            prior_direct_pass if continued_recovery else prior_direct_refusal, history, guest, fixture, protected, passed=continued_recovery)
        checks.need(pair[checks.APP] == previous[checks.APP], "The no-write diagnostic must retain the exact app APK")
        if continued_recovery:
            checks.need(pair == previous and source_sha == prior_source, "Recovery-only requires the unchanged source and APK pair")
    if apply_edit:
        manifest, claim, previous, predecessor_files, recovery_attempt, recovery_readback = prior_recovery_release(
            prior_recovery_pass, history, guest, fixture, protected)
        release_digest = RECOVERY_PASSED_MANIFEST
        apply_authority = sha256(f"settings-direct-apply-v1:{guest}:{folder}".encode("ascii")).hexdigest()
        checks.need(pair[checks.APP] == previous[checks.APP] and
                    not (history / f"settings-retry-spent-{apply_authority}.json").exists(),
                    "Qualified Apply needs the same app and an unspent exact destination")
    snapshot = json.loads(manifest.read_bytes())["recordSnapshot"]
    checks.need(checks.sha(manifest) == release_digest, "The retained release manifest changed")
    additional_apk_bytes = 0 if continued_recovery else sum(path.stat().st_size for path in paths.values())
    checks.need((continued_recovery or pair[checks.TEST] != previous[checks.TEST]) and
                shutil.disk_usage(root).free >= 12 * 1024**3 + additional_apk_bytes,
                "The one-case APK and its retained disk budget are not available")
    stage = root / "validation/raw/providers/ocr-app-standard-full"
    proof = {package: checks.exact_apk(path, pair[package], stage, package == checks.TEST) for package, path in paths.items()}
    checks.require_independent_manifests(proof.values())
    prefix = "picker-adapter-settings-direct-" + uuid.uuid4().hex[:12]
    raw = history / prefix
    raw.mkdir()
    saved = {package: (manifest.parent if continued_recovery else raw) / f"{package}.apk" for package in paths}
    for package, path in paths.items():
        if not continued_recovery:
            shutil.copyfile(path, saved[package])
        checks.need(checks.sha(saved[package]) == pair[package], "APK changed while retaining evidence")
    ns = checks.load_allocator(root, guest, raw, "pristinepage-tree-" + os.environ["OPENCODE_SESSION_ID"])
    base = checks.run_type(ns, purpose="pristinepage-library-app-tree",
                           operation="Settings direct Files Apply" if apply_edit else "Settings direct Files control/recovery",
                           admit_released=lambda record: checks.verify_bound_recipe_release(claim, record, manifest, snapshot))

    class DirectRun(base):
        def process(self, pid):
            return checks.inspect_prior_pid(super().process, pid)

    source = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
    # The shared runner reads this receipt in its constructor, before a claim or a device command is allowed.
    with (raw / f"{prefix}-artifacts.json").open("x", encoding="utf-8") as out:
        json.dump(dict(guest=guest, proof=proof, previous=previous, fixture=fixture, protected=protected,
                       predecessor=predecessor_sha, immediateRelease=release_digest, sourceBase=source,
                       testSourceSha256=source_sha, continuedRecovery=continued_recovery),
                  out, indent=2)
        out.write("\n")
        out.flush()
        os.fsync(out.fileno())
    runner = DirectRun(prefix, None, 0)
    print(f"Runtime evidence: {raw.relative_to(root)}", flush=True)
    try:
        checks.wait_for_allocator(runner, lambda: runner.prepare(False, None, False), before_claim=True)
        checks.recheck_target(runner, ns)
        checks.need(runner.owned()["api"] == 28, "The exact direct Files control requires API 28")

        def adb(*args, **options):
            return runner.command([ns["ADB"], "-s", runner.serial, *args], **options)

        def observe(*, changing_fixture=False):
            checks.recheck_target(runner, ns)
            for item in ((protected,) if changing_fixture else (fixture, protected)):
                checks.verify_unchanged_settings_destination(adb, f"/sdcard/Documents/{item['folder']}", item["inventory"])
            if not changing_fixture:
                pointer = checks.read_selected_history_record(lambda *args: adb("shell", "run-as", checks.APP, *args),
                                                              "files/library-settings/active-library", 320)
                checks.need(sha256(pointer.encode("ascii")).hexdigest() == boundary["pointer"]["sha256"], "Default pointer changed")
            grants = checks.read_app_persisted_grants(adb)
            checks.require_settings_apply_grants(grants, expected_grants, protected["folder"])
            return grants

        checks.require_cold_backup_app(adb)
        present = set(adb("shell", "pm", "list", "packages", checks.APP).splitlines())
        checks.need(present == {f"package:{package}" for package in paths}
                    and {p: runner.installed_hash(p) for p in paths} == previous, "The exact predecessor packages are not installed")
        grants = observe()
        if apply_edit:
            apply_preflight = prepare_hinted_apply(adb, fixture, predecessor_files, recovery_attempt, recovery_readback)
        for package, path in saved.items():
            flags = checks.install_flags(package, previous[package], pair[package], previous[checks.TEST], previous[checks.APP])
            if flags is not None:
                checks.need(checks.sha(path) == pair[package], "APK changed before installation")
                checks.need(adb("install", *flags, "--no-streaming", path, timeout=130).strip().splitlines()[-1:] == ["Success"],
                            "Scoped APK update did not complete")
            checks.need(runner.installed_hash(package) == pair[package], "Installed APK differs")
        checks.need(adb("shell", "pm", "list", "instrumentation", checks.APP).splitlines() ==
                    [f"instrumentation:{checks.RUNNER} (target={checks.APP})"], "The instrumentation target differs")
        checks.require_cold_backup_app(adb)
        checks.need(observe() == grants, "Package update changed the starting grant")
        if apply_edit:
            return exercise_hinted_apply(runner, adb, ns, history, fixture, protected, predecessor_files, apply_authority,
                                         apply_preflight, observe)
        failed = []
        if not continued_recovery:
            try:
                output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", CASE,
                             "-e", "libraryFixtureFolder", folder, checks.RUNNER, timeout=110)
                runner.path("instrumentation.txt").write_text(output, encoding="utf-8")
                passed, failed = checks.read_results(output, (CASE,))
            finally:
                checks.need(observe() == grants, "The control changed the starting grant")
            runner.save(runner.path("outcomes.json"), dict(passed=passed, failed=failed, installedSha256=pair,
                                                           pointerGrantsAndBothFixturesUnchanged=True), durable=True)
            print(f"Settings direct Files: {len(passed)} passed, {len(failed)} failed; unchanged roots verified.", flush=True)
        if failed:
            # Read only relevant package activity events from this guest; do not clear its logs or expose unrelated activities.
            events = adb("shell", f"set -o pipefail; logcat -b events -t 300 -s am_create_activity am_resume_activity am_finish_activity "
                         f"| grep -E '({checks.APP.replace('.', '[.]')}|com[.]android[.]documentsui)'", timeout=15, allowed=(0, 1))
            runner.path("settings-launch-events.txt").write_text(events, encoding="utf-8")
        if not failed and qualify_pointer_recovery:
            # Instrumentation may publish its terminal status before Android finishes tearing down its processes.
            if not continued_recovery:
                deadline = time.monotonic() + 8
                while adb("shell", "pidof", checks.APP, checks.TEST, allowed=(0, 1)).strip() and time.monotonic() < deadline:
                    time.sleep(0.25)
            checks.require_cold_backup_app(adb)
            fingerprint = adb("shell", "getprop", "ro.build.fingerprint").strip()
            checks.need(re.fullmatch(r"[A-Za-z0-9_.+:/,-]{12,256}", fingerprint), "Missing exact Android build fingerprint")
            policy = f"android-imagedecoder-v2/api28/{sha256(fingerprint.encode()).hexdigest()[:16]}/srgb-straight-white-exif-v1"
            synthetic = raw / "synthetic-recovery-library"
            files = checks.make_fixture(synthetic, with_scan=True, conversion=policy)
            try:
                recovery = checks.exercise_selected_recipe_edit(runner, adb, ns, raw, synthetic, files, pair, bound=True,
                                                               recovery_authority=recovery_authority)
            finally:
                checks.need(observe() == grants, "Recovery changed the ordinary selection, starting grant or protected folders")
            runner.save(runner.path("pointer-recovery-outcomes.json"), recovery, durable=True)
            print(f"Selected pointer recovery: {len(recovery['passed'])} passed, {len(recovery['failed'])} failed.", flush=True)
            return bool(recovery["failed"])
        return bool(failed)
    finally:
        if runner.claimed:
            checks.close_owned(runner)


def prepare_hinted_apply(adb, fixture, predecessor_files, recovery_attempt, recovery_readback):
    folder, inventory = fixture["folder"], fixture["inventory"]
    def private(*args):
        return adb("shell", "run-as", checks.APP, *args)

    def require_prior_recovery():
        recovery_folder = recovery_attempt["folder"]
        operation = recovery_attempt["operation"]
        prior = f"/sdcard/Documents/{recovery_folder}"
        children = checks.independent_fixture_children(recovery_folder)
        left, right = (f"{prior}/{name}" for name in children)
        marker_leaf, catalog_leaf = f"generations/{operation}.complete", f"catalogs/{operation}.sqlite"
        initial_leaf, = (leaf for leaf in inventory if leaf.startswith("generations/"))
        initial = (predecessor_files / initial_leaf).read_text(encoding="ascii")
        marker = checks.read_selected_history_record(lambda *args: adb("shell", *args), f"{left}/{marker_leaf}", 256)
        digest = recovery_readback["catalogSha256"]
        stage = f"merge-staging-{operation}"
        scope = f"files/library-selected-editor-{operation}"
        attempt = f"{scope}/catalog-recipe-attempts-v1/{operation}"
        checks.need(operation == recovery_readback["generation"] and marker.splitlines()[2:4] == [operation, recovery_readback["predecessor"]]
                    and marker.splitlines()[-2:] == [str(recovery_readback["catalogBytes"]), digest]
                    and adb("shell", "ls", "-A", "-1", prior).splitlines() == sorted(children),
                    "The earlier isolated recovery fixture changed")
        checks.verify_unchanged_settings_destination(adb, right, inventory)
        checks.verify_unchanged_settings_destination(adb, left, inventory | {
            marker_leaf: sha256(marker.encode("ascii")).hexdigest(), catalog_leaf: digest}, backup_name=stage)
        spend = checks.read_selected_history_record(private, f"{scope}/catalog-revision-spends/{operation}.spent", 1024)
        extras = {"catalog.sqlite", "recipe-edit.intent", "recipe-edit.complete", "recipe-edit.catalog-owner"}
        leaves = set(private("ls", "-A", "-1", attempt).splitlines())
        checks.need(not adb("shell", "ls", "-A", "-1", f"{left}/{stage}").strip()
                    and sha256(spend.encode("ascii")).hexdigest() == recovery_readback["revisionSpendSha256"]
                    and checks.read_bound_recipe_evidence(adb, scope, attempt, left, initial, marker, digest, advanced=True)
                    == recovery_readback["boundReceiptSha256"]
                    and checks.verify_library_move_spends(adb, attempt, leaves - extras, {
                        catalog_leaf: (recovery_readback["catalogBytes"], digest),
                        marker_leaf: (len(marker.encode("ascii")), sha256(marker.encode("ascii")).hexdigest()),
                    }) == recovery_readback["moveSpends"], "The earlier isolated recovery receipt changed")
        return True

    settings = "files/library-settings"
    pointer_path = f"{settings}/active-library"
    pointer = checks.read_selected_history_record(private, pointer_path, 320)
    origin = checks.read_selected_history_record(private, f"{settings}/default-origin", 320)
    default_id = checks.require_returned_settings_pointer(pointer, origin, 3)
    checks.require_settings_apply_private_absence(private("ls", "-A", "-1", "files").splitlines(),
                                                  private("ls", "-A", "-1", settings).splitlines(), folder)
    fingerprint = adb("shell", "getprop", "ro.build.fingerprint").strip()
    policy = f"android-imagedecoder-v2/api28/{sha256(fingerprint.encode()).hexdigest()[:16]}/srgb-straight-white-exif-v1"
    checks.need(policy == fixture["conversion"], "The retained Settings original has a different native conversion")
    require_prior_recovery()
    return default_id, require_prior_recovery


def exercise_hinted_apply(runner, adb, ns, history, fixture, protected, predecessor_files, authority, preflight, observe):
    folder, inventory = fixture["folder"], fixture["inventory"]
    destination = f"/sdcard/Documents/{folder}"
    default_id, require_prior_recovery = preflight
    def private(*args):
        return adb("shell", "run-as", checks.APP, *args)
    settings = "files/library-settings"
    pointer_path = f"{settings}/active-library"
    checks.require_settings_apply_private_absence(private("ls", "-A", "-1", "files").splitlines(),
                                                  private("ls", "-A", "-1", settings).splitlines(), folder)
    checks.recheck_target(runner, ns)
    checks.spend_settings_retry(history, authority, dict(destination=folder, kind="settings-direct-apply"))
    terminated = False
    try:
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", checks.MAIN_SETTINGS_APPLY_CASE,
                     "-e", "libraryFixtureFolder", folder, "-e", "librarySettingsOpenFixture", "apply",
                     "-e", "librarySettingsPriorFolder", protected["folder"], "-e", "librarySettingsExactHint", "true",
                     checks.RUNNER, timeout=260)
        runner.path("settings-apply-instrumentation.txt").write_text(output, encoding="utf-8")
        passed, failed = checks.read_results(output, (checks.MAIN_SETTINGS_APPLY_CASE,))
        terminated = True
        runner.save(runner.path("settings-apply-junit.json"), dict(passed=passed, failed=failed), durable=True)
    finally:
        def predecessor_unchanged():
            for leaf, digest in inventory.items():
                path = f"{destination}/{leaf}"
                checks.need(adb("shell", "sha256sum", path).split() == [digest, path], "Apply changed a retained predecessor file")
            return True
        def pointer_proof():
            actual = checks.read_selected_history_record(private, pointer_path, 320)
            return dict(activation=actual.splitlines()[2], sha256=sha256(actual.encode("ascii")).hexdigest())
        def no_edit_evidence():
            directory = lambda p: private("ls", "-A", "-1", p).splitlines()
            checks.require_settings_apply_private_absence(directory("files"), directory(settings), folder)
            return True
        def unchanged_before_publication():
            return bool(observe()) and no_edit_evidence()
        observations = dict(
            pointer=pointer_proof,
            generationMarkers=lambda: adb("shell", "ls", "-A", "-1", f"{destination}/generations").splitlines(),
            startingGrantAndProtectedFixture=lambda: bool(observe(changing_fixture=True)),
            predecessorFilesUnchanged=predecessor_unchanged,
            isolatedRecoveryUnchanged=require_prior_recovery,
        )
        if not terminated or failed:
            observations["unchangedBeforePublication"] = unchanged_before_publication
        def checked(observe_result):
            checks.recheck_target(runner, ns)
            return observe_result()
        boundary = checks.collect_settings_apply_boundary(terminated, {
            name: lambda observe_result=observe_result: checked(observe_result)
            for name, observe_result in observations.items()
        })
        runner.save(runner.path("settings-apply-boundary.json"), boundary, durable=True)
    if not failed:
        checks.need(not boundary["observationErrors"] and boundary["startingGrantAndProtectedFixture"]
                    and boundary["predecessorFilesUnchanged"] and boundary["isolatedRecoveryUnchanged"],
                    "Apply did not prove preservation")
        checks.verify_settings_apply_destination(runner, adb, destination, predecessor_files, inventory, default_id)
    print(f"Settings foreground Apply: {len(passed)} passed, {len(failed)} failed.", flush=True)
    return bool(failed)


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("app_sha")
    parser.add_argument("test_sha")
    parser.add_argument("manifest", type=Path)
    parser.add_argument("manifest_sha")
    continuation = parser.add_mutually_exclusive_group()
    continuation.add_argument("--prior-direct-refusal", type=Path, help="The exact immediately preceding unchanged no-write refusal")
    continuation.add_argument("--prior-direct-pass", type=Path, help="Reuse the exact completed control and run only the unstarted recovery case")
    continuation.add_argument("--prior-recovery-pass", type=Path, help="Run one foreground Apply on the untouched Settings fixture after isolated qualification")
    parser.add_argument("--qualify-pointer-recovery", action="store_true",
                        help="Only after successful no-write cancellation, run one independent synthetic pointer-recovery witness")
    args = parser.parse_args()
    raise SystemExit(run(args.app_sha, args.test_sha, args.manifest, args.manifest_sha,
                         qualify_pointer_recovery=args.qualify_pointer_recovery, prior_direct_refusal=args.prior_direct_refusal,
                         prior_direct_pass=args.prior_direct_pass, prior_recovery_pass=args.prior_recovery_pass))
