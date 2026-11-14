# Paperlight Android Scanner Implementation Plan

> **For agentic workers:** use a task-by-task implementation workflow with test-first development and independent review. Where available, use `subagent-driven-development` or `executing-plans` skills under the operator's selected execution method. Steps use checkboxes for tracking; acceptance state is maintained in TODO.md.

**Goal:** implement the complete offline scanner in [PRD.md](PRD.md), with a dependable deterministic image core and optional evidence-gated model providers.

**Architecture:** five modules separate pure image/recipe math, Android rendering, durable data and UI. Originals and export snapshots are immutable; renders are generation/revision-checked and resource-admitted. Public model integration cannot block the classical release path.

**Tech stack:** Kotlin/JVM, native Android/Compose, CameraX, Room, coroutines, WorkManager, pinned maintained image primitives, optional ONNX Runtime or LiteRT only for a selected validated provider.

**Spec:** read PRD.md, ARCHITECTURE.md and PIPELINE_CONTRACTS.md together, plus the domain document named by each task. All paths below are in the future application workspace, not this documentation directory.

## Global constraints

- Minimum Android API26; arm64-v8a required. Pin an officially supported compatible toolchain at T-01, not guessed dependency versions.
- No account, cloud/OCR prerequisite, hidden telemetry, mandatory first-run download or Play-services dependency.
- Immutable original bytes; explicit orientation/color conversion; RGB8 shared image contract; checked dimensions/stride and resource admission.
- Geometry→appearance→optional luma noise→optional detail→optional final grayscale/binary; one candidate-clipped strength/protection blend.
- Typed versioned recipes, exact provider/asset identities and explicit OutputSpec.size/purpose; no hidden emergency export shrink.
- No stale publication, recursive fallback, success-labeled partial output or silent substitute under an unavailable model's name.
- Keep app tasks unchecked until actual current-build evidence exists. Model rights, mathematical parity, device fit and visual quality are independent gates.

## Review focus

1. URI permission disappearing during import must not commit a missing original: T-04 `revokedMidCopyDoesNotCommitPage`.
2. Page reorder/recipe edit during export must not change the reviewed output: T-11 `exportUsesFrozenOrderAndRecipes`.
3. Mirrored/rotated capture preview must not transfer incorrect still corners: T-05 `previewTransformDoesNotReplaceStillDetection`.
4. Loaded model returning NaN/wrong axes must fail before publishing: T-08 `invalidProviderOutputNeverPublishes`.
5. Process death between filesystem move and database commit must recover idempotently: T-03 `recoverAllIngestJournalBoundaries`.

## Task T-01 — Reproducible bootstrap and capability-free launch

