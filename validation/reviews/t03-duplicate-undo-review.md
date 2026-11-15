# T03-DUPLICATE-R — independent bounded persistence review

## Scope and evidence

Senior Android persistence/repository review, 2026-11-15. Reviewed the actual uncommitted duplicate-Undo increment over `68a1d1ff7185cecc77027dc5a95319012306ee49`, branch `feat/paperlight-v1`, worktree `X:\local-workspace\.worktrees\paperlight-v1`, same T03-20261115-A claim. Read root/spec AGENTS, complete mandatory PRD/TODO/PIPELINE/incident/operational/storage documents, accepted plan including T-03/T-10, batch/privacy/architecture/capture/resource constraints, all four assigned files, supporting transaction adapter/DAOs/rows/fixtures/history/cancellation tests and retained evidence. Applied requesting-code-review/code-reviewer template without delegation.

Read-only Git status/HEAD/diff, PowerShell SHA-256/XML/ZIP inspection and file reads only. Three source hashes and all33 inventoried XML hashes/counts match: core23 + fixtures15 + data235 + app9 =282 recorded cases, zero failures/errors; duplicate7. Core/fixture tasks were UP-TO-DATE, not freshly executed in that integration. Retained REDs record6 missing-token assertions, then1 classification assertion among7 cases. APK matches `aa8cbde04f96b1e790bafea2af39d1756b3b9afc5f48fe98c95b0d01052a27a0`,3447085bytes, no native-library ZIP entries. Retained SDK outputs identify S-01 signer/self-target/runner; these were not rerun. Schema/locks/verification/accepted plan have no tracked diff. Only this report was written; no build/test/device/network/Git mutation occurred.

## Strengths

The new branch validates an actual one-page insertion, including same-source predecessor and exact shifted survivors, rather than accepting arbitrary valid snapshots. Early all-active validation fixes the nullable-ordinal classification problem. Undo preserves current recipe pointers, mask/history/source ownership and unrelated tombstones; nested insertion reversals remain possible. Live membership/order, document CAS, pending-import, lifecycle, expiry, Source/Blob readiness and pre-first-write owning-context cancellation checks are present on Undo. Exceptions escape Room's transaction before conversion to structured results; no file deletion, serializer state, schema or public API is added.

## Issues

### Critical — 0

None identified.

### Important — 2

**I-1 — Duplicate can publish an unavailable source and an immediately unusable Undo.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:183-203,423-427,448-453`. Established by static control flow, not runtime-reproduced. Duplication's live binding and pin helpers inspect Source.state but never its backing Blob.state. Counterexample: leave Source READY and set its blob QUARANTINED or DELETING before duplication, or during admission without changing documentRevision. Duplication still inserts an ACTIVE copy, history, pins and PAGE_DUPLICATE token. Immediate Undo then rejects at lines309-310 because the unchanged blob is not READY. These independently stored states are permitted by the actual schema/FKs. Consequence: a successful command adds another page referencing an already unavailable original, while its advertised reversal cannot execute. This is an inherited duplicate-path validation gap exposed by the new reversible composition, not a claim that current recovery necessarily produces this combination. Minimum remedy: transactionally validate the required source→blob chains before duplication writes, reusing a narrow metadata-only readiness helper; do not perform file IO under Room. Include every source required by the recorded membership Undo. Add host/native regressions asserting rejection and unchanged pages/order/history/Undo/references/revision for blob-only unavailability.

**I-2 — Duplication can serialize history that its own Undo decoder rejects.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:184-186,200-201`; contrast lines246-250 and337-354. Established statically, not runtime-reproduced. Unlike other membership commands, duplication reads activePages directly and checks only capacity/contiguous ordinals. Counterexample: a persisted ACTIVE row has its valid ordinal but non-null removedAt. Preparation checks ACTIVE, not removedAt; duplication succeeds and copies that inconsistent row into before/after metadata. The new token is deterministically rejected by decodeMembership's ACTIVE/null-removedAt invariant. The exported schema has no CHECK preventing this combination. Consequence: detectable corrupt membership is mutated, and a supposedly reversible copy cannot be undone even immediately. Minimum remedy: reuse membershipPages(documentId) for duplication's live set, retaining its stricter100-page insertion limit. Add an inconsistent-live-row regression proving INTEGRITY_FAILED/nonretryable before any mutation. This is separate from the existing test that corrupts history after a valid duplication.

### Minor — 0

None separately identified.

## Coverage and recommendations

The host fixture runs real repository/codec logic but substitutes rollback and does not enforce SQLite constraints. The three compiled native cases remain UNRUN. Their proposed late Document-update trigger is correctly after page/history/Undo/source-pin writes, but is not observed rollback evidence yet; it covers duplicate creation, not a late Undo failure or mask rollback. Reopen is not process death. Fixtures share one source and provide no image files/decoder. Late recipe pointers and existing mask links are preserved statically; changing late mask sets is not directly asserted. After corrections, retain targeted regressions, identify the rebuilt artifact and execute the bounded Room cases under separate fresh P-04 ownership. Existing cancellation tests cover duplicate admission and removal-Undo prewrite cancellation, not an observed duplicate-Undo runtime cancellation.

## Declined to judge — individually retained gates

- Three new native cases actually passing: execution prohibited here.
- Command process death: no current-build runtime evidence.
- Additional IO interruption timings: not exercised.
- Dirty-WAL recovery: not exercised.
- Hardware power loss: not exercised.
- Canonical repository facade: outside increment.
- Production startup reconciliation: outside increment.
- Durable scheduler integration: outside increment.
- Durable recovery fairness: outside increment.
- Other recovery domains: outside increment.
- Latest20-edit enforcement: explicitly deferred.
- Expiry pruning: only eligibility checks reviewed.
- Pin release after retention: not implemented here.
- Garbage collection: no reclamation performed.
- Confirmed permanent deletion: distinct absent operation.
- Freeze/snapshot creation: outside increment.
- Export isolation/publication: outside increment.
- Committed-job recovery: outside increment.
- Actual source-file integrity: metadata-only fixtures.
- Real provider/decoder integration: T-04 gate.
- Album UI behavior: absent from increment.
- Complete FR-17 batch journey: absent from increment.
- Session Redo: later editor behavior.
- Uncertain committed-result reconciliation: explicitly unresolved.
- Working neural methods: later approved integration required.
- Physical-device performance: excluded device scope.
- Visual fidelity: no rendered representative pages.
- Full fresh-install offline journey: no scanner journey run.
- Accessibility: no UI execution.
- Rights clearance: no licensing acceptance requested.
- Old terminal-retry behavior: separate earlier mechanism.
- Future-version labeling: separate earlier mechanism.
- Seal handling: separate earlier mechanism.
- Owner-marker retention: separate earlier mechanism.
- Cooperative-root limits: separate earlier mechanism.

## Assessment

**Ready to proceed as the verified reversible-duplicate increment: With fixes; NEEDS FIXES.** Resolve I-1/I-2 and retain focused regressions before native acceptance. The new nominal insertion/Undo transition is coherent, but successful reversible duplication currently bypasses two necessary live-state checks. This is not whole-T-03/T-10/FR-17, merge, release or complete-scanner acceptance; no test was independently executed by this reviewer.
