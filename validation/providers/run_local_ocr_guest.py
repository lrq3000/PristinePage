"""Run the exact local OCR application pair on the separately authorized LDPlayer guest."""

if not __debug__:
    raise RuntimeError("Optimized Python is prohibited")

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import time
import uuid
from zipfile import ZipFile

from prepare_ocr_android_probe import verify_file, verify_stage
from prepare_ocr_local_app import verify_candidate, verify_ocr_members, verify_standard_manifest


APP = "app.pristinepage.debug"
TEST = APP + ".test"
RUNNER = TEST + "/androidx.test.runner.AndroidJUnitRunner"
CASE = ("app.pristinepage.ocr.LocalOcrImportJourneyTest",
        "importedScansAreRecognizedAutomaticallyAndHomeOpensTheSecondPage")
FILES_CASE = (CASE[0], "coldHomeSearchReopensTheSavedFilesPageAndCreatesJpeg")
BACKFILL_CASE = ("app.pristinepage.ocr.LocalOcrStartupBackfillTest", "firstActivityLaunchFindsAndIndexesAnExistingUnqueuedPage")
DEATH_CASE = ("app.pristinepage.ocr.LocalOcrWorkDeathTest", "interruptRealNativeRecognition")
RECOVER_CASE = (DEATH_CASE[0], "thirdInterruptionIsTerminalAfterOrdinaryLaunch")
LANGUAGE_CASE = ("app.pristinepage.ocr.LocalOcrLanguageJourneyTest", "settingsSelectedScriptsReachTheExactHomePage")
JAPANESE_CASE = (LANGUAGE_CASE[0], "japaneseSparseLayoutRemainsBoundToTheExactSearchHit")
MANUAL_CASE = ("app.pristinepage.ocr.LocalOcrManualRerunTest", "terminalPageCanRerunWithSavedSettingsAndThenDefaults")
LANGUAGE_CONTROLS = ("eng", "fra", "blank", "ara-eng", "ara", "chi_sim")
LANGUAGE_SUITES = {
    "baseline": (LANGUAGE_CASE, LANGUAGE_CONTROLS),
    "japanese": (JAPANESE_CASE, ("jpn",)),
    "latin": ((LANGUAGE_CASE[0], "remainingLatinLanguagesReachTheExactHomePage"),
              ("spa", "deu", "ita", "por", "nld", "pol", "tur")),
    "non-latin": ((LANGUAGE_CASE[0], "remainingNonLatinLanguagesReachTheExactHomePage"),
                  ("rus", "ukr", "ell", "heb", "hin", "kor", "vie", "ind")),
}
LANGUAGE_RECEIPT = "7d7f418e3b20eea85ed795151c54df8ac2b920d6c09cc5d443a6559c7c5ec093"
SIGNER = "7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee"
ALLOCATOR = "ebc068885e9e6af8c836fb658edebc245a4b8c3adf52bf6c040fa4fdac263c1d"
GUEST_PATTERN = r"agent-pristinepage-ocr-[a-f0-9]{12}"
RAM_KIB = 4 * 1024**2


def need(ok, message):
    if not ok:
        raise RuntimeError(message)


def checked_language_results(output, suite="baseline"):
    case, controls = LANGUAGE_SUITES[suite]
    marker = re.compile(r"(?m)^INSTRUMENTATION_STATUS: ocrLanguageControl=([a-z_-]+)\nINSTRUMENTATION_STATUS_CODE: 65\n")
    normalized = output.replace("\r\n", "\n")
    marks = tuple(marker.findall(normalized))
    need(marks == controls and passed_case(marker.sub("", normalized), case),
         "Not all exact language controls completed; inspect the transcript")
    return marks


def inspect_fresh_registration(before, after, name, installation):
    """The local CLI can return a nonzero status after creating the VM. Admit observed state, never repeat add."""
    matches = [row for row in after if row[1] == name]
    need(len(matches) == 1 and all(row[1] != name for row in before), "No unique new guest registration")
    row, = matches
    need(re.fullmatch(r"(?:0|[1-9][0-9]*)", row[0]) and all(other[0] != row[0] for other in before) and
         sum(other[0] == row[0] for other in after) == 1, "Creation did not allocate a fresh index")
    need(int(row[4]) == 0 and int(row[5]) <= 0 and int(row[6]) <= 0, "New guest is not stopped")
    directory = installation / "vms" / f"leidian{row[0]}"
    need(directory.resolve() == directory and all(
         path.resolve() == path and path.is_file() and path.stat().st_size > 0
         for path in (directory / "data.vmdk", directory / "sdcard.vmdk")), "New guest disks are incomplete or redirected")
    return int(row[0])


def install_flags(package, installed, candidate, previous_test, previous_app=None, *, require_installed=False):
    if installed is None:
        need(not require_installed, "Save as inspection requires both installed APK identities")
        return ["-t"] if package == TEST else []
    if installed == candidate:
        return None
    if package == APP and installed == previous_app:
        return ["-r"]
    need(package == TEST and installed == previous_test, "Refusing to replace an unapproved installed APK")
    return ["-r", "-t"]

