# T03-RESUME-R — independent persistence/concurrency review

2026-11-15. Senior Android persistence/concurrency reviewer. Findings: **0 Critical, 1 Important, 0 new Minor**. Static review, not an executed reproduction. No whole-T-03, scanner, artifact or device acceptance is granted.

## Inputs and method

Reviewed actual uncommitted `data/src/main/kotlin/app/pristinepage/data/repository/RoomRecoveryCoordinator.kt`, `data/src/test/kotlin/app/pristinepage/data/IngestRecoveryTest.kt` and `validation/storage/t03-provider-resumption-receipt.md` against HEAD `e16ab8cad780983e5b71733c3d4f26fac1f4732d`, branch `feat/paperlight-v1`. Traced SourceStore, preparation/lookup/replay/completion, phases/restarts/attempts, file/seal protocol, owners/native proof, lease fencing, sessions and listing SQL. Read both AGENTS, complete mandatory PRD/TODO/PIPELINE/common-mistakes/OPERATIONAL_PLAN/storage documents, accepted-plan T-03/review focus and relevant architecture/privacy/resource/capture contracts; consulted prior boundary reviews.

Read-only commands: `git --no-optional-locks diff --stat`; scoped `git --no-optional-locks diff -- <coordinator> <host-test>`; Python file/XML/hash inspection with read-only Git HEAD/status queries. No code was executed to test the finding. Coordinator Android-test preparation/progress changes are disjoint and excluded.

Snapshot hashes independently match inventory: coordinator SHA-256 `62ab6b3b414bf6cfef5a54a42eafb8638db1ebc130aefe9359f47d317386c6f8`; host-test `6938d756349a05850d8018ae834b0c6122aa08c9153a76cdf87ae99c6f3cabb7`. Accepted plan remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

## Evidence limits

Parsed retained author XML, not reviewer reruns: initial RED 23 cases/4 failures includes the disclosed permission-test NullPointerException; corrected permission RED is one explicit assertion; boundary RED has 26 cases/7 assertion failures/0 errors. Saved GREEN data XML contains 199 cases, including recovery26, with zero failures/errors. All inventory XML hashes match; composed totals are core23, fixtures15, data199, app9 = 246.

The ordinary integration log actually fails at app worker loopback connection before fresh app JUnit; the unchanged targeted app/lint diagnostic succeeds in31s/126 tasks. Lint XML contains zero errors/three existing warnings. Core/fixtures were UP-TO-DATE. This is composed evidence, **not one successful246-case integration run**; timeout causation/repair remains unverified. Real files, codecs, metadata commands and scoped owners are exercised; Android row storage, provider and normalized inspector are substituted. No current resumption APK/native result exists; historical `fc4716e8...` is not current acceptance.

## Strengths

- Existing seal recovery retains priority. Native proof/file/hash IO remains outside Room; ALIVE/UNKNOWN do not authorize takeover.
- Provider plans stay private and session-bound. Releasing the shared non-reentrant copy permit before SourceStore avoids recursive deadlock; SourceStore repeats reference/pointer/admission/live-owner/fence checks.
- Retained attempts are not overwritten. Cancellation propagates, permission loss requests reselection, and Pending handoffs do not inflate resumed counts.
- The16-session set prevents same-pass successor-UUID retry loops without an unbounded retained-ID set.

## Issues

### Critical

None established.

### I-1 — Important — nonprogressing intents can starve every later valid import

**Evidence status: true by static composition; not reproduced.** Locations: `data/src/main/kotlin/app/pristinepage/data/repository/RoomRecoveryCoordinator.kt:30-42,162-168`; `data/src/main/kotlin/app/pristinepage/data/repository/RoomSourceStore.kt:28-35,61-69`; `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestPreparation.kt:18-21`. Listing order is fixed by `data/src/main/kotlin/app/pristinepage/data/db/IngestRecoveryDao.kt:9`.

