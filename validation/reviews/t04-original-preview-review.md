# T04-PREVIEW-R — independent original-preview pipeline/UI ownership review

Original review, 2026-11-17. Reviewer: independent senior Android image-pipeline and lifecycle engineer, OpenCode / OpenAI GPT-6.1 Sol (`gpt-6.1-sol`). Preserve this report and verdict separately from coordinator reproductions, corrections and acceptance decisions.

## Scope and method

Reviewed the entire frozen uncommitted preview increment against BASE = HEAD `c90f0a6aef156e2bb3c89977c7aa34dcc1bd09dd`, branch `feat/paperlight-v1`, worktree `X:\local-workspace\.worktrees\paperlight-v1`: six modified tracked paths and fourteen untracked paths, including implementation, host/native tests, both new runners, both inventory tests, receipt and live progress/task records. This is the mandatory bounded T04 pipeline/UI review under P-01/U-05, not full T04, application, merge or release acceptance. No nested delegation occurred.

Read root/docs AGENTS; complete current PRD, TODO, PIPELINE_CONTRACTS, incident/commands register, OPERATIONAL_PLAN, CAPTURE_IMPORT_EXPORT, UX_SPECIFICATION, ARCHITECTURE, PERFORMANCE_AND_OFFLINE, DATA_MODEL_AND_STORAGE and SECURITY_PRIVACY; accepted IMPLEMENTATION_PLAN including T04/T09 context; the complete preview receipt and progress ledger. Applied the supplied `requesting-code-review/code-reviewer.md`, including reasonable-user consequences rather than treating specification silence as permission.

Read all new/modified implementation and test definitions, MainActivity/LibraryScreen/strings and the shared native fixture; followed SourceDecoder, CommittedSources/RoomCommittedSources, AndroidSourceInspector/DecodedSource, SourceContainer/CanonicalSourcePixels, DecodeAdmission/ImageAllocationBudget, RgbImage/Coordinates/Numeric/OutputSpec, SourceInspection/document metadata, ApplicationServices/PrivateImportStore, library actions/session/ViewModel/state and all inherited runner definitions. Read the original LIBRARY review and its coordinator correction/disposition receipt as consumed prerequisites, not as a second review of that committed increment. Inspected the pinned Compose layout/constraint classes in existing cached AAR ZIPs entirely in memory; no dependency was acquired or executed.

All implementation, other documents, index, HEAD, raw evidence and ownership records remained read-only. No build/Gradle/SDK invocation, device/ADB/emulator-console/UI/installer operation, network/acquisition, conversation or memory recall, cleanup, Git mutation, worktree creation or artifact-writing test occurred. Python used `-B`; fresh executed checks were memory-only hash/XML/ZIP/class-structure/math/source checks and two read-only runner-inventory tests. The sole write is this report, once via patch. The findings are source/control-flow/math findings, not newly observed Kotlin/native regression failures.

## Strengths and bounded alignment

- Original sizing uses an additional verified CommittedSources read, then the existing full-source SourceDecoder. The sizing inspection/byte identity is compared with delivery; SourceDecoder still verifies the complete oriented source against the retained normalization. No provider URI, second normalizer, source downsample/hash substitution, source mutation, new GC lease, schema migration or dependency/configuration change was introduced.
- Preview sampling reuses Coordinates.limitLongEdge/halfPixelSource and Numeric.roundByte with explicit clamped bilinear neighbors and exact same-size channels. Long arithmetic and the 1024 ceiling bound the packed display allocations. Independent literal math reproduces `60,60,100 / 229,100,100` for 4×2→2×1 and `160,80,100` for 4×2→1×1; the full source inspection/identity remains separate from output size/purpose.
- The raster reserves `6*N` before scratch plus defensive RGB copy while full decode is still charged. The display bridge separately reserves `8*N + 4*width` before opaque sRGB ARGB8888 bitmap, provisional drawing copy and row scratch. Full-source/raster owners close through use/finally scopes. Both stages share one limited-parallelism-two processing dispatcher; existing normalization remains off Main. These are processing estimates, not a claimed PSS/graphics/GC upper bound.
- Loader cancellation/delivery cleanup is explicit. Published bitmap-owner close drops its strong reference/accounting without recycling already issued drawing references; only unpublished/failure-owned bitmap paths recycle. UI ownership is document/page/source-keyed composition, STARTED-only, with a post-suspension cancellation check, finally close and no bitmap in the ViewModel, Room or saved state. The explicit uninterruptible-old-completion consumer exercises stale-owner closure without replacement.
- Original photograph, resized display, edited result and full-resolution detail are distinguished visibly. No recipe, geometry, renderer, export, camera, actual neural or batch capability is manufactured by this preview. The retained synthetic-graph UI evidence is accurately distinguished from the production default-database/picker/provider-grant journey.

