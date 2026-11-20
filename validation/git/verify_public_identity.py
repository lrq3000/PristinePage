"""Exercise the installed outbound-history check without sending a push."""

import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


ROOT = Path(__file__).resolve().parents[2]
APPROVED_EMAIL = "1118942+lrq3000@users.noreply.github.com"
PUBLIC_MAIN = "40ad8fbbc4322118919a8d9430a673ebb12e2a8e"
PUBLIC_DESIGN = "2909e007c68580591bd03916e95cb45d1ff37ef9"


def git(*args):
    return subprocess.check_output(["git", "--no-replace-objects", *args], cwd=ROOT, text=True).strip()


def check_response(shell, hook, source, oid, target, error=None, env=None):
    result = subprocess.run(
        [shell, str(hook), "origin", "https://github.com/lrq3000/PristinePage.git"],
        cwd=ROOT, input=f"{source} {oid} {target} {'0' * 40}\n",
        text=True, capture_output=True, check=False, env=env,
    )
    expected = result.returncode == 0 if error is None else result.returncode == 1 and error in result.stderr
    if not expected:
        raise AssertionError(f"Unexpected hook result for {target}: exit {result.returncode}; {result.stderr.strip()}")


def check_blank_email(shell, hook, author, committer):
    # Put one synthetic commit in an isolated object directory; do not add it to the
    # shared repo, its reflogs or the push destination. The public parents stay readable.
    tree = git("rev-parse", f"{PUBLIC_MAIN}^{{tree}}")
    objects = git("rev-parse", "--path-format=absolute", "--git-path", "objects")
    with TemporaryDirectory(prefix="pristinepage-git-identity-") as scratch:
        env = dict(os.environ, GIT_OBJECT_DIRECTORY=scratch, GIT_ALTERNATE_OBJECT_DIRECTORIES=objects)
        raw = (f"tree {tree}\nparent {PUBLIC_MAIN}\nauthor Test <{author}> 1 +0000\n"
               f"committer Test <{committer}> 1 +0000\n\nSynthetic validation only\n")
        oid = subprocess.check_output(
            ["git", "hash-object", "-t", "commit", "--literally", "-w", "--stdin"],
            cwd=ROOT, env=env, input=raw.encode("ascii"),
        ).decode("ascii").strip()
        check_response(shell, hook, oid, oid, "refs/heads/never-publish", "non-approved author or committer", env)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shell", default="sh", help="Use Git for Windows' bin/bash.exe on Windows.")
    args = parser.parse_args()
    source = Path(__file__).with_name("public-pre-push")
    hook = Path(git("rev-parse", "--path-format=absolute", "--git-path", "hooks/pre-push"))
    config_keys = git("config", "--name-only", "--list").lower().splitlines()
    if source.read_bytes() != hook.read_bytes() or "core.hookspath" in config_keys:
        raise AssertionError("Installed guard does not match the reviewed default hook")
    if git("config", "--local", "--get", "user.email") != APPROVED_EMAIL:
        raise AssertionError("Wrong local commit identity")
    if git("config", "--local", "--get", "push.default") != "nothing":
        raise AssertionError("Pushes must name an explicit ref")

    for branch in git("for-each-ref", "--format=%(refname)", "refs/heads").splitlines():
        if not branch.startswith("refs/heads/archive/"):
            emails = git("log", "--format=%ae%n%ce", branch, f"^{PUBLIC_MAIN}", f"^{PUBLIC_DESIGN}", "--")
            if any(email != APPROVED_EMAIL for email in emails.splitlines()):
                raise AssertionError(f"Unexpected new Git identity on {branch}")
            check_response(args.shell, hook, branch, git("rev-parse", branch), branch)
            print(f"{branch}: active history passes")

    archive = "refs/heads/archive/feat/atelier-contract-local-original"
    original = git("rev-parse", archive)
    check_response(args.shell, hook, archive, original, "refs/heads/never-publish", "local archive")
    check_response(args.shell, hook, original, original, "refs/heads/never-publish", "non-approved author or committer")
    check_response(args.shell, hook, "refs/heads/main", PUBLIC_MAIN, "refs/tags/never-publish", "only branch updates")
    check_response(args.shell, hook, "(delete)", "0" * 40, "refs/heads/main", "ref deletion")
    for author, committer in (("", ""), ("", APPROVED_EMAIL), (APPROVED_EMAIL, "")):
        check_blank_email(args.shell, hook, author, committer)
    check_response(args.shell, hook, PUBLIC_MAIN, "x", "refs/heads/never-publish", "could not be inspected")
    print("Local archive, raw unsanitized history, blank identity, invalid ancestry, tag and deletion refusals verified.")


if __name__ == "__main__":
    main()
