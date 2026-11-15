# T03-STORE-RECOVERY-R — independent persistence/Room review

## Scope and evidence

Senior Android persistence/Room review, 2026-11-15, T03-20261115-A/P-01/U-05, `feat/paperlight-v1`. Reviewed actual working-tree changes against HEAD/base `2824d89512f6732b4b9fc2f412d590ebdbb57cfc`, including all assigned untracked files, not merely the index. Read root/spec AGENTS, complete mandatory PRD/TODO/PIPELINE/incident/operational/storage contracts, unchanged accepted T-03 plan and relevant architecture/privacy/capture/resource/batch contracts. Applied requesting-code-review/code-reviewer template without delegation.

Read-only Git, source, generated Room SQL, hash, XML and ZIP inspection only; only this report written. Independently matched all nine inventoried source hashes and all36 XML hashes:333 recorded JVM cases, zero failures/errors/skips; data286/app9 executed, core23/fixtures15 UP-TO-DATE. RED XML records37 cases/12 missing-composition-or-canonical assertions/0errors; focused GREEN37/0/0. Logs corroborate34s RED,64s GREEN and130s/168tasks integration, without compiler warnings. Retained lint inventory reports0errors/3existing warnings. Eight schema/verification/lock/accepted-plan files match HEAD byte-for-byte; plan SHA256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Retained APK independently matches `aa013a5275547a998444f343eb38880c24ded57e53e32183320228b03d720ae3`,3502514bytes, no native-library entries. Embedded v2 certificate fingerprint matches S-01 `7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee`; retained SDK signature/manifest outputs show verification, self-target `app.pristinepage.debug.test`, AndroidJUnitRunner, three known non-exported services and no INTERNET. SDK tools were not rerun. Four new native cases and the26-case target are compiled, UNRUN; historical22-case runtime evidence does not cover this APK. No builds/tests/installations/device/network operations performed.

## Strengths and examined behavior

- `data/src/main/kotlin/app/pristinepage/data/repository/RoomStoreRecovery.kt:17-46,76`: companion mutex serializes whole composed passes across instances. Lock order is store→ingest→copy; existing ingestion releases copy admission before provider delegation. Reviewed concrete wiring has no callback into store reconciliation, so no current reentrancy/lock-inversion defect. Exceptions/cancellation unwind `withLock`.
- `RoomStoreRecovery.kt:15,22-38`: Android constructor composes real metadata/audit/expiry against one database and explicit ingestion. Existing ownership/job-generation machinery remains unchanged; no synthetic owner/grant/default-success implementation is introduced. No Room transaction spans component suspension or file/provider work.
- `data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt:13-15` and generated `CatalogDao_Impl.kt:310-345`: bound ID-only query uses the TEXT primary-key range, null initial cursor, exclusive subsequent cursor and all lifecycles. `RoomStoreRecovery.kt:49-63` validates size, canonical IDs and complete strict ordering before any member expiry. Empty IDs cannot disappear behind the initial sentinel. Canonical UUID ordering agrees between Kotlin and SQLite.
- `RoomStoreRecovery.kt:35-45,66-75`: cancellation fences surround listing/components and precede each expiry; existing expiry additionally fences its first write. Late failures preserve earlier independent commits, not a fictitious whole-pass rollback. Typed failures propagate; raw exceptions become safe keys without private text. Checked additions preserve full observation/action/blocked totals; per-component validation rejects negative/impossible counts and oversized/inconsistent diagnostics. The shared128 cap does not cap blocked counts; overlapping observations legitimately remain non-unique.
- `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:31,51-60`: existing four-argument constructor remains generated and exercised by the fixture. Canonical suspending route delegates explicitly; unconfigured recovery throws nonretryable `PROVIDER_UNAVAILABLE/recovery_unavailable`, not a clean report. Comments/receipt accurately limit zero-issue meaning.
- Actual audit/expiry implementations remain in host composition tests; SQL alone is projected. Extracted projection helper preserves prior semantics. New native tests cover real moved-original recovery before audits, retained mismatch/non-state bytes after reopen,71 documents/all lifecycles, and invalid initial document ID. Expiry changes availability only, never history/pins/counters/files.

## Issues

### New findings: Critical0 / Important0 / Minor0

