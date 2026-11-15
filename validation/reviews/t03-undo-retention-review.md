# T03-UNDO-RETENTION-R — independent persistence review

## Scope and requirements

Senior Android persistence/Room review of the actual uncommitted availability-only increment against HEAD/base `c5a64a5287890e31f6168584ef5cd5a390204e23`, branch `feat/paperlight-v1`, under T03-20261115-A/P-01/U-05. Inspected all ten named source/test inputs, receipt, supporting entities, Room adapter, generated SQL, callers and retained evidence. The index is empty; untracked policy/tests/receipt were included rather than omitted by relying on Git diff alone. Read both AGENTS, complete mandatory authorities, accepted IMPLEMENTATION_PLAN including T-03, and storage/architecture/privacy/capture/resource/batch contracts. No replacement plan.

The assessed contract is latest 20 recorded reversible edits per document, inclusive Unix-time expiry, permanent EXPIRED availability state, shared recipe/membership creation policy, atomic creator rollback, retained bytes/ownership, sanitized standalone outcomes and owning-context cancellation. Reasonable-user consequences include an evicted token executing a repeated logical after-state, lost successful edits after late failure, wrong-document expiry and premature source reclamation.

## Evidence inspected

- Read-only methods: `git --no-optional-locks status --short --branch`, `rev-parse HEAD`, tracked/index diff inspection, direct file reads, and bounded Python standard-library hash/XML/ZIP inspection. No application code, tests, Gradle, SDK commands, device operations, network or nested agents were executed.
- All ten current source SHA-256 values match `validation/raw/storage/undo-retention-review-inventory.json`. All 35 suite XML hashes/counts match: 319 recorded cases, zero failures/errors/skips; data 272 and app 9 executed in the final log, core 23 and fixtures 15 were UP-TO-DATE. This is inspection of retained results, not a reviewer rerun.
- Preserved RED XML contains nine intended failures/zero errors: three state mismatches, actual execution of the old token, bypassed injected expiry failure and four absent-policy assertions. ComparisonFailure brackets explain the recorded parser normalization; no altered test result is needed. Preserved focused GREEN records nine passing cases; logs record 45s/22 tasks RED, 61s/22 tasks GREEN and successful 161s/168-task integration. No compiler-warning lines; inventory records lint zero errors/three existing warnings and the log retains Gradle deprecation summary.
- Nine schema/lock/verification/accepted-plan files are byte-identical to HEAD. Accepted-plan SHA-256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.
- Built and retained APK copies both match SHA-256 `0752548eda3397fc658dfdbdd77966e4f466cf84a79ae3a9cc21a8ecb3295c56`, 3,488,272 bytes. ZIP has no native-library entries. Retained SDK outputs record the S-01 certificate, self-targeted test package/runner, three known non-exported services and no INTERNET. No fresh cryptographic SDK verification or installation was performed here. Compilation log and source inventory support the 21 declared native cases (retention 4/audit 12/duplicate 5); all remain unrun on this artifact.

## Strengths

- `data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt:33–36` scopes both queries to the named document. Ranking includes all retained states, while updates affect only AVAILABLE/APPLIED and only `state`. Inclusive `<=` and deterministic revision/ID order prevent promotion when newer entries become EXPIRED. Generated `CatalogDao_Impl.kt:764–780,823–839` binds the intended parameters and returns affected-row count without materializing Undo BLOBs into application objects.
- `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:259–265,411–421` invokes exactly the same policy after pin creation inside the original command transaction. Exceptions escape to Room rollback before command error conversion; no separate best-effort cap transaction can leave a successful over-window edit. Existing Undo routes recheck live availability inside their transactions, fencing a concurrent expiry despite unchanged document revision.
- `data/src/main/kotlin/app/pristinepage/data/repository/RoomUndoRetention.kt:15–40` checks document existence/positive revision, fences the owning context after the clock/read, propagates cancellation and typed failures, sanitizes other exceptions, avoids an expiry UPDATE for zero candidates and checks action counts. Report counts are candidate rows and actual transitions, not physical rows examined by the ranking query.
- Host regressions exercise the repeat-after-state exploit, APPLIED capacity, exact time boundary, foreign-document scoping, cancellation and retained masks/sources. Native fixtures add genuine SQL-trigger rollback and reopen checks. The centralized ten-table/BLOB helper preserves the two old callers' default full comparisons; only explicitly requested availability comparisons ignore UndoEntry.state.

