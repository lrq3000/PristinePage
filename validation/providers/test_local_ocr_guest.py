"""Host-only release-gate controls; never touch the allocator or an Android device."""

import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from local_ocr_files_journey import LocalFilesJourney
from run_local_ocr_guest import APP, CASE, TEST, install_flags, normalize_config, passed_case, recovery_pid


def block(class_name, method, code):
    return (f"INSTRUMENTATION_STATUS: class={class_name}\r\n"
            f"INSTRUMENTATION_STATUS: test={method}\r\nINSTRUMENTATION_STATUS_CODE: {code}\r\n")


class LocalOcrGuestTest(unittest.TestCase):
    def test_fresh_registration_requires_exact_unused_stopped_index_and_both_disks(self):
        from run_local_ocr_guest import inspect_fresh_registration

        target = "agent-pristinepage-ocr-0123456789ab"
        with TemporaryDirectory() as temp:
            home = Path(temp).resolve()
            vm = home / "vms/leidian2"
            vm.mkdir(parents=True)
            before = [["0", "LDPlayer", "0", "0", "0", "-1", "-1"]]
            stopped = ["2", target, "0", "0", "0", "-1", "-1"]
            (vm / "data.vmdk").write_bytes(b"data")
            with self.assertRaises(RuntimeError):
                inspect_fresh_registration(before, before + [stopped], target, home)
            (vm / "sdcard.vmdk").write_bytes(b"sdcard")
            self.assertEqual(2, inspect_fresh_registration(before, before + [stopped], target, home))
            for after in (before, before + [stopped, stopped], [stopped[:4] + ["1", "1234", "1235"]],
                          [["0", *stopped[1:]]], [stopped, ["2", *before[0][1:]]]):
                with self.subTest(after=after), self.assertRaises(RuntimeError):
                    inspect_fresh_registration(before, after, target, home)

    def test_airplane_verification_requires_no_connected_network_or_default_route(self):
        from local_ocr_airplane import disconnected

        offline = "NetworkFactories for: WIFI\nActive default network: none\nCurrent Networks:\nNetwork Requests:\n"
        self.assertTrue(disconnected(offline))
        for invalid in (
            offline.replace("none", "100"),
            offline.replace("Network Requests:", "  NetworkAgentInfo [ETHERNET () - 100] CONNECTED/CONNECTED\nNetwork Requests:"),
            offline.replace("Network Requests:", "  NetworkAgentInfo{ni{[type: WIFI[], state: CONNECTING/CONNECTING]}}\nNetwork Requests:"),
            offline.replace("Network Requests:", "  output truncated before the first network\nNetwork Requests:"),
            offline.removesuffix("Network Requests:\n"),
            "Permission Denial: missing dump access",
            offline + "Active default network: none\n",
        ):
            with self.subTest(invalid=invalid):
                self.assertFalse(disconnected(invalid))

    def test_airplane_and_save_as_are_independent_journeys(self):
        runner = Path(__file__).with_name("run_local_ocr_guest.py")
        for flags, message in (
            (["--airplane", "--save-as"], "Choose airplane-mode search or external JPEG Save as, not both"),
            (["--airplane"], "Unattended radio switching is disabled"),
            (["--save-as"], "Unattended Save as is disabled"),
            (["--inspect-save-as"], "Save as inspection requires the retained exact synthetic Files album"),
        ):
            with self.subTest(flags=flags):
                completed = subprocess.run(
                    [sys.executable, "-B", runner, "--existing", "unused", *flags,
                     "--files-source", "unused", "unused", "unused", "unused", "unused", "unused"],
                    capture_output=True, text=True, timeout=15,
                )
                self.assertEqual(2, completed.returncode, completed.stderr)
                self.assertIn(message, completed.stderr)

    def test_radio_isolation_needs_positive_wifi_adapter_state(self):
        from local_ocr_airplane import isolated

        radio = {"airplane_mode_on": "1", "wifi_on": "2", "mobile_data": "0"}
        networks = "Active default network: none\nCurrent Networks:\nNetwork Requests:\n"
        self.assertTrue(isolated(radio, networks, "Wi-Fi is disabled\nOther debug fields"))
        for wifi in ("Wi-Fi is enabled", "Permission Denial", "Wi-Fi is disabled\nWi-Fi is enabled"):
            self.assertFalse(isolated(radio, networks, wifi), wifi)
        self.assertFalse(isolated(dict(radio, wifi_on="1"), networks, "Wi-Fi is disabled"))
        self.assertFalse(isolated(dict(radio, mobile_data="1"), networks, "Wi-Fi is disabled"))

    def test_cold_files_runner_needs_a_complete_named_pass_and_exact_witness(self):
        from run_local_ocr_airplane import CASE as AIRPLANE_CASE, MARKER, witnessed

        started = block(*AIRPLANE_CASE, 1)
        passed = block(*AIRPLANE_CASE, 0)
        marker = f"INSTRUMENTATION_RESULT: offlineOcrWitness={MARKER}\n"
        ending = "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        self.assertTrue(witnessed(started + passed + marker + ending))
        for transcript in (started + passed + ending, started + passed + marker * 2 + ending,
                           started + block(*AIRPLANE_CASE, -4) + marker + ending, started + passed + marker):
            self.assertFalse(witnessed(transcript))

    def test_cold_files_runner_rejects_missing_or_connected_radio_readback(self):
        from run_local_ocr_airplane import radio_proven

        good = {"before-state": "1\n0\n0\n", "before-wifi": "Wi-Fi is disabled\n",
                "before-networks": "Active default network: none\nCurrent Networks:\nNetwork Requests:\n"}
        self.assertTrue(radio_proven(good, "before"))
        self.assertFalse(radio_proven(good, "after"))
        self.assertFalse(radio_proven(dict(good, **{"before-wifi": "Wi-Fi is enabled\n"}), "before"))
        self.assertFalse(radio_proven(dict(good, **{"before-state": "1\n2\n0\n"}), "before"))

    def test_ocr_refusal_journey_requires_its_own_instrumented_pass_and_marker(self):
        from run_local_ocr_airplane import RECOVERY_CASE, RECOVERY_MARKER, invocation, witnessed

        case, option, _ = invocation(None, recover_runtime=True)
        self.assertEqual(RECOVERY_CASE, case)
        self.assertEqual("offlineOcrFixture", option)
        ending = "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        transcript = block(*case, 1) + block(*case, 0) + f"INSTRUMENTATION_RESULT: offlineOcrWitness={RECOVERY_MARKER}\n" + ending
        self.assertTrue(witnessed(transcript, recover_runtime=True))
        self.assertFalse(witnessed(transcript))
        self.assertFalse(witnessed(transcript.replace(RECOVERY_MARKER, "EXACT_COLD_FILES_PAGE_IN_AIRPLANE_MODE"), recover_runtime=True))
        with self.assertRaises(RuntimeError):
            invocation("latin", recover_runtime=True)

    def test_offline_app_update_requires_an_exact_predecessor_for_both_packages(self):
        runner = Path(__file__).with_name("run_local_ocr_airplane.py")
        for flags in (["--previous-app-sha", "a" * 64],
                      ["--previous-app-sha", "bad", "--previous-test-sha", "b" * 64]):
            with self.subTest(flags=flags):
                completed = subprocess.run(
                    [sys.executable, "-B", runner, "--existing", "unused", *flags,
                     "unused", "unused", "unused", "unused", "unused", "unused"],
                    capture_output=True, text=True, timeout=15,
                )
                self.assertEqual(2, completed.returncode, completed.stderr)
                self.assertIn("An app update requires both exact predecessor digests", completed.stderr)

    def test_language_and_rerun_modes_refuse_unscoped_arguments_before_apk_admission(self):
        runner = Path(__file__).with_name("run_local_ocr_guest.py")
        for flags, message in (
            (["--manual-rerun", ""], "Rerun requires an exact existing synthetic recovery title"),
            (["--manual-rerun", "OCR recovery 0123456789ab", "--enable-local-adb"], "Guest configuration is not part of language or rerun validation"),
            (["--language-controls", "unused", "--enable-local-adb"], "Guest configuration is not part of language or rerun validation"),
        ):
            with self.subTest(flags=flags):
                completed = subprocess.run([sys.executable, "-B", runner, "--existing", "unused", *flags,
                                            "unused", "unused", "unused", "unused", "unused"],
                                           capture_output=True, text=True, timeout=15)
                self.assertEqual(2, completed.returncode, completed.stderr)
                self.assertIn(message, completed.stderr)

    def test_recovery_requires_exact_checkpoint_and_android_process_death(self):
        started = block("app.pristinepage.ocr.LocalOcrWorkDeathTest", "interruptRealNativeRecognition", 1)
        checkpoint = "INSTRUMENTATION_STATUS: ocrRecoveryCheckpoint=2\nINSTRUMENTATION_STATUS: ocrRecoveryPid=8524\nINSTRUMENTATION_STATUS_CODE: 64\n"
        crash = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertEqual(8524, recovery_pid(started + checkpoint + crash, 2))
        for invalid in (started + crash, started + checkpoint, started + checkpoint + checkpoint + crash,
                        started + checkpoint.replace("Checkpoint=2", "Checkpoint=3") + crash,
                        started.replace("interruptRealNativeRecognition", "other") + checkpoint + crash):
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                recovery_pid(invalid, 2)

    def test_slow_ui_read_can_retry_once_without_repeating_input(self):
        class Read:
            def __init__(self, succeeds):
                self.calls = []
                self.succeeds = succeeds

            def command(self, *args, **kwargs):
                self.calls.append(args)
                if len(self.calls) == 1 or not self.succeeds:
                    raise subprocess.TimeoutExpired(args, kwargs["timeout"])
                return "fresh hierarchy"

        for succeeds in (True, False):
            read = Read(succeeds)
            if succeeds:
                self.assertEqual("fresh hierarchy", LocalFilesJourney.read_tree_output(read))
            else:
                with self.assertRaises(subprocess.TimeoutExpired):
                    LocalFilesJourney.read_tree_output(read)
            self.assertEqual([("exec-out", "uiautomator", "dump", "/dev/tty")] * 2, read.calls)

    def test_transient_null_root_is_reobserved_without_input(self):
        calls = []

        class Read:
            def command(self, *args, **kwargs):
                calls.append(args)
                return "ERROR: null root node returned by UiTestAutomationBridge." if len(calls) == 1 else "<hierarchy/>"

        self.assertEqual("<hierarchy/>", LocalFilesJourney.read_tree_output(Read()))
        self.assertEqual([("exec-out", "uiautomator", "dump", "/dev/tty")] * 2, calls)

    def test_only_reviewed_instrumentation_can_be_updated_in_place(self):
        self.assertEqual(["-t"], install_flags(TEST, None, "new", None))
        self.assertIsNone(install_flags(APP, "same", "same", None))
        self.assertEqual(["-r", "-t"], install_flags(TEST, "previous", "new", "previous"))
        for package, installed, approved in ((APP, "previous", "previous"), (TEST, "unrelated", "previous"),
                                             (TEST, "previous", None)):
            with self.subTest(package=package, installed=installed), self.assertRaises(RuntimeError):
                install_flags(package, installed, "new", approved)

    def test_picker_inspection_refuses_missing_members_of_the_retained_pair(self):
        for package in (APP, TEST):
            with self.subTest(package=package), self.assertRaisesRegex(RuntimeError, "installed APK"):
                install_flags(package, None, "candidate", "previous-test", require_installed=True)
        self.assertIsNone(install_flags(APP, "same", "same", None, require_installed=True))
        self.assertEqual(["-r", "-t"], install_flags(TEST, "previous-test", "candidate", "previous-test", require_installed=True))

    def test_local_save_target_is_bound_to_one_foreground_provider_directory(self):
        from local_jpeg_destination import matches_local_downloads

        folder = "PristinePage-Export-ab1234567890"
        authority = "com.android.providers.downloads.documents"
        path = f"/storage/emulated/0/Download/{folder}"
        uri = f"content://{authority}/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2F{folder}"
        component = "com.android.documentsui/.picker.PickActivity"
        dump = (
            f"  ACTIVITY {component} abc123 pid=42\n"
            "      Active Fragments in 74a9797:\n"
            "        #2: null\n"
            "        #3: DirectoryFragment{def456 #4 id=0x7f090044}\n"
            "          mAdded=true mRemoving=false mFromLayout=false mInLayout=false\n"
            "          mHidden=false mDetached=false mMenuVisible=true mHasMenu=false\n"
            f"          mArguments=Bundle[{{selection=size=0, items=[], root=Root{{authority={authority}, rootId=downloads, title=Downloads, "
            f"isUsb=false, isSd=false, isMtp=false}} @ content://{authority}/root/downloads, "
            f"document=DocumentInfo{{docId=raw:{path}, name={folder}, mimeType=vnd.android.document/directory, "
            f"isContainer=true, isDirectory=true, isCreateSupported=true}} @ {uri}, ignoreState=true}}]\n"
            "      Added Fragments:\n"
            "        #2: DirectoryFragment{def456 #4 id=0x7f090044}\n"
            "      FragmentManager misc state:\n"
        )
        self.assertTrue(matches_local_downloads(dump, component, path))
        for invalid in (
            dump.replace(authority, "test.remote.documents"), dump.replace(f"Download/{folder}", f"Elsewhere/{folder}"),
            dump.replace("%2FDownload%2F", "%2FOther%2F"), dump.replace("isCreateSupported=true", "isCreateSupported=false"),
            dump.replace("DirectoryFragment", "OtherFragment"), dump + dump,
            dump.replace(component, "elsewhere/.Picker"), dump.replace("isUsb=false", "isUsb=true"),
            dump.replace("mHidden=false", "mHidden=true"), dump.replace("mAdded=true", "mAdded=false"),
            dump.replace("Added Fragments:", "Historical Fragments:"),
            dump.replace("Active Fragments in 74a9797:", "Active Fragments in another manager:"),
            dump.replace("Active Fragments in 74a9797:", "Active Fragments in 1234:\n      Active Fragments in 74a9797:"),
            dump.replace("Added Fragments:\n        #2: DirectoryFragment{def456", "Added Fragments:\n        #2: DirectoryFragment{abc123"),
            dump.replace("          mAdded=true", "        #4: Unparsed.Fragment{abcdef}\n          mAdded=true"),
            dump.replace("ignoreState=true", "unrecognized=value"),
        ):
            self.assertFalse(matches_local_downloads(invalid, component, path))

    def test_only_the_exact_previous_application_may_receive_a_checked_successor(self):
        self.assertEqual(["-r"], install_flags(APP, "old-app", "new-app", "old-test", previous_app="old-app"))
        with self.assertRaises(RuntimeError):
            install_flags(APP, "foreign", "new-app", "old-test", previous_app="old-app")
        with self.assertRaises(RuntimeError):
            install_flags(TEST, "old-app", "new-test", "old-test", previous_app="old-app")

    def test_checked_successor_refuses_mixed_installed_pairs(self):
        from run_local_ocr_guest import check_installed_pair

        prior = {APP: "old-app", TEST: "old-test"}
        candidate = {APP: "new-app", TEST: "new-test"}
        for installed in (prior, candidate):
            check_installed_pair(installed, prior, candidate)
        for installed in ({APP: "old-app", TEST: "new-test"}, {APP: "new-app", TEST: "old-test"}, {APP: "old-app"}):
            with self.assertRaises(RuntimeError):
                check_installed_pair(installed, prior, candidate)

    def test_files_tap_uses_actionable_ancestor_and_refuses_ambiguity(self):
        button = ('<node clickable="true" enabled="true" bounds="[10,20][40,60]">'
                  '<node text="From files" clickable="false"/></node>')
        self.assertEqual(("25", "40"), LocalFilesJourney.point(ET.fromstring("<hierarchy>" + button + "</hierarchy>"), "From files"))
        with self.assertRaises(RuntimeError):
            LocalFilesJourney.point(ET.fromstring("<hierarchy>" + button * 2 + "</hierarchy>"), "From files")
        self.assertIsNone(LocalFilesJourney.point(ET.fromstring("<hierarchy/>"), "From files"))

    def test_picker_label_alternatives_do_not_allow_an_ambiguous_tap(self):
        xml = ('<node clickable="true" enabled="true" bounds="[10,20][40,60]" content-desc="Afficher les racines"/>')
        labels = ("Show roots", "Afficher les racines")
        self.assertEqual(("25", "40"), LocalFilesJourney.point(ET.fromstring("<hierarchy>" + xml + "</hierarchy>"), labels))
        with self.assertRaises(RuntimeError):
            LocalFilesJourney.point(ET.fromstring("<hierarchy>" + xml + xml.replace(labels[1], labels[0]) + "</hierarchy>"), labels)

    def test_only_a_picker_document_row_can_use_the_focusable_fallback(self):
        label = "ocr-test.png"
        row = ('<node package="com.android.documentsui" focusable="true" enabled="true" bounds="[0,10][100,60]">'
               f'<node package="com.android.documentsui" text="{label}" resource-id="android:id/title" enabled="true"/></node>')
        inside = f'<hierarchy><node resource-id="com.android.documentsui:id/dir_list">{row}</node></hierarchy>'
        self.assertEqual(("50", "35"), LocalFilesJourney.point(ET.fromstring(inside), label, document_row=True))
        self.assertIsNone(LocalFilesJourney.point(ET.fromstring(inside), label))
        self.assertIsNone(LocalFilesJourney.point(ET.fromstring(f"<hierarchy>{row}</hierarchy>"), label, document_row=True))

    def test_guest_debug_setting_changes_only_local_bridge(self):
        self.assertEqual({"basicSettings.rootMode": False, "basicSettings.adbDebug": 1},
                         normalize_config(b'{"basicSettings.rootMode": false}'))
        for raw in (b'{"basicSettings.adbDebug": 1}', b'{"basicSettings.adbDebug": 2}',
                    b'{"basicSettings.adbDebug": "0"}', b'[]'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                normalize_config(raw)

    def test_accepts_one_matching_pass_only(self):
        started = block(*CASE, 1)
        ok = "OK (1 test)\r\n"
        result = "INSTRUMENTATION_CODE: -1\r\n"
        passed = block(*CASE, 0)
        self.assertTrue(passed_case(started + passed + ok + result, CASE))
        for output in (
            started + passed + ok,
            started + passed + ok + result + result,
            started + passed + ok + result + "Unexpected trailing result\n",
            started + passed + ok + result.replace("-1", "0"),
            started + passed + ok + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\r\n" + result,
            started + block(*CASE, -4) + ok + result,  # A failed assumption is not an actual test.
            started + block(*CASE, -2) + result,
            started + block(CASE[0], "aDifferentTest", 0) + ok + result,
            started + passed + passed + ok + result,
            passed + ok + result,
            started + passed + "OK (2 tests)\n" + result,
        ):
            with self.subTest(output=output):
                self.assertFalse(passed_case(output, CASE))

    def test_sparse_layout_requires_its_own_case_and_single_exact_marker(self):
        import run_local_ocr_guest as guest

        case = (guest.LANGUAGE_CASE[0], "japaneseSparseLayoutRemainsBoundToTheExactSearchHit")
        marker = "INSTRUMENTATION_STATUS: ocrLanguageControl=jpn\r\nINSTRUMENTATION_STATUS_CODE: 65\r\n"
        done = block(*case, 0) + "OK (1 test)\r\nINSTRUMENTATION_CODE: -1\r\n"
        start = block(*case, 1)
        self.assertEqual(("jpn",), guest.checked_language_results(start + marker + done, suite="japanese"))
        for invalid in (start + done, start + marker + marker + done,
                        start + marker.replace("jpn", "eng") + done, block(*guest.LANGUAGE_CASE, 1) + marker + done):
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                guest.checked_language_results(invalid, suite="japanese")

    def test_remaining_script_batch_requires_each_marker_in_order(self):
        import run_local_ocr_guest as guest

        case = (guest.LANGUAGE_CASE[0], "remainingLatinLanguagesReachTheExactHomePage")
        ids = ("spa", "deu", "ita", "por", "nld", "pol", "tur")
        markers = [f"INSTRUMENTATION_STATUS: ocrLanguageControl={control}\nINSTRUMENTATION_STATUS_CODE: 65\n" for control in ids]
        done = block(*case, 0) + "\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
        start = block(*case, 1)
        self.assertEqual(ids, guest.checked_language_results(start + "".join(markers) + done, suite="latin"))
        for reordered in (markers[:-1], markers + markers[:1], markers[::-1]):
            with self.assertRaises(RuntimeError):
                guest.checked_language_results(start + "".join(reordered) + done, suite="latin")

    def test_offline_script_batch_cannot_substitute_the_files_marker(self):
        from run_local_ocr_airplane import invocation
        from run_local_ocr_guest import LANGUAGE_SUITES

        case, option, limit = invocation("latin")
        self.assertEqual(LANGUAGE_SUITES["latin"][0], case)
        self.assertEqual("ocrFixture", option)
        self.assertTrue(0 < limit < 1500)
        self.assertEqual("offlineOcrFixture", invocation(None)[1])
        with self.assertRaises(RuntimeError):
            invocation("not-a-qualified-batch")

    def test_offline_source_is_confined_and_profile_specific(self):
        from run_local_ocr_airplane import offline_source

        root = Path.cwd()
        stage = root / "validation/raw/providers/synthetic-qualified-stage"
        self.assertEqual(stage, offline_source(root, stage, "latin"))
        for path in (root, stage / ".." / ".." / "storage", root.parent / "another-worktree"):
            with self.assertRaises(RuntimeError):
                offline_source(root, path, "latin")
        with self.assertRaises(RuntimeError):
            offline_source(root, stage, None)
