# O-EDITOR-R — consuming editor/display/persistence review

## Scope, version and authority

Independent Android lifecycle, persistence and image-ownership review, dated 2026-11-17. Original verdict: **NEEDSFIXES**. Counts: **0 Critical / 1 Important / 2 Minor**. This verdict concerns the bounded O increment, not completion or acceptance of T-03/T-04/T-06/T-07/T-09/T-10 or the application.

Reviewed primary worktree `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`, confirmed HEAD/base `e61d87a3b5f805c655832e0f9126c5fd70cbb2e1`, plus the frozen uncommitted O product/tests. Inspected `git --no-optional-locks status --short --branch`, `rev-parse HEAD`, `diff e61d87a3b5f805c655832e0f9126c5fd70cbb2e1 --stat` and the complete tracked product diff; separately read every new assigned product/test file because Git diff omits untracked files. Parent validation/evidence activity was concurrent; it is not a product change made by this reviewer.

Read complete root `AGENTS.md`, `docs/specs/{AGENTS,PRD,TODO,PIPELINE_CONTRACTS,common-mistakes-and-frequently-used-commands,OPERATIONAL_PLAN,UX_SPECIFICATION,ARCHITECTURE,PERFORMANCE_AND_OFFLINE}.md`, accepted `IMPLEMENTATION_PLAN.md` T-09/T-10, and the complete requested `requesting-code-review/code-reviewer.md` template. Applied effect-based severity and reasonable-user expectations, including enabled behavior not enumerated in the specifications. Accepted plan SHA-256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Read all new `app/.../editor/{EditorState,EditorSession,EditorLooks,ApplicationEditorActions,EditorContent,PageEditorScreen,EditorViewModel}.kt`, changed `PreviewScheduler.kt`, `MainActivity.kt`, `library/LibraryScreen.kt`, resources, three new app JVM test files, four new app Android test files, image `RenderedPreviewBitmap.kt`, `SrgbPreviewBitmap.kt`, changed `OriginalPreviewBitmapLoader.kt` and new image JVM test. Consumed existing `RecipeHistory`, `ApplicationServices`, `PristinePageApplication`, `ImportLibrarySession`, `ImportLibraryViewModel`, `OriginalPagePreview`, `PrivateImportStore`, `RoomEditablePages`, `EditablePage`, the actual `RoomDocumentRepository.saveRecipe` implementation, `GeometrySizing`, pipeline contracts, `ImageAllocationBudget`, shared `PreviewProcessing` and `DefaultRenderCoordinator` as boundary context. Read the dedicated display runner without executing it. This is not a new numerical review or a replacement for N's original review/fixes.

The worker report at `X:\local-workspace\.worktrees\paperlight-editor-display\validation\editor\t09-rendered-display-worker.md` was read only and hashes to `d57536dca1f4cc13af68e7c3ec2c8914bd1f6d06e209ec05628f7a408fc70574`. The four primary replayed image production/test hashes below match that report. Candidate `2ce220e2e19cb03d5e2c71162430cb6cde8f4bbb` and its worktree remain untouched; historical worker evidence was not rerun.

## Evidence used, and evidence not executed

- Reviewed retained `validation/editor/t09-editor-display-pre-screen-receipt.md` (SHA-256 `0bac8385a4191ef688cf4cfe3d9782e7c0ed6d6cdcf9e18dab9b449fcef3fe10`) and both pre-screen and screen/host-RED outcome inventories. Pre-screen evidence is 859 recorded JVM cases and 40 Android starts / 38 passes / 2 intended missing-screen assertions, not 40 passes. It includes real rendered/original bitmap cases and Application/editor-session Apply/save/reopen/rotated-display consumption, not visible editor controls at that version.
- Read the subsequent three-case screen/host outcome inventory: both screen cases pass, while `EditorHostTest` has the intended missing retained-host assertion. This is the historical `05693944…` / `c5323d04…` pair, anchor `180a32e7…`, generation `320265e2-5290-4862-9059-38aea1893c37`; it does not validate the subsequently implemented ViewModel/navigation.
- During review, the parent produced `validation/raw/editor/editor-host-current-build-command.json` (SHA-256 `8bdf7cb9f89b66e0e6b0fe5590d29d91c48b92b851e1966741bb1670800ab2c8`). Its recorded exit is 0, duration 48.102816 seconds; the retained log says 195 tasks, 27 executed / 168 up-to-date. The product hashes in that manifest match the frozen files inspected here. Progress records 859 reports, app 86 fresh / 773 cached overall; the log independently shows data/image unit tasks UP-TO-DATE and the app test task executing. This is not 859 newly executed tests.
- Directly inspected current retained XML: `EditorSessionTest` 14/0 failures/0 errors, `EditorLooksTest` 5/0/0 and `PreviewDisplayGenerationTest` 4/0/0. The first and third XML hashes are `3e10b481672e250ca33bf69d64504b326239b7303f85a056d8168be875502d6d` and `5af2ebf149102dcbcaf2d29613309a8548e36bdbd2494d7ef68a6ebd68d34b8a`. These are inspected parent results, not reviewer executions.
- No builds, tests, SDK/signature tools, ADB, UI actions, executable probes, network, acquisition, Git mutations, memory/conversation operations, cleanup or delegation were executed by this reviewer. No current native/full-41 success is asserted. Current artifact installation, retained-host/background/recreation and full native evidence remain the parent's independently recorded obligation; later evidence does not retroactively alter this original report.
- Read-only shell commands were limited to the Git inspections above and `Get-FileHash -Algorithm SHA256 -Path <named files> | Format-List Path,Hash`. Dedicated file read/search tools supplied the remaining inspection. The sole authorized write is this report, created with the patch tool. **Build slot never held.**

