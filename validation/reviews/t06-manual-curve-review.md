# T06-MANUAL-E-R — original independent numerical candidate review

Date: 2026-11-17. Reviewer: numerical geometry and API-contract reviewer, fresh context, OpenCode / OpenAI GPT-6.1 Sol (`gpt-6.1-sol`). Preserve this original verdict separately from subsequent coordinator dispositions, corrections and integration evidence.

## Original bounded verdict

**PASS WITH MINOR LIMITATION — 0 Critical / 0 Important / 1 Minor.** No blocking arithmetic, ownership-port or unsafe capability-claim defect was established in the frozen numerical candidate. MANUAL-E-M1 is a regression-coverage gap, not a demonstrated incorrect derivative in the inspected implementation. Recommendation: retain this candidate as the numerical prerequisite for the separately scoped parent proof, carrying the Minor explicitly; do not admit manual raster output from this verdict.

This is not task acceptance, an operational permission, a current full-application test result, complete T-06, native/API26/physical/fidelity evidence, a rights judgment or release acceptance. The absence of the expressly excluded future topology/stretch/global-boundary/support proof is not graded as an unsafe admission by this candidate.

## Scope, authority and identity

Sole review write: `X:\local-workspace\.worktrees\paperlight-v1\validation\reviews\t06-manual-curve-review.md`. Everything else was read-only. Direct Git-pointer/ref file inspection, without a Git command, confirmed primary `5e1d8ea5a112de159fcbcee070c4766bd69f0820` and retained worker `64c1c109b7971e092e066d91064a082d6cb7f7ae` on their stated branches. The primary's three candidate files were independently byte-compared with `X:\local-workspace\.worktrees\paperlight-t06-manual-curve`; all match.

- `core/src/main/kotlin/app/pristinepage/core/geometry/ManualCurve.kt`, complete 227 lines: SHA-256 `ce552a73a7a69f803405474d45afa120654960e0f33e8accd953c6c9d2a37282`.
- `core/src/test/kotlin/app/pristinepage/core/ManualCurveMathTest.kt`, complete 245 lines: SHA-256 `41dfc6d4f88f559a2c196887491e3784f5b1fc5e02aadeec95a087edf293fbf8`.
- `validation/geometry/t06-manual-curve-worker.md`, complete 60 lines: SHA-256 `e9c33373bd601c7387b8aee343198a1ee42751302fc5897709d1cf0b926350c9`.
- Retained worker `validation/raw/geometry/manual-evidence-inventory.json`: SHA-256 `ccd825de0e5b467700d4c12ae8d6715e490254aa31d73f7d4286ccdede0012fe`; all 29 listed raw files independently rehashed with zero mismatches.

Read the active primary AGENTS instructions, complete `docs/specs/AGENTS.md`, PRD, TODO, PIPELINE_CONTRACTS, incident/commands register and OPERATIONAL_PLAN; complete GEOMETRY_ALGORITHMS lines 1–228; accepted-plan introduction/global constraints/T-06; PERFORMANCE_AND_OFFLINE and ARCHITECTURE. The previously line-truncated TODO storage row was recovered without using it to widen this review. Accepted implementation-plan SHA-256 is unchanged: `791dba54b888ac430cf12c0a4d83a24dd570f5305e989c91815fb9be78fa`. Source binding, typed recipe/coordinate/quad/sizing contracts, Homography and CoreApi were inspected as dependencies, not independently reaccepted. GeometryEngine's still-unavailable manual branch and its common-floor predicate were inspected only to check the candidate's boundary and disclosed duplication.

The parent's unfrozen AppearanceEngine, provenance tests and native preparation are outside this semantic review and are not substituted into the worker's build-input set. The worker's 170 cases do not describe the current full application.

## Evidence actually checked; no execution expansion

No tests, build, Gradle, signing, device/emulator/ADB, network/acquisition, memory/conversation operation, Git mutation or nested delegation occurred. No temporary/reproduction file was created. Inspection used read/grep/glob and inline `python -B -` with standard-library pathlib/hashlib/JSON/XML/Decimal/bisect, stdout only. One integrity/metadata command completed its hash, input and XML checks, then exited 1 while printing the recovered Unicode TODO row through Windows cp1252; the next command explicitly selected UTF-8, recovered that row and completed the scalar checks with exit 0. This was an inspection-output encoding failure, not a candidate/test failure.

