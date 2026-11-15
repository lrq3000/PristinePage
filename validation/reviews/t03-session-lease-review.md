# T03-SESSION-LEASE-R — independent bounded review

2026-11-15. Reviewer role: senior Android persistence/concurrency engineer. **Contract compliance: NEEDS FIXES. Code quality: NEEDS FIXES. Operational/scope compliance: PASS.** One Important finding; no Critical or additional Minor findings. These are static-review verdicts, not task acceptance or runtime verification.

## Inputs and method

Reviewed baseline `dd7e53e6442599b664e86d2ea0d98ff91f1aad1c`, reservation commit `e594e6fa924483fc7ab994556b20d814c3a95955`, and binding/current HEAD `4fe40507fc9810468378c09d93f2e66d6ec6930a` on `feat/paperlight-v1`. Also reviewed actual untracked `JobLeasePorts.kt`, `RoomJobLeases.kt`, `db/JobLeaseDao.kt`, `JobLeaseTest.kt`, and modified `db/PristineDatabase.kt`, including the applied `safeWrite` correction. Read both AGENTS files, full PRD/TODO/PIPELINE/operational/commands/storage/batch contracts, exact accepted T-03, receipt/progress, referenced rows/ports/fixture and exported ImportSession/Job DDL. Latest assignment controls over stale progress wording.

Read-only commands: `git --no-optional-locks log -3 --format=fuller --stat`; scoped `git --no-optional-locks diff dd7e53e6442599b664e86d2ea0d98ff91f1aad1c -- <reviewed session paths and PristineDatabase.kt>`; `git --no-optional-locks status --short --branch --untracked-files=normal`. Report-only verification uses read-back and `Get-Content -Raw | Measure-Object -Word`. No tests/builds, changing reports, runtime/ADB/console, network, acquisition, keys, Git mutation, shared-record writes or nested agents. Only this report was written.

## Finding

### I-1 — Important — expired duplicate owner can obtain a new attempt

**Evidence status: true by static control flow; not runtime-reproduced in this review.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomJobLeases.kt:27-40` checks expiration and the previous owner's ENDED status but never checks whether the requesting owner equals that previous owner. Once those conditions pass, it increments attempt/generation and grants the same owner a new lease. This violates the explicit same-owner duplicate-acquire Busy requirement.

**Consequence:** a delayed/repeated acquire can restart work under an owner identity already recorded as ended, rather than require a distinct execution owner. Besides unnecessary retries, this undermines a terminal owner-registry record: a later expiry could consult the old ENDED record for newly running work under that reused ID. Actual overlapping native execution is not asserted; the registry/worker integration does not exist here.

**Counterexample/oracle:** acquire owner O at clock1000 for100ms; advance clock to1100 or1101; return ENDED for O; acquire again with O. Current branch returns Granted with generation2/attempt2. Required result is Busy with the entire row unchanged. Repeat from CHECKPOINTED as well as RUNNING. Existing `data/src/test/kotlin/app/pristinepage/data/JobLeaseTest.kt:48-55` tests same-owner replay only before expiration, so it misses this branch.

**Minimal remedy:** in the RUNNING/CHECKPOINTED branch, return Busy when `oldOwner == owner`, before permitting expired-ended takeover. Require a fresh opaque owner for a new execution attempt. Add the counterexample regressions and retain the distinct-new-owner, expired-plus-ENDED success case. Do not weaken ALIVE/UNKNOWN refusal or infer ENDED from a timeout.

## Considered, with no additional current-slice defect found

- Sessions: parent insertion precedes return; children/planned PageIds are stable ordered metadata, not visible pages. Same-count replay preserves IDs, changed counts reject, capacity failure inserts no subset, and ordered-PageId hashing excludes recipe/document revisions. Document lifecycle/membership checks fence stale/foreign destinations; ITEM-as-parent/unreserved routes reject. URI binding is bounded, Unicode/scheme/authority checked, private and immutable after binding; identical URI replay preserves the child. No provider access/grant/file-camera exception is enabled.
- Transactions/SQL: native constructors bind each DAO to the same database as `RoomMetadataAccess.runInTransaction`. Synchronous reads/checks/writes do not suspend within that writer transaction. Bound SELECT/INSERT/UPDATE SQL matches the inspected schema, unique child ordinals/planned IDs and leading FK indexes. The lease setter changes only scheduling/checkpoint fields, not canonical input/key/hash or consumer identity. Generation-only SQL predicates are backed by owner/state/deadline checks in the same transaction, not an unfenced separate read.
- Leases/errors: initial PENDING/WAITING_RESOURCE/terminal jobs cannot acquire; arithmetic is checked; ALIVE/UNKNOWN block expired takeover; unexpired ENDED also remains blocked. Checkpoint/complete verify owner, generation, running state and unexpired deadline. Checkpoints are copied and limited to524288 bytes. Cancellation propagates; present `safeWrite` wraps unexpected checkpoint/completion storage exceptions without raw messages. This confirms the correction structurally, not its active-run result.
- Integration precautions, not missing-slice findings: retain real resource admission for every attempt, and define registry ENDED only after actual owned native work/resources finish. Unresolved/expired batches still require safe cancel/recovery transitions before replacement. For future joint metadata completion, expose/reuse transaction-scoped fence/update logic; do not block a synchronous writer awaiting this suspending facade or mark success before dependent rows commit. Standalone `complete` publishes no file.

## Explicitly declined / unaccepted

No rerun of recipe or saved-Room reviews. The historical five-case actual-Room artifact does not execute these new session/lease classes. The receipt's98-JVM/161-task checkpoint is historical reported evidence; active integration `sh_1df466277001eDSV4uDSeG8xhp` and its XML were not inspected or inferred.

DocumentRepository facade delegation, SourceStore/ingest, registration/admission/scheduling, local-liveness registry, journal/file-plus-DB commit, recovery/process death, freeze/export, provider/neural/UI, physical/performance/visual/offline/rights and whole T-03 acceptance remain explicitly unimplemented or unverified. Their absence is not charged as a defect in this partial metadata slice. No authority or acceptance was changed.
