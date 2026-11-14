# Validation and release acceptance plan

Status at package creation: no application implementation, model inference, app build, physical-device benchmark or visual acceptance has been run. Checked documentation structure/math is recorded only in SPEC_PACKAGE_VALIDATION. This file defines what the implementing agent must prove.

## Independent gates

| Gate | Evidence required | Not sufficient |
|---|---|---|
| V-01 Deterministic core | Exact vectors, border/stride/precision tests, unchanged inputs and reference/optimized equivalence | A visually attractive sample. |
| V-02 Persistence and state | Failure-injection/process-death recovery, idempotence, revision/snapshot isolation and safe file ownership | Room transaction success alone. |
| V-03 Provider numerical parity | Pinned source/weight hashes; source→exportCPU→deviceCPU/delegate raw outputs and decoded results | A model loads or runs once. |
| V-04 Functional UI | Complete user journeys plus refusal, cancellation, rotation, stale result and accessibility cases | Screenshots or happy-path clicks alone. |
| V-05 Quality | Synthetic semantic measurements and representative licensed real pages with retained outputs/failures | PSNR from an unrelated paper or all-white background. |
| V-06 Device/offline/resource | Physical-device cold/warm/repeated measurements, bounded allocation, actual fresh-install airplane-mode paths | Desktop GPU timings or emulator-only speed. |
| V-07 Export/distribution | Independently readable/rendered exports, correct order/aspect/snapshot, safe URI sharing, source/weight/dependency rights and manifest audit | File exists, extension matches or code license alone. |

V-01,V-02,V-04,V-05,V-06,V-07 apply to mandatory classical release. V-03 applies to each shipped model; a deferred provider is an explicit limitation, not a failed optional model disguised as a required feature success. T-08's registry/gating tests remain mandatory without models.

## Test layers and ownership

JVM unit tests in core cover numeric primitives, filter/gain/threshold recipes, sizing, coordinate conversion, homography/manual curves, topology, recipe serialization/hash and request/result invariants. Property tests vary dimensions, padding, masks, flat colors, endpoint rounding and kernel/image scale. Fixed random seeds and shrunk failure examples are retained.

Data unit/instrumentation tests cover journal transitions, source/reference lifetime, Room migrations with populated databases, immutable export snapshots, concurrent edits and crash reconciliation. A fake filesystem/URI provider tests exact failure boundaries; device tests exercise actual app-private storage, SAF and grants. Never infer real filesystem atomicity from an in-memory fake.

Android unit/instrumented tests cover decode/EXIF/profile handling, CameraX lifecycle, JNI buffer/stride conversion, dispatcher/session ownership, actual runtime asset loading, renderer cancellation, Compose accessibility and system picker/sharing integration. Camera hardware cases need a physical device. Choose an explicit authorized ADB target, never every connected device.

End-to-end journeys: fresh launch→camera denial→import→manual crop→white/natural comparison→save→reopen→multi-page reorder→PDF export→share; capture→curved-page edit→export; canceled preview/export; source/provider unavailable; full-storage boundary; background/process death/restart. Each records build/device, inputs, actions, expected/observed result and evidence.

## Fidelity evaluation

Use TEST_FIXTURES and permission-cleared real sources at identical source/crop/output size and unrelated-stage settings. Measure illumination without optional boost/sharpen first, then detail/noise separately. A public model's current release, paper model, ONNX port and quantized variant are distinct test subjects unless parity is demonstrated. Never rank heterogeneous dataset/resolution tables as a product benchmark.

| Objective | Measurement and interpretation |
|---|---|
| Background | Background-mask95th−5th percentile and target-background error. Report content metrics beside it; white output trivially wins background alone. |
| Faint marks | Per-level/per-thickness local background-minus-stroke contrast, absolute target error, connected-component/width preservation where meaningful; do not average away erased punctuation. |
| Color | Per-patch RGB error, clipped fraction and optional calibrated DeltaE with the exact color transform; retain substrate-target distinction. |
| Noise/detail | Flat-region deviation, edge profiles, halos/overshoot, changed-stroke width and original-side comparison. Smoother or sharper-looking is not necessarily truer. |
| Geometry | Reprojection/known-map error, line straightness, boundary/content coverage, fold/overlap and missing/duplicated marks; homography tests do not establish curved-page inference accuracy. |
| Real pages | Side-by-side faithful-content review with the original and recorded task intent, including disagreements/failing regions. No unverified clean target. |

