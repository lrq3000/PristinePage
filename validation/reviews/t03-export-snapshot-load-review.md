# T03-SNAPSHOT-LOAD-R — independent persistence/export review

## Scope and evidence

Senior persistence/export review, T03-20261115-A/P-01/U-05, branch `feat/paperlight-v1`, actual uncommitted four-file increment against HEAD/base `d5a1ebbbf77fffeef3465e0d7e257cf143ca90d1`. Read complete mandatory authorities/storage domain, accepted plan including T-03/T-11/review focus, architecture/capture/privacy/resource/batch contracts, reviewer template and supporting codecs/ownership/DAO/generated projections. No replacement plan, delegation, application/test execution or mutation except this report.

Independently matched four source hashes and all41 suite hashes:387 recorded JVM cases, zero failures/errors/skips; data340/app9 executed, core23/fixtures15 reused. Retained XML corroborates six missing-load REDs plus28GREEN →34GREEN. Integration log records134s/168tasks; lint XML has0errors/3existing warnings. Schema/verification/catalog/locks/accepted-plan tracked content is unchanged; plan SHA remains791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa.

Retained APK independently matches `02c224ce230ef500e7fa87b1244579aca21f76373db80cbe0a98442e569c711c`,3444283bytes; both new native method names appear in DEX; ZIP has no native libraries. Retained SDK output records expected S-01 certificate/self-target/runner/private services/no INTERNET. Current native cases are compiled, UNRUN; previous32 passes are historical.

## Strengths

`RoomExportSnapshots.kt:29–60` uses one same-database transaction and only frozen snapshot/page queries, with explicit lifecycle/retention refusals, canonical settings comparison, complete typed content/hash verification and sanitized exceptions. No live recipe/document lookup, proof/admission, pin/job/file write or rendering occurs. Existing immutable settings/page/list ownership prevents post-return alias mutation; current renderer configuration is not substituted. Cancellation exceptions propagate separately.

## Findings — Critical0 / Important2 / Minor2

### L-I1 — Bound persisted bytes before materialization

`data/src/main/kotlin/app/pristinepage/data/repository/RoomExportSnapshots.kt:33–35,48–52`, supported by `data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt:10–11`: `SELECT *` materializes settings and every page BLOB before codec/8MiB identity limits. Counterexample:100 retained rows with three524288-byte BLOBs each allocate150MiB of BLOB arrays before inevitable rejection; larger individual fields are also unconstrained. Generated `ExportSnapshotDao_Impl.kt:231–294` retains those arrays. Consequence: corrupted metadata can exhaust memory instead of returning a controlled refusal; LIMIT101 is not a byte bound. Terminal snapshots also fetch pages before refusing. Minimal remedy: same-transaction bounded scalar/type/length preflight or guarded projections, checking per-field and aggregate limits before BLOB extraction. Static allocation-path finding; no OOM reproduced.

### L-I2 — Validate SQLite integers before lossy narrowing

`data/src/main/kotlin/app/pristinepage/data/repository/RoomExportSnapshots.kt:35,51–54`: generated `data/build/generated/ksp/debug/kotlin/app/pristinepage/data/db/ExportSnapshotDao_Impl.kt:237,249–257,271–277` applies `getLong(...).toInt()` before validation/hash. Counterexample: persisted width3 becomes4294967299; projection becomes3 again, so unchanged recipe/settings/stored hash all pass. Likewise recipeSchemaVersion4294967297 aliases1. Schema has no preventing CHECK. Consequence: the new verified loader silently authenticates invalid persisted dimensions/schema instead of detecting corruption. Minimal remedy: validate raw INTEGER types/ranges before narrowing, or use lossless Long read projections and checked conversion; retain native overflow refusals. Static deterministic counterexample, not executed runtime evidence.

### L-M1 — Distinguish future recipe schema from corruption

`data/src/main/kotlin/app/pristinepage/data/repository/RoomExportSnapshots.kt:50–53`, via `ExportSnapshotIdentity.kt:40`: an otherwise loadable record with future recipeSchemaVersion2 becomes INTEGRITY_FAILED, unlike future settings/lifecycle and existing repository recipe reads. Counterexample: a retained newer-schema export opened by this version. Consequence: preserved unsupported history reports integrity failure rather than unsupported version. Minimal remedy: explicit UNSUPPORTED_VERSION classification for unknown frozen recipe schema before current-schema identity validation, retaining malformed-known-content integrity failures. No byte loss or generic future-format support is claimed.

