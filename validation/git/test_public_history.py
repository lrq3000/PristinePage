"""Exercise the real pre-push hook against isolated Git objects; never push."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from verify_public_identity import APPROVED_EMAIL, ROOT


class PublicHistoryTest(unittest.TestCase):
    shell = "sh"

    @classmethod
    def setUpClass(cls):
        cls.base = cls.git("rev-parse", "43a796c").strip()
        objects = cls.git("rev-parse", "--path-format=absolute", "--git-path", "objects").strip()
        cls.scratch = tempfile.TemporaryDirectory(prefix="pristinepage-history-test-")
        cls.addClassCleanup(cls.scratch.cleanup)
        path = Path(cls.scratch.name)
        object_dir = path / "objects"
        object_dir.mkdir()
        cls.env = dict(os.environ, GIT_OBJECT_DIRECTORY=str(object_dir), GIT_ALTERNATE_OBJECT_DIRECTORIES=objects,
                       GIT_AUTHOR_NAME="Synthetic validation", GIT_COMMITTER_NAME="Synthetic validation",
                       GIT_AUTHOR_EMAIL=APPROVED_EMAIL, GIT_COMMITTER_EMAIL=APPROVED_EMAIL)
        cls.hook = path / "pre-push"
        shutil.copyfile(ROOT / "validation/git/public-pre-push", cls.hook)
        helper = ROOT / "validation/git/check_public_history.py"
        if helper.exists():
            shutil.copyfile(helper, path / helper.name)
        cls.tree = cls.git("rev-parse", f"{cls.base}^{{tree}}").strip()
        cls.private_commit = cls.commit(cls.base, cls.tree, "Separate local synthetic evidence")

    @classmethod
    def git(cls, *args, value=None):
        return subprocess.check_output(
            ["git", "--no-replace-objects", *args], cwd=ROOT,
            input=None if value is None else value.encode("utf-8"), env=getattr(cls, "env", None),
        ).decode("utf-8")

    @classmethod
    def commit(cls, parent, tree, message):
        return cls.git("commit-tree", tree, "-p", parent, "--no-gpg-sign", "-m", message).strip()

    def add_file(self, path, content):
        oid = self.git("hash-object", "-w", "--stdin", value=content).strip()
        entry_type = "100644 blob"
        for name in reversed(path.split("/")):
            oid = self.git("mktree", value=f"{entry_type} {oid}\t{name}\n").strip()
            entry_type = "040000 tree"
        return oid

    def push_check(self, tip):
        return subprocess.run(
            [self.shell, str(self.hook), "origin", "https://github.com/lrq3000/PristinePage.git"],
            cwd=ROOT, env=self.env, text=True, capture_output=True,
            input=f"refs/heads/test-publication {tip} refs/heads/main {self.base}\n",
        )

    def assert_blocked(self, tip, value, reason):
        result = self.push_check(tip)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(reason, result.stderr)
        self.assertNotIn(value, result.stderr + result.stdout)

    def test_rejects_resolvable_local_only_abbreviation_in_removed_content(self):
        reference = self.private_commit[:7]
        contaminated = self.commit(self.base, self.add_file("notes.md", f"See `{reference}`.\n"), "Add evidence")
        self.assert_blocked(self.commit(contaminated, self.tree, "Restore public tree"), reference, "non-public")

    def test_rejects_local_only_reference_in_commit_message(self):
        reference = self.private_commit.upper()
        self.assert_blocked(self.commit(self.base, self.tree, f"See {reference}."), reference, "non-public")

    def test_rejects_local_only_reference_in_path(self):
        reference = self.private_commit[:9]
        tip = self.commit(self.base, self.add_file(f"reference-{reference}.md", "synthetic"), "Add note")
        self.assert_blocked(tip, reference, "non-public")

    def test_rejects_local_path_in_a_commit_message(self):
        private_value = "Q:" + "\\Users\\specimen\\test-receipt.txt"
        self.assert_blocked(self.commit(self.base, self.tree, f"See {private_value}."), private_value, "workstation")

    def test_rejects_unapproved_email_in_added_content(self):
        private_value = "synthetic-person@" + "example.invalid"
        tip = self.commit(self.base, self.add_file("notes.md", private_value), "Add note")
        self.assert_blocked(tip, private_value, "address needs destination approval")

    def test_accepts_the_reviewed_kotlin_label_in_its_original_commit(self):
        result = self.push_check("51b972d7c69494f7be2b92448bedababebcda319")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_accepts_the_reviewed_portability_labels_in_their_exact_history(self):
        result = self.push_check("35e5519c1ca84ad95f8e46771bf108442311bbd0")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_does_not_generalize_the_kotlin_label_exception(self):
        label = "this@" + "SavedOcrApplicationTest.target"
        tip = self.commit(self.base, self.add_file("Fixture.kt", f'val contact = "{label}"\n'), "Synthetic fixture")
        self.assert_blocked(tip, label, "address needs destination approval")

    def test_refuses_the_same_added_line_in_another_commit(self):
        label = "this@" + "SavedOcrApplicationTest.target"
        file_name = "app/src/test/kotlin/app/pristinepage/ocr/SavedOcrApplicationTest.kt"
        tip = self.commit(self.base, self.add_file(file_name, f"                assertEquals({label}, target)\n"), "Synthetic fixture")
        self.assert_blocked(tip, label, "address needs destination approval")

    def test_rejects_private_evidence_path(self):
        private_value = "output.keystore"
        tip = self.commit(self.base, self.add_file(private_value, "synthetic"), "Add file")
        self.assert_blocked(tip, private_value, "private artifact class")

    def test_rejects_a_gitlink_path_even_when_local_configuration_ignores_submodules(self):
        private_value = f"reference-{self.private_commit[:7]}"
        tree = self.git("mktree", value=f"160000 commit {self.base}\t{private_value}\n").strip()
        tip = self.commit(self.base, tree, "Add synthetic gitlink")
        self.env = dict(self.env, GIT_CONFIG_COUNT="1", GIT_CONFIG_KEY_0="diff.ignoreSubmodules",
                        GIT_CONFIG_VALUE_0="all")
        self.assert_blocked(tip, private_value, "non-public")

    def test_allows_public_and_outgoing_references(self):
        valid = self.commit(self.base, self.tree, f"Published baseline: {self.base[:7]}")
        tip = self.commit(valid, self.add_file("notes.md", f"See {valid}.\n"), f"See {valid[:8]}.")
        result = self.push_check(tip)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--shell", default="sh")
    args, rest = parser.parse_known_args()
    PublicHistoryTest.shell = args.shell
    unittest.main(argv=[__file__, *rest])
