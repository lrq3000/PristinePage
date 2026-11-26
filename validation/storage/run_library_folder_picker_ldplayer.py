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
from urllib.parse import quote
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
ANCESTRY_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#exactFilesTreeProvesManagedDirectoryAncestry"
ANCESTRY_GRANT_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#persistsExactFilesReadForColdAncestry"
ANCESTRY_COLD_CASE = "app.pristinepage.LibraryProviderAncestryColdTest#provesManagedAncestryWithoutEffectiveWrite"
INDEPENDENT_GRANT_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#persistsIndependentFilesRootsAndAnchor"
INDEPENDENT_DERIVED_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#separatesIndependentlySelectedRootsWithoutAParentGrant"
INDEPENDENT_COLD_CASE = "app.pristinepage.LibraryProviderAncestryColdTest#twoIndependentFilesRootsRequireDisjointPhysicalAncestry"
INDEPENDENT_CHILDREN = ("Library-Left", "Library-Right")
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
SETTINGS_RECOVERY_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#coldSettingsRecoversTheExactCompletedBackupWithoutWriting"
BACKUP_DEATH_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#endsProcessAfterBackupCommitBeforeDelivery"
BACKUP_DEATH_RECOVERY_CASE = "app.pristinepage.LibraryBackupDeathRecoveryTest#coldProcessInspectsInterruptedDeliveryWithoutReplay"
BACKUP_PRE_MARKER_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#endsProcessAfterPayloadBeforeTheCompletionMarker"
BACKUP_PRE_MARKER_RECOVERY_CASE = "app.pristinepage.LibraryBackupDeathRecoveryTest#coldProcessKeepsInterruptedPublicationUnresolvedWithoutReplay"
BACKUP_DEATH_PICKER_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#checksTheBackupDeathPickerRouteWithoutSelection"
SETTINGS_WITNESS_FILE = "files/library-settings-native-witness"
SETTINGS_COMPLETED_MANIFEST = "cee6ac3fd210d2b36c64dff1b534830368b2677e5ea5b36b1555dc2a15c0029a"
SETTINGS_COLD_MANIFEST = "ae398db885ea47ac131838a3011766e69d4984be224188b1a282ad8d5190adc5"
BACKUP_DEATH_PICKER_FAILURE = "e5deb5f75e5e53dae04a8a655d6f9bfde2671e824876a155564a4323b3a156b0"
BACKUP_MANUAL_CONTROL_FAILURE = "08fe10503d488aa4ec3775157a7d8b2690be375908cb2c7194def2dd6cf64002"
BACKUP_DIRECT_CONTROL_PASS = "b2ac8b7795cb08629b3eb0cf888b1467a464ef8b36810afa76de07acae0d7e24"
BACKUP_DEATH_RECEIPT_ONLY = "b6f9e9329f39c067cecdb1c1130b9f8f28bf65492e17709750c89d38bdfa86cd"
ANCESTRY_GRANT_REFUSAL = "3271a4ebf9f4e0c55a25202fecfc160fef75626fe07ab027c767b7c2ce0b8fd1"
ANCESTRY_FINISHED_GRANT_REFUSAL = "0a2fb4828173346ad9d01ff59d00449ce48c48827d38c1c1882e66dcc6f5083e"
ANCESTRY_EXISTING_GRANT_REFUSAL = "3e3e68c579d04a3dd6c69627b8d30ea0ac151ac9096519c5d0d626b748486076"
ANCESTRY_RETAINED_HANDOFF = "9775b7bb50d47222094d4db81e5cc81ccc40958d1e53d803a938a0b886ccb958"
ANCESTRY_COLD_PASSED = "fdc2edc0be9d2ae93fbbb73900dec9d7952df5f5850a8637ded399782fcec705"
CAPACITY_CASE = "app.pristinepage.LibraryFolderFilesJourneyTest#actualFilesCapacityRefusesBeforePublication"
CAPACITY_PASSED = "1e113e46b58e2926ef74e36f4b1e72e32ba38b453602b6a4051b21ac9e5cc665"
BACKUP_DELIVERY_RECOVERY_PASSED = "94965983c698ea277722e113dfd130d7d1df1cf9ee0cdf2dc40ce62bbf626981"
PRE_MARKER_READ_TIMEOUT = "941ce5c60e6db39b3a1e41135f1dfd59bf65766836f56c0e26e643163b3bbd08"
PRE_MARKER_WRITER_STOPPED = "b03c132101aeb5f3f7169230c81533675c3fc5901176d79a6b3231ce4da2e792"
PRE_MARKER_RECOVERY_PASSED = "c5919cf91cda1c7222554f32fc7d339b0870458922dc2279090840eb16101535"
INDEPENDENT_FIRST_PICKER_REFUSAL = "2bf9bd0595719b47826ce3f9d0460791599ddbdaf11aef0666a04a3e7bde0f7e"
INDEPENDENT_GRANTS_RETAINED = "f8d8e889ef209c4aaba178c9737d16881ee3afb362cd67ec621ce59793886084"
INDEPENDENT_COLD_PASSED = "10354effa50ebdf210c7d4d8b5d1fd2709a9388b779414518c2674ffc309095b"
INDEPENDENT_DERIVED_PASSED = "550461e2206a8f887853ded659da4a494215144c64ba53f14f22a5212d3ccdd9"
UUID_TEXT = r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
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