The saved baseline/RED/GREEN/regression command records and XML were parsed independently; the focused GREEN, RED and regression logs were also inspected. The retained results are baseline 58 cases/4 suites/0 failures/errors/skips, exit 0; RED 23 cases/23 failures/0 errors/skips, exit 1; focused GREEN 23 cases/0 failures/errors/skips, exit 0; regression 170 cases/14 suites/0 failures/errors/skips, exit 0. All 23 RED failure messages identify the intended absent `geometry.ManualCurve`, following successful test compilation, rather than a configuration failure. GREEN's log records four executed tasks; regression's `:core:test` executed while the other three tasks were UP-TO-DATE. These are inspected historical worker runs, not reviewer reruns.

Both GREEN command manifests contain 46 source/build inputs; all 46 were independently rehashed against the retained worker in each manifest, with zero missing/drifting inputs. This includes the frozen helper/contracts used by those runs, not the parent's pending changes. The 29-entry evidence inventory covers the retained runner, four command records/four logs and 20 XMLs. Inventory consistency authenticates these retained bytes, not test completeness or native behavior.

## Numerical and interface assessment

### Controls, arcs, inversion and derivatives

**True by source inspection:** ManualCurve.kt:185–207 consumes a typed source-bound recipe and explicit final size, reuses GeometrySizing/QuadValidation, denormalizes source-center controls and preserves their labels. Recipes.kt:26–31,48–54 and PointPx reject invalid versions, turns, handle ranges/nonfiniteness, depth and non-128 arc counts before preparation. Source identity equality remains the explicitly documented consumer responsibility at ManualCurve.kt:36–37; preparation has no actual source pixels to authenticate. This is not an identity bypass in a raster consumer delivered here.

ManualCurve.kt:148–174 derives `C=2M-.5(P0+P2)`, builds separate binary64 128-chord cumulative lengths, refuses nonpositive/nonfinite increments, total length at most one pixel and the specified uncertain-increment threshold, then requires strictly increasing normalized knots. No uniform-LUT fallback or clamping is present. ManualCurve.kt:102–130 uses upper-bound inversion with exact endpoints; at an exact internal knot the common parameter is k/128 and LEFT selects k−1 only for the derivative. For a joint breakpoint belonging to just one boundary, the other boundary remains in its unchanged containing interval. Applying the same side to both boundaries at lines 73–76 is correct for the joint partition.

The ruled map at lines 52–58,75–81 implements the specified depth weight and chain rule without a second homography, root solve or inset. `du` and `dv` are source PIXELS per UNIT UNROTATED BASE destination. A proof must divide their x/y components by source width−1/height−1, respectively; dividing by output dimensions would be wrong. The derivative API deliberately does not differentiate final rotated pixel coordinates. Map rotation and derivative reference space are separately named and documented.

Independent 60-digit Decimal power-basis/chord calculations corroborate the tests' literals within their declared tolerances: symmetric knot32 `0.2547349825258863`, center horizontal derivative `102.60541092128764`, and knot32 horizontal LEFT/RIGHT `100.55249259873960` / `100.67342054123554`. For asymmetric top `Q=(20t+80t²,80t−80t²)` and bottom `Q=(140t−40t²,100−60t+60t²)`, 128-chord totals are `112.63955543314184` / `106.15057785891905`, inverse half-arc parameters `0.6501555407968600` / `0.41153205228592904`, and positions `(46.81928899424580,18.19626508544020)` / `(50.84014211768332,85.46959466636437)`. Tiny last-digit differences from the stored decimal literals are far below their tolerances; none is an incorrect expected value. Depth-center vectors `(50,50)`, `(50,58)`, `(50,42)` and `(50,69)` follow directly from the independent section-12 controls and weights.

### Extrema, straight dispatch and future proof ports

**True by source inspection and scalar checks:** ManualCurve.kt:133–144 examines both coordinate stationary parameters and endpoints rather than only corners/handles. The tested off-midpoint maximum is 41.25 at t=.625, and the outside derived control example has actual minX −5.625; neither is clipped. `Bounds` is explicitly binary64, not outward-rounded. The parent must enclose the actual coefficient/LUT arithmetic rather than treating these rounded scalar extrema as a certified visibility bound.

