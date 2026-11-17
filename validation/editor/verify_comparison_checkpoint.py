"""Read-only authentication of the retained Q checkpoint; never builds, claims or changes a device. Run from the primary worktree before committing its source or consuming its evidence after resumption."""

import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "validation/raw/editor"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def verify():
    report = ROOT / "validation/reviews/t09-editor-comparison-review.md"
    assert sha(report) == "878f442c6b3d9ef8d86f912633b77f0693144855192e8b7ea84dafacdd578be0"
    pins = re.findall(r"^- `(app/[^`]+)` — `([a-f0-9]{64})`", report.read_text(encoding="utf-8"), re.M)
    assert len(pins) == 7
    for path, digest in pins:
        assert sha(ROOT / path) == digest, path
    build = load(RAW / "editor-comparison-integration-command.json")
    assert build["exit"] == 0 and build["inputsUnchanged"]
    assert sum(int(item["tests"]) for item in build["reports"]) == 874
    assert all(int(item["failures"]) == int(item["errors"]) == int(item.get("skipped", 0)) == 0 for item in build["reports"])
    for path, digest in build["inputs"].items():
        assert sha(ROOT / path) == digest, path
    for entry in build["outputs"]:
        assert sha(ROOT / entry["path"]) == entry["sha256"], entry["path"]
    state = load(RAW / "editor-comparison-state-green-command.json")
    assert state["exit"] == 0 and sum(int(item["tests"]) for item in state["reports"]) == 99
    for entry in state["outputs"]:
        assert sha(ROOT / entry["path"]) == entry["sha256"]
    base = ROOT / "validation/raw/decode"
    prefix = "picker-adapter-editor-comparison-current"
    anchor = base / (prefix + "-final-manifest.json")
    assert sha(anchor) == "ba2e05a36ca0726b7706b94d53ab02fdeb9d7dac50926f4bc29610b1f06bd788"
    manifest = load(anchor)
    for entry in manifest["evidence"] + [manifest["runner"]]:
        assert sha(ROOT / entry["path"]) == entry["sha256"]
    artifacts = load(base / (prefix + "-artifacts.json"))
    assert len(artifacts["sources"]) == 332
    for path, digest in artifacts["sources"].items():
        assert sha(ROOT / path) == digest, path
    for artifact in artifacts["artifacts"].values():
        assert sha(Path(artifact["apk"])) == sha(Path(artifact["retained"])) == artifact["sha256"]
    sdk = artifacts["sdkProvenance"]
    assert sha(ROOT / sdk["path"]) == sdk["sha256"]
    commands = load(ROOT / sdk["path"])["commands"]
    assert len(commands) == 8
    for command in commands:
        assert command["exit"] == 0
        for stream in ("stdout", "stderr"):
            assert sha(ROOT / command[stream]["path"]) == command[stream]["sha256"]
    outcomes = load(base / (prefix + "-outcomes.json"))["outcomes"]
    assert sum(entry["count"] for entry in outcomes["0"]) == 2 and not outcomes["-1"] and not outcomes["-2"]
    shutdown = load(base / (prefix + "-shutdown.json"))
    assert shutdown["ownedShutdownVerified"] and shutdown["usageReleased"] and not shutdown["remainingRunning"]
    assert all(not process["running"] and process["wait"] == 0 and process["exit"] == 0 for process in shutdown["after"])
    assert sha(RAW / "editor-comparison-parent-verification.json") == "c7533fb991d41d6230a67ee04d6a557f55e734aef30f61bd59e474f0a577ee40"
    assert sha(ROOT / "docs/specs/IMPLEMENTATION_PLAN.md") == "791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa"
    coordinator = Path.home() / "AppData/Local/agent-emulators/ldplayer"
    current = load(coordinator / "LOCAL_TEST_INSTANCE.json")
    assert current["generation"] == manifest["generation"] and current["owner"] is None and current["state"] == "stopped-released"
    assert not (coordinator / "allocation.lock").exists()
    subprocess.run(["git", "diff", "--check"], cwd=ROOT, check=True)
    print("Authenticated: seven Q review identities; 332 inputs; 874 cached integration reports and preceding 99 executed app cases; eight SDK commands; two actual native passes; original process exits and released generation. No build/device replay.")


if __name__ == "__main__":
    if not __debug__:
        raise RuntimeError("Evidence authentication requires assertions enabled")
    verify()
