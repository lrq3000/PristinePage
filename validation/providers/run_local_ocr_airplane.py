"""Run the ordinary Files OCR witness on the authorized retained LDPlayer guest with guest-local radio restoration."""

import argparse
import json
import os
import re
import signal
import subprocess
import time
import uuid
from pathlib import Path

from local_ocr_airplane import AirplaneJourney, isolated
from local_ocr_files_journey import CONTROL_SHA256
from run_local_ocr_guest import (
    APP, TEST, RUNNER, GUEST_PATTERN, LANGUAGE_RECEIPT, LANGUAGE_SUITES, checked_language_results,
    exact_apk, install_flags, load_allocator, need, passed_case, run_type, sha, verify_file, verify_stage,
)


CASE = ("app.pristinepage.ocr.LocalOcrAirplaneFilesTest",
        "coldFilesImportCorrectsRecognizesAndReopensTheExactPageInAirplaneMode")
MARKER = "EXACT_COLD_FILES_PAGE_IN_AIRPLANE_MODE"
BASELINE = {"airplane_mode_on": "0", "wifi_on": "1", "mobile_data": "1"}
LOCAL_ADB_OVERRIDES = ("ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_ADDRESS", "ANDROID_ADB_SERVER_PORT", "ADB_SERVER_PORT")

# A closed host ADB session cannot end this detached shell's test or restoration. A missing receipt is never success.
GUEST_SCRIPT = r"""
set -eu
d="$1"
fixture="$2"
radio_state() {
    for key in airplane_mode_on wifi_on mobile_data; do timeout 8 settings get global "$key" || return 1; done
}
end_status() {
    printf '%s\n' "$1" >"$d/end.part"
    mv "$d/end.part" "$d/end"
}
app_processes_ended() {
    for pkg in app.pristinepage.debug app.pristinepage.debug.test; do
        if pidof "$pkg" >"$d/remaining-pids"; then
            timeout 8 am force-stop "$pkg" || return 1
        else
            test "$?" -eq 1 || return 1
        fi
        if pidof "$pkg" >"$d/remaining-pids"; then
            return 1
        else
            test "$?" -eq 1 && test ! -s "$d/remaining-pids" || return 1
        fi
    done
}
restore() {
    trap - EXIT
    set +e
    if [ -e "$d/prelaunch" ]; then
        app_processes_ended || { end_status RESTORE_UNVERIFIED; exit 1; }
    fi
    timeout 8 cmd connectivity airplane-mode disable
    timeout 8 svc wifi enable
    timeout 8 svc data enable
    for trial in 1 2 3; do
        if radio_state >"$d/restored" && [ "$(tr '\n' ':' <"$d/restored")" = "0:1:1:" ]; then
            end_status RESTORED
            exit 0
        fi
        sleep 1
    done
    end_status RESTORE_UNVERIFIED
    exit 1
}
record_isolation() {
    phase=$1
    radio_state >"$d/$phase-state"
    timeout 8 dumpsys wifi >"$d/$phase-wifi"
    timeout 8 dumpsys connectivity >"$d/$phase-networks"
    test "$(tr '\n' ':' <"$d/$phase-state")" = "1:0:0:"
    test "$(grep '^Wi-Fi is ' "$d/$phase-wifi")" = "Wi-Fi is disabled"
    test "$(grep '^Active default network:' "$d/$phase-networks")" = "Active default network: none"
    test "$(grep -c '^[[:space:]]*Current Networks:[[:space:]]*$' "$d/$phase-networks")" -eq 1
    test "$(grep -c '^[[:space:]]*Network Requests:[[:space:]]*$' "$d/$phase-networks")" -eq 1
    test "$(sed -n '/^[[:space:]]*Current Networks:[[:space:]]*$/,/^[[:space:]]*Network Requests:[[:space:]]*$/p' "$d/$phase-networks" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//;/^$/d')" = "$(printf 'Current Networks:\nNetwork Requests:')"
}
trap '' HUP
test "$(radio_state | tr '\n' ':')" = "0:1:1:"
trap restore EXIT
printf '%s\n' ARMED >"$d/begin"
timeout 8 svc data disable
timeout 8 cmd connectivity airplane-mode enable
timeout 8 svc wifi disable
sleep 5
record_isolation before
if pidof app.pristinepage.debug >"$d/prelaunch-pids"; then
    exit 1
else
    test "$?" -eq 1 && test ! -s "$d/prelaunch-pids"
fi
printf '%s\n' COLD >"$d/prelaunch"
set +e
timeout "$5" am instrument -w -r -e class "$3" -e "$6" "$fixture" "$4" >"$d/instrumentation" 2>&1
status=$?
set -e
printf '%s\n' "$status" >"$d/instrumentation-exit"
record_isolation after
"""


