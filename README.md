# PristinePage

Native offline Android document/photo scanner under active implementation. Confirmed future store title: **PristinePage: Doc & Photo Scan**. **This is not a delivered or accepted complete scanner.** Source now contains immutable storage/normalized decoding, the shared classical geometry/appearance renderer, a basic persistent library/editor and ordinary frozen-page render groundwork. Camera, complete export/sharing, larger editor controls, cumulative album batches and genuine neural methods remain unfinished. Current source/evidence limits are in [TODO](docs/specs/TODO.md) and [execution progress](validation/progress.md), not the historical bootstrap receipt.

## Authorities and scope

T-01/T-02 have bounded acceptance only; their [identity](validation/bootstrap/identity-receipt.md), [runtime](validation/bootstrap/ldplayer-receipt.md) and [contract](validation/contracts/t02-receipt.md) receipts remain historical. The [default library/editor](validation/editor/t09-default-library-journey-receipt.md) and [comparison](validation/editor/t09-editor-comparison-receipt.md) receipts record earlier runtime scope. The [render-intent receipt](validation/storage/export-render-intent-integration-receipt.md) records 482 fresh data JVM tests and five focused host SQLite checks on the feature branch; these are affected-module host tests, not an app-wide rerun or a completed export.

**Host and device gates are separate.** The [historical Gradle worker failure](validation/export/derived-files-blocker-receipt.md) preceded the operator's Windows restart. Its exact process identities were reconciled, and [subsequent host tests ran successfully](validation/export/derived-files-integration-receipt.md). [LDPlayer real-picker testing](validation/decode/ldplayer-post-restart-readiness-handoff.md) remains paused before installation or instrumentation, pending the separate readiness-only decision. The personal phone is excluded.

Read [AGENTS.md](AGENTS.md), [live PRD](docs/specs/PRD.md), [TODO](docs/specs/TODO.md), [pipeline contracts](docs/specs/PIPELINE_CONTRACTS.md), [accepted implementation plan](docs/specs/IMPLEMENTATION_PLAN.md) and [operational permissions](docs/specs/OPERATIONAL_PLAN.md). [Execution progress](validation/progress.md) records actual evidence and restart state. The original external v1.0 source package remains unchanged.

Both classical and multiple genuinely working neural geometry/appearance methods are mandatory under live PRD amendment U-01. Neural provider/asset/runtime selection is a later separately approved milestone after a tested classical import→edit→save→export slice. Missing or gated models cannot be disguised as implemented methods, and a registry-only/classical-only result cannot close the amended objective.

Approved identity alignment I-01/U-03 makes the executable namespace/application ID `app.pristinepage`, with development/test packages `app.pristinepage.debug` / `app.pristinepage.debug.test`; visible name remains PristinePage. Repository/worktree/cache paths retain historical names. [Identity validation](validation/bootstrap/identity-receipt.md) distinguishes the replacement's current evidence from the earlier legacy-package bootstrap receipt. Development/runtime testing uses only the exclusively assigned LDPlayer instance under P-04; the operator's phone is excluded.

## Current build baseline

Five modules: `core`, `test-fixtures`, `data`, `image-android`, `app`. Pinned configuration uses Gradle8.13, AGP8.13.2, Kotlin/Compose compiler2.3.21, KSP2.3.14, existing JBR21.0.8, SDK36/Build Tools35.0.0, minAPI26 and arm64 baseline. Java/Kotlin bytecode target17 is distinct from the JDK used to run Gradle. See the [toolchain receipt](validation/bootstrap/toolchain-receipt.md), version catalog and strict dependency locks/verification metadata. API26/27 decoder behavior and physical-device performance remain unverified; an API26 manifest minimum is not compatibility evidence.

Use process-local `JAVA_HOME` and `ANDROID_HOME` pointing to approved installed tools. Use the isolated `GRADLE_USER_HOME` in P-02 and only `ANDROID_USER_HOME` pointing to the project-local ignored `.android-user-home` preference directory; unset deprecated `ANDROID_SDK_HOME` for the build process instead of setting it to a different folder. Global settings remain unchanged. Automatic JDK/SDK provisioning is disabled. Do not infer permission to install a missing tool or acquire an unlisted dependency.

The first-acquisition commands in [P-02](validation/bootstrap/acquisition-plan.md) are historical setup, not commands to regenerate locks or verification metadata during ordinary builds. Confirm the exact Gradle distribution is already verified locally before invoking the wrapper: `--offline` alone cannot prevent a missing wrapper distribution download. The approved existing process-local environment on this host is:

```text
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
ANDROID_HOME=C:\Users\local-user\AppData\Local\Android\Sdk
ANDROID_USER_HOME=X:\local-workspace\.worktrees\paperlight-v1\.android-user-home
GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle
ANDROID_SDK_HOME must be unset for the build process
```

Run from an approved worktree only, when holding the sole reconciled test slot. Use the affected-module command in the current claim for focused checks. At a coherent app integration boundary, the normal integration command is:

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :core:test :test-fixtures:test :data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
```

Only after relevant checks pass, approved S-01 debug assembly uses `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :app:assembleDebug :app:assembleDebugAndroidTest`. Expected paths are `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`; assembly is not installation or final acceptance. These are documented commands, not newly executed checks. Do not use `clean`, forced task reruns, release signing or connected-device tasks to bypass the current blocker.

Actual retained files are still the historical Q main (`d8d80c5f…`) and compiled-but-unrun S test (`4edc1edf…`), not a current goal-complete APK. `dist/` contains only older bootstrap APKs. [Artifact reception](validation/export/derived-files-contract-readiness.md#actual-retained-artifact-reception-not-current-build-delivery) records exact hashes, sizes and stale-input limits; do not rename these into a final delivery.

## Signing, devices and release

S-01 explicitly permits the exact ignored local development key and debug/test packages in [the signing plan](validation/bootstrap/signing-plan.md); only debug uses this identity and release signing remains disabled. No global default debug key is used. The [P-02A amendment](validation/bootstrap/dependency-scope-amendment.md) records explicit exceptions for required CameraX media transitives and AGP host tools, not video-feature or blanket prerelease permission. APK assembly/verification does not authorize a connected-device task. Named-device protocols, real-photo rights, neural assets/runtimes, external sharing/publication and release decisions remain separately gated. No store listing is being published by this work.

Raw logs/build outputs and approved temporary cache/scratch remain retained but uncommitted; never clean unrelated data or worktrees. Before heavy work require at least8GiB free on C:, one build at a time, at most four Gradle workers and4GiB Gradle heap. See the live records for measured limitations and the next incomplete authorized action.
