# T-01 verified development APK — bootstrap only

Historical artifact after I-01/U-03: this receipt identifies the genuine earlier app.paperlight.debug APK only. Preserve its hash-named dist copy/review; do not install it after the approved PristinePage identity alignment. [identity-receipt.md](identity-receipt.md) records the replacement app.pristinepage.debug artifact and its separate checks. No prior evidence is relabeled as validation of the changed package.

Built2026-11-14 under approved P-01/P-02/P-02A/S-01, attemptT01-20261114-A, from the T-01 worktree source changes after preparation commit434d58f. This artifact is a development bootstrap, **not the requested completed scanner**: import/capture are visibly disabled and no image algorithms, durable library or export journey exist. No installation or launch was authorized or performed.

## Exact artifact

- Normal build output: `app/build/outputs/apk/debug/app-debug.apk`.
- Preserved unique local copy: `dist/pristinepage-0.1.0-debug-t01-387217986018.apk`, with an adjacent generated JSON summary; both ignored/regenerable, not committed or externally published.
- SHA-256:`387217986018f31b7854abe153efb0febd0c0d2bd1029d7eb4e5e1b341ec7183`; bytes:16,779,849; ZIP entries:527, no duplicates or CRC failure.
- Package:`app.paperlight.debug`; versionCode1/versionName`0.1.0-debug`; labelPristinePage; minAPI26/target36/compile36; launcher`app.paperlight.MainActivity` with MAIN and LAUNCHER in one filter.
- One RSA2048 development signer; certificate SHA-256:`7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee`. Installed Build Tools35.0.0 apksigner reports **Verifies**, v2=true; v1/v3/v3.1/v4=false. This is a valid development signature for the declared API range, not a production/store identity or device-install result.

## Build and tests actually run

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :core:test :test-fixtures:test :data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Result: exit0 in49s,148 tasks (46 executed/102 up-to-date). Raw log:`validation/raw/bootstrap/bootstrap-assembly.log`. Parsed and preserved JUnit confirms BootstrapPolicyTest4/4 and ManifestPolicyTest5/5,0 failures/errors. Launcher-test correction was independently reproduced first: the exported-without-filter negative case failed before the predicate required MAIN/LAUNCHER in the same filter; absent and split filters are now rejected. The other modules remain source-empty, not scanner coverage.

Lint:0 errors/3 warnings, unchanged OldTargetApi/ChromeOsAbiSupport/MissingApplicationIcon. Gradle also reported deprecations incompatible with Gradle9 (not an attempted update) and inability to strip three prebuilt native libraries; it packaged those binaries unchanged. No NDK was installed or stripping workaround applied. Distribution/resource/functional gates are not waived by these warnings being nonfatal.

## Independent-tool artifact inspection

Used existing SDK Build Tools35.0.0 and JBR21.0.8, without network/device access:

```text
java.exe -jar <BuildTools35.0.0>/lib/apksigner.jar verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
aapt2.exe dump badging app/build/outputs/apk/debug/app-debug.apk
aapt2.exe dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml
aapt2.exe dump xmltree app/build/outputs/apk/debug/app-debug.apk --file res/xml/backup_rules.xml
aapt2.exe dump xmltree app/build/outputs/apk/debug/app-debug.apk --file res/xml/data_extraction_rules.xml
aapt2.exe dump resources app/build/outputs/apk/debug/app-debug.apk
zipalign.exe -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk
```

Actual packaged manifest has no INTERNET/ACCESS_NETWORK_STATE, allowBackup=false and the expected SDK/launcher. Compiled resource IDs0x7f0e0000/0x7f0e0001 resolve to backup_rules/data_extraction_rules. Decoded legacy/cloud/device-transfer resources each exclude all nine normative domains at path`.` with no includes. These checks do not prove OEM backup/transfer behavior or actual offline cold start. Release merged-manifest assertions pass, but no release APK is built/signed/audited.

ZIP contains only arm64 native libraries. Their ELF64/AArch64 PT_LOAD alignment is16384 and offset/address congruence passes16KiB checks; zipalign also passes16KiB ZIP alignment. These static checks are not actual16KiB-device execution evidence:

- `libandroidx.graphics.path.so`,10,096bytes, SHA-25641e9a793c43a0f4fddb19e33f346bace464f30f888ba7b9eaf96294ea115bfb6.
- `libimage_processing_util_jni.so`,32,528bytes, SHA-2560292b063fafccf734472cbb59588e756c2dbca907c72021ba6fbf126385508a2.
- `libsurface_util_jni.so`,4,896bytes, SHA-256a5c9d1928ea92ec7a94dff8cf666e7739cd734198b9f0ae1d4e28ea3c86516ba.

A bounded standard-library DEX-class-definition inventory found24,287 classes including MainActivity/BuildPolicy; no AGP/UTP/Jetifier host classes or ONNX/LiteRT runtime prefixes were defined. No ONNX/ORT/TFLite/PT/PTH asset was found by filename inventory. This is a scoped absence check, not a general malicious-code or complete SBOM audit. The permitted CameraX transitive video/media libraries do not imply a requested recording feature.

Raw signature/badging/manifest/resource/zipalign outputs and APK/ELF/DEX/JUnit inventory JSON are retained under ignored `validation/raw/bootstrap/apk-*`; originals/private keys were never printed or uploaded. Free C: space after assembly was9.52GiB. Independent artifact review, dependency-notice closure and coherent local commit remain next gates at this receipt's creation. All device/UI/scanner/neural/visual rights and final release gates remain open.
