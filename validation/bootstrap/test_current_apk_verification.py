"""Host-only metadata witnesses for current pinned-SDK APK verification."""
import importlib
import unittest


def parse_badging(text, package, min_sdk=26, target_sdk=36):
    try:
        verifier = importlib.import_module("current_apk_verification")
    except ModuleNotFoundError as error:
        if error.name == "current_apk_verification":
            raise AssertionError("Current APK badging verifier is absent") from error
        raise
    return verifier.parse_badging(text, package=package, min_sdk=min_sdk, target_sdk=target_sdk)


APP = "app.pristinepage.debug"
TEST = APP + ".test"


def report(package=APP, version_name="7.3-dev", version_code="71", minimum="minSdkVersion:'26'"):
    return "\n".join((
        f"package: name='{package}' versionCode='{version_code}' versionName='{version_name}' compileSdkVersion='36'",
        minimum,
        "targetSdkVersion:'36'",
        "application-debuggable",
        "",
    ))


class CurrentApkVerificationTest(unittest.TestCase):
    def test_app_accepts_current_badging_and_returns_observed_release_metadata(self):
        for version, code in (("7.3-dev", "71"), ("12.1-debug", "120")):
            with self.subTest(version=version):
                self.assertEqual(parse_badging(report(version_name=version, version_code=code).replace("\n", "\r\n"), APP),
                                 dict(package=APP, version_name=version, version_code=int(code), min_sdk=26, target_sdk=36))

    def test_debug_test_package_can_omit_release_metadata(self):
        self.assertEqual(parse_badging(report(TEST, "", ""), TEST),
                         dict(package=TEST, version_name=None, version_code=None, min_sdk=26, target_sdk=36))

    def test_minimum_sdk_requires_one_exact_field_not_a_target_or_legacy_decoy(self):
        for minimum in ("", "sdkVersion:'26'", "minSdkVersion:26", "minSdkVersion:'28'",
                        "minSdkVersion:'26'\nminSdkVersion:'26'", "minSdkVersion:'26'\nminSdkVersion:'28'",
                        "targetSdkVersion:'26'"):
            with self.subTest(minimum=minimum):
                with self.assertRaises(ValueError):
                    parse_badging(report(minimum=minimum), APP)

    def test_package_and_target_must_match_the_named_artifact(self):
        for bad in (report(TEST), report().replace("targetSdkVersion:'36'", "targetSdkVersion:'34'"),
                    report().replace("targetSdkVersion:'36'", "targetSdkVersion:'36'\ntargetSdkVersion:'36'")):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    parse_badging(bad, APP)