def check_installed_pair(installed, previous, candidate):
    need(installed in (previous, candidate), "Refusing an installed pair that is neither the exact predecessor nor the exact candidate")


def sha(path):
    with path.open("rb") as stream:
        from hashlib import file_digest
        return file_digest(stream, "sha256").hexdigest()


def normalize_config(contents):
    original = json.loads(contents)
    if not isinstance(original, dict) or original.get("basicSettings.adbDebug", 0) != 0:
        raise ValueError("No disabled local debugging setting to configure")
    return dict(original, **{"basicSettings.adbDebug": 1})


def passed_case(output, case):
    terminal = re.findall(r"(?m)^INSTRUMENTATION_CODE: (-?[0-9]+)[ \r]*$", output)
    if terminal != ["-1"] or not re.search(r"(?:^|\n)INSTRUMENTATION_CODE: -1[ \r\n]*\Z", output):
        return False
    if re.search(r"(?m)^(?:INSTRUMENTATION_(?:FAILED|ABORTED):|INSTRUMENTATION_RESULT: (?:shortMsg|longMsg)=)", output):
        return False
    observed = []
    for fields, code in re.findall(r"(.*?)(?:^INSTRUMENTATION_STATUS_CODE: (-?\d+)[ \r]*$)", output, re.M | re.S):
        method = re.search(r"(?m)^INSTRUMENTATION_STATUS: test=([^ \r\n]+)[ \r]*$", fields)
        cls = re.search(r"(?m)^INSTRUMENTATION_STATUS: class=([^ \r\n]+)[ \r]*$", fields)
        if not cls or not method:
            return False
        observed.append((cls[1], method[1], code))
    return observed == [(*case, "1"), (*case, "0")] and len(re.findall(r"(?m)^OK \(1 test\)[ \r]*$", output)) == 1


def recovery_pid(output, ordinal):
    def field(name):
        return re.findall(rf"(?m)^INSTRUMENTATION_STATUS: {name}=([^\r\n]+)$", output.replace("\r\n", "\n"))

    pid = field("ocrRecoveryPid")
    need(field("class") == [DEATH_CASE[0]] and field("test") == [DEATH_CASE[1]]
         and field("ocrRecoveryCheckpoint") == [str(ordinal)] and len(pid) == 1 and re.fullmatch(r"[1-9][0-9]{0,8}", pid[0])
         and re.findall(r"(?m)^INSTRUMENTATION_STATUS_CODE: (\d+)\s*$", output) == ["1", "64"]
         and re.search(r"(?m)^INSTRUMENTATION_RESULT: shortMsg=Process crashed\.\s*$", output)
         and re.search(r"(?m)^INSTRUMENTATION_CODE: 0\s*$", output), "Missing or ambiguous native interruption witness")
    return int(pid[0])


def exact_apk(path, digest, stage, is_test):
    need(re.fullmatch(r"[0-9a-f]{64}", digest) and sha(path) == digest, "Local APK identity changed")
    sdk = Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/build-tools/36.0.0"
    env = dict(os.environ, JAVA_HOME="C:/Program Files/Android/Android Studio/jbr")

    def read(command, *args):
        return subprocess.run([sdk / command, *args, path], check=True, env=env, capture_output=True,
                              text=True, encoding="utf-8", timeout=45).stdout

    badging = read("aapt2.exe", "dump", "badging")
    manifest = read("aapt2.exe", "dump", "xmltree", "--file", "AndroidManifest.xml")
    signing = read("apksigner.bat", "verify", "--print-certs")
    read("zipalign.exe", "-c", "-P", "16", "4")
    need(re.findall(r"(?m)^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$", signing) == [SIGNER],
         "Wrong S-01 development signer")
    package = TEST if is_test else APP
    need(re.search(rf"(?m)^package: name='{re.escape(package)}' ", badging), "Wrong APK package")
    need(not re.search(r"android.permission.(INTERNET|ACCESS_NETWORK_STATE|CHANGE_NETWORK_STATE)", manifest),
         "APK declares network permissions")
    if is_test:
        need('"androidx.test.runner.AndroidJUnitRunner"' in manifest and
             re.search(rf'android:targetPackage.*"{re.escape(APP)}"', manifest), "Wrong test target")
        with ZipFile(path) as archive:
            verify_ocr_members(archive, {})
    else:
        # Retain replay of historical test pairs. The new normal-build route is checked separately and must contain the same OCR payload.
        if re.search(r"versionName='[^']+-debug-ocr-local'", badging):
            need("app.pristinepage.LocalOcrApplication" in manifest and "androidx.work.WorkManagerInitializer" in manifest,
                 "Not the historical OCR development app")
        else:
            verify_standard_manifest(badging, manifest)
        verify_candidate(stage, path)
    return {"package": package, "sha256": digest, "manifest": manifest, "badging": badging}


