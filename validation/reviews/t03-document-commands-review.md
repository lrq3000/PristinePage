# T03-COMMANDS-R — independent bounded persistence review

## Scope and evidence

Senior Android persistence/repository review, 2026-11-15. Reviewed actual uncommitted increment over `97d72d2d43bccf729b7bc509e0ac5846dcbf93ef`, branch `feat/paperlight-v1`, worktree `X:\local-workspace\.worktrees\paperlight-v1`; same T03-20261115-A claim, not whole-task acceptance. Read root/spec AGENTS, complete mandatory PRD/TODO/PIPELINE/incident/operational/storage specifications, accepted implementation plan including T-03/T-10, batch/architecture/privacy/capture/resource contracts, all six assigned source/test files and receipt, supporting DAOs/rows/transaction adapter/import completion/history regressions and retained evidence. Applied requesting-code-review/code-reviewer template; no nested delegation.

Read-only commands: `git --no-optional-locks status --short --branch`, `rev-parse HEAD`, scoped `diff --stat`/`diff`, and `python -B -` for hash/XML/ZIP inspection only. Six frozen source hashes match inventory; all recorded current JVM XML hashes match: core23 + fixtures15 + data216 + app9 =263, zero recorded failures/errors, including15 command cases. Retained test APK matches SHA-256 `59dd6c16558f8666b639dd5eb0003bc82aad7031472b8cbe5c845387473542f9`,3322919bytes; ZIP has no native-library entries. This verifies retained evidence, not independent execution. Four new native cases remain unrun. Only this report was written; no source/test/config/schema/receipt/evidence/Git state was changed, and no build/test/device/network operation occurred.

## Strengths

Exceptions leave the actual Room transaction before conversion to results. Exact membership/document-CAS/import fencing and disjoint ordinal writes respect immediate SQLite uniqueness. Removal retains history/ownership, and membership Undo preserves later recipe pointers. Rotation uses immutable append, widened signed modulo arithmetic and existing transactional mask/source rechecks. Unknown recipe bytes are not decoded by membership-only edits. No file mutation or new external capability is introduced.

## Issues

### Critical — 0

None identified in the bounded static review.

### Important — 2

**I-1 — Canonical removal history is not validated as a removal transition.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:257-259,275-278`. Established statically; not runtime-reproduced. Decoder validation proves each snapshot is canonical and internally well-formed, but the cross-snapshot checks establish only equal ID/source mappings, all-active before-state and exactly one removed after-state. Counterexample: remove A from `[A,B,C]`, retaining after-state `[B,C]`; corrupt the canonical before-state by swapping B/C ordinals to `[A,C,B]`, leaving IDs/sources/states valid. Current membership still exactly matches after-state, so Undo applies `[A,C,B]`: restoring A also unexpectedly reverses untouched survivors. This is detectably inconsistent PAGE_REMOVE history, not an impossible requirement to authenticate every valid-looking historical permutation. Minimum remedy: derive the expected after-state from before by removing exactly the recorded page and compacting ordinals, then require equality, including unchanged survivor state/source/removedAt. Reject inconsistent history with INTEGRITY_FAILED before writes. Retain a canonical-but-semantically-invalid regression asserting unchanged pages/revision/Undo state/references. `DocumentCommandsTest.kt:126-132` tests only `{}` and already-expired history, not this case.

**I-2 — Reactivation checks Source readiness but not its backing SourceBlob.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:267-276`; supporting `data/src/main/kotlin/app/pristinepage/data/db/DocumentRows.kt:12-16` and `data/src/main/kotlin/app/pristinepage/data/repository/RepositoryPorts.kt:13-23`. Established statically; not runtime-reproduced. Source and blob have independent states, and their FK does not imply READY consistency; this command has no blob lookup. Counterexample: after removal, the referenced blob is QUARANTINED or DELETING while Source remains READY (inconsistent/recovery-affected persisted metadata); Undo passes line271 and makes the page ACTIVE despite its original already being marked unavailable. The new guard therefore protects only one level of the source chain. Minimum remedy: within the same transaction require the linked blob exists and is READY before reactivation, through a narrow metadata query/shared integrity helper; no hashing/file IO under Room. Add a native metadata regression that changes only SourceBlob.state, reopens, and verifies rejection with page still removed, Undo AVAILABLE, unchanged revision/pins. Existing host/native quarantine cases change only Source.state. This is a current restoration-invariant gap, not a claim that today's recovery already emits that inconsistent combination or a demand to implement physical GC.

### Minor — 1

**M-1 — Pre-commit cancellation remains unobserved after synchronous admission.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:87-94,445-449`. Inherited shared-command limitation, now reached by rotatePage; not attributed as a newly introduced helper regression. Static counterexample: cancel the calling Job during synchronous RecipeAdmission and return normally. There is no suspension/activity check before append/commit; the metadata may commit and withContext subsequently report cancellation rather than Applied. Minimum remedy: poll coroutine activity after preflight and before the first transaction mutation; add a cancel-during-admission oracle. Keep explicit reconciliation for unavoidable cancellation racing an already completed commit; this finding does not promise cancellation can reverse committed edits.

## Coverage and recommendations

Host tests execute real repository/codec code but MemoryMetadata supplies rollback without SQLite constraints. Native trigger tests abort the second temporary ordinal UPDATE, before Undo/reference insertion: they do not demonstrate rollback after pin creation or late final writes. Native combined fixture shares one synthetic source, has no masks/source bytes, and reopen is not process death. Add the targeted counterexamples above and a late-write rollback oracle before broadening confidence. After corrections, rebuild/re-identify the changed artifact and run the named Room cases only under fresh P-04 ownership. Green retained tests do not settle the untested corruption/cancellation cases.

## Declined to judge — each remains open, not implicitly safe

- Actual execution of the four new Room cases: prohibited in this static assignment.
- Command process-death/dirty-WAL/power-loss behavior: no current command-build runtime evidence.
- Real provider decoding/source-byte integrity: fixtures contain synthetic metadata, not decoder evidence.
- Canonical facade/startup/scheduler/durable recovery fairness: outside this increment.
- Other recovery domains and global reference/orphan reconciliation: outside this increment.
- Physical purge/GC: absent; no reclamation safety acceptance inferred.
- Confirmed permanent document deletion: absent; reversible removal is not that operation.
- Duplicate membership Undo: unchanged null token; no new acceptance inferred.
- Latest20-edit enforcement and expiry/pruning/pin release policy: deferred; current expiry checks were inspected, policy completion was not accepted.
- Freeze/snapshot/jobs/export isolation: no freeze implementation in this increment.
- Album draft/Done/Cancel/Redo/UI and FR-17 completion: individual commands do not implement them.
- Actual provider assets/neural processing/rights: unrelated later gates.
- Physical-device performance/fidelity/offline/accessibility/release behavior: not evidenced here.
- Old terminal-retry/future-version-labeling/seal/marker-retention/cooperative-root limits: separate earlier mechanisms, not re-reviewed for acceptance.

## Assessment

**Ready to proceed as corrected command increment: With fixes; current verdict NEEDS FIXES.** Resolve I-1/I-2 and retain focused regressions before native acceptance; M-1 remains a smaller explicit command-contract limitation. No whole-T-03/T-10/FR-17, merge, release, physical-device or complete-scanner acceptance is granted. Findings are static control-flow/invariant judgments, not reproduced runtime failures.