**Counterexample:** retain16 prepared, owner-free PENDING/INTENT items whose sessions have expired, followed lexically by one healthy item. Replay returns Pending because expiry is not a terminal persisted state. `planProvider` checks child state/binding but not expiry, so each expired item consumes a handoff. SourceStore preparation rejects SESSION_EXPIRED without changing its journal/job/session. Every subsequent reconcile resets `after` and the handoff set, consumes the same16 slots, and never invokes the healthy item's provider. An unchanged Pending adapter or persistent pre-copy rejection has the same scheduling defect.

**User consequence:** an otherwise readable import can remain blocked indefinitely, with its batch unresolved, solely because unrelated earlier imports cannot progress. No source loss is asserted. The17-item test at `data/src/test/kotlin/app/pristinepage/data/IngestRecoveryTest.kt:333-340` covers successful removal of the first16, not this case.

**Minimal remedy:** retain fair handoff scheduling across passes, using a cursor/rotation over eligible stable items or equivalent explicit terminal/cooldown handling that guarantees later items receive service. Preserve the16-call limit and once/session/pass rule. Add a deterministic17-item regression with16 unchanged expired/no-op outcomes and one healthy item; repeated passes must eventually consume the healthy item while preserving old evidence. Merely raising the cap or promising another pass does not fix starvation.

### Minor

No new Minor finding. Previously recorded M1/M2/seal-retention limitations are neither closed nor recounted.

## Recommendations and operational scope

Fix I-1 before relying on autonomous bound-provider resumption. Do not remove positive-ENDED checks or refund every failed handoff in an unbounded loop to recover throughput. Retain the separate native and full-ingest interruption gates after the targeted regression.

Only this report was written. No source/test/schema/build/config/receipt/index/HEAD/branch/worktree mutation, build/test/Gradle, ADB/console/emulator/phone operation, network/acquisition/key access, indexing/recall or nested delegation occurred. No independent audit of concurrent runtime activity was performed.

## Considered and declined individually

- Canonical database-backed recovery facade: expressly deferred; injected composition was reviewed.
- Production app startup wiring: deferred integration, not established by the optional constructor.
- Production scheduler: separate unimplemented entry point.
- Other file-transaction kinds: outside unfinished INGEST scope.
- Committed-source integrity sweep: separate recovery domain.
- Refcount reconciliation: separate recovery domain.
- Snapshot/pin reconciliation: separate recovery domain.
- Orphan recovery: separate recovery domain.
- Actual Room/WAL resumption behavior: no current native evidence.
- Full-ingest process death: not established by host faults or prior native-lock proof.
- Reboot recovery: not exercised.
- Hardware power-loss durability: not exercised.
- Real provider/grant behavior: T-04 integration, substituted here.
- Real normalized decoder/allocation fidelity: T-04, synthetic inspection is insufficient.
- Camera private-URI handoff: future exact-transaction implementation.
- End-user reselection/new-child UI: deferred; I-1 concerns scheduling before that UI.
- Remaining document commands: incomplete separate implementation.
- Library UI: deferred.
- Editor UI: deferred.
- Export/publication/sharing: separate boundaries.
- FR-17 album batch editing: remains mandatory and unaccepted.
- Neural geometry/appearance methods: mandatory later, not evaluated here.
- Physical-device performance: excluded phone/unperformed physical gate.
- Complete offline scanner behavior: no end-to-end implementation evidence.
- Real-page visual fidelity: no current permission-cleared acceptance evidence.
- Distribution rights: separate unresolved gate.
- Existing M1 terminal-Retry guidance: previously recorded, not closed.
- Existing M2 future-version labeling: previously recorded, not closed.
- Seal retention/pruning: separate verifier/retention work; no pruning introduced.
- Aggregate retained-attempt/marker cleanup: deferred policy, not deletion authority.
- Arbitrary ownership roots: outside the stable-root protocol guarantee.
- Separate application classloaders: outside the shared-classloader guarantee.
- Malicious alias/same-UID mutation: outside the cooperative protocol threat boundary.
- Concurrent disjoint Android-test preparation: excluded from frozen-input review and not runtime evidence.

## Assessment

**Ready to integrate this increment? With fixes. Contract/code quality: NEEDS FIXES.** I-1 violates reasonable-user progress expectations despite green retained tests. Preserve the bounded safety mechanisms while adding fairness. This verdict grants no merge/publication permission and accepts none of the individually deferred behaviors above.
