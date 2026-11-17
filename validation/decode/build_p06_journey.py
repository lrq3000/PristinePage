"""Bounded test-APK-only build and SDK provenance for the real P06 journey. No emulator activity, dependency acquisition, clean/forced tasks or main/release signing."""

if not __debug__:
    raise RuntimeError("The operational evidence guards require assertions enabled")

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from run_picker_adapter import ROOT, SIGNER, sha


def now():
    return datetime.now(timezone.utc).isoformat()


def build(prefix):
    assert Path.cwd().resolve() == ROOT.resolve()
    assert re.fullmatch(r"p06-real-journey-[a-z0-9-]+", prefix)
    raw = ROOT / "validation/raw/decode"
    prior_build = json.loads((ROOT / "validation/raw/editor/editor-comparison-integration-command.json").read_text())
    prior_sdk_path = ROOT / "validation/raw/editor/editor-comparison-current-sdk-commands.json"
    prior_sdk = json.loads(prior_sdk_path.read_text())
    prior = json.loads((raw / "picker-adapter-editor-comparison-current-artifacts.json").read_text())
    assert shutil.disk_usage(ROOT).free >= 8 * 1024**3
    environment = os.environ.copy()
    for key, value in prior_build["environment"].items():
        if value is None:
            environment.pop(key, None)
        else:
            environment[key] = value
    assert list((Path(environment["GRADLE_USER_HOME"]) / "wrapper/dists").glob("gradle-8.13-bin/*/gradle-8.13/bin/gradle.bat"))
    inputs = {path: sha(ROOT / path) for path in prior_build["inputs"]}
    for module in ("core", "test-fixtures", "data", "image-android", "app"):
        for path in (ROOT / module / "src").rglob("*"):
            if path.is_file():
                inputs[str(path.relative_to(ROOT))] = sha(path)
    argv = prior_build["argv"][:10] + [":app:assembleDebugAndroidTest"]
    record = {"startedUtc": now(), "argv": argv, "environment": prior_build["environment"], "inputs": inputs}
    log = raw / (prefix + "-build.log")
    start = time.monotonic()
    with log.open("xb") as stream:
        outcome = subprocess.run(argv, env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=240)
    record.update(endedUtc=now(), seconds=time.monotonic()-start, exit=outcome.returncode, logSha256=sha(log), inputsUnchanged=all(sha(ROOT / path) == digest for path, digest in inputs.items()))
    build_record = raw / (prefix + "-build-command.json")
    with build_record.open("x", encoding="utf-8") as stream:
        json.dump(record, stream, indent=2)
    print("\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-10:]), flush=True)
    assert outcome.returncode == 0 and record["inputsUnchanged"], "No SDK/device step follows a failed or moving build"
    assert sha(Path(prior["artifacts"]["app"]["apk"])) == prior["artifacts"]["app"]["sha256"], "Main APK changed; existing main proofs cannot be reused"
    commands, artifacts = [], {}
    sdk_environment = environment.copy()
    sdk_environment["PATH"] = environment["JAVA_HOME"] + "\\bin;" + environment["PATH"]
    for old in prior_sdk["commands"]:
        if old["role"] == "app":
            assert old["exit"] == 0
            for stream in ("stdout", "stderr"):
                assert sha(ROOT / old[stream]["path"]) == old[stream]["sha256"]
            commands.append(old)
            continue
        entry = {"role": "test", "kind": old["kind"], "argv": old["argv"], "startedUtc": now()}
        result = subprocess.run(old["argv"], env=sdk_environment, capture_output=True, timeout=60)
        entry.update(endedUtc=now(), exit=result.returncode)
        for key, payload in (("stdout", result.stdout), ("stderr", result.stderr)):
            path = raw / (prefix + "-sdk-test-" + old["kind"] + "." + key)
            with path.open("xb") as stream:
                stream.write(payload)
            entry[key] = {"path": str(path.relative_to(ROOT)), "bytes": len(payload), "sha256": sha(path)}
        commands.append(entry)
        assert result.returncode == 0
    signer = next(entry for entry in commands if entry["role"] == "test" and entry["kind"] == "signer")
    assert "Signer #1 certificate SHA-256 digest: " + SIGNER in (ROOT / signer["stdout"]["path"]).read_text()
    for role, prior_artifact in prior["artifacts"].items():
        apk = Path(prior_artifact["apk"])
        metadata = json.loads((apk.parent / "output-metadata.json").read_text())
        assert metadata["applicationId"] == "app.pristinepage.debug" + (".test" if role == "test" else "")
        digest = sha(apk)
        if role == "app":
            retained = Path(prior_artifact["retained"])
            assert sha(retained) == digest
        else:
            retained = raw / (prefix + "-test-" + digest + ".apk")
            with retained.open("xb") as stream:
                stream.write(apk.read_bytes())
        artifacts[role] = {"apk": str(apk), "retained": str(retained), "sha256": digest, "bytes": apk.stat().st_size, "metadata": metadata, "certificateSha256": SIGNER}
    proof = raw / (prefix + "-sdk-commands.json")
    with proof.open("x", encoding="utf-8") as stream:
        json.dump({"commands": commands, "newSdkCommands": 4, "reusedUnchangedMainCommands": 4, "priorProofSha256": sha(prior_sdk_path), "buildCommandSha256": sha(build_record)}, stream, indent=2)
    sources = {path: sha(ROOT / path) for path in prior["sources"]}
    sources.update(inputs)
    for path in (ROOT / "validation/decode").glob("*.py"):
        sources[str(path.relative_to(ROOT))] = sha(path)
    manifest = raw / ("picker-adapter-" + prefix + "-artifacts.json")
    with manifest.open("x", encoding="utf-8") as stream:
        json.dump({"artifacts": artifacts, "sources": sources, "buildCommandSha256": sha(build_record), "sdkProvenance": {"path": str(proof.relative_to(ROOT)), "sha256": sha(proof)}}, stream, indent=2)
    print(json.dumps({"buildExit": 0, "seconds": record["seconds"], "mainSha256": artifacts["app"]["sha256"], "testSha256": artifacts["test"]["sha256"], "sourceInputs": len(sources), "manifestSha256": sha(manifest), "freshSdkCommands": 4, "reusedMainProofs": 4}), flush=True)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", required=True)
    build(parser.parse_args().prefix)