## Findings

### Critical — none established

No new destructive source operation, wrong-document durable publication or premature manual recycle of a published bitmap was established. The Important findings below concern an enabled layout crash and selected-page/error usability; matching retained tests does not remove those counterexamples.

### Important — PREVIEW-I1: a valid tall original can crash Compose layout after successful decode

**Evidence status: true by current source, mathematical counterexample and pinned Compose class inspection; no new native reproduction was run.** Locations: `app/src/main/kotlin/app/pristinepage/library/OriginalPagePreview.kt:69`, within the vertically scrolling parent at `app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt:37`. Input acceptance: `image-android/src/main/kotlin/app/pristinepage/image/decode/SourceContainer.kt:260-263`, `DecodeAdmission.kt:15-23`; sizing: `core/src/main/kotlin/app/pristinepage/core/geometry/Coordinates.kt:35-41`.

Image uses fillMaxWidth followed by aspectRatio inside a verticalScroll, so its height is derived from the full available width with an unbounded parent height. A supported opaque PNG of only 2×1024 pixels is within source/decode admission and remains a 2×1024 preview without upscaling. At an ordinary 648px content width the modifier requests `648/(2/1024) = 331776px` height. Even the largest finite axis in Compose's packed constraints is only 262142px. A 1×1024 source requests 663552px. Small, safely admitted rasters therefore do not bound UI measurement dimensions.

Read-only ZIP inspection of the actual cached Compose 1.10.6 classes confirms AspectRatioNode.tryMaxWidth divides width by ratio and rounds, its measure constructs Constraints.fixed, and ConstraintsKt.createConstraints/bitsNeedForSizeUnchecked reject unrepresentable dimensions rather than containing them. Inspected AAR SHA-256: foundation-layout `872c0a93f962517dcc974a63e05f3f43a696b155e08e94411fe4137511c467fd`; ui-unit `5639769ead88bff570bee0f02d88f3889d0e0b0f5c049c0fa4da569ce6f2b5f4`. The exception occurs in layout after ready publication, outside the loading coroutine's structured failure handlers. Opening or automatically selecting such a saved page can terminate the Activity/process repeatedly; source retention does not make that enabled behavior acceptable.

**Minimal fix:** contain the image in a finite, device-appropriate viewport and use aspect-preserving Fit inside that viewport, without deriving an unbounded layout height from the source ratio. Keep the original raster/output identity and source bytes unchanged. Merely adding a height cap after contradictory fill-width/aspect constraints can still make aspectRatio fall back to an unsatisfied size; bound the containing viewport itself. Add a small real 2×1024 or 1×1024 portrait fixture and a wide counterpart, proving successful layout/display or a controlled explanation, reachable page/retry controls and no Main-thread exception. Do not fix this through hidden source downsampling, distortion, or a blanket refusal of otherwise supported originals.

### Important — PREVIEW-I2: page selection has no selected state, and loading/failure does not identify the active page

**Evidence status: true by current UI source; full TalkBack testing was not performed.** Locations: `app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt:69-80`; `app/src/main/kotlin/app/pristinepage/library/OriginalPagePreview.kt:64-73`.

Every page is an identically styled Button with only “Page N”; none sets selected semantics or a selected-state description. The preview heading, spinner, error and Retry text omit the page number. Only a successfully published Image has a page-specific content description. After choosing Page 2 and receiving a resource/integrity error, there is no persistent visual or accessibility indication of which page failed or which page Retry will load. Even successful equal-looking/shared-source pages cannot be distinguished visually by selection. This is a missing static prerequisite of FR-15 and the assigned structured page-scoped error UI, not merely an unperformed broad accessibility gate.

