# T06-HOMOGRAPHY-OWNER-R-20261117-B — original independent review

Date: 2026-11-17. Reviewer role: senior numerical-imaging and Android resource-ownership reviewer, fresh context, OpenCode / OpenAI GPT-6.1 Sol. This is the original verdict; subsequent coordinator corrections or dispositions must not overwrite it.

## Scope and immutable input identity

Primary checkout: `X:\local-workspace\.worktrees\paperlight-v1`. Read-only Git inspection confirmed HEAD/base `5a3b4cec167cff255573e830476a7c36272414e9`. The candidate is uncommitted: the Contracts change is modified and the other five reviewed files are untracked. Contracts review is limited to the RenderResult amendment and its AtomicBoolean import; unrelated contract definitions were read for context, not reaccepted.

The following are the exact absolute reviewed paths and independently recomputed SHA-256 identities:

- `X:\local-workspace\.worktrees\paperlight-v1\core\src\main\kotlin\app\pristinepage\core\geometry\Homography.kt` — `9c331c0379231973743af3878c0d90fd0ca45f3d1f115ba88e87f7852049b26a`.
- `X:\local-workspace\.worktrees\paperlight-v1\core\src\main\kotlin\app\pristinepage\core\pipeline\Contracts.kt` — `f03992fef075a7df6e7ee0499f62dfcd38d195a9070102ae5ec2fa7a26f46e47`.
- `X:\local-workspace\.worktrees\paperlight-v1\image-android\src\main\kotlin\app\pristinepage\image\render\GeometryEngine.kt` — `a1f633f4401a4a1e6ce299085e849f34f122e99dcd4a7e203f5cdd7c2e518d91`.
- `X:\local-workspace\.worktrees\paperlight-v1\core\src\test\kotlin\app\pristinepage\core\HomographyMathTest.kt` — `606f6e159c025aa9f62972c4b9f94b66c91004cb099ad18157cc917a565ef966`.
- `X:\local-workspace\.worktrees\paperlight-v1\core\src\test\kotlin\app\pristinepage\core\RenderOwnershipTest.kt` — `5c636a1a130f3a8f6f55f7368236511981b2264c8306ea88ba374ee0518e826c`.
- `X:\local-workspace\.worktrees\paperlight-v1\image-android\src\test\kotlin\app\pristinepage\image\GeometryEngineTest.kt` — `da90d517c66b391511733ba93cb0821b5668d6ca5746b9407d25c2af54639d4b`.

Frozen integration manifest `validation/raw/geometry/geometry-appearance-integration-1-command.json` independently hashes to the supplied `dec834dd97afcd5ef950e6556a279d54756a0ce377c8e6bdd676d2b1b26da639`. All 231 recorded input files currently match their recorded hashes, with zero missing files or drift. Identity-only hashing of the wider input set is not semantic review of the separate appearance candidate/correction.