def read_backup_delivery_death(output, operation, *, before_marker=False):
    """Reject an ordinary assertion failure, an early crash or anything other than the one armed boundary."""
    target, method = (BACKUP_PRE_MARKER_CASE if before_marker else BACKUP_DEATH_CASE).split("#")
    lines = output.rstrip(" \r\n").splitlines()
    error = "Missing exact backup checkpoint and ensuing Android process crash"
    need(lines[-2:] == ["INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "INSTRUMENTATION_CODE: 0"], error)
    blocks, fields = [], {}
    for line in lines[:-2]:
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            need(sep and key and key not in fields, error)
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            blocks.append((line.removeprefix("INSTRUMENTATION_STATUS_CODE: ").strip(), fields))
            fields = {}
        else:
            need(not line.strip() or ("stream" in fields and not line.startswith("INSTRUMENTATION_")), error)
    need(not fields and [code for code, _ in blocks] == ["1", "72" if before_marker else "71"], error)
    start, checkpoint = blocks[0][1], blocks[1][1]
    keys = {"backupDeathOperation", "backupDeathPid", "backupDeathReceipt", "backupDeathBindings"}
    if before_marker:
        keys.add("backupDeathGrantInventory")
    need(start.get("class") == target and start.get("test") == method and
         set(start) <= {"class", "test", "current", "id", "numtests", "stream"} and
         set(checkpoint) == keys and
         checkpoint["backupDeathOperation"] == operation and re.fullmatch(r"[1-9][0-9]{0,8}", checkpoint["backupDeathPid"]) and
         re.fullmatch(rf"{UUID_TEXT}:{UUID_TEXT}", checkpoint["backupDeathReceipt"]) and
         re.fullmatch(rf"{UUID_TEXT}:{UUID_TEXT}:{UUID_TEXT}(?::[a-f0-9]{{64}}){{3}}", checkpoint["backupDeathBindings"]) and
         (not before_marker or re.fullmatch(r"[a-f0-9]{64}", checkpoint["backupDeathGrantInventory"])), error)
    identity = checkpoint["backupDeathPid"], checkpoint["backupDeathReceipt"].split(":"), checkpoint["backupDeathBindings"].split(":")
    return (*identity, checkpoint["backupDeathGrantInventory"]) if before_marker else identity


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
    need(args.settings_retry is None or (args.settings_backup and not args.fresh and
         isinstance(args.settings_retry, str) and re.fullmatch(r"[a-f0-9]{64}", args.settings_retry)),
         "Settings backup requires a fresh standalone run or an exact retained cancel receipt")
    if args.settings_backup:
        need((args.fresh or args.settings_retry) and not any((args.backup_export, args.probe_write, args.ocr_activation, args.working_catalog,
                                     args.probe_directory, args.activation, args.selected_catalog, args.activation_compatibility,
                                     args.expect_home, args.settings_picker_only)),
             "Settings backup requires a fresh standalone app witness or an exact retained cancel receipt")
        return SETTINGS_BACKUP_CASE
    return BACKUP_CASE if args.backup_export else None


def select_settings_picker_case(args):
    if not args.settings_picker_only:
        return None
    need(not any((args.fresh, args.settings_backup, args.backup_export, args.probe_write, args.ocr_activation,
                  args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
                  args.activation_compatibility, args.expect_home, args.settings_retry)),
         "Settings picker cancellation requires the retained synthetic guest and a standalone invocation")
    return SETTINGS_PICKER_CASE


def select_settings_recovery_case(args):
    if args.settings_recover is None:
        return None
    need(re.fullmatch(r"[a-f0-9]{64}", args.settings_recover) and
         not any((args.fresh, args.settings_backup, args.settings_picker_only, args.backup_export, args.probe_write,
                  args.ocr_activation, args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
                  args.activation_compatibility, args.expect_home, args.settings_retry, args.settings_prior_failure)),
         "Settings receipt recovery requires the retained synthetic guest and a standalone invocation")
    return SETTINGS_RECOVERY_CASE


def select_backup_death_picker_case(args):
    if not args.backup_death_picker_only:
        return None
    need(not any((args.backup_delivery_death, args.settings_backup, args.settings_picker_only, args.settings_retry,
                  args.settings_prior_failure, args.settings_recover, args.fresh, args.backup_export, args.probe_write,
                  args.ocr_activation, args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
                  args.activation_compatibility, args.expect_home)), "Backup death picker control requires the retained guest and a standalone invocation")
    return BACKUP_DEATH_PICKER_CASE


def verify_backup_receipt_only(receipt, mixed):
    need(receipt is None or (receipt == BACKUP_DEATH_RECEIPT_ONLY and not mixed),
         "Require the exact retained process-death receipt in a standalone read-only invocation")
    return receipt


def select_tree_ancestry(enabled, mixed):
    if not enabled:
        return ()
    need(not mixed, "Actual Files ancestry requires a retained read-only standalone invocation")
    return ANCESTRY_GRANT_CASE, ANCESTRY_COLD_CASE


def select_provider_capacity(enabled, mixed):
    if not enabled:
        return None
    need(not mixed, "Actual provider capacity requires a retained standalone invocation")
    return CAPACITY_CASE


def select_independent_roots(enabled, mixed, *, derived=False):
    need(not derived or (enabled and not mixed), "Derived Files roots require a retained read-only standalone invocation")
    if not enabled:
        return ()
    need(not mixed, "Actual independent Files roots require a retained read-only standalone invocation")
    if derived:
        return (INDEPENDENT_DERIVED_CASE,)
    return INDEPENDENT_GRANT_CASE, INDEPENDENT_COLD_CASE


def require_independent_manifests(proofs):
    broad = r"android\.permission\.(?:READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|MANAGE_MEDIA|READ_MEDIA_[A-Z_]+)\b"
    need(all(not re.search(broad, proof["manifest"]) for proof in proofs), "An independent-root APK declares broad storage access")


def independent_fixture_children(folder):
    match = re.fullmatch(r"PristinePage-tree-([a-f0-9]{12})", folder)
    need(match, "Invalid independent-roots synthetic parent")
    return tuple(f"{name}-{match[1]}" for name in INDEPENDENT_CHILDREN)


def verify_independent_picker_refusal(anchor, guest, previous):
    artifacts = json.loads(anchor("artifacts.json"))
    status = json.loads(anchor("independent-roots-grants.json"))
    folder = status["fixture"]
    independent_fixture_children(folder)
    output = anchor("independent-roots-grant-instrumentation.txt")
    launches = [call for line in anchor("commands.jsonl").splitlines() if "instrument" in (call := json.loads(line))["argv"]]
    expected = {"passed": [], "failed": [INDEPENDENT_GRANT_CASE], "installedSha256": previous}
    need(artifacts["guest"] == guest and artifacts["independentRoots"] is True and status["state"] == "cleanup-unconfirmed" and
         json.loads(anchor("outcomes.json")) == expected and read_results(output, (INDEPENDENT_GRANT_CASE,)) == ([], [INDEPENDENT_GRANT_CASE]) and
         "Files did not reach exact synthetic destination" in output and "Library-Left / android:id/title" in output and
         all(f"{leaf} / android:id/title" in output for leaf in ("catalogs", "generations", "objects")) and
         "Suppressed:" not in output and len(launches) == 1 and launches[0]["argv"][-5:] ==
         [INDEPENDENT_GRANT_CASE, "-e", "libraryFixtureFolder", folder, RUNNER],
         "Independent-root retry requires the exact first chooser refusal before any grant handoff")
    return folder, artifacts["fixture"], artifacts["previous"]


def read_independent_grant_record(text, folder):
    paths = (*(f"{folder}/{name}" for name in independent_fixture_children(folder)), folder)
    fields = text.splitlines()
    prefix = "content://com.android.externalstorage.documents/tree/"
    need(len(fields) == 5 and len(text) <= 1024 and text == "\n".join(fields) + "\n" and
         re.fullmatch(r"[1-9][0-9]{0,8}", fields[0]) and re.fullmatch(r"[a-f0-9]{64}", fields[4]) and
         all(uri in {prefix + quote(f"home:{path}", safe=""), prefix + quote(f"primary:Documents/{path}", safe="")}
             for uri, path in zip(fields[1:4], paths)), "The exact independent Files grant record is invalid")
    return fields[0]


def verify_independent_cold_handoff(anchor, guest, previous):
    artifacts = json.loads(anchor("artifacts.json"))
    status = json.loads(anchor("independent-roots-grants.json"))
    folder = status["fixture"]
    independent_fixture_children(folder)
    need(artifacts["guest"] == guest and artifacts["independentRoots"] is True and
         artifacts["independentRootsRetry"] == INDEPENDENT_FIRST_PICKER_REFUSAL and status["state"] == "cleanup-unconfirmed" and
         {p: artifacts["proof"][p]["sha256"] for p in previous} == previous and
         read_results(anchor("independent-roots-grant-instrumentation.txt"), (INDEPENDENT_GRANT_CASE,)) == ([INDEPENDENT_GRANT_CASE], []),
         "A successful exact independent Files grant handoff was not authenticated")
    trace = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    launches = [c for c in trace if "instrument" in c["argv"]]
    records = [c for c in trace if c["argv"][-5:] == ["shell", "run-as", APP, "cat", f"files/independent-ancestry-grants-{folder}"]]
    pids = [c for c in trace if c["argv"][-4:] == ["shell", "pidof", APP, TEST]]
    need(len(launches) == len(records) == 1 and launches[0].get("exit") == records[0].get("exit") == 0 and
         launches[0]["argv"][-8:-2] == [INDEPENDENT_GRANT_CASE, "-e", "libraryFixtureFolder", folder, "-e", "libraryPriorIndependentFolder"] and
         not any("force-stop" in c["argv"] for c in trace), "The retained grant run is not a sole Files handoff")
    record = records[0]["stdout"]
    previous_pid = read_independent_grant_record(record, folder)
    need(len(pids) >= 2 and pids[-2].get("exit") == 1 and not pids[-2]["stdout"].strip() and
         pids[-1].get("exit") == 0 and re.fullmatch(r"[1-9][0-9]{0,8}", pids[-1]["stdout"].strip()) and
         pids[-1]["stdout"].strip() != previous_pid, "No exact cold refusal of an unexpected successor process")
    return folder, artifacts["fixture"], record, artifacts["previous"]


def verify_released_independent_guest(original, stopped):
    stable = ("name", "installation", "index", "created", "worktree", "purpose", "generation", "api", "created_by_agent")
    need(all(stopped.get(key) == original[key] for key in stable) and original["api"] == 28 and original["created_by_agent"] is True and
         stopped["owner"] is None and stopped["state"] == "stopped-released" and stopped["shutdown_verified"] is True and
         len(original["runtime_processes"]) == 2 and stopped["shutdown_processes"] == original["runtime_processes"],
         "The released guest does not match the exact independent-root predecessor")


def verify_independent_derived_installed(installed, previous):
    need(installed == previous, "The exact installed predecessor is required for the derived-root witness")


def verify_derived_receipt(anchor, guest, pair):
    artifacts = json.loads(anchor("artifacts.json"))
    status = json.loads(anchor("independent-roots-grants.json"))
    folder = status["fixture"]
    independent_fixture_children(folder)
    need(artifacts["guest"] == guest and artifacts["independentRoots"] is True and artifacts["independentRootsDerived"] is True and
         artifacts["independentRootsRetry"] is None and artifacts["independentRootsHandoff"] is None and
         status["state"] == "prior-inventory-restored" and json.loads(anchor("installed-before.json")) == artifacts["previous"] and
         json.loads(anchor("outcomes.json")) == {"passed": [INDEPENDENT_DERIVED_CASE], "failed": [], "installedSha256": pair},
         "The derived predecessor did not restore the exact grant inventory on the admitted pair")
    return folder, artifacts["fixture"], artifacts["previous"]


def settings_run_anchors(raw, expected_sha):
    manifest = raw / f"{raw.name}-final-manifest.json"
    need(sha(manifest) == expected_sha, "Retained Settings manifest changed")
    proof = json.loads(manifest.read_bytes())
    need(proof["stage"] == "verified-before-release", "Unverified Settings manifest")
    anchors = proof["evidence"] + proof["ancillaryEvidence"]

    def anchored(suffix):
        path = raw / f"{raw.name}-{suffix}"
        matching = [entry for entry in anchors if Path(entry["path"]).resolve() == path.resolve()]
        need(len(matching) == 1 and matching[0]["bytes"] == path.stat().st_size and matching[0]["sha256"] == sha(path),
             "Retained Settings anchor changed")
        return path.read_text(encoding="utf-8")
    return anchored


def verify_settings_retry_evidence(raw, expected_sha, guest, previous):
    """Require the exact completed no-write picker control immediately preceding the retained run."""
    anchor = settings_run_anchors(raw, expected_sha)
    need(json.loads(anchor("artifacts.json"))["guest"] == guest, "Settings cancel control belongs to another guest")
    need(json.loads(anchor("outcomes.json")) == {"passed": [SETTINGS_PICKER_CASE], "failed": [], "installedSha256": previous},
         "The exact no-write Settings cancel control did not pass on the predecessor pair")


def verify_ancestry_grant_refusal(anchor, guest, pair, *, before_picker=False):
    case, transcript_leaf, error = (
        (ANCESTRY_GRANT_CASE, "tree-ancestry-grant-instrumentation.txt", "The cold ancestry guest must start without retained URI grants")
        if before_picker else (ANCESTRY_CASE, "tree-ancestry-instrumentation.txt",
                               "Files retained effective write access during the read-only probe expected:<-1> but was:<0>")
    )
    transcript = anchor(transcript_leaf)
    need(json.loads(anchor("artifacts.json"))["guest"] == guest and
         json.loads(anchor("outcomes.json")) == {"passed": [], "failed": [case], "installedSha256": pair} and
         read_results(transcript, (case,)) == ([], [case]) and
         f"INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: {error}" in transcript.splitlines(),
         "Require the exact failed read-only grant boundary on the installed predecessor")


def verify_ancestry_cold_handoff(anchor, guest, pair):
    artifacts = json.loads(anchor("artifacts.json"))
    calls = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    relevant = [call for call in calls if any(op in call["argv"] for op in ("instrument", "force-stop", "pidof"))]
    need(artifacts["guest"] == guest and
         {package: artifacts["proof"][package]["sha256"] for package in pair} == pair and len(relevant) == 3 and
         ANCESTRY_GRANT_CASE in relevant[0]["argv"] and relevant[0].get("exit") == 0 and
         relevant[1]["argv"][-4:] == ["shell", "am", "force-stop", APP] and relevant[1].get("exit") == 0 and
         relevant[2]["argv"][-4:] == ["shell", "pidof", APP, TEST] and relevant[2].get("exit") == 0 and
         re.fullmatch(r"[1-9][0-9]{0,8}", relevant[2].get("stdout", "").strip()) and
         read_results(anchor("tree-ancestry-grant-instrumentation.txt"), (ANCESTRY_GRANT_CASE,)) == ([ANCESTRY_GRANT_CASE], []),
         "Require the exact cold ancestry handoff with no prior cold launch")
    return backup_death_control_destination(anchor, ANCESTRY_GRANT_CASE)


def verify_pre_marker_handoff(anchor, spent, guest, pair):
    artifacts = json.loads(anchor("artifacts.json"))
    attempt = json.loads(anchor("backup-death-attempt.json"))
    operation = attempt["operation"]
    destination, fixture = backup_death_control_destination(anchor, BACKUP_PRE_MARKER_CASE)
    kind = "single-pre-marker-death"
    need(artifacts["guest"] == guest and artifacts["backupPreMarker"] is True and artifacts["backupDeliveryDeath"] == operation and
         re.fullmatch(UUID_TEXT, operation) and {p: artifacts["proof"][p]["sha256"] for p in pair} == pair and
         attempt == {"operation": operation, "destination": destination.rsplit("/", 1)[1], "kind": kind} and
         spent == {"operation": operation, "destination": destination, "kind": kind},
         "The interrupted result is not the exact spent pre-marker attempt")
    launches = [json.loads(line)["argv"] for line in anchor("commands.jsonl").splitlines()]
    need(len([call for call in launches if "instrument" in call]) == 1,
         "An interrupted backup can resume only if it had no prior cold launch")
    checkpoint = read_backup_delivery_death(anchor("backup-death-instrumentation.txt"), operation, before_marker=True)
    return destination, fixture, operation, checkpoint


def verify_pre_marker_read_refusal(anchor, guest, pair, handoff_digest, timed_out_path):
    evidence = json.loads(anchor("artifacts.json"))
    calls = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    timeouts = [call["argv"][-3:] for call in calls if "exit" not in call and call.get("timeout") == 15 and
                "sha256sum" in call["argv"]]
    need(evidence["guest"] == guest and evidence["previous"] == pair and evidence["backupPreMarker"] is True and
         evidence["backupPreMarkerHandoff"] == handoff_digest and json.loads(anchor("installed-before.json")) == pair and
         timeouts == [["shell", "sha256sum", timed_out_path]] and
         not any(any(op in call["argv"] for op in ("instrument", "install", "push", "mkdir", "force-stop")) for call in calls),
         "Ancestry of the pre-marker handoff lacks the exact read-only checksum timeout")


def retry_pre_marker_checksum(adb, *argv, **options):
    try:
        return adb(*argv, **options)
    except subprocess.TimeoutExpired:
        direct_hash = len(argv) == 3 and argv[:2] == ("shell", "sha256sum")
        private_hash = len(argv) == 5 and argv[:4] == ("shell", "run-as", APP, "sha256sum")
        if not (direct_hash or private_hash):
            raise
        return adb(*argv, **(options | {"timeout": 30}))


def verify_backup_death_control(anchor, guest, pair):
    artifacts = json.loads(anchor("artifacts.json"))
    need(artifacts["guest"] == guest and artifacts["backupDeliveryDeath"] is None and
         json.loads(anchor("outcomes.json")) == {
             "passed": [BACKUP_DEATH_PICKER_CASE, DESCRIPTOR_CASE], "failed": [], "installedSha256": pair},
         "Require both exact no-write controls on the admitted pair")
    for leaf, case in (("death-picker-instrumentation.txt", BACKUP_DEATH_PICKER_CASE),
                       ("directory-probe-instrumentation.txt", DESCRIPTOR_CASE)):
        need(read_results(anchor(leaf), (case,)) == ([case], []), "Require both exact no-write controls")


def backup_death_control_destination(anchor, case):
    calls = [json.loads(line)["argv"] for line in anchor("commands.jsonl").splitlines()]
    targets = [call[i + 1] for call in calls for i, arg in enumerate(call[:-1])
               if arg == "libraryFixtureFolder" and case in call]
    need(len(targets) == 1 and re.fullmatch(r"PristinePage-tree-[a-f0-9]{12}", targets[0]),
         "Could not recover the exact chooser fixture")
    return f"/sdcard/Documents/{targets[0]}", json.loads(anchor("artifacts.json"))["fixture"]


def backup_death_failed_destination(anchor, host_spend, guest, app_hash):
    failed = json.loads(anchor("artifacts.json"))
    attempted = json.loads(anchor("backup-death-attempt.json"))
    destination, fixture = backup_death_control_destination(anchor, BACKUP_DEATH_CASE)
    transcript = anchor("backup-death-instrumentation.txt")
    need(failed["guest"] == guest and failed["proof"][APP]["sha256"] == app_hash and
         attempted == {"operation": host_spend["operation"], "destination": destination.rsplit("/", 1)[1],
                       "kind": "single-delivery-death"} and host_spend["destination"] == destination and
         read_results(transcript, (BACKUP_DEATH_CASE,)) == ([], [BACKUP_DEATH_CASE]) and
         "Files did not reach synthetic folder; foreground=com.android.documentsui;" in transcript and "backupDeathPid" not in transcript,
         "The retained death attempt was not the exact pre-selection refusal")
    op = attempted["operation"]
    need(re.fullmatch(UUID_TEXT, op), "The spent operation has no exact synthetic ID")
    return (destination, fixture), f"library-export-source-{op}"


def settings_failed_destination(raw, expected_sha, guest, app_sha):
    anchor = settings_run_anchors(raw, expected_sha)
    artifacts = json.loads(anchor("artifacts.json"))
    need(artifacts["guest"] == guest and artifacts["proof"][APP]["sha256"] == app_sha,
         "The earlier Settings witness did not use this synthetic guest and app")
    transcript = anchor("instrumentation.txt")
    first_refused = not artifacts["previous"] and "Files did not reach root drawer; foreground=app.pristinepage.debug;" in transcript
    preflight_refused = artifacts.get("settingsCancel") and "Unexpected retained OcrResult inventory expected:<[0]> but was:<[1]>" in transcript
    navigation_refused = artifacts.get("settingsCancel") and \
        "Files did not reach primary storage; foreground=com.android.documentsui;" in transcript
    need(read_results(transcript, (SETTINGS_BACKUP_CASE,)) == ([], [SETTINGS_BACKUP_CASE]) and
         (first_refused or preflight_refused or navigation_refused),
         "The retained Settings failure is not the exact observed pre-selection boundary")
    calls = [json.loads(line)["argv"] for line in anchor("commands.jsonl").splitlines()]
    candidates = [call[i + 1] for call in calls for i, value in enumerate(call[:-1])
                  if value == "libraryFixtureFolder" and SETTINGS_BACKUP_CASE in call]
    need(len(candidates) == 1 and re.fullmatch(r"PristinePage-tree-[a-f0-9]{12}", candidates[0]),
         "The original Settings destination could not be recovered")
    return (f"/sdcard/Documents/{candidates[0]}", artifacts["fixture"]), artifacts.get("settingsPriorFailure"), bool(navigation_refused)


def spend_settings_retry(raw, digest, attempt):
    path = raw / f"settings-retry-spent-{digest}.json"
    try:
        with path.open("x", encoding="utf-8") as file:
            json.dump(attempt, file)
            file.flush()
            os.fsync(file.fileno())
    except FileExistsError as error:
        raise RuntimeError("This Settings retry is already spent") from error


def verify_unchanged_settings_destination(adb, guest_root, files, backup_name=None):
    namespaces = {"catalogs", "generations", "objects"}
    ident = r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
    locator = rf"(?:catalogs/{ident}\.sqlite|generations/{ident}\.complete|objects/[a-f0-9]{{64}})"
    need({relative.split("/")[0] for relative in files} == namespaces and
         all(re.fullmatch(locator, file) and re.fullmatch(r"[a-f0-9]{64}", digest) for file, digest in files.items()),
         "Invalid retained synthetic inventory")
    expected_root = namespaces if backup_name is None else namespaces | {backup_name}
    need(set(adb("shell", "ls", "-A", "-1", guest_root).splitlines()) == expected_root,
         "The first Settings destination is not unchanged; the backup must not be repeated")
    for namespace in namespaces:
        leaves = {relative.split("/", 1)[1] for relative in files if relative.startswith(f"{namespace}/")}
        need(set(adb("shell", "ls", "-A", "-1", f"{guest_root}/{namespace}").splitlines()) == leaves,
             "The first Settings destination contains changed fixture entries")
    for relative, digest in files.items():
        need(adb("shell", "sha256sum", f"{guest_root}/{relative}").split() == [digest, f"{guest_root}/{relative}"],
             "The first Settings destination contains changed fixture bytes")


def stage_synthetic_files(adb, fixture, destination, files):
    # Claim the leaf first; do not touch a child if exclusive mkdir fails.
    adb("shell", "mkdir", destination)
    namespaces = sorted({relative.split("/", 1)[0] for relative in files})
    adb("shell", "mkdir", *(f"{destination}/{namespace}" for namespace in namespaces))
    for relative in files:
        adb("push", fixture / relative, f"{destination}/{relative}")
    verify_unchanged_settings_destination(adb, destination, files)


def exercise_independent_roots(run, adb, ns, guest_root, folder, files, hashes, prior_folder, retained_record=None, *, derived=False):
    children = independent_fixture_children(folder)
    def verify_fixtures():
        recheck_target(run, ns)
        need(adb("shell", "ls", "-A", "-1", guest_root).splitlines() == sorted(children),
             "The independent roots anchor gained unexpected entries")
        for name in children:
            verify_unchanged_settings_destination(adb, f"{guest_root}/{name}", files)

    verify_fixtures()
    require_cold_backup_app(adb)
    passed, failed = [], []
    status_path = run.path("independent-roots-grants.json")
    run.save(status_path, {"fixture": folder, "state": "cleanup-unconfirmed"}, durable=True)
    try:
        schedule = ((INDEPENDENT_DERIVED_CASE, "derived"),) if derived else (
            (INDEPENDENT_GRANT_CASE, "grant"), (INDEPENDENT_COLD_CASE, "cold"))
        for case, phase in schedule if retained_record is None else schedule[1:]:
            recheck_target(run, ns)
            if phase == "cold":
                # A different matching process is not authority to stop it.
                record = f"files/independent-ancestry-grants-{folder}"
                size = adb("shell", "run-as", APP, "stat", "-c", "%s", record).strip()
                need(size.isdecimal() and 0 < int(size) <= 1024, "The exact read grant handoff is not readable")
                text = adb("shell", "run-as", APP, "cat", record)
                pid = read_independent_grant_record(text, folder)
                need(len(text) == int(size) and (retained_record is None or text == retained_record),
                     "The retained independent Files grant record has changed")
                active = adb("shell", "pidof", APP, TEST, allowed=(0, 1)).split()
                if active:
                    need(retained_record is None and active == [pid] and adb("shell", "pidof", APP, allowed=(0, 1)).split() == active,
                         "A different process blocks the cold read; read-grant cleanup is unconfirmed")
                    adb("shell", "am", "force-stop", APP)
                require_cold_backup_app(adb)
            prior = ("-e", "libraryPriorIndependentFolder", prior_folder) if prior_folder and phase in ("grant", "derived") else ()
            output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", case,
                         "-e", "libraryFixtureFolder", folder, *prior, RUNNER, timeout=155)
            run.path(f"independent-roots-{phase}-instrumentation.txt").write_text(output, encoding="utf-8")
            successes, failures = read_results(output, (case,))
            passed.extend(successes)
            failed.extend(failures)
            if failures:
                break
            if phase in ("cold", "derived"):
                run.save(status_path, {"fixture": folder, "state": "prior-inventory-restored"}, durable=True)
    finally:
        verify_fixtures()
    return {"passed": passed, "failed": failed, "installedSha256": hashes}


def verify_settings_ocr(db, expected):
    rows = db.execute("""
        SELECT r.pageId,r.recipeRevision,r.sourcePixelSha256,r.engineId,r.engineVersion,r.layout,
               w.resultJobId,j.canonicalInputSha256,w.projectionVersion,j.state,
               CASE WHEN length(CAST(coalesce(r.verbatimText,r.indexedText) AS BLOB))<=4096
                    THEN CAST(coalesce(r.verbatimText,r.indexedText) AS BLOB) END
          FROM OcrResult r LEFT JOIN OcrPageWork w ON w.pageId=r.pageId
          LEFT JOIN Job j ON j.jobId=w.resultJobId LIMIT 2
    """).fetchall()
    need(len(rows) == 1 and rows[0][:9] == expected[:9] and rows[0][9] == "SUCCEEDED" and
         isinstance(rows[0][10], bytes) and sha256(rows[0][10]).hexdigest() == expected[9],
         "Settings backup lost the exact OCR text or producer binding")


def settings_completed_destination(raw, digest, guest, previous):
    anchor = settings_run_anchors(raw, digest)
    artifacts = json.loads(anchor("artifacts.json"))
    need(artifacts["guest"] == guest and {package: artifacts["proof"][package]["sha256"] for package in previous} == previous and
         json.loads(anchor("outcomes.json")) == {"passed": [SETTINGS_BACKUP_CASE], "failed": [], "installedSha256": previous},
         "No exact completed Settings backup on the admitted synthetic guest")
    need(read_results(anchor("instrumentation.txt"), (SETTINGS_BACKUP_CASE,)) == ([SETTINGS_BACKUP_CASE], []),
         "Instrumentation did not pass the exact Settings publication")
    claim = json.loads(anchor("claim.json"))
    proof = json.loads((raw / f"{raw.name}-final-manifest.json").read_bytes())
    need(claim["name"] == guest and claim["generation"] == proof["generation"] and claim["state"] == "running" and
         claim["created_by_agent"] is True and claim["purpose"] == "pristinepage-library-app-tree" and claim["api"] == 28 and
         len(claim["runtime_processes"]) == 2, "The original Settings execution has no exact synthetic guest claim")
    calls = [json.loads(line) for line in anchor("commands.jsonl").splitlines()]
    marker_path = rf"(/sdcard/Documents/PristinePage-tree-[a-f0-9]{{12}}/PristinePage-backup-({UUID_TEXT}))/generations/\2\.complete"
    markers = [(match, call["stdout"]) for call in calls if call["argv"][-3:-1] == ["shell", "cat"] and call.get("exit") == 0
               if (match := re.fullmatch(marker_path, call["argv"][-1]))]
    witnesses = [call["stdout"] for call in calls if
                 call["argv"][-5:] == ["shell", "run-as", APP, "cat", SETTINGS_WITNESS_FILE] and call.get("exit") == 0]
    need(len(markers) == len(witnesses) == 1, "Exactly one anchored backup marker and private witness are required")
    (match, marker_text), witness = markers[0], witnesses[0]
    backup, generation = match.groups()
    marker = marker_text.splitlines()
    need(len(marker) == 7 and marker[0] == "pristinepage-library-v1" and re.fullmatch(UUID_TEXT, marker[1]) and
         marker[2:4] == [generation, "-"] and re.fullmatch(r"[1-9][0-9]{0,5}", marker[4]) and
         re.fullmatch(r"[1-9][0-9]{0,6}", marker[5]) and 0 < int(marker[5]) <= 2 * 1024 * 1024 and
         re.fullmatch(r"[a-f0-9]{64}", marker[6]), "The retained backup marker is invalid")
    need(len(witness.splitlines()) == 15 and witness.splitlines()[6:9] == [marker[1], generation, backup.rsplit("/", 1)[1]],
         "The anchored private witness does not identify the exact closed backup")
    catalog = raw / "settings-closed-catalog.sqlite"
    need(catalog.stat().st_size == int(marker[5]) and sha(catalog) == marker[6],
         "The retained catalog does not match the anchored closed-folder readback")
    return (backup, generation, marker_text, artifacts["fixture"], witness), claim


def verify_completed_destination(adb, backup, generation, marker_text, fixture, witness):
    parent, folder = backup.rsplit("/", 1)
    verify_unchanged_settings_destination(adb, parent, fixture, backup_name=folder)
    verify_closed_backup(adb, backup, generation, marker_text)
    need(adb("shell", "run-as", APP, "cat", SETTINGS_WITNESS_FILE) == witness, "The private publication witness changed")


def verify_closed_backup(adb, backup, generation, marker_text):
    need(set(adb("shell", "ls", "-A", "-1", backup).splitlines()) ==
         {"catalogs", "generations", "objects", "staging"}, "Completed destination layout changed")
    need(not adb("shell", "ls", "-A", "-1", f"{backup}/staging").strip(), "Unexpected staging bytes in the completed backup")
    for namespace, leaf in (("catalogs", f"{generation}.sqlite"), ("generations", f"{generation}.complete"),
                            ("objects", BACKUP_ORIGINAL)):
        need(adb("shell", "ls", "-A", "-1", f"{backup}/{namespace}").splitlines() == [leaf],
             "Completed destination inventory changed")
    need(adb("shell", "cat", f"{backup}/generations/{generation}.complete") == marker_text, "Completed marker identity drifted")
    for relative, digest in ((f"catalogs/{generation}.sqlite", marker_text.splitlines()[6]),
                             (f"objects/{BACKUP_ORIGINAL}", BACKUP_ORIGINAL)):
        path = f"{backup}/{relative}"
        need(adb("shell", "sha256sum", path).split() == [digest, path], "Completed backup bytes changed")


def verify_completed_guest(original, current):
    stable = ("name", "installation", "index", "created", "worktree", "purpose")
    need(all(current.get(k) == original[k] for k in stable) and current.get("api") == 28 and
         current.get("created_by_agent") is True and
         any(entry["generation"] == original["generation"] and entry["shutdown_processes"] == original["runtime_processes"]
              for entry in current["ownership_history"]), "The current allocator claim is not the retained publication guest")


def protected_completed_backups(raw, guest):
    cold = settings_run_anchors(raw / "picker-adapter-library-tree-bb9159f6e027", SETTINGS_COLD_MANIFEST)
    ordinary_baseline = json.loads(cold("recovery-after.json"))
    need(json.loads(cold("recovery-before.json")) == ordinary_baseline, "The original cold backup baseline drifted")
    original = raw / "picker-adapter-library-tree-e3a9881e2bdd"
    anchor = settings_run_anchors(original, SETTINGS_COMPLETED_MANIFEST)
    pair = json.loads(anchor("outcomes.json"))["installedSha256"]
    completed, claim = settings_completed_destination(original, SETTINGS_COMPLETED_MANIFEST, guest, pair)
    death = settings_run_anchors(raw / "picker-adapter-library-tree-329ddeb38f65", BACKUP_DEATH_RECEIPT_ONLY)
    attempt = json.loads(death("backup-death-attempt.json"))
    target, fixture = backup_death_control_destination(death, BACKUP_DEATH_CASE)
    operation = attempt["operation"]
    _, (_, generation), _ = read_backup_delivery_death(death("backup-death-instrumentation.txt"), operation)
    recovered = settings_run_anchors(raw / "picker-adapter-library-tree-d0a368cd0ec6", BACKUP_DELIVERY_RECOVERY_PASSED)
    baseline = json.loads(recovered("death-after-reopen.json"))
    need(baseline == json.loads(recovered("death-before-reopen.json")) and baseline["operation"] == operation and
         attempt["destination"] == target.rsplit("/", 1)[1], "The earlier death recovery lost its exact baseline")
    return completed, claim, ordinary_baseline, (target, fixture, generation, operation, baseline)


def verify_interrupted_external(adb, fixture_root, fixture, generation, payloads):
    name = f"PristinePage-backup-{generation}"
    backup = f"{fixture_root}/{name}"
    verify_unchanged_settings_destination(adb, fixture_root, fixture, backup_name=name)
    namespaces = {"generations", "staging", "catalogs", "objects"}
    need(set(adb("shell", "ls", "-A", "-1", backup).splitlines()) == namespaces,
         "Interrupted backup namespaces changed")
    for namespace in sorted(namespaces):
        expected = sorted(key.split("/", 1)[1] for key in payloads if key.startswith(namespace + "/"))
        need(adb("shell", "ls", "-A", "-1", f"{backup}/{namespace}").splitlines() == expected,
             "Interrupted backup has an unexpected marker, stage or payload")
    for leaf, (size, digest) in payloads.items():
        path = f"{backup}/{leaf}"
        need(adb("shell", "stat", "-c", "%F:%s", path).strip() == f"regular file:{size}" and
             adb("shell", "sha256sum", path).split() == [digest, path], "Interrupted backup payload changed")


def interrupted_backup_preservation(raw, recovery, guest, pair):
    writer = settings_run_anchors(raw / "picker-adapter-library-tree-b0b27bd6debd", PRE_MARKER_WRITER_STOPPED)
    spent = json.loads((raw / f"settings-retry-spent-{CAPACITY_PASSED}.json").read_bytes())
    target, fixture, operation, (_, (library, generation), _, _) = verify_pre_marker_handoff(writer, spent, guest, pair)
    baseline = json.loads(recovery("death-after-reopen.json"))
    need(baseline["operation"] == operation and json.loads(recovery("death-before-reopen.json")) == baseline,
         "Interrupted-publication fingerprint baseline changed")

    def check(adb):
        isolated = f"files/library-export-source-{operation}"
        current = settings_private_baseline(adb, f"{isolated}/files", before_marker=True)
        need(current == baseline, "The predecessor's interrupted private backup changed")
        marker = verify_backup_death_intent(adb, isolated, operation, target.rsplit("/", 1)[1], None, current, before_marker=True).splitlines()
        need(len(marker) == 7 and marker[:5] == ["pristinepage-library-v1", library, generation, "-", "8"] and
             re.fullmatch(r"[1-9][0-9]{0,6}", marker[5]) and re.fullmatch(r"[a-f0-9]{64}", marker[6]),
             "The protected interruption lost its exact catalog binding")
        verify_interrupted_external(adb, target, fixture, generation,
                                    {f"catalogs/{generation}.sqlite": (int(marker[5]), marker[6]),
                                     f"objects/{BACKUP_ORIGINAL}": (81, BACKUP_ORIGINAL)})
    return check


def settings_private_baseline(adb, root="files", *, before_marker=False):
    """Observe bounded evidence without starting the app or deriving authority from a prior record."""
    def private(*arguments):
        return adb("shell", "run-as", APP, *arguments)

    leaves = [leaf for leaf in private("ls", "-A", "-1", root).splitlines()
              if leaf.startswith("library-out-") or leaf == "library-backup-acknowledged"]
    need(len(leaves) == 1 and re.fullmatch(rf"library-out-{UUID_TEXT}", leaves[0]),
         "Expected one retained active backup attempt and no archive")
    attempt = f"{root}/{leaves[0]}"
    need(private("stat", "-c", "%F", attempt).strip() == "directory", "The backup attempt is not an exact directory")
    need(private("stat", "-c", "%F", f"{root}/library-settings").strip() == "directory", "The active pointer parent changed")
    records = private("ls", "-A", "-1", attempt).splitlines()
    required = {"export.intent", "catalog.sqlite", "export.catalog.owner"} if before_marker else {"export.intent", "export.complete"}
    need(2 <= len(records) <= 16 and len(set(records)) == len(records) and required <= set(records) and
         (not before_marker or "export.complete" not in records) and "export.ack" not in records and
         all(re.fullmatch(r"[a-zA-Z0-9_.-]{1,128}", leaf) and leaf not in (".", "..") for leaf in records),
         "The retained backup journal inventory is not an unacknowledged completion")
    fingerprints = {}
    for path in [f"{root}/library-settings/active-library", *(f"{attempt}/{leaf}" for leaf in records)]:
        metadata = re.fullmatch(r"regular file:([1-9][0-9]{0,6})", private("stat", "-c", "%F:%s", path).strip())
        need(metadata and int(metadata[1]) <= 2 * 1024 * 1024,
             "The retained backup journal extent is unexpected")
        digest = private("sha256sum", path).split()
        need(len(digest) == 2 and digest[1] == path and re.fullmatch(r"[a-f0-9]{64}", digest[0]),
             "The retained backup journal hash is unavailable")
        fingerprints[path] = [int(metadata[1]), digest[0]]
    return dict(operation=leaves[0].removeprefix("library-out-"), files=fingerprints)


def require_cold_backup_app(adb):
    need(not adb("shell", "pidof", APP, TEST, allowed=(0, 1)).strip(), "The app and test process must be absent")


def finish_pre_marker_outcome(outcome, preservation_checks, record, *, label="Pre-marker backup"):
    failures = []
    for check in preservation_checks:
        try:
            check()
        except Exception as failure:
            failures.append(failure)
    if failures:
        raise ExceptionGroup(f"{label} preservation checks failed", failures)
    if outcome is not None:
        record(outcome)


def inspect_abnormal_backup_death(run, adb, fixture_root, isolated, operation):
    """Retain only bounded listings of the exact test-owned directories; no guessed paths or write retry."""
    private_root = f"{isolated}/files"
    observations = {}
    for label, arguments in (
        ("destination", ("shell", "ls", "-A", "-1", fixture_root)),
        ("privateStore", ("shell", "run-as", APP, "ls", "-A", "-1", private_root)),
        ("privateAttempt", ("shell", "run-as", APP, "ls", "-A", "-1", f"{private_root}/library-out-{operation}")),
    ):
        try:
            result = adb(*arguments, timeout=12, allowed=(0, 1, 2)).splitlines()
            observations[label] = result if len(result) <= 24 and all(len(line) <= 256 for line in result) else None
        except (RuntimeError, subprocess.SubprocessError):
            observations[label] = None
    run.save(run.path("death-abnormal-inventory.json"), observations, durable=True)


def verify_backup_death_intent(adb, isolated, operation, parent, marker, baseline, *, before_marker=False):
    attempt = f"{isolated}/files/library-out-{operation}"
    leaves = {}
    for leaf in (("export.intent",) if before_marker else ("export.intent", "export.complete")):
        path = f"{attempt}/{leaf}"
        extent, digest = baseline["files"][path]
        need(extent <= 5000, "The retained private export record exceeds its limit")
        text = adb("shell", "run-as", APP, "cat", path)
        raw = text.encode("ascii")
        need(len(raw) == extent and sha256(raw).hexdigest() == digest, "The retained export record changed")
        leaves[leaf] = text
    text = leaves["export.intent"]
    fields = text.splitlines()
    trees = {"content://com.android.externalstorage.documents/tree/" + quote(value, safe="")
             for value in (f"primary:Documents/{parent}", f"home:{parent}")}
    retained_marker = "\n".join(fields[11:18]) + "\n"
    need(len(fields) == 19 and fields[:2] == ["pristinepage-library-export-intent-v2", operation] and
         fields[6:9] == ["pristinepage-library-grant-v1", operation, "3"] and fields[9] in trees and
         (marker is None if before_marker else retained_marker == marker) and text == "\n".join(fields) + "\n" and
         sha256(text[:-(len(fields[-1]) + 1)].encode("ascii")).hexdigest() == fields[-1] and
         (before_marker or leaves["export.complete"] == f'pristinepage-library-export-complete-v2\n{operation}\n{sha256(text.encode("ascii")).hexdigest()}\n'),
         "The external marker does not match the exact completed private intent" if not before_marker else
         "The interrupted publication does not match its exact private intent")
    return retained_marker


def exercise_backup_delivery_death(run, adb, ns, raw, fixture_root, fixture, fixture_digest, operation, hashes, authorization, *, before_marker=False):
    """Spend a new isolated synthetic attempt once; no retry or cleanup even if the test loses its result."""
    require_cold_backup_app(adb)
    folder = fixture_root.rsplit("/", 1)[1]
    isolated = f"files/library-export-source-{operation}"
    need(isolated.rsplit("/", 1)[1] not in adb("shell", "run-as", APP, "ls", "-A", "-1", "files").splitlines(),
         "The isolated test source is not new")
    verify_unchanged_settings_destination(adb, fixture_root, fixture)
    extras = ("-e", "libraryBackupDeathOperation", operation)
    # Spend one exact test admission, never replay a writer after an uncertain result.
    kind = "single-pre-marker-death" if before_marker else "single-delivery-death"
    spend_settings_retry(raw.parent, authorization, dict(operation=operation, destination=fixture_root, kind=kind))
    run.save(run.path("backup-death-attempt.json"), {"operation": operation, "destination": folder, "kind": kind}, durable=True)
    try:
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", BACKUP_PRE_MARKER_CASE if before_marker else BACKUP_DEATH_CASE,
                     "-e", "libraryFixtureFolder", folder, "-e", "libraryFixtureOriginal", fixture_digest,
                     *extras, RUNNER, timeout=150, allowed=(0, 1))
        run.path("backup-death-instrumentation.txt").write_text(output, encoding="utf-8")
        observed = read_backup_delivery_death(output, operation, before_marker=before_marker)
    except (RuntimeError, subprocess.SubprocessError):
        recheck_target(run, ns)
        inspect_abnormal_backup_death(run, adb, fixture_root, isolated, operation)
        raise
    recheck_target(run, ns)
    if before_marker:
        return complete_pre_marker_death(run, adb, ns, raw, fixture_root, fixture, operation, hashes, observed)
    return complete_backup_delivery_death(run, adb, ns, raw, fixture_root, fixture, operation, hashes, observed)