**Minimal fix:** expose the current page through selected semantics and a visible selected indication on the page controls, and retain a page-numbered preview/status heading during loading, failure and retry. Keep retry bound to the existing keyed page/source lifetime. Add semantics/presentation assertions for selecting Page 2, forcing a preview refusal, identifying Page 2 as selected/affected, and retrying it without changing another page. Full TalkBack/large-font acceptance remains separate.

### Minor — PREVIEW-M1: Activity recreation resets the selected original page to the first page

**Evidence status: true by Compose state lifetime; no new recreation test was run.** Location: `app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt:69-70`; related ID-only save boundary: `app/src/main/kotlin/app/pristinepage/MainActivity.kt:85-91`.

selectedPage uses remember only. A recreated Activity builds a fresh composition with null selection and falls back to the first page, although the retained session still selects the same document. A person inspecting Page 2 and rotating the device unexpectedly sees Page 1. No recipe/source data is lost, so this is not Important, but it is a reasonable-user continuity issue for the newly enabled multi-page preview.

**Minimal fix:** preserve only the opaque selected PageId, keyed to its document and validated against the current page list, through a small saveable/retained identity state. Do not save a bitmap, raster, private file or source lease; the recreated composition must obtain its own STARTED display owner. Extend the actual Activity test to select Page 2, recreate and assert the selected page and fresh-owner lifecycle remain correct without a provider reopen.

### Minor — PREVIEW-M2: the same-source/different-page lifetime rule lacks a discriminating consuming regression

**Evidence status: true test-coverage limitation; the production page key is present.** Locations: `app/src/androidTest/kotlin/app/pristinepage/OriginalPreviewScreenTest.kt:119-130,52-76`; implementation: `app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt:80`.

The fixture imports the same encoded bytes twice through separate ingest identities. It does not construct two pages sharing one SourceId or assert that condition. Its page-switch and stale-completion tests therefore do not discriminate a source-only lifecycle key from the required page-plus-source key. Current code explicitly includes selected.pageId, so no existing stale-publication defect is alleged here.

**Minimal improvement:** duplicate a retained page through the existing repository command, assert equal SourceId/different PageId, switch between them and assert a distinct load/display-owner lifetime, correct page label, old-owner close and preserved source/accounting. Retain the different-source stale-completion test as a separate case.

## Independently reconciled retained evidence

These are inspections of coordinator executions, not fresh build/device successes by this reviewer.

