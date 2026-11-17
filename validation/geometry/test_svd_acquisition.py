"""Memory-only acquisition guards: no Gradle, network, artifact loading or file writes."""

import unittest


class SvdAcquisitionTest(unittest.TestCase):
    def validator(self):
        try:
            from svd_acquisition_guard import validate_command
        except ImportError:
            self.fail("Strict SVD acquisition command guard is absent")
        return validate_command

    def test_verification_writer_is_refused_even_when_strict_is_also_requested(self):
        for writer in ("-M", "--write-verification-metadata", "--write-verification-metadata=sha256"):
            with self.assertRaises(ValueError):
                self.validator()(["gradlew.bat", "--dependency-verification=strict", writer, "sha256", ":core:test"])

    def test_writer_dependencies_report_is_not_accepted_as_safe_metadata_only(self):
        with self.assertRaises(ValueError):
            self.validator()(["gradlew.bat", "--write-verification-metadata", "sha256", ":core:dependencies"])

    def test_lenient_or_off_verification_cannot_override_a_strict_flag(self):
        for option in ("--dependency-verification=off", "--dependency-verification=lenient"):
            with self.assertRaises(ValueError):
                self.validator()(["gradlew.bat", "--dependency-verification=strict", option, ":core:test"])

    def test_missing_strict_verification_refuses_before_any_execution(self):
        with self.assertRaises(ValueError):
            self.validator()(["gradlew.bat", "--write-locks", ":core:dependencies"])

    def test_strict_nonconsuming_resolution_and_lock_updates_are_allowed(self):
        self.validator()(["gradlew.bat", "--dependency-verification=strict", "--write-locks", "-I", "validation/geometry/resolve_svd_artifacts.init.gradle.kts", ":core:resolveApprovedSvd"])

    def test_separate_and_short_verification_options_cannot_bypass_the_guard(self):
        for flag in ("-F", "--dependency-verification"):
            self.validator()(["gradlew.bat", flag, "strict", ":core:resolveApprovedSvd"])
            for mode in ("off", "lenient", None):
                command = ["gradlew.bat", "--dependency-verification=strict", flag]
                if mode is not None:
                    command.append(mode)
                with self.assertRaises(ValueError):
                    self.validator()(command)


if __name__ == "__main__":
    unittest.main()