## Strengths

- The consuming path uses the initialized Application graph: `EditorViewModel.kt:33-36` supplies its existing renderer/budget, and `ApplicationEditorActions.kt:9-10` delegates guarded metadata read and exact save-CAS to that same store. Neither the screen nor the bitmap adapter creates another source decoder, provider handoff, database or processing allowance.
- Complete-owner publication is correctly placed after conversion: `PreviewScheduler.kt:47-65` verifies complete request equality, OK and actual raster dimensions, keeps the entire result alive through conversion, then rechecks cancellation/current request and display request identity before transferring both owners. Rejected/stale/late candidates are closed in `finally`; replacement and reversible pause detach state before releasing both owners. DEGRADED is not automatically published as current.
- The image adapter independently enforces OK/PREVIEW/exact-size/1024 preflight. Shared `SrgbPreviewBitmap.kt:30-52` uses checked Long accounting before bitmap/row allocation, opaque explicit sRGB ARGB_8888, density NONE, unsigned active RGB rather than padding, and row checkpoints. Delivered close does not recycle issued draw references. The extraction keeps original and rendered conversion policy aligned without retaining an unaccounted RGB lease.
- Accepted and draft recipes remain separate. `EditorSession.kt:96-119` captures page, expected recipe revision and whole draft before suspension, blocks edits while saving, and accepts only matching page/hash plus strictly newer recipe/document revisions. Conflict, rejected and malformed success leave the draft/baseline distinction intact. Terminal load/save publication is fenced after suspension; Undo/Redo remains recipe history rather than cumulative pixel filtering.
- Back offers Keep editing/Discard/Apply without implicit save. Preset mappings preserve geometry and explicitly implement full No-enhancement bypass versus compatible finishing. The screen supplies a finite 300dp fit viewport, explicit previous-output notice, error/retry states and 48dp minimum controls. ViewModel/session ownership and ID-only saved state are appropriate mechanisms for draft retention across Activity recreation and safe accepted-state reopen after process loss, subject to the native gates below.

## Issues

### Critical

None established in this bounded review.

### Important

#### O-I1 — display allocation exhaustion escapes the resource-error boundary and can terminate the editor

**Evidence status:** true from the exception/control flow; actual memory-pressure trigger was not executed here. **Severity:** Important, because enabled preview display can crash and lose an unsaved in-memory draft rather than report a controlled resource failure; not graded Critical merely because a resource exception is possible.

**Locations:** `image-android/src/main/kotlin/app/pristinepage/image/preview/SrgbPreviewBitmap.kt:35-52` (native bitmap and row allocation); `image-android/src/main/kotlin/app/pristinepage/image/preview/RenderedPreviewBitmap.kt:34-51` (only cancellation, RepositoryFailure and Exception catches); `app/src/main/kotlin/app/pristinepage/editor/PreviewScheduler.kt:43-83` (conversion outside rendering, again only Exception); production scope supplied by `EditorViewModel.kt:36`. The shared original adapter also has only Exception handling at `OriginalPreviewBitmapLoader.kt:26-28`.

