"""Flag likely private material in PristinePage's outgoing Git history."""

import re
import subprocess
import sys


# These exact tips were verified on the public remote. A local tracking ref, a
# proposed push target, or a ref advertised by a different remote is not proof.
PUBLIC_TIPS = ("43a796c05b2c058fc7fa26784cae1d20c9ae849e", "2909e007c68580591bd03916e95cb45d1ff37ef9")
PUBLIC_EMAILS = {b"1118942+lrq3000@users.noreply.github.com"}
EMAIL = re.compile(rb"(?<![\w.+-])[\w.+-]+@(?:[\w-]+\.)+[a-z][a-z0-9-]*", re.I)
LOCAL_PATH = re.compile(rb"(?:[a-z]:[\\/]+(?:Users|Documents and Settings)[\\/]+|/(?:home|Users)/)[^\\/ \t\r\n`]+", re.I)
COMMIT_TOKEN = re.compile(rb"(?<![0-9a-f])[0-9a-f]{7,40}(?![0-9a-f])", re.I)
PRIVATE_PATH = re.compile(
    rb"(?:^|/)(?:\.worktrees|dist|validation/raw)(?:/|$)|"
    rb"(?:^|/)\.env(?:$|\.)|\.(?:apk|aab|jks|keystore|p12|pfx|key|kubeconfig)$", re.I,
)


def git(*args):
    return subprocess.run(
        ["git", "--no-replace-objects", *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout


class HistoryCheck:
    def __init__(self, tip):
        # First validate the tips and obtain the complete outgoing DAG, including
        # all second-parent commits of a merge.
        self.public = set(git("rev-list", *PUBLIC_TIPS, "--").splitlines())
        self.outgoing = git("rev-list", "--reverse", "--parents", tip,
                            *(f"^{oid}" for oid in PUBLIC_TIPS), "--").splitlines()
        self.allowed = self.public | {line.split()[0] for line in self.outgoing}
        self.checked_tokens = set()

    def inspect(self, value):
        if LOCAL_PATH.search(value):
            raise ValueError("a workstation home path needs review")
        if any(email.lower() not in PUBLIC_EMAILS for email in EMAIL.findall(value)):
            raise ValueError("an email address needs destination approval")
        for match in COMMIT_TOKEN.finditer(value):
            token = match[0].lower()
            if token in self.checked_tokens:
                continue
            for oid in git("rev-parse", f"--disambiguate={token.decode('ascii')}").splitlines():
                if oid not in self.allowed and git("cat-file", "-t", oid.decode("ascii")).strip() == b"commit":
                    raise ValueError("a reference resolves to a non-public, non-outgoing commit")
            self.checked_tokens.add(token)

    def check(self):
        for line in self.outgoing:
            oid, *parents = line.decode("ascii").split()
            self.inspect(git("show", "-s", "--format=%B", oid, "--"))
            if not parents:
                raise ValueError("outgoing history is not based on a known public history")
            parent = parents[0]
            # Do not follow renames: a new spelling and copied contents both
            # need checking. Compare each commit, not just the net branch diff.
            common = ("--no-ext-diff", "--no-textconv", "--no-renames", "--no-relative",
                      "--ignore-submodules=none", "--submodule=short", "--diff-filter=ACMT")
            for path in git("diff", *common, "--name-only", "-z", parent, oid, "--").split(b"\0"):
                if PRIVATE_PATH.search(path) or any(byte < 32 or byte == 127 for byte in path):
                    raise ValueError("an outgoing file path matches a private artifact class")
                self.inspect(path)
            in_hunk = False
            for row in git("diff", *common, "--no-color", "--text", "--unified=0",
                           "--output-indicator-new=+", parent, oid, "--").split(b"\n"):
                if row.startswith(b"diff --git "):
                    in_hunk = False
                elif row.startswith(b"@@ "):
                    in_hunk = True
                elif in_hunk and row.startswith(b"+"):
                    self.inspect(row[1:])


def main():
    try:
        HistoryCheck(sys.argv[1]).check()
    except ValueError as error:
        print(f"Push blocked by the public-history guard: {error}.", file=sys.stderr)
        return 1
    except (OSError, IndexError, subprocess.SubprocessError):
        print("Push blocked by the public-history guard: outgoing content could not be inspected.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
