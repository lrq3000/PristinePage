# T03-RECOVERY-R — independent bounded review

2026-11-15. Senior Android persistence/concurrency reviewer. **Contract: NEEDS FIXES. Code quality: NEEDS FIXES.** Findings: 0 Critical, 1 Important, 1 Minor. Operational scope is assessed separately below. This is neither merge advice nor T-03/recovery/task acceptance.

## Inputs, method and evidence limits

Reviewed `d19362ae96cc16648de7bcb8156bdb16705cc9bd` → `7001c2014826d296e567dd7c4403f63a1b5dd66e`, plus actual uncommitted RecoveryPorts, RoomRecoveryCoordinator, SourceCopyAdmission, IngestRecoveryDao/database wiring, shared-admission extraction and IngestRecoveryTest/IngestIntentTest/SourceStoreTest changes. Traced preparation, lookup/replay/completion, phases, files, codecs, owners, lease SQL/fences and session consumers. Read both AGENTS, complete required PRD/TODO/PIPELINE/commands/operational/storage documents, accepted T-03/review focus, architecture/resource/privacy/capture contracts, batch amendment and latest checkpoint/rulings/receipt.

Read-only Git inspection: `git --no-optional-locks diff --stat d19362ae96cc16648de7bcb8156bdb16705cc9bd 7001c2014826d296e567dd7c4403f63a1b5dd66e`; `git --no-optional-locks diff --stat`; `git --no-optional-locks diff 7001c2014826d296e567dd7c4403f63a1b5dd66e -- data/src`; `git --no-optional-locks show --format=fuller --stat 7001c2014826d296e567dd7c4403f63a1b5dd66e`; `git --no-optional-locks status --short --branch --untracked-files=all`; `git --no-optional-locks log -1 --format=%H`.

Retained author evidence, not reviewer reruns: recovery XML records initial 8 missing-coordinator failures, corrected boundary 2 failures, subsequent listing 3 failures, and final 15/15 GREEN; readmission XML records 7 RED then 7 GREEN. Final saved log ends BUILD SUCCESSFUL/47s/138 tasks; inventory reports 210 JVM tests, zero failures/errors, three existing lint warnings and unchanged schema/audit/plan. The compiler-temp failure is not product RED. The initial 130-child fixture failure is disclosed and excluded from intended RED; its XML survives despite reused log. Tests exercise production orchestration and real private file IO/local structured ENDED, but substitute Android row storage, provider streams and normalized inspection; four bytes are not PNG validation. No current recovery Android/process-death result follows.

## Strengths

- Immutable-ID pagination validates each complete bounded page before executing it; pass-level listing failures stay typed and cannot falsely return an empty successful report or undo earlier commits.
- Recovery and ingestion share one process-wide copy/hash Mutex. File/hash work remains outside Room; canonical ownership, fresh scoped coordinator owners, incremented per-job leases and existing joint completion preserve visibility fencing and replay identity.
- ALIVE/UNKNOWN block recovery without trusting expiry. Unsealed data remains untouched; no provider/decoder opening, deletion, fabricated read lease or whole-store zero-report claim was found in the coordinator.

## Issues

### Critical

None identified in this bounded static review.

### I-1 — Important — readmission bypasses the ended-owner reacquisition guard

**Evidence status: true by static API composition; not executed.** Location: `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestReadmission.kt:29-30`, composing with `data/src/main/kotlin/app/pristinepage/data/repository/RoomJobLeases.kt:26-42` and `data/src/main/kotlin/app/pristinepage/data/repository/JobLeaseTransactions.kt:35-38`.

**Counterexample:** obtain a real locally ended owner O with recorded RUNNING lease generation 1 and a recoverable ingest; under recovery admission, `readmitEnded(intent, oldLease)` clears ownership to ADMITTED. Before a fresh runner acquires, a delayed consumer calls `acquireAdmitted(jobId, O, duration)`. The ADMITTED branch checks only null scheduling fields, bypassing the RUNNING/CHECKPOINTED `oldOwner == owner` guard. It grants generation 2 while the real registry still reports O as ENDED. Checkpoint/joint completion do not consult requesting-owner liveness and can accept that new lease.

