"""P06-PROVIDER-R: one read-only owned-target probe. Reuse Q APK/SDK evidence; no installs, scans, media writes, UI, permission changes or broad queries. Retain a bounded installed system APK for static inspection, never execute its code on the host."""

if not __debug__:
    raise RuntimeError("The operational evidence guards require assertions enabled")

from datetime import datetime, timezone
import json
import re
import time

from run_picker_adapter import ADB, ROOT, PickerAdapterRun, sha


class MediaProviderProbe(PickerAdapterRun):
    source_prefix = "picker-adapter-editor-comparison-current"
    prefix = "picker-adapter-p06-provider-contract"
    fixture = "/storage/emulated/0/Pictures/PristinePage-QA-981904ac-9b2b-4019-b6c1-fe95f44e7ebb/source-a.png"
    fixture_hash = "3c27e10c8f798d30402251941669802a65768b3e2415f0bb35b066bc1eecd555"

    def __init__(self):
        raw = ROOT / "validation/raw/decode"
        source = raw / (self.source_prefix + "-artifacts.json")
        assert sha(source) == "d922b549f6e0fb609ce4ce91d8b72dfa7fc0c123bcb49540aa57f38540dc7b2f"
        value = json.loads(source.read_text(encoding="utf-8"))
        # This new diagnostic is not retroactively part of the APK or old runtime manifest. Its exact bytes join a new probe manifest before allocation.
        value["sources"][str(__file__)] = sha(ROOT / "validation/decode/probe_media_provider.py")
        value["purpose"] = "Read-only P06 installed media-provider contract probe; no install or scan"
        destination = raw / (self.prefix + "-artifacts.json")
        with destination.open("x", encoding="utf-8") as output:
            json.dump(value, output, indent=2)
        super().__init__(self.prefix, {role: artifact["sha256"] for role, artifact in value["artifacts"].items()}, 0)

    def command(self, args, timeout=15, allowed=(0,)):
        result = super().command(args, timeout, allowed)
        # Add end timestamps separately without rewriting the inherited transcript or historical evidence.
        with self.path("command-end-times.jsonl").open("a", encoding="utf-8") as output:
            output.write(json.dumps({"sequence": self.sequence, "endedUtc": datetime.now(timezone.utc).isoformat()}) + "\n")
        return result

    def install_and_test(self):
        record = self.owned()
        row = self.exact(self.inventory())
        assert [int(row[5]), int(row[6])] == [identity[0] for identity in record["runtime_processes"]]
        for pid, start in record["runtime_processes"]:
            state = self.process(pid)
            assert state and state["running"] and state["start"] == start
        for role in ("app", "test"):
            assert self.installed_hash(self.input["artifacts"][role]["metadata"]["applicationId"]) == self.input["artifacts"][role]["sha256"]
        args = [ADB, "-s", self.serial]
        package = "com.android.providers.media"
        path_output = self.command(args + ["shell", "pm", "path", package]).strip()
        assert re.fullmatch(r"package:/system/(?:priv-app|app)/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\.apk", path_output), path_output
        remote = path_output.removeprefix("package:")
        size = int(self.command(args + ["shell", "stat", "-c", "%s", remote]).strip())
        assert 0 < size <= 32 * 1024**2
        remote_hash = self.command(args + ["shell", "sha256sum", remote]).split()[0]
        assert re.fullmatch(r"[a-f0-9]{64}", remote_hash)
        self.command(args + ["shell", "dumpsys", "package", package])
        self.command(args + ["shell", "dumpsys", "activity", "services", package])
        assert self.command(args + ["shell", "sha256sum", self.fixture]).split()[0] == self.fixture_hash
        self.command(args + ["shell", "ls", "-l", self.fixture])
        destination = self.path("installed-provider.apk")
        assert not destination.exists()
        self.command(args + ["pull", remote, destination], timeout=45)
        assert destination.stat().st_size == size and sha(destination) == remote_hash
        self.save(self.path("inspection.json"), {"generation": self.generation, "package": package, "remote": remote, "apk": self.evidence(destination), "fixture": self.fixture, "fixtureSha256": self.fixture_hash, "noInstall": True, "noScan": True, "noMediaMutation": True})
        print(json.dumps({"providerBytes": size, "providerSha256": remote_hash, "fixtureHashMatched": True, "noScan": True}), flush=True)


if __name__ == "__main__":
    start = time.monotonic()
    probe = MediaProviderProbe()
    try:
        probe.run()
    finally:
        print(json.dumps({"probeSeconds": time.monotonic() - start, "generation": probe.generation}), flush=True)
