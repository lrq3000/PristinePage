"""Qualify actual Settings Reconnect once, on a new synthetic folder after foreground Apply."""

from argparse import ArgumentParser
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import uuid

from run_settings_direct_files_ldplayer import (
    checks, prior_recovery_release, verify_prior_settings_recovery, PREDECESSOR_MANIFEST, RECOVERY_PASSED_MANIFEST, TEST_SOURCE,
)


APPLY_RELEASE = "6fff22d7ed9aa23df6925142b438deba2002d53b3e0e39d0e648a83de0678ff4"
DOCUMENTS_PROBE_REFUSAL = "e0b0cccd9d949cd1da2016bde97107299949c89155b8261b314eed358550446c"
CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#mainSettingsReconnectsAnInterruptedEditWithoutRepublishingIt"


def retained_inputs(args, history):
    c = checks
    def local_anchor(manifest, digest):
        manifest = manifest.resolve(strict=True)
        raw = manifest.parent
        c.need(raw.parent == history.resolve() and manifest.name == f"{raw.name}-final-manifest.json", "Unexpected release location")
        return manifest, c.settings_run_anchors(raw, digest)

    manifest, apply = local_anchor(args.apply, APPLY_RELEASE)
    _, original = local_anchor(args.original, PREDECESSOR_MANIFEST)
    protected_anchor = c.settings_run_anchors(history / c.SETTINGS_OPEN_PASS_RUN, c.SETTINGS_OPEN_PASS_MANIFEST)
    guest = c.SETTINGS_OPEN_CANCELED_GUEST
    fixture, protected = (json.loads(anchor("settings-open-fixture.json")) for anchor in (original, protected_anchor))
    _, _, _, synthetic, recovery_attempt, recovery_readback = prior_recovery_release(args.recovery, history, guest, fixture, protected)
    claim, artifacts, boundary, prior = (json.loads(apply(name)) for name in
        ("claim.json", "artifacts.json", "settings-apply-boundary.json", "settings-apply-readback.json"))
    c.need(guest == claim["name"] == artifacts["guest"] and artifacts["immediateRelease"] == RECOVERY_PASSED_MANIFEST
           and artifacts["fixture"] == fixture and artifacts["protected"] == protected
           and c.read_results(apply("settings-apply-instrumentation.txt"), (c.MAIN_SETTINGS_APPLY_CASE,)) == ([c.MAIN_SETTINGS_APPLY_CASE], [])
           and boundary["instrumentationTerminated"] and not boundary["observationErrors"]
           and all(boundary[key] for key in ("startingGrantAndProtectedFixture", "predecessorFilesUnchanged", "isolatedRecoveryUnchanged"))
           and boundary["pointer"]["activation"] == "6" and prior["returnedActivation"] == 6,
           "The foreground Apply predecessor was not verified")
    snapshot = json.loads(manifest.read_bytes())["recordSnapshot"]
    c.need(c.sha(manifest) == APPLY_RELEASE, "The admitted release changed")
    traces = [[json.loads(line) for line in anchor("commands.jsonl").splitlines()] for anchor in (original, apply)]
    def grant_reads(trace):
        return [c.parse_app_persisted_grants(row["stdout"]) for row in trace if row["exit"] == 0 and len(row["argv"]) == 5
                and row["argv"][4].startswith(f"set -o pipefail; dumpsys -t 10 activity -a -p {c.APP} | ")]
    original_grants = grant_reads(traces[0])
    apply_grants = grant_reads(traces[1])
    c.need(original_grants and apply_grants and all(grant == original_grants[0] for grant in original_grants),
           "Starting grants are not anchored")
    baseline = original_grants[0]
    c.require_settings_apply_grants(baseline, baseline, protected["folder"])
    c.need(apply_grants[-1] == baseline, "Apply did not restore the starting grants")
    grant_rows = [row["stdout"] for row in traces[1] if row["exit"] == 0 and row["argv"][-4:-1] == ["head", "-c", "4353"]
                  and row["argv"][-1].startswith("files/library-settings/folder-grants/")]
    c.need(len(grant_rows) == 1, "The exact formerly selected Apply grant is not authenticated")
    former_uri = grant_rows[0].splitlines()[3]
    c.require_bound_recipe_grant_destination(former_uri, f"/sdcard/Documents/{fixture['folder']}")
    former = (former_uri, True, "com.android.externalstorage", 3)
    return manifest, snapshot, guest, fixture, protected, synthetic, recovery_attempt, recovery_readback, claim, artifacts, boundary, prior, baseline, former