def load_allocator(root, guest, raw, owner):
    path = root / "validation/decode/run_picker_adapter.py"
    need(sha(path) == ALLOCATOR, "Shared allocator driver changed")
    code = path.read_text(encoding="utf-8")
    for old, new, count in (
        ('ROOT = Path(r"X:\\local-workspace\\.worktrees\\paperlight-v1")', f"ROOT = Path({str(root)!r})", 1),
        ('NAME = "LOCAL_TEST_INSTANCE"', f"NAME = {guest!r}", 1),
        ('OWNER = "LOCAL_SESSION"', f"OWNER = {owner!r}", 1),
        ('        self.raw = ROOT / "validation/raw/decode"', f'        self.raw = Path({str(raw)!r})', 1),
        ('"--index", "2"', '"--index", str(self.index)', 4),
    ):
        need(code.count(old) == count, "Shared allocator adapter no longer matches")
        code = code.replace(old, new)
    ns = {"__name__": "_local_ocr_allocator", "__file__": str(path)}
    exec(compile(code, str(path), "exec"), ns)
    return ns


def run_type(ns, *, purpose="pristinepage-ocr-qualification", operation="PristinePage local OCR guest",
             fresh_disk_floor_gib=12, add_statuses=(0, 1, 2)):
    base, console, adb, now = (ns[key] for key in ("PickerAdapterRun", "CONSOLE", "ADB", "now"))

    class Run(base):
        def exact(self, rows):
            matches = [row for row in rows if row[1] == ns["NAME"]]
            need(len(matches) == 1 and int(matches[0][0]) == self.index, "Owned guest identity changed")
            return matches[0]

        def prepare(self, create, recovery, enable_adb):
            self.index = None
            record = None
            self.lock.mkdir()
            try:
                slot = json.loads(self.slot.read_text())
                if recovery:
                    need(slot["state"] == "released-with-unresolved-shutdown" and slot["owner"] == ns["OWNER"]
                         and slot["generation"] == recovery, "Not this failed provisioning slot")
                else:
                    need(slot["state"] == "released", "Shared allocation slot is not released")
                before = self.inventory()
                name = ns["NAME"]
                for path in ns["COORD"].glob("*.json"):
                    other = json.loads(path.read_text())
                    if recovery and path == self.record:
                        need(other["state"] == "launch_failed" and other["owner"] == ns["OWNER"]
                             and other["claim_generation"] == recovery and other["index"] is None
                             and not other["runtime_processes"] and not other["shutdown_processes"],
                             "An indeterminate launch is not eligible for creation-only recovery")
                        continue
                    need(not other.get("reservedKiB", 0) and other.get("state") not in
                         ("provisioning", "launching", "shutdown_failed", "launch_failed"), "An unresolved shared claim exists")
                available = int(self.command(["pwsh", "-NoProfile", "-Command",
                                             "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"]).strip())
                free = min(shutil.disk_usage(ns["ROOT"]).free, shutil.disk_usage(console.parent / "vms").free)
                disk_floor = fresh_disk_floor_gib if create else 12
                need(available >= RAM_KIB and free >= disk_floor * 1024**3, "Not enough RAM or disk for safe guest admission")
                need("1.0.41" in self.command([adb, "version"]), "Unexpected local ADB protocol")
                prior_server = self.protocol(True)
                if prior_server == "absent":
                    need(not self.command(["pwsh", "-NoProfile", "-Command",
                        'Get-CimInstance Win32_Process -Filter "Name=\'adb.exe\'" | Select-Object -ExpandProperty ProcessId']).strip(),
                         "Unknown ADB daemon; do not restart it")

                if create:
                    need(not self.record.exists() and all(row[1] != name for row in before), "New guest name already exists")
                    record = {"name": name, "index": None, "installation": str(console.parent), "worktree": str(ns["ROOT"]),
                              "purpose": purpose, "created_by_agent": True, "disposable": False,
                              "created": now(), "keep_running": False, "handoff_to": None, "ownership_history": []}
                elif recovery:
                    record = self.owned_provision(before, recovery, console)
                else:
                    record = json.loads(self.record.read_text())
                    need(record["name"] == name and record["installation"] == str(console.parent)
                         and record.get("purpose") == purpose
                         and record["owner"] is None and record["state"] == "stopped-released" and record["shutdown_verified"]
                          and not record["keep_running"] and not record["handoff_to"]
                         and not record.get("radio_recovery_required"), "Not this released test guest, or radio restoration needs reconciliation")
                    self.index = record["index"]
                    self.require_stopped(self.exact(before))
                    for pid, start in record["shutdown_processes"]:
                        process = self.process(pid)
                        need(not process or process["start"] != start or not process["running"], "Prior owned process is live")
                    record["ownership_history"].append({k: record[k] for k in ("generation", "stopped", "shutdown_processes")})

                record.update(owner=ns["OWNER"], generation=self.generation, claim_generation=self.generation,
                              state="provisioning" if create else "launching", claimed=now(), shutdown_ready=False,
                              evidence_saved=False, shutdown_verified=False, availableKiB=available, reservedKiB=RAM_KIB,
                              serial=None, runtime_processes=[], shutdown_processes=[], cleanup_attempt=None)
                self.save(self.record, record, durable=True)
                self.claimed = True
                self.save(self.slot, {"owner": ns["OWNER"], "generation": self.generation,
                                      "state": "held", "operation": operation, "acquired": now()}, durable=True)
                if create:
                    # An allowed nonzero status is not success; only a unique stopped registration and both disks admit it.
                    self.command([console, "add", "--name", name], timeout=95, allowed=add_statuses)
                    self.index = inspect_fresh_registration(before, self.inventory(), name, console.parent)
                    record.update(index=self.index, state="launching")
                    self.save(self.record, record, durable=True)
                if create or recovery:
                    self.command([console, "modify", "--index", str(self.index), "--memory", "4096"], timeout=20, allowed=(0, 1))
                    self.require_stopped(self.exact(self.inventory()))
                    settings = console.parent / "vms/config" / f"leidian{self.index}.config"
                    fields = json.loads(settings.read_text(encoding="utf-8-sig"))
                    need(fields.get("advancedSettings.memorySize") == 4096, "New guest memory change was not acknowledged")

                if enable_adb:
                    self.configure_local_adb(console)
                self.command([console, "launch", "--index", str(self.index)], timeout=25)
                deadline = time.monotonic() + 170
                self.serial = None
                while time.monotonic() < deadline:
                    row = self.exact(self.inventory())
                    if int(row[5]) > 0 and int(row[6]) > 0:
                        states = [self.process(int(row[i])) for i in (5, 6)]
                        need(all(state and state["running"] for state in states), "Owned process drifted at boot")
                        ids = [[state["pid"], state["start"]] for state in states]
                        if not record["runtime_processes"]:
                            record["runtime_processes"] = ids
                            self.save(self.record, record)
                        need(record["runtime_processes"] == ids, "Original guest PIDs changed")
                        if not self.serial:
                            response = self.command([console, "adb", "--index", str(self.index),
                                                     "--command", "get-serialno"], timeout=12).strip()
                            if re.fullmatch(r"emulator-[0-9]+", response):
                                self.serial = response
                        if self.serial and self.protocol() == 41:
                            targeted = [adb, "-s", self.serial]
                            try:
                                state = self.command([*targeted, "get-state"], allowed=(0, 1)).strip()
                                if state == "device":
                                    boot = self.command([*targeted, "shell", "getprop", "sys.boot_completed"], allowed=(0, 1)).strip()
                                    if boot == "1" and self.command([*targeted, "shell", "pm", "path", "android"],
                                                                    allowed=(0, 1)).strip().startswith("package:"):
                                        break
                            except subprocess.TimeoutExpired:
                                # LDPlayer can report an attached serial before Android responds to shell requests.
                                # Retry the read within the existing boot deadline, never repeat a launch or input.
                                pass
                    time.sleep(2)
                else:
                    raise RuntimeError("Owned guest boot or ADB readiness deadline exceeded")
                api = int(self.command([*targeted, "shell", "getprop", "ro.build.version.sdk"]).strip())
                abis = self.command([*targeted, "shell", "getprop", "ro.product.cpu.abilist"]).strip().split(",")
                record.update(state="running", api=api, supported_abis=abis, serial=self.serial, booted=now(), reservedKiB=0)
                self.save(self.record, record, durable=True)
                self.save(self.slot, {"owner": ns["OWNER"], "generation": self.generation, "state": "released",
                                      "released": now()}, durable=True)
                self.save(self.path("claim.json"), record, durable=True)
                need(api >= 28 and "arm64-v8a" in abis, "New guest API or ABI cannot run the app")
            finally:
                self.lock.rmdir()

        @staticmethod
        def require_stopped(row):
            need(int(row[4]) == 0 and int(row[5]) <= 0 and int(row[6]) <= 0, "Target guest is not stopped")

        def configure_local_adb(self, console):
            record = self.owned()
            need(record["purpose"] == purpose and record["index"] == self.index,
                 "Local-only ADB applies only to the exact qualification guest")
            self.require_stopped(self.exact(self.inventory()))
            need(self.command([console, "isrunning", "--index", str(self.index)]).strip() == "stop", "Guest is not stopped")
            config = console.parent / "vms/config" / f"leidian{self.index}.config"
            need(config.resolve() == config and config.is_file(), "Unexpected guest configuration path")
            contents = config.read_bytes()
            need(len(contents) <= 64 * 1024, "Unexpected guest configuration extent")
            original = json.loads(contents)
            key = "basicSettings.adbDebug"
            expected = normalize_config(contents)
            with self.path("before-local-adb.config").open("xb") as saved:
                saved.write(contents)
                saved.flush()
                os.fsync(saved.fileno())
            # Installed LDPlayer uses 1 for local ADB (not remote or root). Retain all other settings.
            need(config.read_bytes() == contents, "Guest config changed before writing")
            self.save(config, expected, durable=True)
            need(json.loads(config.read_bytes()) == expected, "Guest configuration read-back mismatch")
            self.save(self.path("local-adb.json"), {"targetIndex": self.index, "setting": key, "before": original.get(key),
                                                   "after": 1, "allOtherSettingsUnchanged": True}, durable=True)

        def owned_provision(self, rows, generation, console):
            record = json.loads(self.record.read_text())
            need(record["name"] == ns["NAME"] and record["installation"] == str(console.parent)
                 and record["generation"] == generation and record["purpose"] == purpose,
                 "Provisioning recovery has the wrong identity")
            matches = [row for row in rows if row[1] == ns["NAME"]]
            need(len(matches) == 1, "No unique registered guest from the original add")
            self.index = int(matches[0][0])
            self.require_stopped(self.exact(rows))
            need(self.command([console, "isrunning", "--index", str(self.index)]).strip() == "stop",
                 "Provisioned guest is not stopped")
            guest_dir = console.parent / "vms" / f"leidian{self.index}"
            need(guest_dir.resolve() == guest_dir and all((guest_dir / disk).is_file() for disk in ("data.vmdk", "sdcard.vmdk")),
                 "Provisioned guest disks are incomplete")
            record["index"] = self.index
            record["ownership_history"].append({"generation": generation, "state": record["state"], "creationRecovered": now()})
            return record

        def close_owned(self):
            if not self.claimed:
                return
            record = self.owned()
            if len(record["runtime_processes"]) == 2:
                self.shutdown()
                return
            self.lock.mkdir()
            try:
                current = self.owned()
                need(current == record, "Partial-launch ownership changed")
                # An unverified partial launch is never free for reuse. Do not guess at a name, index or process to quit.
                self.save(self.record, dict(current, state="launch_failed", shutdown_verified=False,
                                            evidence_saved=True, shutdown_ready=False), durable=True)
                self.save(self.slot, {"owner": ns["OWNER"], "generation": self.generation,
                                      "state": "released-with-unresolved-shutdown", "released": now()}, durable=True)
            finally:
                self.lock.rmdir()
            raise RuntimeError("Unverified provisioning/launch; retained the exact allocator cleanup obligation")

        def witness(self, pair, hashes, fresh, files_source, saved_album, backfill, recovery, controls, rerun, diagnosis, previous_test,
                    settings_restart=False, inspect_save_as=False, previous_app=None):
            record = self.owned()
            row = self.exact(self.inventory())
            need([int(row[i]) for i in (5, 6)] == [item[0] for item in record["runtime_processes"]], "Target processes changed")
            for pid, start in record["runtime_processes"]:
                process = self.process(pid)
                need(process and process["running"] and process["start"] == start, "Target process identity changed")
            target = [adb, "-s", self.serial]
            present = set(self.command([*target, "shell", "pm", "list", "packages", APP]).splitlines())
            need(present <= {f"package:{APP}", f"package:{TEST}"} and (not fresh or not present),
                 "Fresh guest already contains an unrelated PristinePage app")
            if settings_restart:
                need(present == {f"package:{APP}", f"package:{TEST}"} and previous_test is None,
                     "This witness must reuse both exact installed APKs")
            if previous_app is not None:
                need(present == {f"package:{APP}", f"package:{TEST}"} and previous_test is not None,
                     "A checked successor requires the retained installed app and test")
            installed = {}
            for package, path in pair.items():
                need(sha(path) == hashes[package], "APK changed before install")
                installed[package] = self.installed_hash(package) if f"package:{package}" in present else None
            if previous_app is not None:
                check_installed_pair(installed, {APP: previous_app, TEST: previous_test}, hashes)
                self.save(self.path("installed-before.json"), {"sha256": installed}, durable=True)
            installation = {package: install_flags(package, installed[package], hashes[package], previous_test, previous_app,
                                                    require_installed=inspect_save_as)
                            for package in pair}
            for package, path in pair.items():
                flags = installation[package]
                if flags is not None:
                    need(sha(path) == hashes[package], "APK changed before install")
                    result = self.command([*target, "install", *flags, path], timeout=110)
                    need(result.strip().splitlines()[-1] == "Success", "Exact guest install failed")
                need(self.installed_hash(package) == hashes[package], "Installed bytes do not match the reviewed APK")
            registry = self.command([*target, "shell", "pm", "list", "instrumentation", APP]).splitlines()
            need(registry == [f"instrumentation:{RUNNER} (target={APP})"], "Unexpected registered instrumentation target")
            self.save(self.path("installed.json"), {"sha256": hashes, "runner": registry}, durable=True)
            if settings_restart:
                from local_ocr_files_journey import LocalOcrSettingsRestart
                settings = LocalOcrSettingsRestart(self, target, APP)
                self.save(self.path("settings-restart.json"), settings.verify(), durable=True)
                return
            if diagnosis and diagnosis != "files":
                self.inspect_saved_wakes(target, recovery_title=None if diagnosis == "work" else diagnosis)
                return
            if diagnosis == "files":
                self.inspect_files_failure(target)
                return
            if recovery:
                self.interrupt_and_recover(target)
                return
            if controls:
                self.language_journey(target, controls)
                return
            self.saved_journey(target, files_source, saved_album, backfill, rerun, inspect_save_as=inspect_save_as)

        def saved_journey(self, target, files_source, saved_album, backfill, rerun, checkpoint=None, inspect_save_as=False):
            if files_source:
                from local_ocr_files_journey import LocalFilesJourney
                journey = LocalFilesJourney(self, target, APP, files_source)
                try:
                    saved_album = journey.enter()
                except Exception:
                    # Preserve crash and resolver evidence before shutting down; do not reissue the unsuccessful UI action.
                    self.inspect_files_failure(target)
                    raise
            if checkpoint:
                checkpoint("after-save")
            case = FILES_CASE if saved_album else BACKFILL_CASE if backfill else MANUAL_CASE if rerun else CASE
            extras = ["-e", "filesSavedAlbum", saved_album] if saved_album else ["-e", "recoveryAlbum", f"'{rerun}'"] if rerun else []
            if checkpoint:
                extras += ["-e", "filesSearchOnly", "1"]
            if inspect_save_as:
                extras += ["-e", "filesInspectPicker", "1"]
            output = self.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                   "#".join(case), *extras, RUNNER], timeout=320 if rerun else 250)
            self.path("instrumentation.txt").write_text(output, encoding="utf-8")
            need(passed_case(output, case),
                 "Ordinary OCR instrumentation failed or skipped; inspect the exact transcript")
            branch = "EXACT_PAGE_SEARCH" if checkpoint else "PICKER_OPENED_WITHOUT_CONFIRMATION" if inspect_save_as else None
            if branch:
                markers = re.findall(r"(?m)^INSTRUMENTATION_RESULT: filesWitness=(\S+)[ \r]*$", output)
                need(markers == [branch], "No exact completion marker for the requested scope")
            self.save(self.path("outcomes.json"), {"case": "#".join(case), "instrumentationPassed": True,
                                                  "filesAlbum": saved_album, "branch": branch}, durable=True)

        def interrupt_and_recover(self, target):
            title = f"OCR recovery {uuid.uuid4().hex[:12]}"
            # ADB shells receive a single command; escape spaces in this bounded synthetic title.
            extras = ["-e", "recoveryAlbum", f"'{title}'"]
            interrupted = []
            for ordinal in (1, 2, 3):
                output = self.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                       "#".join(DEATH_CASE), *extras, "-e", "recoveryOrdinal", str(ordinal), RUNNER],
                                      timeout=155, allowed=(0, 1))
                self.path(f"recovery-start-{ordinal}.txt").write_text(output, encoding="utf-8")
                pid = recovery_pid(output, ordinal)
                alive = self.command([*target, "shell", "pidof", APP], allowed=(0, 1)).strip()
                need(not alive, "The observed instrumentation process has not exited; no second launch")
                interrupted.append({"ordinal": ordinal, "nativeFrameObserved": True, "processExited": True, "pid": pid})
                self.save(self.path("recovery-interruptions.json"), {"interruptions": interrupted}, durable=True)
            output = self.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                   "#".join(RECOVER_CASE), *extras, RUNNER], timeout=155)
            self.path("recovery-terminal.txt").write_text(output, encoding="utf-8")
            need(passed_case(output, RECOVER_CASE), "Missing terminal recovery witness")
            self.save(self.path("recovery-outcome.json"), {"phase": "FAILED", "error": "INTERRUPTED", "starts": 3}, durable=True)

        def language_journey(self, target, controls, suite="baseline"):
            prefix = uuid.uuid4().hex[:12]
            folder = "/sdcard/Download/pristinepage-ocr-" + prefix
            probe = controls.parents[2]
            need(controls == probe / "assets/ocr-probe/controls", "Unexpected synthetic control location")
            verify_stage(probe, LANGUAGE_RECEIPT)
            cases = {case["id"]: case for case in json.loads((controls.parent / "manifest.json").read_text(encoding="utf-8"))["cases"]}
            self.command([*target, "shell", "mkdir", folder])
            case, control_ids = LANGUAGE_SUITES[suite]
            for control in control_ids:
                name = control + ".png"
                source = controls / name
                identity = cases[control]
                need(0 < identity["imageBytes"] < 32 * 1024, "Invalid synthetic control length")
                verify_file(source, identity["imageBytes"], identity["imageSha256"])
                self.command([*target, "push", source, f"{folder}/{name}"], timeout=20)
            output = self.command([*target, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                                   "#".join(case), "-e", "ocrFixture", prefix, RUNNER], timeout=60 + 150 * len(control_ids))
            label = "language" if suite in ("baseline", "japanese") else f"language-{suite}"
            self.path(f"{label}-instrumentation.txt").write_text(output, encoding="utf-8")
            marks = checked_language_results(output, suite)
            self.save(self.path(f"{label}-outcomes.json"), {"controls": marks, "passed": True}, durable=True)

        def inspect_files_failure(self, target):
            for name, argv in (
                ("files-resolver", ("shell", "cmd", "package", "resolve-activity", "--brief",
                                    "-a", "android.intent.action.OPEN_DOCUMENT", "-c", "android.intent.category.OPENABLE", "-t", "image/*")),
                ("files-crash-buffer", ("logcat", "-b", "crash", "-d", "-t", "120", "-v", "brief", "AndroidRuntime:E", "*:S")),
                ("files-crash-archive", ("shell", "dumpsys", "dropbox", "--print", "data_app_crash")),
            ):
                value = self.command([*target, *argv], timeout=18, allowed=(0, 1, 2))
                need(len(value) <= 512 * 1024, "Unexpected crash evidence size on the synthetic guest")
                self.path(name + ".txt").write_text(value, encoding="utf-8")

        def copy_scheduler_database(self, target, remote_dir, filename):
            live = self.command([*target, "shell", "pidof", APP], allowed=(0, 1)).strip()
            need(not live, "Do not copy scheduler metadata while the application process is live")
            local_db = self.raw / ("diagnostic-" + filename)
            local_db.mkdir()
            for suffix in ("", "-wal", "-shm"):
                name = filename + suffix
                remote = remote_dir + "/" + name
                extent = self.command([*target, "shell", "run-as", APP, "stat", "-c", "%s", remote], allowed=(0, 1, 2)).strip()
                if not re.fullmatch(r"[0-9]{1,8}", extent):
                    need(suffix, "Missing WorkManager database")
                    continue
                need(0 <= int(extent) <= 8 * 1024**2, "Diagnostic store is over its fixture bound")
                path = local_db / name
                with path.open("xb") as output:
                    subprocess.run([*target, "exec-out", "run-as", APP, "cat", remote],
                                   check=True, timeout=20, stdout=output, stderr=subprocess.PIPE)
                need(path.stat().st_size == int(extent), "Scheduler metadata changed during readback")
            return local_db / filename

        def inspect_saved_wakes(self, target, recovery_title=None):
            path = self.copy_scheduler_database(target, "no_backup", "androidx.work.workdb")
            with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as database:
                rows = database.execute("""SELECT state, run_attempt_count, COUNT(*)
                    FROM WorkSpec WHERE worker_class_name = ? GROUP BY state, run_attempt_count
                    ORDER BY state, run_attempt_count""", ("app.pristinepage.ocr.SavedOcrPageWorker",)).fetchall()
            need(rows, "No saved OCR wake found in the synthetic guest")
            self.save(self.path("work-state-only.json"), {"stateAttemptAndCount": rows}, durable=True)
            print(f"WorkManager state / run-attempt / count rows: {rows}", flush=True)
            if recovery_title:
                path = self.copy_scheduler_database(target, "databases", "pristinepage.db")
                with sqlite3.connect(path.as_uri() + "?mode=ro", uri=True) as database:
                    rows = database.execute("""SELECT j.state, j.attempt, j.errorCode, w.resultJobId IS NOT NULL
                        FROM Document d JOIN Page p ON p.documentId=d.documentId
                        LEFT JOIN OcrPageWork w ON w.pageId=p.pageId LEFT JOIN Job j ON j.jobId=w.jobId
                        WHERE d.title=?""", (recovery_title,)).fetchall()
                need(len(rows) == 1, "Diagnostic must resolve exactly one synthetic recovery page")
                self.save(self.path("recovery-room-state-only.json"), {"stateAttemptErrorAndResult": rows[0]}, durable=True)
                print(f"Exact Room recovery state / starts / error / result: {rows[0]}", flush=True)

    return Run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--new", action="store_true")
    mode.add_argument("--existing")
    mode.add_argument("--recover-provision", nargs=2, metavar=("GUEST", "GENERATION"))
    mode.add_argument("--preflight", action="store_true")
    parser.add_argument("--enable-local-adb", action="store_true")
    files = parser.add_mutually_exclusive_group()
    files.add_argument("--files-source", type=Path)
    files.add_argument("--files-saved-album")
    files.add_argument("--startup-backfill", action="store_true")
    files.add_argument("--native-recovery", action="store_true")
    files.add_argument("--language-controls", type=lambda v: Path(v).resolve())
    files.add_argument("--manual-rerun")
    files.add_argument("--settings-restart", action="store_true")
    files.add_argument("--diagnose-work", action="store_const", const="work", dest="diagnosis")
    files.add_argument("--diagnose-files", action="store_const", const="files", dest="diagnosis")
    files.add_argument("--diagnose-recovery", dest="diagnosis")
    parser.add_argument("--previous-test-sha")
    parser.add_argument("--previous-app-sha")
    parser.add_argument("--airplane", action="store_true")
    parser.add_argument("--save-as", action="store_true")
    parser.add_argument("--inspect-save-as", action="store_true")
    parser.add_argument("stage", type=lambda v: Path(v).absolute())
    parser.add_argument("app", type=Path)
    parser.add_argument("app_sha")
    parser.add_argument("test", type=Path)
    parser.add_argument("test_sha")
    args = parser.parse_args()
    if args.airplane and args.save_as:
        parser.error("Choose airplane-mode search or external JPEG Save as, not both")
    if args.airplane:
        parser.error("Unattended radio switching is disabled on this LDPlayer guest; use a verified isolation and restoration witness")
    if args.save_as:
        parser.error("Unattended Save as is disabled until the exact local provider directory is proven before confirmation")
    if args.inspect_save_as:
        if not args.existing or not args.files_saved_album or args.enable_local_adb or args.airplane:
            parser.error("Save as inspection requires the retained exact synthetic Files album")
    if args.enable_local_adb and not args.existing:
        parser.error("Local-only ADB diagnosis requires the exact newly provisioned and released guest")
    if (args.files_source or args.files_saved_album) and not args.existing:
        parser.error("Files UI validation requires the released OCR guest after the initial smoke")
    if args.files_saved_album and not re.fullmatch(r"pristinepage_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}", args.files_saved_album):
        parser.error("Only the exact saved synthetic Files album may be resumed")
    if (args.startup_backfill or args.native_recovery or args.language_controls or args.manual_rerun) and not args.existing:
        parser.error("Backfill, recovery, language and rerun validation require the exact retained OCR guest")
    if args.manual_rerun is not None and not re.fullmatch(r"OCR recovery [a-f0-9]{12}", args.manual_rerun):
        parser.error("Rerun requires an exact existing synthetic recovery title")
    if args.enable_local_adb and (args.language_controls or args.manual_rerun):
        parser.error("Guest configuration is not part of language or rerun validation")
    if args.settings_restart and (not args.existing or args.previous_test_sha or args.enable_local_adb):
        parser.error("Settings restart requires the retained guest and unchanged installed APKs and guest settings")
    if args.diagnosis and (not args.existing or args.enable_local_adb):
        parser.error("Diagnosis requires only the released OCR guest")
    if args.diagnosis and args.diagnosis not in ("work", "files") and not re.fullmatch(r"OCR recovery [a-f0-9]{12}", args.diagnosis):
        parser.error("Recovery diagnosis requires an exact existing synthetic recovery title")
    if args.previous_test_sha and (not args.existing or args.diagnosis
                                  or not re.fullmatch(r"[0-9a-f]{64}", args.previous_test_sha)):
        parser.error("An exact installed test digest is accepted only for test APK updates on the OCR guest")
    if args.previous_app_sha is not None and (
            not args.existing or not args.previous_test_sha or args.enable_local_adb or args.inspect_save_as
            or any((args.files_source, args.files_saved_album, args.startup_backfill, args.native_recovery, args.language_controls,
                    args.manual_rerun, args.settings_restart, args.diagnosis))
            or not re.fullmatch(r"[0-9a-f]{64}", args.previous_app_sha)):
        parser.error("An app successor needs exact prior app and test digests and the primary Home OCR witness on the retained guest")
    root = Path.cwd().resolve()
    need(root.name in ("ocr-background-search", "ocr-current-validation") and root.parent.name == ".worktrees",
         "Use the authorized OCR worktree")
    if args.language_controls:
        raw = root / "validation/raw/providers"
        need(args.language_controls.is_dir() and args.language_controls.is_relative_to(raw)
             and args.language_controls.name == "controls", "Only the retained synthetic control directory is permitted")
    proofs = [exact_apk(args.app, args.app_sha, args.stage, False), exact_apk(args.test, args.test_sha, args.stage, True)]
    if args.preflight:
        print("Verified exact OCR APK pair; no guest mutation")
        return
    guest = args.existing or (args.recover_provision[0] if args.recover_provision else None) or "agent-pristinepage-ocr-" + uuid.uuid4().hex[:12]
    need(re.fullmatch(GUEST_PATTERN, guest), "Invalid OCR qualification guest name")
    prefix = "picker-adapter-ocr-ordinary-" + uuid.uuid4().hex[:12]
    raw = root / "validation/raw/storage" / prefix
    raw.mkdir()
    pair = {APP: args.app.resolve(), TEST: args.test.resolve()}
    hashes = {APP: args.app_sha, TEST: args.test_sha}
    owner = "pristinepage-ocr-ordinary-" + os.environ["OPENCODE_SESSION_ID"]
    ns = load_allocator(root, guest, raw, owner)
    (raw / f"{prefix}-artifacts.json").write_text(json.dumps(
        {"guest": guest, "apks": proofs, "driverSha256": sha(Path(__file__))}, indent=2), encoding="utf-8")
    run = run_type(ns)(prefix, None, 0)
    print(f"Target {guest}; evidence {raw.relative_to(root)}", flush=True)
    try:
        run.prepare(args.new, args.recover_provision[1] if args.recover_provision else None, args.enable_local_adb)
        run.witness(pair, hashes, args.new or bool(args.recover_provision), args.files_source, args.files_saved_album,
                    args.startup_backfill, args.native_recovery, args.language_controls, args.manual_rerun, args.diagnosis,
                    args.previous_test_sha, settings_restart=args.settings_restart, inspect_save_as=args.inspect_save_as,
                    previous_app=args.previous_app_sha)
        if not args.diagnosis:
            print("Observed the system JPEG picker without choosing a destination." if args.inspect_save_as else
                  "Verified search, reopen and private JPEG export of the previously saved Files scan." if args.files_saved_album else
                  "Verified the Files picker journey." if args.files_source else
                  "Verified automatic startup backfill and exact Home navigation." if args.startup_backfill else
                  "Verified terminal recovery after three observed native interruptions." if args.native_recovery else
                  "Verified exact multilingual OCR controls." if args.language_controls else
                  "Verified explicit rerun with saved and default OCR settings." if args.manual_rerun else
                  "Verified OCR defaults across an app process restart and restored the initial order." if args.settings_restart else
                  "The real import, background OCR, and exact-page Home smoke passed.", flush=True)
    finally:
        run.close_owned()


if __name__ == "__main__":
    main()
