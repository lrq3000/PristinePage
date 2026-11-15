# T03-REQUIRED-PINS-R — independent senior Android persistence/Room review

## Scope and evidence

Reviewed 2026-11-15 using requesting-code-review/code-reviewer, without delegation: actual uncommitted required-source-pin increment on `feat/paperlight-v1`, HEAD/base `df693da2ff88fde6fe38004279d4a4ca2b6fef15`, claim T03-20261115-A. Read root/spec AGENTS, complete mandatory PRD/TODO/PIPELINE/incident/OPERATIONAL_PLAN/DATA_MODEL_AND_STORAGE, accepted IMPLEMENTATION_PLAN T-03, architecture/privacy/capture/resource/batch contracts, all assigned files, supporting entities/DDL/reference producers/transactions/fixtures and generated Room DAO. No replacement plan.

Independent read-only Git/hash/XML/ZIP inspection confirms all five inventoried source SHA256s and all34 suite XML hashes. Retained RED:18 cases/6 intended absent-route assertions/0errors; focused GREEN:18/0failures/errors,43s/22tasks. Integration log:111s/168tasks, no compiler warnings; lint XML:0errors/3existingwarnings.303 recorded JVM cases have zero failures/errors/skips: data256/app9 executed; core23/fixtures15 UP-TO-DATE. Eight schema/verification/lock/accepted-plan inputs match HEAD byte-for-byte; plan SHA256 remains791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa. Index is unstaged.

Retained APK independently hashes to `d22d0e03597a9c4585e78df0d312e896b0e70d080c29c364f38fa480c61d505e`,3472983bytes, with no ZIP native libraries. Adjacent retained SDK outputs record S01 certificate7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee, self-target app.pristinepage.debug.test/AndroidJUnitRunner, three known non-exported services and no INTERNET. Signature/manifest tools were not rerun. Nine native cases compile; none was run for this increment. Historical counter-only runtime is not required-pin evidence. No build/test/Gradle/device/network operation performed; only this report written.

## Strengths

- `data/src/main/kotlin/app/pristinepage/data/db/ReferenceAuditDao.kt:20-28` checks exact PAGE/page/source, canonical RECIPE_HISTORY/recipe-v1/page/revision/source and MASK/mask/source tuples without lifecycle filtering. REMOVED pages and all retained history participate; current-revision-only and counter-balanced wrong-source cases do not pass.
- Per-page history aggregation is sound under the existing schema: RecipeRevision PK(pageId,recipeRevision) plus SourceReference PK(ownerType,ownerId,sourceId) allows at most one exact match per history row. Extra wrong-source/noncanonical/other-owner pins cannot compensate for a missing match. Decimal Long revision formatting agrees with producers. Page/history and reference leading indexes support bounded result materialization without recipe BLOB reads; work still scales with retained history, not a fixed latency bound.
- `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:20-74` preserves counter-only audit(), reuses the same64-row reader/nullable initial cursor/exclusive progression and128issue cap, counts mismatching projections beyond the cap, propagates cancellation and sanitizes ordinary listing errors. Each projection page is transactionally consistent; neither route writes owners/counters/files. Counts describe projection groups, not the number of individual missing history pins.
- `data/src/androidTest/kotlin/app/pristinepage/data/RoomReferenceAuditIntegrationTest.kt:17-64,153-227` supplies counter-balanced corruption controls, old-history wrong-source/noncanonical01 cases, tombstone coverage and bounded ten-table persisted-value comparisons including BLOBs. Fixture mutations target generated metadata only; reopen is correctly distinguished from process death.
- The new route has no production facade/GC consumer. Its documentation and receipt explicitly reject whole-store readiness and reclamation authority; no unsafe composition introduced here was identified.

## Issues

### Critical — 0 new

None identified.

### Important — 0 new

None identified. Unperformed native validation is an explicit acceptance gate, not a concealed pass or demonstrated implementation defect.

### Minor — 0 new

Historical baseline M-1 remains: `data/src/test/kotlin/app/pristinepage/data/ReferenceCountAuditTest.kt:53` always truncates and supplies nonnegative actual counts; removing the production oversize/negative-actual guards at `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:61-65` would still evade those regressions. Guards exist, the same reader is reused, and this increment does not change the consequence. Retain the existing Minor; minimal eventual remedy remains injected65-row/negative-actual projections asserting typed failure/no mutation. Do not relabel it a new finding.

## Recommendations

Proceed to separately authorized current-artifact native validation before bounded runtime acceptance. As an additional precaution, a future fixture with more than64 pages/masks and multiple historical gaps can directly protect the new SQL enumeration and per-page blocked-count semantics; this is optional additional coverage, not an observed defect. Keep both audit results separate from recovery/GC authorization.

## Declined to judge — individually retained open

- Current native test success: execution prohibited; compiled only.
- Signature re-verification: only retained SDK output inspected.
- Whole-database snapshot consistency: expressly per-page observations.
- Concurrent-write stress/latency: no permitted runtime measurement.
- Cancellation inside one blocking SQLite call: only boundary checks reviewed.
- Inverse dangling-owner validity: intentionally not queried.
- Excess otherwise-valid ownership pins: completeness, not inverse validity.
- Undo-owner completeness: separate owner family.
- Import-owner completeness: separate owner family.
- Job-owner completeness: separate owner family.
- Export-page-owner completeness: separate owner family.
- Page-versus-history source agreement: wider binding integrity.
- Orphan RecipeRevision rows with broken FKs: schema integrity, not this FK-backed enumeration contract.
- Malformed SQLite storage classes/revision values: broader schema-value integrity.
- Recipe/hash/mask-content validity: no content decode here.
- Source-file existence/hash/durability: synthetic metadata, no image files.
- Automatic repair: intentionally absent.
- Latest20-edit retention: separate mechanism.
- Expiry/pruning: separate mechanism.
- Pin release: no release path here.
- Garbage collection: no reclaim authority here.
- Confirmed permanent deletion: separate command.
- Repository facade: not wired here.
- Startup invocation: not wired here.
- Durable scheduling/fairness: separate integration.
- Other recovery domains: three-family audit only.
- Freeze/snapshot isolation: no new snapshot path.
- Export/publication/sharing: no new execution path.
- Process-death/dirty-WAL/power-loss durability: reopen cannot establish these.
- Additional IO/crash timings: not exercised.
- Real provider access: synthetic fixtures.
- Real decoder: T-04 gate.
- Album UI/complete FR-17 journey: absent here.
- Uncertain committed-result reconciliation: separate command integration.
- Neural methods: separate provider/rights gates.
- Physical-device performance: excluded scope.
- Visual fidelity: no rendered pages.
- Offline scanner journey: not executed.
- Accessibility: no UI execution.
- Rights/release clearance: separate acceptance.

## Assessment

**Static bounded review: PASS; ready for the next authorized validation step.** No new Critical/Important/Minor finding; one historical Minor remains. Current native acceptance is pending. This verdict grants no whole-T03, recovery/retention/GC, merge, release or scanner acceptance.