class CurrentApkBadgingBoundaryTest(unittest.TestCase):
    def test_retained_badging_header_forms(self):
        # The package headers are verbatim from the retained Q app/test aapt2 stdout; these are metadata fixtures, not proofs of either artifact.
        for package, fields, name, code in (
            (APP, "versionCode='1' versionName='0.1.0-debug'", "0.1.0-debug", 1),
            (TEST, "versionCode='' versionName=''", None, None),
        ):
            with self.subTest(package=package):
                text = (
                    f"package: name='{package}' {fields} platformBuildVersionName='16' "
                    "platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'\r\n"
                    "minSdkVersion:'26'\r\n"
                    "targetSdkVersion:'36'\r\n"
                    "application-debuggable\r\n"
                )
                self.assertEqual(parse_badging(text, package),
                                 dict(package=package, version_name=name, version_code=code, min_sdk=26, target_sdk=36))

    def test_a_populated_test_version_is_observed_not_inferred(self):
        self.assertEqual(parse_badging(report(TEST, "candidate β 4", "72"), TEST),
                         dict(package=TEST, version_name="candidate β 4", version_code=72, min_sdk=26, target_sdk=36))

    def test_duplicate_or_malformed_package_row_cannot_hide_behind_a_valid_row(self):
        text = report()
        package_row = text.split("\n", 1)[0]
        for extra in (package_row, report(TEST).split("\n", 1)[0], "package:", " package : name='other.app'"):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                parse_badging(text + extra + "\n", APP)

    def test_target_refuses_missing_unquoted_or_conflicting_evidence(self):
        text = report()
        for target in ("", "targetSdkVersion:36", "targetSdkVersion:'36' trailing",
                       "targetSdkVersion:'36'\ntargetSdkVersion:'35'",
                       "targetSdkVersion:'36'\ntargetSdkVersion:36"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                parse_badging(text.replace("targetSdkVersion:'36'", target), APP)

    def test_package_requires_unambiguous_quoted_version_fields(self):
        text = report()
        malformed_app = (
            text.replace("versionCode='71'", "versionCode='71' versionCode='71'"),
            text.replace("versionName='7.3-dev'", "versionName='other' versionName='7.3-dev'"),
            text.replace(f"name='{APP}'", f"name='{APP}' name='{TEST}'"),
            text.replace(" versionCode='71'", ""),
            text.replace("versionName='7.3-dev'", "versionName=7.3-dev"),
            text.replace("versionCode='71'", "versionCode='７１'"),
            text.replace("versionCode='71'", "versionCode='+71'"),
            text.replace("versionCode='71'", "versionCode='4294967296'"),
            report(APP, "", ""),
        )
        malformed_test = (
            report(TEST, "", "71"), report(TEST, "7.3-dev", ""),
            report(TEST, "", "").replace(" versionCode='' versionName=''", ""),
        )
        for package, variants in ((APP, malformed_app), (TEST, malformed_test),
                                  ("other.app.test", (report("other.app.test", "", ""),))):
            for malformed in variants:
                with self.subTest(package=package, malformed=malformed), self.assertRaises(ValueError):
                    parse_badging(malformed, package)

    def test_caller_sdk_limits_must_be_actual_ints(self):
        for minimum, target in ((26.0, 36), (26, 36.0), ("26", 36), (26, "36"), (True, 36), (26, False)):
            with self.subTest(minimum=minimum, target=target), self.assertRaises(TypeError):
                parse_badging(report(), APP, minimum, target)

    def test_caller_sdk_limits_must_be_positive_ordered_android_ints(self):
        for minimum, target in ((0, 36), (-1, 36), (26, 0), (36, 26), (26, 2**31)):
            with self.subTest(minimum=minimum, target=target), self.assertRaises(ValueError):
                parse_badging(report(), APP, minimum, target)

    def test_caller_sdk_values_are_not_hardcoded(self):
        text = report(minimum="minSdkVersion:'28'").replace("targetSdkVersion:'36'", "targetSdkVersion:'35'")
        self.assertEqual(parse_badging(text, APP, 28, 35),
                         dict(package=APP, version_name="7.3-dev", version_code=71, min_sdk=28, target_sdk=35))

    def test_caller_must_supply_text_and_a_package_string(self):
        for text, package in ((report().encode("utf-8"), APP), (report(), None)):
            with self.subTest(input_types=(type(text), type(package))), self.assertRaises(TypeError):
                parse_badging(text, package)
        with self.assertRaises(ValueError):
            parse_badging(report(package=""), "")

    def test_non_line_end_controls_cannot_hide_required_evidence(self):
        for separator in ("\r", "\x00\n", "\u2028"):
            with self.subTest(separator=separator), self.assertRaises(ValueError):
                parse_badging(report().replace("\n", separator), APP)

    def test_exact_required_labels_refuse_no_colon_duplicates_in_either_order(self):
        text = report()
        for label, malformed_rows in (
            ("package", (f"package name='{TEST}'", f"\tpackage\t= name='{TEST}'")),
            ("minSdkVersion", ("minSdkVersion '28'", "\tminSdkVersion\t='28'")),
            ("targetSdkVersion", ("targetSdkVersion '35'", "\ttargetSdkVersion='35'")),
        ):
            for row in malformed_rows:
                for order, duplicate in (("before", row + "\n" + text), ("after", text + row + "\n")):
                    with self.subTest(label=label, row=row, order=order), self.assertRaises(ValueError):
                        parse_badging(duplicate, APP)

    def test_longer_labels_and_unrelated_badging_rows_do_not_count_as_duplicates(self):
        text = (
            f"packageInstaller: name='{TEST}'\n"
            "minSdkVersionPreview:'28'\n"
            "targetSdkVersion_2:'35'\n"
            + report()
            + "uses-permission: name='android.permission.CAMERA'\n"
            "application-label:'PristinePage'\n"
            "  uses-feature-not-required: name='android.hardware.camera.any'\n"
        )
        self.assertEqual(parse_badging(text, APP),
                         dict(package=APP, version_name="7.3-dev", version_code=71, min_sdk=26, target_sdk=36))


if __name__ == "__main__":
    unittest.main()
