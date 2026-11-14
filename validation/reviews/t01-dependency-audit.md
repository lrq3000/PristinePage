# T01-DEP-R — independent dependency audit

Attempt `T01-20261114-A`; reviewed 2026-11-14 as an Android dependency/supply-chain reviewer. **Verdict: CHANGES REQUIRED for acquisition-authority reconciliation; technical identity/locked-root checks pass within scope.** Approval and T-01 acceptance remain coordinator/operator decisions. No unexplained direct dependency or checksum mismatch was found; the findings below do not establish APK bundling or unauthorized intent.

## Coverage and reproducibility

Read every mandated startup document, accepted implementation plan, complete P-02 and LICENSES_AND_SOURCES, catalog, root settings/build, five module build files and six lockfiles. Programmatically parsed the entire 4,157-line verification XML and enumerated only the approved isolated Maven cache. Inspected relevant cached POM/module text, CameraX AAR manifest/metadata and selected class constant-pool text; never executed dependency code. Used read-only `python -B -` checks, dedicated reads/searches and the report-only patch. No network, downloads, Gradle/builds, devices, signing, Git operations, delegation, private images, secrets or log capture; `.local-signing` was not accessed.

Verified lock coordinate counts: app **332**, data **217**, image-android **189**, core **40**, test-fixtures **37**; settings has **0** and only `empty=incomingCatalogForLibs0`. There are **369 distinct coordinates**, all present in verification metadata. Nonempty/empty configuration counts respectively: app 33/32, data 37/34, image 26/33, core 10/8, fixtures 10/8, settings 0/1. Evidence: complete `app/gradle.lockfile:4-336`, `data/gradle.lockfile:4-221`, `image-android/gradle.lockfile:4-193`, `core/gradle.lockfile:4-44`, `test-fixtures/gradle.lockfile:4-41`, `settings-gradle.lockfile:4`.

Verification contains **583 components / 994 artifact records**: 105 AAR, 304 JAR, 237 module, 348 POM. Every record has one SHA-256 and matching cached bytes; zero missing files, mismatches or trust-bypass rules. All 583 components have recorded metadata; 409 have binaries. Cache inventory is **1,232 files / 585 coordinates**: the other **238 metadata files** (237 POM, one module) are unrecorded, not missing binary verification. Therefore verification does not cover every cached metadata file. All 238 cached modules were parsed; 152 recorded artifact hashes also matched embedded module SHA-256 values, which are not independent authentication. No source JARs were cached. Evidence: `gradle/verification-metadata.xml:1-4157`; audited XML SHA-256 `adb85e2ef63ab5acdd356a13b61abb12209553c6f649a90cf79eca76b9cb98ad`.

Strict project locking is enabled (`build.gradle.kts:17-22`); repositories are Google/Central and the plugin portal, with project repositories rejected (`settings.gradle.kts:1-14`). All 32 used catalog library roots and seven plugin declarations fit P-02; three library aliases remain unused. No snapshot/dynamic direct version was found (`gradle/libs.versions.toml:1-69`). These six lockfiles do not independently lock the plugin/buildscript graph. Recorded plugin/compiler bytes are covered by verification, but clean-cache replay, distribution/JDK/SDK identity, historical acquisition origins and future unresolved configurations were not verified. Hashes are explicitly generated-on-acquisition, signatures disabled, not publisher-authenticated provenance (`gradle/verification-metadata.xml:4-5,10`; P-02:67).

## Roles and findings

App locks contain 161 release-runtime and 167 debug-runtime coordinates, not APK inventories. JUnit/Android-test/Room-testing roots are test-only; Room compiler/sqlite-jdbc and KSP are build-only (`data/build.gradle.kts:38-45`; `data/gradle.lockfile:220`). Kotlin stdlib 2.4.0 occurs only on KSP/compiler plugin configurations, not app runtime (`data/gradle.lockfile:190-191`). gRPC/Netty/OpenCensus are AGP host-tool dependencies (`app/gradle.lockfile:221-263`), not an app telemetry SDK. TensorFlow **metadata**, not an inference runtime/model, comes from AGP itself (`gradle/verification-metadata.xml:4140-4146`; AGP-POM:331-335). No ONNX/LiteRT inference, Play-services, ML Kit, Firebase or unexpected network SDK was found in the inspected runtime graph. Names in module dependency constraints alone are not acquired artifacts.

### F1 — Medium, verified: P-02's video exclusion conflicts with its approved root

P-02:15 permits required stable transitives, while P-02:28 explicitly approves camera-view 1.6.2 and says “No video…artifacts.” Cached CV:135-141,308-314 declares **nonoptional camera-video** for API/runtime. VV:310-323 requires media3-container/muxer 1.9.0; MP:31-44 links muxer to common/container. These match `app/gradle.lockfile:21-22,112-114`; hashes are retained at `gradle/verification-metadata.xml:155-170,874-897`. CameraController class descriptors also reference VideoCapture/Recorder. This is a native consequence of the selected root, not evidence of a new direct video feature. Blind exclusion is not a demonstrated safe remedy.

**Smallest remedy:** coordinator/operator explicitly reconcile the exclusion with this exact transitive chain before declaring acquisition compliant. Alternatively, propose a separately reviewed preview dependency arrangement if zero video artifacts is literal; do not silently change versions or remove required transitives. This report grants neither interpretation.

### F2 — Medium, verified: “stable transitives only” is not literally satisfied by AGP

Six UTP coordinates are `0.0.9-alpha03` (`app/gradle.lockfile:212-217`); two Jetifier components are `1.0.0-beta10` (`gradle/verification-metadata.xml:1544-1559`). AGP-POM:247-257,319-323 and AGP's UtpDependency constant-pool text establish native build/host-test chains, not prerelease app roots. **Smallest remedy:** obtain an explicit exact-component host-tool exception/reconciled bill, or reconsider the approved toolchain through its normal approval process. Do not relabel these versions stable or independently override AGP internals.

### F3 — Low, verified: notices remain incomplete

`licenses/README.md:5-7` retains bootstrap Gradle 8.12.1 notices, explicitly not the acquired dependency notice audit. P-02:67 and `docs/specs/LICENSES_AND_SOURCES.md:31-33` still require retained artifact/origin/notice/role records. **Remedy:** complete that register from approved cached metadata/embedded notices before closing the dependency record; APK-specific notices/legal clearance remain later gates. No blanket license or authenticity clearance follows from matching hashes.

## Cached evidence locators

P-02 denotes `validation/bootstrap/acquisition-plan.md`. Prefix `C` = `C:/Users/local-user/AppData/Local/Temp/opencode/paperlight-build-cache/gradle/caches/modules-2/files-2.1`.

- CV = `C/androidx.camera/camera-view/1.6.2/d596c0b04261e3c0ac35b4e812289d33e5ef89fe/camera-view-1.6.2.module`.
- VV = `C/androidx.camera/camera-video/1.6.2/f56ad4f1af167520b189c3bbb6886b53d09dfbdf/camera-video-1.6.2.module`.
- MP = `C/androidx.media3/media3-muxer/1.9.0/64c54e19d3e6eb1f85232733ebd026696a655a38/media3-muxer-1.9.0.pom`.
- AGP-POM = `C/com.android.tools.build/gradle/8.13.2/99424fd799fa798ff502c7b25793cb86010ea46d/gradle-8.13.2.pom`.

Only this report changed. Final byte-identity recheck found all ten frozen review files unchanged. No application tests or APK inspection were performed; runtime execution, packaged contents, offline behavior and release acceptance remain unverified.
