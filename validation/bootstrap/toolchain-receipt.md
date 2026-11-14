# T-01 observed toolchain and privacy checks

Date2026-11-14, attemptT01-20261114-A, branch feat/paperlight-v1, baseline preparation commit434d58f. This is bootstrap evidence only, not device or complete-scanner acceptance. No SDK/JDK/NDK/emulator or neural runtime/model was installed.

## Versions and identity

Gradle8.13 revision073314332697ba45c16c0a0ce1891fa6794179ff was acquired under P-02 using the publisher-pinned ZIP SHA-25620f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78, after an absent target cache and publisher-verified bootstrap wrapper were checked. The8.13 wrapper was subsequently regenerated and publisher-hash verified; see wrapper-receipt.md. Embedded Kotlin2.0.21 is Gradle's DSL runtime, not the project compiler.

Project pins are AGP8.13.2, Kotlin/Compose compiler2.3.21, KSP2.3.14, Compose BOM2026.03.01, compile/target36, minimum26, required arm64-v8a and Java/Kotlin bytecode17. Existing build JVM is JetBrains JBR21.0.8+-14196175-b1038.72, sourcegit:c9af5651efce. Existing SDK platform36 reports revision2, extension17; existing Build Tools35.0.0 is used. KSP plugin resolution is not proof of actual Room schema generation, which remains T-03 work.

Read-only SHA-256 observations of existing local tools (identity, not independent publisher authenticity proof):

- JBR `release`:018864009c44761bed2e2f23c993f7186364034dd0d3bdb5940762ff1aba7c0e.
- JBR `bin/java.exe`:5d3f7288ebd653721e1f2ef79b9623484cbf16ad8bb2e4b57567cde7f0a01d03.
- JBR `bin/keytool.exe`:43c7d14a721e2fb6f8468fdef81fcaa009685b7155338edfffb460ddb45747b0.
- SDK `platforms/android-36/android.jar`:d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a.
- SDK `platforms/android-36/source.properties`:72291ff6611dbfc46fdfb6de2821c4a62c87befa5200713847eda426e3a501c2.
- SDK `build-tools/35.0.0/aapt2.exe`:cbfe5deda5f7074ce47b6f33818b456ee8046a076a11af13e29c837d3c80c564.
- SDK `build-tools/35.0.0/lib/apksigner.jar`:00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7.
- SDK `build-tools/35.0.0/source.properties`:084847d70abc41284feee7ea717e7c92eab0d1be05f048c27445a359cfe109d8.

## Actual checks

The first BootstrapPolicyTest failure was4 intended missing-policy assertions, not the earlier infrastructure failures; focused green had4 tests/0 failures/0 errors. The broader module/lint/release-manifest command exited0/125 tasks. Actual lint has0 errors/3 warnings: OldTargetApi, ChromeOsAbiSupport, MissingApplicationIcon. Target36 and arm64 are approved bounded configuration choices; the missing icon remains a presentation warning, not a reason to fabricate a finished UI.

Added ManifestPolicyTest before build-input wiring and observed4 missing-input assertions/0 infrastructure errors; wired the real AGP merged-artifact providers and backup resource inputs. Final offline strict command was:

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :core:test :test-fixtures:test :data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 --no-validate-url
```

Result: exit0/68 tasks, eight app tests passed (two suites, each4 tests/0 failures/0 errors). Raw log `validation/raw/bootstrap/manifest-policy-green-wrapper.log`; preserved JUnit `BootstrapPolicyTest-post-manifest-green.junit.xml` and `ManifestPolicyTest-post-manifest-green.junit.xml` in that directory. Core/fixture/data/image tasks are source-empty, not scanner coverage. Changing merged artifacts or backup resources invalidates the test inputs; no hard-coded intermediate path is trusted.

Actual merged debug/release manifests omit INTERNET/ACCESS_NETWORK_STATE, set allowBackup=false, reference the checked legacy/modern backup resources and name the exported MainActivity launcher. Both backup modes exclude all nine declared root/file/database/preference/external/device-protected domains. AndroidX startup contains WorkManager, EmojiCompat, ProcessLifecycle and ProfileInstaller; scheduler/diagnostic components carry platform permissions where applicable. Debug adds Compose's PreviewActivity; it is absent from release. Source/merged checks do not prove compiled resource equivalence, OEM backup behavior, fresh-install offline operation or launch success.

## Signing and open gates

S-01 was explicitly approved and recorded before key generation. Generated only the named ignored PKCS12/RSA2048 development key after the checks above, using aliasandroiddebugkey and subjectCN=PristinePage Development,O=Local Development. Public certificate SHA-256:7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee; validity2026-11-14 through2036-11-11. The private key is never printed/committed/uploaded; release signing remains null.

No APK has yet been assembled. Assembly/packaging is held while the independent dependency-scope audit resolves the CameraX video-transitive ambiguity in P-02; approval is not inferred from lock entries or successful resolution. Independent code/artifact review, packaged signature/resource/native-ABI checks, devices, full rights notices and all scanner/visual acceptance remain open. C: free space was9.89GiB on resumption; retain the8GiB guard and do not clean caches automatically.

Subsequent checkpoint: the operator explicitly approved the exact P-02A exceptions before affected builds resumed. The final offline-strict module/test/lint/assemble command passed,9 app tests green; the [APK receipt](apk-receipt.md) now retains the exact signed debug artifact, package/resource/ELF/ZIP checks and observed warnings. The [notice register](../reviews/t01-notice-register.md) covers583 acquired components with explicit missing declarations/unknown roles and no blanket legal clearance. The preceding no-APK/held statements are historical. Independent artifact review and a coherent local commit remain next at this update; actual launch/devices and complete scanner/rights acceptance remain open.