def complete_pre_marker_death(run, adb, ns, raw, fixture_root, fixture, operation, hashes, checkpoint):
    old_pid, (library, generation), bindings, grants = checkpoint
    require_cold_backup_app(adb)
    isolated = f"files/library-export-source-{operation}"
    folder = fixture_root.rsplit("/", 1)[1]
    backup_name = f"PristinePage-backup-{generation}"
    backup = f"{fixture_root}/{backup_name}"
    before = settings_private_baseline(adb, f"{isolated}/files", before_marker=True)
    need(before["operation"] == operation, "The interrupted private attempt does not match the terminated operation")
    marker = verify_backup_death_intent(adb, isolated, operation, folder, None, before, before_marker=True).splitlines()
    need(len(marker) == 7 and marker[:5] == ["pristinepage-library-v1", library, generation, "-", "8"] and
         re.fullmatch(r"[1-9][0-9]{0,6}", marker[5]) and int(marker[5]) <= 2 * 1024 * 1024 and
         re.fullmatch(r"[a-f0-9]{64}", marker[6]), "The private intent does not bind the stopped generation and catalog")
    catalog_name = f"{generation}.sqlite"
    payloads = {f"objects/{BACKUP_ORIGINAL}": (81, BACKUP_ORIGINAL), f"catalogs/{catalog_name}": (int(marker[5]), marker[6])}
    spends = {sha256(leaf.encode("ascii")).hexdigest(): proof for leaf, proof in payloads.items()}
    attempt = f"{isolated}/files/library-out-{operation}"
    need({Path(name).name for name in before["files"] if name.startswith(attempt + "/")} ==
         {"export.intent", "catalog.sqlite", "export.catalog.owner", *(f"{key}.spent" for key in spends)},
         "The interrupted attempt does not hold exactly the two closed payload move spends and no completion")
    for name in ("catalog.sqlite", "export.catalog.owner"):
        need(before["files"][f"{attempt}/{name}"] == [int(marker[5]), marker[6]],
             "The private catalog stage differs from the interrupted intent")
    def stage_owner():
        paths = [f"{attempt}/{name}" for name in ("catalog.sqlite", "export.catalog.owner")]
        ids = [adb("shell", "run-as", APP, "stat", "-c", "%d:%i:%h", path).strip() for path in paths]
        need(ids[0] == ids[1] and re.fullmatch(r"[0-9]+:[1-9][0-9]*:2", ids[0]),
             "The interrupted catalog no longer has its exact second hard-link name")
        return ids[0]
    before_owner = stage_owner()
    for key, (size, digest) in spends.items():
        path = f"{attempt}/{key}.spent"
        extent, recorded = before["files"][path]
        need(extent <= 512, "The interrupted move spend exceeds its bound")
        text = adb("shell", "run-as", APP, "cat", path)
        fields = text.splitlines()
        need(len(text) == extent and sha256(text.encode("ascii")).hexdigest() == recorded and
             text == "\n".join(fields) + "\n" and len(fields) == 6 and fields[:2] == ["pristinepage-library-move-spent-v1", key] and
             re.fullmatch(r"[a-f0-9]{64}", fields[2]) and re.fullmatch(r"[0-9]+:[1-9][0-9]*", fields[3]) and
             fields[4:] == [digest, str(size)], "The interrupted move spend does not bind its exact payload")

    def verify_external():
        verify_interrupted_external(adb, fixture_root, fixture, generation, payloads)

    verify_external()
    catalog = raw / "interrupted-publication-catalog.sqlite"
    adb("pull", f"{backup}/catalogs/{catalog_name}", catalog, timeout=20)
    need(catalog.stat().st_size == int(marker[5]) and sha(catalog) == marker[6], "Interrupted catalog differs from the private intent")
    verify_settings_catalog(catalog, BACKUP_ORIGINAL, 81, bindings, title="Synthetic default backup")
    run.save(run.path("death-before-reopen.json"), before, durable=True)
    try:
        recheck_target(run, ns)
        require_cold_backup_app(adb)
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", BACKUP_PRE_MARKER_RECOVERY_CASE,
                     "-e", "libraryBackupDeathOperation", operation, "-e", "libraryBackupDeathGeneration", generation,
                     "-e", "libraryBackupDeathLibrary", library, "-e", "libraryBackupDeathPreviousPid", old_pid,
                     "-e", "libraryFixtureFolder", folder, "-e", "libraryBackupDeathGrantInventory", grants, RUNNER, timeout=120)
        run.path("backup-death-recovery-instrumentation.txt").write_text(output, encoding="utf-8")
    finally:
        recheck_target(run, ns)
        verify_external()
        after = settings_private_baseline(adb, f"{isolated}/files", before_marker=True)
        run.save(run.path("death-after-reopen.json"), after, durable=True)
        need(after == before and stage_owner() == before_owner,
             "Cold interruption inspection changed the private intent, payload spends, catalog owner or pointer")
    passed, failed = read_results(output, (BACKUP_PRE_MARKER_RECOVERY_CASE,))
    return {"passed": passed, "failed": failed, "installedSha256": hashes, "preMarkerDeathPid": old_pid}


