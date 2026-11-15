# T03-REF-AUDIT-R — independent bounded Room review

## Scope and evidence

Senior Android persistence/Room review, 2026-11-15, of the actual uncommitted increment over `69b1906534ea9a451e7ce3daadd1e53cab84491a`, `feat/paperlight-v1`, same T03-20261115-A claim/worktree. Read root/spec AGENTS, complete mandatory PRD/TODO/PIPELINE/incident/operational/storage documents, accepted T-03 plan, architecture/privacy/resource/capture/batch contracts, all seven assigned files, supporting rows/DAOs/transactions/reference callers/fixtures and generated Room implementations. Applied requesting-code-review/code-reviewer without delegation.

Read-only Git/hash/XML/ZIP and installed SDK artifact inspection only; only this report written. All six inventoried source hashes and all34 XML hashes/counts match:294 recorded cases, data247/app9 executed, core23/fixtures15 UP-TO-DATE; zero failures/errors/skips. RED XML records9 absent-audit assertions/0errors; focused GREEN records9/0/0. Logs corroborate47s/22tasks focused and168tasks/2minutes integration; no compiler warning, lint0errors/3existingwarnings. Schema/locks/verification/accepted-plan have no tracked diff. Retained APK independently matches SHA-256 `451c874e8a2da7c2ec7d8e4a06119bf558603cd14c4b1082e5bc062c7e5c8357`,3460049bytes, no native libraries. Fresh aapt2/apksigner inspection verifies self-target `app.pristinepage.debug.test`, AndroidJUnitRunner, three non-exported services, no INTERNET and S-01 certificate `7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee`. No build/test/device/network operation was performed. The three native cases remain compiled, UNRUN.

## Strengths

The three correlated counts match the defined ownership formulas, including both mask joins and all retained Source states. Exported DDL supplies leading FK/count indexes and TEXT primary-key ordering; generated Room code binds cursor/limit and reads both counters as Long. Each complete projection is inside the same short metadata transaction, without file/provider IO. Validation precedes processing the entire page; strict progress and impossible negative actual counts fail closed. Negative stored counters remain reportable mismatches. Cancellation propagates, raw listing exceptions are sanitized, diagnostics stop at128 while mismatch counts continue, and no repair/lifetime write occurs. Per-page—not global—consistency and delayed observation of concurrent changes are accurately disclosed; equality is not reclamation authority.

## Issues

### Critical — 0

None identified.

### Important — 1

**I-1 — The initial cursor silently excludes a corrupt row instead of failing closed.** `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:21-26`; `data/src/main/kotlin/app/pristinepage/data/db/ReferenceAuditDao.kt:10-17`. Established statically, not runtime-reproduced. Every scan starts at `""` and selects only IDs greater than it. The actual schema permits an empty TEXT primary key: see `data/schemas/app.pristinepage.data.db.PristineDatabase/1.json:74,182,873`; there is no UUID/nonempty CHECK. Counterexample: retain an additional Source with sourceId empty, valid existing Blob FK, unique conversion fingerprint, refCount1 and no SourceReference; set the Blob's sourceCount to its correct retained-row count. The source query omits that mismatched row forever, while the Blob comparison succeeds. All otherwise healthy rows therefore produce blocked0/empty issues, without invoking UUID validation. Empty Blob/Mask IDs have the same omission. Consequence: a genuinely inconsistent counter can receive a false-clean audit; this is not an owner-legitimacy or GC finding. Minimum remedy: distinguish the initial page from a real exclusive cursor, include every row initially, then reject invalid IDs before processing. Retain indexed subsequent pages; no schema migration/repair is necessary. Add host and native regressions for empty IDs in each audited table and unchanged metadata on rejection.

### Minor — 1

**M-1 — Two explicit fail-closed listing guards lack regression coverage.** `data/src/test/kotlin/app/pristinepage/data/ReferenceCountAuditTest.kt:32-45,121-126`; production guards `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:50-54`. The fixture always truncates to the requested limit and computes nonnegative actual counts. Counterexample regression: removing the oversize-page or negative-actual guard leaves all nine existing cases green. Current production checks are correct, but these promised corruption protections are not evidenced by tests. Minimum remedy: injectable malformed projections covering65rows and negative actualCount, preferably including a bad later row; assert typed sanitized INTEGRITY_FAILED/no report/no mutation.

## Recommendations

Resolve I-1 before bounded acceptance; retain focused regressions, identify the rebuilt artifact and execute the three native cases under fresh P-04 ownership. Keep an explicit separate owner/schema/file-integrity gate before any future consumer treats this count report as operational readiness. Host fixtures substitute SQL and rollback; native reopen is not process death. No additional facade or repair mechanism is needed for this increment.

## Declined to judge — each remains open

- Native audit passes: execution prohibited here.
- Polymorphic owner legitimacy: only row counts inspected.
- Missing ownership pins: equality cannot establish completeness.
- Other malformed SQLite column storage classes: broader schema-value integrity, not typed Long projection validation.
- Full foreign-key integrity: no current native execution.
- Source-file integrity: generated metadata has no original bytes.
- Automatic counter repair: intentionally absent.
- Latest20-edit retention: separate implementation.
- Expiry pruning: separate implementation.
- Pin release: no release operation here.
- Garbage collection: no reclaim operation here.
- Confirmed permanent deletion: separate command.
- Canonical repository facade: not integrated here.
- Startup invocation: not integrated here.
- Scheduler integration: not integrated here.
- Durable recovery fairness: separate lifecycle contract.
- Other recovery domains: count-only increment.
- Freeze/snapshot creation: outside increment.
- Export isolation: outside increment.
- External publication/sharing: outside increment.
- Committed-job recovery: outside increment.
- Process death: no audit runtime evidence.
- Additional IO failure timings: not exercised.
- Dirty-WAL recovery: not exercised.
- Power loss: not exercised.
- Concurrent-write stress: only snapshot semantics reviewed.
- Real provider access: synthetic fixtures.
- Real decoder integration: T-04 gate.
- Album UI: absent here.
- Complete FR-17 journey: absent here.
- Session Redo: editor behavior.
- Uncertain committed-result reconciliation: separate command integration.
- Neural methods: separate provider gates.
- Physical performance: excluded device scope.
- Visual fidelity: no rendered pages.
- Fresh-install offline scanner journey: not executed.
- Accessibility: no UI execution.
- Rights clearance: no legal acceptance requested.
- Old terminal-retry behavior: separate mechanism.
- Future-version behavior: separate mechanism.
- Seal handling: separate mechanism.
- Owner-marker retention: separate mechanism.
- Cooperative-root guarantees: separate mechanism.

## Assessment

**Ready to proceed as the verified counter-audit increment: With fixes; NEEDS FIXES.** One Important false-clean enumeration defect and one Minor coverage gap; no Critical finding. Nominal indexed counting/reporting is coherent, but I-1 and current-build native evidence remain acceptance gates. This is not whole-T-03, retention/recovery, merge, release or scanner acceptance.
