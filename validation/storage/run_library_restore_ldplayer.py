"""Run only verified PristinePage library data tests on a released LDPlayer guest."""

from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "validation/providers"))
from run_local_ocr_airplane import LOCAL_ADB_OVERRIDES, verify_target
from run_local_ocr_guest import RUNNER, SIGNER, TEST, load_allocator, need, run_type, sha


CASES = {
    "RoomLibraryCatalogReaderTest": (
        "verifiesExactRoomSchemaAndReadsTheClosedCatalogWithoutChangingItsBytes",
        "aMatchingRoomIdentityDoesNotAuthorizeAnUnexpectedTrigger",
        "aFutureCatalogIsRejectedWithoutRunningMigrationOrDroppingContent",
        "aPortableCatalogCannotDependOnASidecar",
        "unreadableBtreeDoesNotChangeOrDeleteTheVerificationFile",
        "validCatalogWithTrailingPagesIsRejectedWithoutReadingRows",
    ),
    "RoomLibrarySavedCatalogTest": (
        "removedScansRemainRecoverableInTheSnapshotWithTheirOriginalAndHistory",
        "freshWriterRefusesAStandaloneLeftoverJournalThatHasNoForeignKeyViolation",
        "aMalformedCommittedDraftCannotHideItsAlbumFromSnapshotAdmission",
        "savedAlbumProjectionKeepsScanOrderAndEveryRecipeInAFreshRoomCatalog",
    ),
    "RoomLibraryLocalExportTest": (
        "anIsolatedFolderContainsOneExactOriginalAndAReopenableCatalogForTwoScanSlots",
        "anExistingEmptyFolderCannotBeClaimedAsANewExport",
        "aSameLengthDamagedSecondOriginalCannotPublishEvenTheFirstObject",
    ),
    "RoomLibraryLocalRestoreTest": (
        "aClosedBackupRestoresOrderedOriginalsEditsAndSearchWithoutRerunningRecognition",
        "aBackupWithADamagedOriginalLeavesTheFreshTargetUnclaimed",
    ),
    "RoomOcrIndexIntegrationTest": (
        "savedOcrMovesIntoAFreshCatalogWithoutCopyingPendingWorkOrIndexTokens",
        "presentOcrWithUnreadableBytesCannotBeSilentlyOmittedFromTheBackup",
        "danglingOrMisnumberedOcrProvenanceDoesNotMasqueradeAsAnEmptyOrSupersededResult",
    ),
}
OWN_GUEST = "agent-pristinepage-library-" + sha256(str(ROOT).encode()).hexdigest()[:12]
APPROVED = ("304f257cd9ba689efd6392f1145191a42da97ae9", "dd89fca5caf988c7d8c6cb92fd0689f4cebf365bfc7fd75e1f3db5cb6e436e9a")
BUILD_INPUTS = ("data", "core", "gradle", "settings.gradle.kts", "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "buildSrc", "build-logic")


def read_results(output, scenarios):
    result = "INSTRUMENTATION_RESULT: stream="
    terminal = "INSTRUMENTATION_CODE: -1"
    lines = output.rstrip(" \r\n").splitlines()
    need(lines and lines[-1] == terminal and lines.count(result) == 1, "Instrumentation did not terminate normally")
    split = lines.index(result)
    summary = lines[split + 1:-1]
    need(not any(line.startswith("INSTRUMENTATION_") for line in summary), "Unexpected instrumentation result fields")
    expected = set(scenarios)
    passed, failed = set(), set()
    fields = {}
    active = None
    for line in lines[:split]:
        if line.startswith("INSTRUMENTATION_STATUS: "):
            field, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            need(separator and field not in fields, "Invalid or repeated status field")
            fields[field] = value
        elif line.startswith("INSTRUMENTATION_"):
            status = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (1|0|-2|-1)", line)
            need(status and {"class", "test"} <= fields.keys(), "Unexpected instrumentation status")
            case = f"{fields['class']}#{fields['test']}"
            code = status[1]
            if code == "1":
                need(active is None and case in expected and case not in passed | failed, "Invalid named test start")
                active = case
            else:
                need(case == active, "Test completion must match its start")
                (passed if code == "0" else failed).add(case)
                active = None
            fields.clear()
        else:
            # AndroidJUnitRunner sends class headings and stack traces as continuation lines of a status field.
            need(not line.strip() or (fields and not line.startswith(("OK (", "FAILURES!!!", "Tests run:"))), "Text outside a status block")
    need(not fields and active is None and passed | failed == expected, "Incomplete or unexpected named cases")
    if failed:
        need(summary.count("FAILURES!!!") == 1 and
             [line for line in summary if line.startswith("Tests run:")] == [f"Tests run: {len(expected)},  Failures: {len(failed)}"],
             "Missing exact failure summary")
    else:
        need([line for line in summary if line.startswith("OK (")] ==
             [f"OK ({len(expected)} {'test' if len(expected) == 1 else 'tests'})"], "Missing exact success summary")
    return sorted(passed), sorted(failed)


def verify_apk(apk, digest):
    need(apk.is_file() and sha(apk) == digest, "The build artifact changed")
    sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/build-tools/36.0.0"
    env = dict(os.environ, JAVA_HOME="C:/Program Files/Android/Android Studio/jbr")

    def read(tool, *args):
        return subprocess.run([sdk / tool, *args, apk], check=True, timeout=45, capture_output=True, encoding="utf-8", env=env).stdout

    badging = read("aapt2.exe", "dump", "badging")
    manifest = read("aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml")
    signer = read("apksigner.bat", "verify", "--print-certs")
    read("zipalign.exe", "-c", "-P", "16", "4")
    need(re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$", signer) == [SIGNER] and
         re.search(rf"(?m)^package: name='{re.escape(TEST)}' ", badging) and
         '"androidx.test.runner.AndroidJUnitRunner"' in manifest and
         re.search(rf'android:targetPackage.*"{re.escape(TEST)}"', manifest) and
         not re.search(r"android.permission.(?:INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE)", manifest), "Wrong signer, package, or manifest")
    with ZipFile(apk) as archive:
        need(not any(name.startswith("lib/") or name.endswith(".traineddata") for name in archive.namelist()),
             "A data test APK must not contain an OCR runtime or packs")
    return {"sha256": digest, "badging": badging, "manifest": manifest, "signer": signer}


def release_proof(run):
    final = run.path("final-manifest.json")
    proof = json.loads(final.read_text())
    record = json.loads(run.record.read_text())
    need(proof["generation"] == record["generation"] == run.generation and record["shutdown_verified"] and
         record["state"] == "stopped-released" and record["owner"] is None, "The owned guest was not released")
    entries = [proof["runner"], *proof["evidence"], *proof["ancillaryEvidence"]]
    for entry in entries:
        path = ROOT / entry["path"]
        need(path.stat().st_size == entry["bytes"] and sha(path) == entry["sha256"], "Retained runtime evidence changed")


def wait_for_allocator(run, operation, *, before_claim=False):
    """Retry only a collision at the shared lock acquisition, never a partially started operation."""
    deadline = time.monotonic() + (90 if before_claim else 300)
    while True:
        try:
            return operation()
        except FileExistsError as blocked:
            if (Path(blocked.filename or ".") != run.lock or (before_claim and run.claimed) or
                    time.monotonic() >= deadline):
                raise
            time.sleep(2)


def close_owned(run):
    try:
        wait_for_allocator(run, run.close_owned)
    except FileExistsError as blocked:
        if Path(blocked.filename or ".") != run.lock:
            raise
        # The existing instance record still fences reuse; never try to edit the shared lock or slot from outside the allocator.
        record = run.owned()
        run.save(run.path("cleanup-pending.json"), {"generation": run.generation, "guest": record["name"],
                                                    "index": record["index"], "originalProcesses": record["runtime_processes"],
                                                    "reason": "allocation-lock-contention"}, durable=True)
        raise RuntimeError("Guest still owned: graceful shutdown pending allocator release") from blocked
    release_proof(run)


def inspect_prior_pid(inspect, pid):
    if type(pid) is int:
        return inspect(pid)
    need(isinstance(pid, str) and re.fullmatch(r"[1-9][0-9]{0,9}", pid) and int(pid) <= 0xFFFFFFFF,
         "Unknown shared allocator PID encoding")
    # Legacy clients can record PID and start as strings with a different timestamp epoch. Do not compare those clocks.
    state = inspect(int(pid))
    need(state is None or not state["running"], "An earlier recorded PID still exists; preserve this guest")
    return state


def main():
    need(__debug__ and Path.cwd().resolve() == ROOT and not any(os.environ.get(k) for k in LOCAL_ADB_OVERRIDES),
         "Require the exact worktree and default scoped ADB transport")
    need(len(sys.argv) in (3, 4) and re.fullmatch(r"[a-f0-9]{40}", sys.argv[1]) and
         re.fullmatch(r"[a-f0-9]{64}", sys.argv[2]), "Require the exact source and built test APK identities")
    commit, digest = sys.argv[1:3]
    need((commit, digest) == APPROVED, "Unapproved source and test APK pair")
    mode = sys.argv[3] if len(sys.argv) == 4 else None
    need(mode in (None, "--fresh", OWN_GUEST), "Runtime requires the dedicated library guest")
    need(not subprocess.check_output(["git", "diff", commit, "--", *BUILD_INPUTS]) and
         not subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", *BUILD_INPUTS]),
         "Application source or build inputs changed after the exact test build")
    apk = ROOT / "data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk"
    proof = verify_apk(apk, digest)
    if mode is None:
        print(f"Verified self-target data APK {digest}; no guest was touched.")
        return 0
    fresh = mode == "--fresh"
    guest = OWN_GUEST
    prefix = "picker-adapter-library-restore-" + uuid.uuid4().hex[:12]
    raw = ROOT / "validation/raw/storage" / prefix
    raw.mkdir(parents=True)
    retained = raw / "data-library-test.apk"
    with apk.open("rb") as incoming, retained.open("xb") as copy:
        shutil.copyfileobj(incoming, copy)
    need(sha(retained) == digest, "Retained test APK changed")
    (raw / f"{prefix}-artifacts.json").write_text(json.dumps(dict(proof, source=commit, guest=guest, driver=sha(Path(__file__)))), encoding="utf-8")
    ns = load_allocator(ROOT, guest, raw, "pristinepage-library-" + os.environ["OPENCODE_SESSION_ID"])
    options = dict(purpose="pristinepage-library-restore", operation="PristinePage library restore fixture",
                   fresh_disk_floor_gib=16, add_statuses=(0, 1, 2, 3))

    class LibraryRun(run_type(ns, **options)):
        def process(self, pid):
            return inspect_prior_pid(super().process, pid)

    run = LibraryRun(prefix, None, 0)
    print(f"Runtime evidence: {raw.relative_to(ROOT)}", flush=True)
    try:
        wait_for_allocator(run, lambda: run.prepare(fresh, None, fresh), before_claim=True)
        verify_target(run, ns)
        adb = [ns["ADB"], "-s", run.serial]
        listed = run.command([*adb, "shell", "pm", "list", "packages", TEST]).splitlines()
        need(listed == ([] if fresh else [f"package:{TEST}"]), "Expected an absent package on first install or the verified test package on reuse")
        if fresh:
            need(sha(retained) == digest, "Test APK changed before installation")
            need(run.command([*adb, "install", "-t", "--no-streaming", retained], timeout=90).strip().splitlines()[-1:] == ["Success"],
                 "Test-only installation did not acknowledge success")
        need(run.installed_hash(TEST) == digest, "Installed test APK bytes differ")
        registry = run.command([*adb, "shell", "pm", "list", "instrumentation", TEST]).splitlines()
        need(registry == [f"instrumentation:{RUNNER} (target={TEST})"], "Wrong self-target instrumentation")
        scenarios = [f"app.pristinepage.data.{cls}#{test}" for cls, tests in CASES.items() for test in tests]
        verify_target(run, ns)
        output = run.command([*adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", ",".join(scenarios), RUNNER], timeout=150)
        run.path("instrumentation.txt").write_text(output, encoding="utf-8")
        passed, failed = read_results(output, scenarios)
        run.save(run.path("outcomes.json"), {"passed": passed, "failed": failed, "installedSha256": digest}, durable=True)
        print(f"LDPlayer: {len(passed)} passed, {len(failed)} failed. " + ", ".join(failed), flush=True)
        return 1 if failed else 0
    finally:
        if run.claimed:
            close_owned(run)


if __name__ == "__main__":
    raise SystemExit(main())