No actionable new defect established in the reviewed increment. This is a static assessment, not proof of all runtime behavior.

### Historical deferred Minor — M-1, not a new finding

`data/src/test/kotlin/app/pristinepage/data/ReferenceCountAuditTest.kt:36-42` and `ReferenceAuditProjectionFixture.kt:31-34` still truncate projections and generate nonnegative actual counts. Counterexample regression: deleting the production oversize/negative-actual guards at `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:66-70` would not fail those malformed-projection cases because none supplies them. Consequence: correct existing fail-closed guards lack direct regression protection. Minimal remedy remains injected65-row/negative-actual projections, including a bad later row, asserting sanitized INTEGRITY_FAILED/no mutation. Store-listing/aggregate tests do not close this historical audit-specific gap.

## Recommendations

Proceed to the exact26-case native gate only after coordinator disposition and fresh authorized ownership/readiness. Preserve its result/build identity separately from this static review. As inexpensive future wiring protection, add distinct-instance serialization and canceled-waiter/exception-release regressions; current overlap test uses one instance, although production mutex is static. Avoid recursive RecoveryCoordinator graphs: nested RoomStoreRecovery or a callback into the canonical facade would reacquire a non-reentrant mutex; no such graph exists in reviewed callers.

## Declined to judge or execute — individually retained gates

- Current native success: execution prohibited; all26 cases remain unrun.
- Cross-instance/waiter runtime scheduling: source inspected, not independently executed.
- Recursive custom coordinator graphs: not present in current wiring; integration precaution above.
- Mismatched databases/custom no-op injection: not present in concrete constructor; caller wiring remains explicit.
- Startup invocation: absent application integration.
- Library expiry-state display: absent UI integration.
- Scheduler lifecycle: separate integration.
- Durable cross-restart fairness: transient ingest cursor remains unchanged.
- Wider database-backed recovery lease: not implemented; existing per-ingest leases are not its substitute.
- Global snapshot under ordinary edits: explicitly not promised; inserts behind cursors await another pass.
- Concurrent hard document deletion: command absent; no current deletion path to assess.
- Other owner families/unknown owners: three source-pin routes are not universal owner validation.
- Full FK/schema-value/storage-class integrity: no comprehensive checker here.
- Recipe/mask/content validity: audit cardinality is not content validation.
- All committed source-file validation: unchanged broader recovery obligation.
- Known/unexpected orphan files: no orphan-reconciliation implementation.
- Source-pin release: intentionally absent.
- Removed-page finalization: intentionally absent.
- Garbage collection: no reclaim authority introduced.
- Confirmed permanent deletion: separate missing command.
- Freeze/snapshot creation: outside composition.
- Export isolation: separate snapshot/export boundary.
- Render/artifact recovery: unimplemented domains.
- Publication/share recovery: unimplemented domains.
- General committed-job recovery: ingest jobs do not cover every job kind.
- Additional cancellation/IO timings: not executed; committed results still require accepted-state reconciliation.
- Whole-ingest process death: selected historical evidence only; new reopen tests are not process death.
- Dirty-WAL/reboot/power-loss durability: no current evidence.
- Ownership proof/root/classloader/marker cleanup: unchanged mechanisms, not newly accepted.
- Historical terminal-retry action guidance: unchanged; no action UI added.
- Future-version preservation: unchanged separate mechanism.
- Real provider/decoder integration: synthetic inputs; T-04 remains open.
- Editor/album UI: not implemented here.
- Complete FR-17/Redo journey: storage composition cannot establish it.
- Neural geometry/appearance: U-01/provider acquisition and acceptance remain separate.
- Physical performance: excluded device scope.
- Visual fidelity: no rendered-page evaluation.
- Complete offline scanner journey: not executed.
- Accessibility: no UI execution.
- Rights clearance: no legal/release acceptance.
- Final artifact/release/scanner acceptance: broader requirements remain open.

## Assessment

**Bounded static review: PASS; ready for the pending native verification gate.** New findings0Critical/0Important/0Minor; historical deferred M-1 remains one separate Minor. Composition and declared limitations are coherent; current-build runtime acceptance remains unestablished. This verdict does not accept whole T-03, merge, release or the scanner.
