# Independent T02-CORE-R boundary review

Reviewed 2026-11-15, branch `feat/paperlight-v1`, HEAD `753bb4f9945a9d73a170c8f99d90e8ad779fc755`. Scope: all actual files under `core/src/` and `test-fixtures/src/`, including untracked files, their module/surrounding build configuration, and `validation/contracts/t02-receipt.md`. Read the complete required operating documents, T-02 accepted-plan excerpt, TEST_FIXTURES, geometry specification and photometric specification. Accepted-plan SHA-256 independently remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. Source remained frozen; this report is not coordinator acceptance.

## Verdicts

- **Spec compliance: FAIL — incomplete required fixture deliverable.** The core type/family boundary is substantially present, but F-01–F-05 coverage is partial rather than complete.
- **Code quality: CHANGES REQUIRED — canonical encoding can silently alter an accepted recipe.** No Critical finding. Two Important findings and two Minor verification gaps follow; none requests a style-only rewrite.

## Important

### I-1 — UTF-8 output replacement breaks canonical recipe identity

`core/src/main/kotlin/app/pristinepage/core/recipe/RecipeCodec.kt:18-22`, together with `core/src/main/kotlin/app/pristinepage/core/recipe/Recipes.kt:12-16`.

**Status: established by static data flow and cached-dependency bytecode inspection; not runtime-reproduced.** String fields accept unpaired UTF-16 surrogates. Canonical JSON string rendering retains such a character, then `toByteArray(UTF_8)` silently replaces it. Consequently two otherwise identical recipes with conversion-version strings consisting of lone `\uD800` versus literal `?` produce identical encoded bytes/hash; decoding the first encoding changes the source binding. This is lossy normalization before SHA-256, not a cryptographic collision. Strict input-byte decoding does not protect programmatic construction or escaped JSON surrogates. Cached serialization 1.9.0 `StringOpsKt.printQuoted` was inspected with read-only `javap`: its 93-entry escape table does not escape/reject surrogate code units.

**Smallest fix:** use a REPORT-mode UTF-8 encoder instead of replacement encoding, and reject ill-formed surrogate sequences in decoded recipe strings before returning an accepted recipe. A shared well-formed-Unicode validator is sufficient; do not prohibit valid supplementary characters. **Regression oracle:** direct construction and JSON `"\uD800"`/`"\uDC00"` reject without hashing; a valid surrogate pair survives encode/decode exactly; no accepted recipe silently becomes the literal-question-mark recipe.

### I-2 — Required diagnostic fixture cases and geometry coverage are absent

`test-fixtures/src/main/kotlin/app/pristinepage/fixtures/DocumentFixtures.kt:43-66,129-130,159-188`.

**Status: established from exhaustive generator dispatch/source inspection.** F-04 preserves per-variant map provenance now, but returns only clean-canvas content/background masks, with no destination page-coverage mask. Background is not page coverage: white paper outside the printed frame remains part of the page. Also absent are the separate distinctive-corner image, invalid bilinear cell, separated overlapping positive-orientation cells and manual-edge oracle required by TEST_FIXTURES F-04. These are input/oracle datasets, not a demand to implement T-06 validation/rendering now.

Other explicit omissions: F-01 offers one unrelated padded image rather than padded equivalents with different nonzero padding for the specified constant cases; F-03 always chooses the white canvas, so its required cream-target corruption cases cannot be generated; F-05 offers a 3×2 identity raster and two expected-number strings, not the remaining reusable primitive/stage vector datasets. For example, the log-order, signed-detail/headroom, Sauvola and crop/letterbox vectors have no generated input/expected-parameter representation.

**Consequence:** later quality/geometry tests cannot independently diagnose omitted margins, false whitening, channel/label swaps or the specified topology failures from this fixture API. Calling the present generator F-01–F-05 complete would conceal these missing oracles.

