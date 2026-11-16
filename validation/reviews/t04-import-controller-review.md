# T04-IMPORT-R — independent Android/persistence/input review

## Scope and method

Reviewed the actual uncommitted increment, including all four untracked Kotlin files, against HEAD `9dc3702ed12ff69a4ea89dc1994bfcfe5435d37a`, branch `feat/paperlight-v1`, T04-20261116-A/P-01/U-05. Read the complete mandatory root/spec authorities, accepted implementation plan, capture/import/export, storage, architecture, security and resource specifications, progress/receipt and requesting-code-review/code-reviewer.md. The resource specification is `PERFORMANCE_AND_OFFLINE.md`; `PERFORMANCE.md` does not exist. Read the existing preparation, completion, phases, lookup, SourceStore, session DAO and ports to assess composition, not just the added tests. No replacement plan or nested delegation.

Only this report was written. Inspection used read-only Git, file reads and bounded Python hashing/XML/ZIP/DEX-string analysis. No build, SDK, device, console, network, acquisition, indexing, recall, Git mutation or cleanup was performed. Runtime and test observations below concern retained coordinator evidence, not reviewer-executed Android/JUnit tests.

## Strengths and verified behavior

- `RoomImportSessions.kt:105–145` validates references before writes and performs reservation, every binding and the parent token update inside one metadata transaction. Late failure/cancellation throws out of that transaction; no provider/file operation is introduced. Duplicate references retain distinct ordinal child identities. Existing individual reserve/bind calls retain their checks and cannot replace a prepared selection's bound references.
- `RoomImportSessions.kt:117–157` separates unexecuted legacy adoption from digest-backed replay. Ordered child identities and active references are checked; terminal references must be cleared. Active replay rechecks membership; terminal replay is read-only. Completion/phases preserve the BATCH token while clearing ITEM URIs and retain mixed outcomes. SourceStore still independently verifies receipts/private files, pending state, admission and ownership; selection is not byte acceptance.
- `PhotoImportController.kt:12–40` freezes caller references before its first suspension, validates parent/cardinality/unique/nonparent children before source IO, serially delegates through the existing bound-provider route, preserves all ordered result variants and checks each result's child ID. Cancellation propagates instead of returning a partial success. Empty selection produces a parent-scoped CANCELED rejection after durable cancellation, not an empty success.
- Each reference is bounded to 8192 UTF-16 units and well-formed Unicode/content syntax before UTF-8 encoding; count is capped at 100 before list copying. Digest work is bounded, although it materializes several megabytes at the extreme. Repeated metadata scans are quadratic only within the fixed 100-item/page envelope. New calls use APIs available at API26; this is static compatibility assessment, not API26 runtime evidence.

## Evidence verification

Independently matched all 30 inventoried source/build/script hashes and 49 current JVM XML hashes: 454 recorded cases, zero failures/errors; data361/image37/app18 executed, core23/fixtures15 reused. Retained RED XML contains exactly 10 missing-selection and nine missing-controller assertion failures, zero errors. The normal integration log records 49 seconds/174 tasks; no fresh build was run. Accepted-plan SHA-256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`; the observed diff contains no dependency/build/schema change.

Matched all 13 runtime-manifest evidence hashes and anchor `a80d0f1d09ee40472cea727398b170f6ec0f46a3cd798cefaf9f56700e949f50`. Built/retained APK copies match `19f8a6290a6a440a018710aef1ad231c189afc39edce3841099b4b9cf7aa27df`, 3,631,992 bytes; ZIP integrity passes. Saved signature/manifest records identify the S-01 signer, self-target test package, known runner and no INTERNET. DEX strings contain RoomImportSessions/RoomImportSelectionTest but not PhotoImportController.

The instrumentation transcript has exactly the 26 source-derived named starts and passes, no failures, 5.861 seconds. Four new tests exercise actual Room rollback, selection concurrency/capacity, cancellation/reopen and out-of-order mixed ingestion/replay through real files/codecs with injected provider streams. Saved shutdown evidence identifies the same generation and original player/VM creation identities, both wait0/exit0, stopped rows and released usage. These are saved observations, not a new live allocator/process inspection.

## Findings

Critical: **0**. Important: **0**. Minor: **1**.

### IMPORT-M1 — pin the durable selection encoding independently

**File:** `data/src/test/kotlin/app/pristinepage/data/ImportSelectionTest.kt:35–49`; encoding at `data/src/main/kotlin/app/pristinepage/data/repository/RoomImportSessions.kt:161–167`.

**Verified coverage gap:** current tests assert token shape, same-code replay and changed-selection refusal, but never an independent literal token. Counterexample: changing only the hash domain while retaining the outer `photo-selection-v1:` label would satisfy those assertions and same-build reopen tests, yet reject an existing stored callback identity after update. User impact is lost resumability of previously selected imports. The current implementation matches the documented encoding; this is a nonblocking regression-oracle gap, not a demonstrated current encoding defect.

**Minimal remedy:** add a literal golden and replay fixture using independently constructed stored token/children. Independent Python calculation for the test's `[content://fixture.documents/a, content://fixture.documents/b, content://fixture.documents/a]` produces 135 canonical bytes and `photo-selection-v1:2d0cc153a124542b1d72c5499ae4416cf73ee9962368ea6884cfd84dd19b4c6e`. Include a multibyte reference to pin byte-length rather than character-length semantics.

## Individually considered and declined behaviors

- BATCH.uriText reuse: not a defect; explicitly documented kind-discriminated private metadata, and inspected provider consumers use ITEM rows only.
- Terminal replay without current membership/active-document admission: not a new insertion; IDs are read-only and SourceStore revalidates actual outcomes.
- Expired callback refusal: consistent with the persisted session lifetime/explicit expired-result contract; no fresh batch or wrong-document fallback is invented.
- Continuing later items after Rejected/Pending: required ordered per-item behavior; existing SourceStore retains execution/ownership fences.
- Cancellation after a durable selection or item commit: not transactional rollback of accepted bytes; replay/reconciliation remains necessary and no partial list is returned as success.
- Empty cancellation without source/document IO: safe metadata-only termination of an unselected parent; no page is attached or resurrected.
- Public string adapter: process-local Kotlin API, not an exported Android component or arbitrary loader; actual binding validation remains data-owned.
- Native controller/Android Uri adapter execution: not covered by the library APK; remains the next integration gate, not silently accepted.
- Picker availability/API26 fallback: unimplemented application journey outside this increment; mandatory T04 work remains open.
- Activity Result recreation/session UI/per-item presentation: unimplemented and not established by controller doubles.
- Real provider grants/revocation/stalls: injected streams prove bounded store behavior, not platform grant/lifecycle behavior.
- API26/27 strict decoding, ICC/non-sRGB, native WebP and broader adversarial inputs: existing unfinished decoder gates, not waived.
- Startup/global allocation wiring, scheduler/fairness, GC, broader recovery/crash-WAL/power and physical performance: not established by these tests.
- SOURCE-I1 correction and SOURCE-M1/ROOM-M1/D-M1/older storage findings: distinct historical dispositions, not independently rereviewed here.
- Neural methods, album batch editing, editor/export and release: mandatory future gates, not supplied or accepted by selection/controller plumbing.

## Verdict

**PASS WITH MINOR LIMITATION for this bounded increment: 0 Critical / 0 Important / 1 Minor.** No blocking defect was identified in the reviewed atomic-selection/controller composition. Add the independent durable-format oracle before evolving that representation, and retain native controller/picker integration as unfinished work. This is not whole T03/T04, scanner, release or merge acceptance.
