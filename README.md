# PristinePage

Native offline Android document/photo scanner under active implementation. Confirmed future store title: **PristinePage: Doc & Photo Scan**. This repository is not yet a delivered scanner. T-01 has a signed app.pristinepage.debug bootstrap with9 passing policy/privacy tests and inspected signature/package/resources. The exact reviewed APK was installed and launched in an exclusively claimed LDPlayer API28 instance; expected bootstrap UI and scoped logs were inspected, then player/VM shutdown was verified. Import/capture actions remain intentionally unavailable. See the [current identity/artifact receipt](validation/bootstrap/identity-receipt.md) and [LDPlayer runtime receipt](validation/bootstrap/ldplayer-receipt.md); the [earlier legacy-package artifact](validation/bootstrap/apk-receipt.md) remains historical. No complete scanner, physical-device, airplane-mode or visual-quality acceptance is claimed.

## Authorities and scope

Read [AGENTS.md](AGENTS.md), [live PRD](docs/specs/PRD.md), [TODO](docs/specs/TODO.md), [pipeline contracts](docs/specs/PIPELINE_CONTRACTS.md), [accepted implementation plan](docs/specs/IMPLEMENTATION_PLAN.md) and [operational permissions](docs/specs/OPERATIONAL_PLAN.md). [Execution progress](validation/progress.md) records actual evidence and restart state. The original external v1.0 source package remains unchanged.

Both classical and multiple genuinely working neural geometry/appearance methods are mandatory under live PRD amendment U-01. Neural provider/asset/runtime selection is a later separately approved milestone after a tested classical import→edit→save→export slice. Missing or gated models cannot be disguised as implemented methods, and a registry-only/classical-only result cannot close the amended objective.

Approved identity alignment I-01/U-03 makes the executable namespace/application ID `app.pristinepage`, with development/test packages `app.pristinepage.debug` / `app.pristinepage.debug.test`; visible name remains PristinePage. Repository/worktree/cache paths retain historical names. [Identity validation](validation/bootstrap/identity-receipt.md) distinguishes the replacement's current evidence from the earlier legacy-package bootstrap receipt. Development/runtime testing uses only the exclusively assigned LDPlayer instance under P-04; the operator's phone is excluded.

## Current build baseline

Five modules: `core`, `test-fixtures`, `data`, `image-android`, `app`. Pinned configuration uses Gradle8.13, AGP8.13.2, Kotlin/Compose compiler2.3.21, KSP2.3.14, existing JBR21.0.8, SDK36/Build Tools35.0.0, minAPI26 and arm64 baseline. Java/Kotlin bytecode target17 is distinct from the JDK used to run Gradle. Actual bootstrap policy/privacy tests and module/lint checks have run; see [toolchain receipt](validation/bootstrap/toolchain-receipt.md). Empty module test tasks are not scanner coverage, and build evidence is not device or final acceptance.

Use process-local `JAVA_HOME` and `ANDROID_HOME` pointing to approved installed tools. Use the isolated `GRADLE_USER_HOME` in P-02 and only `ANDROID_USER_HOME` pointing to the project-local ignored `.android-user-home` preference directory; unset deprecated `ANDROID_SDK_HOME` for the build process instead of setting it to a different folder. Global settings remain unchanged. Automatic JDK/SDK provisioning is disabled. Do not infer permission to install a missing tool or acquire an unlisted dependency.

The first approved dependency/policy-test run is:

```text
gradlew.bat --no-daemon --console=plain --max-workers=4 --write-verification-metadata sha256 --write-locks :app:testDebugUnitTest --tests *BootstrapPolicyTest
```

The intended red failure must be missing bootstrap policy behavior, not a repository, compiler or configuration error. After implementation, obtain a successful green run covering resolved module test configurations, verify persisted locks and dependency hashes, then use normal strict-verification offline incremental commands. See [P-02](validation/bootstrap/acquisition-plan.md) for exact allowed roots/origins/commands and the first-wrapper checksum exception. A failed red build is not lock-persistence evidence.

## Signing, devices and release

S-01 explicitly permits the exact ignored local development key and debug/test packages in [the signing plan](validation/bootstrap/signing-plan.md); only debug uses this identity and release signing remains disabled. No global default debug key is used. The [P-02A amendment](validation/bootstrap/dependency-scope-amendment.md) records explicit exceptions for required CameraX media transitives and AGP host tools, not video-feature or blanket prerelease permission. APK assembly/verification does not authorize a connected-device task. Named-device protocols, real-photo rights, neural assets/runtimes, external sharing/publication and release decisions remain separately gated. No store listing is being published by this work.

Raw logs/build outputs and approved temporary cache/scratch remain retained but uncommitted; never clean unrelated data or worktrees. Before heavy work require at least8GiB free on C:, one build at a time, at most four Gradle workers and4GiB Gradle heap. See the live records for measured limitations and the next incomplete authorized action.
