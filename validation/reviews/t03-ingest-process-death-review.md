# T03-CRASH-R — independent persistence/runtime-test confinement review

## Scope and evidence

Reviewed the actual uncommitted test increment against HEAD `a2edb14569538e42d251c6d3a13dccae04cb569c`, not merely its description. Read root/spec AGENTS, complete PRD/TODO/PIPELINE/incident/operational/storage specifications, accepted-plan T-03, P-04/I-01, LDPlayer lifecycle references, native-owner protocol, consuming production implementation and saved artifact evidence. No nested delegation, build, test execution, device operation, network access or source modification occurred. Only this report was written.

Read-only verification used Git status/diff/rev-parse and Python file/hash/XML/ZIP inspection. Current source hashes match the saved inventory; retained/build APK bytes match SHA-256 `fb76c091d0ed70a3fe6cf04c3d35db2682bad2919a4bcdea0849fd7f094bf925`, 3,305,797 bytes. ZIP inspection found both new DEX type strings and no native-library entries. Saved SDK manifest/signature outputs identify self-targeted `app.pristinepage.debug.test`, AndroidJUnitRunner, the S-01 certificate and three non-exported services, including two distinct custom private processes. Signature/manifest tools were not independently rerun. The saved build log reports 168 tasks/61 seconds; current 248-case JVM XML hashes match inventory, but those unit tasks were UP-TO-DATE. No six-case runtime evidence exists.

## Strengths and boundary mapping

The manifest addition is androidTest-only. Service inputs are UUID token, canonical ITEM ID and enum; database/root names have fixed prefixes within test-package storage. Provider bytes are generated, not selected documents. HALT/onDestroy target only `myPid()` in the dedicated process. No production/schema/build change was found.

The wrappers preserve these actual production boundaries, subject to I-1:

- OWNED_INTENT: the native owner and real job lease exist before the real COPYING transition.
- PARTIAL: the second provider read pauses after SourceFiles has written the first byte; it does not establish payload sync.
- SEALED: real stage/inspection/seal complete before SEALED metadata.
- MOVED: real move/readback complete before MOVED metadata.
- IN_METADATA: delegated RecipeRevision insertion follows real blob/source/page inserts inside the actual outer Room visibility transaction.
- COMMITTED: real joint journal/session/catalog/job completion returns before the owner scope exits.

Parent pre-HALT reads avoid repository writer transactions; explicit WAL configuration plus PRAGMA checks are meaningful. Foreign native ALIVE does not grant local requester privilege. Post-death exclusive-lock/initialized-marker proof, rather than Binder death or elapsed lease time, gates recovery. Recovery uses actual Room/store/files implementations.

## Issues

### Critical

None established.

### Important

**I-1 — gate expiration can produce a false-positive process-death boundary.** `data/src/androidTest/kotlin/app/pristinepage/data/IngestCrashFixtureService.kt:54–62,35–37`; `data/src/androidTest/kotlin/app/pristinepage/data/IngestProcessDeathIntegrationTest.kt:48–52,66–88`.

READY completes the sole readiness deferred. A later FAILED only attempts to complete that already-completed deferred and is lost. Concrete IN_METADATA schedule: READY arrives promptly; the parent is descheduled beyond 60 seconds; the gate throws, Room rolls back and the structured owner unwinds; FAILED is ignored. The fixture process still retains its process-lifetime native lock. The parent resumes, observes MOVED/no visible rows and foreign native ALIVE, kills the now-idle child, obtains genuine ENDED and successfully recovers. Every existing assertion can pass although death did not interrupt the visibility transaction. SEALED/MOVED/COMMITTED have analogous expiry/unwind windows. The reported transaction flag is only the earlier READY snapshot.

Consequence: unsafe evidence acceptance even without a production change; the receipt's assertion that gate expiry fails the route is not enforced. Minimal remedy: latch failures independently of readiness and add an armed-gate HALT handshake serialized against expiry. A successful halt acknowledgement must guarantee the selected gate cannot unwind before self-termination; expired/unarmed HALT must fail acceptance. Retain a regression for delayed HALT after expiry. Do not interpret a passing current APK as proving these boundaries.

