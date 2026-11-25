"""Verify the real Files folder handoff on one allocator-owned PristinePage app guest."""

from argparse import ArgumentParser
from contextlib import closing
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import uuid

from make_library_folder_picker_fixture import make_fixture, synthetic_scan
from run_library_restore_ldplayer import close_owned, inspect_prior_pid, read_results, wait_for_allocator

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "validation/providers"))
from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, verify_target
from run_local_ocr_guest import (
    APP, TEST, RUNNER, check_installed_pair, exact_apk, install_flags, load_allocator, need, run_type, sha,
)

FILES_CASE = ("app.pristinepage.LibraryFolderFilesJourneyTest"
              "#actualFilesGrantReopensAfterActivityRecreationAndRevocationFailsClosed")
DESCRIPTOR_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#exactFilesTreeExposesSyncableDirectoryDescriptors"
WORKING_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#populatingAWorkingRoomCatalogDoesNotMaterializeTheOriginal"
ACTIVATION_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#defaultAppGraphUsesItsDurableActivationBeforeAndAfterActivityRecreation"
SELECTED_CATALOG_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#durableFolderSelectionReopensExactCatalogAndRefusesRevocation"
ACTIVATION_COMPATIBILITY = (
    "app.pristinepage.SettingsNavigationTest#libraryBackupRequiresAnExplicitConfirmationAndCanBeCanceledInSettings",
    "app.pristinepage.ApplicationCompositionTest#firstActivationPreservesAPopulatedStoreOpenedByThePreviousComposition",
    "app.pristinepage.ApplicationCompositionTest#corruptSelectionRefusesBeforeCreatingTheDefaultStore",
    "app.pristinepage.ApplicationCompositionTest#aFolderSelectionWithLostSupportNeverOpensTheDefaultStore",
    "app.pristinepage.ApplicationCompositionTest#failedRecoveryDoesNotCacheReadinessAndTheSameRootCanRetry",
    "app.pristinepage.ApplicationCompositionTest#cancellationDuringIoDeliveryLeavesNoCachedCanceledGraphAndAllowsRetry",
    "app.pristinepage.ApplicationCompositionTest#aStalePhotoResultCannotBootstrapOrAccessTheDefaultGraph",
    "app.pristinepage.DocumentCatalogIntegrationTest#committedMixedSelectionReopensWithExactOrderedPageMetadataWithoutProviderReads",
    "app.pristinepage.EditorPersistenceIntegrationTest#applicationSavesThenReopensTheExactRecipeAndRendersItsUnchangedSource",
    "app.pristinepage.PhotoResultRoutingTest#idOnlySavedRequestAndRegistryRestoreKeepTheOriginalRoomBatch",
    "app.pristinepage.PhotoResultRoutingTest#scopedPickerStateReopensOnlyItsExactRoutingIdentity",
    "app.pristinepage.LibraryPickerBindingTest#restoredScopedFilesRegistrationClaimsOnlyItsQueuedResult",
    "app.pristinepage.LibraryPickerBindingTest#libraryBackupRegistryDeliversOnlyTheLaunchedWritableSelection",
    "app.pristinepage.ApplicationCompositionTest#aColdBackupReceiptLookupNeverOpensRoomOrTheProvider",
    "app.pristinepage.ApplicationCompositionTest#anEditorContinuationCannotReadSaveOrRenderUnderAnotherActivation",
    "app.pristinepage.ApplicationCompositionTest#anAtelierChooserBelongsToTheDraftAndItsIssuingActivation",
    "app.pristinepage.ApplicationCompositionTest#onlyTheIssuedCameraRequestCanDiscardItsHandoff",
    "app.pristinepage.ApplicationCompositionTest#atelierSaveRefusesAReplacementRevisionAndCanReadBackOnlyTheExactReceipt",
    "app.pristinepage.ApplicationCompositionTest#batchDonePreviewAndUndoCannotDeliverAfterTheirIssuingActivationChanges",
    "app.pristinepage.ApplicationCompositionTest#aThumbnailCannotDeliverPixelsAfterItsLibraryActivationChanges",
    "app.pristinepage.ApplicationCompositionTest#aTextSaveResultFromAnObsoleteActivationNeverReachesItsTransfer",
    "app.pristinepage.ApplicationCompositionTest#anObsoleteAlbumReviewCannotLoadSameIdsFromTheDefaultStore",
    "app.pristinepage.LibraryPickerBindingTest#savedEditorNeedsItsIssuingActivationAndLegacyIdsCannotReopen",
    "app.pristinepage.LibraryPickerBindingTest#savedHomeNavigationRequiresAnExactActivationButLegacyChooserIdentityRemainsSeparate",
    "app.pristinepage.LibraryPickerBindingTest#malformedHomeNavigationDoesNotDiscardAnExactQueuedFilesIdentity",
    "app.pristinepage.EditorHostTest#retainedEditorSurvivesRecreationAndBackgroundWithoutSavingOrRetainingDisplayAllocations",
    "app.pristinepage.LibraryEditorJourneyTest#nonfirstPageAppliesAndRecreatesThroughDefaultNavigationThenReturnsToThatSamePage",
    "app.pristinepage.RenderCoordinatorIntegrationTest#realPrivateSourceRendersThenReopensWithoutProviderAccessOrSourceMutation",
)
WRITE_PROBE_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#grantedTreeCanStageAndPublishOneSyntheticFileWithoutOverwriting"
BACKUP_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#defaultLibraryExportsItsOriginalAndAcceptedEditIntoANewFilesBackup"
SETTINGS_BACKUP_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#settingsPublishesOneBackupOfTheSyntheticDefaultLibrary"
SETTINGS_PICKER_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#settingsOpensAndCancelsFilesWithoutStartingABackup"
SETTINGS_WITNESS_FILE = "files/library-settings-native-witness"
BACKUP_ORIGINAL = "ebd602840377a3b3cf763cb6828fee67861ff77c444e8fe5894457ef6d99a24d"
OCR_ACTIVATION_CASES = tuple(f"app.pristinepage.ocr.OcrWorkManagerIntegrationTest#{method}" for method in (
    "sameActivationKeepsTheLiveWakeButAnotherActivationDoesNotShareIt",
    "invalidInputFailsBeforeExecutingTheStandardOcrWorker",
    "coldLegacyAndStaleWakesLeaveAnExistingDefaultGraphUnopened",
    "pendingTileUsesOnlyTheLiveWakeForItsSuppliedGeneration",
)) + ("app.pristinepage.ApplicationCompositionTest#aColdWorkerCannotCreateOrBorrowADefaultLibraryUntilTheExactActivationExists",)
OCR_SAVE_CASE = "app.pristinepage.ocr.LocalOcrImportJourneyTest#importedScansAreRecognizedAutomaticallyAndHomeOpensTheSecondPage"
OCR_REDISCOVERY_CASE = (
    "app.pristinepage.ocr.LocalOcrStartupBackfillTest#legacyAndStaleWakesLeaveARealPendingJobForCurrentActivationRediscovery"
)
CASES = (
    "app.pristinepage.LibraryFolderPickerContractTest#retainsTheActualEncodedTreeAndBothReturnedModes",
    "app.pristinepage.LibraryFolderPickerContractTest#rejectsDocumentUrisAndCancelledOrNonpersistentSelections",
    "app.pristinepage.LibraryFolderPickerContractTest#retainsAnExactDocumentsHomeChildButRejectsTheHomeRootAndTraversal",
    FILES_CASE,
)
INPUTS = ("app", "data", "core", "image-android", "test-fixtures", "gradle",
          "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "buildSrc", "build-logic")


def recheck_target(run, ns):
    """Retry a deadlined identity read once; reject any observed mismatching identity immediately."""
    try:
        verify_target(run, ns)
    except subprocess.TimeoutExpired:
        verify_target(run, ns)


def check_fixture_root(namespaces, after, probe_write, probe_passed, backup_export, backup_passed):
    allowed = set(namespaces)
    required = set(namespaces)
    if probe_write:
        allowed.add("isolated-write-probe")
    if probe_passed:
        required.add("isolated-write-probe")
    backups = {name for name in after if re.fullmatch(r"PristinePage-backup-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", name)}
    need(len(backups) <= int(backup_export) and (not backup_passed or len(backups) == 1),
         "Instrumentation made an unexpected number of backup folders")
    need(required <= after <= (allowed | backups), "Instrumentation changed entries outside its synthetic write destinations")
    return next(iter(backups), None)


def select_backup_case(args):
    if args.settings_backup:
        need(args.fresh and not any((args.backup_export, args.probe_write, args.ocr_activation, args.working_catalog,
                                     args.probe_directory, args.activation, args.selected_catalog, args.activation_compatibility,
                                     args.expect_home, args.settings_picker_only)),
             "Settings backup requires a fresh standalone app witness")
        return SETTINGS_BACKUP_CASE
    return BACKUP_CASE if args.backup_export else None


def select_settings_picker_case(args):
    if not args.settings_picker_only:
        return None
    need(not any((args.fresh, args.settings_backup, args.backup_export, args.probe_write, args.ocr_activation,
                  args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
                  args.activation_compatibility, args.expect_home)),
         "Settings picker cancellation requires the retained synthetic guest and a standalone invocation")
    return SETTINGS_PICKER_CASE


def verify_settings_catalog(path, digest, extent, expected):
    album, page, source, pixels, revision1, revision2 = expected
    cli = shutil.which("sqlite3")
    need(cli is not None, "The existing FTS4-capable SQLite CLI is required")
    checks = subprocess.run([cli, "-batch", "-bail", "-readonly", str(path), "PRAGMA integrity_check; PRAGMA foreign_key_check;"],
                            capture_output=True, text=True, timeout=15, check=True)
    need(checks.stdout.splitlines() == ["ok"], "Settings backup catalog failed independent integrity or foreign-key readback")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)) as db:
        for table in ("Document", "Page", "SourceBlob", "Source", "LibraryScanName"):
            need(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone() == (1,), "Settings backup included or omitted unrelated records")
        rows = db.execute("""
            SELECT d.documentId,d.title,d.documentRevision,p.pageId,p.currentRecipeRevision,p.ordinal,s.sourceId,
                   s.pixelSha256,b.byteSha256,b.byteCount,n.filename,n.documentId
              FROM Document d JOIN Page p ON p.documentId=d.documentId JOIN Source s ON s.sourceId=p.sourceId
              JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId JOIN LibraryScanName n ON n.pageId=p.pageId LIMIT 2
        """).fetchall()
        need(rows == [(album, "Synthetic Settings backup", 3, page, 2, 0, source, pixels, digest, extent, f"Scan {page}.png", album)],
             "Settings backup scan binding differed from the exact synthetic album, original or logical name")
        revisions = db.execute("""
            SELECT pageId,sourceId,recipeRevision,parentRevision,canonicalRecipe FROM RecipeRevision ORDER BY recipeRevision LIMIT 3
        """).fetchall()
        need([(p, s, rev, parent, sha256(recipe).hexdigest(), json.loads(recipe)["geometry"]["outputQuarterTurns"])
              for p, s, rev, parent, recipe in revisions] == [
                  (page, source, 1, None, revision1, 0), (page, source, 2, 1, revision2, 1)
              ], "Settings backup lost the exact revision sequence or saved rotation")
        need(db.execute("SELECT documentId,action,appliedDocumentRevision,state FROM UndoEntry LIMIT 2").fetchall() ==
             [(album, "RECIPE_EDIT", 3, "AVAILABLE")], "Settings backup lost the retained Undo")


