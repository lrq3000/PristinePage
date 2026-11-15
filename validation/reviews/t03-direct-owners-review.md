# T03-DIRECT-OWNERS-R — independent senior Android persistence/Room review

## Scope and verified evidence

Reviewed2026-11-15: actual UNCOMMITTED increment over HEAD/base `dcde78d5b5b500ddaa11b9e3bd875c641b4e4852`, branch `feat/paperlight-v1`, claim T03-20261115-A. Applied requesting-code-review/code-reviewer, including reasonable-user expectations. Read root/spec AGENTS, complete mandatory PRD/TODO/PIPELINE/incident/operational/storage documents, accepted IMPLEMENTATION_PLAN T-03, architecture/privacy/capture/resource/batch contracts, all assigned files, supporting producers/entities/DDL/transactions/fixtures and generated Room SQL. No replacement plan or delegation.

Independent read-only Git/hash/XML/ZIP inspection confirms five source hashes and all34 suite XML hashes against `validation/raw/storage/direct-owners-review-inventory.json`. RED XML records25cases/7 intended absent-route assertions/0errors; focused GREEN25/0failures/errors. Logs record30s/22tasks RED,42s/22tasks GREEN and successful168tasks/2minutes integration.310 recorded JVM cases have0failures/errors/skips: data263/app9 executed; core23/fixtures15 UP-TO-DATE. Integration contains no compiler warnings; retained lint XML has0errors/3existingwarnings. Eight schema/verification/lock/accepted-plan files match HEAD bytes; plan SHA256 remains791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa. Index has no staged diff; source diff whitespace check is clean.

Retained APK independently matches `cbf39d20c89a7a3a35d284938829ceae7673c1c25871e837ca8530d2b953c803`,3478644bytes, no ZIP native libraries. Adjacent SDK outputs record S01 certificate7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee, self-target app.pristinepage.debug.test/AndroidJUnitRunner, three known non-exported services and no INTERNET. SDK tools were not rerun. Twelve native class cases compile but remain UNRUN. Historical d22d0e03... nine-case transcript and all14 manifest evidence hashes match; generatione8e718c9-7d13-4168-bdaf-5af3b1c5f57a agrees across instrumentation/shutdown/released snapshot, with both original processes signaled/exit0. That is not current direct-owner evidence.

No builds/tests/Gradle/device/network operations occurred. Only this report was written; frozen source hashes were rechecked. Reviewed receipt SHA256:1e7ed15d173de0e721c2298e067554464c61b1b87adc44d4c67c8e4aa3dd4519.

## Strengths

- `data/src/main/kotlin/app/pristinepage/data/db/ReferenceAuditDao.kt:30-32`: actualCount counts matching reference rows through EXISTS, not join multiplicities. Under existing primary-key uniqueness it cannot exceed or compensate for an invalid row in the same three-family storedCount. PAGE/MASK require exact owner/source equality. History seeks candidate(pageId,revision), then checks the complete reconstructed key/source; `01`, `+1`, suffixes and saturated overflow cannot pass merely through CAST. Canonical Long.MAX_VALUE is representable. UUID/revision offsets agree with actual producers.
- The Source primary-key range, SourceReference.sourceId index and owner/composite recipe primary keys support seeks without BLOB materialization or a full-history scan per pin. Work scales with retained pins/lookups, not constant latency. All retained lifecycle states participate.
- `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:15-76`: separate entrypoints preserve earlier semantics. The new route reuses64-row per-page transactions, nullable initial cursor, validated strict progression, cancellation/error handling and128issue cap with full affected-Source counts. Diagnostics use the specified safe state/key/nonretryable integrity action; completed/resumed/quarantined remain0. No owner/counter/file writes exist.
- `data/src/test/kotlin/app/pristinepage/data/ReferenceCountAuditTest.kt:246-324` exercises balanced dangling/wrong-source pins, canonical-history requirements, aliases, pagination/cap and multiple bad pins grouped once. `data/src/androidTest/kotlin/app/pristinepage/data/RoomReferenceAuditIntegrationTest.kt:66-76,166-180,243-289` supplies actual-SQL fixtures, genuine retained pins, canonical Long.MAX_VALUE, repeat/reopen ten-table BLOB comparisons and FK checks. Native execution remains pending.
- No production facade/GC consumer of the new route was found. Method documentation/receipt expressly reject whole-store/reclaim authority. No unsafe composition or acceptance inflation was identified.

## Issues

### Critical — 0 new