def witnessed(transcript):
    return (passed_case(transcript, CASE) and
            re.findall(r"(?m)^INSTRUMENTATION_RESULT: offlineOcrWitness=([^\s]+)[ \r]*$", transcript) == [MARKER])


def invocation(suite):
    if suite is None:
        return CASE, "offlineOcrFixture", 265
    need(suite in LANGUAGE_SUITES, "No qualified offline script batch")
    case, controls = LANGUAGE_SUITES[suite]
    return case, "ocrFixture", 60 + 150 * len(controls)


def offline_source(root, source, suite):
    source = source.resolve()
    need(source.is_relative_to(root / "validation/raw/providers"), "Only a retained stage in this worktree is permitted")
    if suite is None:
        need(source.is_file() and source.stat().st_size <= 32_768 and sha(source) == CONTROL_SHA256,
             "Only the independently retained synthetic English control is admitted")
    return source


class EvidenceScope:
    """Use one allocator claim while retaining distinct evidence for each cold launch."""
    def __init__(self, run, suite):
        self.run = run
        self.suite = suite

    def path(self, suffix):
        return self.run.path(f"{self.suite}-{suffix}")

    def __getattr__(self, name):
        return getattr(self.run, name)


def radio_proven(reads, phase):
    values = reads.get(f"{phase}-state", "").splitlines()
    if values != ["1", "0", "0"]:
        return False
    return isolated(dict(zip(AirplaneJourney.KEYS, values)), reads.get(f"{phase}-networks", ""), reads.get(f"{phase}-wifi", ""))


def mark_restoration(run, pending):
    run.lock.mkdir()
    try:
        record = run.owned()
        need(record["state"] == "running" and bool(record.get("radio_recovery_required")) != pending,
             "Unexpected restoration obligation on the current guest claim")
        run.save(run.record, dict(record, radio_recovery_required=pending), durable=True)
    finally:
        run.lock.rmdir()


def verify_target(run, ns):
    record = run.owned()
    row = run.exact(run.inventory())
    need(record["generation"] == run.generation and row[0] == str(run.index) and
         [int(row[i]) for i in (5, 6)] == [pid for pid, _ in record["runtime_processes"]] and
         all((p := run.process(pid)) and p["running"] and p["start"] == start for pid, start in record["runtime_processes"]) and
         run.protocol() == 41 and
         run.command([ns["CONSOLE"], "adb", "--index", str(run.index), "--command", "get-serialno"], timeout=12).strip() == run.serial,
         "Current guest processes, local ADB and exact serial must match the active claim")