ManualCurve.kt:198–202 compares exact serialized normalized midpoint arithmetic and exact zero depth before denormalization-based straightness decisions. It reuses the actual Homography solve for the unrotated base size, not corner bilinear interpolation. The serialized trapezoid center `(73.91304347826087,50)` and base-unit derivatives `(97.92060491493383,0)` / `(−4.158790170132325,69.56521739130434)` independently follow from the published projective matrix. The tests' literals are correct. Tiny nonzero curvature/depth remains a direct candidate; underflow of a negligible depth contribution does not convert the declared dispatch into perspective.

The immutable actual straight Homography is available internally at lines 79–80 for the existing core proof; its raw coefficients are not reconstructed from corner samples. For direct proof, boundary endpoints/control points, separate copied/indexed knots, parameter/arc positions and both derivative sides are sufficient to construct the union partition and its quadratic/cubic coefficients. Proof code must also check true top/bottom curves and both straight side boundaries, normalize source units, enclose rounding and retain the separately required work/depth/support/global checks. No public arbitrary map or borrowed mutable LUT is introduced. SourceBinding/PixelSize/PointPx are immutable, and `arcKnots()` copies its array; the private LUT stops changing before the candidate escapes.

### Rotation, failures, cancellation and resources

**True by source inspection:** ManualCurve.kt:40–48 composes final-to-base quarter-turns once before the source map; lines 54,65–70 account for base dimensions in straight-map positions/derivatives. The test covers all four direct-map unit compositions and the literal non-square 3×2 straight q1 coordinates; it does not constitute a raster/permutation/mask test. The common-floor predicate at lines 219–224 is currently equivalent to GeometryEngine.kt:126–132 with `geometric=true`, including rejecting singleton axes, inconsistent aspect and upscaling. Duplication is a disclosed maintenance risk, not a demonstrated divergent admission today; consolidate or cross-check only under later shared-path authority.

Numerical LUT/finite-result refusals use InvalidManualCurveException; malformed typed inputs/preflight/query domains remain ordinary IllegalArgumentException. The straight solve wraps InvalidHomographyException specifically and preserves its cause; it does not catch cancellation or arbitrary backend failures. Future consumers must catch numerical subtypes before their IllegalArgumentException base and map known straight Homography failures appropriately. There is no promise here of a complete RenderResult/error/fallback adapter. The zero-chord witness independently has `Qx(31/128)=Qx(32/128)=−15.5`; its perturbed ideal increment is about `7.9345703125e−12`, below the total-relative uncertainty threshold, supporting the negative tests without a raw mutable-LUT seam.

Checkpoints are outside numerical catch conversion, at entry, after preflight, within both construction/normalization loops and before return. The direct count is `1+1+2*(1+128+128)+1=517`, independent of image dimensions. Straight SVD is bounded constant work with checks before/after, not internally interruptible at each scalar operation. The retained tests pin original cancellation object identity at multiple loop positions; no measured cancellation latency follows.

The two retained 129-double LUTs occupy 2,064 payload bytes. Arrays/solver state remain constant-sized; no source/output-sized array, dense map, raster or topology tree is allocated. `workspaceBytes()=65536` at lines 182–183 is clearly an estimated simultaneous numerical allowance including straight SVD, not measured heap/PSS or allocation enforcement. Callers must reserve it before preparation and separately charge proof/output/source and copied read-port storage. Query work is O(log128) and fixed temporary storage; object allocation churn and runtime class loading are not bounded by a measured peak here. This is consistent with the worker receipt's limited resource claim.

The class/dispatch documentation at lines 12–15,26–34,79–83 and the receipt repeatedly identify an unvalidated candidate. There is no raster or validity flag, and GeometryEngine.kt:53–55 still refuses manual geometry. Deliberately observing a negative-J folded candidate is therefore not an accepted unsafe image capability. No present label claims topology, analytic equal arclength, recovered content or full manual implementation acceptance.

## Findings

### Critical — 0

No Critical defect established in this frozen numerical increment.

### Important — 0

No Important defect established in this frozen numerical increment. Future admission/consumer gates remain mandatory and unperformed, not implicitly passed.

### Minor — 1

#### MANUAL-E-M1 — distinct top/bottom joint-knot derivative composition is not regression-pinned