- Current anchor `validation/raw/decode/picker-adapter-visible-original-lifecycle-final-manifest.json` hashes to `3ab723a67bdd249b88b0ef0d99b3430432b35df2b15ee69de441040ab3453c0b`. All twenty runtime evidence entries and the base runner match their retained sizes/hashes. All 241 current source/test/build/runner input hashes match. The 224 pre-build inputs in `visible-original-lifecycle-command.json` also match; its exact offline/strict argv, worktree cwd, four-worker limit and scoped environment are retained, with ANDROID_SDK_HOME null.
- Invocation SHA-256 is `d17b68f10919d0fce95474a190e2dbb5feb35b5fd81012d7201b6f68cbb49629`; build log SHA-256 is `d64f4d8dbf0e70cba2d78d42c1c9f456fdbc6be373559afb4e52d7873dd69285`. The log ends BUILD SUCCESSFUL in 1m5s, 195 tasks / 8 executed / 187 up-to-date. All 58 inventory XML hashes match and parse to 527 recorded cases, zero failures/errors/skips: core23, fixtures15, data396, image44, app49. These are recorded current-input reports, not 527 freshly executed cases in that invocation. The inventory retains seven lint warnings and the AutoboxingStateCreation Hint, not a warning-free lint claim. The receipt's aggregate 37 host checks were not all rerun here.
- Current main APK is `71673258e1f0a021913c36080d3c554761116cba39f1e5f65eb9d89a927b2cd7`, 18142321 bytes; test APK is `1d26e0668ffc0b6d800724b20f2241142c77ac9ec9c5799fcbd98e4221e2303b`, 1220000 bytes. Built and retained bytes, saved installed hashes and actual AGP output metadata agree. ZIP CRCs pass, stored entries are four-byte aligned and the three stored main native libraries are 16KiB aligned. DEX class definitions contain the new raster/bitmap/UI implementation and all three new native test classes. Saved SDK signature reports verify the same named development certificate/v2 scheme; no SDK cryptographic command or live installed-byte query was rerun here.
- Derived all 45 named native cases from the literal runner class additions and actual test source, then independently parsed instrumentation: exactly 45 starts, 45 passes, no failure/error, Time16.243s / OK(45 tests). Saved outcomes agree. All 45 command transcript entries have exit0/no timeout and match their retained stdout/stderr. The pre-shutdown transcript-prefix length/hash matches `c9131d0ee7475c79d85c6e8e868297aa02b9cb2bf090d8d53cf208cc155b3db2`.
- Current source agrees with the bounded test graphs: real Room/private-byte/ImageDecoder/raster samples, bitmap opaque sRGB samples and issued-reference survival, private synthetic Activity-content rendering/capture/aspect, page switching/disposal, uninterruptible older-owner closure, shared-pressure/no-image/explicit retry and CREATED→RESUMED release/reload. The two screen pages are still the same asymmetric 4×2 encoded picture; a different page label is not an independent different-picture content oracle. Default production graph wiring was inspected statically, not exercised by replacing Activity content with that graph.
- Rehashed every evidence entry/base runner in earlier preview anchors: raster39 `e1ba153c418edbddca2fc3989c9a504c06f3a06d85e90f906a594e4129ea77e9`, bitmapRED `a1d7c0689fe2f9b0a05514aba99d8f2a8ed53ed679185f37c778f54ef6811478`, bitmapGREEN `95e4a5e2f3ed6b55486f39acb5ff8094ab21febb547a4608d0173a40130ff81f`, visibleRED `46977486e3128075ab6a5e09986cdc8d943cc9f699a7db964b0f78b9c3aa8e62` and visibleGREEN `6627f4fe7a45e01e8d95f99f17a8b955848e5696a76cbbf29305d305a4ad077b`. Actual instrumentation preserves 39PASS, then 39PASS+two exact missing-bitmap failure blocks, 41PASS, then 41PASS+two exact missing-visible-preview failure blocks, 43PASS. Bitmap test APK `30990a1a53992cb2c6ef9f16c0f6aeb5055d4bbf8d91d10c407fa32ca06ff5b7` and visible test APK `b25aa240a87c148433bf81f1cb3fea8443822915c68fadca4fb529f4db075f98` are unchanged across their respective RED/GREEN pairs. The host RED XML has seven exact absent-loader assertions; current OriginalPreviewTest has seven GREEN cases in the matched XML. The retained interrupted-build log actually ends SUCCESSFUL in 1m57s / 195 tasks / 20 executed; it was not rebuilt here.
- Saved/current JSON records agree on generation `6bf06770-0cbc-4178-b159-5bf49924e297`, player25968/start134393479942985941 and VM18940/start134393479971438388. Both original identities have wait0/exit0/not running; all known list rows are stopped, remainingRunning is empty, storageDeleted=false. Current shared records are owner-null/stopped-released/shutdown-verified/completed cleanup, matching released allocator slot/current evidence pointer and absent allocation lock. This is saved evidence/current-record reconciliation, not a new process, console or device query.
- Fresh reviewer execution: the two new read-only runner inventory tests passed in 0.003s; independent literal sampling/constraint math and the above read-only checks passed. Two preliminary reconciliation attempts stopped only at reviewer-checker assumptions: build/invocation entries do not carry a bytes field, and stack traces repeat a missing-feature marker. Corrected schema-aware/hash and per-failure-block checks passed without changing application or evidence bytes. No new Kotlin/device failure or success is implied.

## Prior findings, permission and preservation

