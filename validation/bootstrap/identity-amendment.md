# I-01 — approved PristinePage internal identity alignment

Status: **explicitly approved through the I-01 form on2026-11-15**. After questioning the legacy internal Paperlight name, the operator selected canonical spelling **PristinePage** (not PrestigePage) and then “Approve app.pristinepage migration”. This records the approved small design/execution amendment to T-01, not a replacement for docs/specs/IMPLEMENTATION_PLAN.md. No product feature, toolchain version, asset/runtime permission or source algorithm changes.

## Design and affected identities

Use `app.pristinepage` for Kotlin package/Android namespace/application ID and root Gradle group; debug application ID `app.pristinepage.debug`, instrumentation ID `app.pristinepage.debug.test`, library namespaces `app.pristinepage.data` and `app.pristinepage.image`. Visible app name remains PristinePage and future title remains `PristinePage: Doc & Photo Scan`. Internal JVM test-input keys use the pristinepage prefix. Existing API26/target36/arm64, Java17 bytecode, empty initial provider/network policy and backup exclusions do not change.

Considered alternatives: retain legacy package (least edit but contradicts the explicit request); align executable identifiers only (selected); rename repository/worktree/cache paths too (unrelated path/claim/build-cache/signing risk with no APK benefit). Preserve approved working directories and cache names, immutable original/root seed, accepted-plan bytes and genuine historical artifacts/reports. Existing v1 plan/domain references to code prefixes `app.paperlight` / `app/paperlight` are interpreted as the new source prefix under live PRD U-03; they do not authorize legacy packages in future code. File names, tasks, algorithms and cross-module interfaces remain unchanged.

Exact code/config changes: move/update `app/src/main/kotlin/app/paperlight/{BuildPolicy,MainActivity}.kt` to app/pristinepage; move/update `app/src/test/kotlin/app/paperlight/{BootstrapPolicyTest,ManifestPolicyTest}.kt` likewise; update root/app/data/image-android build.gradle.kts group/namespaces/application ID and the test-input keys. Keep release signing null and reuse the exact existing ignored S-01 development key/certificate; no regeneration or key publication.

I-01 explicitly extends/supersedes S-01/P-04 **package targets only** with app.pristinepage.debug / app.pristinepage.debug.test and the newly verified same-signer worktree artifacts. The old SHA387217986018 bootstrap and legacy package review remain historical and must not be installed after this amendment. A changed application ID is a separate Android app; no user-data migration, existing-app adoption/replacement, uninstall, data clearing or phone operation is approved. P-04's exclusive released-instance/resource/boot/target/shutdown rules are unchanged; no emulator has yet been claimed/launched for this project.

## Approved test-first sequence and expected checks

1. Update/move tests first. Run the focused BootstrapPolicyTest using the approved offline strict environment. Expected RED: four missing app.pristinepage.BuildPolicy assertions against the still-legacy production class, zero infrastructure errors. Preserve original XML/log before the next run.
2. Move production classes and update build identities/property keys; keep all unrelated behavior and dependency locks/verification bytes unchanged.
3. Run normal incremental focused/full module unit tests, app lint/debug assembly and release merged manifest. Expected GREEN: all actual app tests pass; source-empty modules remain explicitly not algorithm coverage. No forced rerun/clean/SDK acquisition.
4. Verify actual APK signer/hash, label/package/SDK/launcher, new class definitions/no legacy class prefix, compiled backup/network policy, native ABI and ELF/ZIP16KiB static alignment. Compare actual package/version metadata to current build output, not a fixed project release assertion. Preserve a uniquely hash-named replacement in ignored dist; retain older artifacts without relabeling them.
5. Obtain independent scoped code/artifact review, resolve consequential findings, update the active authorities/receipts and commit the verified identity milestone. Previous review is historical for the changed artifact, not automatically inherited.
6. Resume P-04 only with the verified replacement and new package targets. Do not query or access the excluded phone. Missing emulator capabilities or unexpected installed data remain explicit blockers, never guessed around.

Commands from the existing primary worktree with existing JBR/SDK/cache, only ANDROID_USER_HOME and the8GiB disk guard:

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :app:testDebugUnitTest --tests *BootstrapPolicyTest
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :core:test :test-fixtures:test :data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:processReleaseMainManifest
```

Existing Build Tools35.0.0 apksigner/aapt2/zipalign and bounded standard-library ZIP/DEX/ELF inspection provide static evidence. No dependency/root/version change, model/runtime/SDK install, repository relocation, automatic deletion, external sharing, production signing, push or merge. Active record paths include root AGENTS/README, live PRD/ARCHITECTURE/OPERATIONAL_PLAN/TODO/traceability/decisions and validation progress/signing/LDPlayer/new artifact receipts; preserve the accepted implementation plan and archived evidence unchanged.
