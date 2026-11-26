from argparse import Namespace
from contextlib import closing
from hashlib import sha256
from pathlib import Path
import json
import sqlite3
import shutil
from subprocess import TimeoutExpired, run
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from run_library_folder_picker_ldplayer import (
    check_fixture_root, read_backup_delivery_death, recheck_target, require_independent_manifests, select_backup_case, select_backup_death_picker_case, select_independent_roots, select_provider_capacity, select_settings_picker_case, select_settings_recovery_case, select_tree_ancestry, settings_completed_destination, spend_settings_retry, verify_released_independent_guest,
    finish_pre_marker_outcome, independent_fixture_children, read_independent_grant_record, retry_pre_marker_checksum, verify_ancestry_cold_handoff, verify_ancestry_grant_refusal, verify_backup_death_control, verify_backup_death_intent, verify_backup_receipt_only, verify_pre_marker_handoff, verify_pre_marker_read_refusal, verify_settings_catalog, verify_settings_ocr, verify_settings_retry_evidence,
    verify_independent_derived_installed, verify_derived_receipt, verify_selected_append_catalog,
)
from run_local_ocr_guest import read_boot_serial


class LibraryFolderPickerRunnerTest(unittest.TestCase):
    def test_derived_roots_admits_only_the_two_selection_witness(self):
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#separatesIndependentlySelectedRootsWithoutAParentGrant"
        self.assertEqual((case,), select_independent_roots(True, False, derived=True))
        for enabled, mixed in ((False, False), (True, True)):
            with self.assertRaisesRegex(RuntimeError, "standalone"):
                select_independent_roots(enabled, mixed, derived=True)

    def test_selected_append_needs_the_standalone_derived_lane(self):
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#selectedFilesMergeAppendsOnlyTheMissingOriginalAndKeepsItsPredecessor"
        self.assertEqual((case,), select_independent_roots(True, False, derived=True, append=True))
        for enabled, mixed, derived in ((False, False, True), (True, True, True), (True, False, False)):
            with self.assertRaisesRegex(RuntimeError, "standalone"):
                select_independent_roots(enabled, mixed, derived=derived, append=True)

    def test_selected_append_host_oracle_rejects_missing_recipe_history(self):
        with TemporaryDirectory(prefix="pristinepage-append-catalog-test-") as temporary:
            path = Path(temporary) / "catalog.sqlite"
            with closing(sqlite3.connect(path)) as db:
                db.executescript("""
                    CREATE TABLE Document(documentId, title);
                    CREATE TABLE Page(pageId, documentId, sourceId, ordinal, currentRecipeRevision);
                    CREATE TABLE LibraryScanName(pageId, filename);
                    CREATE TABLE Source(sourceId, sourceBlobId);
                    CREATE TABLE SourceBlob(sourceBlobId, byteSha256);
                    CREATE TABLE RecipeRevision(pageId, recipeRevision, parentRevision);
                    INSERT INTO Document VALUES(1,'Synthetic folder test');
                    INSERT INTO Page VALUES(9,1,7,0,2),(2,1,8,1,2);
                    INSERT INTO LibraryScanName VALUES(9,'Synthetic.png'),(2,'Additional synthetic.png');
                    INSERT INTO Source VALUES(7,17),(8,18);
                    INSERT INTO SourceBlob VALUES(17,'old'),(18,'incoming');
                    INSERT INTO RecipeRevision VALUES(9,1,NULL),(9,2,1),(2,1,NULL),(2,2,1);
                """)
                verify_selected_append_catalog(path, "old", "incoming")
                db.execute("DELETE FROM RecipeRevision WHERE pageId=2 AND recipeRevision=1")
                db.commit()
                with self.assertRaisesRegex(RuntimeError, "recipe history"):
                    verify_selected_append_catalog(path, "old", "incoming")

    def test_derived_roots_refuses_even_the_candidate_as_preinstalled_baseline(self):
        predecessor = {"app": "a", "test": "b"}
        verify_independent_derived_installed(predecessor.copy(), predecessor)
        for installed in ({"app": "c", "test": "d"}, {"app": "a", "test": "d"}, {}):
            with self.assertRaisesRegex(RuntimeError, "installed predecessor"):
                verify_independent_derived_installed(installed, predecessor)

    def test_successor_requires_a_passed_derived_case_with_exact_grant_restoration(self):
        pair = {"app": "a", "test": "b"}
        old_pair = {"app": "c", "test": "d"}
        folder = "PristinePage-tree-0123456789ab"
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#separatesIndependentlySelectedRootsWithoutAParentGrant"
        anchors = {
            "artifacts.json": dict(guest="guest", independentRoots=True, independentRootsDerived=True,
                                   independentRootsHandoff=None, independentRootsRetry=None, previous=old_pair, fixture={"file": "digest"}),
            "installed-before.json": old_pair,
            "outcomes.json": dict(passed=[case], failed=[], installedSha256=pair),
            "independent-roots-grants.json": dict(fixture=folder, state="prior-inventory-restored"),
        }
        def read(key):
            return json.dumps(anchors[key])
        self.assertEqual((folder, {"file": "digest"}, old_pair), verify_derived_receipt(read, "guest", pair))
        anchors["independent-roots-grants.json"]["state"] = "cleanup-unconfirmed"
        with self.assertRaisesRegex(RuntimeError, "derived.*restore"):
            verify_derived_receipt(read, "guest", pair)

    def test_independent_cold_handoff_binds_exact_order_and_both_nested_paths(self):
        folder = "PristinePage-tree-0123456789ab"
        prefix = "content://com.android.externalstorage.documents/tree/"
        rows = ["3948", prefix + f"home%3A{folder}%2FLibrary-Left-0123456789ab",
                prefix + f"primary%3ADocuments%2F{folder}%2FLibrary-Right-0123456789ab",
                prefix + f"home%3A{folder}", "d"*64]
        self.assertEqual("3948", read_independent_grant_record("\n".join(rows) + "\n", folder))
        for wrong in ([rows[0], rows[2], rows[1], *rows[3:]], [*rows[:2], rows[1], *rows[3:]],
                      [*rows[:3], prefix + "home%3A", rows[4]], [*rows[:-1], "a"*65]):
            with self.assertRaisesRegex(RuntimeError, "independent.*grant"):
                read_independent_grant_record("\n".join(wrong) + "\n", folder)

    def test_independent_fixture_children_use_the_exact_attempt_nonce(self):
        self.assertEqual(("Library-Left-0123456789ab", "Library-Right-0123456789ab"),
                         independent_fixture_children("PristinePage-tree-0123456789ab"))
        with self.assertRaisesRegex(RuntimeError, "synthetic parent"):
            independent_fixture_children("PristinePage-tree-../other")

    def test_independent_probe_rejects_broad_permissions_from_either_apk(self):
        require_independent_manifests(({"manifest": "E: manifest"}, {"manifest": "E: instrumentation"}))
        for name in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES"):
            with self.assertRaisesRegex(RuntimeError, "broad storage"):
                require_independent_manifests(({"manifest": "E: manifest"}, {"manifest": f'android.permission.{name}'}))

    def test_independent_probe_requires_exact_released_generation_and_processes(self):
        original = dict(name="synthetic", installation="installed", index=3, created="creation", worktree="owned", purpose="library",
                        api=28, created_by_agent=True, generation="exact", runtime_processes=[[15, 42], [17, 47]])
        stopped = original | dict(state="stopped-released", shutdown_verified=True, owner=None, shutdown_processes=original["runtime_processes"])
        verify_released_independent_guest(original, stopped)
        for mismatch in (dict(generation="later"), dict(shutdown_processes=[]), dict(owner="other"), dict(index=2)):
            with self.assertRaisesRegex(RuntimeError, "released.*predecessor"):
                verify_released_independent_guest(original, stopped | mismatch)

    def test_interruption_checksum_retries_only_timeout_on_the_same_read(self):
        calls = []
        def read(*args, **kwargs):
            calls.append((args, kwargs))
            if len(calls) == 1:
                raise TimeoutExpired("sha256sum", 15)
            return "same path"
        args = ("shell", "sha256sum", "/sdcard/Documents/synthetic")
        self.assertEqual("same path", retry_pre_marker_checksum(read, *args))
        self.assertEqual([(args, {}), (args, {"timeout": 30})], calls)
        calls.clear()
        with self.assertRaises(TimeoutExpired):
            retry_pre_marker_checksum(read, "shell", "am", "instrument")
        self.assertEqual(1, len(calls))

    def test_interruption_continuation_refusal_must_not_have_launched_a_test_or_changed_a_fixture(self):
        proof = {"app.pristinepage.debug": "a"*64, "app.pristinepage.debug.test": "b"*64}
        artifact = {"previous": proof, "guest": "assigned", "backupPreMarker": True, "backupPreMarkerHandoff": "c"*64}
        before = {"artifacts.json": json.dumps(artifact), "installed-before.json": json.dumps(proof)}
        path = "/sdcard/Documents/synthetic/objects/payload"
        observed = [dict(argv=["shell", "sha256sum", path], timeout=15)]
        def anchor(calls):
            return (before | {"commands.jsonl": "\n".join(map(json.dumps, calls))}).__getitem__
        verify_pre_marker_read_refusal(anchor(observed), "assigned", proof, "c"*64, path)
        for command in (("shell", "am", "instrument"), ("install", "apk"), ("push", "a", "b"), ("shell", "mkdir", "a")):
            with self.assertRaisesRegex(RuntimeError, "read-only.*timeout"):
                verify_pre_marker_read_refusal(anchor(observed + [dict(argv=list(command))]), "assigned", proof, "c"*64, path)
        with self.assertRaisesRegex(RuntimeError, "read-only.*timeout"):
            verify_pre_marker_read_refusal(anchor([dict(argv=["shell", "sha256sum", path], exit=1)]), "assigned", proof, "c"*64, path)

    def test_interruption_outcome_requires_both_independent_preservation_checks(self):
        calls = []
        def fail():
            calls.append("ordinary")
            raise RuntimeError("ordinary backup changed")
        with self.assertRaisesRegex(ExceptionGroup, "preservation"):
            finish_pre_marker_outcome({"passed": ["cold"]}, (fail, lambda: calls.append("earlier death")), calls.append)
        self.assertEqual(["ordinary", "earlier death"], calls)
        finish_pre_marker_outcome({"passed": ["cold"]}, (lambda: calls.append("first"), lambda: calls.append("second")), calls.append)
        self.assertEqual(["first", "second", {"passed": ["cold"]}], calls[-3:])

    @patch("run_library_folder_picker_ldplayer.verify_target")
    def test_once_only_read_retry_for_a_timeout(self, verify):
        verify.side_effect = [TimeoutExpired("get-serialno", 12), None]
        recheck_target("claim", "coordinator")
        self.assertEqual(2, verify.call_count)

    @patch("run_library_folder_picker_ldplayer.verify_target")
    def test_positive_mismatch_never_retried(self, verify):
        verify.side_effect = RuntimeError("different guest")
        with self.assertRaisesRegex(RuntimeError, "different guest"):
            recheck_target("claim", "coordinator")
        verify.assert_called_once_with("claim", "coordinator")

    def test_boot_serial_timeout_remains_inside_the_existing_readiness_budget(self):
        def deadline():
            raise TimeoutExpired("get-serialno", 12)

        self.assertIsNone(read_boot_serial(deadline))
        self.assertEqual("emulator-5558", read_boot_serial(lambda: " emulator-5558\n"))
        self.assertIsNone(read_boot_serial(lambda: "error: device not found"))

    def test_complete_backup_allows_only_one_new_canonical_child(self):
        original = {"catalogs", "generations", "objects"}
        backup = "PristinePage-backup-10000000-aaaa-4aaa-8aaa-000000000001"
        result = original | {backup}
        self.assertEqual(backup, check_fixture_root(original, result, False, False, True, True))
        for wrong in (original, result | {"unrelated"}, result | {backup[:-1] + "2"}, result - {"objects"}):
            with self.assertRaises(RuntimeError):
                check_fixture_root(original, wrong, False, False, True, True)
        with self.assertRaises(RuntimeError):
            check_fixture_root(original, result, False, False, False, False)
        self.assertEqual(backup, check_fixture_root(original, result, False, False, True, False))

    def test_settings_backup_does_not_run_on_a_retained_or_mixed_guest_batch(self):
        flags = dict(settings_backup=True, fresh=True, backup_export=False, probe_write=False, ocr_activation=False,
                     working_catalog=False, probe_directory=False, activation=False, selected_catalog=False,
                     activation_compatibility=False, expect_home=False, settings_picker_only=False, settings_retry=None)
        self.assertEqual("app.pristinepage.LibraryFolderFilesJourneyTest#settingsPublishesOneBackupOfTheSyntheticDefaultLibrary",
                         select_backup_case(Namespace(**flags)))
        for key in flags.keys() - {"settings_backup"}:
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "fresh.*standalone"):
                select_backup_case(Namespace(**(flags | {key: not flags[key]})))
        self.assertIsNone(select_backup_case(Namespace(**(flags | {"settings_backup": False}))))
        self.assertEqual("app.pristinepage.LibraryFolderFilesJourneyTest#settingsPublishesOneBackupOfTheSyntheticDefaultLibrary",
                         select_backup_case(Namespace(**(flags | {"fresh": False, "settings_retry": "a" * 64}))))

    def test_picker_cancellation_is_standalone_on_the_retained_synthetic_guest(self):
        flags = dict(settings_picker_only=True, settings_backup=False, fresh=False, backup_export=False,
                     probe_write=False, ocr_activation=False, working_catalog=False, probe_directory=False,
                     activation=False, selected_catalog=False, activation_compatibility=False, expect_home=False,
                     settings_retry=None)
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#settingsOpensAndCancelsFilesWithoutStartingABackup"
        self.assertEqual(case, select_settings_picker_case(Namespace(**flags)))
        for key in flags.keys() - {"settings_picker_only"}:
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "retained.*standalone"):
                select_settings_picker_case(Namespace(**(flags | {key: "a" * 64 if key == "settings_retry" else True})))
        self.assertIsNone(select_settings_picker_case(Namespace(**(flags | {"settings_picker_only": False}))))

    def test_retained_settings_run_requires_the_same_verified_guest_and_completed_cancel_control(self):
        import json

        case = "app.pristinepage.LibraryFolderFilesJourneyTest#settingsOpensAndCancelsFilesWithoutStartingABackup"
        before = {"app.pristinepage.debug": "a" * 64, "app.pristinepage.debug.test": "b" * 64}
        with TemporaryDirectory() as scratch:
            raw = Path(scratch)
            artifacts = raw / f"{raw.name}-artifacts.json"
            outcomes = raw / f"{raw.name}-outcomes.json"
            manifest = raw / f"{raw.name}-final-manifest.json"
            artifacts.write_text(json.dumps({"guest": "exact-test-guest"}), encoding="utf-8")
            outcomes.write_text(json.dumps({"passed": [case], "failed": [], "installedSha256": before}), encoding="utf-8")
            manifest.write_text(json.dumps({"stage": "verified-before-release", "evidence": [],
                                            "ancillaryEvidence": [{"path": str(p), "bytes": p.stat().st_size,
                                                                   "sha256": sha256(p.read_bytes()).hexdigest()}
                                                                  for p in (artifacts, outcomes)]}), encoding="utf-8")
            digest = sha256(manifest.read_bytes()).hexdigest()
            verify_settings_retry_evidence(raw, digest, "exact-test-guest", before)
            with self.assertRaisesRegex(RuntimeError, "another guest"):
                verify_settings_retry_evidence(raw, digest, "other-guest", before)
            with self.assertRaisesRegex(RuntimeError, "manifest"):
                verify_settings_retry_evidence(raw, "0" * 64, "exact-test-guest", before)
            with self.assertRaisesRegex(RuntimeError, "exact.*cancel"):
                verify_settings_retry_evidence(raw, digest, "exact-test-guest", before | {"app.pristinepage.debug.test": "c" * 64})
            outcomes.write_text("{}", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "anchor"):
                verify_settings_retry_evidence(raw, digest, "exact-test-guest", before)

    def test_retry_authority_is_spent_even_when_the_guest_never_confirms(self):
        with TemporaryDirectory() as scratch:
            root = Path(scratch)
            spend_settings_retry(root, "a" * 64, {"guest": "synthetic", "retry": "first"})
            with self.assertRaisesRegex(RuntimeError, "already spent"):
                spend_settings_retry(root, "a" * 64, {"guest": "synthetic", "retry": "second"})
            self.assertIn('"retry": "first"', (root / ("settings-retry-spent-" + "a" * 64 + ".json")).read_text())

    def test_cold_receipt_recovery_requires_the_exact_standalone_retained_result(self):
        flags = dict(settings_recover="a" * 64, settings_backup=False, settings_picker_only=False, settings_retry=None,
                     fresh=False, backup_export=False, probe_write=False, ocr_activation=False, working_catalog=False,
                     probe_directory=False, activation=False, selected_catalog=False, activation_compatibility=False, expect_home=False,
                     settings_prior_failure=None)
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#coldSettingsRecoversTheExactCompletedBackupWithoutWriting"
        self.assertEqual(case, select_settings_recovery_case(Namespace(**flags)))
        for key in flags.keys() - {"settings_recover"}:
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "retained.*standalone"):
                select_settings_recovery_case(Namespace(**(flags | {key: True})))
        self.assertIsNone(select_settings_recovery_case(Namespace(**(flags | {"settings_recover": None}))))

    def test_failed_death_picker_control_cannot_be_combined_with_a_write(self):
        flags = dict(backup_death_picker_only=True, backup_delivery_death=False, settings_backup=False,
                     settings_picker_only=False, settings_retry=None, settings_prior_failure=None, settings_recover=None,
                     fresh=False, backup_export=False, probe_write=False, ocr_activation=False, working_catalog=False,
                     probe_directory=False, activation=False, selected_catalog=False, activation_compatibility=False, expect_home=False)
        self.assertEqual("app.pristinepage.LibraryFolderFilesJourneyTest#checksTheBackupDeathPickerRouteWithoutSelection",
                         select_backup_death_picker_case(Namespace(**flags)))
        for key in flags.keys() - {"backup_death_picker_only"}:
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "standalone"):
                select_backup_death_picker_case(Namespace(**(flags | {key: True})))

    def test_death_receipt_only_rejects_other_modes_and_unauthenticated_receipts(self):
        pin = "b6f9e9329f39c067cecdb1c1130b9f8f28bf65492e17709750c89d38bdfa86cd"
        self.assertIsNone(verify_backup_receipt_only(None, False))
        self.assertEqual(pin, verify_backup_receipt_only(pin, False))
        for receipt, mixed in ((pin, True), ("0"*64, False)):
            with self.assertRaisesRegex(RuntimeError, "exact.*standalone"):
                verify_backup_receipt_only(receipt, mixed)

    def test_actual_tree_ancestry_is_a_standalone_read_only_batch(self):
        self.assertEqual(("app.pristinepage.LibraryFolderFilesJourneyTest#persistsExactFilesReadForColdAncestry",
                          "app.pristinepage.LibraryProviderAncestryColdTest#provesManagedAncestryWithoutEffectiveWrite"),
                         select_tree_ancestry(True, False))
        self.assertEqual((), select_tree_ancestry(False, True))
        with self.assertRaisesRegex(RuntimeError, "read-only.*standalone"):
            select_tree_ancestry(True, True)

    def test_actual_provider_capacity_refusal_is_a_single_nonpublication_case(self):
        self.assertIsNone(select_provider_capacity(False, True))
        self.assertEqual("app.pristinepage.LibraryFolderFilesJourneyTest#actualFilesCapacityRefusesBeforePublication",
                         select_provider_capacity(True, False))
        with self.assertRaisesRegex(RuntimeError, "capacity.*standalone"):
            select_provider_capacity(True, True)

    def test_independent_roots_require_exact_grant_handoff_and_no_mixed_writer(self):
        self.assertEqual((
            "app.pristinepage.LibraryFolderFilesJourneyTest#persistsIndependentFilesRootsAndAnchor",
            "app.pristinepage.LibraryProviderAncestryColdTest#twoIndependentFilesRootsRequireDisjointPhysicalAncestry",
        ), select_independent_roots(True, False))
        self.assertEqual((), select_independent_roots(False, True))
        with self.assertRaisesRegex(RuntimeError, "independent.*standalone"):
            select_independent_roots(True, True)

    def test_ancestry_continuation_requires_the_exact_grant_boundary_refusal(self):
        cls, method = "app.pristinepage.LibraryFolderFilesJourneyTest", "exactFilesTreeProvesManagedDirectoryAncestry"
        case = f"{cls}#{method}"
        pair = {"app.pristinepage.debug": "a" * 64, "app.pristinepage.debug.test": "b" * 64}
        fields = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\n"
        errors = "Files retained effective write access during the read-only probe expected:<-1> but was:<0>"
        transcript = fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + (
            f"INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: {errors}\n"
            "INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_RESULT: stream=\n"
            "FAILURES!!!\nTests run: 1,  Failures: 1\nINSTRUMENTATION_CODE: -1\n")
        evidence = {"artifacts.json": json.dumps({"guest": "assigned"}),
                    "outcomes.json": json.dumps({"passed": [], "failed": [case], "installedSha256": pair}),
                    "tree-ancestry-instrumentation.txt": transcript}
        verify_ancestry_grant_refusal(evidence.__getitem__, "assigned", pair)
        for guest, apks, trace in (("other", pair, transcript), ("assigned", {}, transcript),
                                   ("assigned", pair, transcript.replace(errors, "Unrelated failure"))):
            with self.subTest(guest=guest, apks=apks, trace=trace), self.assertRaisesRegex(RuntimeError, "grant boundary"):
                verify_ancestry_grant_refusal((evidence | {"tree-ancestry-instrumentation.txt": trace}).__getitem__, guest, apks)

    def test_ancestry_continuation_can_admit_the_observed_pre_picker_refusal(self):
        cls, method = "app.pristinepage.LibraryFolderFilesJourneyTest", "persistsExactFilesReadForColdAncestry"
        case = f"{cls}#{method}"
        pair = {"app.pristinepage.debug": "a" * 64, "app.pristinepage.debug.test": "b" * 64}
        fields = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\n"
        text = "The cold ancestry guest must start without retained URI grants"
        transcript = fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + (
            f"INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: {text}\n"
            "INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_RESULT: stream=\n"
            "FAILURES!!!\nTests run: 1,  Failures: 1\nINSTRUMENTATION_CODE: -1\n")
        evidence = {"artifacts.json": json.dumps({"guest": "assigned"}),
                    "outcomes.json": json.dumps({"passed": [], "failed": [case], "installedSha256": pair}),
                    "tree-ancestry-grant-instrumentation.txt": transcript}
        verify_ancestry_grant_refusal(evidence.__getitem__, "assigned", pair, before_picker=True)
        with self.assertRaisesRegex(RuntimeError, "grant boundary"):
            verify_ancestry_grant_refusal((evidence | {"tree-ancestry-grant-instrumentation.txt":
                transcript.replace(text, "An unexpected URI was returned")}).__getitem__, "assigned", pair, before_picker=True)

    def test_cold_ancestry_handoff_refuses_any_prior_cold_launch(self):
        cls, method = "app.pristinepage.LibraryFolderFilesJourneyTest", "persistsExactFilesReadForColdAncestry"
        case = f"{cls}#{method}"
        folder = "PristinePage-tree-012345abcdef"
        app, test = "app.pristinepage.debug", "app.pristinepage.debug.test"
        pair = {app: "a" * 64, test: "b" * 64}
        fields = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\n"
        transcript = fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + (
            "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        calls = [dict(argv=["shell", "am", "instrument", case, "libraryFixtureFolder", folder], exit=0),
                 dict(argv=["shell", "am", "force-stop", app], exit=0),
                 dict(argv=["shell", "pidof", app, test], exit=0, stdout="3749\n")]
        evidence = {"artifacts.json": json.dumps({"guest": "assigned", "proof": {k: {"sha256": v} for k, v in pair.items()}, "fixture": {}}),
                    "tree-ancestry-grant-instrumentation.txt": transcript}
        def anchor(loaded):
            return (evidence | {"commands.jsonl": "\n".join(map(json.dumps, loaded))}).__getitem__
        self.assertEqual((f"/sdcard/Documents/{folder}", {}), verify_ancestry_cold_handoff(anchor(calls), "assigned", pair))
        replay = dict(argv=["shell", "am", "instrument", "app.pristinepage.LibraryProviderAncestryColdTest#provesManagedAncestryWithoutEffectiveWrite"])
        with self.assertRaisesRegex(RuntimeError, "cold.*handoff"):
            verify_ancestry_cold_handoff(anchor([*calls, replay]), "assigned", pair)

    def test_death_retry_requires_both_no_write_controls_on_the_same_exact_pair(self):
        def transcript(case):
            cls, method = case.split("#")
            fields = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\n"
            return fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + (
                "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")

        prefix = "app.pristinepage.LibraryFolderFilesJourneyTest#"
        picker = prefix + "checksTheBackupDeathPickerRouteWithoutSelection"
        descriptor = prefix + "exactFilesTreeExposesSyncableDirectoryDescriptors"
        pair = {"app": "a"*64, "test": "b"*64}
        def receipt(passed):
            return {
                "artifacts.json": json.dumps({"guest": "synthetic", "backupDeliveryDeath": None}),
                "outcomes.json": json.dumps({"passed": passed, "failed": [], "installedSha256": pair}),
                "death-picker-instrumentation.txt": transcript(picker),
                "directory-probe-instrumentation.txt": transcript(descriptor),
            }
        verify_backup_death_control(receipt([picker, descriptor]).__getitem__, "synthetic", pair)
        with self.assertRaisesRegex(RuntimeError, "exact.*controls"):
            verify_backup_death_control(receipt([picker]).__getitem__, "synthetic", pair)
        with self.assertRaisesRegex(RuntimeError, "exact.*controls"):
            verify_backup_death_control(receipt([picker, descriptor]).__getitem__, "synthetic", pair | {"test": "c"*64})

    def test_cold_recovery_does_not_trust_a_replaced_local_catalog(self):
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#settingsPublishesOneBackupOfTheSyntheticDefaultLibrary"
        class_name, method = case.split("#")
        fields = f"INSTRUMENTATION_STATUS: class={class_name}\nINSTRUMENTATION_STATUS: test={method}\n"
        transcript = fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields + (
            "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n")
        pair = {"app.pristinepage.debug": "a" * 64, "app.pristinepage.debug.test": "b" * 64}
        generation = "10000000-aaaa-4aaa-8aaa-000000000001"
        library = "10000000-aaaa-4aaa-8aaa-000000000002"
        backup = f"/sdcard/Documents/PristinePage-tree-0123456789ab/PristinePage-backup-{generation}"
        catalog = b"verified"
        marker = f"pristinepage-library-v1\n{library}\n{generation}\n-\n8\n8\n{sha256(catalog).hexdigest()}\n"
        witness = "\n".join(["info"] * 6 + [library, generation, backup.rsplit("/", 1)[1]] + ["info"] * 6) + "\n"
        with TemporaryDirectory() as scratch:
            raw = Path(scratch)
            entries = {
                "artifacts.json": {"guest": "exact-test-guest", "fixture": {},
                                   "proof": {p: {"sha256": sha} for p, sha in pair.items()}},
                "outcomes.json": {"passed": [case], "failed": [], "installedSha256": pair},
                "claim.json": {"name": "exact-test-guest", "generation": generation, "state": "running",
                               "created_by_agent": True, "api": 28, "purpose": "pristinepage-library-app-tree",
                               "created": "fixture", "index": 3, "installation": "sample", "worktree": "sample",
                               "runtime_processes": [[10, 100], [11, 101]]},
                "commands.jsonl": [
                    {"argv": ["adb", "shell", "getprop", "sys.boot_completed"], "timeout": 1},
                    {"argv": ["adb", "shell", "cat", f"{backup}/generations/{generation}.complete"], "exit": 0, "stdout": marker},
                    {"argv": ["adb", "shell", "run-as", "app.pristinepage.debug", "cat", "files/library-settings-native-witness"], "exit": 0, "stdout": witness},
                ],
                "instrumentation.txt": transcript,
            }

            def pin(inputs):
                anchors = []
                for suffix, value in inputs.items():
                    path = raw / f"{raw.name}-{suffix}"
                    text = "\n".join(map(json.dumps, value)) if suffix == "commands.jsonl" else value if isinstance(value, str) else json.dumps(value)
                    path.write_bytes(text.encode("utf-8"))
                    anchors.append({"path": str(path), "bytes": path.stat().st_size, "sha256": sha256(path.read_bytes()).hexdigest()})
                manifest = raw / f"{raw.name}-final-manifest.json"
                manifest.write_text(json.dumps({"stage": "verified-before-release", "generation": generation,
                                                "evidence": anchors, "ancillaryEvidence": []}), encoding="utf-8")
                return sha256(manifest.read_bytes()).hexdigest()

            local = raw / "settings-closed-catalog.sqlite"
            local.write_bytes(catalog)
            for invalid in (entries | {"instrumentation.txt": "No execution"}, {k: v for k, v in entries.items() if k != "instrumentation.txt"}):
                with self.assertRaisesRegex(RuntimeError, "Instrumentation|anchor"):
                    settings_completed_destination(raw, pin(invalid), "exact-test-guest", pair)
            digest = pin(entries)
            settings_completed_destination(raw, digest, "exact-test-guest", pair)
            local.write_bytes(b"replaced")
            with self.assertRaisesRegex(RuntimeError, "retained catalog"):
                settings_completed_destination(raw, digest, "exact-test-guest", pair)

    def test_settings_ocr_readback_binds_even_empty_text_to_the_exact_producer(self):
        with closing(sqlite3.connect(":memory:")) as db:
            db.executescript("""
                CREATE TABLE OcrResult(pageId,recipeRevision,sourcePixelSha256,engineId,engineVersion,layout,indexedText,verbatimText);
                CREATE TABLE OcrPageWork(pageId,resultJobId,projectionVersion);
                CREATE TABLE Job(jobId,canonicalInputSha256,state);
                INSERT INTO OcrResult VALUES ('page',2,'pixels','engine',1,'AUTOMATIC','',NULL);
                INSERT INTO OcrPageWork VALUES ('page','producer',1);
                INSERT INTO Job VALUES ('producer','input','SUCCEEDED');
            """)
            expected = ("page", 2, "pixels", "engine", 1, "AUTOMATIC", "producer", "input", 1, sha256(b"").hexdigest())
            verify_settings_ocr(db, expected)
            db.execute("UPDATE OcrResult SET verbatimText='unexpected'")
            with self.assertRaisesRegex(RuntimeError, "OCR.*binding"):
                verify_settings_ocr(db, expected)
            db.execute("UPDATE OcrResult SET verbatimText=NULL")
            db.execute("UPDATE Job SET canonicalInputSha256='different'")
            with self.assertRaisesRegex(RuntimeError, "OCR.*binding"):
                verify_settings_ocr(db, expected)

    def test_backup_delivery_death_requires_exact_checkpoint_and_process_crash(self):
        operation = "10000000-aaaa-4aaa-8aaa-000000000001"
        uuid = "10000000-aaaa-4aaa-8aaa-000000000002"
        receipt = ":".join((uuid, uuid))
        bindings = ":".join((uuid, uuid, uuid, "a"*64, "b"*64, "c"*64))
        trace = (
            "INSTRUMENTATION_STATUS: class=app.pristinepage.LibraryFolderFilesJourneyTest\n"
            "INSTRUMENTATION_STATUS: test=endsProcessAfterBackupCommitBeforeDelivery\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: backupDeathOperation={operation}\n"
            "INSTRUMENTATION_STATUS: backupDeathPid=3021\n"
            f"INSTRUMENTATION_STATUS: backupDeathReceipt={receipt}\n"
            f"INSTRUMENTATION_STATUS: backupDeathBindings={bindings}\n"
            "INSTRUMENTATION_STATUS_CODE: 71\n"
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n"
            "INSTRUMENTATION_CODE: 0\n"
        )
        self.assertEqual(("3021", receipt.split(":"), bindings.split(":")), read_backup_delivery_death(trace, operation))
        prior_marker = trace.replace("endsProcessAfterBackupCommitBeforeDelivery", "endsProcessAfterPayloadBeforeTheCompletionMarker").replace(
            "INSTRUMENTATION_STATUS_CODE: 71", f"INSTRUMENTATION_STATUS: backupDeathGrantInventory={'d'*64}\nINSTRUMENTATION_STATUS_CODE: 72")
        self.assertEqual(("3021", receipt.split(":"), bindings.split(":"), "d"*64),
                         read_backup_delivery_death(prior_marker, operation, before_marker=True))
        for wrong, before_marker in ((trace, True), (prior_marker, False), (prior_marker.replace("STATUS_CODE: 72", "STATUS_CODE: 71"), True),
                                     (prior_marker.replace(f"INSTRUMENTATION_STATUS: backupDeathGrantInventory={'d'*64}\n", ""), True)):
            with self.assertRaisesRegex(RuntimeError, "checkpoint"):
                read_backup_delivery_death(wrong, operation, before_marker=before_marker)
        folder = "PristinePage-tree-012345abcdef"
        pair = {"app.pristinepage.debug": "e"*64, "app.pristinepage.debug.test": "f"*64}
        case = "app.pristinepage.LibraryFolderFilesJourneyTest#endsProcessAfterPayloadBeforeTheCompletionMarker"
        evidence = {"artifacts.json": json.dumps({"guest": "assigned", "backupPreMarker": True, "fixture": {},
                    "backupDeliveryDeath": operation, "proof": {package: {"sha256": sha} for package, sha in pair.items()}}),
                    "backup-death-attempt.json": json.dumps({"operation": operation, "destination": folder, "kind": "single-pre-marker-death"}),
                    "backup-death-instrumentation.txt": prior_marker}
        spent = {"operation": operation, "destination": f"/sdcard/Documents/{folder}", "kind": "single-pre-marker-death"}
        calls = [dict(argv=["shell", "am", "instrument", case, "libraryFixtureFolder", folder], exit=0)]
        def anchor(log):
            return (evidence | {"commands.jsonl": "\n".join(map(json.dumps, log))}).__getitem__
        self.assertEqual((spent["destination"], {}, operation, ("3021", receipt.split(":"), bindings.split(":"), "d"*64)),
                         verify_pre_marker_handoff(anchor(calls), spent, "assigned", pair))
        with self.assertRaisesRegex(RuntimeError, "prior cold launch"):
            verify_pre_marker_handoff(anchor(calls + [dict(argv=["shell", "am", "instrument", "other"])]), spent, "assigned", pair)
        with self.assertRaisesRegex(RuntimeError, "spent"):
            verify_pre_marker_handoff(anchor(calls), spent | {"operation": uuid}, "assigned", pair)
        for bad in (trace.replace("Process crashed.", "Test failed."), trace.replace(operation, operation[:-1] + "2"),
                    trace.replace("backupDeathPid=3021", "backupDeathPid=3021\nINSTRUMENTATION_STATUS: backupDeathPid=501"),
                    trace.replace("backupDeathPid=3021", "backupDeathPid=3021\nINSTRUMENTATION_STATUS: backupDeathPid=malformed"),
                    trace.replace("INSTRUMENTATION_STATUS_CODE: 1\n", "").replace(
                        "INSTRUMENTATION_STATUS_CODE: 71", "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 71"),
                    trace.replace("INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_CODE: -1\nINSTRUMENTATION_RESULT: shortMsg="),
                    trace.replace("STATUS_CODE: 71", "STATUS_CODE: 0"), trace + "INSTRUMENTATION_CODE: -1\n"):
            with self.assertRaisesRegex(RuntimeError, "checkpoint"):
                read_backup_delivery_death(bad, operation)

    def test_death_readback_rejects_a_valid_private_intent_for_another_catalog(self):
        operation = "10000000-aaaa-4aaa-8aaa-000000000001"
        library = "10000000-aaaa-4aaa-8aaa-000000000002"
        generation = "10000000-aaaa-4aaa-8aaa-000000000003"
        parent = "PristinePage-tree-0123456789ab"
        isolated = f"files/library-export-source-{operation}"
        attempt = f"{isolated}/files/library-out-{operation}"
        marker = f"pristinepage-library-v1\n{library}\n{generation}\n-\n8\n536576\n{'a'*64}\n"
        digest = lambda text: sha256(text.encode("ascii")).hexdigest()
        grant = f"pristinepage-library-grant-v1\n{operation}\n3\ncontent://com.android.externalstorage.documents/tree/home%3A{parent}\n"

        def evidence(record_marker):
            body = f"pristinepage-library-export-intent-v2\n{operation}\n{library}\n1\n1\n1\n{grant}{digest(grant)}\n{record_marker}"
            intent = f"{body}{digest(body)}\n"
            complete = f"pristinepage-library-export-complete-v2\n{operation}\n{digest(intent)}\n"
            leaves = {f"{attempt}/export.intent": intent, f"{attempt}/export.complete": complete}
            return lambda *cmd: leaves[cmd[-1]], {"files": {path: (len(value), digest(value)) for path, value in leaves.items()}}

        adb, baseline = evidence(marker)
        verify_backup_death_intent(adb, isolated, operation, parent, marker, baseline)
        adb, baseline = evidence(marker.replace("a"*64, "b"*64))
        with self.assertRaisesRegex(RuntimeError, "does not match.*intent"):
            verify_backup_death_intent(adb, isolated, operation, parent, marker, baseline)

    def test_settings_readback_rejects_a_valid_catalog_with_another_source_binding(self):
        first, second = (b'{"geometry":{"outputQuarterTurns":0}}', b'{"geometry":{"outputQuarterTurns":1}}')
        original = "5" * 64
        expected = ("album", "page", "source", "pixels", sha256(first).hexdigest(), sha256(second).hexdigest())
        with TemporaryDirectory() as root:
            path = Path(root) / "closed.sqlite"
            with closing(sqlite3.connect(path)) as db:
                db.executescript("""
                    CREATE TABLE Document(documentId TEXT PRIMARY KEY, title TEXT, documentRevision INTEGER);
                    CREATE TABLE SourceBlob(sourceBlobId TEXT PRIMARY KEY, byteSha256 TEXT, byteCount INTEGER);
                    CREATE TABLE Source(sourceId TEXT PRIMARY KEY, sourceBlobId TEXT REFERENCES SourceBlob, pixelSha256 TEXT);
                    CREATE TABLE Page(pageId TEXT PRIMARY KEY, documentId TEXT REFERENCES Document,
                        sourceId TEXT REFERENCES Source, currentRecipeRevision INTEGER, ordinal INTEGER);
                    CREATE TABLE LibraryScanName(pageId TEXT REFERENCES Page, documentId TEXT REFERENCES Document, filename TEXT);
                    CREATE TABLE RecipeRevision(pageId TEXT REFERENCES Page, sourceId TEXT REFERENCES Source,
                        recipeRevision INTEGER, parentRevision INTEGER, canonicalRecipe BLOB);
                    CREATE TABLE UndoEntry(documentId TEXT REFERENCES Document, action TEXT, appliedDocumentRevision INTEGER, state TEXT);
                    CREATE TABLE OcrResult(resultId TEXT);
                    INSERT INTO Document VALUES ('album','Synthetic Settings backup',3);
                    INSERT INTO Page VALUES ('page','album','source',2,0);
                    INSERT INTO Source VALUES ('source','blob','pixels');
                    INSERT INTO LibraryScanName VALUES ('page','album','Scan page.png');
                    INSERT INTO UndoEntry VALUES ('album','RECIPE_EDIT',3,'AVAILABLE');
                """)
                db.execute("INSERT INTO SourceBlob VALUES ('blob',?,75)", (original,))
                db.executemany("INSERT INTO RecipeRevision VALUES ('page','source',?,?,?)", [(1,None,first),(2,1,second)])
                db.commit()
                # Exercise the real FTS4 capability required by a Room backup, not just the host Python build's regular tables.
                run([shutil.which("sqlite3"), "-batch", "-bail", str(path), "CREATE VIRTUAL TABLE FixtureSearch USING fts4(text)"],
                    check=True, timeout=10, capture_output=True)
                verify_settings_catalog(path, original, 75, expected)
                db.execute("UPDATE Source SET pixelSha256 = 'another normalized binding'")
                db.commit()
                self.assertEqual([], db.execute("PRAGMA foreign_key_check").fetchall())
                with self.assertRaisesRegex(RuntimeError, "scan.*binding"):
                    verify_settings_catalog(path, original, 75, expected)