The original LIBRARY NEEDS FIXES report is unchanged at SHA-256 `303e513077b3437edcace8088b0558781408287b3fc2bb02d7ec4736cffe3442`. Coordinator I1–I4 reproductions/corrections and their saved evidence remain separate; this review does not rewrite or rerun that verdict. LIBRARY-M1 and prior D-M1, ROOM-M1, SOURCE-M1, IMPORT-M1, PICKER-M1, CANCEL-M1, COMPOSE-M1 and earlier contracts/storage/snapshot/audit/bootstrap limitations remain separate, not silently closed.

Accepted-plan SHA-256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. No schema, dependency/version/configuration, identity, durable source ownership or original bytes were changed by this increment/review. Source/page/history ownership remains an existing prerequisite; a preview read is not a new GC lease. Immutable source/root paths, LDPlayer-only limits, the existing signer and exclusions of phone, acquisition, deletion, production signing, external sharing/publication and Git push/merge remain binding.

## Recommendations

Reproduce PREVIEW-I1 with a tiny tall fixture before changing layout; use a bounded containing viewport rather than trying to catch a post-publication layout failure in the loader. Reproduce PREVIEW-I2 through selected/busy/error semantics and fix the indication without moving bitmap ownership into retained state. Address or explicitly retain the Minor findings; preserve this original report. Reuse existing Compose/repository facilities, then obtain affected focused/current-build evidence under the coordinator's existing permissions. Do not rerun unchanged heavy/native checks merely to decorate this review or treat the 45 saved passes as coverage of the new counterexamples.

## Declined to judge — one behavior per line