**Consequence:** the public metadata components allow a terminal owner identity to execute again, undermining the positive ENDED evidence used to authorize subsequent readmission. This reopens the prior same-owner safety defect through the newly introduced ADMITTED route; it is an unsafe composition contract, not a claim that the inspected coordinator itself chooses O. Its `owners.run` path correctly requests a fresh owner. Actual overlap or source loss is not demonstrated.

**Minimal remedy:** reject acquisition unless the requesting identity is currently ALIVE in the bounded registry, for ADMITTED as well as takeover paths, or enforce an equivalent live scoped-owner capability. Keep the existing old-owner guard and UNKNOWN refusal. Add a real-registry readmission→same-ended-owner acquisition regression proving no write, plus a fresh-ALIVE-owner positive case and stale-generation publication refusal. Do not perform OS IO under Room or infer ALIVE/ENDED from deadlines.

### M-1 — Minor — returned terminal errors advertise Retry

**Evidence status: true by static control flow.** Location: `data/src/main/kotlin/app/pristinepage/data/repository/RoomRecoveryCoordinator.kt:21,79-80,123-124,130`.

**Counterexample:** the existing `data/src/test/kotlin/app/pristinepage/data/IngestRecoveryTest.kt:99-105` canceled-parent scenario makes replay return `Rejected(CANCELED, retryable=false)`. Wrapping that returned result in default Outcome produces a RecoveryIssue with action RETRY. Returned integrity failures similarly bypass the REVIEW_INTEGRITY selection used only for thrown RepositoryFailure.

**Consequence:** callers receive contradictory permitted-action guidance and can repeatedly offer a retry which cannot resume a terminal operation. Originals remain safe; this is not a publication/data-loss finding.

**Minimal remedy:** centralize error-to-action mapping for both returned rejections and thrown failures; cancellation should not offer retry, and integrity should direct review. Extend the existing canceled-destination test and one returned-integrity case to assert action as well as counts/error.

## Operational-scope assessment

**Reviewer operations: PASS.** Only this report was written. No source/test/schema/config/receipt changes, Git/index/HEAD/worktree-state changes, tests/builds/Gradle, devices/ADB/console, network/acquisition/keys/indexing/recall or nested delegation was performed by this reviewer. Report verification used read-back and a read-only word count; the file is below 1400 words.

**Shared review-window exception:** `validation/progress.md:5` now reports coordinator Android-test build `sh_1dfd9b7f8001u3WMtZCeXUfILX` during this review. The assignment allowed disjoint Android-test preparation, not an additional build. I did not authorize or audit that operation; no broader operational PASS or runtime inference is made. Disjoint Android-test changes are excluded from this frozen production/host-test verdict.

## Considered and declined individually

- Native cross-process termination detection/cache: expressly deferred; local ENDED is not process-death proof, and restarted identities remain UNKNOWN.
- Unsealed/provider resumption, preserved fresh attempts and owner-free retries: expressly deferred; blocked outcomes are not implementation acceptance.
- Committed/terminal verification, other transaction kinds, refcounts, snapshots and orphans: excluded from this unfinished-INGEST pass; zero does not certify them.
- Canonical facade, freeze/export and remaining document commands: separate incomplete work, not charged merely for absence.
- Actual T-04 provider/decoder and allocation fidelity: substituted here, not accepted from synthetic seals.
- Current Android Room/crash/process-death integration: unperformed for this slice; earlier five SourceStore cases belong to the base.
- UI/FR-17, neural methods, physical performance, offline/fidelity/rights: independent deferred gates.
- Future-version error labeling and staging-seal pruning: prior M-2/retention precautions remain open; no reset or pruning was introduced, and no closure is claimed.

## Recommendations and bounded assessment

Resolve I-1 before relying on readmission as a safely composable lease boundary; correct M-1 before exposing RecoveryIssue actions. Retain seals and the frozen evidence, then obtain the separately required current native/crash-boundary evidence. **Contract/code-quality verdict: NEEDS FIXES**, independently of green host tests. No task, artifact, merge or recovery acceptance is granted.
