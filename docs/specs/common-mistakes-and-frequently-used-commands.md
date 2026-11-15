# Common mistakes and frequently used commands

Read this file at task start and after resumption. These are anticipated pitfalls unless an actual implementation incident is recorded below; they are not invented past application bugs.

## Avoid these errors

- Inferring neural capability from an available file or treating restored RGB as an illumination divisor or inverse grid.
- Mixing RGB/BGR, NHWC/NCHW, Lab8 lightness and linear luminance, EXIF display rotation and source-pixel orientation, or normalized corner/edge coordinates.
- Mixing corner-aligned learned-grid interpolation with half-pixel resizing; applying a second warp to an image-output provider.
- Applying strength/protection before clipping when the recipe specifies candidate-then-blend, or blending twice in adapter and orchestration.
- Sharpening/denoising by default to compensate for poor illumination estimates; clipping faint content before a later supposed restoration step.
- Treating preview processing as a full-resolution export oracle; using an arbitrary tile for a global-context algorithm without equivalence evidence.
- Updating a document's current recipe/order while an export rereads it; publishing a result whose generation no longer matches.
- Treating SAF URI access as a durable private copy; assuming provider rename/delete is atomic; reporting a partial external output as complete.
- Assuming moving a file and updating Room is one transaction. Use journals and reconcile both sides after interruption.
- Treating owner-free ADMITTED as permission for an old ENDED/UNKNOWN runner to execute again. Require a currently ALIVE scoped requester before lease acquisition writes, separately from resource admission and old-owner positive-termination evidence.
- Assuming WorkManager runs immediately or completes after force-stop. Show pending/interrupted state truthfully.
- Packaging unresolved/research-restricted weights under a permissive code license; installing both inference runtimes before a selected provider requires them.

## Commands after implementation bootstrap

The future application workspace must contain its own Gradle wrapper before these commands are valid. On Windows invoke `gradlew.bat`; on POSIX invoke `./gradlew`. Never invent a successful run from the existence of these examples.