def admit_documents_probe_refusal(manifest, history, prior_claim, guest, previous, pair, source):
    """Only the anchored pre-install probe failure may continue on the unchanged candidate and guest."""
    c = checks
    manifest = manifest.resolve(strict=True)
    raw = manifest.parent
    c.need(raw.parent == history.resolve() and manifest.name == f"{raw.name}-final-manifest.json",
           "Unexpected pre-install refusal location")
    anchor = c.settings_run_anchors(raw, DOCUMENTS_PROBE_REFUSAL)
    claim, artifacts = (json.loads(anchor(name)) for name in ("claim.json", "artifacts.json"))
    trace = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    scoped = [row for row in trace if row["argv"][1:3] == ["-s", claim["serial"]]]
    c.need(guest == claim["name"] == artifacts["guest"] and artifacts["immediateRelease"] == APPLY_RELEASE and
           claim["ownership_history"][-1]["generation"] == prior_claim["generation"] and artifacts["previous"] == previous and
           {p: artifacts["proof"][p]["sha256"] for p in pair} == pair and artifacts["source"] == source and
           scoped and scoped[-1]["argv"][3:] == ["shell", "readlink", "-f", "/sdcard"] and scoped[-1]["exit"] == 1 and
           all(not {"install", "instrument", "mkdir", "push", "force-stop", "rm"}.intersection(row["argv"]) for row in trace),
           "No exact unchanged pre-install Documents probe refusal")
    return manifest, json.loads(manifest.read_bytes())["recordSnapshot"], claim


def require_guest_documents(adb):
    # /sdcard itself is an Android alias. Check the exact Documents leaf, not whether the shell can canonicalize the alias.
    reply = adb("shell", 'test -d /sdcard/Documents && test ! -L /sdcard/Documents && stat -c "%d:%i" /sdcard/Documents').strip()
    checks.need(re.fullmatch(r"[0-9]+:[1-9][0-9]*", reply), "The guest-local Documents parent is not an identified directory")
    return reply