def complete_backup_delivery_death(run, adb, ns, raw, fixture_root, fixture, operation, hashes, checkpoint):
    old_pid, (library, generation), bindings = checkpoint
    require_cold_backup_app(adb)
    folder = fixture_root.rsplit("/", 1)[1]
    isolated = f"files/library-export-source-{operation}"
    backup_name = f"PristinePage-backup-{generation}"
    backup = f"{fixture_root}/{backup_name}"
    marker_path = f"{backup}/generations/{generation}.complete"
    marker_extent = adb("shell", "stat", "-c", "%s", marker_path).strip()
    need(re.fullmatch(r"[1-9][0-9]{0,2}", marker_extent) and int(marker_extent) <= 256,
         "Invalid bounded death-witness marker")
    marker_text = adb("shell", "cat", marker_path)
    marker = marker_text.splitlines()
    need(len(marker) == 7 and marker[:5] == ["pristinepage-library-v1", library, generation, "-", "8"] and
         marker_text == "\n".join(marker) + "\n" and re.fullmatch(r"[1-9][0-9]{0,6}", marker[5]) and
         int(marker[5]) <= 2 * 1024 * 1024 and re.fullmatch(r"[a-f0-9]{64}", marker[6]), "Invalid closed death-witness marker")
    need(adb("shell", "sha256sum", marker_path).split() == [sha256(marker_text.encode("ascii")).hexdigest(), marker_path],
         "Closed death-witness marker does not have its literal canonical bytes")
    verify_unchanged_settings_destination(adb, fixture_root, fixture, backup_name=backup_name)
    verify_closed_backup(adb, backup, generation, marker_text)
    catalog = raw / "interrupted-delivery-catalog.sqlite"
    adb("pull", f"{backup}/catalogs/{generation}.sqlite", catalog, timeout=20)
    need(catalog.stat().st_size == int(marker[5]) and sha(catalog) == marker[6], "The independently pulled backup catalog changed")
    verify_settings_catalog(catalog, BACKUP_ORIGINAL, 81, bindings, title="Synthetic default backup")
    before = settings_private_baseline(adb, f"{isolated}/files")
    need(before["operation"] == operation, "The durable attempt does not match the terminated operation")
    verify_backup_death_intent(adb, isolated, operation, folder, marker_text, before)
    run.save(run.path("death-before-reopen.json"), before, durable=True)
    extras = ("-e", "libraryBackupDeathOperation", operation)
    try:
        output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", BACKUP_DEATH_RECOVERY_CASE,
                     *extras, "-e", "libraryBackupDeathGeneration", generation, "-e", "libraryBackupDeathPreviousPid", old_pid,
                     "-e", "libraryBackupDeathLibrary", library, "-e", "libraryFixtureFolder", folder, RUNNER, timeout=100)
        run.path("backup-death-recovery-instrumentation.txt").write_text(output, encoding="utf-8")
    finally:
        recheck_target(run, ns)
        verify_unchanged_settings_destination(adb, fixture_root, fixture, backup_name=backup_name)
        verify_closed_backup(adb, backup, generation, marker_text)
        after = settings_private_baseline(adb, f"{isolated}/files")
        run.save(run.path("death-after-reopen.json"), after, durable=True)
        need(before == after, "Recovery changed the isolated backup attempt or pointer")
    passed, failed = read_results(output, (BACKUP_DEATH_RECOVERY_CASE,))
    run.save(run.path("outcomes.json"), {"passed": passed, "failed": failed, "installedSha256": hashes,
                                         "deliveryDeathPid": old_pid}, durable=True)
    print(f"Cold recovery after backup delivery death: {len(passed)} passed, {len(failed)} failed.", flush=True)
    return bool(failed)