## Issues

**Critical: 0. Important: 0. Minor: 0.** No actionable defect was established in the frozen availability-only increment. No invented counterexample or severity is assigned to explicitly pending integration gates.

## Recommendations and precautions

Retain the exact-artifact 21-case native gate before current-build Room acceptance/commit; historical `cbf39d20...` results cannot satisfy it. Profile long retained histories before claiming responsiveness: `DocumentRows.kt:39` has a document/state/expiry index, not an index covering applied-revision ordering. The top-20 subquery must examine retained document history on each applicable pass; cumulative work grows across edits. The receipt appropriately discloses this, and no constant-time or fixed-latency guarantee was found. Any later index/schema change belongs to the coordinator and its separate verification.

Future UI wording should say “latest 20 recorded reversible edits, for up to 24 hours,” not twenty guaranteed executable actions, physical deletion after 24 hours, or secure erasure. Preserve the distinction between APPLIED history occupancy and currently executable AVAILABLE entries.

## Declined to judge — individually retained gates

- Actual current-artifact Android SQL/trigger execution: prohibited in this review; 21 cases await owned P-04 verification.
- Fresh APK installation/signature verification: retained artifacts/outputs were inspected only; no device or SDK execution permission here.
- Automatic startup/library expiry and legacy over-window AVAILABLE rows: no consumer is supplied; an old token can remain executable until a pass/new Undo-producing edit. Wire policy before advertising a globally enforced window.
- Clock rollback before an entry has ever been marked EXPIRED: Unix-time comparison is the contract, not elapsed-time/lifetime latching; the reviewed policy never reactivates a marked entry.
- Durable scheduler/fairness/canonical repository facade: absent consumers remain separate work, not implied by a public helper.
- Undo pin release: deliberately absent; EXPIRED must not become permission for another component to decrement counters.
- Removed-page/history finalization: deliberately absent; tombstones and dependent histories remain retained.
- Source/blob/mask file reclamation and GC: no file IO/deletion exists in this increment; separate ownership/reclaim proof remains mandatory.
- Confirmed permanent document deletion: requires its own bound confirmation/transaction contract.
- Other owner families and broader relational/schema/content corruption: this state policy is not a comprehensive integrity audit or repair mechanism.
- Historical audit M1 malformed-projection coverage: unchanged deferred coverage, not a newly introduced finding.
- Full journal/WAL/process-death/power-loss recovery: compilation and reopen fixtures do not establish these guarantees.
- Freeze/export isolation/publication/share lifetime: unchanged, separate implementation/acceptance boundaries.
- Real decoder/import/capture T-04 onward: synthetic metadata cannot establish pixel/source ingestion correctness.
- FR-17 batch drafts/UI/Redo/accessibility journey: shared Undo creation is not whole-feature acceptance.
- Neural providers/assets/rights/parity: no implementation or acquisition in this increment.
- Physical-device performance and real-page fidelity: no measurements; emulator evidence would not substitute.
- Complete offline, accessibility, legal/distribution and release gates: not exercised or accepted by this persistence review.

## Assessment

**Bounded verdict: PASS — static availability-policy/code and retained-evidence review only.** The documented recorded-edit ruling is consistent with the actual reversible representation and does not justify a broader retention claim. Ready for the next authorized native verification gate, not accepted native behavior, whole T-03, merge, release or complete-scanner acceptance.
