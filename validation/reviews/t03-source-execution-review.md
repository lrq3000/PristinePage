# T03-SOURCE-EXEC-R — independent bounded review

2026-11-15. Senior Android persistence/concurrency review of actual uncommitted SourceStore/phase execution against HEAD `a19ac560e6f7d6dbcef4add29157af1501ef900e`, branch `feat/paperlight-v1`. **Contract: NEEDS FIXES. Code quality: NEEDS FIXES. Operational scope: PASS.** No Critical findings; three Important findings. No merge recommendation, task acceptance or authority change.

## Inputs and evidence

Read both AGENTS, complete mandatory PRD/TODO/PIPELINE/commands/OPERATIONAL_PLAN/storage specification, accepted T-03 and review focus, capture/resource/privacy/architecture contracts, storage receipt and progress. Reviewed SourceIngestPorts, RoomSourceStore, RoomIngestPhases, IngestPhaseDao/database addition, both new test classes and IngestIntentTest fixture changes; traced preparation, lookup/replay, completion, sessions, owners, leases, files, codecs and generated phase SQL.

Findings below are **true by static control/dataflow; counterexamples were not executed**. Frozen SourceStoreTest/IngestPhaseTest XML records 13/7 tests with zero failures/errors. The integration log ends BUILD SUCCESSFUL/161 tasks; the saved inventory reports 180 JVM tests and three lint warnings. Those are retained author evidence, not reviewer reruns or Android acceptance. Three read-only Git commands inspected the narrow tracked diff, HEAD and diff summary. No builds/tests/Gradle/runtime/device/network/acquisition/key/indexing/recall/delegation or Git mutation occurred. Only this authorized report was written.

## Strengths

- Process-wide Mutex admission precedes ADMITTED/lease acquisition; structured owners retain ALIVE through stream cleanup, with monotonic fences and UNKNOWN blocking takeover.
- Provider/file/hash/inspection work is outside Room. Stream ownership transfers synchronously, staging closes before inspection, actual-byte limits are enforced, and phase failure/final-CAS loss rolls back journal/job writes together.
- Recorded completion uses lease-free lookup/replay and verified private bytes. Interrupted non-INTENT operations are not restarted or overwritten. Native phase SQL changes execution fields only.

## Issues

### Critical

None identified in this bounded static review.

### I-1 — Important — terminal failures never finish their import sessions

**Location:** `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestPhases.kt:45-55`; consumed by `RoomIngestCompletion.kt:149-151` and `RoomImportSessions.kt:33` in the same directory.

**Counterexample:** reserve/bind two items; reject the first through the inspector with UNSUPPORTED_FORMAT, then successfully ingest the second. `failed` persists FAILED in FileTransaction/Job but never updates the first ITEM, which remains INGESTING. Completion therefore leaves the parent RESERVED because its sibling-terminal predicate reads ITEM states. Even a single failed item leaves this condition. Replaying the failed callback returns its terminal rejection without repairing sessions.

**Consequence:** a completed mixed-result import continues to block the next import with `finish_import_before_new_batch`; retained provider-reference metadata also remains attached to an apparently active item. This is a current producer/consumer state mismatch, not a finding that a future cancel/recovery command is missing.

**Minimal remedy:** inside the same fenced failure transaction, record the matching ITEM's terminal outcome and settle the parent when all children are terminal, preserving accurate mixed outcomes, independent cancellation and retryable-state semantics. Do not delete retained payloads. Add single-failure and mixed-batch tests proving a subsequent beginImport is possible without replay/recovery repair.

### I-2 — Important — provider read IO is reported as internal storage failure

**Location:** `data/src/main/kotlin/app/pristinepage/data/repository/RoomSourceStore.kt:60-63,84,100-101`.

**Counterexample:** SourceInput opens successfully, then its stream throws IOException during read because selected content becomes unavailable. CheckedInput forwards it unchanged; the shared stage/execute catch records STORAGE_FAILED and returns Pending(RETRYABLE), exactly as for an app-private disk error. The FileNotFoundException correction applies only to open, not read.

**Consequence:** source/provider loss is indistinguishable from failed private storage, preventing accurate retry/reselect versus storage guidance under FR-14. The source is not published, but the persisted explanation is wrong for an ordinary import failure.

**Minimal remedy:** classify provider-origin IO at open/read/close before it enters the file layer, using a safe typed source error and appropriate retryability. Leave private create/write/sync/move IO classified as storage failure; preserve permission loss and cancellation. Add separate mid-read-provider and private-disk failure oracles.

### I-3 — Important — unexpected provider exceptions escape the safe result boundary

**Location:** `data/src/main/kotlin/app/pristinepage/data/repository/RoomSourceStore.kt:60-63,77-85,97-106`; `SourceIngestPorts.kt:9-12` declares no adapter obligation that excludes these exceptions.

**Counterexample:** provider.open, read or close throws `IllegalStateException("private provider detail")`. Unlike unexpected inspector exceptions at lines65-68, this exception matches none of execute's catches or the outer typed-failure catch. Ownership unwinds, but the raw exception escapes; journal/job remain COPYING/RUNNING instead of recording a structured failure.

**Consequence:** a caller's import coroutine/batch can abort without an IngestResult or safe persisted cause; later callbacks merely see interrupted Pending. Raw provider diagnostics cross the intended safe error boundary.

**Minimal remedy:** normalize unexpected ordinary provider exceptions into a safe typed source/processing failure and fenced failure recording. Continue propagating CancellationException and preserving RepositoryFailure; do not catch fatal VM errors. Test unexpected open/read/close failures and exactly-once cleanup.

### Minor / carried forward

No new Minor finding. Prior M-2 future-version error labeling remains open, not fixed/retested here. Retain staging seals: committed replay still requires them; no cleanup/pruning safety is accepted.

## Considered without additional defect found

- Canonical intent/job/path checks, same-database transaction construction, pre/post generation/deadline fencing and failed-write rollback are structurally coherent.
- Lost phase writes, elapsed expiry and uninterruptible reads may leave private recovery data, but cannot publish a page through the current completion fence. No timeout establishes owner termination.
- Four synthetic bytes and substituted inspection/DB storage are disclosed. Sequential replay tests are useful; concurrent cross-instance callbacks remain an additional integration-test precaution, not a proven defect.

## Declined to judge / explicitly unaccepted

- Actual application wiring, Android provider adapter and T-04 normalized inspector/decode allocation: not this admitted execution slice.
- Current-flow Android Room/FK/order/rollback and process-death behavior: generated SQL/host transactions are insufficient evidence.
- RecoveryCoordinator, new-attempt readmission and positive cross-process termination proof: future recovery boundary.
- Repository facade, freeze/export and remaining document commands: outside this increment.
- UI/FR-17, neural methods, physical-device/performance/offline/visual acceptance and rights: separate implementation/evidence gates.

## Recommendations and assessment

Resolve I-1–I-3 with retained targeted regressions before relying on this execution component. The bounded contract and quality verdicts require fixes; operational scope passes only the inspected review envelope. Neither the green build nor this report accepts T-03, recovery or a functioning scanner.