**Causal failure:** the logical image-budget reservation does not guarantee that Android/native bitmap or Java row allocation succeeds, particularly because residual graphics/GC allocations are explicitly outside that accounting. `OutOfMemoryError` is not an `Exception`. If bitmap creation, row allocation or native packing throws it after the renderer has returned, the primitive's `finally` releases its provisional resources, but the error bypasses both display-loader and scheduler error mapping. It escapes the Main-owned launch to the uncaught coroutine exception path instead of producing RESOURCE_LIMIT and retaining the previous preview with an error. The existing renderer's explicit OOM mapping at `DefaultRenderCoordinator.kt:30-34` cannot protect conversion performed later at scheduler line 57. Checked sizes and a successful reservation therefore do not close this newly enabled consuming failure path.

**Bounded reproduction idea for the parent:** first publish a real successful result/display pair, then inject an `OutOfMemoryError` from the existing controlled display-converter boundary on the next request. Assert that no uncaught coroutine error escapes, the new complete result closes, the old pair remains clearly previous, draft/accepted state is unchanged and failure is RESOURCE_LIMIT. To verify the concrete loader rather than only its consumer, inject a bounded failing allocation/conversion seam and assert provisional-permit release and borrowed-input survival. Do not deliberately exhaust the emulator/host or allocate a huge raster to reproduce this finding. None of these proposed checks was run by this reviewer.

**Recommendation:** after the test-first reproduction, narrowly translate display allocation OOM into the established resource failure while retaining cleanup of any privately completed output and the caller-owned result. Reuse one shared conversion/error policy for the rendered and extracted original routes; do not swallow unrelated VM Errors, silently resize, retry indefinitely or turn this into a generic success/fallback. Verify both the adapter boundary and scheduler's previous-pair behavior.

### Minor

#### O-M1 — returning from a nonfirst page editor resets the selected library page

**Evidence status:** true from composition/state ownership; native round-trip reproduction not run here. **Locations:** `app/src/main/kotlin/app/pristinepage/MainActivity.kt:104-108`; `app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt:71-82`.

The selected PageId is only a local `remember` inside LibraryScreen. Opening the new editor removes that whole library composition; Back constructs it again with null selection and chooses the first page. For a multipage document, select page 2, enter its editor, return, then inspect the selected-page semantics and Edit button: they now target page 1. This does not silently save another page—the visible button updates its number—but it interrupts the user's editing context and makes accidental edits to the next opened page more likely. It is a new navigation round-trip trigger, not a claim that the earlier original-preview recreation Minor was fixed or newly Critical.

Retain the selected opaque PageId in the library/navigation owner keyed by document, validating membership on refreshed metadata. Add one multipage enter/Back regression. No pixel/recipe/provider state needs to be saved to retain selection.

#### O-M2 — conflict guidance asks for a refresh that the loaded editor cannot perform

**Evidence status:** true from enabled state/actions; conflict UI reproduction not run here. **Locations:** `app/src/main/kotlin/app/pristinepage/editor/EditorSession.kt:30-31,104-118`; `app/src/main/kotlin/app/pristinepage/editor/PageEditorScreen.kt:48-50,81-82`; `app/src/main/res/values/strings.xml:42`.

On conflict the draft is safely retained, but the message says “Refresh before trying again.” The loaded editor exposes no Refresh; its load retry is hidden when a page exists and retryLoad would return immediately anyway. Apply repeats the old captured expected revision and conflicts again. The only current route to a new baseline is to leave after explicitly discarding and reopen. Reproduce with a retained page changed externally before Apply, then follow the displayed guidance. This is Minor because CAS/baseline safety is correct, no success is falsely reported and leaving requires an explicit draft decision; the defect is the unusable recovery instruction.

At minimum, explain the actual safe choices and any draft loss before reopening. A later draft-preserving conflict-resolution workflow is an alternative, not a reason to auto-update expectedRevision and overwrite the winner. Keep the conflict draft until the user's explicit choice.

## Recommendations and verification handoff

The parent should reproduce O-I1 test-first and fix only the verified consequential boundary; retain this original verdict and separately record its disposition rather than requesting a recursive review. Record O-M1/O-M2 in the Minor ledger if deferred. No product fix is included in this report.

Finish the already pending exact-current native host/full affected run and authenticate its artifacts/outcomes/release separately. In addition, an actual MainActivity default-library journey is still needed: select a nonfirst retained PageId, open its editor without replacing Activity content, mutate/Apply or explicitly discard, return/refresh, and reopen. `EditorScreenTest.kt:28,49` replaces Activity content; `EditorHostTest.kt:36,40,51-52` uses a separately keyed fixture ViewModel and reinstalls fixture content. Those are useful real Compose/Room/renderer consumers, but do not exercise MainActivity's default navigation, its saved-ID restoration or selection retention. A fresh-process/opaque-ID restore check must not be relabeled as draft survival; only Activity recreation promises the current in-memory draft.

