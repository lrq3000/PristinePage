# P-02A — exact native-transitive exception proposal

Status: **explicitly approved through the P-02A form on2026-11-14**. The operator selected “Approve P-02A exact exceptions and continue”; no additional note or widened scope was supplied. Prepared for T01-20261114-A after independent T01-DEP-R and coordinator verification of cached upstream declarations. This supplements P-02's exact direct roots, not a replacement implementation plan. Earlier cache/build state is historical; authority for continued retention/use/reacquisition and packaging now comes from this explicit form approval, not from prior successful builds or a reviewer verdict.

## Actual discrepancy

P-02 explicitly approved camera-view1.6.2 and required stable transitives, but separately excluded video artifacts. The approved camera-view module declares nonoptional camera-video1.6.2, which in turn requires the media3 chain below. AGP8.13.2 also natively depends on the named prerelease Jetifier/UTP host tools, conflicting with the stable-only transitive wording. Earlier approved Gradle invocations already populated these cache/verification/lock records before the contradiction was detected. This is not a claim that those components were separately approved or that any video feature was requested. No APK has yet been assembled.

The [independent audit](../reviews/t01-dependency-audit.md) retains exact cached locators and roles. Coordinator verified camera-view's module dependency, camera-video's media dependencies, AGP's Jetifier/core-proto declarations and exact lock/verification entries. Do not blindly exclude required transitives or relabel alpha/beta as stable. No direct root/version or repository changes are proposed.

## Exact proposed exception

Permit continued retention, use and any required reacquisition from the already-approved Google/Central origins of only these native dependencies, at these exact versions, within their approved-root chains:

- Runtime dependency of approved camera-view: `androidx.camera:camera-video:1.6.2`.
- Its media dependencies: `androidx.media3:media3-common:1.9.0`, `androidx.media3:media3-container:1.9.0`, `androidx.media3:media3-muxer:1.9.0`.
- AGP host-only Jetifier: `com.android.tools.build.jetifier:jetifier-core:1.0.0-beta10`, `com.android.tools.build.jetifier:jetifier-processor:1.0.0-beta10`.
- AGP host/build/test infrastructure, all group `com.google.testing.platform`, version `0.0.9-alpha03`: `android-device-provider-local`, `android-driver-instrumentation`, `android-test-plugin`, `core-proto`, `core`, `launcher`.

This is not blanket prerelease permission. Jetifier/UTP must remain host-only, not app runtime dependencies. Camera/video/media libraries may be present as required implementation dependencies; the app still implements only the approved still-photo scanning scope, no video recording UI/feature or video permission request. Continue SHA-256 verification and lock inspection; exact identities remain in gradle/verification-metadata.xml and module lockfiles. Clean-cache replay and publisher authenticity are not established by generated hashes.

## Changes and commands after approval

Record the exact approval in this file, OPERATIONAL_PLAN.md and progress/TODO. Retain unchanged catalog/direct versions/approved repositories. Resolve code-review findings, finish dependency notice records from cached POM/embedded notices, then run the bounded incremental module/privacy/lint checks and approved S-01 debug assembly. Exact build envelope:

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :core:test :test-fixtures:test :data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

If a missing required build artifact prevents offline operation, reacquire only within P-02 plus these exact P-02A exceptions, write/review necessary verification/lock updates, then return to offline strict checks. No new direct root or version is authorized. Existing S-01 permits only the named local development key/packages, not production signing.

Verify the resulting APK with installed Build Tools35.0.0 apksigner, aapt2 and ZIP/native inventory: signature/certificate, SHA-256, package/version/min/targetSDK, merged/packaged network and backup policy, resource/domain exclusions, ABI and unexpected runtime/model contents. Retain actual logs and findings, obtain independent scoped artifact review, and commit coherent verified local milestones. These checks do not establish launch/device/offline/fidelity acceptance.

Writes remain approved worktree source/authority/notice/lock/verification files, ignored build/raw/dist outputs and the existing isolated temporary build cache. Check C: free space before heavy work; pause below8GiB. No file deletion, cache cleanup or overwrite of unrelated data is included. No device installation/launch/instrumentation, production signing, neural runtime/model, SDK/JDK/NDK/emulator install, spending, publication, external sharing or remote Git operation is included. The development key already created under S-01 remains ignored; it is neither deleted nor exposed by this proposal.

Alternative if zero video binaries or zero prerelease host binaries is a strict requirement: hold affected builds and inspect a supported alternate preview/toolchain arrangement read-only, then present its concrete revised bill for separate approval. Do not pretend exclusions are safe, install another toolchain or delete the current cache without a named plan. Independent nonaffected work may continue; T-01 artifact acceptance stays open.
