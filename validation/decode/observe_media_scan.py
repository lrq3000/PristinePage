"""One P06 exact-retained-file reproduction with a correct process/log witness. No media enumeration, new fixture, install, UI, permission, settings or repair; no second scan on any outcome."""

if not __debug__:
    raise RuntimeError("The operational evidence guards require assertions enabled")

import json
import argparse
import re
import shlex
import time

from probe_media_provider import MediaProviderProbe
from run_picker_adapter import ADB, ROOT, sha


class ObservedMediaScan(MediaProviderProbe):
    prefix = "picker-adapter-p06-observed-scan"

    def __init__(self, prefix=None):
        if prefix is not None:
            assert re.fullmatch(r"picker-adapter-p06-observed-scan-[a-z0-9-]+", prefix)
            self.prefix = prefix
        super().__init__()
        self.input["sources"]["validation/decode/observe_media_scan.py"] = sha(ROOT / "validation/decode/observe_media_scan.py")
        self.input["purpose"] = "One approved exact-existing-file scan with actual android.process.media PID/tag witness; no broader repair"
        self.save(self.path("artifacts.json"), self.input)

    def exact_rows(self, collection):
        assert collection in ("images/media", "file")
        # ADB concatenates shell arguments. Quote the complete SQL predicate for the remote shell, retaining the SQL string quotes instead of accidentally sending an unquoted path to SQLite.
        where = shlex.quote("_data='" + self.fixture + "'")
        projection = "_id:_data:mime_type:width:height" if collection == "images/media" else "_id:_data:media_type:mime_type:width:height"
        return self.command([ADB, "-s", self.serial, "shell", "content", "query", "--uri", "content://media/external/" + collection, "--projection", projection, "--where", where])

    def install_and_test(self):
        record = self.owned()
        row = self.exact(self.inventory())
        assert [int(row[5]), int(row[6])] == [identity[0] for identity in record["runtime_processes"]]
        for pid, start in record["runtime_processes"]:
            state = self.process(pid)
            assert state and state["running"] and state["start"] == start
        for role, artifact in self.input["artifacts"].items():
            assert self.installed_hash(artifact["metadata"]["applicationId"]) == artifact["sha256"]
        args = [ADB, "-s", self.serial]
        assert self.command(args + ["shell", "sha256sum", self.fixture]).split()[0] == self.fixture_hash
        before = {kind: self.exact_rows(kind) for kind in ("images/media", "file")}
        # The exact query starts the provider if needed. Its manifest explicitly names this process, not the package name used by the historical unsuccessful PID probe.
        pid = self.command(args + ["shell", "pidof", "android.process.media"]).strip()
        assert re.fullmatch(r"[1-9][0-9]*", pid), pid
        baseline = self.command(args + ["logcat", "-d", "-v", "threadtime", "--pid", pid, "-t", "80", "MediaScannerReceiver:D", "MediaScannerService:D", "*:S"])
        if any("Row:" in value for value in before.values()):
            self.save(self.path(getattr(self, "observation_name", "observation.json")), {"before": before, "pid": pid, "scanCount": 0, "reason": "Exact retained row already exists; do not scan again"})
            return
        assert all(value.strip() == "No result found." for value in before.values()), before
        self.command(args + ["shell", "am", "broadcast", "-n", "com.android.providers.media/.MediaScannerReceiver", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + self.fixture], timeout=30)
        after = []
        deadline = time.monotonic() + 6
        while True:
            rows = {kind: self.exact_rows(kind) for kind in ("images/media", "file")}
            after.append(rows)
            if "Row:" in rows["images/media"] or time.monotonic() >= deadline:
                break
            assert all(value.strip() == "No result found." or "Row:" in value for value in rows.values()), rows
            # Condition-based bounded registration polling, not repeated scan requests.
            time.sleep(.5)
        logs = self.command(args + ["logcat", "-d", "-v", "threadtime", "--pid", pid, "-t", "80", "MediaScannerReceiver:D", "MediaScannerService:D", "*:S"])
        self.save(self.path(getattr(self, "observation_name", "observation.json")), {"before": before, "pid": pid, "scanCount": 1, "after": after, "newLogLines": [line for line in logs.splitlines() if line not in baseline.splitlines()], "registeredImage": "Row:" in rows["images/media"], "limitations": "One exact-file reproduction; log silence does not establish non-delivery or a cause"})
        print(json.dumps({"scanCount": 1, "registeredImage": "Row:" in rows["images/media"], "newLogLines": [line for line in logs.splitlines() if line not in baseline.splitlines()]}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", required=True)
    ObservedMediaScan(parser.parse_args().prefix).run()
