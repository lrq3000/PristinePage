# T03-EXPORT-FREEZE-R — independent persistence/export review

## Scope and verified evidence

Senior Android persistence/export review of the actual uncommitted increment, including all ten untracked and four modified source/test files, against HEAD/base `1f58ddbf10dc07f518947c609281361e9d7459b8`, `feat/paperlight-v1`, same T03-20261115-A/P-01/U-05. Read complete mandatory authorities, storage specification, accepted plan/T-03/review focus, architecture, capture/export, privacy, resources, batch contract and reviewer template. No delegation, application execution, SDK/device/network operation or mutation except this report.

Independently matched all14 source and41 suite hashes:381 recorded JVM cases, zero failures/errors/skips; data334/app9 executed, core23/fixtures15 reused. XML corroborates8 missing-identity REDs→8GREEN;10 missing-freeze REDs plus8GREEN→18GREEN;7 specific additional REDs→28GREEN. Three immediately-green characterizations are not RED claims. Retained integration log records132s/168tasks; lint XML has0errors/3existing warnings. Generated DAO SQL preserves bound queries, ABORT inserts and bounded job-pin lookup. Schema/locks/verification/accepted plan have no tracked changes; plan SHA remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

APK independently hashes to `53bbe28fa960a78a0e4c7a394e2e600dbb4f62e3a977719d74deb9f762bc5b98`,3434363bytes; all six new native test methods are present in DEX. ZIP contains no native libraries. Retained SDK output records expected certificate/self-target/runner/private services/no INTERNET; signature verification was not rerun. Six native cases are compiled but UNRUN. Receipt SHA `ec1ac7828e0fe38a0bd9b9266a0e09c02ee71e1c706b66ff89091d44f513a230`; inventory SHA `c60525f1a7c3349dc1bcc6ed0514b8c6532ca7caa55432e70919ef53916dd7a6`.

## Strengths

- `RoomExportSnapshots.kt:35–73,79–118`: outside-Room source proof/admission, final revision/content/source recheck and owning-context pre-first-write cancellation fence precede one atomic snapshot/page/pin/job transaction. Late failure rolls back; no document revision is advanced by freeze.
- `ExportSnapshotIdentity.kt:22–73`: bounded, tagged full ordered identity includes settings and every frozen row field except transient SnapshotId; strict canonical recipe/hash/source binding prevents guessed history. Public BLOBs/lists are independently owned.
- `RoomExportSnapshots.kt:52–71,108–112`: independent snapshot/job source ownership, exact canonical reuse checks and snapshot-specific ordered assembly intent avoid attaching a reusable page job to one snapshot. Backend/precision/encoding are frozen, not merely constructor-local.
- Explicit unsupported-path refusals preserve dependencies. Canonical delegation fails truthfully when unwired; source-file proof is read-only and does not access providers/decode.

## Issues — new Critical0 / Important0 / Minor2

### F-M1 — Pin independent snapshot and assembly byte vectors

`data/src/test/kotlin/app/pristinepage/data/ExportSnapshotIdentityTest.kt:32–42,81–95` and `data/src/test/kotlin/app/pristinepage/data/ExportSnapshotTest.kt:93–101,162–167`: identity tests compare production outputs with other production outputs; assembly assertions count jobs without decoding their payload. Counterexamples: changing the snapshot domain tag/field order, or omitting/reversing assembly job IDs, lacks a distinguishing independent expected-byte assertion. Consequence: persistent v1 identity drift or wrong assembly dependency order can escape these tests. Minimal remedy: independently assembled full snapshot bytes/hash and an assembly payload vector asserting tag, SnapshotId, snapshot hash and ordered job IDs. Static coverage observation; no mutation test executed, no current serialization defect established.

### F-M2 — Exercise cancellation inside the new streaming proof

`data/src/test/kotlin/app/pristinepage/data/ExportSourceProofTest.kt:59–63` cancels before entering `withContext`; it does not reach the read loop. Counterexample: removing the checkpoints from `SourceFiles.kt:176–180` still leaves that test rejecting the already-canceled call. Consequence: a future regression could make up to100 bounded original-file hashes continue after cancellation without this guard detecting it. Minimal remedy: a retained multi-buffer file and a checkpoint-triggered mid-read cancellation assertion, plus no-freeze-writes verification after proof cancellation. Current checkpoints are correctly present; no hard syscall-interruption guarantee is implied.

### Separate historical limitations