**Smallest fix:** extend the existing generator with named cases/variant parameters, retaining independently specified expected values and mask spaces; no new renderer/framework is necessary. **Regression oracles:** perspective destination coverage is zero at `(0,0)` and full at mapped clean corner `(40,40)` regardless of white RGB; corner colors stay distinguishable; the specified invalid cell has center determinant 1.5 but corner determinant −1; overlap data contains two positive-orientation footprints with intersecting interiors; manual data records control `(50,−20)` and `w(.5)=.625`; cream corruption retains clean background `(240,225,190)`; paired padded cases preserve active pixels; F-05 exposes its specified inputs/expected values without executing future stages.

## Minor

### M-1 — Reproducibility tests lack independent canonical/fixture goldens

`core/src/test/kotlin/app/pristinepage/core/RecipeCodecTest.kt:13-23`; `test-fixtures/src/test/kotlin/app/pristinepage/fixtures/DocumentFixturesTest.kt:27-34,54-70`.

Only the tiny identity raster has a pinned decoded-pixel digest. Recipe hashes are compared against the same current implementation, and repeated generation checks only the noise image. Changing the hash domain tag or canonical numeric/default representation can leave every assertion green. The curl coordinate assertion is at its zero-displacement center, so changing amplitude 16 to zero also passes that oracle. **Smallest fix/oracle:** pin independently reviewed canonical bytes/hash and per-variant packed-pixel/parameter/mask hashes; compare repeated complete manifests. Add a nonzero curl sample, for example `(255.5,191.75)→(271.5,191.75)`. A domain-tag or zero-amplitude mutation must fail. Hash decoded arrays, not codec-dependent PNG bytes.

### M-2 — Some delivered boundary branches have no regression coverage

`core/src/test/kotlin/app/pristinepage/core/ContractTest.kt:54-63`; `core/src/test/kotlin/app/pristinepage/core/RecipeCodecTest.kt:7-8,60-73`; `core/src/test/kotlin/app/pristinepage/core/CoordinatesTest.kt:20-28`.

Mask validation is tested only through ProtectionMask, without returned-array mutation or retained/closed-mask checks. All codec tests use GeometryRecipe.None; provider references and the other three geometry families are not round-tripped. U-04's four-turn recipe identity is not asserted. **Smallest fix/oracle:** exercise both typed mask lifecycles/copies, each geometry/provider family with default and boundary parameters, and four successive valid turn updates restoring canonical recipe bytes while preserving source controls and an independent duplicated-page recipe. No observed production failure is inferred solely from these missing tests.

## Actual coverage and limits

Inspected core checks cover RGB unsigned order, positive/overflow/short extents, padding-independent equality/hash, defensive copies, sequential RGB leases, mask ranges, half-pixel versus aligned corners, rotation coordinates/aspect sizing, signed/byte rounding, luma, reflect101 and ordered binary64 composition. Recipe constructors enforce the stated photometric ranges and detail/Sauvola exclusion. Unknown fields/versions, duplicate keys, bounded nesting and malformed UTF-8 have implementation paths and some tests. Core/build dependencies contain no Android, Room or model runtime; no inference capability is claimed.

Preserved evidence supports intended initial core15/fixture3 absent-implementation failures, expanded core18 with one real duplicate-key failure plus fixture6 absent-generator failures, then the earlier successful core/fixture task run. Later provenance RED records eight fixture tests/one failure; current source corrects that provenance. I ran no tests/builds and did not inspect the active integration log, changing build reports or APK; no current full-build or post-correction GREEN verdict is supplied.

Declined to judge: T-06 solvers/topology/renderers and mask warping, T-07 stage outputs, T-08 actual neural inference/assets/rights, T-03 persistence and future-document preservation, T-09/T-10 batch execution, device/performance/visual/offline acceptance, and the running integration outcome: these are outside the frozen deliverable or unavailable under this review's permissions. U-01 and U-04 remain mandatory later. Only this review file was written; no source, authority, index, HEAD, build, dependency, device or original artifact was mutated. Coordinator can resolve these findings and continue the next eligible task under U-05 without a per-task operator pause.