def exercise(run, ns, source, hashes, test, previous, *, suite=None):
    target = [ns["ADB"], "-s", run.serial]
    radio = AirplaneJourney(run, target)
    case, option, limit = invocation(suite)
    installed = {pkg: run.installed_hash(pkg) for pkg in hashes}
    need(installed[APP] == hashes[APP], "The exact application must already be installed")
    flags = install_flags(TEST, installed[TEST], hashes[TEST], previous, require_installed=True)
    if flags is not None:
        verify_target(run, ns)
        need(sha(test) == hashes[TEST], "Test APK changed immediately before update")
        need(run.command([*target, "install", *flags, test], timeout=110).strip().splitlines()[-1] == "Success",
             "The instrumentation update failed")
    need({pkg: run.installed_hash(pkg) for pkg in hashes} == hashes, "Installed pair did not match")
    need(run.command([*target, "shell", "pm", "list", "instrumentation", APP]).splitlines() == [
        f"instrumentation:{RUNNER} (target={APP})"], "Unexpected test registration")
    run.save(run.path("installed.json"), {"sha256": hashes, "before": installed}, durable=True)
    baseline, _ = radio.observe("baseline")
    need(baseline == BASELINE and not run.command([*target, "shell", "pidof", APP], allowed=(0, 1)).strip(),
         "Starting guest state is not the known radio baseline with app absent")
    need(radio.shell("dumpsys", "wifi").splitlines()[:1] == ["Wi-Fi is enabled"], "Original Wi-Fi adapter state must be enabled")
    for name in ("setsid", "timeout", "cmd", "svc", "sed", "grep", "sha256sum"):
        need(re.fullmatch(r"/[A-Za-z0-9_/-]+", run.command([*target, "shell", "command", "-v", name]).strip()),
             f"Guest lacks required local command: {name}")
    # This API 28 connectivity service prints real help but returns 255 for the help command.
    help_output = run.command([*target, "shell", "cmd", "connectivity", "help"], allowed=(0, 255))
    need(help_output.splitlines()[:1] == ["Connectivity service commands:"] and
         "airplane-mode [enable|disable]" in {line.strip() for line in help_output.splitlines()},
         "The guest does not advertise the checked radio control")
    nonce = uuid.uuid4().hex
    state = f"/data/local/tmp/pristinepage-airplane-{nonce}"
    program = run.path("guest-program.sh")
    program.write_bytes(GUEST_SCRIPT.encode("ascii"))
    if suite is None:
        folder = f"/sdcard/Download/PristinePage-Offline-{nonce[:12]}"
        fixture = f"{folder}/pristinepage-offline-{nonce[:12]}.png"
        verify_file(source, source.stat().st_size, CONTROL_SHA256)
        fixtures = [(source, fixture, CONTROL_SHA256)]
    else:
        verify_stage(source, LANGUAGE_RECEIPT)
        folder = f"/sdcard/Download/pristinepage-ocr-{nonce[:12]}"
        probe = source / "assets/ocr-probe"
        cases = {c["id"]: c for c in json.loads((probe / "manifest.json").read_bytes())["cases"]}
        fixtures = []
        for key in LANGUAGE_SUITES[suite][1]:
            image = probe / f"controls/{key}.png"
            identity = cases[key]
            verify_file(image, identity["imageBytes"], identity["imageSha256"])
            fixtures.append((image, f"{folder}/{key}.png", identity["imageSha256"]))
    verify_target(run, ns)
    for directory in (state, folder):
        run.command([*target, "shell", "mkdir", directory])
    for artifact, leaf, digest in [(program, state + "/program", sha(program)), *fixtures]:
        run.command([*target, "push", artifact, leaf], timeout=25)
        need(radio.shell("sha256sum", leaf).split()[0] == digest, "Staged guest artifact changed")
    run.save(run.path("radio-obligation.json"), {"status": "PENDING", "baseline": BASELINE,
                                                 "programSha256": sha(program)}, durable=True)
    verify_target(run, ns)
    # If the guest or tool dies after mutation, ordinary OCR runs must not claim the unrestored disk.
    mark_restoration(run, True)
    command = f"setsid sh {state}/program {state} {nonce[:12]} '{'#'.join(case)}' {RUNNER} {limit} {option} </dev/null >{state}/trace 2>&1 &"
    # Allow all guest deadlines, process verification, and ADB recovery. Defer normal host cancellation until the owned guest has unwound.
    deadline = time.monotonic() + limit + 355
    interrupted = False

    def defer_interrupt(*_):
        nonlocal interrupted
        interrupted = True

    handlers = {sig: signal.signal(sig, defer_interrupt) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        try:
            run.command([*target, "shell", command], timeout=10)
        except (AssertionError, subprocess.TimeoutExpired):
            pass  # Unknown delivery is not a reason to replay the radio mutation.
        end = ""
        while not end and time.monotonic() < deadline:
            try:
                end = run.command([*target, "shell", "cat", state + "/end"], timeout=8, allowed=(0, 1)).strip()
            except (AssertionError, subprocess.TimeoutExpired):
                pass
            if not end:
                time.sleep(5)
    finally:
        for sig, handler in handlers.items():
            signal.signal(sig, handler)
    need(end == "RESTORED", "No verified guest-local restoration; retain the obligation and reconcile before reuse")
    verify_target(run, ns)
    restored, _ = radio.observe("restored")
    original_state = json.loads(run.path("airplane-baseline.json").read_text())
    restored_state = json.loads(run.path("airplane-restored.json").read_text())
    need(restored == BASELINE and restored_state == original_state and
         radio.shell("dumpsys", "wifi").splitlines()[:1] == ["Wi-Fi is enabled"],
         "Original observed guest radio and connectivity state did not return")
    run.save(run.path("radio-obligation.json"), {"status": "RESTORED", "baseline": BASELINE,
                                                 "programSha256": sha(program)}, durable=True)
    mark_restoration(run, False)
    if interrupted:
        raise KeyboardInterrupt("Canceled after exact guest restoration")
    evidence = {}
    for name in ("begin", "prelaunch", "instrumentation-exit", "instrumentation", "trace", "before-state", "before-wifi",
                 "before-networks", "after-state", "after-wifi", "after-networks"):
        evidence[name] = run.command([*target, "shell", "cat", state + "/" + name], timeout=15, allowed=(0, 1))
        run.path(f"guest-{name}.txt").write_text(evidence[name], encoding="utf-8")
    need(evidence["begin"].strip() == "ARMED" and evidence["prelaunch"].strip() == "COLD" and
         radio_proven(evidence, "before") and radio_proven(evidence, "after"), "Cold launch or surrounding isolation was not verified")
    need(evidence["instrumentation-exit"].strip() == "0", "Guest OCR instrumentation did not complete")
    result = {"case": "#".join(case), "coldAppProcessAtInstrumentationStart": True, "isolated": True,
              "restored": True, "instrumentationPassed": True}
    if suite is None:
        need(witnessed(evidence["instrumentation"]), "The ordinary cold Files OCR journey did not complete")
    else:
        result["controls"] = checked_language_results(evidence["instrumentation"], suite)
    run.save(run.path("outcomes.json"), result, durable=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--existing", required=True)
    parser.add_argument("--previous-test-sha")
    parser.add_argument("--language-suite", action="append", choices=LANGUAGE_SUITES,
                        help="Cold-start one named script suite from the retained probe stage; repeat for additional suites")
    for name in ("stage", "app", "test", "source"):
        parser.add_argument(name, type=lambda arg: Path(arg).resolve(strict=True))
    parser.add_argument("app_sha")
    parser.add_argument("test_sha")
    args = parser.parse_args()
    root = Path.cwd().resolve()
    need(not any(os.environ.get(name) for name in LOCAL_ADB_OVERRIDES), "Do not override the local shared ADB server")
    need(root.name in ("ocr-background-search", "ocr-current-validation") and root.parent.name == ".worktrees",
         "Use the approved OCR worktree")
    need(re.fullmatch(GUEST_PATTERN, args.existing) and
         (args.previous_test_sha is None or re.fullmatch(r"[0-9a-f]{64}", args.previous_test_sha)),
         "Require the exact retained guest and, if updating instrumentation, the exact prior digest")
    suites = tuple(args.language_suite or ())
    need(len(suites) == len(set(suites)), "Do not repeat a suite in one claim")
    source = offline_source(root, args.source, suites[0] if suites else None)
    if suites:
        verify_stage(source, LANGUAGE_RECEIPT)
    hashes = {APP: args.app_sha, TEST: args.test_sha}
    proofs = [exact_apk(args.app, hashes[APP], args.stage, False), exact_apk(args.test, hashes[TEST], args.stage, True)]
    prefix = "picker-adapter-ocr-ordinary-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    (raw / f"{prefix}-artifacts.json").write_text(json.dumps({
        "guest": args.existing, "apks": proofs, "driverSha256": sha(Path(__file__)),
        **({"suites": suites, "fixtureReceiptSha256": LANGUAGE_RECEIPT} if suites else {"fixtureSha256": CONTROL_SHA256}),
    }), encoding="utf-8")
    ns = load_allocator(root, args.existing, raw, "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"])
    run = run_type(ns)(prefix, None, 0)
    print(f"Cold airplane-mode OCR evidence: {raw.relative_to(root)}", flush=True)
    try:
        run.prepare(False, None, False)
        verify_target(run, ns)
        if suites:
            for suite in suites:
                exercise(EvidenceScope(run, suite), ns, source, hashes, args.test, args.previous_test_sha, suite=suite)
                print(f"Exact native batch {suite} passed with verified radio isolation and restoration.", flush=True)
        else:
            exercise(run, ns, source, hashes, args.test, args.previous_test_sha)
            print("The cold real Files import, correction, background OCR and exact Home reopen passed in airplane mode.", flush=True)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