**Files:** create root `settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper, `gradle/libs.versions.toml`, dependency verification metadata; five module build files; `app/src/main/AndroidManifest.xml`; `app/src/main/kotlin/app/paperlight/MainActivity.kt`; test `app/src/test/kotlin/app/paperlight/BootstrapPolicyTest.kt`. No model asset or network SDK.

**Interfaces:** produces module/package paths in ARCHITECTURE, a launchable import/capture shell and pinned toolchain record. Consumes PRD target/permissions/offline constraints. `BuildPolicy` exposes `minApi=26` and a release-provider allowlist initially empty.

- [ ] Confirm application workspace/operator authority, inspect any existing build, choose compatible stable versions from official documentation and pin them. Reuse existing approved wrappers/components if present; do not overwrite a different application silently.
- [ ] Write `BootstrapPolicyTest` asserting minApi26, an empty unapproved-provider allowlist and no production network endpoint configuration. Run `:app:testDebugUnitTest --tests '*BootstrapPolicyTest'`; observe failure before policy implementation. Manifest permission/backup checks are also added as build/instrumentation assertions, not inferred from that unit test.
- [ ] Implement the five-module dependency graph and minimal launcher/BuildPolicy. Record exact JDK/Gradle/SDK/dependency hashes and compile/target policy; do not fabricate a latest-version claim.
- [ ] Run `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`; inspect merged release manifest as well. Retain actual command output/toolchain. If no SDK/device is available, mark that gate blocked rather than passed.
- [ ] Review the scoped diff and commit this coherent milestone if the application uses Git and commits are authorized. Update TODO T-01 with evidence.

## Task T-02 — Contracts, reference math and fixture generator

**Files:** create `core/src/main/kotlin/app/paperlight/core/image/PixelBuffer.kt`, `pipeline/Contracts.kt`, `recipe/RecipeCodec.kt`, `geometry/Coordinates.kt`; tests `core/src/test/kotlin/app/paperlight/core/ContractTest.kt`, `CoordinatesTest.kt`, `RecipeCodecTest.kt`; generator `test-fixtures/src/main/kotlin/app/paperlight/fixtures/DocumentFixtures.kt` and generator tests.

**Interfaces:** produces the exact PIPELINE_CONTRACTS types, validated RgbImage/float-mask ownership and typed serializable recipe families. `RecipeCodec.encode(recipe:Recipe):ByteArray`, `decode(bytes:ByteArray):Recipe` and `hash(recipe:Recipe):String` use a pinned canonical encoding including effective defaults. `DocumentFixtures.generate(id:String):Fixture` returns clean/corrupt images, masks and parameters, never an unavailable external photo.

- [ ] Write ContractTest assertions for invalid/overflow dimensions, strides, padding-independent equality, RGB byte order and unchanged inputs. CoordinatesTest checks aligned-corner−1/+1→0/3999 and half-pixel resize separately. RecipeCodecTest rejects unknown schema/algorithm/fields and hashes equivalent canonical defaults identically. Run `:core:test`; observe intended failures.
- [ ] Implement the validated types/numerical adapters and canonical serialization; mutable native buffers stay out of stored recipes. Implement F-01–F-05 generators with seed20261114 and masks as specified, independent of fonts/Android APIs.
- [ ] Run focused core/generator tests; compare fixed scalar vectors and fixture parameter/hash reproducibility across repeated runs. Do not compare unspecified compressed PNG bytes across codecs.
- [ ] Review numeric/coordinate boundaries and commit if permitted; record fixture and contract versions in TODO.

## Task T-03 — Durable source/document store and recovery

**Files:** create `data/src/main/kotlin/app/paperlight/data/{db,files,journal,repository,model}/`; principal classes `SourceStore`, `DocumentRepository`, `RecoveryCoordinator`; tests `data/src/test/kotlin/app/paperlight/data/SourceJournalTest.kt`, `RecipeRevisionTest.kt`; populated migration tests under `data/src/androidTest`.

**Interfaces:** consume Recipe/PixelSize/hash contracts; produce source/document/page/value IDs, ImportSessionId, IngestResult, SaveRecipeResult, ExportSettings, ExportSnapshot, ExportSnapshotId, RecoveryReport and repositories defined in DATA_MODEL_AND_STORAGE. Required suspend methods: `SourceStore.ingest(uri:Uri,session:ImportSessionId):IngestResult`, `DocumentRepository.createDocument(title:String):DocumentId`, `saveRecipe(pageId:PageId,expectedRevision:Long,recipe:Recipe):SaveRecipeResult`, `freezeExport(documentId:DocumentId,expectedDocumentRevision:Long,settings:ExportSettings):ExportSnapshot`, `reconcile():RecoveryReport`.

- [ ] Write `recoverAllIngestJournalBoundaries`: inject interruption before/after staging, checksum, durable move and metadata commit; after two reconciliations every committed page has a valid owned source, no source is silently lost and duplicate recovery creates no extra page. Add shared-source duplicate/delete reference tests, revision-CAS conflict assertions and STATE-03 `fallbackPoliciesHaveDistinctJobsAndPolicyScopedRetries`. Observe failures with `:data:testDebugUnitTest`.
- [ ] Implement the documented Room schema/journal/file ownership transitions, atomic metadata transactions, source hashes and idempotence. Logical page removal/Undo and permanent confirmed document deletion are distinct operations. Do not claim database/filesystem joint atomicity.
- [ ] Run unit tests and real populated migration/process-death cases on an authorized device. Verify opaque IDs, FK/index constraints and disk-full handling.
- [ ] Review storage/privacy boundaries, commit if permitted, update T-03 evidence and any recovery assumptions.

## Task T-04 — Import and normalized decode

**Files:** create `app/.../capture/PhotoImportController.kt`; `image-android/.../decode/SourceDecoder.kt`; tests `image-android/src/test/.../DecodeAdmissionTest.kt` and `src/androidTest/.../OrientationDecodeTest.kt`; import integration tests.

**Interfaces:** `SourceDecoder.decode(sourceId:String,output:OutputSpec):DecodedSource` provides orientation-normalized RGB and source pixel/byte identities using SourceStore's durable bytes. `PhotoImportController.import(uris:List<Uri>,session:ImportSessionId)` coordinates per-item outcomes; its exact return data is the repository's IngestResult list, not a silent all-or-nothing UI success.

- [ ] Write admission tests for proposed100MiB/48MP/12000-side metadata limits, overflow/magic mismatch and actual dimensions; no giant allocations. Write EXIF1..8/color/alpha fixtures and `revokedMidCopyDoesNotCommitPage`. Observe intended unit/instrumented failures.
- [ ] Implement system picker plus API26-compatible document fallback, durable byte copy, actual-content validation, explicit decode/profile policy and per-item failure UI. Never rely solely on a temporary URI grant or file extension.
- [ ] Run JVM admission, actual decoder/provider tests and picker cancellation/revocation cases. Record non-sRGB/HDR support/rejection truthfully.
- [ ] Review input/resource/ownership checks and commit if permitted; mark T-04 only with actual evidence.

## Task T-05 — Camera capture

**Files:** create `app/.../capture/CameraController.kt`, `CaptureScreen.kt`, `CaptureSession.kt`; tests `app/src/androidTest/.../CaptureLifecycleTest.kt` and capture-session unit tests.

**Interfaces:** CameraController owns CameraX lifecycle and submits a captured app-owned source into the same import/store path. CaptureSession associates the still with the intended document/session, independent of a rotated/recreated UI. A detection suggestion never changes an accepted crop without user action.

- [ ] Write refusal/revocation/background/capture-in-flight tests, `previewTransformDoesNotReplaceStillDetection`, front/back orientation/mirror checks where hardware exists, and a no-model manual shutter/crop journey. Run and retain intended failing behavior before implementation.
- [ ] Implement permission-aware CameraX preview/still/flash controls, lifecycle cleanup and still-image orientation mapping. Prefer rerunning still detection over transferring unverified preview coordinates. No live auto-capture dependency.
- [ ] Run camera tests on an explicitly authorized physical device; verify importing remains usable when camera permission is refused. Retain actual device/camera limits.
- [ ] Review lifecycle/source handoff and commit if permitted; update T-05.

## Task T-06 — Deterministic geometry and masks

**Files:** create `core/.../geometry/{Homography,ManualCurve,InverseMapValidator}.kt`; `image-android/.../render/GeometryEngine.kt`; tests `core/src/test/.../GeometryMathTest.kt`, `image-android/src/androidTest/.../MaskWarpTest.kt`.

**Interfaces:** consume GeometryRecipe, OutputSpec, PointPx/QuadPx and source identity. GeometryEngine returns completed geometry raster plus aligned protection/page coverage and StageRecord, or a typed failure; its output enters appearance. Domain contracts are GEOMETRY_ALGORITHMS, not a generic arbitrary matrix/float-array guess.

- [ ] Write GEO-01–GEO-07, crossed/duplicate/degenerate corner tests, flat manual-curve homography dispatch, depth bounds, corner-Jacobian fold case with positive center, global overlap, crop origin, quarter-turn aspect/inverse-map composition, out-of-bounds neighbor sampling and protected-mask alignment. Run core/renderer tests and observe intended failures.
- [ ] Implement target sizing, direct inverse homography,128-segment edge arc LUT, manual ruled surface and row/coarse-map sampling. Preserve exact pixel-center and model-grid conventions; no hidden inset or invalid-map clamping to conceal failure.
- [ ] Run pure/instrumented reference-versus-optimized tests, full-frame identity and known-transform checkerboards. Review completeness/scale of map rejection gates, not just a final screenshot.
- [ ] Obtain independent numeric/map review and commit if permitted; update T-06.

## Task T-07 — Classical appearance and detail

**Files:** create `core/.../image/{PaperGain,NaturalLightness,Sauvola,LumaDetail,LumaDenoise}.kt` and Android color/filter bridge as needed; tests `PaperGainTest.kt`, `NaturalLightnessTest.kt`, `SauvolaTest.kt`, `DetailTest.kt`; renderer integration tests.

**Interfaces:** consume versioned AppearanceRecipe/DenoiseRecipe/DetailRecipe/BinaryRecipe and stage-aligned protection. AppearanceEngine supplies absolute candidate/output plus StageRecord as specified in PHOTOMETRIC_ALGORITHMS; RenderCoordinator applies the fixed stage order without a second blend or sharpen. Native optimized implementations must match the declared reference policies.

- [ ] Write ALG-01–ALG-12 plus paper-grid ties/partial cells/fill waves, natural-flat/mean-log behavior, clipped windows, large dark fill, binomial per-pass rounding, RGB headroom, threshold equality and band/tiling seam tests. Include I200,G1.5,a.5→228 and the binary64 composer boundary3/8/0.1→4. Observe intended focused failures.
- [ ] Implement conservative target245/maxgain3/noink gain recipe, natural lightness, explicit grayscale/Sauvola, optional luma noise/detail, exact-off bypass and mask support. Do not silently import an aggressive source preset or GPL code into an incompatible build.
- [ ] Run scalar/property/optimized parity tests, then produce actual F-02/F-03 outputs with stage-isolated metrics. Keep failures visible; arithmetic correctness is not visual acceptance.
- [ ] Independently review content-loss and memory behavior, commit if permitted and update T-07.

## Task T-08 — Provider registry and optional source adapters

**Files:** create `image-android/.../provider/{ProviderRegistry,ProviderManifest,ProviderValidator}.kt`, separate selected adapter classes, asset/license manifests; tests `ProviderGateTest.kt`, `ProviderContractTest.kt`; optional desktop export/parity harness only under separately approved dependency/model acquisition.

**Interfaces:** implement the DetectionProvider/GeometryProvider/AppearanceProvider distinctions in PIPELINE_CONTRACTS and MODEL_PROVIDERS. Registry reports unavailable with reasons without network. Each asset/hash/version has a distinct validation/build-allowlist state.

- [ ] Write no-asset/no-download/gated-card tests, wrong RGB/layout/grid-axis/range tests and `invalidProviderOutputNeverPublishes` for NaN/invalid maps. Include image-only dewarp plus protected geometry rejection and exact-once integrated-stage tests. Observe intended failures.
- [ ] Implement the registry and one optional source-reference path at a time. Recommended order: DocQuad detection, map-only UVDoc, GCDRNet field/full, DocRes, then conditional SyntheticDoc; keep FSENet/restricted/unclear assets deferred unless they earn explicit approval. Do not acquire models automatically.
- [ ] For each actually selected asset verify rights/hash, pinned source→exportCPU→device parity, offline load, memory/latency and quality cases before release enablement. Missing models can remain deliberately deferred; registry tests cannot.
- [ ] Review per-provider records, commit if permitted, list accepted/deferred adapters and open gates in T-08.

## Task T-09 — Editor and same-document comparison

**Files:** create `app/.../editor/{EditorViewModel,EditorState,PageEditorScreen,RecipeHistory,PreviewScheduler}.kt`; tests `EditorRevisionTest.kt`, `PreviewGenerationTest.kt` and instrumented `EditorAccessibilityTest.kt`.

**Interfaces:** EditorViewModel serializes draft/accepted Recipe revisions, calls RenderCoordinator, and publishes only matching-generation OK results; repository Apply uses expectedRevision CAS. Scheduler keys include source/recipe/mask/provider/output identities. Compare-stage input is distinct from original photograph.

- [ ] Write stale generation8-after9, rapid switch/cancel, draft Back/Apply/Discard, Undo/Redo, same-geometry cards, no-substitute failed card, rotation and true-detail-context tests. Observe intended unit/UI failures.
- [ ] Implement Looks/Shape/Detail, accessible controls, prioritized/debounced previews, bounded cache and Info/provenance. Use actual packaged processing for sample cards and annotate precomputed examples honestly.
- [ ] Run unit/Compose/device accessibility tests including TalkBack and large fonts; verify all48dp targets and non-gesture crop/curve actions. Record visual review separately from automation.
- [ ] Review state/performance/accessibility and commit if permitted; update T-09.

## Task T-10 — Multi-page library

**Files:** create `app/.../library/{LibraryViewModel,LibraryScreen,DocumentOverview}.kt`; data command additions under repository; tests `DocumentOrderingTest.kt`, `SourceReferenceCountTest.kt`, library journey instrumentation.

**Interfaces:** consume repository IDs/revisions. Every page-order mutation changes documentRevision atomically; duplication creates a page with independent recipe/history but an explicitly shared immutable source. Removal/Undo and permanent confirmation follow storage ownership rules.

- [ ] Write order/reopen/reorder-conflict, duplicate-edit isolation, remove/Undo and last-reference/permanent-delete tests. A deletion must not remove shared sources or external exports. Observe intended failures.
- [ ] Implement create/rename/open/add/rotate/duplicate/move/remove and clear confirmation/Undo states. Use stable IDs, not list indices, in job/editor/navigation references.
- [ ] Run data/UI/process-death integration cases with populated documents and mixed page aspects. Verify library counts/thumbnails agree with durable state after reconciliation.
- [ ] Review durability/delete scope and commit if permitted; update T-10.

## Task T-11 — Verified snapshot export and sharing

**Files:** create `app/.../export/{ExportViewModel,ExportReviewScreen}.kt`, `image-android/.../export/{ExportService,PdfWriter,ImageWriter}.kt`, data snapshot/journal support, FileProvider XML and tests `ExportSnapshotTest.kt`, `ExportPublicationTest.kt`, independent PDF/image verification instrumentation.

**Interfaces:** `DocumentRepository.freezeExport(...)` produces immutable ordered pages with source/recipe/provider/output identities. `ExportService.export(snapshotId:ExportSnapshotId):ExportResult` returns a complete verified app-private artifact or explicit failure. SAF publication and sharing consume that artifact, not current mutable pages.

- [ ] Write `exportUsesFrozenOrderAndRecipes`, mixed-aspect/page-count/dimension assertions, `acceptedFallbackReusesPinnedActualRaster`, changed/lost accepted-raster rejection, renewed actual-dimension/omitted-rotation review, frozen fallback policy, low-storage/cancel/revoked-destination/short-write and partial-external-copy tests. Observe intended failures. Independent rasterization verifies distinctive page patterns/order, not only writer metadata.
- [ ] Implement the admitted PDF/JPEG/PNG backend and its measured memory bounds from CAPTURE_IMPORT_EXPORT, freeze exact settings, render source snapshots, verify final private output, then publish/share with content URI grants. Do not silently truncate/split documents or overwrite unrelated external files.
- [ ] Run actual codec/URI/PDF readback and interruption tests. Stress output-specific page/memory limits;100 admitted library pages are not a promise of unbounded PDF assembly. Keep complete private outputs recoverable after failed publication.
- [ ] Obtain independent persistence/export/privacy review and commit if permitted; update T-11.

## Task T-12 — Device, fidelity and release acceptance

**Files:** create `validation/{fixtures,device,quality,offline,release}/` records/generators where needed; instrumented suite under app/image/data modules; `licenses/` notices and final build/asset allowlist; release documentation. Do not commit private user photos or regenerable large output caches.

**Interfaces:** consumes all requirement rows, package version, actual build/toolchain/assets, fixture manifests and test outputs. Produces an honest release receipt with passed/failed/skipped/not-run states and supported-device/document scope.

- [ ] Pin reproducible failure tests before fixes for remaining defects. Run VALIDATION_PLAN gates V-01–V-07; retain exact commands/current-build evidence. Verify all traceability rows and mandatory task states.
- [ ] Measure proposed resource/latency limits on physical target devices; run sustained cycles, process death, low storage/memory, unsupported content, offline fresh install, permissions, TalkBack/large-font and actual sharing. Produce genuine same-document gallery outputs and explicit failing cases.
- [ ] Audit code/model/sample/dependency rights, merged manifests/network/backup policy, native ABI/page-size/toolchain compatibility and signing settings. Obtain operator decisions for final license, branding, accepted device/quality limits and distribution; publication is not automatically authorized.
- [ ] Resolve consequential review findings and rerun affected checks. Commit the tested release milestone if permitted. Report exact implementation/validation state; do not equate optional algorithm research with a fully accepted app.

## Execution handoff

The operator must review this plan in the selected application workspace and authorize implementation. A native implementer or disjoint task workers may execute it; shared contracts/database/build configuration have a single owner. Tasks are coherent milestones, not permission for a12-task unreviewed bulk edit. Use TODO for current claims and REQUIREMENTS_TRACEABILITY for coverage; a specification-package review does not replace application code/security/device review.