### L-M2 — Exercise the exact-canonical-settings gate

`data/src/test/kotlin/app/pristinepage/data/ExportSnapshotTest.kt:281–287` supplies malformed/trailing bytes; both fail decoding, never the comparison at `RoomExportSnapshots.kt:49`. Counterexample: removing that comparison leaves these tests green; decodable PDF margin−0 bytes normalize to+0 on encoding. Consequence: a future regression could accept noncanonical stored bytes undetected. Minimal remedy: a decodable/noncanonical settings fixture asserting INTEGRITY_FAILED and byte preservation. Current production comparison is present and correct; no mutation test executed.

## Considered but declined — individual executor dispositions required

- Fresh build/JVM/lint runs: prohibited; retained evidence only.
- Current34 native-case success: unrun, not inferred from historical32.
- Signature rerun: SDK execution prohibited; inspected retained output only.
- Installation/launch success: no current runtime operation.
- Exact flags/authorship chronology: logs alone cannot independently prove these.
- Hidden lifecycle fields: metadata value is not execution/status authority.
- Lifecycle/expiry changes after read: consistent read is not a lease.
- Terminal FAILED/CANCELED resumption: explicit refusal, not retry readmission.
- Current source ownership/pin completeness: separate execution/recovery checks.
- Missing/corrupt source files: metadata retrieval intentionally performs no file proof.
- Expiry processing/pin release: no such state change implemented.
- Same-UID/root/Reflection/Unsafe tampering: not an advertised security boundary.
- Racing borrowed-array mutation: no linearizable malicious-writer guarantee.
- Cross-database custom participants: violate documented same-database injection contract.
- Generic future-byte interpretation: preserved rejection, not supported round-trip execution.
- CreatedAt authenticity: timestamps intentionally excluded from content identity.
- Hard interruption of blocked SQL: cooperative cancellation is not syscall cancellation.
- Cancellation during bounded copying/identity work: no hard latency promise; guards exist, injection coverage absent.
- F-M1 independent snapshot/assembly vectors: unchanged, still open.
- F-M2 streaming-proof mid-read cancellation: unchanged, still open.
- ES-M1 pre-copy list cardinality: unchanged, still open.
- ES-M2 independent settings vectors: unchanged, still open.
- Historical auditM1 projection coverage: unchanged; L-I1/I2 concern this new loader.
- Mask retention/freezing: explicitly refused, mandatory unfinished work.
- Neural asset/provider retention: explicitly refused; U-01 remains mandatory.
- Accepted-degraded lease transfer: explicitly refused, not implemented.
- Accepted-raster loss/corruption/reuse: future consumer must verify, never regenerate acceptance.
- Normalized decoder identity: synthetic inspection, T-04 remains open.
- Geometry/provider/rights readiness: frozen declarations are not capability evidence.
- JPEG sampling/quality/readback: no encoder executed.
- PNG raster equality: no encoder executed.
- PDF placement/margins: no writer exercised.
- PDF measured profiles/minimum two-page envelope: unverified mandatory gates.
- Whole-process resource ceiling: identity cap is not allocation admission.
- Job leases/checkpoints/state/readmission: no executor added.
- Scheduler/enqueue-gap recovery: no scheduler added.
- Export process-death/WAL/power-loss recovery: no current runtime evidence.
- Long.MAX_VALUE retention policy: unchanged conservative retention, not deletion authority.
- Garbage collection/finalization: no reclaim operation added.
- Confirmed document deletion: not implemented here.
- Populated migration acceptance: schema unchanged, no migration evaluated.
- Physical live-page purge: native case removes reversibly, not purge.
- Production startup wiring: not established by an injectable service.
- FR-17 album journey: storage metadata isolation is not UI acceptance.
- Completed private artifacts: none produced or verified.
- External publication/partial-copy recovery: no destination writes.
- Sharing/grants/recipient behavior: no sharing performed.
- Export metadata stripping: no output inspected.
- Full offline/backup/accessibility journeys: not evaluated.
- Real-page fidelity/physical performance: unperformed; phone excluded.
- Rights/publication/merge/release/scanner acceptance: outside this review.

## Bounded verdict

**NEEDS FIXES — static metadata-retrieval boundary only.** Resolve L-I1/L-I2 before accepting verified persisted reads; disposition both Minor findings and every declined line. Preserve F-M1/F-M2/ES-M1/M2/auditM1. Current Room evidence remains pending. This is not whole T-03, job execution, completed export, merge or release acceptance.
