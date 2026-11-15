# T03-RECIPE-R — bounded independent repository review

## Status and evidence

Independent static review, 2026-11-15; observed branch `feat/paperlight-v1`, HEAD `1291db33191279fabe73a3f8bd004231f5ae58ea`, workspace `X:\local-workspace\.worktrees\paperlight-v1`. No implementation authorship, nested delegation, preferred verdict or task-acceptance authority. Inspected actual untracked repository files/tests, CatalogDao and changed PristineDatabase; related DTOs, row definitions, codec/recipes and relevant exported DDL; complete required operating/domain contracts, accepted-plan T-03, prior receipt/file review and supporting architecture/privacy/resource/import-export contracts. SourceFiles was not re-reviewed.

Commands: read-only `git --no-optional-locks status --short --branch` and `git --no-optional-locks rev-parse HEAD`; otherwise file reads/searches. Only this report was written. No tests, builds, schema generation, changing integration reports/APKs, keys, devices, network or Git mutations. Coordinator-reported 6+8 missing-repository failures, additional mask/Undo-owner failure and subsequent data-test exit0/22 tasks are attributed, not independently executed/harvested evidence. No outcome is inferred for integration `sh_1df13cf76001sMIErMaQKaprzO`.

All findings below are statically established control-flow gaps with proposed regression oracles, **not reproduced runtime failures**. Repository line references use `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt` (abbreviated R).

## Implemented safeguards

Create validates bounded, trimmed Unicode titles. Save uses page-revision CAS; aggregate conflicts correctly return document revision. Batch validates all live pages before writing, inserts immutable revisions/pins, increments the document once and creates one Undo. Exceptions escape the transaction before result conversion. Unknown stored versions remain byte-preserved/read-only. Duplicate allocates an independent page/history, shares source ownership, and uses a genuinely disjoint temporary ordinal range before final numbering; SQLite's immediate unique constraint and deferred initial recipe FK are respected by this ordering. CatalogDao uses bound SQL, ABORT writes and indexed ownership counts. RoomMetadataAccess:7-9 binds the actual DAO and `runInTransaction(Callable)`, not a fake production transaction.

## Critical

None identified within this scope.

## Important — necessary command corrections

### I-1 — New-revision Undo prevents the next earlier Undo

**R:131-137**, especially the exact `after.recipeRevision` passed to verifyLive, and **R:159**. Counterexample using only implemented commands: batch U1 changes A revision1→2; batch U2 changes A2→3; Undo U2 with document revision3 creates A4, effectively restoring A2. Undo U1 with the now-correct document revision4 nevertheless conflicts because its after-pointer is2, not4. Ordinary successive Undo across retained edits is therefore impossible on overlapping pages, despite preserving all history and observing document CAS. This undermines the documented retained-edit Undo behavior, not a request for session Redo or membership Undo. Minimum remedy: recognize the expected complete logical after-state/source/masks through restored history, or maintain explicit logical Undo-head bookkeeping, while retaining document fencing and new monotonic revisions. Oracle: two overlapping saved batches followed by reverse-order Undo both apply; final effective recipes equal the entry state, revision counters keep increasing, and genuinely divergent intervening edits still conflict.

### I-2 — Mask readiness is trusted from preflight after the writer lock is acquired

**R:151,157-164,196-207**. Prepared masks are checked only outside the transaction. verifyLive rechecks source/page/current recipe but neither the captured associations nor Mask readiness/binding; copyMasks merely inserts links and updates counts. Counterexample: during admission, another metadata writer quarantines an associated protection mask without editing the page recipe. Batch/save still commits a new revision and Undo pin referencing that non-READY mask. Foreign keys do not reject a quarantined existing row. Minimum remedy: inside the transaction, before any writes, re-read/validate the masks actually being copied and fence changed associations/identity against the admitted snapshot; do not perform mask-file IO under the lock. Oracle: an admission-hook mask quarantine causes complete rejection with no new revision, pointer, Undo or reference/count change; repeat with a changed association. This is a metadata validation fix, not implementation of recovery/GC.

### I-3 — Undo admits the target recipe using the current revision's masks

**R:118-124**, via **R:151-154**. prepare checks admission with current masks, then Undo replaces them with historical target masks without repeating admission. With valid retained history whose mask sets differ, a previously unprotected binary recipe followed by a protected nonbinary edit cannot be restored: admission sees the current protection and rejects the otherwise valid unprotected target. The reverse mismatch can under-validate a target under capability changes. These differing histories are not generated by today's mask-preserving commands; they are supported persisted states once mask editing is integrated. Minimum remedy: supply the target revision's checked masks to preparation/admission from the outset, then transactionally recheck them as in I-2. Oracle: admission observes the target protection flag, and Undo restores exactly the target recipe/mask set without partial writes. No mask-editing UI/API rewrite is requested.

## Minor

**M-1 — Pre-write cancellation is not polled. R:41-45,57-63,126-137,250-254** (also create/duplicate). Catching CancellationException preserves explicitly thrown cancellation, but synchronous admission/queries/transactions do not observe a canceled coroutine Job. Cancellation during admission can therefore be followed by a complete commit and only surface when withContext returns. Minimum remedy: check coroutine activity immediately before entering the transaction and before its first mutation; retain documented reconciliation for unavoidable cancellation racing an already completed commit. Oracle: cancel during admission and verify no metadata mutation. This is not a claim that SQLite partially commits, nor that cancellation can undo an acknowledged commit.

## Coverage and declined judgments

Reviewed atomic write placement, page-versus-document conflict values, preflight races, source binding, immutable/canonical history, monotonic overflow handling, Undo ownership/refcounts, duplicate isolation/order, safe errors and unknown-version preservation. Host fixtures exercise real business/codec code but do not enforce SQLite foreign keys/uniqueness, Android rollback, concurrency or cancellation; second-insert failure coverage is not platform rollback evidence.

Declined: actual Android Room open/rollback/process-death/populated migrations; real ingest/decoder/recovery; freeze preservation (correctly awaiting actual freeze, not a fabricated snapshot); leases, deletion/retention and reclaim; app batch drafts/UI, membership Undo/Redo, provider assets/runtime/rights, neural U-01, devices/performance/visual/offline release and whole T-03 acceptance. Those remain explicit open gates, not new defects charged to this initial command slice. Provider rejection and duplicate's null membership-Undo token are honestly bounded.

## Verdict

Bounded specification compliance: **NEEDS FIXES** for I-1–I-3. Code quality: coherent small transaction/port separation, but Undo and mask validation need correction; add the focused oracles before broadening integration. No whole-task acceptance, merge/push or device authority is given.
