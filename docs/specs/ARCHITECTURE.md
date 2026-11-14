# Native architecture and ownership

## Approach decision

DESIGN: native Kotlin/Compose with a deterministic local image core and optional model adapters. A neural-first design makes basic scanning depend on unverified assets/operators/device cost. A third-party scanner SDK is simpler only if it actually meets offline, source-retention, recipe transparency, distribution and multi-algorithm requirements; evaluate its terms before replacing this architecture. A library-only UI cannot satisfy the accepted camera/library/multi-page/export scope. This architecture reuses Android platform components rather than reimplementing pickers, camera device control or durable scheduling.

## Modules and files

Use package prefix `app.paperlight` as an internal working namespace; final application ID/signing identity must be confirmed before publication. Keep five Gradle modules, not a module per screen:

| Module | Responsibilities / principal paths |
|---|---|
| `:core` | Kotlin/JVM contracts, recipe serialization/hash, geometry math, deterministic reference algorithms, topology checks; `core/src/main/kotlin/app/paperlight/core/{image,geometry,pipeline,recipe}`. No Android or model runtime dependencies. |
| `:image-android` | Android decoding/color bridge, native OpenCV operations, renderer, provider adapters and resource accounting; `image-android/src/main/kotlin/app/paperlight/image/{decode,render,provider}`; JNI code under `src/main/cpp` only when justified. |
| `:data` | Room entities/DAOs, private file store, source ownership, journals, job/export snapshots and recovery; `data/src/main/kotlin/app/paperlight/data`. |
| `:app` | Compose navigation/screens, CameraX, pickers, lifecycle, foreground UI processing and WorkManager integration; `app/src/main/kotlin/app/paperlight/{capture,editor,library,export}`. |
| `:test-fixtures` | Reproducible pure fixture generator and semantic masks consumed by JVM/device tests; no copyrighted sample bundle is assumed. |

UI depends on domain contracts/repository interfaces, not native tensor layouts. Core never opens URIs or loads models. Decode and native image ownership do not leak through a Room entity. Put feature logic in cohesive classes; avoid a generic workflow/graph/plugin execution framework, service locator or mandatory dependency-injection framework.

## Data flow and executors

System camera/picker → durable source ingestion → page + recipe revision → immutable RenderRequest → single RenderCoordinator admission queue → decoder/geometry/appearance/detail → owned RenderResult → generation/revision check → preview or committed rendered-page file → ExportSnapshot → verified export → publish/share.

Use a bounded CPU dispatcher; start with two processing threads and one inference session at a time, tune on devices. Renderer owns buffers and releases them with explicit close/finally scopes. Compose observes StateFlow state; it never owns a native Mat lifetime. UI-thread work is state publication and draw-ready bitmap assignment. A bitmap still referenced by the display cannot be recycled by an LRU eviction.

Interactive preview jobs live with an editor session and are cancelable; they are not enqueued persistently on every slider movement. A committed render/export is journaled first, then scheduled as recoverable work. WorkManager is a scheduler, not a guarantee of immediate execution or indefinite CPU time. Respect the actual Android job/foreground-service limits of the pinned target; page-level work and checkpoints avoid one unbounded worker. The app does not promise export completion after force-stop. Reopening reconciles interrupted jobs idempotently.

## State boundaries

`EditorState` contains document/page IDs, accepted source orientation, draft recipe, persisted recipeRevision, previewGeneration, view transform and preview status. A recipe edit increments previewGeneration immediately; Apply stores a new immutable recipe revision. Navigation does not silently apply an unaccepted draft. Undo/Redo operates on bounded recipe history; document page-order edits use their own history and revision.

`RenderCoordinator.render(request)` returns a typed result and accepts cooperative cancellation. Its publish guard compares requested source/recipe hashes, dimensions and editor generation, not only page ID. Persistent page-job identity is the versioned hash of canonical pre-execution render input: pageId, recipeRevision/hash, sourcePixelHash, OutputSpec, mask hashes, assetSetHash, conversion/render/encoding versions, effective backend/precision and **fallbackPolicy**. DATA_MODEL_AND_STORAGE defines canonical input and retry guards. FAIL and KEEP_STAGE_INPUT are different jobs; same-policy retries reuse only their own job. Execution hash belongs only to post-execution raster-cache identity, never the pre-execution key. Completed old output is retained as an old revision and cannot become the current preview without an explicit selection.

`DocumentRepository` provides atomic metadata transitions; `SourceStore` handles staging→durable file moves; `ExportService` renders a frozen snapshot, never repeatedly reads mutable current recipes. Their exact ownership/recovery rules are in [DATA_MODEL_AND_STORAGE.md](DATA_MODEL_AND_STORAGE.md).

## Provider boundaries and build variants

The mandatory build contains no unresolved model and no network-capable SDK. Public algorithms are described independently; direct public code reuse includes notices. Add the first runtime only for a selected asset whose conversion/device gate is being evaluated. Do not package both ONNX Runtime and LiteRT merely because candidate repositories use different runtimes. A research build may enable explicitly permitted experimental assets; it must not contaminate the release allowlist or advertise gated functionality as shipped.

ProviderRegistry exposes capabilities, immutable asset identity, contract version, load cost and validation status. Separate DetectionProvider, GeometryProvider and AppearanceProvider. An image-output dewarper is not a map provider; unsupported paired masks cause preflight rejection. No provider downloads an asset. Model creation, execution and destruction run on the thread required by its runtime; GPU delegate constraints stay inside the adapter.

## Bootstrap decisions and invariants

At T-01 pin Gradle wrapper, JDK, Android Gradle plugin, Kotlin, Compose BOM, compile/target SDK and dependency checksums in a version catalog and lock/verification metadata. Verify compatibility with API 26 and arm64; choose the current supported stable combination from official sources, not a guessed version in this document. Check image backend Android binaries and 16KB-page compatibility where required by the chosen distribution target. Retain exact toolchain output in validation evidence.

Mandatory invariants: immutable originals; no document network calls; one ordered/versioned recipe; complete result or explicit failure; source-to-output coordinate provenance; exactly one strength/protection blend; no stale publication; bounded preallocation; durable journal before scheduling; explicit publish/share consent; and source/weight rights separate from inference success.
