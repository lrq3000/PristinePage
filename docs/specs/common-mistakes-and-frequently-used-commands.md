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