```bash
./gradlew :core:test
./gradlew :data:testDebugUnitTest
./gradlew :image-android:testDebugUnitTest
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For physical-device tests select an explicit authorized ADB serial and preserve it in the test record. Build first, then run the appropriate connected test task against that target; never install/test on every connected device implicitly. Use normal incremental tasks, not `clean` or `--rerun-tasks` by default. Signing, installs, emulator launches and publication require the operator's actual authorization.

## Incident record template

Record incident ID/date/build/device, reproducible symptom, known cause versus hypothesis, affected data/contracts, smallest reproduction, attempted commands, verified remedy, regression test/evidence, remaining limits and impacted tasks. Do not paste photos, URIs containing private names, credentials or full sensitive logs into this file.

No application implementation incident was recorded at package creation. The entries below are actual implementation-preparation observations, separate from that historical receipt.

### INC-001 — source receipt filename-order convention

Date 2026-11-14; attempt T01-20261114-A; no app build/device involved. The first setup guard computed technical-content SHA-256 `823ddf2d31a203ae5ea9c913753f365fd8ee59b1b79569f923a85ceea2e6885b` and stopped before creating any files, rather than copying a package with an unexplained identity mismatch. Root cause: Python code-point filename sorting differs from the receipt's case-insensitive filename ordering for the lowercase incident/working-file names. Holding file bytes/hash-stream encoding constant, case-insensitive ordering reproduced the documented `54f5d3f72134075b40ca178f8e68f39a626352de2632b2e7db4fe598aba263c3`; the source files had not changed. Corrected the setup guard to use `name.casefold()` and verified all 24 copied files plus unchanged original hashes. This was a provenance-check convention issue, not a passed application regression test or an amendment to SPEC. Retain both digest observations; future package-identity checks use the recorded explicit ordering.

### INC-002 — conflicting Android preference-home overrides

Date2026-11-14; attemptT01-20261114-A; Gradle8.13/AGP8.13.2/JBR21.0.8; no device involved. The first policy-test build failed during AGP application, before JUnit, with AndroidLocationsBuildService/AndroidDirectoryCreator initialization errors. A single bounded `help --stacktrace` diagnostic retained in `validation/raw/bootstrap/android-home-diagnostic.log` identified the exact cause: `AndroidLocationsException: Several environment variables and/or system properties contain different paths to the Android Preferences folder`, listing process-local ANDROID_SDK_HOME and ANDROID_USER_HOME pointing at different ignored worktree directories. This is infrastructure failure, not the intended policy red test.

Verified supported remedy: set only `ANDROID_USER_HOME` to the approved ignored `.android-user-home` directory and unset deprecated `ANDROID_SDK_HOME` in the build process; keep SDK location in the distinct `ANDROID_HOME` variable. No global environment or user SDK was modified. The focused rerun advanced through AGP setup and7 tasks to dependency resolution, proving the directory conflict was removed, but then encountered the separate INC-003 configuration issue; JUnit still did not run. Evidence: `validation/raw/bootstrap/bootstrap-policy-red-home-fixed.log`. Do not reintroduce both preference-home aliases with different paths or count configuration failure as an application test result.

### INC-003 — incompatible dependency-resolution modes

Date2026-11-14; Gradle8.13; attemptT01-20261114-A. After INC-002 was corrected, `:app:checkDebugAarMetadata` failed before tests with `Both dependency locking and fail on dynamic versions are enabled. You must choose between the two modes.` Root build configuration had combined strict `lockAllConfigurations()` with the alternative fail-on-version strategy calls. Selected the supported strict-locking mechanism already required by P-02 and removed that redundant alternative strategy block; direct catalog pins, approved-root review and SHA-256 verification remain in force. The inspected Gradle8.13 dependency-locking guide documents `lockAllConfigurations` and successful lock writing; no repository/version/verification scope was widened. Verified remedy: the corrected run reached4 expected absent-policy JUnit assertions; after minimal implementation the focused run passed4/4 and persisted locks. The later offline strict module/manifest/wrapper run passed8/8 app tests. See validation/bootstrap/policy-test-receipt.md and toolchain-receipt.md; neither infrastructure failure was counted as the intended policy red.

### INC-004 — Git normalization changes audited metadata bytes

Date2026-11-14; after verified bootstrap milestone efa7ae6. Git staged Gradle's CRLF verification XML and six lockfiles as normalized LF blobs, even though the generated notice inventory retained exact SHA-256 fingerprints of the observed working bytes. XML/dependency semantics and the built APK were unchanged, but a post-commit byte-equality assertion failed for all seven files; the earlier legal-snapshot guard had not covered these additional audit inputs. Added narrowly scoped no-text-normalization attributes for the seven metadata/lock paths and allow only their CR-at-EOL whitespace in diff checking. Restage the original observed bytes and verify actual index blobs equal them before a separate additive reproducibility commit; never amend/rewrite the existing milestone or regenerate the audit to conceal the difference. Gradle may legitimately rewrite line endings on another host; that is a changed audit input requiring a new snapshot, not an authenticity failure or permission to change dependencies.

### INC-005 — LDPlayer/API28 diagnostic-output assumptions

Date2026-11-15; sourceb3759b2; P-04 owned LDPlayer/API28 bootstrap validation. Three command/assertion assumptions stopped their batches without an application defect: direct ADB propagates pm-path exit1 for absent packages while ldconsole wraps it as0; Compose enabled text children are not the disabled clickable parent action; ungranted CAMERA can be omitted from the runtime-permission dump instead of shown as granted=false. Reconciled each against actual successful package-manager/list output, saved hierarchy/screenshot and complete package dump; no app source, guest permissions/settings, root or server changes, no repeated install/launch. For first-install absence use a successful package-filtered listing with exact package checks, verify both output and exit status, and inspect the nearest actionable UI node. Omitted permission entries are not an explicitly performed grant/refusal probe. The retained [runtime receipt](../../validation/bootstrap/ldplayer-receipt.md) separates observed launch success from untested scanner/offline/physical gates. Exploratory pm-help returned255 and was not retried; do not guess unsupported commands or turn tool mismatches into app fixes.

### INC-006 — exited Windows process objects mistaken for live emulator processes

Date2026-11-15; source935cd49 plus data Room instrumentation; P-04 generation68b9cf6d-0ca7-454b-bfa6-7a0eb51785e6. Five real Room cases passed, then targeted graceful quit reached stopped list2 state but the60-second shutdown verifier reported failure because OpenProcess/GetProcessTimes still returned the original PID/creation identity. Read-only Windows wait/exit-code diagnosis showed both original objects signaled/exitCode0; an exited process object may remain openable while references exist. Creation time distinguishes reused identity, not running state. Future Windows lifecycle verification must additionally use WaitForSingleObject(handle,0), GetExitCodeProcess or equivalent real exit proof, treating access denial as unknown; do not resend quit or force-kill from this false negative. Retained shutdown_failed until a new same-generation cleanup-only verification observed both original PIDs gone, stopped console/isrunning and then released ownership. No runtime/application fix, second quit, force-kill or data deletion was performed. [Room runtime receipt](../../validation/storage/t03-room-runtime-receipt.md) retains actual boundaries/evidence and all unperformed process-death/physical gates.

### INC-007 — scoped API28 package dump omits instrumentation registration

Date2026-11-15; SourceStore testAPK a5edfc630f2fc4bd0204425012c41eec7d2876aea156ce20d303232ea4da26fa, P04 generation5fddc483-2606-473e-a9d1-8b9fab496569. Install-r and installed-byte identity succeeded, but a test-harness assertion expected AndroidJUnitRunner/target fields inside package-specific dumpsys; that API28 output omitted the instrumentation section and stopped before tests. Verified supported narrower query pm list instrumentation app.pristinepage.debug.test returned the exact registered runner/target, alongside the already verified APK manifest/hash/version. Continued without reinstall, code/settings change or broader package dump; five named native Room cases then passed. This was a diagnostic-output assumption, not an app regression or initially executed failing device test. [Current flow receipt](../../validation/storage/t03-source-runtime-receipt.md) retains both commands and scope.

### INC-008 — Kotlin backup cleanup failure and PowerShell property splitting

Date2026-11-15; T03 retry increment after148ae5a, Kotlin/KGP2.3.21/JBR21.0.8/Windows. Normal post-fix integration failed in app Kotlin compilation at a temporary kotlin-backups path; one unchanged diagnostic advanced through192 passing data tests but failed in app unit-test compilation with DirectoryNotEmptyException in RecoverableCompilationTransaction.cleanupStash/close/tryCompileIncrementally. Both reported directories were later present/empty; no manual removal or identified antivirus/OS causation. Full gate remains open until a successful actual check. [Retry receipt](../../validation/storage/t03-ingest-attempt-receipt.md) retains both logs and investigation.

Official Kotlin compilation/caches documentation supports command-line -Pkotlin.incremental=false, with extra compilation and incremental-cache invalidation; use only as an explicit scoped diagnosis, not permanent config or a proven filesystem repair. The first fallback did not reach compilation because this PowerShell parsed unquoted -Pkotlin.incremental=false as two native arguments. A tiny Python argv probe reproduced ['-Pkotlin','.incremental=false'] versus the quoted single argument ['-Pkotlin.incremental=false']. Quote dotted Gradle project/system-property arguments, or use an explicit argument array through a robust runner. No clean/forced-task flag, manual cache deletion, dependency/tool acquisition or global change was used. Corrected quoted fallback is pending at this entry; its actual outcome must be recorded separately.

Corrected quoted fallback sh_1e02ab59c0014V3GJ6K7gnPseZ subsequently completedexit0/168tasks/65s;239 JVM tests passed, testAPK packaged and lint0errors/3existingwarnings. This verifies the scoped nonincremental workaround for this run, not a causal filesystem repair or permanent permission to disable incremental builds. Recompilation exposed three warnings in unchanged DirectorySync/FileOperationOwnersTest/RepositoryContractTest code; receipt retains them. Build configuration/dependency/schema/accepted-plan bytes remain unchanged. Retain both original failure logs/temporary empty folders, and prefer ordinary incremental execution again when investigating subsequent builds; no deletion is authorized.

Subsequent symptom2026-11-15 during reference-audit fixes after69b1906: harness reported server-restart cancellation, but retained log independently records app:compileDebugUnitTestKotlin/BuildToolsApiCompilationWork failure naming kotlin-backups14771475623320487654 after80s/121tasks. No surviving build process;250fresh data XMLs retained and stale app output not counted. One unchanged targeted compilation diagnostic passed25s/51tasks, then ordinary full incremental verification passed49s/168tasks with297recorded current JVM cases/current testAPK. No cache cleanup/config/dependency/incremental-disable change. No proof server restart, antivirus or the prior cleanup exception caused this event; [audit receipt](../../validation/storage/t03-reference-audit-receipt.md) retains distinctions and the named temporary directory, not a proven filesystem repair.

### INC-009 — Gradle test-worker parent loopback timeout

Date2026-11-15; provider resumption aftere16ab8c, normal Gradle8.13/JBR21.0.8 build. Integration executed199 passing data tests then app Gradle Test Executors2/3 timed out connecting to their parent127.0.0.1:62223/62234 (ConnectException/getsockopt), before fresh app JUnit. Previous app XML was stale, so it was not counted. Retained all23 fresh data XMLs/hashes before one targeted unchanged app:testDebugUnitTest/app:lintDebug diagnostic, which passed9freshappcases/lint in31s/126tasks. A basic Python loopback probe passed but did not establish Java child connectivity or causation. No firewall/server/global environment/source/config/dependency/cache change was made. This is distinct from INC008's compilation cleanup exception; no established application assertion defect or proven system repair follows. Final post-review normal168-task integration later passed248JVMtests/54s. [Provider receipt](../../validation/storage/t03-provider-resumption-receipt.md) retains the failed log, successful targeted log, composed pre-review246 evidence and final successful command separately.