def run(args):
    c = checks
    root = c.ROOT
    c.need(__debug__ and Path.cwd().resolve() == root and not any(os.environ.get(k) for k in c.LOCAL_ADB_OVERRIDES),
           "Require the exact checkout and default verified ADB transport")
    history = root / "validation/raw/storage"
    (manifest, snapshot, guest, fixture, protected, synthetic, recovery_attempt, recovery_readback,
     claim, artifacts, prior_boundary, prior, baseline, former) = retained_inputs(args, history)
    c.need(c.sha(root / TEST_SOURCE) == args.source_sha, "The built test source changed")
    paths = {c.APP: root / "app/build/outputs/apk/debug/app-debug.apk",
             c.TEST: root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"}
    pair = dict(zip(paths, (args.app_sha, args.test_sha)))
    previous = {p: artifacts["proof"][p]["sha256"] for p in paths}
    authority = sha256(f"settings-reconnect-edit-v1:{guest}".encode("ascii")).hexdigest()
    release = APPLY_RELEASE
    if args.probe_refusal is not None:
        manifest, snapshot, claim = admit_documents_probe_refusal(
            args.probe_refusal, history, claim, guest, previous, pair, args.source_sha)
        release = DOCUMENTS_PROBE_REFUSAL
    c.need(all(pair[p] != previous[p] for p in pair) and not (history / f"settings-retry-spent-{authority}.json").exists() and
           shutil.disk_usage(root).free >= 12 * 1024**3 + sum(p.stat().st_size for p in paths.values()),
           "Need two new exact APKs, unspent recovery authority and retained-artifact headroom")
    stage = root / "validation/raw/providers/ocr-app-standard-full"
    proof = {p: c.exact_apk(paths[p], pair[p], stage, p == c.TEST) for p in paths}
    c.require_independent_manifests(proof.values())
    prefix = "picker-adapter-settings-reconnect-" + uuid.uuid4().hex[:12]
    raw = history / prefix
    raw.mkdir()
    saved = {p: raw / f"{p}.apk" for p in paths}
    for package, path in paths.items():
        shutil.copyfile(path, saved[package])
        c.need(c.sha(saved[package]) == pair[package], "The retained APK changed")
    (raw / f"{prefix}-artifacts.json").write_text(json.dumps(dict(guest=guest, previous=previous, proof=proof, source=args.source_sha,
        immediateRelease=release, protected=protected, applyReadback=prior), indent=2), encoding="utf-8")
    ns = c.load_allocator(root, guest, raw, "pristinepage-tree-" + os.environ["OPENCODE_SESSION_ID"])
    base = c.run_type(ns, purpose="pristinepage-library-app-tree", operation="Settings Reconnect interrupted recipe",
                      admit_released=lambda record: c.verify_bound_recipe_release(claim, record, manifest, snapshot))

    class ReconnectRun(base):
        def process(self, pid):
            return c.inspect_prior_pid(super().process, pid)

    runner = ReconnectRun(prefix, None, 0)
    print(f"Runtime evidence: {raw.relative_to(root)}", flush=True)
    try:
        c.wait_for_allocator(runner, lambda: runner.prepare(False, None, False), before_claim=True)
        c.recheck_target(runner, ns)
        c.need(runner.owned()["api"] == 28, "The real Files route is qualified on API 28 only")

        def adb(*a, **kw):
            return runner.command([ns["ADB"], "-s", runner.serial, *a], **kw)

        def private(*a):
            return adb("shell", "run-as", c.APP, *a)

        original_files = fixture["inventory"]
        old_destination = f"/sdcard/Documents/{fixture['folder']}"
        operation = prior["generation"]
        marker = f"generations/{operation}.complete"
        catalog = f"catalogs/{operation}.sqlite"
        initial_leaf, = (leaf for leaf in original_files if leaf.startswith("generations/"))
        old_marker = (synthetic / initial_leaf).read_text(encoding="ascii")
        settings = "files/library-settings"
        pointer_path = f"{settings}/active-library"

        def preserve():
            c.recheck_target(runner, ns)
            c.verify_unchanged_settings_destination(adb, f"/sdcard/Documents/{protected['folder']}", protected["inventory"])
            child = c.read_selected_history_record(lambda *a: adb("shell", *a), f"{old_destination}/{marker}", 256)
            c.verify_unchanged_settings_destination(adb, old_destination, original_files | {
                marker: sha256(child.encode("ascii")).hexdigest(), catalog: prior["catalogSha256"]},
                backup_name=f"merge-staging-{operation}")
            captured = c.read_selected_history_record(private, f"files/library-settings-apply-pointer-{fixture['folder']}", 320)
            receipt = c.read_bound_recipe_evidence(adb, "files", f"files/catalog-recipe-attempts-v1/{operation}", old_destination,
                old_marker, child, prior["catalogSha256"], advanced=True, issuing=4, captured_pointer=captured)
            spend = c.read_selected_history_record(private, f"files/catalog-revision-spends/{operation}.spent", 1024)
            attempt = f"files/catalog-recipe-attempts-v1/{operation}"
            leaves = set(private("ls", "-A", "-1", attempt).splitlines())
            extras = {"catalog.sqlite", "recipe-edit.intent", "recipe-edit.complete", "recipe-edit.catalog-owner"}
            c.need(receipt == prior["receiptSha256"] and sha256(spend.encode("ascii")).hexdigest() == prior["revisionSpendSha256"]
                   and not adb("shell", "ls", "-A", "-1", f"{old_destination}/merge-staging-{operation}").strip()
                   and c.verify_library_move_spends(adb, attempt, leaves - extras, {
                       catalog: (int(child.splitlines()[5]), prior["catalogSha256"]),
                       marker: (len(child.encode("ascii")), sha256(child.encode("ascii")).hexdigest()),
                   }) == prior["moveSpends"], "Prior Apply evidence changed")
            return verify_prior_settings_recovery(adb, original_files, synthetic, recovery_attempt, recovery_readback)

        def starting_pointer():
            pointer = c.read_selected_history_record(private, pointer_path, 320)
            origin = c.read_selected_history_record(private, f"{settings}/default-origin", 320)
            c.need(sha256(pointer.encode("ascii")).hexdigest() == prior_boundary["pointer"]["sha256"], "The returned Default pointer changed")
            return c.require_returned_settings_pointer(pointer, origin, 6)

        c.require_cold_backup_app(adb)
        c.need(set(adb("shell", "pm", "list", "packages", c.APP).splitlines()) == {f"package:{p}" for p in paths}
               and {p: runner.installed_hash(p) for p in paths} == previous, "Wrong installed predecessor pair")
        fingerprint = adb("shell", "getprop", "ro.build.fingerprint").strip()
        policy = f"android-imagedecoder-v2/api28/{sha256(fingerprint.encode()).hexdigest()[:16]}/srgb-straight-white-exif-v1"
        c.need(policy == fixture["conversion"], "The fixture requires the retained native decoder identity")
        default = starting_pointer()
        preserve()
        before = c.read_app_persisted_grants(adb)
        c.need(before in (baseline, tuple(sorted((*baseline, former)))), "The cold grant inventory is not the exact Apply baseline")
        folder = "PristinePage-tree-" + uuid.uuid4().hex[:12]
        new_operation = str(uuid.uuid4())
        new_destination = f"/sdcard/Documents/{folder}"
        parent = require_guest_documents(adb)
        settings_names = private("ls", "-A", "-1", settings).splitlines()
        c.need(not any(n.startswith(".active-library-") and n.endswith(".pending") for n in settings_names)
               and new_operation not in private("ls", "-A", "-1", "files/catalog-recipe-attempts-v1").splitlines()
               and f"{new_operation}.spent" not in private("ls", "-A", "-1", "files/catalog-revision-spends").splitlines(),
               "A pointer stage or the new operation ID is already occupied")
        c.recheck_target(runner, ns)
        c.spend_settings_retry(history, authority, dict(folder=folder, operation=new_operation, kind="settings-reconnect-edit",
            previous=previous, candidate=pair))
        former_args = ("-e", "librarySettingsSecondPriorFolder", fixture["folder"]) if former in before else ()
        terminated = False
        try:
            for package, apk in saved.items():
                c.recheck_target(runner, ns)
                installed = runner.installed_hash(package)
                c.need(installed == previous[package], "The installed predecessor changed before update")
                flags = c.install_flags(package, installed, pair[package], previous[c.TEST], previous[c.APP])
                c.need(c.sha(apk) == pair[package] and adb("install", *flags, "--no-streaming", apk, timeout=130).strip().splitlines()[-1:] == ["Success"]
                       and runner.installed_hash(package) == pair[package], "Could not install the exact Reconnect APK")
            c.need(adb("shell", "pm", "list", "instrumentation", c.APP).splitlines() ==
                   [f"instrumentation:{c.RUNNER} (target={c.APP})"], "The instrumentation target changed")
            c.require_cold_backup_app(adb)
            c.need(starting_pointer() == default and c.read_app_persisted_grants(adb) == before, "Installation changed the baseline")
            c.need(require_guest_documents(adb) == parent, "The guest-local Documents parent changed")
            c.stage_synthetic_files(adb, synthetic, new_destination, original_files)
            output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", CASE,
                         "-e", "libraryFixtureFolder", folder, "-e", "librarySettingsOpenFixture", "reconnect-edit",
                         "-e", "librarySettingsPriorFolder", protected["folder"], *former_args, "-e", "librarySettingsExactHint", "true",
                         "-e", "librarySelectedAppendOperation", new_operation, c.RUNNER, timeout=260)
            runner.path("instrumentation.txt").write_text(output, encoding="utf-8")
            passed, failed = c.read_results(output, (CASE,))
            terminated = True
        finally:
            def checked(observe):
                c.recheck_target(runner, ns)
                return observe()

            observations = dict(
                appSha256=lambda: runner.installed_hash(c.APP),
                testSha256=lambda: runner.installed_hash(c.TEST),
                pointer=lambda: c.read_selected_history_record(private, pointer_path, 320).splitlines()[2],
                generationMarkers=lambda: adb("shell", "ls", "-A", "-1", f"{new_destination}/generations").splitlines(),
                priorUnchanged=preserve,
                grantUnchanged=lambda: c.read_app_persisted_grants(adb) == before)
            observed = c.collect_settings_apply_boundary(terminated, {
                name: lambda observe=observe: checked(observe) for name, observe in observations.items()
            })
            runner.save(runner.path("reconnect-boundary.json"), observed, durable=True)
        if not failed:
            c.need(not observed["observationErrors"] and observed["priorUnchanged"] and observed["grantUnchanged"]
                   and observed["appSha256"] == pair[c.APP] and observed["testSha256"] == pair[c.TEST],
                   "The Reconnect case did not preserve the exact starting grant and fixtures")
            c.recheck_target(runner, ns)
            c.verify_settings_apply_destination(runner, adb, new_destination, synthetic, original_files, default,
                                                issuing=7, expected_operation=new_operation)
        runner.save(runner.path("outcomes.json"), dict(passed=passed, failed=failed, installedSha256=pair), durable=True)
        print(f"Settings Reconnect: {len(passed)} passed, {len(failed)} failed.", flush=True)
        return bool(failed)
    finally:
        if runner.claimed:
            c.close_owned(runner)


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    for arg in ("app_sha", "test_sha", "source_sha"):
        parser.add_argument(arg)
    for arg in ("apply", "original", "recovery"):
        parser.add_argument(arg, type=Path)
    parser.add_argument("--probe-refusal", type=Path, help="Exact pre-install Documents probe refusal; never retry after a mutation")
    raise SystemExit(run(parser.parse_args()))
