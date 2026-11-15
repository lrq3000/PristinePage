# T03-RETRY-R — independent bounded persistence/concurrency review

2026-11-15. Senior Android persistence/concurrency reviewer. Findings: **0 Critical, 2 Important, 0 new Minor**. Both findings are established by inspected control flow, not runtime reproductions. Contract, code quality and operational scope are assessed separately; no merge, release, artifact or whole-T-03 acceptance is given.

## Inputs and evidence boundary

Reviewed HEAD `148ae5a9094eb4e46a716152266967a3e285af6c` plus actual uncommitted `RoomIngestAttempts`, `IngestIntentWriter`, `RoomIngestRestarts`, preparation ports/implementation, SourceStore and attempt/retry/fixture tests. Traced files, codecs, lookup/completion, phases, owners/native proof, readmission/recovery, lease fences/SQL, sessions and Room transaction adapter. Read both AGENTS, complete mandated PRD/TODO/PIPELINE/commands/operational/storage documents, accepted T-03/review focus, architecture, capture/session, privacy/resource and batch contracts, receipt/progress and relevant prior reviews. Disjoint coordinator Android-test preparation is excluded.

Snapshot anchors: `RoomSourceStore.kt` SHA-256 `754eda1e2c88f1523b39444fb04a6d964a8e101b6d33e4a43cd1a67964274a99`; attempts `2814a7c6a352ab18d7c7289522637ef227bdd46c11370791c6e4b7fc82262ad1`; restarts `a35dba626adfdcccc294f13be3a491d4071925045e6a1cd5e84713166704ddaf`. Accepted-plan hash independently matches `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Read-only verification used Git diff/status/rev-parse and Python XML parsing/hashing, not test execution. Retained `validation/raw/storage/ingest-attempt-{red,green}.junit.xml` records 8 missing-metadata assertions then 8 GREEN; `ingest-retry-{red,queued-red}.junit.xml` records 5 then 6 missing-adapter assertions, zero errors. Integration XMLs record attempt8/retry6 GREEN. Inventory sums 234 JVM tests: core23/fixtures15/data187/app9; saved integration log ends BUILD SUCCESSFUL/49s/138 tasks. Inventory reports zero compiler warnings, three existing lint warnings and unchanged schema/audit/plan. These are retained author results, not reviewer reruns. Actual streams/private files/codecs/commands/scopes are exercised; Android DB storage/provider bytes/normalized inspection are substituted. Fatal Error unwinds a real one-byte stream and structured scope; it is not VM death. Four bytes are not PNG acceptance. No current retry APK/native Room result exists; prior `7b41f283...` proves only its historical native ownership case.

## Strengths

- Retirement validates exact canonical journal/job/key/input/path identity, live reservation/destination/membership/capacity and recorded generation; owned takeover requires positive cached ENDED, never timeout/absence.
- Old payloads/paths/input/files remain retained. New transaction/blob/source/job IDs preserve ITEM/document/planned page/reference; PENDING is not admission. Throwing after failed writes makes rollback cover retirement, inserts and pointer update.
- The consumer performs native refresh and actual seal/final checks outside Room under the existing copy permit. Default construction stays conservative, and committed callbacks use private receipt verification without provider rereads.

## Issues

### Critical

None established.

### I-1 — Important — retired-attempt replay can falsely terminate a valid callback

**Evidence status: true by static interleaving.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomSourceStore.kt:27-30,37-39`, composing with `RoomIngestAttempts.kt:50-54` and `RoomIngestCompletion.kt:88-107` in the same directory.

**Counterexample:** callback A resolves old COPYING intent T1 at line27 and is preempted before replay. Callback B acquires the permit, retires T1 to QUARANTINED, updates the stable ITEM to T2, and starts or commits T2. A resumes replay(T1): completion interprets QUARANTINED as nonretryable INTEGRITY_FAILED; line30 returns it without checking the current pointer. This can occur within one process because this fast path is outside the mutex. Across processes, the same race also exists between lines37 and38; the mutex is process-local.

**Consequence:** a legitimate duplicate callback reports terminal corruption although the user's preserved import is healthy or already saved. The queued-callback test covers callbacks that both finish initial replay before waiting, not this interleaving. No source loss is asserted.

**Minimal remedy:** before accepting a noncommitted replay/preparation rejection, re-resolve the stable session and follow a changed transaction pointer; distinguish superseded retained journals from terminal current-ITEM outcomes. Serialize the initial fast path where practical, but retain pointer revalidation for cross-process writers. Add deterministic barriers around lookup→replay and prepare→commit races; require one page and consistent successor results, with bounded Pending rather than false terminal failure under continuing contention.