def run(args):
    need(__debug__ and ROOT == Path.cwd().resolve() and not any(os.environ.get(k) for k in LOCAL_ADB_OVERRIDES),
         "Require the exact build worktree and default scoped ADB transport")
    need(re.fullmatch(r"[a-f0-9]{40}", args.commit) is not None and
         subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == args.commit and
         not subprocess.check_output(["git", "diff", args.commit, "--", *INPUTS]) and
         not subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", *INPUTS]),
         "The checked-out production/test build inputs do not match the admitted source")
    need(re.fullmatch(r"agent-pristinepage-tree-[a-f0-9]{12}", args.guest), "Require a dedicated library app-test guest name")
    need(args.fresh != bool(args.previous), "Choose first admission or an exact retained predecessor pair")
    picker_case = select_settings_picker_case(args)
    backup_case = select_backup_case(args)
    if args.ocr_activation:
        need(not any((args.fresh, args.expect_home, args.probe_directory, args.working_catalog, args.activation, args.selected_catalog,
                      args.activation_compatibility, args.probe_write, args.backup_export)),
             "The OCR regression batch requires the retained app guest and does not operate on Files fixtures")
    prefix = "picker-adapter-library-tree-" + uuid.uuid4().hex[:12]
    raw = ROOT / "validation/raw/storage" / prefix
    raw.mkdir()
    stage = ROOT / "validation/raw/providers/ocr-app-standard-full"
    built = {
        APP: ROOT / "app/build/outputs/apk/debug/app-debug.apk",
        TEST: ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    }
    disk_floor = 16 if args.fresh else 12
    need(shutil.disk_usage(ROOT).free >= disk_floor * 1024**3 + sum(path.stat().st_size for path in built.values()),
         "Not enough disk for the exact retained APK pair and the guest floor")
    hashes = dict(zip(built, [args.app_sha, args.test_sha]))
    proof = {package: exact_apk(path, hashes[package], stage, package == TEST) for package, path in built.items()}
    previous = {}
    if args.previous:
        for package, file in zip(built, args.previous):
            path = Path(file).resolve()
            need(path.is_relative_to(ROOT / "validation/raw/storage") and path.name == f"{package}.apk",
                 "The predecessor must be the retained evidence file for this package")
            previous[package] = sha(path)
            exact_apk(path, previous[package], stage, package == TEST)
    retained = {}
    for package, path in built.items():
        saved = raw / f"{package}.apk"
        with path.open("rb") as incoming, saved.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        need(sha(saved) == hashes[package], "Retained candidate APK changed")
        retained[package] = saved
    fixture = raw / "synthetic-library"
    files = {} if args.ocr_activation else make_fixture(fixture, with_scan=True)
    original = None if args.ocr_activation else synthetic_scan()[0]
    if not args.ocr_activation:
        need(files.get(f"objects/{original}") == original, "The populated Files fixture lacks its exact original")
    (raw / f"{prefix}-artifacts.json").write_text(
        json.dumps(dict(proof=proof, source=args.commit, fixture=files, previous=previous, guest=args.guest)),
        encoding="utf-8",
    )
    ns = load_allocator(ROOT, args.guest, raw, "pristinepage-tree-" + os.environ["OPENCODE_SESSION_ID"])

    class FolderRun(run_type(ns, purpose="pristinepage-library-app-tree", operation="PristinePage Files tree",
                             fresh_disk_floor_gib=16, add_statuses=(0, 1, 2, 3))):
        def process(self, pid):
            return inspect_prior_pid(super().process, pid)

    run = FolderRun(prefix, None, 0)
    print(f"Runtime evidence: {raw.relative_to(ROOT)}", flush=True)
    try:
        wait_for_allocator(run, lambda: run.prepare(args.fresh, None, args.fresh), before_claim=True)
        recheck_target(run, ns)
        target = [ns["ADB"], "-s", run.serial]

        def adb(*argv, **options):
            return run.command([*target, *argv], **options)

        present = set(adb("shell", "pm", "list", "packages", APP).splitlines())
        expected = set() if args.fresh else {f"package:{p}" for p in built}
        need(present == expected, "The exact expected package set did not match; nothing was replaced")
        installed = {p: run.installed_hash(p) if f"package:{p}" in present else None for p in built}
        if not args.fresh:
            check_installed_pair(installed, previous, hashes)
        run.save(run.path("installed-before.json"), installed, durable=True)
        for p, path in retained.items():
            flags = install_flags(p, installed[p], hashes[p], previous.get(TEST), previous.get(APP))
            if flags is not None:
                need(sha(path) == hashes[p], "Candidate changed before installation")
                result = adb("install", *flags, "--no-streaming", path, timeout=130)
                need(result.strip().splitlines()[-1:] == ["Success"], "APK installation did not acknowledge success")
            need(run.installed_hash(p) == hashes[p], "Installed APK differs from the admitted candidate")
        need(adb("shell", "pm", "list", "instrumentation", APP).splitlines() ==
             [f"instrumentation:{RUNNER} (target={APP})"], "The instrumentation registry differs")
        if args.ocr_activation:
            recheck_target(run, ns)
            outcomes = []
            for label, cases, deadline in (("transport", OCR_ACTIVATION_CASES, 150),
                                           ("rediscovery", (OCR_REDISCOVERY_CASE,), 230),
                                           ("save-edit", (OCR_SAVE_CASE,), 540)):
                output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(cases), RUNNER, timeout=deadline)
                run.path(f"ocr-{label}-instrumentation.txt").write_text(output, encoding="utf-8")
                passed, failed = read_results(output, cases)
                outcomes.append({"batch": label, "passed": passed, "failed": failed})
                if failed:
                    break
            run.save(run.path("outcomes.json"), {"batches": outcomes, "installedSha256": hashes}, durable=True)
            for result in outcomes:
                print(f"LDPlayer OCR {result['batch']}: {len(result['passed'])} passed, {len(result['failed'])} failed.", flush=True)
            return any(result["failed"] for result in outcomes)

        folder = "PristinePage-tree-" + uuid.uuid4().hex[:12]
        guest_root = f"/sdcard/Documents/{folder}"
        adb("shell", "mkdir", "-p", "/sdcard/Documents")
        namespaces = sorted({relative.split("/", 1)[0] for relative in files})
        # The root reservation must succeed before touching any child, even if a colliding folder already exists.
        adb("shell", "mkdir", guest_root)
        adb("shell", "mkdir", *(f"{guest_root}/{namespace}" for namespace in namespaces))
        for relative, digest in files.items():
            adb("push", fixture / relative, f"{guest_root}/{relative}")
            need(adb("shell", "sha256sum", f"{guest_root}/{relative}").split()[0] == digest,
                 "The synthetic Files fixture differed after staging")
        need(set(adb("shell", "ls", "-A", "-1", guest_root).splitlines()) == set(namespaces),
             "The fresh fixture contains an unexpected root entry before instrumentation")
        recheck_target(run, ns)
        original_args = ["-e", "libraryFixtureOriginal", original]
        if args.expect_home:
            original_args += ["-e", "libraryExpectHome", "1"]
        scenarios = (picker_case,) if picker_case else (SETTINGS_BACKUP_CASE,) if args.settings_backup else (*CASES, *((DESCRIPTOR_CASE,) if args.probe_directory else ()),
                     *((WORKING_CASE,) if args.working_catalog else ()),
                     *((ACTIVATION_CASE,) if args.activation else ()),
                     *((SELECTED_CATALOG_CASE,) if args.selected_catalog else ()),
                     *(ACTIVATION_COMPATIBILITY if args.activation_compatibility else ()),
                     *((WRITE_PROBE_CASE,) if args.probe_write else ()),
                     *((BACKUP_CASE,) if args.backup_export else ()))
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(scenarios),
                     "-e", "libraryFixtureFolder", folder, *original_args,
                     *(("-e", "librarySettingsFresh", "1") if args.settings_backup else ()), RUNNER, timeout=150)
        run.path("instrumentation.txt").write_text(output, encoding="utf-8")
        passed, failed = read_results(output, scenarios)
        for relative, digest in files.items():
            need(adb("shell", "sha256sum", f"{guest_root}/{relative}").split()[0] == digest,
                 "Instrumentation changed an existing library fixture file")
        after = set(adb("shell", "ls", "-A", "-1", guest_root).splitlines())
        backup_passed = backup_case is not None and backup_case in passed
        backup_name = check_fixture_root(namespaces, after, args.probe_write, WRITE_PROBE_CASE in passed,
                                         backup_case is not None, backup_passed)
        if backup_passed:
            backup = f"{guest_root}/{backup_name}"
            need(set(adb("shell", "ls", "-A", "-1", backup).splitlines()) == {*namespaces, "staging"},
                 "The backup does not contain only its expected namespaces")
            need(not adb("shell", "ls", "-A", "-1", f"{backup}/staging").strip(), "A complete backup retained a staged file")
            generation = backup_name.removeprefix("PristinePage-backup-")
            for namespace, leaf in (("catalogs", f"{generation}.sqlite"), ("generations", f"{generation}.complete")):
                need(adb("shell", "ls", "-A", "-1", f"{backup}/{namespace}").splitlines() == [leaf],
                     "The backup has an unexpected catalog or marker")
            marker = adb("shell", "cat", f"{backup}/generations/{generation}.complete").splitlines()
            need(len(marker) == 7 and marker[0] == "pristinepage-library-v1" and marker[2:4] == [generation, "-"],
                 "The guest backup has the wrong completion record")
            # Independently calculated from the exact encoded PNG in LibraryDefaultFolderExportProbe.
            digest = BACKUP_ORIGINAL
            need(adb("shell", "ls", "-A", "-1", f"{backup}/objects").splitlines() == [digest],
                 "The backup has an unexpected original locator")
            for leaf, digest in ((f"objects/{digest}", digest), (f"catalogs/{generation}.sqlite", marker[6])):
                need(adb("shell", "sha256sum", f"{backup}/{leaf}").split()[0] == digest, "Backup bytes do not match their recorded digest")
            if args.settings_backup:
                witness = adb("shell", "run-as", APP, "cat", SETTINGS_WITNESS_FILE).splitlines()
                uuid_text = r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
                need(len(witness) == 9 and all(re.fullmatch(uuid_text, x) for x in witness[:3] + witness[6:8])
                     and all(re.fullmatch(r"[0-9a-f]{64}", x) for x in witness[3:6])
                     and witness[6:9] == [marker[1], marker[2], backup_name], "Settings receipt did not match the closed folder")
                need(adb("shell", "stat", "-c", "%s", f"{backup}/objects/{BACKUP_ORIGINAL}").strip() == "81",
                     "Settings fixture original extent differs")
                catalog = raw / "settings-closed-catalog.sqlite"
                need(marker[5].isdigit() and 0 < int(marker[5]) <= 2 * 1024 * 1024, "Settings fixture catalog extent is unexpected")
                need(adb("shell", "stat", "-c", "%s", f"{backup}/catalogs/{generation}.sqlite").strip() == marker[5],
                     "Settings fixture catalog extent differs from its marker")
                adb("pull", f"{backup}/catalogs/{generation}.sqlite", catalog, timeout=20)
                need(catalog.stat().st_size == int(marker[5]) and sha(catalog) == marker[6], "Pulled Settings catalog changed")
                verify_settings_catalog(catalog, BACKUP_ORIGINAL, 81, witness[:6])
        for namespace in namespaces:
            expected_leaves = {relative.split("/", 1)[1] for relative in files if relative.startswith(f"{namespace}/")}
            need(set(adb("shell", "ls", "-A", "-1", f"{guest_root}/{namespace}").splitlines()) == expected_leaves,
                 "Instrumentation changed the namespace of the existing fixture")
        run.save(run.path("outcomes.json"), {"passed": passed, "failed": failed, "installedSha256": hashes}, durable=True)
        print(f"LDPlayer Files grant: {len(passed)} passed, {len(failed)} failed. " + ", ".join(failed), flush=True)
        return bool(failed)
    finally:
        if run.claimed:
            close_owned(run)


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    for field in ("commit", "app_sha", "test_sha", "guest"):
        parser.add_argument(field)
    parser.add_argument("--fresh", action="store_true")
    parser.add_argument("--expect-home", action="store_true", help="Require the actual AOSP Documents namespace")
    parser.add_argument("--probe-directory", action="store_true")
    parser.add_argument("--working-catalog", action="store_true")
    parser.add_argument("--activation", action="store_true")
    parser.add_argument("--selected-catalog", action="store_true")
    parser.add_argument("--activation-compatibility", action="store_true")
    parser.add_argument("--probe-write", action="store_true", help="Write only in a fresh synthetic probe subdirectory")
    parser.add_argument("--backup-export", action="store_true", help="Export an isolated default library into a separately named backup child")
    parser.add_argument("--settings-backup", action="store_true", help="Exercise real Settings publication only on a verified fresh guest")
    parser.add_argument("--settings-picker-only", action="store_true", help="Open and cancel Files from Settings on the retained synthetic guest; never select a destination")
    parser.add_argument("--ocr-activation", action="store_true", help="Test scoped WorkManager wakes and ordinary synthetic save/edit; no Files fixture")
    parser.add_argument("--previous", nargs=2, metavar=("APP_APK", "TEST_APK"))
    raise SystemExit(run(parser.parse_args()))