- Complete production default-MainActivity database/import/reopen-to-preview journey: new screen tests replace Activity content with an explicitly private synthetic graph/session; static wiring is narrower evidence.
- Actual external picker selection/cancellation UI: no real external picker journey was executed by these tests or this review.
- API26/no-Play-services document fallback UI: API28 adapter/content evidence does not establish the older-platform journey.
- Real provider temporary/persistable grant acquisition, revocation, release and cloud availability: injected byte delivery and preserved flags are not platform grant experiments.
- Actual Activity Result delivery through OS process death/force-stop/reboot: retained-VM/registry/parcel prerequisites do not establish those conditions.
- Complete visible mixed-import outcomes/cancellation/recovery/reselection journey in the production UI: current session and preview consumers are narrower; no end-to-end success is inferred.
- Full TalkBack focus/announcements, large-font reflow, contrast, Switch Access and reduced-motion acceptance: these experiments were not performed; the static selected-state defect is judged above, not waived.
- Device-level selected-page recreation/rapid-navigation/lifecycle stress envelope: source lifetime and the specific saved consumers were inspected; PREVIEW-M1 remains an explicit continuity finding, not a claim of dynamic coverage.
- Measured large-library frame time, loaded-window memory and scroll restoration: inherited LIBRARY-M1 remains open and no performance envelope was measured.
- Absolute graphics/PSS/GC retention bounds and sustained preview-open/close accumulation: published draw references intentionally outlive application ownership; processing estimates and functional budget-zero checks are not those measurements.
- Measured decode/sampling/display latency, thermal behavior and physical minimum-device headroom: emulator timings cannot supply handset performance acceptance.
- Universal synchronous SQLite/native-code interruptibility and cancellation latency: cancellation fences were judged, not a promise that every native call can be interrupted.
- Direct exhaustive loader delivery-cancellation and bitmap-stage-specific contention fault injection: current cleanup code was inspected, but pre-canceled host work/early shared-pressure and UI stale-return tests do not exercise every internal failure point.
- API26/27 strict decoder implementation and compatibility: these remain explicitly unavailable in the consumed inspector, not established by minSdk metadata.
- ICC/non-sRGB conversion and broad native WebP/HDR/gain-map compatibility: the unchanged decoder's refusal/support policy remains separately gated.
- Arbitrary malformed image/EXIF/profile adversaries beyond retained tests: no new parser audit or universal codec-safety acceptance was performed; display-dimension safety was judged in PREVIEW-I1.
- Representative rights-cleared photograph/page visual fidelity, small-text readability and resampling quality across real content: tiny synthetic literal/captured samples do not establish that gate.
- Full true-100%-detail or preview/export equivalence: this component explicitly supplies a resized original preview, not either capability.
- Every-source integrity merely from a metadata library display: only a consumed preview's original is verified; catalog counts are not a global file audit.
- Current recipe/schema/provider/render executability of each catalog page: original preview deliberately bypasses recipes and is not edited-output readiness.
- Whole-library/inactive/out-of-cursor corruption auditing and complete import history/digest validation: bounded discovery/read prerequisites are not global audits.
- Full source/history/mask/job/snapshot owner legitimacy, pin repair and counter reconciliation: preview reads change no ownership and authorize no repair.
- Real-codec crash-boundary, dirty-WAL, filesystem/power-loss and hardware durability breadth: selected earlier persistence evidence does not establish every timing or storage failure.
- Autonomous scheduling, durable fairness, wider leases and all-domain startup recovery: the Application graph remains a bounded prerequisite, not completion of these subsystems.
- Camera permission/refusal/revocation, CameraX still orientation and hardware behavior: camera remains unavailable in this increment.
- Complete rename/reorder/duplicate/rotate/remove/Undo editor/library journeys: existing repository commands are not a finished editing UI.
- Confirmed deletion, finalization, pin release, GC and retention cleanup: these remain independent unfinished gates and no deletion authority was granted.
- U-04/FR-17 changing-selection cumulative album edits and atomic Done: no batch-edit implementation is supplied by this preview.
- U-01 genuinely implemented neural geometry and appearance: no provider asset/runtime acquisition or neural output is accepted here; the requirement remains mandatory.
- Perspective/manual-curve geometry, appearance/detail/binary processing and carousel comparisons: this explicitly original-only view is not an implementation of those future tasks.
- Snapshot execution, mask/provider/accepted-degraded retention and render jobs: no completion of these downstream consumers follows.
- Usable multipage PDF/JPEG/PNG export, independent output readability/readback and whole-writer resource profiles: export is unavailable and untested here.
- External publication/sharing, recipient grants and share-copy retention: prohibited external effects and later export gates remain separate.
- Populated released-version migration/downgrade and damaged-schema recovery: unchanged version1/source hashes are not migration acceptance.
- Hostile OS/same-UID/schema/root replacement: consumed private-store integrity assumes cooperative retained roots/schema, not tamper-proofing.
- Full runtime privacy, backup/device transfer, secure-window and offline cold-start acceptance: safe new state/ownership structure and saved package reports are narrower evidence.
- Dependency/model/sample rights, final branding/listing/legal clearance, production signing and distribution: artifact hashes supply none of these approvals.
- Generic inherited runner partial-launch/timeout/failure cleanup in every environment: current successful release records were reconciled, not all failure branches replayed or certified.
- Whole T03/T04/T09/T10/T12, first-version/scanner completion, merge or release acceptance: this bounded independent review grants no such decision and waives no mandatory remaining work.

## Assessment

**Bounded compliance verdict: NEEDS FIXES.** Full-source identity, admitted ownership, original-only labeling and nominal stale/lifecycle behavior align with the assigned slice, but the enabled display is not safe for all admitted aspect ratios and lacks the required selected-page/error indication.

**Bounded quality verdict: NEEDS FIXES — 0 Critical, 2 Important, 2 Minor.** The retained 45-native/527-recorded-JVM evidence is genuine within its stated coverage; it does not exercise PREVIEW-I1/I2. No full task/application/merge/release acceptance is given.

## Commands and sole write accounting

Executed only dedicated read/glob/grep, read-only `git --no-optional-locks status --short --untracked-files=all`, `rev-parse HEAD`, `branch --show-current`, scoped `diff` and `diff --check`, and inline Python `-B` checks described above. The final pre-report diff check returned0; final post-report hash/input/HEAD checks are read-only. No temporary files were created.

Exact write: create `validation/reviews/t04-original-preview-review.md` only, once via patch. No fix, other document, raw artifact, ownership record, index, HEAD or branch was written. Report SHA-256 is returned separately to avoid a self-referential digest.