Keep lifecycle checks adversarial but bounded: current published display during STOP/START and recreation, rotation while conversion is privately finishing, and terminal close during load/save. Consider an unrelated recomposition while a display lease is detached, since `PageEditorScreen.kt:55-56` obtains the bitmap through an owner getter that intentionally rejects after close. I did not establish a reachable default-Activity stale-getter crash from static inspection, so that possibility is not promoted to an Important finding. A delivered draw reference remaining readable is already a separate, correctly targeted native oracle; it is not proof that every future owner-getter access is safe.

## Declined to judge / unaccepted behaviors

These are explicit limitations, not waivers. No enabled stale publication, false Saved, lost-draft or crash behavior was excluded merely because the specification did not enumerate its trigger.

### Earlier boundaries retained, not recursively reopened

- Perspective/manual-curve numerical proofs, sampler interpolation, topology and appearance arithmetic: consumed as established interfaces; no new numerical audit or changed verdict on earlier reviews.
- N raw SQLite/Room guards, canonical/source-integrity corrections and their deferred mask/TEXT coverage: current read/save interfaces were traced, but N's original NEEDS FIXES/corrections/minors were not rewritten or reaccepted here.
- Source GC/pins, destructive deletion, broad WAL/power-loss/store recovery and durable job ownership: metadata lookup is not a GC lease; these are earlier/later separate contracts, with no newly enabled deletion or GC feature certified by O.

### Required product features beyond this bounded basic editor

- Shape controls, crop/curve handles, magnifier and Detail/strength controls: absent and explicitly unfinished; existing backend support is not visible-feature acceptance.
- Carousel/neighbors/debounce/cache, Info/provenance presentation, synchronized Original/geometry-input/result comparisons and true 100% full-context detail: not implemented/accepted by this selected-preview screen; a resized preview is correctly not called full-resolution detail.
- U-04 album selection, cumulative subset rotations/settings, Mixed state and atomic batch Done/Undo/Cancel: mandatory and unfinished; individual-page rotation cannot satisfy it.
- Durable masks, persisted execution provenance, pinned accepted-DEGRADED output and degraded export consent: not supplied by this transient display/CAS path; masked page refusal is not durable mask support.
- Camera, document management beyond the existing library subset, export/snapshot rendering/verification and sharing: no acceptance from this editor increment; disabled/missing features remain required work.
- U-01 multiple genuine neural geometry and appearance methods, asset/runtime/provider gates: not implemented or satisfied by classical presets, registry placeholders or unavailable cards.

### Evidence and environment boundaries

- Current retained-host/background/recreation/full-41 native behavior: pending parent evidence at the review cutoff; historical missing-host RED and prior screen GREEN cannot certify current lifecycle behavior.
- Actual default-library navigation, real MainActivity saved-ID restoration/fresh-process accepted reopen and the possible detached-getter recomposition sequence described above: source mechanisms inspected, but no execution claim or demonstrated stale-getter defect; fixture-content tests are not substitutes.
- Actual native mid-allocation/row failure and completed-but-undelivered bitmap cancellation: source cleanup traced; host rejection/queued-cancel and successful draw-safe-close tests do not execute every native failure timing. O-I1 remains an enabled defect, not an excluded gate.
- P-06 real system-picker selection, platform grants and the default import-to-editor journey: no new evidence from private synthetic byte delivery or provider-forbidden reopen tests.
- API26/27 and wider codec/profile/WebP/HDR compatibility, OEM behavior and physical-device resource/performance/thermal results: not established by API28 LDPlayer evidence; no physical phone operation is authorized here.
- Representative real-page fidelity, color/content retention, subjective visual quality, full TalkBack/large-font/contrast/Switch Access/reduced-motion acceptance and complete accessibility coverage: minimum target/finite-layout source checks and basic Compose tests are not these gates.
- Graphics/PSS/GC lifetime, sustained memory-pressure cycling and latency distributions: logical permit accounting and unrecycled draw references do not measure or bound all platform memory; no benchmark guarantee.
- Source/sample/model/dependency rights clearance, production signing, publication/store listing and installable final-artifact acceptance: outside this read-only review; no acquisition, SDK verification, installation or publication was performed.

## Assessment

**Ready to integrate this bounded increment unchanged? No. Original verdict: NEEDSFIXES, 0C/1I/2M.** The complete-owner preview and captured-CAS design is coherent, but post-render display allocation currently bypasses controlled resource failure and can terminate an editor holding unsaved work. Resolve the consequential finding with bounded evidence and retain the separate native/lifecycle/product gates; neither this review nor a successful build accepts the whole task/application.