def verify_settings_catalog(path, digest, extent, expected, expected_ocr=None, *, title="Synthetic Settings backup"):
    album, page, source, pixels, revision1, revision2 = expected
    cli = shutil.which("sqlite3")
    need(cli is not None, "The existing FTS4-capable SQLite CLI is required")
    checks = subprocess.run([cli, "-batch", "-bail", "-readonly", str(path), "PRAGMA integrity_check; PRAGMA foreign_key_check;"],
                            capture_output=True, text=True, timeout=15, check=True)
    need(checks.stdout.splitlines() == ["ok"], "Settings backup catalog failed independent integrity or foreign-key readback")
    with closing(sqlite3.connect(path.as_uri() + "?mode=ro&immutable=1", uri=True)) as db:
        for table in ("Document", "Page", "SourceBlob", "Source", "LibraryScanName"):
            need(db.execute(f"SELECT COUNT(*) FROM {table}").fetchone() == (1,), "Settings backup included or omitted unrelated records")
        if expected_ocr is None:
            need(db.execute("SELECT 1 FROM OcrResult LIMIT 1").fetchone() is None, "Settings backup included unexpected OCR text")
        else:
            verify_settings_ocr(db, expected_ocr)
        rows = db.execute("""
            SELECT d.documentId,d.title,d.documentRevision,p.pageId,p.currentRecipeRevision,p.ordinal,s.sourceId,
                   s.pixelSha256,b.byteSha256,b.byteCount,n.filename,n.documentId
              FROM Document d JOIN Page p ON p.documentId=d.documentId JOIN Source s ON s.sourceId=p.sourceId
              JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId JOIN LibraryScanName n ON n.pageId=p.pageId LIMIT 2
        """).fetchall()
        need(rows == [(album, title, 3, page, 2, 0, source, pixels, digest, extent, f"Scan {page}.png", album)],
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
    recovery_case = select_settings_recovery_case(args)
    backup_case = select_backup_case(args)
    death_picker_case = select_backup_death_picker_case(args)
    death_receipt = verify_backup_receipt_only(args.backup_death_receipt_only,
        any((args.fresh, args.backup_delivery_death, args.backup_death_retry, death_picker_case, picker_case,
             recovery_case, backup_case, args.probe_write, args.ocr_activation, args.working_catalog, args.probe_directory,
             args.activation, args.selected_catalog, args.activation_compatibility, args.expect_home,
             args.settings_retry, args.settings_prior_failure, args.tree_ancestry)))
    ancestry_cases = select_tree_ancestry(args.tree_ancestry,
        any((args.fresh, args.backup_delivery_death, args.backup_death_retry, death_picker_case, picker_case,
             recovery_case, backup_case, args.probe_write, args.ocr_activation, args.working_catalog, args.probe_directory,
             args.activation, args.selected_catalog, args.activation_compatibility, args.expect_home,
             args.settings_retry, args.settings_prior_failure)))
    ancestry_resume = args.tree_ancestry_handoff
    need(ancestry_resume is None or (ancestry_cases and ancestry_resume == ANCESTRY_RETAINED_HANDOFF),
         "An ancestry handoff requires the exact stopped run and standalone ancestry mode")
    capacity_case = select_provider_capacity(args.provider_capacity,
        any((args.fresh, args.backup_delivery_death, args.backup_death_retry, death_receipt, death_picker_case,
             ancestry_cases, picker_case, recovery_case, backup_case, args.probe_write, args.ocr_activation,
             args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
             args.activation_compatibility, args.expect_home, args.settings_retry, args.settings_prior_failure)))
    if args.backup_delivery_death:
        need(not any((args.fresh, args.settings_picker_only, args.settings_recover, backup_case, args.probe_write,
                      args.ocr_activation, args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
                      args.activation_compatibility, args.expect_home, args.settings_retry, args.settings_prior_failure)),
             "Backup delivery death needs the exact retained guest and a standalone invocation")
    before_marker = args.backup_pre_marker
    pre_marker_handoff = args.backup_pre_marker_handoff
    need(pre_marker_handoff is None or before_marker, "A pre-marker handoff is only admitted in pre-marker mode")
    if before_marker:
        need(not any((args.fresh, args.backup_delivery_death, args.backup_death_retry, death_receipt, death_picker_case,
                      ancestry_cases, capacity_case, picker_case, recovery_case, backup_case, args.probe_write,
                      args.ocr_activation, args.working_catalog, args.probe_directory, args.activation,
                      args.selected_catalog, args.activation_compatibility, args.expect_home, args.settings_retry,
                      args.settings_prior_failure)),
             "Pre-marker death needs an exact retained guest and a standalone invocation")
    independent_derived = args.independent_roots_derived
    independent_cases = select_independent_roots(args.independent_roots,
        any((args.fresh, args.backup_delivery_death, args.backup_death_retry, death_receipt, death_picker_case,
             ancestry_cases, before_marker, capacity_case, picker_case, recovery_case, backup_case, args.probe_write,
             args.ocr_activation, args.working_catalog, args.probe_directory, args.activation, args.selected_catalog,
              args.activation_compatibility, args.expect_home, args.settings_retry, args.settings_prior_failure)),
        derived=independent_derived)
    need(args.independent_roots_retry is None or (independent_cases and args.independent_roots_retry == INDEPENDENT_FIRST_PICKER_REFUSAL),
         "Only the exact first independent-root chooser refusal can be retried")
    independent_handoff = args.independent_roots_handoff
    need(independent_handoff is None or (independent_cases and not args.independent_roots_retry and independent_handoff == INDEPENDENT_GRANTS_RETAINED),
         "The independent-roots handoff requires the exact standalone grant receipt")
    need(not independent_derived or not (args.independent_roots_retry or independent_handoff),
         "Derived independent roots may not repeat or continue a previous selection")
    need(bool(args.settings_retry) == bool(args.settings_prior_failure), "A retained retry requires the exact first failure receipt")
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
    if independent_cases:
        require_independent_manifests(proof.values())
    previous = {}
    prior_destinations = []
    prior_navigation_only = False
    completed = None
    publication_claim = None
    capacity_claim = None
    pinned_cold_baseline = None
    protected_death_backup = None
    refused_death_destinations = []
    refused_death_private = None
    retained_death = None
    retained_ancestry = None
    retained_pre_marker = None
    pre_marker_claim = None
    pre_marker_latest_claim = None
    independent_claim = None
    independent_prior = None
    independent_retained = None
    independent_protected = None
    prior_derived = None
    protect_interrupted = None
    death_authority = CAPACITY_PASSED if before_marker else args.backup_death_retry or SETTINGS_COLD_MANIFEST
    death_operation = str(uuid.uuid4()) if args.backup_delivery_death or before_marker else None
    need(args.backup_death_retry is None or (death_operation and args.backup_death_retry == BACKUP_DIRECT_CONTROL_PASS),
         "Retry requires the exact new no-write batch receipt")
    if args.previous:
        for package, file in zip(built, args.previous):
            path = Path(file).resolve()
            need(path.is_relative_to(ROOT / "validation/raw/storage") and path.name == f"{package}.apk",
                 "The predecessor must be the retained evidence file for this package")
            previous[package] = sha(path)
            exact_apk(path, previous[package], stage, package == TEST)
        if recovery_case:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1 and previous[APP] == hashes[APP] and args.settings_recover == SETTINGS_COMPLETED_MANIFEST,
                 "Require the independently pinned successful backup receipt and exact prior app")
            completed, publication_claim = settings_completed_destination(parents.pop(), args.settings_recover, args.guest, previous)
        if independent_cases:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1, "Independent roots require one exact predecessor receipt")
            root = parents.pop()
            recovery_pair = previous
            if args.independent_roots_retry or independent_handoff or independent_derived:
                if independent_derived:
                    latest = settings_run_anchors(root, INDEPENDENT_DERIVED_PASSED)
                    folder, contents, prior_pair = verify_derived_receipt(latest, args.guest, previous)
                    need(read_results(latest("independent-roots-derived-instrumentation.txt"), (INDEPENDENT_DERIVED_CASE,)) ==
                         ([INDEPENDENT_DERIVED_CASE], []), "The exact derived predecessor did not pass its instrumented case")
                    prior_derived = folder, contents
                    independent_claim = json.loads(latest("claim.json"))
                    cold = settings_run_anchors(raw.parent / "picker-adapter-library-tree-2cb71680ebd0", INDEPENDENT_COLD_PASSED)
                    granted = settings_run_anchors(raw.parent / "picker-adapter-library-tree-185be1bd3f81",
                                                  INDEPENDENT_GRANTS_RETAINED)
                    folder, contents, record, refused_pair = verify_independent_cold_handoff(granted, args.guest, prior_pair)
                    cold_artifact = json.loads(cold("artifacts.json"))
                    need(cold_artifact["guest"] == args.guest and cold_artifact["fixture"] == contents and
                         cold_artifact["independentRootsHandoff"] == INDEPENDENT_GRANTS_RETAINED and
                         json.loads(cold("outcomes.json")) == {"passed": [INDEPENDENT_COLD_CASE], "failed": [], "installedSha256": prior_pair} and
                         json.loads(cold("independent-roots-grants.json")) == {"fixture": folder, "state": "prior-inventory-restored"} and
                         read_results(cold("independent-roots-cold-instrumentation.txt"), (INDEPENDENT_COLD_CASE,)) ==
                         ([INDEPENDENT_COLD_CASE], []), "The exact predecessor did not prove cold read and grant restoration")
                    independent_protected = folder, contents, record
                    root = raw.parent / "picker-adapter-library-tree-ec1a12a0878f"
                elif independent_handoff:
                    need(previous == hashes, "Cold independent-root readback must not replace either APK")
                    granted = settings_run_anchors(root, INDEPENDENT_GRANTS_RETAINED)
                    folder, contents, record, refused_pair = verify_independent_cold_handoff(granted, args.guest, previous)
                    independent_retained = folder, contents, record
                    independent_claim = json.loads(granted("claim.json"))
                    root = raw.parent / "picker-adapter-library-tree-ec1a12a0878f"
                else:
                    need(previous[APP] == hashes[APP], "The chooser-only retry must not replace the app")
                    refused_pair = previous
                refusal = settings_run_anchors(root, INDEPENDENT_FIRST_PICKER_REFUSAL)
                prior_folder, prior_files, recovery_pair = verify_independent_picker_refusal(refusal, args.guest, refused_pair)
                independent_prior = prior_folder, prior_files
                if independent_claim is None:
                    independent_claim = json.loads(refusal("claim.json"))
                root = raw.parent / "picker-adapter-library-tree-c90ea22ee998"
            old = settings_run_anchors(root, PRE_MARKER_RECOVERY_PASSED)
            outcomes = json.loads(old("outcomes.json"))
            need(json.loads(old("artifacts.json"))["guest"] == args.guest and outcomes["installedSha256"] == recovery_pair and
                 outcomes["passed"] == [BACKUP_PRE_MARKER_RECOVERY_CASE] and not outcomes["failed"] and
                 read_results(old("backup-death-recovery-instrumentation.txt"), (BACKUP_PRE_MARKER_RECOVERY_CASE,)) ==
                 ([BACKUP_PRE_MARKER_RECOVERY_CASE], []), "The exact native recovery predecessor did not pass")
            if independent_claim is None:
                independent_claim = json.loads(old("claim.json"))
            completed, publication_claim, pinned_cold_baseline, protected_death_backup = protected_completed_backups(raw.parent, args.guest)
            protect_interrupted = interrupted_backup_preservation(raw.parent, old, args.guest, recovery_pair)
            capacity = settings_run_anchors(raw.parent / "picker-adapter-library-tree-417110df4323", CAPACITY_PASSED)
            refused_death_destinations.append(backup_death_control_destination(capacity, CAPACITY_CASE))
        if before_marker:
            parents = {Path(file).resolve().parent for file in args.previous}
            spend_path = raw.parent / f"settings-retry-spent-{death_authority}.json"
            need(len(parents) == 1, "Missing exact released pre-marker predecessor")
            predecessor = parents.pop()
            if pre_marker_handoff:
                need(re.fullmatch(r"[a-f0-9]{64}", pre_marker_handoff) and previous == hashes,
                     "The pre-marker handoff is read-only and cannot replace either APK")
                if pre_marker_handoff == PRE_MARKER_READ_TIMEOUT:
                    refusal = settings_run_anchors(predecessor, PRE_MARKER_READ_TIMEOUT)
                    writer_pin = json.loads(refusal("artifacts.json"))["backupPreMarkerHandoff"]
                    writer_path = raw.parent / "picker-adapter-library-tree-b0b27bd6debd"
                    handoff = settings_run_anchors(writer_path, writer_pin)
                    pre_marker_latest_claim = json.loads(refusal("claim.json"))
                else:
                    writer_pin = pre_marker_handoff
                    handoff = settings_run_anchors(predecessor, writer_pin)
                retained_pre_marker = verify_pre_marker_handoff(handoff, json.loads(spend_path.read_bytes()), args.guest, previous)
                pre_marker_claim = json.loads(handoff("claim.json"))
                death_operation = retained_pre_marker[2]
                old_path = raw.parent / "picker-adapter-library-tree-417110df4323"
                capacity_pair = json.loads(handoff("artifacts.json"))["previous"]
            else:
                need(not spend_path.exists(), "Pre-marker admission is already spent")
                old_path = predecessor
                capacity_pair = previous
            old = settings_run_anchors(old_path, CAPACITY_PASSED)
            need(json.loads(old("artifacts.json"))["guest"] == args.guest and
                 json.loads(old("outcomes.json")) == {"passed": [CAPACITY_CASE], "failed": [], "installedSha256": capacity_pair} and
                 read_results(old("provider-capacity-instrumentation.txt"), (CAPACITY_CASE,)) == ([CAPACITY_CASE], []),
                 "The exact native capacity predecessor did not pass")
            capacity_claim = json.loads(old("claim.json"))
            refused_death_destinations.append(backup_death_control_destination(old, CAPACITY_CASE))
            completed, publication_claim, pinned_cold_baseline, protected_death_backup = protected_completed_backups(raw.parent, args.guest)
            if pre_marker_latest_claim:
                verify_pre_marker_read_refusal(refusal, args.guest, previous, writer_pin,
                                               f"{protected_death_backup[0]}/objects/{synthetic_scan()[0]}")
        if capacity_case:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1, "Require the exact released pair from the cold ancestry run")
            old = settings_run_anchors(parents.pop(), ANCESTRY_COLD_PASSED)
            need(json.loads(old("artifacts.json"))["guest"] == args.guest and
                 json.loads(old("outcomes.json")) == {"passed": [ANCESTRY_COLD_CASE], "failed": [], "installedSha256": previous} and
                 read_results(old("tree-ancestry-cold-instrumentation.txt"), (ANCESTRY_COLD_CASE,)) == ([ANCESTRY_COLD_CASE], []),
                 "The capacity probe must use the exact released app guest and predecessor pair")
            capacity_claim = json.loads(old("claim.json"))
            refused_death_destinations.append(backup_death_control_destination(old, ANCESTRY_COLD_CASE))
        if ancestry_cases:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1 and previous[APP] == hashes[APP],
                 "Require the exact released app pair for the ancestry continuation")
            predecessor = parents.pop()
            if ancestry_resume:
                need(previous == hashes, "Cold ancestry handoff must not change either APK")
                handoff = settings_run_anchors(predecessor, ancestry_resume)
                retained_ancestry = verify_ancestry_cold_handoff(handoff, args.guest, previous)
                prior = raw.parent / "picker-adapter-library-tree-8e93d48f6993"
            else:
                need(previous[TEST] != hashes[TEST], "Require changed instrumentation for a new ancestry fixture")
                prior = predecessor
            old = settings_run_anchors(prior, ANCESTRY_EXISTING_GRANT_REFUSAL)
            if not ancestry_resume:
                verify_ancestry_grant_refusal(old, args.guest, previous, before_picker=True)
            refused_death_destinations.append(backup_death_control_destination(old, ANCESTRY_GRANT_CASE))
            finished = settings_run_anchors(raw.parent / "picker-adapter-library-tree-d41f13a3e0aa", ANCESTRY_FINISHED_GRANT_REFUSAL)
            refused_death_destinations.append(backup_death_control_destination(finished, ANCESTRY_CASE))
            first = settings_run_anchors(raw.parent / "picker-adapter-library-tree-62c66f5beb8e", ANCESTRY_GRANT_REFUSAL)
            refused_death_destinations.append(backup_death_control_destination(first, ANCESTRY_CASE))
        if (death_operation and not before_marker) or death_receipt:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1 and previous[APP] == hashes[APP], "Require the exact retained cold recovery pair")
            predecessor = parents.pop()
            cold_run = (raw.parent / "picker-adapter-library-tree-bb9159f6e027" if args.backup_death_retry or death_receipt else predecessor)
            cold_anchor = settings_run_anchors(cold_run, SETTINGS_COLD_MANIFEST)
            cold_outcome = json.loads(cold_anchor("outcomes.json"))
            need(cold_outcome == {"passed": [SETTINGS_RECOVERY_CASE], "failed": [],
                                  "installedSha256": cold_outcome["installedSha256"]} and
                 cold_outcome["installedSha256"][APP] == hashes[APP] and
                 (args.backup_death_retry or death_receipt or cold_outcome["installedSha256"] == previous),
                 "The original cold recovery evidence did not pass on the retained pair")
            pinned_cold_baseline = json.loads(cold_anchor("recovery-after.json"))
            need(json.loads(cold_anchor("recovery-before.json")) == pinned_cold_baseline,
                 "The pinned ordinary backup changed during cold recovery")
            need(death_receipt or not (raw.parent / f"settings-retry-spent-{death_authority}.json").exists(),
                 "This isolated backup death authorization is already spent")
            if death_receipt:
                need(hashes == previous, "Receipt-only recovery must not update either APK")
                old = settings_run_anchors(predecessor, death_receipt)
                artifacts = json.loads(old("artifacts.json"))
                attempt = json.loads(old("backup-death-attempt.json"))
                need(artifacts["guest"] == args.guest and artifacts["previous"] == previous and
                     {p: artifacts["proof"][p]["sha256"] for p in previous} == previous and
                     re.fullmatch(UUID_TEXT, attempt["operation"]) and attempt["operation"] == artifacts["backupDeliveryDeath"] and
                     artifacts["backupDeathRetry"] == BACKUP_DIRECT_CONTROL_PASS,
                     "The previous process death does not match the exact retained guest, pair and spend")
                destination, inventory = backup_death_control_destination(old, BACKUP_DEATH_CASE)
                spent = json.loads((raw.parent / f"settings-retry-spent-{BACKUP_DIRECT_CONTROL_PASS}.json").read_bytes())
                need(attempt["destination"] == destination.rsplit("/", 1)[1] and spent == {
                    "operation": attempt["operation"], "destination": destination, "kind": "single-delivery-death"},
                     "The previous process-death destination does not match the spent authorization")
                checkpoint = read_backup_delivery_death(old("backup-death-instrumentation.txt"), attempt["operation"])
                pid_results = [call for line in old("commands.jsonl").splitlines() if
                               (call := json.loads(line))["argv"][-4:] == ["shell", "pidof", APP, TEST]]
                need(len(pid_results) == 3 and all(call.get("exit") == 1 and not call["stdout"].strip() for call in pid_results[:2]) and
                     pid_results[-1].get("exit") == 0 and re.fullmatch(r"[1-9][0-9]{0,8}", pid_results[-1]["stdout"].strip()) and
                     checkpoint[0] != pid_results[-1]["stdout"].strip(),
                     "No verified post-checkpoint refusal with a different process")
                retained_death = destination, inventory, attempt["operation"], checkpoint
            if args.backup_death_retry:
                need(hashes == previous, "Retry requires the same app and test APKs as the direct no-write controls")
                control = settings_run_anchors(predecessor, BACKUP_DIRECT_CONTROL_PASS)
                verify_backup_death_control(control, args.guest, previous)
                refused_death_destinations.append(backup_death_control_destination(control, BACKUP_DEATH_PICKER_CASE))
                manual = settings_run_anchors(raw.parent / "picker-adapter-library-tree-8892e54640f8", BACKUP_MANUAL_CONTROL_FAILURE)
                refused_death_destinations.append(backup_death_control_destination(manual, BACKUP_DEATH_PICKER_CASE))
                prior_spent = json.loads((raw.parent / f"settings-retry-spent-{SETTINGS_COLD_MANIFEST}.json").read_bytes())
                failed_anchor = settings_run_anchors(raw.parent / "picker-adapter-library-tree-9649cfde8d90", BACKUP_DEATH_PICKER_FAILURE)
                refused, refused_death_private = backup_death_failed_destination(failed_anchor, prior_spent, args.guest, hashes[APP])
                refused_death_destinations.append(refused)
            original_run = raw.parent / "picker-adapter-library-tree-e3a9881e2bdd"
            pub_anchor = settings_run_anchors(original_run, SETTINGS_COMPLETED_MANIFEST)
            pub_pair = json.loads(pub_anchor("outcomes.json"))["installedSha256"]
            completed, publication_claim = settings_completed_destination(original_run, SETTINGS_COMPLETED_MANIFEST, args.guest, pub_pair)
        if death_picker_case:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1 and previous[APP] == hashes[APP], "Require the exact retained failed navigation-control pair")
            manual_anchor = settings_run_anchors(parents.pop(), BACKUP_MANUAL_CONTROL_FAILURE)
            manual = json.loads(manual_anchor("artifacts.json"))
            need(manual["guest"] == args.guest and json.loads(manual_anchor("outcomes.json")) ==
                 {"passed": [], "failed": [BACKUP_DEATH_PICKER_CASE], "installedSha256": previous},
                 "The retained manual navigation control is not the exact installed predecessor")
            refused_death_destinations.append(backup_death_control_destination(manual_anchor, BACKUP_DEATH_PICKER_CASE))
            failed_anchor = settings_run_anchors(raw.parent / "picker-adapter-library-tree-9649cfde8d90", BACKUP_DEATH_PICKER_FAILURE)
            failed = json.loads(failed_anchor("artifacts.json"))
            attempted = json.loads(failed_anchor("backup-death-attempt.json"))
            need(failed["guest"] == args.guest and
                 failed["proof"][APP]["sha256"] == hashes[APP] and
                 re.fullmatch(r"PristinePage-tree-[a-f0-9]{12}", attempted["destination"]) and
                 read_results(failed_anchor("backup-death-instrumentation.txt"), (BACKUP_DEATH_CASE,)) == ([], [BACKUP_DEATH_CASE]),
                 "The retained death attempt was not the exact failed chooser invocation")
            refused_death_destinations.append((f"/sdcard/Documents/{attempted['destination']}", failed["fixture"]))
        if args.settings_retry:
            parents = {Path(file).resolve().parent for file in args.previous}
            need(len(parents) == 1, "Settings cancel predecessor APKs must share the exact receipt")
            need(previous[APP] == hashes[APP], "Settings retry must use the byte-identical app from the cancel control")
            verify_settings_retry_evidence(parents.pop(), args.settings_retry, args.guest, previous)
            pair = args.settings_prior_failure
            seen = set()
            while pair is not None:
                old_run, old_sha = pair
                need(re.fullmatch(r"picker-adapter-library-tree-[a-f0-9]{12}", old_run) and
                     re.fullmatch(r"[a-f0-9]{64}", old_sha) and old_run not in seen and len(seen) < 4,
                     "Invalid retained Settings witness chain")
                seen.add(old_run)
                prior = (raw.parent / old_run).resolve()
                need(prior.parent == raw.parent.resolve() and prior.name == old_run, "Earlier witness is not a local retained receipt")
                destination, pair, navigation_only = settings_failed_destination(prior, old_sha, args.guest, hashes[APP])
                prior_destinations.append(destination)
                prior_navigation_only = prior_navigation_only or navigation_only
    retained = {}
    for package, path in built.items():
        saved = raw / f"{package}.apk"
        with path.open("rb") as incoming, saved.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        need(sha(saved) == hashes[package], "Retained candidate APK changed")
        retained[package] = saved
    fixture = raw / "synthetic-library"
    files = (independent_retained[1] if independent_retained else retained_pre_marker[1] if pre_marker_handoff else retained_ancestry[1] if ancestry_resume else {} if args.ocr_activation or recovery_case or death_receipt
             else make_fixture(fixture, with_scan=True))
    original = None if args.ocr_activation or recovery_case or death_receipt else synthetic_scan()[0]
    if original:
        need(files.get(f"objects/{original}") == original, "The populated Files fixture lacks its exact original")
    (raw / f"{prefix}-artifacts.json").write_text(
        json.dumps(dict(proof=proof, source=args.commit, fixture=files, previous=previous, guest=args.guest,
                        settingsCancel=args.settings_retry, settingsPriorFailure=args.settings_prior_failure,
                        settingsCompleted=args.settings_recover, backupDeliveryDeath=death_operation,
                        backupDeathRetry=args.backup_death_retry, backupDeathReceiptOnly=death_receipt,
                        treeAncestryHandoff=ancestry_resume, backupPreMarker=before_marker,
                        backupPreMarkerHandoff=pre_marker_handoff, independentRoots=bool(independent_cases),
                        independentRootsRetry=args.independent_roots_retry, independentRootsHandoff=independent_handoff,
                        independentRootsDerived=independent_derived)),
        encoding="utf-8",
    )
    ns = load_allocator(ROOT, args.guest, raw, "pristinepage-tree-" + os.environ["OPENCODE_SESSION_ID"])

    admit = None if independent_claim is None else lambda record: verify_released_independent_guest(independent_claim, record)

    class FolderRun(run_type(ns, purpose="pristinepage-library-app-tree", operation="PristinePage Files tree",
                             fresh_disk_floor_gib=16, add_statuses=(0, 1, 2, 3), admit_released=admit)):
        def process(self, pid):
            return inspect_prior_pid(super().process, pid)

    run = FolderRun(prefix, None, 0)
    pre_marker_outcome = None
    pre_marker_preservation = ()
    independent_outcome = None
    independent_preservation = ()
    print(f"Runtime evidence: {raw.relative_to(ROOT)}", flush=True)
    try:
        wait_for_allocator(run, lambda: run.prepare(args.fresh, None, args.fresh), before_claim=True)
        recheck_target(run, ns)
        if capacity_claim:
            verify_completed_guest(capacity_claim, run.owned())
        if independent_claim:
            current = run.owned()
            verify_completed_guest(independent_claim, current)
            need(current["ownership_history"][-1]["generation"] == independent_claim["generation"],
                 "The independent-roots predecessor is no longer the guest's immediately preceding claim")
        if pre_marker_claim:
            current = run.owned()
            verify_completed_guest(pre_marker_claim, current)
            if pre_marker_latest_claim:
                verify_completed_guest(pre_marker_latest_claim, current)
            latest = pre_marker_latest_claim or pre_marker_claim
            need(current["ownership_history"][-1]["generation"] == latest["generation"],
                 "Read-only pre-marker handoff is no longer the immediately preceding run; refusing another cold launch")
        target = [ns["ADB"], "-s", run.serial]

        def adb_once(*argv, **options):
            return run.command([*target, *argv], **options)

        def adb(*argv, **options):
            return retry_pre_marker_checksum(adb_once, *argv, **options) if pre_marker_handoff else adb_once(*argv, **options)

        def check_protected_death_backup():
            prior_target, prior_fixture, prior_generation, prior_op, prior_baseline = protected_death_backup
            name = f"PristinePage-backup-{prior_generation}"
            path = f"{prior_target}/{name}"
            marker = adb("shell", "cat", f"{path}/generations/{prior_generation}.complete")
            isolated = f"files/library-export-source-{prior_op}"
            current = settings_private_baseline(adb, f"{isolated}/files")
            need(current == prior_baseline, "The earlier interrupted-delivery private backup changed")
            verify_backup_death_intent(adb, isolated, prior_op, prior_target.rsplit("/", 1)[1], marker, current)
            verify_unchanged_settings_destination(adb, prior_target, prior_fixture, backup_name=name)
            verify_closed_backup(adb, path, prior_generation, marker)

        present = set(adb("shell", "pm", "list", "packages", APP).splitlines())
        expected = set() if args.fresh else {f"package:{p}" for p in built}
        need(present == expected, "The exact expected package set did not match; nothing was replaced")
        installed = {p: run.installed_hash(p) if f"package:{p}" in present else None for p in built}
        if independent_derived:
            verify_independent_derived_installed(installed, previous)
        elif not args.fresh:
            check_installed_pair(installed, previous, hashes)
        run.save(run.path("installed-before.json"), installed, durable=True)
        if independent_cases:
            require_cold_backup_app(adb)
        if completed:
            verify_completed_guest(publication_claim, run.owned())
            require_cold_backup_app(adb)
            verify_completed_destination(adb, *completed)
            baseline = settings_private_baseline(adb)
            need(not (death_operation or death_receipt or independent_cases) or baseline == pinned_cold_baseline,
                 "The retained ordinary private backup no longer matches its pinned cold baseline")
            run.save(run.path("recovery-before.json"), baseline, durable=True)
        if protected_death_backup:
            check_protected_death_backup()
        for refused in refused_death_destinations:
            verify_unchanged_settings_destination(adb, *refused)
        if refused_death_private:
            need(refused_death_private not in adb("shell", "run-as", APP, "ls", "-A", "-1", "files").splitlines(),
                 "A prior death attempt has private data; no new publication")
        if before_marker or independent_cases:
            def check_ordinary():
                recheck_target(run, ns)
                verify_completed_destination(adb, *completed)
                need(settings_private_baseline(adb) == baseline, "The retained ordinary backup attempt changed")

            def check_prior_death():
                recheck_target(run, ns)
                check_protected_death_backup()

            def check_capacity_fixture():
                recheck_target(run, ns)
                for refused in refused_death_destinations:
                    verify_unchanged_settings_destination(adb, *refused)

            # Cover APK replacement and fixture staging as well as the writer and the cold reader.
            checks = (check_ordinary, check_prior_death, check_capacity_fixture)
            if independent_cases:
                def check_interrupted():
                    recheck_target(run, ns)
                    protect_interrupted(adb)
                check_interrupted()
                independent_preservation = (*checks, check_interrupted)
                if independent_prior:
                    def check_prior_picker():
                        recheck_target(run, ns)
                        prior_folder, inventory = independent_prior
                        root = f"/sdcard/Documents/{prior_folder}"
                        need(adb("shell", "ls", "-A", "-1", root).splitlines() == sorted(INDEPENDENT_CHILDREN),
                             "The refused earlier independent fixture changed")
                        for name in INDEPENDENT_CHILDREN:
                            verify_unchanged_settings_destination(adb, f"{root}/{name}", inventory)
                        need(f"independent-ancestry-grants-{prior_folder}" not in
                             adb("shell", "run-as", APP, "ls", "-A", "-1", "files").splitlines(),
                             "The refused earlier chooser unexpectedly created a grant handoff")
                    check_prior_picker()
                    independent_preservation = (*independent_preservation, check_prior_picker)
                if independent_protected:
                    def check_prior_cold():
                        recheck_target(run, ns)
                        prior_folder, inventory, record = independent_protected
                        root = f"/sdcard/Documents/{prior_folder}"
                        children = independent_fixture_children(prior_folder)
                        need(adb("shell", "ls", "-A", "-1", root).splitlines() == sorted(children),
                             "The verified earlier independent fixture changed")
                        for name in children:
                            verify_unchanged_settings_destination(adb, f"{root}/{name}", inventory)
                        need(adb("shell", "run-as", APP, "cat", f"files/independent-ancestry-grants-{prior_folder}") == record,
                             "The earlier independent fixture record changed")
                    check_prior_cold()
                    independent_preservation = (*independent_preservation, check_prior_cold)
                if prior_derived:
                    def check_prior_derived():
                        recheck_target(run, ns)
                        prior_folder, inventory = prior_derived
                        root = f"/sdcard/Documents/{prior_folder}"
                        children = independent_fixture_children(prior_folder)
                        need(adb("shell", "ls", "-A", "-1", root).splitlines() == sorted(children),
                             "The previous derived roots fixture changed")
                        for name in children:
                            verify_unchanged_settings_destination(adb, f"{root}/{name}", inventory)
                        need(f"independent-ancestry-grants-{prior_folder}" not in
                             adb("shell", "run-as", APP, "ls", "-A", "-1", "files").splitlines(),
                             "The previous derived roots unexpectedly retained a grant handoff")
                    check_prior_derived()
                    independent_preservation = (*independent_preservation, check_prior_derived)
            else:
                pre_marker_preservation = checks
        for p, path in retained.items():
            flags = install_flags(p, installed[p], hashes[p], previous.get(TEST), previous.get(APP))
            if flags is not None:
                need(sha(path) == hashes[p], "Candidate changed before installation")
                result = adb("install", *flags, "--no-streaming", path, timeout=130)
                need(result.strip().splitlines()[-1:] == ["Success"], "APK installation did not acknowledge success")
            need(run.installed_hash(p) == hashes[p], "Installed APK differs from the admitted candidate")
        need(adb("shell", "pm", "list", "instrumentation", APP).splitlines() ==
             [f"instrumentation:{RUNNER} (target={APP})"], "The instrumentation registry differs")
        if death_receipt:
            recheck_target(run, ns)
            try:
                destination, inventory, operation, checkpoint = retained_death
                return complete_backup_delivery_death(run, adb, ns, raw, destination, inventory, operation, hashes, checkpoint)
            finally:
                recheck_target(run, ns)
                verify_completed_destination(adb, *completed)
                need(settings_private_baseline(adb) == baseline, "The retained ordinary backup attempt changed")
        if recovery_case:
            recheck_target(run, ns)
            need(settings_private_baseline(adb) == baseline and not adb("shell", "pidof", APP, TEST, allowed=(0, 1)).strip(),
                 "The prelaunch backup journal or cold process state changed")
            parent, folder = completed[0].rsplit("/", 2)[1:]
            try:
                output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", recovery_case,
                             "-e", "settingsCompletedFolder", folder, "-e", "settingsCompletedParent", parent,
                             "-e", "settingsCompletedOperation", baseline["operation"], RUNNER, timeout=100)
                run.path("instrumentation.txt").write_text(output, encoding="utf-8")
            finally:
                # Read back even after a malformed runner response or timeout, but only on the verified target.
                recheck_target(run, ns)
                verify_completed_destination(adb, *completed)
                after = settings_private_baseline(adb)
                run.save(run.path("recovery-after.json"), after, durable=True)
                need(after == baseline, "Cold recovery changed the private backup journal or active pointer")
            passed, failed = read_results(output, (recovery_case,))
            run.save(run.path("outcomes.json"), {"passed": passed, "failed": failed, "installedSha256": hashes}, durable=True)
            print(f"Cold Settings receipt recovery: {len(passed)} passed, {len(failed)} failed.", flush=True)
            return bool(failed)
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

        if death_operation and not pre_marker_handoff:
            need(not (raw.parent / f"settings-retry-spent-{death_authority}.json").exists(),
                 "This isolated backup death authorization became spent before fixture staging")
        if independent_cases:
            folder = independent_retained[0] if independent_retained else "PristinePage-tree-" + uuid.uuid4().hex[:12]
            guest_root = f"/sdcard/Documents/{folder}"
            if not independent_retained:
                adb("shell", "mkdir", guest_root)
                for name in independent_fixture_children(folder):
                    stage_synthetic_files(adb, fixture, f"{guest_root}/{name}", files)
            independent_outcome = exercise_independent_roots(run, adb, ns, guest_root, folder, files, hashes,
                                                            independent_prior[0] if independent_prior else None,
                                                            independent_retained[2] if independent_retained else None,
                                                            derived=independent_derived)
            return bool(independent_outcome["failed"])
        if ancestry_resume or pre_marker_handoff:
            guest_root = retained_pre_marker[0] if pre_marker_handoff else retained_ancestry[0]
            folder = guest_root.rsplit("/", 1)[1]
            if not pre_marker_handoff:
                verify_unchanged_settings_destination(adb, guest_root, files)
        else:
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
        if death_operation:
            if before_marker:
                if pre_marker_handoff:
                    pre_marker_outcome = complete_pre_marker_death(run, adb, ns, raw, guest_root, files, death_operation,
                                                                   hashes, retained_pre_marker[3])
                else:
                    pre_marker_outcome = exercise_backup_delivery_death(run, adb, ns, raw, guest_root, files, original,
                                                                        death_operation, hashes, death_authority, before_marker=True)
                return bool(pre_marker_outcome["failed"])
            try:
                return exercise_backup_delivery_death(run, adb, ns, raw, guest_root, files, original, death_operation, hashes, death_authority)
            finally:
                recheck_target(run, ns)
                verify_completed_destination(adb, *completed)
                need(settings_private_baseline(adb) == baseline, "The retained ordinary backup attempt changed")
                for refused in refused_death_destinations:
                    verify_unchanged_settings_destination(adb, *refused)
                if refused_death_private:
                    need(refused_death_private not in adb("shell", "run-as", APP, "ls", "-A", "-1", "files").splitlines(),
                         "The failed death attempt gained unexpected private data")
        if death_picker_case or ancestry_cases or capacity_case:
            # Both ancestry probes use one exact Files read grant in the fresh process. Do not reboot the VM between stages.
            all_passed, all_failed = [], []
            try:
                if capacity_case:
                    verify_unchanged_settings_destination(adb, guest_root, files)
                schedule = tuple(zip(ancestry_cases, ("tree-ancestry-grant", "tree-ancestry-cold"))) if ancestry_cases else (
                    (death_picker_case, "death-picker"), (DESCRIPTOR_CASE, "directory-probe"))
                if capacity_case:
                    schedule = ((capacity_case, "provider-capacity"),)
                if ancestry_resume:
                    schedule = schedule[1:]
                for case, name in schedule:
                    if case == ANCESTRY_COLD_CASE:
                        recheck_target(run, ns)
                        if not ancestry_resume:
                            adb("shell", "am", "force-stop", APP)
                        require_cold_backup_app(adb)
                    output = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", case,
                                 "-e", "libraryFixtureFolder", folder, "-e", "libraryFixtureOriginal", original, RUNNER, timeout=155)
                    run.path(f"{name}-instrumentation.txt").write_text(output, encoding="utf-8")
                    passed, failed = read_results(output, (case,))
                    all_passed.extend(passed)
                    all_failed.extend(failed)
                    if failed:
                        break
            finally:
                recheck_target(run, ns)
                verify_unchanged_settings_destination(adb, guest_root, files)
                for refused in refused_death_destinations:
                    verify_unchanged_settings_destination(adb, *refused)
            run.save(run.path("outcomes.json"), {"passed": all_passed, "failed": all_failed, "installedSha256": hashes}, durable=True)
            label = "provider capacity" if capacity_case else "descriptor/ancestry" if ancestry_cases else "picker/descriptor"
            print(f"LDPlayer no-write {label} batch: {len(all_passed)} passed, {len(all_failed)} failed.", flush=True)
            return bool(all_failed)
        if prior_destinations:
            for previous_destination in prior_destinations:
                verify_unchanged_settings_destination(adb, *previous_destination)
            spend_settings_retry(raw.parent, args.settings_retry, dict(guest=args.guest, candidate=prefix,
                abandonedDestinations=[item[0] for item in prior_destinations], authorizedDestination=guest_root))
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
                     *(("-e", "librarySettingsFixture", "retained" if args.settings_retry else "fresh")
                       if args.settings_backup else ()),
                     *(("-e", "librarySettingsPriorNavigationOnly", "1") if prior_navigation_only else ()),
                     RUNNER, timeout=210)
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
                need(len(witness) == 15 and all(re.fullmatch(uuid_text, x) for x in witness[:3] + witness[6:8] + witness[12:13])
                     and all(re.fullmatch(r"[0-9a-f]{64}", x) for x in witness[3:6] + witness[13:15])
                     and re.fullmatch(r"[a-zA-Z0-9_-]{1,100}", witness[9]) and re.fullmatch(r"[1-9][0-9]{0,8}", witness[10])
                     and re.fullmatch(r"[A-Z_]{1,32}", witness[11])
                     and witness[6:9] == [marker[1], marker[2], backup_name], "Settings receipt did not match the closed folder")
                need(adb("shell", "stat", "-c", "%s", f"{backup}/objects/{BACKUP_ORIGINAL}").strip() == "81",
                     "Settings fixture original extent differs")
                catalog = raw / "settings-closed-catalog.sqlite"
                need(marker[5].isdigit() and 0 < int(marker[5]) <= 2 * 1024 * 1024, "Settings fixture catalog extent is unexpected")
                need(adb("shell", "stat", "-c", "%s", f"{backup}/catalogs/{generation}.sqlite").strip() == marker[5],
                     "Settings fixture catalog extent differs from its marker")
                adb("pull", f"{backup}/catalogs/{generation}.sqlite", catalog, timeout=20)
                need(catalog.stat().st_size == int(marker[5]) and sha(catalog) == marker[6], "Pulled Settings catalog changed")
                engine, version, layout, producer, intent, text = witness[9:]
                verify_settings_catalog(catalog, BACKUP_ORIGINAL, 81, witness[:6],
                                        (witness[1], 2, witness[3], engine, int(version), layout, producer, intent, 1, text))
        for namespace in namespaces:
            expected_leaves = {relative.split("/", 1)[1] for relative in files if relative.startswith(f"{namespace}/")}
            need(set(adb("shell", "ls", "-A", "-1", f"{guest_root}/{namespace}").splitlines()) == expected_leaves,
                 "Instrumentation changed the namespace of the existing fixture")
        for previous_destination in prior_destinations:
            verify_unchanged_settings_destination(adb, *previous_destination)
        run.save(run.path("outcomes.json"), {"passed": passed, "failed": failed, "installedSha256": hashes}, durable=True)
        print(f"LDPlayer Files grant: {len(passed)} passed, {len(failed)} failed. " + ", ".join(failed), flush=True)
        return bool(failed)
    finally:
        try:
            if independent_preservation:
                finish_pre_marker_outcome(independent_outcome, independent_preservation,
                                          lambda value: run.save(run.path("outcomes.json"), value, durable=True),
                                          label="Independent roots")
                if independent_outcome is not None:
                    print(f"Independent Files roots: {len(independent_outcome['passed'])} passed, "
                          f"{len(independent_outcome['failed'])} failed; predecessor preservation verified.", flush=True)
            if pre_marker_preservation:
                finish_pre_marker_outcome(pre_marker_outcome, pre_marker_preservation,
                                          lambda value: run.save(run.path("outcomes.json"), value, durable=True))
                if pre_marker_outcome:
                    print(f"Cold recovery of interrupted publication: {len(pre_marker_outcome['passed'])} passed, "
                          f"{len(pre_marker_outcome['failed'])} failed; predecessor preservation verified.", flush=True)
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
    parser.add_argument("--settings-backup", action="store_true", help="Exercise real Settings publication on an admitted synthetic guest")
    parser.add_argument("--settings-picker-only", action="store_true", help="Open and cancel Files from Settings on the retained synthetic guest; never select a destination")
    parser.add_argument("--settings-recover", metavar="COMPLETED_MANIFEST_SHA", help="Read the existing Settings receipt after a cold launch; never open a picker")
    parser.add_argument("--backup-delivery-death", action="store_true", help="One isolated native process death after commit but before delivery; never replay")
    parser.add_argument("--backup-pre-marker", action="store_true",
                        help="One isolated publication interrupted after payload verification and before its marker; never replay")
    parser.add_argument("--backup-pre-marker-handoff", metavar="INTERRUPTED_MANIFEST_SHA",
                        help="Read-only completion of an exact spent pre-marker witness whose cold test never started")
    parser.add_argument("--backup-death-retry", metavar="NO_WRITE_MANIFEST_SHA",
                        help="Spend the new exact direct no-write controls after verifying the safe previous refusal")
    parser.add_argument("--backup-death-receipt-only", metavar="DEATH_MANIFEST_SHA",
                        help="Verify the exact retained process-death result without launching Files or the writer")
    parser.add_argument("--backup-death-picker-only", action="store_true",
                        help="Reach the exact writable chooser folder, cancel without a grant, then separately verify read-only local descriptors")
    parser.add_argument("--tree-ancestry", action="store_true",
                        help="Observe read-only physical ancestry for two children of one verified Files tree")
    parser.add_argument("--tree-ancestry-handoff", metavar="GRANT_MANIFEST_SHA",
                        help="Use only the exact retained Files read grant; skip fixture staging and the chooser")
    parser.add_argument("--independent-roots", action="store_true",
                        help="Prove read-only descriptor separation for two exact Files-selected synthetic roots")
    parser.add_argument("--independent-roots-retry", metavar="REFUSED_MANIFEST_SHA",
                        help="Use the exact first pre-selection refusal as predecessor, preserving its fixture")
    parser.add_argument("--independent-roots-handoff", metavar="GRANTED_MANIFEST_SHA",
                        help="Continue only the first cold read of the exact retained Files selections")
    parser.add_argument("--independent-roots-derived", action="store_true",
                        help="With --independent-roots, select only two roots and verify ancestry without a parent grant")
    parser.add_argument("--provider-capacity", action="store_true",
                        help="Verify the real Files capacity gate with an unattainable synthetic requirement and no publication")
    parser.add_argument("--settings-retry", metavar="CANCEL_MANIFEST_SHA", help="Permit one new confirmation on the exact retained, independently canceled fixture")
    parser.add_argument("--settings-prior-failure", nargs=2, metavar=("RUN_ID", "MANIFEST_SHA"), help="Verify the exact first pre-selection failure and its unchanged destination")
    parser.add_argument("--ocr-activation", action="store_true", help="Test scoped WorkManager wakes and ordinary synthetic save/edit; no Files fixture")
    parser.add_argument("--previous", nargs=2, metavar=("APP_APK", "TEST_APK"))
    raise SystemExit(run(parser.parse_args()))
