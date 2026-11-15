# T-01 / P-04 — LDPlayer bootstrap runtime receipt

Observed2026-11-15 against source commit `b3759b262a6af96d328b34b6e7f5fd8949a00c60`, primary worktree `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`. This records the explicitly approved P-04/I-01 bootstrap install/launch check, not acceptance of a working scanner. Runtime evidence and graceful player/VM shutdown are verified locally; [independent runtime review](../reviews/t01-runtime-review.md) passed the bounded scope with Minor evidence limitations. The coordinator accepts only T-01 bootstrap, not any full scanner requirement.

## Target, ownership and admission

Existing LDPlayer9.5.39.0 installation `C:\LDPlayer\LDPlayer9`; exclusively reclaimed the explicitly released instance `LOCAL_TEST_INSTANCE`, observed index2. Existing host-wide registry/atomic allocation lock were reused, with owner `LOCAL_SESSION` and fresh generation `2cb82541-3299-43cd-a861-d678efd3d034`. Prior history/guest disks were retained; no cloning, instance creation or settings/root changes. Allocation remained serialized through bounded readiness, then the short lock was released while usage ownership remained exclusive.

Live prelaunch admission:38.17GiB available physical RAM,17.62GiB free C:, exceeding the4GiB/8GiB guards. Native index-scoped discovery observed `emulator-5558`, never inferred from index arithmetic. Verified ADB device state, sys.boot_completed=1 and package-manager readiness; API28, primary x86_64, advertised arm64-v8a support through `libnb.so`. App package manager selected primaryCpuAbi=arm64-v8a. This is translated emulator execution, not physical ARM64 or API26/current-API device coverage. Captured player PID/start45008/134392084866433998 and VM37824/134392084933851646. Bundled ADB and existing server protocol both41; no server restart/connect/disconnect or phone command.

## Actual installation and launch observations

- Current package was independently confirmed absent before installation by a successful package-filtered listing; no replacement/adoption, uninstall or data migration.
- Installed only `dist/pristinepage-0.1.0-debug-i01-19595f091f69.apk`,16,779,849bytes, SHA-256 `19595f091f69eda19fb7d05450c55160ba32c598ab6ee68b4d1d703be16557a7`. Installed `/data/app/.../base.apk` SHA-256 independently matched these exact bytes; the previously verified signature is therefore the same S-01 certificate. Actual installed version/package/components were compared with current AGP output metadata rather than fixed project-version assertions.
- `am start -W` returned Status:ok for `app.pristinepage.debug/app.pristinepage.MainActivity`; the same activity was resumed, app PID4823 remained present during observations, stopped=false/notLaunched=false. No duplicate launch was performed while reconciling assertion mismatches below.
- App-scoped hierarchy and720×1280 PNG show PristinePage, the offline-scanning subtitle, explicit development/unavailable notice and disabled Import photos/Take photo controls. The nearest actionable Compose nodes are disabled; text labels themselves are not actionable controls. Both control bounds are648×72pixels; no general accessibility/TalkBack/large-font gate is inferred from this one screen.
- Retained at most160 app-PID log lines and40 crash-buffer lines without global log clearing. Actual retained app log has102 lines plus buffer header; app-PID crash output is empty and no FATAL EXCEPTION was observed. Translation/nativebridge warnings, EGL errors/failed swap-behavior setting and Android9 hidden-API warnings are present, not concealed as a warning-free run. The screenshot renders; no claim about unexercised JNI pipelines or long-term stability follows.
- No camera grant/refusal stimulus, input/capture/import, personal photograph, account, sharing recipient, app-data clearing, guest network/airplane-mode change or host-camera passthrough. Package dump records CAMERA as requested and an empty user runtime-grant section; no explicit camera-permission probe is claimed. No actual fresh-install airplane-mode acceptance was performed.

## Diagnostic guard corrections, not application defects

1. The first preinstall guard stopped before any install because direct ADB propagates `pm path` exit1 for an absent package, while ldconsole's wrapper returned0 with the same empty output. A successful package-filtered listing was empty and `pm path android` succeeded; only then was first installation performed. Do not interpret wrapper exit0 alone as package presence/absence or package-manager health.
2. The first UI assertion checked enabled on the Import photos text child rather than its nearest clickable ancestor. Read-only hierarchy inspection and screenshot established that the parent action is disabled; saved hierarchy validation was corrected without changing app code or repeating launch.
3. An assertion expecting an explicit CAMERA:granted=false line stopped because this API28 dump omits ungranted runtime entries. No denial/grant transition is inferred; the receipt records only no grant operation and no grant recorded. An exploratory `pm help` returned255 and was not retried or used to mutate permissions. These guard failures did not trigger root/settings repairs or application changes.

## Commands and retained evidence

All Android commands used the observed owned index or verified `-s emulator-5558`; absolute existing console/ADB paths were used. Per-command deadlines were bounded; installation90seconds, activity start35seconds, UI dump25seconds, readiness180seconds. Representative actual command arguments:

```text
ldconsole.exe adb --index 2 --command "get-serialno"
adb.exe -s emulator-5558 shell pm list packages --user 0 app.pristinepage.debug
adb.exe -s emulator-5558 install X:\local-workspace\.worktrees\paperlight-v1\dist\pristinepage-0.1.0-debug-i01-19595f091f69.apk
adb.exe -s emulator-5558 shell dumpsys package app.pristinepage.debug
adb.exe -s emulator-5558 shell sha256sum <observed-installed-base.apk-path>
adb.exe -s emulator-5558 shell am start -W -n app.pristinepage.debug/app.pristinepage.MainActivity
adb.exe -s emulator-5558 shell dumpsys activity activities app.pristinepage.debug
adb.exe -s emulator-5558 exec-out uiautomator dump /dev/tty
adb.exe -s emulator-5558 exec-out screencap -p
adb.exe -s emulator-5558 logcat -d --pid=4823 -t 160 -v threadtime
adb.exe -s emulator-5558 logcat -d -b crash --pid=4823 -t 40 -v threadtime
```

Binary screenshot stdout was captured directly in Python, avoiding PowerShell text-redirection corruption and temporary guest files. Regenerable/raw evidence remains ignored under `validation/raw/bootstrap/ldplayer/`: boot.json, install.json, install-output.txt, package-before-launch.txt, appops-before-launch.txt, activity-start.txt, activity-app.txt, activity-app-after-check.txt, bootstrap-ui.xml, bootstrap-screen.png, app-pid-logcat.txt, app-pid-crash.txt, package-after-launch.txt and launch.json. `evidence-manifest.json` retains hashes/lengths of these14 files. Main inspected the screenshot directly; this is engineering bootstrap inspection, not human real-page visual acceptance.

## Shutdown and remaining gates

After all consumers ended and the14 evidence files' hashes/lengths were checked, acquired the existing allocator, revalidated exact ownership/generation/process-start identities and atomically published current shutdown_ready/evidence_saved plus a cleanup-attempt claim. Saved both original identities before `ldconsole.exe quit --index 2`. By2026-11-15T09:39:29.685403+00:00, list2 reported Android stopped/player−1/VM−1, isrunning returned `stop`, and both saved PID/start identities had exited. Only then released usage, completed the cleanup attempt and released the allocation lock. `validation/raw/bootstrap/ldplayer/shutdown.json` retains the actual proof; shared state is stopped-released/owner:null/reservedKiB:0.

Final inventory of the known installation lists all three instances stopped; unrelated indices0/1 were preserved without a quit, install or data operation. No known shared-registry unresolved launch/shutdown reservation was found. Coverage is this installation and registry, not a host-wide assertion across inaccessible accounts/backends. No instance removed, no force-kill, no automatic file cleanup. Candidate guest disk and installed development package remain retained; no disk removal is authorized. Every later reuse requires a fresh claim/admission check.

Independent T01-RUNTIME-R returned bounded spec-compliance PASS/evidence-quality PASS WITH MINOR LIMITATIONS and no Critical/Important finding. Coordinator independently rechecked all14 evidence hashes, reviewer anchors, source/dist APK hashes, saved9-test results, unchanged source/accepted-plan bytes and released current registry state without replaying device operations. T-01 is accepted only for reproducible foundation/policy/privacy configuration and actual capability-free bootstrap launch/recorded cleanup; T-02 is the next unclaimed task.

RT-1 disposition: retain the limitation that readiness/admission/absence/installed-hash/process-exit decisions are structured observations without individual command transcripts. This is corroborated evidence, not an independently rerun guest/host inspection or exhaustive command-history proof. Future approved runs should retain bounded argv/exit/timestamp/stdout and PID-start inspection outcomes; no emulator restart is justified solely to retrofit transcripts. RT-2 disposition: preserve the original pre-shutdown manifest without rewriting historical evidence; the reviewer and this tracked receipt anchor manifest SHA-256 `d1183d7b97269fdd776eb7615a15d453df32496bfb960ccf6d7acbc851a36312` and later shutdown.json SHA-256 `6eec5141dc8c0e58ee2c0fc675f1092ba709f415966cf26773a29cfdcc0f55c5`, both independently rechecked. Neither Minor is silently represented as repaired historical coverage.

Complete scanner, neural implementations/assets, representative-page fidelity, physical/OEM/API26/current-API, resource/performance, offline journeys, export/persistence and rights/distribution gates remain open. Reviewer-declined camera permission/refusal, test-APK execution, rotation/background/process-death, TalkBack/large-font, broad security and physical-device behaviors remain explicit unperformed dependent gates, not dropped requirements. Existing rear-camera filtering and missing launcher-icon minors remain disclosed. The excluded personal phone is not an alternative test target. No merge/publication approval follows from T-01 acceptance.