## Frozen reviewed file identities

SHA-256 of inspected primary bytes; paths are relative to `X:\local-workspace\.worktrees\paperlight-v1`. These identify the original review, not later parent fixes.

```text
57b7101719cf77961e91249268b8631523388ec2e9be357b0d0e8cb6513aa7f1  app/src/main/kotlin/app/pristinepage/editor/EditorState.kt
4843a0dfb5431713d88aa95439c22bee66ed918dd676dd528527e541704a383e  app/src/main/kotlin/app/pristinepage/editor/EditorSession.kt
6adaf16efbb23a516b78ca2607dc410e63f0f99517af82ee32b433d8214e91be  app/src/main/kotlin/app/pristinepage/editor/EditorLooks.kt
619899f57a4bc51058ab9fc7d671a5c2deb326e8906b4914395be3595ad8ed03  app/src/main/kotlin/app/pristinepage/editor/ApplicationEditorActions.kt
8521cb176d79ac7d0aaf162bacbe949b9927ebd21786532fe1d5765c52283ce3  app/src/main/kotlin/app/pristinepage/editor/EditorContent.kt
a64bce17dada604c358245e31551d1b8da3aca9c8206e0d3b00007e0de91a368  app/src/main/kotlin/app/pristinepage/editor/PageEditorScreen.kt
c90672581bcdfd56797e23b62d2b80871f1e04dfa2ca549178a00d154e13d601  app/src/main/kotlin/app/pristinepage/editor/EditorViewModel.kt
70ba00d5e1953167ac6a1a4f2446465f0ef287e3ab166b7ecf9c70b305c3984c  app/src/main/kotlin/app/pristinepage/editor/PreviewScheduler.kt
3b1e4ff1cadb2ffdf598b1c7b110af809717a13ce460576191d23380abf68105  app/src/main/kotlin/app/pristinepage/MainActivity.kt
68bbcfc5c83854e7b9b7d2c979b05a18d6a2cd043f4503df05e741ef45f5549f  app/src/main/kotlin/app/pristinepage/library/LibraryScreen.kt
fe9453f2f81f621ca4564bbf502290b836dbeb6ae0a4c6ba457bf13e437cad61  app/src/main/res/values/strings.xml
95c3a0447b43403919a1d95aff41c4289dc03150692112ceac3831fbc9305b0c  image-android/src/main/kotlin/app/pristinepage/image/preview/RenderedPreviewBitmap.kt
c4fdc08e416d5cd7117f07b197c8c870b3bb36d393bdb191dabf5fec0e446a73  image-android/src/main/kotlin/app/pristinepage/image/preview/SrgbPreviewBitmap.kt
412b05b61cbb47ec2ce32ab4f76be5f1ab91d45144095794ccc4fcd2812eb137  image-android/src/main/kotlin/app/pristinepage/image/preview/OriginalPreviewBitmapLoader.kt
35fa0aeeaece650799b69f851e1957bb799966bf0bb8c0bf526436dd755ffe6b  app/src/test/kotlin/app/pristinepage/EditorSessionTest.kt
18decf77129afa754d6b654afc42d1ab12b62ccf9a7422333eb71bb516147f44  app/src/test/kotlin/app/pristinepage/EditorLooksTest.kt
0590c2b3f53951bd1088cff0dffab90bbec42357d7ed4041cfffac9c3afb62fe  app/src/test/kotlin/app/pristinepage/PreviewDisplayGenerationTest.kt
16409e5491ad5ceea37a1190dfcf79a9dac9a99597119da62d9b9a42e6c04bfd  app/src/androidTest/kotlin/app/pristinepage/EditorHostTest.kt
b4b46f7be88814485bec87dc072a710e46fa798eb170a3f5c0aaa6fbef223053  app/src/androidTest/kotlin/app/pristinepage/EditorScreenTest.kt
78803cb7344aa7b109c8cfacc13c31cf4595111cbe4b6b235f648d1e319894ee  app/src/androidTest/kotlin/app/pristinepage/EditorSessionIntegrationTest.kt
d551bdbc742f3db07e986cbd0b74214fdc48121c1af9ebee10c2438c0318b91a  app/src/androidTest/kotlin/app/pristinepage/RenderedPreviewBitmapIntegrationTest.kt
4ff81b92bfff6fff46783daec04096439f9e38e923c66694ab93449652405c50  image-android/src/test/kotlin/app/pristinepage/image/RenderedPreviewBitmapTest.kt
```