Proposed promotion rule: preserve-content recipes improve shading-fixture background spread/error without erasing a required faint level or neutralizing a protected color patch; unchanged clean input has no unexplained degradation. A failing case remains declared and cannot receive visual acceptance until corrected or excluded by an explicit supported-document scope. Binary output is intentionally lossy: score foreground/background and dark-fill/faint-mark limits for its declared use, retain gray/original alternatives, and never sell binary as preserving every gray/color distinction.

Paper whitening and faithful tinted-paper rendering have different targets. Human preference can choose between useful nondominated alternatives but does not turn demonstrated content loss into fidelity. A map-only model still can stretch/drop content; an image-restoration model can synthesize or alter marks. Documentary/forensic authenticity is not guaranteed by this application.

## Provider acceptance

Check exact local asset hash against the approved source/distribution and manifest. Load a source reference without changing its preprocessing, then an exported CPU graph; compare raw outputs (fields/maps/images) and final pixels on flat, near-black, colorful, faint-detail, odd-dimension and shadow cases. Select justified absolute/relative tolerances from observed numerical error and task effects, record why; do not set loose tolerances merely to pass. Shape/layout/channel/normalization/padding/cropping and zero handling are hard contract tests.

Device/delegate/FP16/INT8/tiling adaptations repeat relevant comparisons and quality gates. Unsupported operators or excessive partitioning are explicit failures. Check CPU fallback performance/power rather than assuming acceleration. A paper's PSNR and a public checkpoint download do not grant redistribution rights.

## Resource, offline and accessibility protocol

Use PERFORMANCE_AND_OFFLINE initial limits and collect actual device evidence. At least20 warm runs per chosen condition plus cold load; record P50/P95, peak Java/native/graphics/PSS, repeated-cycle growth, thermal behavior, cancellation and OOM/failure count. Measure complete pipeline including decode, prompts, copies, maps and output encoding. Stress rapid switching/rotation/background, low-memory signals and low storage. Test actual maximum admitted requests; guard tests use simulated large dimensions rather than dangerous allocations.

Fresh-install airplane mode must exercise every shipped algorithm/example, camera, local picker-readable import, reopen, PDF/images and share handoff. No model/runtime/example network fetch may occur. Audit merged manifests/dependency startup and backup settings separately; a cloud-backed document provider's unreadable content produces a bounded unavailable response. Test TalkBack, large fonts, all48dp hit targets, contrast, reduced motion and non-gesture editing.

## Export and recovery acceptance

Verify JPEG/PNG magic, dimensions, orientation, decode and intended fidelity; PNG reference pixels are lossless only relative to its rendered source. Verify each PDF page count/order/aspect/placement by independent parsing/rasterization, not the same writer's metadata alone. At least one mixed-aspect multi-page fixture must have distinctive page labels/patterns. Confirm no OCR text claim, encryption/password promise or archival/PDF-A claim is made without implementation.

Interrupt before/after private finalization, external destination creation/copy, metadata commit and sharing. Complete private output must remain retrievable; partial external copies never become completed exports. User-selected destination permissions can disappear. Cleanup is limited to this operation's newly created owned target where supported, not unrelated files. Existing external documents are never silently overwritten or recursively deleted.

For accepted degraded exports, exercise `acceptedFallbackReusesPinnedActualRaster`: persist and pin the exact lossless EXPORT-purpose DEGRADED raster/record, freeze policy/requested/actual dimensions and RenderId/execution hash, renew review when size/orientation differs, and prove provider recovery or later edits cannot change encoded pixels. Lost/corrupt pins must fail, never rerender under old acceptance. Ordinary frozen pages use FAIL; new degradation needs a new reviewed snapshot. Invalid input, cancellation and resource/storage failures are not acceptable fallbacks.

## Evidence, severity and release decision

Retain test IDs, toolchain/build/commit, fixture hashes, actual commands/results, device identity, date, warnings, output artifacts and reviewer dispositions. Record passed/failed/skipped/not-run/unavailable distinctly. Stop ship for source/data loss, wrong-page export, unintended network transmission, source overwrite, invalid-map/content-loss acceptance, stale publication, unrecoverable committed state, rights violations or claimed successful partial exports. Performance preferences may be documented trade-offs; crashes at ordinary supported input are not.

The final reviewer checks all FR/NFR rows in REQUIREMENTS_TRACEABILITY, every shipped recipe/provider, database migrations and export targets. Operator accepts visual intent and any explicit capability limitations. No automated count or task checkbox substitutes for the retained current-build evidence.