None identified.

### Important — 0 new

None identified. Unperformed current native validation is a disclosed acceptance gate, not a demonstrated defect.

### Minor — 0 new; 1 historical retained

Baseline M-1 is unchanged: `data/src/test/kotlin/app/pristinepage/data/ReferenceCountAuditTest.kt:66` truncates projections; computed actual counts are nonnegative. Removing the correct production guards at `data/src/main/kotlin/app/pristinepage/data/repository/RoomReferenceAudit.kt:66-70` would evade existing tests. Consequence has not changed. Eventual minimal remedy: inject65-row/negative-actual projections, including an invalid later row; require sanitized INTEGRITY_FAILED/no report/no mutation. Not a new finding.

## Recommendations

Proceed only to separately authorized current-artifact native validation before bounded runtime acceptance/commit. Additional precaution: eventually exercise the direct SQL itself with>64Source groups, multiple bad pins per Source and QUARANTINED/DELETING Sources; host coverage and shared-reader reuse do not constitute those native cases.

## Declined to judge — individually retained open

- Current native success: execution prohibited; compiled only.
- Fresh signature verification: retained SDK output only.
- Fresh packaged-manifest verification: retained SDK output only.
- Original command flags: not captured by task-output logs.
- Command exit-status replay: no invocation repeated.
- Global snapshot consistency: contract is per-page observations.
- Concurrent-write stress: no runtime measurement.
- Query latency: no runtime measurement.
- Cancellation inside blocking SQLite: boundary checks only.
- Undo-owner validity: different family.
- Undo-pin completeness: different family.
- Import-owner validity: different family.
- Import-pin completeness: different family.
- Job-owner validity: different family.
- Job-pin completeness: different family.
- Export-page-owner validity: different family.
- Export-page-pin completeness: different family.
- Unknown owner families: deliberately excluded.
- MaskReference owner validity: different relation.
- RecipeMask owner validity: different relation.
- Mask roles: not queried here.
- Mask contents: no content inspection.
- Mask dimensions: wider binding validation.
- Mask encoding: no content inspection.
- Recipe byte validity: metadata cardinalities only.
- Recipe hashes: no canonical-content comparison here.
- Recipe schema versions: no execution validation here.
- Page/history/source binding agreement: wider integrity contract.
- Malformed stored owner UUIDs: schema-value integrity.
- Invalid stored revision domains: schema-value integrity.
- Other SQLite storage classes: typed projection is not schema validation.
- Broken FKs: no full relational-integrity scan.
- Orphan references to absent Sources: retained-Source enumeration assumes relational constraints.
- Orphan recipes without Pages: retained-recipe lookup assumes relational constraints.
- Source-file existence: metadata fixtures, no actual images.
- Source-file hashes: no file reads.
- Source-file durability: no file operations.
- Automatic repair: intentionally absent.
- Latest20-edit retention: separate mechanism.
- Expiry: separate mechanism.
- Pruning: separate mechanism.
- Pin release: no release operation.
- Garbage collection: no reclaim authority.
- Confirmed permanent deletion: separate command.
- Canonical repository facade: not wired here.
- Startup invocation: not wired here.
- Scheduling: separate integration.
- Durable fairness: separate lifecycle integration.
- Other recovery domains: three-family audit only.
- Freeze: no snapshot implementation changed.
- Snapshot isolation: no snapshot implementation changed.
- Export execution: outside increment.
- Publication: outside increment.
- Sharing: outside increment.
- Process death: reopen is not death/restart.
- Dirty-WAL durability: unperformed.
- Power-loss durability: unperformed.
- Additional IO timings: unperformed.
- Additional crash timings: unperformed.
- Real provider access: synthetic fixtures.
- Real decoder/T-04 integration: absent here.
- Album UI: consuming feature absent.
- Complete FR-17: consuming journey absent.
- Session Undo: editor scope.
- Session Redo: editor scope.
- Uncertain committed-result reconciliation: separate integration.
- Neural methods: provider/asset gates.
- Physical performance: excluded device scope.
- Visual fidelity: no rendered-page evidence.
- Offline scanner journey: not executed.
- Accessibility: no UI execution.
- Rights clearance: separate acceptance.
- Release clearance: separate acceptance.

## Assessment

**Bounded static review: PASS; ready for the next authorized validation step.**0newCritical/0newImportant/0newMinor; historical M-1 remains. Current native acceptance is pending. No whole-T03, retention/recovery/GC, merge, release or scanner acceptance is granted.
