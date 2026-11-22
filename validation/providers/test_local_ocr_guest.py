"""Host-only release-gate controls; never touch the allocator or an Android device."""

import subprocess
import unittest
import xml.etree.ElementTree as ET

from local_ocr_files_journey import LocalFilesJourney
from run_local_ocr_guest import APP, CASE, TEST, install_flags, normalize_config, passed_case


def block(class_name, method, code):
    return (f"INSTRUMENTATION_STATUS: class={class_name}\r\n"
            f"INSTRUMENTATION_STATUS: test={method}\r\nINSTRUMENTATION_STATUS_CODE: {code}\r\n")


class LocalOcrGuestTest(unittest.TestCase):
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

    def test_only_reviewed_instrumentation_can_be_updated_in_place(self):
        self.assertEqual(["-t"], install_flags(TEST, None, "new", None))
        self.assertIsNone(install_flags(APP, "same", "same", None))
        self.assertEqual(["-r", "-t"], install_flags(TEST, "previous", "new", "previous"))
        for package, installed, approved in ((APP, "previous", "previous"), (TEST, "unrelated", "previous"),
                                             (TEST, "previous", None)):
            with self.subTest(package=package, installed=installed), self.assertRaises(RuntimeError):
                install_flags(package, installed, "new", approved)

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
        passed = block(*CASE, 0)
        self.assertTrue(passed_case(started + passed + ok, CASE))
        for output in (
            started + block(*CASE, -4) + ok,  # A failed assumption is not an actual test.
            started + block(*CASE, -2),
            started + block(CASE[0], "aDifferentTest", 0) + ok,
            started + passed + passed + ok,
            passed + ok,
            started + passed + "OK (2 tests)\n",
        ):
            with self.subTest(output=output):
                self.assertFalse(passed_case(output, CASE))