Accepted plan `docs/specs/IMPLEMENTATION_PLAN.md` independently hashes to `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. Read the complete root and docs/specs AGENTS, PRD, TODO, PIPELINE_CONTRACTS, operational plan and incident/command register; the accepted plan including introduction/T-06; complete GEOMETRY_ALGORITHMS lines 1–228; PERFORMANCE_AND_OFFLINE, TEST_FIXTURES and VALIDATION_PLAN; and the requested code-reviewer template. Live U-01/U-03/U-04/U-05/U-06 authority was not replaced by historical optional-provider wording. Existing sampler, quad validation, sizing, coordinates, pixel/mask leases, recipes, allocation budget and source decoder were read only as supporting contracts.

## Evidence verified, and its limits

The reviewer ran no Gradle/build/test, device/ADB, artifact execution, acquisition, network, install, memory, server or Git mutation operation, and delegated nothing. Inspection used read/grep/glob, read-only Git diff/status/rev-parse with optional locks disabled, SHA-256 hashing, and read-only PowerShell JSON/XML aggregation. The sole file written by this review is this report. No temporary file or reproduction executable was created.

- Original parent RED evidence: `geometry-consumer-red-command.json`, `geometry-consumer-red-build.log` and the three retained `consumer-red-TEST-*.xml` files agree on 22 failed intended missing-feature cases: 9 Homography, 4 ownership and 9 renderer. Log/XML failures identify the absent backend/class/accounting constructor, rather than a build-configuration failure masquerading as an algorithm RED.
- Parent ownership/NONE GREEN evidence: `geometry-owner-none-green-command.json` and `geometry-owner-none-green-build.log` record exit 0, 31.157 seconds, covering the 4 ownership and 9 renderer cases. Parent backend/ownership GREEN evidence: `homography-backend-green-command.json` and `.log` record exit 0, 39.792 seconds, covering 9 Homography and 4 ownership cases. These are historical parent executions, not reviewer reruns.
- Final parent integration evidence: the frozen manifest and `geometry-appearance-integration-1.log` record normal offline/strict verification, exit 0 in 153.552 seconds, 195 actionable tasks / 194 executed / 1 up-to-date. Its argv includes `--write-locks`; this is not mislabeled as the separate no-writer acquisition verification. Read-only aggregation of all 65 retained XML snapshots confirms 615 tests, zero failures/errors/skips: core 102, image-android 53, fixtures 15, data 396 and app 49. Suite names/counts/timestamps match the manifest; PowerShell's automatic JSON DateTime conversion was normalized before timestamp comparison. Current three reviewed suites also contain the expected 9/4/9 named passing cases. No broader behavioral review is inferred from the aggregate count.
- Current lint XML contains 8 Warning and 1 Hint issues and no Error/Fatal issues. Both generated APKs still match manifest size/hash: debug `bba140141ce24d204ba4d4070ead76142dc4da525077b7348f12b6c2403bb6cd`, test `d4c6442b1511289dc8e495c306e864bd36f53006fa42d81a34a348ca572e899a`. This verifies artifact identity/build evidence only, not installation, runtime compatibility or device acceptance.
- The previously reviewed/corrected SVD acquisition is not reopened. No acquisition command, dependency write, JAR execution or new native compatibility claim was made.

## Verified implementation checks and strengths

**Source-verified:** Homography.kt:25–75 implements the bounded mathematical solver, not a renderer-admission certificate. It validates labeled source-center controls first, independently Hartley-normalizes both point sets, forms the specified 8×9 DLT matrix, requests noncompact V and explicitly requires 9×9 dimensions before taking column 8. It checks both the eight DLT singular values and the three normalized-H singular values with the 1e10 condition limit. It inverts in normalized units, denormalizes the inverse algebraically, and rescales without forcing a tiny H22 to one. Forward/backward corner reprojection, finite coordinates, common-sign relative pole margin and positive det(G)/z³ orientation checks are present. A scale-sign change leaves the orientation ratio unchanged. Pole-free invertible homography plus the validated convex quad supports the bounded injectivity reasoning; no sampled-grid proof is substituted for it.

**Parent-test-backed:** HomographyMathTest:22–46 exercises the actual selected backend's ninth right vector using the independent `[I8 | -(1,...,8)]` null-vector/residual witness, not merely a shape assertion. The projective center oracle distinguishes homography from four-corner bilinear interpolation. Translated crop, large harmless offset, output-center changes, exact rectangle, malformed quad, singleton axes and invalid map arguments have explicit cases. These do not test all numerical rejection paths; see M2.

**Source-verified:** GeometryEngine.kt:30–53 checks recipe/binding/source-size agreement, aligned live masks and a live source before reservation. Existing immutable constructors establish mask range/finiteness. Destination admission rejects upscaling, incompatible floored-scale aspects and geometric singleton axes. NONE singleton output remains allowed. Pixel-count/row-size checks precede narrowing and arrays. With N bounded by Int.MAX_VALUE/3, index arithmetic and the Long charge are safe. The 65536 + 6N + optional 8N per mask charge covers scratch and defensive-constructor copies concurrently; charging the separately owned full source/masks remains the caller's obligation.

**Source-verified:** The only admitted non-NONE branch is exact normalized full frame with source-sized base raster (GeometryEngine.kt:46–48,95). Every other map is visibly refused as PROVIDER_UNAVAILABLE before output allocation. That branch does not invoke the standalone Homography solver. NONE downsize composes half-pixel coordinates with clockwise quarter turns once; source-size branches use exact indices, including distinct q2 handling despite unchanged final dimensions. RGB and both typed masks share those coordinates. For admitted downsizes, source coordinates stay within the source-center rectangle, so the constant-border mask samplers do not introduce a resize-border discrepancy. No coverage threshold repaints RGB.

**Source-verified and bounded parent-test-backed:** GeometryRaster owns its completed RGB/masks/reservation, not the borrowed inputs. The transfer flag and finally block prevent publication on checkpoints/constructor failures and release the permit on unsuccessful exits. Concrete RGB/mask close operations are idempotent/nonthrowing; the reservation is attempted in finally. RenderResult removes generated ownership-aliasing copy methods, closes its image before the optional accounting owner and uses AtomicBoolean to make repeated close harmless even after an owner exception. Its null/status invariant forbids pixels for every failure and requires pixels for OK/DEGRADED. The shared amendment at PIPELINE_CONTRACTS:59 documents this before a production coordinator consumer exists. Ordinary borrower-retain operations without accounting transfer remain prohibited by contract, not claimed to be impossible at the type level.

**Complexity:** solve/setup is constant-sized; the admitted renderer is O(N) time and O(N) output/copy storage, without a dense two-channel inverse map or unbounded row parallelism. Cancellation is checked on entry, each output row and before transfer. The existing cancellation test interrupts at the first post-admission row checkpoint, not after a populated partial raster or after all three output constructors; no stronger timing/coverage claim is inferred.

## Findings

### Critical — 0

No Critical defect identified in the frozen, bounded implementation.

### Important — 0

No Important defect identified in the frozen, bounded implementation. This is a static/artifact review conclusion, not a claim that all future T-06 safety or native gates passed.

### Minor — 2

#### HOMOGRAPHY-OWNER-M1 — nested stage warnings remain caller-mutable

- Evidence status: **true by source inspection**, not dynamically executed in this review.
- Location: `core/src/main/kotlin/app/pristinepage/core/pipeline/Contracts.kt:44,48–49`; regression coverage belongs in `core/src/test/kotlin/app/pristinepage/core/RenderOwnershipTest.kt`.
- Cause: `stages.toList()` snapshots the outer container but keeps each StageRecord and its `warningCodes: List<String>` reference. The caller can supply an ordinary mutable warning list and retain it. Top-level result warnings are separately copied, so this issue is specifically nested warning ownership rather than a failure of every defensive copy.
- Effect: stage diagnostics in an already returned result can change after publication, contrary to treating that result as stable execution provenance. No current production coordinator/persisted execution consumes this new result, so this is a nonblocking boundary-hardening limitation here, not demonstrated lost export provenance or a pixel-lifetime defect.
- Minimal reproducer (illustrative, unexecuted): create `codes = mutableListOf("OMITTED_STAGE")`; construct a StageRecord with `warningCodes = codes`, then a valid DEGRADED RenderResult containing that record; call `codes.clear()`. `result.stages.single().warningCodes` is now empty without modifying the result property itself.
- Smallest remedy: snapshot each record's warningCodes while constructing RenderResult, for example by copying StageRecord with a copied warning list; retain the existing outer snapshots. Add a regression that mutates the original outer lists and nested warning list after construction. If runtime immutability against Java/cast-based mutation is required, use an unmodifiable snapshot consistently rather than claiming Kotlin's read-only List interface alone establishes it. Do not expand this into an unrelated StageRecord redesign.

#### HOMOGRAPHY-OWNER-M2 — numerical rejection guards lack targeted negative witnesses

- Evidence status: **true coverage gap by source/test inspection**; no incorrect numerical acceptance was reproduced or established.
- Location: `core/src/main/kotlin/app/pristinepage/core/geometry/Homography.kt:45,51–74,78–83` and `core/src/test/kotlin/app/pristinepage/core/HomographyMathTest.kt:87–110`.
- Cause: current negative solves use invalid quads or singleton dimensions, all rejected before SVD; invalid map arguments exercise only map's argument precondition. The passing projective/backend/affine examples do not drive rank/condition rejection, pole-margin rejection or the numerical-solution exception classification.
- Effect: the nine passing Homography cases prove useful positive arithmetic/backend behavior and malformed-input refusal, but are not regression evidence for the implemented numerical rejection policy. This does not currently expose rendered general-map pixels because GeometryEngine refuses those maps. Numerical rejection witnesses are needed before using this solver to admit such rendering.
- Minimal coverage witness: inspect the invalid list at test lines 88–94 against QuadValidation; each fails source bounds, convexity, edge/area or duplicate checks before Homography.kt:43. No current test asserts `InvalidHomographyException` or targets the specified relative denominator/conditioning thresholds.
- Smallest remedy: add fixed valid-convex but ill-conditioned correspondence cases that first independently pass QuadValidation and then assert the numerical-solution subtype. Add threshold/sign fixtures for the pole/orientation gate through a narrow internal validation seam if those failures cannot be reached independently through valid convex correspondence fitting; do not expose an unrestricted public matrix renderer merely for testing. Retain the good normalization and projective/null-vector oracles. This is nonblocking for the present standalone solver/refusal milestone, not acceptance of the missing witnesses.

## Recommendations and next evidence

Preserve the shared-owner boundary and explicit unsupported-map refusal while building the next bounded slice. Resolve or explicitly carry M1 before results become durable execution snapshots, and obtain M2's rejection evidence before the standalone solver authorizes general-map pixels. When extending renderer tests, include resize plus odd turns and typed masks, closed/mismatched inputs, explicit unsupported-map refusal before reservation, and cancellation after partial rows and after completed-plane construction. These are targeted follow-up precautions, not assertions of a defect in the inspected paths. Keep nontrivial perspective stretch/visibility, full manual/grid validation, native/API26 equivalence and actual scanner journeys as separately identified work; do not convert the current 615-test integration count into any of those acceptances.

## Considered and declined to judge as completed behavior

- General perspective rasterization and its analytic stretch/visibility/work-budget acceptance remain open T-06 gates: the standalone solver is not that validator and the renderer refuses non-exact maps, so missing general rendering is not itself a defect of this bounded milestone.
- Manual-curve/LUT/direct-map proof, learned-grid topology/overlap/crop support, detection and mandatory U-01 provider implementations remain open T-06/T-08 gates: none is represented as implemented or accepted by these six files.
- Stage-failure KEEP_STAGE_INPUT orchestration, durable degraded-raster review/pinning, subsequent appearance order and export publication remain open coordinator/persistence/export gates: this primitive has no fallback-policy input and returns no substituted failure raster.
- Editor dispatch, generation/revision fencing, comparison/cache lifecycle and U-04 batch controls remain open UI/coordinator gates: the primitive is expressly synchronous/off-UI-thread and does not implement publication scheduling.
- Android/API26/ABI runtime, optimized/native sampler equivalence and actual native MaskWarp journeys remain unverified: host XMLs and APK assembly do not prove them; the previously corrected SVD acquisition audit is not duplicated here.
- Real-page content quality, physical-device/PSS/thermal/performance limits, fresh-install airplane-mode and human visual/accessibility acceptance remain open product gates: no device/real-asset operation was authorized or performed by this review.
- The separate appearance candidate/correction, MemPalace/helper work and unrelated storage/import changes are outside semantic review scope: wider hashes/test totals establish identity/evidence only, not fresh acceptance of their behavior.
- Git integration/merge, task/FR acceptance, signing/publication/rights decisions and changes to shared authority/build/dependency/schema remain coordinator/operator gates: this review changes none of them and grants no new operational permission.

## Original verdict

**PASS WITH MINOR LIMITATIONS — bounded prerequisite ready for coordinator disposition/integration; 0 Critical / 0 Important / 2 Minor findings.**

The inspected implementation is coherent for Hartley/full-V homography math plus explicitly limited NONE/exact-fullframe raster ownership. No blocking defect was identified within that actual scope; M1 is a narrow provenance-alias limitation and M2 is an explicit numerical-rejection coverage limitation. This is not merge authority, complete T-06/T-07 acceptance, general-map safety acceptance, an installable/runtime-verified APK claim, or app/device/release acceptance. The coordinator retains the decision and must preserve open gates and this original verdict when recording any later corrections.
