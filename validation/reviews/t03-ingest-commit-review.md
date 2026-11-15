# T03-INGEST-COMMIT-R — independent bounded review

2026-11-15. Reviewer: senior Android persistence/file-ownership engineer. **Contract and code quality: NEEDS FIXES. Operational scope: PASS.** No Critical findings; two Important and two Minor. Evidence status for findings: **true by static control/dataflow, not execution-reproduced in this review**. No task acceptance or authority change.

## Inputs and limits

Reviewed preparation commit `834796a151d2545fdc0a3f6c5073bfe369793ad1`, baseline `84ae2101fd6a98e95130e626ef276cbb473fa5c2`, and actual supplied uncommitted completion/ports/DAO/database/receipt-codec/value/test/fixture changes in `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`. Read both AGENTS, complete mandatory PRD/TODO/PIPELINE/commands/OPERATIONAL_PLAN/storage/batch documents, accepted T-03, architecture/privacy/resource/capture contracts, receipt and progress rulings. Inspected supporting rows, exported DDL, transaction adapter, file verification and fence consumption.

Inspection used two read-only Git diff commands and file reads/searches; report verification used read-back and a read-only word count. No tests/builds/Gradle, changing outputs/logs, devices/console/runtime, network/acquisition, keys, shared-record/Git mutation or delegation. Only this report was written via patch. Frozen `validation/raw/storage/ingest-completion-green.junit.xml:2-10` records seven cases, zero failures/errors. Broader counts remain coordinator-reported. Active integration `sh_1df7dca7d001SjEj19GtN2SbBW` was not inspected or inferred.

## I-1 — Important — replay can certify inconsistent durable ownership

`data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestCompletion.kt:116-123` verifies only selected Source/SourceBlob fields. It does not compare normalized dimensions, conversion fingerprint/profile/orientation, encoded dimensions/MIME, recorded blob path or ingest transaction against the receipt. Neither replay entry (`:38`, `:49-52`) validates the committed child/session transaction binding or immutable job input/provenance. A present page plus matching byte/pixel hashes is not the complete accepted source binding.

**Counterexamples/oracles:** commit normally; independently replace Source.conversionFingerprint with another valid value, or SourceBlob.relativePath with an unused nonexistent owned path. Leave the intended final file untouched. Replay still returns Committed, although the stored conversion contradicts revision1 or the recorded source location is unavailable. Separately remove/change the job input or rebind the child's transactionId: replay never consults them. These are controlled metadata-integrity/recovery counterexamples, not a claim that ordinary recipe edits mutate immutable sources. SQL does not prohibit these field changes.

**Minimal remedy:** validate the complete immutable source/blob identity against the verified receipt and expected opaque path/transaction; validate retained committed operation/job provenance against canonical intent bytes. Reject contradictions without duplicate writes. Do not compare mutable refcounts, live ordinal/current recipe revision or current document revision to their original values: legitimate later edits must still replay the original result. Add each independent mutation oracle, plus unchanged later-recipe-edit success.

## I-2 — Important — terminal outcomes become recoverable pending/expiry

`data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestCompletion.kt:30-31,49-57,75` collapses distinct terminal states. Every known file state except MOVED/COMMITTED returns Pending, including CANCELED, FAILED and QUARANTINED. A canceled Job fails the shared fence and returns Pending(MOVED); a canceled child/parent is reported SESSION_EXPIRED. DATA_MODEL_AND_STORAGE defines Pending as interrupted recoverable work and requires distinct cancellation/error outcomes.

**Counterexamples/oracles:** from `ready()`, set only Job.state=CANCELED: commit returns Pending(MOVED), with no cancellation reason. Set FileTransaction.state=FAILED with a persisted error: it returns Pending(FAILED), discarding that error. Set child.state=CANCELED: result is Rejected(SESSION_EXPIRED), not CANCELED. No page is published, but UI/recovery can offer endless retry instead of the actual terminal outcome.

**Minimal remedy:** explicitly classify terminal file/job/session states and preserved safe error codes, reserving Pending for genuinely resumable/blocked states. Recheck cancellation under the completion writer; retain coroutine CancellationException propagation and no new writes after rejection. Add the above cases and distinguish expired/stale leases from canceled jobs.

## M-1 — Minor — completion omits canonical job hash validation

`data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestCompletion.kt:43-44` compares kind/key/input bytes but not canonicalInputSha256, unlike preparation at `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestPreparation.kt:38-39`. **Oracle:** change only the running job's canonicalInputSha256 to another valid hash after `ready()`; completion still publishes and marks SUCCEEDED. Byte/seal equality prevents a wrong-pixel substitution in this counterexample, but durable identity is inconsistent. **Remedy:** reuse the tagged input hash calculation and validate it transactionally, including replay; assert rejection with all rows unchanged.

## M-2 — Minor — unsupported versions are mislabeled corruption

`data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestCompletion.kt:30,58` and `data/src/main/kotlin/app/pristinepage/data/journal/IngestJournalCodec.kt:43,55-61,73-84` funnel future state/version rejection into INTEGRITY_FAILED. **Oracle:** retain a canonical receipt with schemaVersion=2 or a future journal state; current completion reports corruption rather than UNSUPPORTED_VERSION. Bytes remain preserved, so no destructive failure is asserted. **Remedy:** distinguish supported-envelope/version detection from malformed integrity failures, preserving opaque future bytes and returning an unsupported explanation; add no-write regressions.

## Considered, without additional defect found

- Preparation atomically allocates child-bound IDs/INTENT/PENDING job; exact canonical-byte comparison rejects normalization/rebinding. Existing URI binding prohibits replacement; PENDING is not admission.
- File length/hash and typed seal/full-intent equality are checked outside Room. Native construction shares one database; journal rechecks and shared pre/post lease fencing protect the metadata writer. Failure throws through rollback; original files are not deleted or deduplicated.
- Publication inserts blob/source/page/revision1/two ownership pins, derives refCount, increments document once and clears URI atomically. With fewer than100 old contiguous pages, temporary ordinals100..198, new ordinal200 and final0..99 are disjoint. Exported deferred current-recipe FK permits page-before-revision insertion; immediate remaining FKs have predecessors. This is structural feasibility, not Android execution proof.
- Host fixture rolls back catalog/session/job/journal/blob maps, but does not enforce SQL FKs/ordinal uniqueness. Two-item empty-document ordering and mid-recipe rollback are meaningful, not anchored/nonempty-document or actual Room proofs. Four synthetic bytes/manual MOVED/RUNNING setup are correctly disclosed.
- Integration precaution: replay currently requires the staging seal (`RoomIngestCompletion.kt:34`). Retain it until a reviewed receipt-based final-file verification path supports authorized seal pruning; do not silently implement cleanup that breaks retry.

## Declined / unaccepted

Canonical SourceStore orchestration, real decode/admission, phase workers, recovery/process-death/cross-process proof, scheduling, current-flow Android Room, freeze/export/GC, UI/FR-17/neural/physical/performance/offline/visual/rights and whole T-03 remain separate gates. Their future absence is not a defect charged against this slice. Prior clock/owner implementations were inspected only as consumed contracts, not re-reviewed.
