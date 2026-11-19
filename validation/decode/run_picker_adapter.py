"""Exact P-04 paired-APK adapter test runner; no UI activity, grants, reset, server repair or deletion. Reuses the existing allocator and retains all output. Run only from the authorized worktree with SDK-verified <prefix>-artifacts.json. This is a coordinator validation harness, not application code or a host-wide emulator manager."""

# Python assertions are used for fail-closed operational invariants below. This explicit exception survives -O/PYTHONOPTIMIZE and runs before imports, records, paths, claims or subprocess work.
if not __debug__:
    raise RuntimeError("Optimized Python execution is not authorized for this operational runner")

import argparse
from collections import Counter
import ctypes
from ctypes import wintypes as wt
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import uuid


ROOT = Path(r"X:\local-workspace\.worktrees\paperlight-v1")
COORD = Path(os.environ["LOCALAPPDATA"]) / "agent-emulators/ldplayer"
NAME = "LOCAL_TEST_INSTANCE"
OWNER = "LOCAL_SESSION"
CONSOLE = Path(r"C:\LDPlayer\LDPlayer9\ldconsole.exe")
ADB = CONSOLE.with_name("adb.exe")
TEST_CLASS = "app.pristinepage.PhotoPickerContractTest"
SIGNER = "7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee"


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PickerAdapterRun:
    def __init__(self, prefix, previous, missing):
        assert Path.cwd().resolve() == ROOT.resolve()
        assert re.fullmatch(r"picker-adapter-[a-z0-9-]+", prefix)
        self.raw = ROOT / "validation/raw/decode"
        self.prefix = prefix
        self.generation = str(uuid.uuid4())
        self.record = COORD / (NAME + ".json")
        self.slot = COORD / "allocation-owner.json"
        self.lock = COORD / "allocation.lock"
        self.transcript = self.path("commands.jsonl")
        assert not self.transcript.exists()
        self.input = json.loads(self.path("artifacts.json").read_text())
        self.previous = previous
        self.missing = missing
        self.claimed = False
        self.sequence = 0
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.OpenProcess.argtypes = [wt.DWORD, wt.BOOL, wt.DWORD]
        self.kernel.OpenProcess.restype = wt.HANDLE
        self.kernel.CloseHandle.argtypes = [wt.HANDLE]
        self.kernel.WaitForSingleObject.argtypes = [wt.HANDLE, wt.DWORD]
        self.kernel.WaitForSingleObject.restype = wt.DWORD
        self.kernel.GetExitCodeProcess.argtypes = [wt.HANDLE, ctypes.POINTER(wt.DWORD)]
        self.kernel.GetProcessTimes.argtypes = [wt.HANDLE] + [ctypes.POINTER(wt.FILETIME)] * 4

    def path(self, suffix):
        return self.raw / (self.prefix + "-" + suffix)

    def save(self, path, value, *, durable=False):
        temporary = path.with_name(path.name + "." + self.generation + ".tmp")
        text = json.dumps(value, indent=2) + "\n"
        if durable:
            # Shutdown evidence must reach the filesystem before the shared records advertise release. Flush the complete file, then request a same-volume write-through replacement on Windows.
            with temporary.open("w", encoding="utf-8") as stream:
                stream.write(text)
                stream.flush()
                os.fsync(stream.fileno())
            replace = ctypes.WinDLL("kernel32", use_last_error=True).MoveFileExW
            replace.argtypes = [wt.LPCWSTR, wt.LPCWSTR, wt.DWORD]
            replace.restype = wt.BOOL
            if not replace(str(temporary), str(path), 0x1 | 0x8):  # REPLACE_EXISTING | WRITE_THROUGH
                raise ctypes.WinError(ctypes.get_last_error())
            return
        temporary.write_text(text, encoding="utf-8")
        os.replace(temporary, path)

    def evidence(self, path):
        return {"path": str(path.relative_to(ROOT)), "bytes": path.stat().st_size, "sha256": sha(path)}

    def command(self, args, timeout=15, allowed=(0,)):
        self.sequence += 1
        stem = self.path(f"cmd{self.sequence:03d}")
        out, err = stem.with_suffix(".stdout"), stem.with_suffix(".stderr")
        assert not out.exists() and not err.exists()
        args = list(map(str, args))
        entry = {"started": now(), "argv": args}
        try:
            # Files, not capture_output pipes: daemon-spawning console commands must not retain reader threads after their direct child exits.
            with out.open("wb") as stdout, err.open("wb") as stderr:
                result = subprocess.run(args, stdout=stdout, stderr=stderr, timeout=timeout)
            entry.update(exit=result.returncode, stdout=out.read_text(encoding="utf-8", errors="replace"), stderr=err.read_text(encoding="utf-8", errors="replace"))
        except subprocess.TimeoutExpired:
            entry["timeout"] = timeout
            raise
        finally:
            with self.transcript.open("a", encoding="utf-8") as log:
                log.write(json.dumps(entry) + "\n")
        assert result.returncode in allowed, entry
        return entry["stdout"]

    def process(self, pid):
        k = self.kernel
        handle = k.OpenProcess(0x1000 | 0x100000, False, pid)
        if not handle:
            error = ctypes.get_last_error()
            if error == 87:
                return None
            raise OSError(error, "Cannot inspect owned process")
        try:
            created, exited, kernel, user = (wt.FILETIME() for _ in range(4))
            code = wt.DWORD()
            assert k.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited), ctypes.byref(kernel), ctypes.byref(user))
            assert k.GetExitCodeProcess(handle, ctypes.byref(code))
            wait = k.WaitForSingleObject(handle, 0)
            assert wait in (0, 258)
            return {"pid": pid, "start": (created.dwHighDateTime << 32) | created.dwLowDateTime, "running": wait == 258, "wait": wait, "exit": code.value}
        finally:
            k.CloseHandle(handle)

    def inventory(self):
        rows = [line.split(",") for line in self.command([CONSOLE, "list2"]).splitlines() if line.strip()]
        assert all(len(row) >= 7 for row in rows)
        return rows

    def exact(self, rows):
        matches = [row for row in rows if row[1] == NAME]
        assert len(matches) == 1 and matches[0][0] == "2"
        return matches[0]

    def owned(self):
        value = json.loads(self.record.read_text())
        assert value["owner"] == OWNER and value["generation"] == self.generation and not value["keep_running"] and not value["handoff_to"]
        return value

    def protocol(self, allow_absent=False):
        try:
            connection = socket.create_connection(("127.0.0.1", 5037), timeout=3)
        except ConnectionRefusedError:
            if allow_absent:
                return "absent"
            raise
        with connection:
            payload = b"host:version"
            connection.sendall(f"{len(payload):04x}".encode() + payload)
            assert connection.recv(4) == b"OKAY"
            length = int(connection.recv(4), 16)
            assert int(connection.recv(length), 16) == 41
        return 41

    def claim(self):
        self.lock.mkdir()
        try:
            assert json.loads(self.slot.read_text())["state"] == "released"
            record = json.loads(self.record.read_text())
            assert record["owner"] is None and record["state"] == "stopped-released" and record["shutdown_verified"] and not record["keep_running"] and not record["handoff_to"]
            assert record["installation"] == str(CONSOLE.parent) and record["name"] == NAME and record["index"] == 2
            row = self.exact(self.inventory())
            assert int(row[4]) == 0 and int(row[5]) <= 0 and int(row[6]) <= 0
            for pid, start in record["shutdown_processes"]:
                state = self.process(pid)
                assert state is None or state["start"] != start or not state["running"]
            for path in COORD.glob("*.json"):
                value = json.loads(path.read_text())
                assert not value.get("reservedKiB", 0) and value.get("state") not in ("launching", "shutdown_failed", "launch_failed"), path
            free = shutil.disk_usage(ROOT).free
            available = int(self.command(["pwsh", "-NoProfile", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"]).strip())
            assert free >= 8 * 1024**3 and available >= 4194304
            assert "1.0.41" in self.command([ADB, "version"])
            initial = self.protocol(True)
            if initial == "absent":
                assert not self.command(["pwsh", "-NoProfile", "-Command", 'Get-CimInstance Win32_Process -Filter "Name=\'adb.exe\'" | Select-Object -ExpandProperty ProcessId']).strip()
            record.setdefault("ownership_history", []).append({key: record.get(key) for key in ("owner", "generation", "worktree", "state", "stopped", "serial", "shutdown_processes", "cleanup_attempt")})
            record.update(owner=OWNER, generation=self.generation, claim_generation=self.generation, worktree=str(ROOT), state="launching", claimed=now(), shutdown_ready=False, evidence_saved=False, shutdown_verified=False, availableKiB=available, reservedKiB=4194304, serial=None, runtime_processes=[], shutdown_processes=[], cleanup_attempt=None)
            self.save(self.record, record)
            self.claimed = True
            self.save(self.slot, {"owner": OWNER, "generation": self.generation, "state": "held", "acquired": now(), "operation": "P04 paired APK picker adapter tests"})
            self.command([CONSOLE, "launch", "--index", "2"], 20)
            deadline = time.monotonic() + 180
            serial = None
            while time.monotonic() < deadline:
                row = self.exact(self.inventory())
                if int(row[5]) > 0 and int(row[6]) > 0:
                    states = [self.process(int(row[5])), self.process(int(row[6]))]
                    assert all(state and state["running"] for state in states)
                    record["runtime_processes"] = [[state["pid"], state["start"]] for state in states]
                    self.save(self.record, record)
                    if serial is None:
                        lines = [line.strip() for line in self.command([CONSOLE, "adb", "--index", "2", "--command", "get-serialno"]).splitlines() if line.strip()]
                        serial = lines[0] if len(lines) == 1 and lines[0].startswith("emulator-") else None
                    if serial:
                        assert self.protocol() == 41
                        state = self.command([ADB, "-s", serial, "get-state"], allowed=(0, 1)).strip()
                        boot = self.command([ADB, "-s", serial, "shell", "getprop", "sys.boot_completed"], allowed=(0, 1)).strip()
                        package = self.command([ADB, "-s", serial, "shell", "pm", "path", "android"], allowed=(0, 1)).strip()
                        if state == "device" and boot == "1" and package.startswith("package:"):
                            break
                time.sleep(2)
            else:
                raise RuntimeError("Owned readiness deadline exceeded")
            self.serial = serial
            api = int(self.command([ADB, "-s", serial, "shell", "getprop", "ro.build.version.sdk"]).strip())
            abis = self.command([ADB, "-s", serial, "shell", "getprop", "ro.product.cpu.abilist"]).strip()
            record.update(state="running", serial=serial, booted=now(), api=api, supported_abis=abis.split(","), reservedKiB=0)
            self.save(self.record, record)
            self.save(self.path("claim.json"), {"generation": self.generation, "serial": serial, "api": api, "processes": record["runtime_processes"], "availableKiB": available, "freeDiskBytes": free, "initialAdbProtocol": initial})
            self.save(self.slot, {"owner": OWNER, "generation": self.generation, "state": "released", "released": now()})
        finally:
            self.lock.rmdir()

    def installed_hash(self, package):
        paths = self.command([ADB, "-s", self.serial, "shell", "pm", "path", package]).splitlines()
        assert len(paths) == 1 and re.fullmatch(r"package:/data/app/[^\s]+/base.apk", paths[0])
        return self.command([ADB, "-s", self.serial, "shell", "sha256sum", paths[0][8:]]).split()[0]

    def install_pair(self):
        """Verify the owned runtime and exact installed pair without starting instrumentation. P-06 reuses these guards for its real-picker journey rather than replaying unrelated suites."""
        record = self.owned()
        row = self.exact(self.inventory())
        assert [int(row[5]), int(row[6])] == [identity[0] for identity in record["runtime_processes"]]
        for pid, start in record["runtime_processes"]:
            state = self.process(pid)
            assert state and state["start"] == start and state["running"]
        installs = {}
        for role in ("app", "test"):
            artifact = self.input["artifacts"][role]
            package = artifact["metadata"]["applicationId"]
            before = self.installed_hash(package)
            assert before in (self.previous[role], artifact["sha256"]), (package, before)
            if before != artifact["sha256"]:
                assert "Success" in self.command([ADB, "-s", self.serial, "install", "-r", artifact["retained"]], 120)
            assert self.installed_hash(package) == artifact["sha256"]
            dump = self.command([ADB, "-s", self.serial, "shell", "dumpsys", "package", package])
            self.path(role + "-package.txt").write_text(dump, encoding="utf-8")
            assert re.search(r"versionCode=" + str(artifact["metadata"]["elements"][0]["versionCode"]) + r"\b", dump)
            installs[role] = {"priorSha256": before, "installedSha256": artifact["sha256"], "metadata": artifact["metadata"]}
        # The optional pm-list filter is TARGET package, not instrumentation APK package. Library tests self-targeted, while this app test targets the separate main APK.
        registry = self.command([ADB, "-s", self.serial, "shell", "pm", "list", "instrumentation", "app.pristinepage.debug"])
        self.path("registry.txt").write_text(registry, encoding="utf-8")
        assert "instrumentation:app.pristinepage.debug.test/androidx.test.runner.AndroidJUnitRunner (target=app.pristinepage.debug)" in registry
        self.save(self.path("install.json"), installs)

    def install_and_test(self):
        self.install_pair()
        output = self.command([ADB, "-s", self.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS, "app.pristinepage.debug.test/androidx.test.runner.AndroidJUnitRunner"], 180)
        self.path("instrumentation.txt").write_text(output, encoding="utf-8")
        expected = Counter((TEST_CLASS, name) for name in re.findall(r"@Test\s+fun\s+(\w+)", (ROOT / "app/src/androidTest/kotlin/app/pristinepage/PhotoPickerContractTest.kt").read_text()))
        outcomes = {code: Counter() for code in ("1", "0", "-2", "-1")}
        missing_assertions = 0
        for block, code in re.findall(r"(.*?)(?:^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$)", output, re.M | re.S):
            cls = re.search(r"^INSTRUMENTATION_STATUS: class=(.+)$", block, re.M)
            test = re.search(r"^INSTRUMENTATION_STATUS: test=(.+)$", block, re.M)
            assert cls and test and code in outcomes
            outcomes[code][(cls.group(1).strip(), test.group(1).strip())] += 1
            if code == "-2" and "Bounded Android photo-picker contract is absent" in block:
                missing_assertions += 1
        assert outcomes["1"] == expected and outcomes["0"] + outcomes["-2"] + outcomes["-1"] == expected
        assert not outcomes["-1"] and sum(outcomes["-2"].values()) == self.missing
        if self.missing:
            assert missing_assertions == self.missing
        else:
            assert f"OK ({len(expected)} tests)" in output
        self.save(self.path("outcomes.json"), {"expectedMissingAssertions": self.missing, "outcomes": {code: [{"class": cls, "test": test, "count": count} for (cls, test), count in items.items()] for code, items in outcomes.items()}})
        print(json.dumps({"started": sum(expected.values()), "passed": sum(outcomes["0"].values()), "intendedMissing": self.missing}), flush=True)

    def shutdown(self):
        self.lock.mkdir()
        unlock = True
        record_versions, slot_versions = [], []
        attempt_token = None
        verified_anchor = None
        try:
            slot = json.loads(self.slot.read_text())
            assert slot["state"] == "released" or (
                slot["state"] == "held" and slot.get("owner") == OWNER and slot.get("generation") == self.generation
            ), "Shared allocation slot is not released or held by this exact generation"
            record = self.owned()
            record_versions.append(record)
            slot_versions.append(slot)
            identities = record["runtime_processes"]
            assert len(identities) == 2, "Persist partial-launch obligation; do not invent original process identities"
            row = self.exact(self.inventory())
            assert [int(row[5]), int(row[6])] == [identity[0] for identity in identities]
            states = [self.process(pid) for pid, _ in identities]
            # Matching numeric PIDs do not prove the current row is the original runtime: Windows can reuse them. Refuse before publishing shutdown authorization or issuing quit; retain the original ownership/reservation for reconciliation.
            assert all(state and state["start"] == start and state["running"] for state, (_, start) in zip(states, identities)), {"expected": identities, "observed": states}
            attempt_token = str(uuid.uuid4())
            held_slot = {"owner": OWNER, "generation": self.generation, "state": "held", "operation": "P04 picker adapter shutdown", "acquired": now()}
            slot_versions.append(held_slot)
            self.save(self.slot, held_slot)
            pre_shutdown = self.path("pre-shutdown.json")
            self.save(pre_shutdown, {"generation": self.generation, "processes": states, "transcriptPrefix": self.evidence(self.transcript)}, durable=True)
            record = dict(record, shutdown_ready=True, evidence_saved=True, state="shutdown_ready", shutdown_processes=identities, cleanup_attempt={"owner": OWNER, "generation": self.generation, "token": attempt_token, "state": "running", "started": now()})
            record_versions.append(record)
            self.save(self.record, record)
            self.command([CONSOLE, "quit", "--index", "2"])
            deadline = time.monotonic() + 60
            while True:
                rows = self.inventory()
                row = self.exact(rows)
                states = [self.process(pid) for pid, _ in identities]
                exited = all(state is None or state["start"] != start or not state["running"] for state, (_, start) in zip(states, identities))
                running_state = self.command([CONSOLE, "isrunning", "--index", "2"]).strip()
                if running_state == "stop" and int(row[4]) == 0 and int(row[5]) <= 0 and int(row[6]) <= 0 and exited:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Owned shutdown unverified")
                time.sleep(1)
            # The immutable receipt describes the evidence checkpoint, not a later ownership transition. Actual release is witnessed by both same-generation shared records.
            shutdown_proof = self.path("shutdown.json")
            self.save(shutdown_proof, {"generation": self.generation, "stage": "verified-before-release", "originalProcesses": identities, "after": states, "ownedShutdownVerified": True, "usageReleased": False, "list2": rows, "isrunning": running_state, "remainingRunning": [row[:2] for row in rows if int(row[4]) != 0 or int(row[5]) > 0 or int(row[6]) > 0], "storageDeleted": False}, durable=True)
            # All command appends have closed, including isrunning. Flush the existing required transcript before hashing it; a failure must block manifest publication and release.
            with self.transcript.open("r+b") as transcript:
                os.fsync(transcript.fileno())
            manifest = self.path("final-manifest.json")
            required = sorted((pre_shutdown, shutdown_proof, self.transcript), key=lambda path: path.name)
            excluded = {path.name for path in (*required, manifest)}
            # Retain ancillary hashes as observed provenance, not as part of the flushed shutdown proof.
            ancillary = sorted((path for path in self.raw.glob(self.prefix + "-*") if path.suffix in (".txt", ".json", ".jsonl") and path.name not in excluded), key=lambda path: path.name)
            self.save(manifest, {"generation": self.generation, "stage": "verified-before-release", "runner": self.evidence(Path(__file__).resolve()), "evidence": [self.evidence(path) for path in required], "ancillaryEvidence": [self.evidence(path) for path in ancillary], "recordSnapshot": record}, durable=True)
            manifest_sha = sha(manifest)
            verified_anchor = str(manifest)

            assert json.loads(self.record.read_text()) == record and json.loads(self.slot.read_text()) == held_slot, "Shutdown ownership drifted before release"
            released_record = dict(record, owner=None, state="stopped-released", reservedKiB=0, shutdown_verified=True,
                                   stopped=now(), runtime_evidence=verified_anchor,
                                   cleanup_attempt=dict(record["cleanup_attempt"], state="completed", finished=now()))
            released_slot = {"owner": OWNER, "generation": self.generation, "state": "released", "released": now()}
            # Keep the slot held until the instance write succeeds. Remember the would-be values before writing: a failed write can have taken effect.
            record_versions.append(released_record)
            self.save(self.record, released_record)
            assert json.loads(self.slot.read_text()) == held_slot, "Shutdown slot drifted before release"
            slot_versions.append(released_slot)
            self.save(self.slot, released_slot)
            assert json.loads(self.record.read_text()) == released_record and json.loads(self.slot.read_text()) == released_slot, "Shutdown release read-back mismatch"
        except BaseException as primary:
            if record_versions:
                # Reconcile each record independently. Only the initially admitted snapshot or this attempt's exact intended writes can be replaced; a newer owner must survive even if reconciliation of the other record is possible.
                errors = []
                blocked = False
                for path, versions, label in ((self.slot, slot_versions, "slot"), (self.record, record_versions, "instance")):
                    try:
                        current = json.loads(path.read_text())
                        if current not in versions:
                            raise RuntimeError(f"{label} no longer matches an admitted shutdown snapshot")
                        if label == "slot":
                            failed = {"owner": OWNER, "generation": self.generation, "state": "released-with-unresolved-shutdown", "released": now()}
                        else:
                            failed = dict(current, state="shutdown_failed", owner=OWNER, shutdown_verified=False,
                                          reservedKiB=record_versions[0]["reservedKiB"])
                            if verified_anchor is not None:
                                failed["runtime_evidence"] = verified_anchor
                            attempt = current.get("cleanup_attempt")
                            if attempt_token is not None and attempt and attempt.get("token") == attempt_token and attempt.get("owner") == OWNER and attempt.get("generation") == self.generation:
                                failed["cleanup_attempt"] = dict(attempt, state="failed", finished=now())
                        self.save(path, failed)
                        if json.loads(path.read_text()) != failed:
                            raise RuntimeError(f"{label} blocker read-back mismatch")
                        blocked = True
                    except BaseException as exc:
                        errors.append(f"{label}: {exc!r}")
                if errors:
                    # If neither allocator-visible blocker can be confirmed, fail closed by retaining the existing allocation lock for coordinator reconciliation.
                    unlock = blocked
                    raise RuntimeError(f"Shutdown reconciliation is incomplete (allocation lock retained: {not unlock}): {'; '.join(errors)}") from primary
            raise
        finally:
            if unlock:
                self.lock.rmdir()
        print(json.dumps({"generation": self.generation, "ownedShutdownVerified": True, "usageReleased": True, "processes": states, "manifestSha256": manifest_sha}), flush=True)

    def verify_inputs(self):
        """Read-only identity checks must finish before a claim or emulator mutation, including callers that do not run the adapter instrumentation."""
        for path, expected in self.input["sources"].items():
            assert sha(ROOT / path) == expected, path
        for role, artifact in self.input["artifacts"].items():
            assert artifact["certificateSha256"] == SIGNER
            assert artifact["metadata"]["applicationId"] == "app.pristinepage.debug" + (".test" if role == "test" else "")
            assert sha(Path(artifact["apk"])) == sha(Path(artifact["retained"])) == artifact["sha256"]

    def run(self):
        self.verify_inputs()
        try:
            self.claim()
            self.install_and_test()
        finally:
            if self.claimed:
                self.shutdown()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", required=True)
    parser.add_argument("--previous-app", required=True)
    parser.add_argument("--previous-test", required=True)
    parser.add_argument("--missing", type=int, default=0, choices=(0, 7))
    options = parser.parse_args()
    assert all(re.fullmatch(r"[0-9a-f]{64}", value) for value in (options.previous_app, options.previous_test))
    PickerAdapterRun(options.prefix, {"app": options.previous_app, "test": options.previous_test}, options.missing).run()