- Evidence status: **true coverage gap by source/test inspection**; the current implementation selects and composes both sides correctly. No mutant or Kotlin test was executed by this review.
- Locations: `ManualCurveMathTest.kt:59–69,86–105`; production dependency `ManualCurve.kt:62–76,112–130`.
- Cause: the asymmetric fixture asserts positions/LUT inversion but no derivatives. The explicit LEFT/RIGHT assertions use the symmetric fixture at v=0, so the bottom derivative's coefficient is zero. The interior derivative case uses the default RIGHT side only. There is no literal composed derivative at a breakpoint unique to the top LUT and another unique to the bottom LUT.
- Minimal coverage witness: hypothetically replacing only `bottom.derivativeAtArc(u, side)` with `bottom.derivativeAtArc(u, RIGHT)` at line 74 would not be distinguished by the current numerical assertions. This is a source-derived unexecuted mutation argument, not a defect attributed to current code.
- Effect: later changes could drop one required one-sided derivative while the suite remains green, weakening a diagnostic/proof consumer's regression protection at the joint partition. This does not establish a bad current Jacobian or admission result; severity is Minor coverage hardening.
- Smallest remedy: extend the existing asymmetric literal fixture with depth .4, v=.4, inspecting both LEFT and RIGHT at each boundary's own knot32. A 60-digit independent power-basis/128-chord oracle gives top knot u≈`0.1639193844710806`, composed LEFT du≈`(97.0954017139465,14.16210740439296)`, RIGHT du≈`(96.8683545328157,14.010742616972427)`, common dv≈`(6.701761208831652,84.9641704113192)`. At bottom knot u≈`0.3243337654660131`, LEFT du≈`(107.44876204702118,−9.962975541834645)`, RIGHT du≈`(107.79304135258757,−10.04904536822624)`, common dv≈`(5.772317570790303,74.32329541524375)`. Query each actual stored `arcKnot(32)` rather than rounding these displayed u values into the test, so the exact-equality knot branch executes. A source-pixel tolerance around 1e−9 accommodates binary64 accumulation. The top-only joint uses intervals (31,15)/(32,15); the bottom-only joint uses (60,31)/(60,32). This pins common-side selection without any new public seam or raster execution.

## Six declined gate classes and reasons

1. **Manual admission and complete geometry:** source-normalized J reference lattice, outward-rounded interval/cubic/stretch bounds, boundary injectivity, support/extents acceptance, sampled overlap diagnostic and work/depth enforcement are explicitly future parent work. Neither finite positions nor positive samples constitute their proof. Learned grids/detection/U-01 neural methods and complete T-06/T-08 remain outside this candidate.
2. **Raster/pipeline/ownership/provenance integration:** no manual GeometryEngine output, RGB/protection/coverage sampling, reservation transfer/lifetime, full error/fallback mapping, RenderCoordinator, generation fencing or durable requested-versus-executed record is delivered. The parent's unfrozen appearance/provenance work is not judged here.
3. **Application, persistence/export and U-04 journeys:** no editable handles UI, save/reopen, carousel, cumulative subset batch edits, atomic Done/Undo/Redo/Cancel, export/share or accepted-degraded snapshot journey is exercised by scalar math or retained core XML.
4. **Current-build platform/native/offline parity:** the worker's 170 core cases are not a current app build, installed APK, Android/API26/ABI/JNI parity, paired-mask raster test or fresh-install airplane-mode scanner evidence. No such execution was permitted or performed in this review.
5. **Physical resources and fidelity:** the constant setup estimate and synthetic scalar fixtures do not measure physical PSS/GC/thermal fit, cancellation latency, sustained memory, antialiasing/one-pixel stroke preservation or representative real-page meaning/content retention. Personal-phone operations remain excluded; emulator evidence would not replace these gates.
6. **Authority, rights and release:** coordinator/operator alone retains integration/task/requirement acceptance, shared-contract changes, acquisition/model/asset/distribution rights and signing/device/release decisions. This report grants no operation, publication, spending, cleanup, Git mutation, push or merge, and makes no rights-clearance claim.

## Bounded recommendation and additional precaution

Proceed only with coordinator disposition and the already separately assigned proof/consumer work. Retain the candidate/validated-map distinction in types and prevent any GeometryEngine manual raster allocation until proof returns an admitted result bound to these exact candidate coefficients/LUTs and actual decoded SourceBinding. Add MANUAL-E-M1 in the next authorized coherent validation increment; it is not a reason to substitute another method or claim a present unsafe admission. As an additional integration precaution, make the future proof/renderer boundary consume the same immutable prepared instance rather than independently preparing/refitting a second map, and retain explicit tests for wrong-source refusal and failure-before-output. None of those future tests or gates is represented as performed by this review.
