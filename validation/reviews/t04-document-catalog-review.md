# T04-CATALOG-R — independent catalog persistence review

Original review, 2026-11-16. Reviewer: independent senior Android/persistence reviewer, OpenCode / OpenAI GPT-6.1 Sol (`gpt-6.1-sol`). This original verdict is retained; a coordinator correction or later review must not rewrite it.

## Scope and authority

Reviewed the bounded uncommitted catalog prerequisite, including all new/untracked implementation, test and runner files, against base/current HEAD `265b7886f1d9562ed34faa04b45eff1b9d811503`, branch `feat/paperlight-v1`, worktree `X:\local-workspace\.worktrees\paperlight-v1`. This is the assigned independent persistence-boundary review under P-01/U-05, not a new implementation claim, permission expansion, merge recommendation or full-app acceptance.

Read root/docs AGENTS, complete PRD/TODO/PIPELINE_CONTRACTS/common-mistakes/OPERATIONAL_PLAN, accepted IMPLEMENTATION_PLAN, complete DATA_MODEL_AND_STORAGE/CAPTURE_IMPORT_EXPORT/ARCHITECTURE/PERFORMANCE_AND_OFFLINE/SECURITY_PRIVACY, progress and the catalog/UI receipt. Used the specified `requesting-code-review/code-reviewer.md` template, including reasonable-user consequences and explicit declined judgments. Accepted-plan SHA-256 independently matches `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Read all scoped definitions and their actual consumers: model/repository `DocumentCatalog.kt`, `RoomDocumentCatalog.kt`, full `CatalogDao.kt`, `PrivateImportStore.kt`, host `DocumentCatalogTest.kt`, actual-SQL `test_document_catalog_bounds.py`, native `DocumentCatalogIntegrationTest.kt`, `run_document_catalog.py`, `test_catalog_runner.py`, changed live records and receipt. Followed the existing identifiers/Unicode/title writers, row/schema definitions, MetadataAccess/CatalogQueries/RoomMetadataAccess, ApplicationServices/ApplicationImports, import controller, host fixture, generated `CatalogDao_Impl.kt`, inherited routing/composition/base runners and retained native composition/routing consumers. There is no production UI consumer of the new catalog yet; MainActivity still displays disabled import/camera controls.

Only authorized write: create this report once with the patch tool. All source, tests, docs, index, HEAD, raw evidence and emulator/coordinator records were read-only. No nested delegation, builds, SDK/ADB/console/emulator/UI calls, network, acquisition, signing, installation, cleanup, Git mutation or worktree operation occurred. Python ran with `-B`; SQLite databases and diagnostic changes existed only in memory. No temporary files were created by this review.

## Strengths

- Raw scalar projections precede Kotlin title/row materialization and ordinal narrowing in the same Room transaction. Generated code confirms the nullable document guard preserves missing-row `null`, page counts remain Long, and typed page ordinals are narrowed only after the raw guard. UUID byte/type checks and the small invalid-ID sentinel prevent oversized IDs from reaching Kotlin or disappearing as an apparently empty library.
- Count/range/ordinal gates cover the queried ACTIVE set, and overview construction validates exact document membership, canonical page/source IDs, positive revisions, order and uniqueness. Exported NOT NULL/unique constraints support the SQL three-valued-logic and contiguity reasoning; nullable ordinal is explicitly rejected for active pages.
- Returned library/page lists are owned unmodifiable copies; no mutable entity/BLOB, URI, path or image escapes. PrivateImportStore supplies the reader from the same metadata/database owner, without a new schema, database, dependency or source pin.
- Missing/inactive, queried metadata invalidity, storage exceptions and coroutine cancellation have distinct paths. Errors contain safe keys and opaque IDs, not titles/provider exceptions. This reader does not open providers/files, decode recipes/images, grant ownership, mutate metadata or claim rendering readiness.
- Tests combine guard-before-materialization host ports, actual exported-DDL SQL, and generated Room/Application composition with real normalized private ingestion/reopen. Mixed selection results are not confused with successful page counts or empty/pending documents.

## Findings

### Critical — none

No Critical finding was established within this bounded scope. This is not a statement about unreviewed app/release gates.

### Important

**CATALOG-I1 — keyset output is bounded, but its query scans/sorts the active library on each page.**

- Location: `data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt:15-17`; consuming transaction `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentCatalog.kt:14-21,55-59`; existing adapter `data/src/main/kotlin/app/pristinepage/data/repository/RoomMetadataAccess.kt:9`.
- Evidence status: true for the exact SQL on the actual exported version-1 DDL in host SQLite; exact Android planner/latency was not rerun. `EXPLAIN QUERY PLAN` for both null and non-null cursors selects `index_Document_lifecycle_updatedAt (lifecycle=?)` and reports `USE TEMP B-TREE FOR ORDER BY`. That index does not order by documentId. With `limit=1`, a memory-only progress-handler probe counted 171/1,241/12,041 VM instructions for 10/100/1,000 ACTIVE documents. A separate 1,000-row comparison counted 12,051 instructions for the current query versus 48 when directing the identical query to the existing document-ID primary-key index. These are deterministic work observations, not handset timings.
- Why it matters: there is no whole-library document-count ceiling. A growing receipts/document library incurs work proportional to the remaining active library, rather than the requested page, on every pagination call; traversal can approach quadratic total scan work. This runs inside Room's synchronous transaction, delaying concurrent metadata writers. Cancellation is checked around the transaction, not inside this synchronous SQL scan. Consequently the promised short metadata/keyset prerequisite is not robust as the library grows, even though returned values are small and functionally ordered.
- Remedy: preserve the sentinel/ordering semantics while making the document-ID range index the actual access path, preferably reusing the existing PK rather than introducing a migration. The diagnostic `INDEXED BY sqlite_autoindex_Document_1` demonstrates an existing-schema route, not an approved or built fix; verify Room/KSP support and the actual Android plan before choosing its production form. A coordinator-approved `(lifecycle,documentId)` index is an alternative only if separately admitted as a schema change. Add an exported-DDL query-plan/work-scaling regression with mixed active/inactive rows and initial/continuation cursors, then affected host/native validation. Do not merely increase timeouts, weaken corruption visibility or add OFFSET pagination.

### Minor

**CATALOG-M1 — the new title guard rejects a title accepted by both existing title-writing commands.**

- Location: `data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt:19`, consumed at `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentCatalog.kt:38-46`; write-side comparison: `data/src/main/kotlin/app/pristinepage/data/repository/RoomDocumentRepository.kt:38-44,89-93`, Unicode validation at `core/src/main/kotlin/app/pristinepage/core/pipeline/Contracts.kt:51-59`.
- Evidence status: true SQL behavior, with write admission established by direct code tracing, not a new Kotlin/device execution. `"\u0000Synthetic"` is nonempty, trimmed, well-formed Unicode and ten UTF-16 units, so it passes existing create/rename checks. In memory-only actual-DDL SQL, SQLite reports `length(title)=0`, byte length 10, and `documentReadIsBounded=0` because TEXT length stops at the first NUL. Unlike the oversized-NUL adversary, this title is within both advertised bounds.
- Why it matters: an unusual pasted/programmatic title can be successfully stored but immediately becomes unreadable through load/list as INTEGRITY_FAILED; one such entry also fails its complete library page. This does not lose original bytes, and the trigger is rare, hence Minor rather than a routine-user-path Important finding.
- Remedy: reconcile bounded raw admission with the already accepted title domain, or explicitly centralize/tighten the shared write contract for future inputs while preserving existing data. Retain the UTF-8 byte ceiling and post-materialization full-string validation. Add create/rename-to-list/load tests for leading/interior NUL and Unicode boundary titles; do not silently strip or rewrite stored titles.

## Independently reconciled evidence

All checks in this section were read-only hash/XML/ZIP/DEX/log analysis or explicitly memory-only Python/SQLite tests. No build or device result was reproduced operationally.

- Runtime anchor `validation/raw/decode/picker-adapter-document-catalog-final-manifest.json` hashes to `99421e3bdc71fb16e8505df23f16214cb5339e38cbf2257dea0b53b131d3a4f4`. All 20 evidence records and the base-runner byte counts/SHA-256 entries match actual retained bytes. Artifact inventory SHA-256 is `f2f10d4450910f7b9e2134d40585d5190a2bc038972b353c21f1b23b6f2cd2f8`.
- Rehashed all 214 source/test/build/runner inputs in artifacts.json and all 205 pre-build inputs in `document-catalog-integration-command.json`, including every new catalog implementation/test. All match current bytes and their intersection agrees. Build/schema/locks/configuration are not changed by this diff. Pre-build invocation SHA-256 is `bc8dd36c7bbb6f62d82b6f981955422fec6703a55766643c83f3b1b7b2929b05`; it records approved cwd, full offline/strict argv and scoped environment, without deprecated ANDROID_SDK_HOME.
- Integration log SHA-256 `f39555564287c8619649f2d05c53978d44afd6bdb84122fe562b61cb2fa164e3` records BUILD SUCCESSFUL in 1m 2s, 195 tasks/45 executed. Rehashed/parsed all 56 inventoried JVM suite XMLs: 489 recorded tests, zero failures/errors/skips (core23, fixtures15, data391, image37, app23). These are retained current-input reports, not a claim all 489 tests freshly executed in that incremental build. The retained lint inventory has zero errors and four preexisting warning IDs: OldTargetApi, ChromeOsAbiSupport, MissingApplicationIcon, UseKtx.
- Preserved host RED XML SHA-256 `d744084f70502777000368df043c021253e71623ad67dce621f76defd61c441b` contains ten tests/ten exact missing-reader assertion failures/zero errors. Focused logs record the 36s/22-task RED and 30s/22-task GREEN. Actual-SQL original RED records 22 failures/seven fixture errors; the distinct corrected-fixture RED records 29 missing-query assertion failures/zero errors across eight tests. No fixture error was counted as intended RED.
- Fresh reviewer memory-only execution of the eight `test_document_catalog_bounds.py` cases and one `test_catalog_runner.py` inventory case passed: nine tests in 0.052s. Source inspection established that these tests use only an in-memory SQLite database/read-only source inventory; Python `-B` prevents bytecode-cache writes. The receipt's aggregate 29 host checks and runner-absence RED are coordinator-reported evidence; no separate saved runner-absence RED transcript was found or fabricated. The fresh nine-case run is not a recreation of that historical RED or of the other twenty checks.
- Main APK SHA-256 `2c630abc65548627eb2eda820ce180739299a8a5b8441d7a4620d625fd6cca35`, 17,432,013 bytes; test APK SHA-256 `302a5df8d207e348242fb53742e02aebcef3c64458fd2dfdff48d5dcad2e3960`, 1,150,672 bytes. Built/retained copies, recorded installed hashes and AGP metadata agree. ZIP CRC checks pass; parsed actual DEX class definitions contain the catalog implementation/model in main and DocumentCatalogIntegrationTest in test. Saved SDK-signature reports bind the stated development signer `7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee`; signature/manifest/alignment/badging records are anchored and their commands are saved. SDK tools were not rerun; this is saved-artifact reconciliation, not a new cryptographic signature validation or full artifact audit.
- Independently derived the exact native test inventory from the four source classes, then parsed starts/outcomes from the raw instrumentation transcript. Exactly 27 unique named tests started and passed once each: four catalog and 23 retained picker/composition/routing cases; no failed/error test outcomes. Raw output ends `Time: 6,011`, `OK (27 tests)`, instrumentation completion code -1. Outcomes.json matches the independently parsed counters. Final instrumentation code -1 is not a failed-test status.
- Reconciled all 46 saved command entries against their individual stdout/stderr files, with zero nonzero exit codes/timeouts, and verified the pre-shutdown transcript prefix hash `8b39c37b95100e79e584d7a6ad20c30084240cfc24dd14ff66f8f78ac0d2ca88`. This comparison read saved command data; it did not execute any recorded command.
- Saved generation `c0d29327-f030-47a9-aa34-4dd81af7ff8e` consistently binds player37156/start134393382389320161 and VM14260/start134393382409250405. Saved post-quit observations are both wait0/exit0/not running; all known list rows are stopped, remainingRunning is empty, usageReleased is true and storageDeleted is false. Read-only coordinator JSON confirms the same generation stopped-released/owner null/shutdown verified/completed cleanup attempt, matching released allocator slot and absent allocation lock. No process/console/emulator query was made by this reviewer.

## Recommendations

Resolve CATALOG-I1 before accepting the bounded catalog prerequisite or layering visible library navigation on it. Keep the production correction small and preserve source/metadata semantics; a query-plan regression is a lower-cost first step than changing schema. Resolve or explicitly track CATALOG-M1 with its title-domain decision. Keep this original review and record coordinator reproductions/corrections independently, without relabeling this review as a post-fix pass. Historical routing/storage/decoder minors remain separate and are not closed by the catalog evidence.

## Declined to judge — one behavior per line

- Actual Activity recreation and lifecycle ownership: new tests open/reopen stores, not an Activity; remains an unfinished UI gate.
- OS process death/result delivery: no new process-death experiment was authorized or provided for this catalog slice.
- Queued callback delivery and saved-state parcel round trips (ROUTING-M1): retained routing tests do not establish those missing cases; the old Minor remains open.
- Actual photo-picker UI, API26 fallback UI and provider read/persistable-grant acquisition/release/revocation: Intent/provider doubles and metadata tests are not platform-grant evidence.
- Visible ordered per-item outcomes, Cancel/interruption/retry/reselection behavior: launcher remains disabled and no library/import UI was implemented in this diff.
- Visual presentation, TalkBack/large-font/touch targets and navigation accessibility: no new UI was within the reviewed prerequisite.
- Displayed original-preview bitmap lifetime/cache/admission: catalog returns no image and is not a preview ownership implementation.
- Source-byte integrity for arbitrary library entries: selected native fixture bytes were compared, but metadata discovery is not all-source hashing or file proof.
- Executable current-recipe/schema/provider/render readiness: no recipe BLOB is decoded or capability checked by this reader, deliberately.
- Whole-library integrity, unknown/inactive record auditing and corruption outside the queried active/cursor page: the API is a bounded snapshot reader, not a global audit.
- Full source/history/mask/job/snapshot pin legitimacy and reference-count repair: showing metadata does not acquire ownership or authorize repair/reclamation.
- Arbitrary corrupt import-session semantics and selection-digest correctness: catalog consumes the existing pending predicate without reopening those earlier boundary reviews.
- Abort latency while synchronous SQLite is executing: pre/post cancellation propagation was inspected, but native interruptibility/latency was not measured; CATALOG-I1 separately covers excess query work.
- API26/27 decoder support: this incomplete bridge's older-API gate remains open; API28 evidence cannot substitute.
- ICC/non-sRGB/HDR/color conversion and actual WebP-native coverage: no new codec validation was included in this metadata milestone.
- Physical-device performance, thermal/low-memory/resource envelopes and OEM behavior: forbidden physical-device operations and emulator timings cannot satisfy these gates.
- Full real-codec ingest crash boundaries, dirty WAL/reboot/power-loss recovery and filesystem durability: store close/reopen and selected prior tests do not cover these broader gates.
- Autonomous scheduler/startup fairness, wider leases and all-domain recovery: catalog sharing one Application store does not complete these subsystems.
- Complete create/rename/reorder/rotate/duplicate/remove/Undo library journeys: existing commands and metadata read tests are not the unfinished editor/library UI acceptance.
- Mandatory cumulative album batch editing U-04/FR-17: no draft/selection/atomic-Done feature was implemented or tested here.
- Mandatory multiple genuine neural geometry/appearance methods U-01: no providers/assets/runtime acquisition or neural acceptance was in this slice; requirement remains mandatory.
- Geometry, enhancement, comparison, renderer fidelity and export/preview parity: no rendering algorithm is exercised by metadata discovery.
- Full snapshot freeze/consumption, mask/provider/degraded retention and render-job execution: earlier partial persistence work remains distinct and unfinished.
- JPEG/PNG/PDF export readability/fidelity, writer memory profiles and usable multipage export: no renderer/export validation was supplied for this catalog prerequisite.
- External publication/sharing, recipient access, grant expiry and share-copy retention: prohibited external effects and future export gates are untouched.
- Confirmed deletion, finalization, pin release, GC and retention cleanup: no deletion authority or destructive operation is part of this reader/review.
- Populated released-version migrations and future database downgrade handling: schema is unchanged; this is not migration acceptance.
- Full runtime privacy/security, OS backup/transfer, secure-window and offline cold-start acceptance: safe local read/error structure is not an exhaustive production privacy audit.
- Dependency/model/sample rights, distribution licensing and release signing/publication: no acquisition or legal/release clearance follows from source/APK identities.
- Hostile OS/same-UID/schema replacement and broken exported NOT NULL/unique constraints: analysis assumes the unchanged Room schema; no tamper-proof or arbitrary damaged-schema guarantee is claimed.
- Generic inherited runner partial-launch/timeout/failure cleanup under every environment: current successful saved lifecycle was reconciled, not all operational failure branches replayed or universally certified.
- Full T-03/T-04/T-10/T-12, all-app acceptance and merge/release readiness: this review is limited to the metadata prerequisite and grants none of these decisions.

## Assessment

**Bounded compliance verdict: NEEDS FIXES.** Read/query snapshot, materialization, ownership, error and scope boundaries are substantially implemented, but CATALOG-I1 prevents accepting the intended short keyset metadata transaction as robust for an uncapped library.

**Bounded quality verdict: NEEDS FIXES — 0 Critical, 1 Important, 1 Minor.** Existing green functional evidence is genuine within its recorded limits; it does not cover the verified query-plan scaling defect or the title-domain mismatch.

**Ready to accept this metadata prerequisite: No, pending Important correction and affected evidence. Ready to merge/full-app accept: not assessed and not authorized.** Full T-03/T-04/T-10, actual Activity/picker/grant/visual gates, U-01/U-04 and every broader gate enumerated above remain unfinished.

## Commands and write accounting

Commands run: read-only `git --no-optional-locks status --short --branch --untracked-files=all`, `git --no-optional-locks rev-parse HEAD`, scoped `git --no-optional-locks diff -- ...`; dedicated file reads/glob/grep; inline `python -B -` for in-memory SQLite probes/nine safe tests and read-only hash/XML/JSON/ZIP/DEX/transcript reconciliation. Exact reproducer observations and outcomes are recorded above. No shell command executed a build, SDK, ADB, console or emulator operation; saved argv was treated as data only.

Exact writes: this newly created `validation/reviews/t04-document-catalog-review.md` only, once via patch. No implementations/fixes, other files, raw records, index, HEAD, branch, coordinator state or guest state were written. Report SHA-256 is returned separately to avoid a self-referential file hash.
