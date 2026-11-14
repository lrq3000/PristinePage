# BootstrapPolicyTest — observed red/green evidence

AttemptT01-20261114-A,2026-11-14. This receipt concerns the small bootstrap policy only, not scanner functionality, neural execution, visual acceptance, device behavior or a complete application.

## Intended red — observed before policy implementation

Command from the primary worktree, with the approved isolated Gradle/JBR/SDK environment and only modern ANDROID_USER_HOME:

```text
gradlew.bat --no-daemon --console=plain --max-workers=4 --write-verification-metadata sha256 --write-locks :app:testDebugUnitTest --tests *BootstrapPolicyTest
```

Raw log: `validation/raw/bootstrap/bootstrap-policy-red-locking-fixed.log`. Preserved JUnit report: `validation/raw/bootstrap/bootstrap-policy-red.junit.xml`. Gradle reached actual test execution after52 tasks and exited1. A separate XML read verified **tests4, failures4, errors0**; every failure was the intended assertion `Bootstrap policy is absent: minimum platform, provider and network capabilities are not implemented.`, caused by missing `app.paperlight.BuildPolicy`. No policy source existed at that run. Infrastructure failures INC-002/INC-003 were diagnosed separately and are not included as red-test evidence.

The four test cases were `api26IsTheMinimumAndOlderPlatformsAreRejected`, `noUnapprovedModelCanBeEnabledAtBootstrap`, `noProductionNetworkDestinationIsConfigured` and `aFreshBootstrapDoesNotRequireAModelAsset`. Test-source reflection isolates this runtime boundary without Android Context or a fake policy implementation. The original JUnit result was copied before a green run could overwrite normal test outputs.

## Minimal implementation and observed green

Only after that observed red, added BuildPolicy using the catalog-derived generated minimum API, an initially empty unapproved-provider allowlist/network-endpoint set, API/provider admission methods and model-free basic launch. Added the T-01 Compose launcher with visibly disabled unavailable actions; it is not a working capture/import/edit/export journey and cannot close the whole goal. Mandatory eventual neural methods remain required by U-01.

Focused green command was the same command above and completed with exit0,54 tasks,4 tests,0 failures and0 errors. Raw log: `validation/raw/bootstrap/bootstrap-policy-green.log`; independently parsed preserved JUnit: `validation/raw/bootstrap/bootstrap-policy-green.junit.xml`. Gradle reported successful persisted locks, with actual lock files subsequently found for settings and all five modules. A failed red build was not used as lock-persistence evidence.

The subsequent module/lint/release-manifest command completed with exit0 and125 tasks; raw log `validation/raw/bootstrap/bootstrap-module-lint.log`. Its source-empty core/fixture/data/image tests are not algorithm coverage. Actual lint report has0 errors and3 warnings: OldTargetApi, ChromeOsAbiSupport and MissingApplicationIcon. Compile/target36 and the arm64 baseline are deliberate approved pins, not permission to raise SDK/change ABIs. The missing icon is an open presentation warning. Executable merged-manifest/backup assertions, packaged artifacts/signatures and all functional/device/quality gates remain separate outstanding checks at this checkpoint.