ES-M1 settings pre-copy cardinality, ES-M2 independent settings vectors and audit M-1 malformed/oversize/negative-actual projection coverage remain open, unchanged and NOT counted as new findings or closed by this review.

## Considered but declined to accept or execute — individually retained

- Fresh JVM/build/lint runs: explicitly forbidden; retained evidence only.
- Actual six-case Room rollback/reopen success: unrun; host rollback is not native proof.
- Current installation/launch: no install occurred.
- Independent signature rerun: SDK execution prohibited.
- Exact invocation flags/chronological authorship: logs alone do not independently establish them.
- Cancellation after the first write/commit: only the known-canceled pre-write fence is established; uncertain results require readback.
- Malicious same-UID/root replacement: outside cooperative stable-root guarantee.
- File replacement after proof: not database/filesystem atomicity; render must reverify its input.
- Interrupting blocked filesystem syscalls: cooperative checkpoints are not hard deadlines.
- Actual normalized pixel/decode identity: synthetic inspection; T-04 remains open.
- Full corrupt-database projection/bounds defense: not established by typed DAO values or the identity stream cap.
- Whole-process memory ceiling:8MiB caps identity content, not every simultaneously retained copy/native allocation.
- Concurrent caller mutation during construction: no linearizable racing-writer contract.
- Reflection/Unsafe mutation: not an in-process security boundary.
- Cross-database custom participants: injected constructor requires same-database participants; native constructor wires them correctly.
- Released binary ABI compatibility: source overloads remain; this is not an independently released binary library.
- Complete mask proof/pinning: any RecipeMask explicitly refuses before file IO.
- Neural asset/rights/pin preparation: provider recipes explicitly refuse; U-01 remains mandatory.
- Accepted-degraded lease transfer: explicitly refused, not silently omitted.
- Accepted raster loss/corruption/rerun behavior: no accepted entry reaches this path.
- Renderer capability/geometry validity: explicit injected admission, not a production implementation.
- Backend availability/threading/precision execution: frozen declarations are not execution evidence.
- JPEG sampling/quality/readback: no encoder executed.
- PNG pixel round trip: no encoder executed.
- PDF physical placement/margin fit: no writer admission implementation.
- PDF measured profile/minimum two-page envelope: remains mandatory and unverified.
- Terminal FAILED/CANCELED job retry: identity attachment is not readmission; no current render executor consumes it.
- SUCCEEDED job checkpoint/file validity: no completed raster is served here; consumer must verify before reuse.
- Unknown/malformed job-state consumption: no execution contract accepted; consumer must reject unsupported states.
- Equivalent-source job's original file proof: current export source is proved; retained alternate JOB source is only metadata-checked, so execution must independently verify it.
- Scheduler enqueue/dependency execution: durable intents only, no scheduler implemented.
- Enqueue-gap restart recovery: not implemented for these jobs.
- Export process death/WAL/power loss: no current runtime evidence.
- Assembly/render execution: FROZEN/PENDING never means completed output.
- Job/snapshot pin release: absent; independent pins must survive until explicit retention ends.
- Indefinite retention: conservative Long.MAX_VALUE, not an approved deletion/expiry policy.
- All-owner integrity audits: existing audits do not prove all export/job dependencies.
- Garbage collection/finalization: no reclaim operation or authority added.
- Confirmed document deletion: not implemented by this increment.
- Populated migrations: schema unchanged; no migration acceptance inferred.
- Actual page purge after freeze: historical PageId design is sound, but no purge operation tested here.
- App-startup production wiring: absent; unwired canonical freeze rejects explicitly.
- Unsaved-draft/export-review UI: no UI implemented.
- FR-17 batch journey: immutable rows do not establish the UI/storage journey.
- Private finished artifacts: none produced or independently verified.
- External publication/partial-copy recovery: no destination writes implemented.
- Sharing/grants/recipient behavior: no sharing implemented.
- Export metadata stripping: no output artifact evaluated.
- Full offline/backup/accessibility acceptance: no corresponding journey evaluated.
- Real-page fidelity/physical performance: excluded/unperformed, not emulator substitutes.
- Store rights/publication/merge readiness: not authorized or established.

## Bounded verdict

**PASS WITH MINOR LIMITATIONS — static ordinary mask-free FAIL freeze preparation only.** No Critical/Important defect established in this implemented path; disposition F-M1/F-M2 and retain every historical limitation. Native persistence acceptance remains pending the six actual current-build cases. This is not whole T-03, renderer/export-service, neural, merge, release or scanner acceptance.