**I-2 — final checks do not establish claimed refcounts/catalog stability.** `data/src/androidTest/kotlin/app/pristinepage/data/IngestProcessDeathIntegrationTest.kt:96–111`.

The test counts SourceReference rows, not persisted Source.refCount or SourceBlob.sourceCount, and never asserts final Source/SourceBlob cardinalities. Concrete counterexample: a completion regression writes refCount=0 while returning a successful update; two reference rows, one page/history entry, valid FKs, receipt replay and every current assertion still pass. Production replay (`data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestCompletion.kt:157–169`) does not validate either stored counter. Additional unreferenced source/blob rows also escape the final checks.

Consequence: a successful run cannot support the requested reference-count/no-extra-source evidence; incorrect counters could later undermine reclamation. Minimal remedy: assert exactly one Source and SourceBlob, stored refCount=actual ownership count=2, sourceCount=actual retained Source count=1, expected reference owner keys, page/current-recipe/ordinal binding and recipe hash. Capture that bounded catalog snapshot after first recovery and compare after second recovery and callback replay. This is a test gap, not a reproduced production defect.

### Minor

None established as an additional defect.

## Runtime/evidence precautions

After corrections, rebuild/reinspect the changed exact APK before a fresh exclusive P-04 generation. Record each named result and verify fixture process/service absence after unbind, including failure/reconnect paths; asynchronous teardown requests alone do not prove absence. Preserve any unresolved cleanup obligation without broad process killing. Database close can checkpoint/change WAL lifecycle: distinguish retained application files from a preserved crash-time DB/WAL/SHM image. WAL mode and uncommitted transaction isolation do not demonstrate that dirty pages actually spilled as uncommitted WAL frames. If runtime exposes a production defect, retain its reproduction before proposing a production fix.

## Considered but declined to judge

- Real PNG validity: four bytes are explicitly synthetic.
- Real provider/grant behavior: no ContentProvider is exercised.
- Normalized decoder identity: injected inspection leaves T-04 open.
- Remaining payload-write interruption positions: outside six selected gates.
- Validation-time death: not one of these gates.
- Payload fsync interruption: not directly injected.
- Seal write/fsync interruption: not directly injected.
- Rename syscall interruption: gate follows completed move.
- Storage-full/short-write/IO failure timing: separate retained/future tests.
- Cancellation timing: not exercised by self-HALT cases.
- Reboot recovery: no boot transition occurs.
- Hardware power loss: process termination is not power loss.
- Physical-device performance: LDPlayer cannot supply that evidence.
- OEM behavior: no representative-device evidence.
- Real-page fidelity: no representative page input.
- Production facade/startup integration: test-only composition.
- Scheduler integration: not exercised here.
- Durable fairness across recreation: explicitly remains open.
- Other file-transaction kinds: ingest-only scope.
- Remaining ownership lifetimes/refcount races: single synthetic ITEM only; I-2 still applies here.
- Snapshot isolation: no snapshot is created.
- Orphan reconciliation: no orphan scan is exercised.
- Remaining document commands: outside this increment.
- Freeze/export/publication: no such operations occur.
- UI and FR-17: no editing journey exists here.
- Neural methods: unrelated unimplemented gate.
- Rights clearance: no release/legal conclusion.
- Prior M1/M2 findings: retained, not implicitly resolved.
- Broader seal validation coverage: unchanged separate gate.
- Aggregate ownership-marker retention: unchanged policy obligation.
- Malicious same-UID aliases/root replacement: outside the cooperative stable-root protocol.
- Independent classloader ownership pools: unsupported protocol scope.
- Universal reconnect/leak freedom: requires runtime observations, not inferred from callbacks.

## Verdict

**NEEDS FIXES — 0 Critical, 2 Important, 0 additional Minor.** Static confinement is appropriately narrow, but the current tests are not ready to substantiate their full claimed runtime evidence. Resolve I-1/I-2 before the authorized six-case run. This is neither merge approval, whole-T-03 acceptance nor scanner acceptance; no independent runtime success is claimed.
