# T01-ART-R — independent frozen APK boundary review

Attempt `T01-20261114-A`; 2026-11-14; independent Android APK reviewer; workspace `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`, preparation base `434d58f`, current T-01 implementation uncommitted.

## Verdict

**PASS for scoped local T-01 static bootstrap-artifact verification, with two Minor findings.** No Critical or Important artifact defect identified in this scope. This is explicitly **not** whole-application, actual launch, device/offline, production, release or distribution acceptance; it does not close T-01 or any shared TODO. The APK is a signed development shell, not an implemented scanner. Authority for subsequent operations remains with the coordinator/operator.

## Full-read coverage and method

Read completely: root `AGENTS.md`; `docs/specs/{AGENTS,PRD,TODO,PIPELINE_CONTRACTS,common-mistakes-and-frequently-used-commands,OPERATIONAL_PLAN,ARCHITECTURE,PERFORMANCE_AND_OFFLINE,SECURITY_PRIVACY,VALIDATION_PLAN,LICENSES_AND_SOURCES,IMPLEMENTATION_PLAN}.md`, including accepted T-01; `validation/bootstrap/{apk-receipt,toolchain-receipt,signing-plan,dependency-scope-amendment,acquisition-plan}.md`; and `validation/reviews/{t01-code-review,t01-dependency-audit}.md`. Independently reproduced accepted-plan SHA-256 `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Also read complete app build/catalog, manifest, MainActivity, BuildPolicy, strings, both backup resources, both test classes, README, debug/package and release/main merged manifests, assembly log, lint text, launcher-negative red JUnit and both assembly-green JUnit files. Historical checkpoint statements were not mistaken for current artifact state. P-02A records exactly twelve approved exceptions; S-01 records development-only signing, not device/production permission. License/dependency clearance is assigned elsewhere and was not duplicated.

Used only read-only `python -B -` standard-library hashing, ZIP CRC/inventory, ELF64 program-header parsing, DEX header/checksum/class-definition parsing, XML/report checks, and installed JBR21.0.8/SDK Build Tools35.0.0. Tool hashes for Java, aapt2 and apksigner.jar match the toolchain receipt. Independently executed these commands against `app/build/outputs/apk/debug/app-debug.apk` (all exit 0; stdout only):

```text
java.exe -jar <BuildTools35.0.0>/lib/apksigner.jar verify --verbose --print-certs <APK>
aapt2.exe dump badging <APK>
aapt2.exe dump xmltree <APK> --file AndroidManifest.xml
aapt2.exe dump xmltree <APK> --file res/xml/backup_rules.xml
aapt2.exe dump xmltree <APK> --file res/xml/data_extraction_rules.xml
aapt2.exe dump resources <APK>
zipalign.exe -c -P 16 -v 4 <APK>
```

No Gradle/build/sign/generate, installation, ADB/device/emulator/launch, network/acquisition, Git mutation, nested delegation or private-key access occurred. Only this report was written. Start/end checks cover both APKs and 31 source/build-configuration/lock/wrapper inputs; their aggregate SHA-256 is `cea9a1e02b4399b7951929f8735ab0e77240139aa1471359e08f9b11dbae529e`, excluding mutable coordinator records.

## Independently verified artifact evidence

- **True — identity/signature:** both normal output and `dist/pristinepage-0.1.0-debug-t01-387217986018.apk` have 16,779,849 bytes and SHA-256 `387217986018f31b7854abe153efb0febd0c0d2bd1029d7eb4e5e1b341ec7183`. One RSA2048 signer verifies with APK v2; certificate SHA-256 `7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee`. v1/v3/v3.1/v4 and SourceStamp are false, not defects for this scoped development artifact/API26 minimum. No production identity is established.
- **True — package/source agreement:** `app.paperlight.debug`, versionCode1/`0.1.0-debug`, min26/target36/compile36, label PristinePage. Exported `app.paperlight.MainActivity` has MAIN+LAUNCHER in the same compiled filter and a matching DEX definition. Packaged bootstrap strings match source, including the explicit unavailable-feature notice. MainActivity source disables both actions; no import/capture implementation is claimed. This is consistency checking, not independently rebuilt source-to-binary reproducibility.
- **True — capability surface:** no INTERNET/ACCESS_NETWORK_STATE, microphone or broad-storage permission. CAMERA plus WorkManager WAKE_LOCK/RECEIVE_BOOT_COMPLETED/FOREGROUND_SERVICE and the signature-level dynamic-receiver permission are present. Startup initializes WorkManager, EmojiCompat, ProcessLifecycle and ProfileInstaller. Exported scheduler/diagnostic/profile components carry BIND_JOB_SERVICE or DUMP restrictions. Debug PreviewActivity is exported; it is absent from the inspected release merged manifest. This debug APK is not a production capability audit.
- **True — compiled backup resources:** allowBackup=false; no BackupAgent. Resource table resolves label `0x7f0b001e`, legacy rules `0x7f0e0000` and modern rules `0x7f0e0001` to the expected string/XML. No alternate backup XML configuration appears. Decoded legacy, cloud-backup and device-transfer sections each exclude all nine normative domains at path `.` with no includes, agreeing with source. OEM transfer/restore behavior remains untested.
- **True — ZIP/native:** 527 unique entries, no CRC failure, encrypted entries or traversal paths. Only three native libraries, all uncompressed ELF64/AArch64: `libandroidx.graphics.path.so` (10,096 bytes), `libimage_processing_util_jni.so` (32,528), `libsurface_util_jni.so` (4,896). Each hash matches the APK receipt; every PT_LOAD has alignment16384 and congruent offset/address. ZIP data offsets15728640/15745024/15777792 are 16KiB aligned; zipalign passes. This does not prove execution on a 16KiB device.
- **True — scoped absence:** five DEX038 files pass SHA-1/Adler32 checks and define24,287 unique classes. No defined AGP/UTP/Gradle/Jetifier, gRPC/Netty/OpenCensus, ONNX/LiteRT/TensorFlow, Play-services/ML Kit/Firebase or JUnit/test-runner prefixes were found. Camera-video443/media3 558 definitions are consistent with P-02A, not a recording feature. There are no assets or inspected model/host-binary/key filename extensions. Prefix/filename checks are not a complete SBOM, semantic malware audit or proof against disguised content.

## Findings — effect-based severity

**Critical: none identified. Important: none identified within the bounded bootstrap review.**

**ART-1 — Minor, true: optional camera declaration leaves an implied rear-camera requirement.** Source manifest lines2–5 declares CAMERA and optional `android.hardware.camera.any`, but not optional `android.hardware.camera`. Independent badging explicitly reports required/implied `android.hardware.camera` from CAMERA. Future store feature filtering can exclude front-only/camera-less devices despite an intended import fallback. It does not establish an installation/launch failure here. After lifting the freeze, coordinator should evaluate explicitly making the implied feature optional, retain a regression check and rebuild/re-review; no manifest edit was made.

**ART-2 — Minor, true, already disclosed: no application icon.** Source application declaration and compiled badging contain no icon, matching MissingApplicationIcon. Launcher identification/presentation is unfinished; actual rendering was not observed. Supply approved artwork in a later scoped change rather than treating this shell as release presentation.

## Retained evidence and declined judgments

`validation/raw/bootstrap/bootstrap-assembly.log` records BUILD SUCCESSFUL/148 tasks (46 executed/102 up-to-date); preserved assembly JUnit records Bootstrap4+Manifest5, zero failures/errors/skips. The launcher-negative red report records one intended failure; current source rejects absent/split filters. These are inspected retained results, not tests rerun by this reviewer. Other module tests are NO-SOURCE. Lint records0 errors/3 warnings; Gradle deprecations and unchanged-prebuilt stripping warnings remain disclosed.

Actual launch/API26 operation, airplane-mode cold start, OEM backup/transfer, accessibility/visual behavior, memory/performance and all scanner/neural/storage/export journeys remain unverified. Rights/notices, clean-cache replay, publisher authenticity, production signing and final packaged-release acceptance remain separate open gates. Precaution: preserve this exact reviewed artifact; any changed build needs renewed identity/resource/signature checks and separately authorized named-device testing before launch claims.