### I-2 — Important — a valid seal after retryable IO has no consuming recovery route

**Evidence status: true by static failure composition.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestRestarts.kt:28-36`, composing with `RoomSourceStore.kt:70,84,87-94`, `RoomIngestPhases.kt:59-64`, `RoomRecoveryCoordinator.kt:82-100`, and `data/src/main/kotlin/app/pristinepage/data/files/SourceFiles.kt:75-81`.

**Counterexample:** let seal writing/force succeed, then inject one transient directory-sync IOException at SourceFiles line80. The valid seal remains on disk, but SourceStore records journal RETRYABLE with null seal/hash/count and job RETRYABLE/null owner/null deadline. After storage recovers, the new retry adapter correctly discovers the valid seal and returns null rather than recopying. However, the existing reconciler rejects that scheduling state at line94 before inspecting the seal; it accepts only RUNNING/CHECKPOINTED or ADMITTED. Every subsequent callback/reconcile therefore remains Pending/blocked. Post-seal move/sync failures can strand equivalent sealed RETRYABLE operations too.

**Consequence:** an ordinary transient storage failure permanently blocks an otherwise verified import and leaves its BATCH RESERVED, preventing another insertion batch in that document. This is a dead end in the current advertised valid-seal handoff, not a demand for autonomous startup/provider resumption. Bytes are preserved, not lost.

**Minimal remedy:** supply a fenced owner-free sealed-recovery/readmission route that validates exact current generation/session/canonical identity and actual seal outside Room, then restores the appropriate phase and acquires a fresh live runner before existing move/commit. Preserve evidence and avoid provider reread/new-copy allocation for the valid seal. Add one-shot sync-after-seal and post-move failure tests, recover after the fault clears, and prove exactly one commit over two passes even with provider access unavailable.

### Minor

No new Minor finding. Existing terminal-Retry guidance M-1, future-version labeling M-2 and seal-retention precautions remain open.

## Operational scope

Reviewer scope: report-only compliance. Only `validation/reviews/t03-ingest-retry-review.md` was written. No source/test/schema/config/receipt edits, Git/index/HEAD/branch/worktree mutations, builds/tests/Gradle, ADB/console/emulator/phone operations, network/acquisition/keys/indexing/recall or nested delegation. No runtime-state audit was performed. Coordinator Android-test preparation observed in Git status is allowed and excluded; it provides no runtime evidence.

## Considered and declined individually

- Standalone metadata helper without file proof: explicit caller precondition; the consuming adapter supplies checks, not null-journal inference.
- Unexpected final or malformed/partial seal: retained and refused; automatic salvage is not required here.
- Autonomous startup INTENT/provider resumption: explicitly deferred, unlike I-2's existing sealed-recovery handoff.
- Different URI/new-child selection workflow: deferred; current binding is immutable.
- Actual Room/WAL rollback and full-ingest process death: unperformed platform gate, not inferred from host fixtures.
- Production app wiring/canonical facade: deferred; injected composition was nevertheless reviewed.
- Remaining document commands/freeze/export: separate incomplete tasks.
- Committed/terminal/refcount/snapshot/orphan broader recovery: separate bounded work.
- Real decoder/provider and normalized fidelity: T-04 acceptance, not synthetic inspection.
- Aggregate retained-attempt/marker cleanup: no deletion authorized; no retention-completion claim.
- Arbitrary roots/classloaders, malicious alias mutation, reboot/power loss: outside the documented cooperative native-proof guarantee.
- UI/FR-17: deferred feature acceptance.
- Neural methods: mandatory later, not evaluated here.
- Physical performance/offline/fidelity/rights: independent unperformed gates.
- Existing M-1 Retry guidance: still open, not re-counted or closed.
- Existing M-2 version labeling: still open, not re-counted or closed.
- Seal pruning: still prohibited pending its separate verifier/retention work.

## Recommendations and bounded verdict

Address both interleavings/failure routes with production-composition regressions before relying on this retry boundary. Keep UNKNOWN refusal, generation fencing and all retained bytes/seals intact; green author tests do not justify weakening them. **Contract: NEEDS FIXES. Code quality: NEEDS FIXES.** Operational compliance is separate. This verdict grants no task, merge, release or scanner acceptance.
