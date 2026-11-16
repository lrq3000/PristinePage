# T04-COMPOSE-R — independent application/persistence review

## Scope and evidence

Reviewed the actual tracked and untracked increment on `feat/paperlight-v1`, BASE/HEAD `6e1d9bea97f296645a47dada69873973527bf7c2`, under T04-20261116-A/P-01/U-05. Read the mandatory authorities, accepted plan, five assigned domain specifications, receipt, reviewer template and P-04 lifecycle/backend references. No nested delegation, build, SDK, ADB, console, device, UI, network, acquisition, recall, Git mutation or cleanup occurred. Only this report was written.

Read-only Git and memory-only Python/hash/XML/ZIP analysis verified the GREEN manifest `cdb4f1c4fcc843337a18f85f6f9157ce64afd5abd8c1e2515c1aff1e6922e930`, its 16 evidence files plus base runner, all 116 input hashes, 52 suite hashes/472 recorded JVM cases, built/retained APK hashes and ZIP integrity, recorded installed identities, transcript prefix and same-generation released records. APKs match main `8a68d0da…`/17,509,870 bytes and test `651ea789…`/1,105,110 bytes. Retained native output contains exactly 14 named starts/passes. RED independently contains 14 starts/8 passes/6 intended missing-composition assertions/0 errors, matching current native-source identity; the corrected alternate-draft runner oracle is not an application fix. Retained shutdown proves both original player/VM identities exited; the current matching record/slot are released and allocation lock absent. These are source/evidence checks, not reviewer-run tests or live runtime inspection.

## Strengths

- Budget selection delegates the existing widened arithmetic; ingestion and committed decode share the actual normalizer and allowance, including retained decoded pixels.
- Data owns Room and the exact private `pristinepage.db`/`filesDir/store` composition. Existing journal, phase, completion, lease, restart, private-byte proof and recovery participants are reused without schema/dependency changes or destructive fallback.
- Mutex publication, lazy manifest Application ownership, explicit unavailable renderer/export and provider-free recovery avoid independent Activity graphs, fabricated readiness and expired-grant reopening on the intended path.
- Tests exercise mixed ordered results, duplicate selections, committed/canceled replay, retained bytes, resource exhaustion, unsealed reselection and concurrent initialization against actual Room/files/codec code.

## Issues

### Critical: 0

### Important: 1

**COMPOSE-I1 — failure cleanup escapes IO confinement.** `app/src/main/kotlin/app/pristinepage/ApplicationServices.kt:44-46` and `data/src/main/kotlin/app/pristinepage/data/repository/PrivateImportStore.kt:53-55`; the latter's `close()` is synchronous at line 27. **True, static:** call `imports()`/`open()` from Main, then fail recovery/opening or cancel at an IO result-dispatch boundary. The catch executes after `withContext(IO)` resumes the caller and invokes Room close inline. Memory-only inspection of cached Room 2.8.5 confirms `RoomDatabase.close → CloseBarrier`, with `onClosed → RoomConnectionManager.close → SupportSQLiteOpenHelper.close`; there is no IO dispatcher handoff in those close methods. Thus storage shutdown can execute on the UI thread precisely during storage faults/cancellation, risking stalls; no ANR duration was measured. Smallest remedy: perform both failure closes in `withContext(NonCancellable + Dispatchers.IO)`, retain the initiating failure, and keep ownership markers/files untouched. Add deterministic canceled-delivery and recovery-failure checks proving background close, no cached graph and successful retry. Current six tests do not exercise these initialization-failure paths.

### Minor: 1

**COMPOSE-M1 — default input captures the original Context.** `app/src/main/kotlin/app/pristinepage/ApplicationServices.kt:25-33`. **True, static:** constructing the public default constructor with an Activity stores a provider lambda that references the constructor parameter, despite the separate application-context field. Retaining that service consequently retains the Activity. The actual manifest path passes an Application, so this is not an observed launcher leak. Smallest remedy: capture only an already-resolved application Context/ContentResolver in a small input factory; retain an Activity-context regression check when this constructor is exercised.

## Considered but declined — coordinator ruling required per item

- Actual picker/fallback UI: explicitly the next integration, not exercised here.
- Activity Result routing/recreation: durable request ownership remains unfinished.
- Platform grant acquisition/release/revocation: injected delivery is not grant evidence.
- Autonomous provider recovery: deliberately excluded without established grants.
- Blocked startup reports: exposed honestly, not falsely converted to clean recovery.
- Future-schema preservation: no destructive fallback exists; actual unknown-schema test remains unperformed.
- API26/27 decoding: earlier explicitly unavailable bridge; not solved by composition.
- ICC/non-sRGB support: earlier independent codec gate remains open.
- Native WebP/adversarial/source-integrity coverage: earlier gates remain open.
- Real-codec OS process death/WAL/power loss: reopen/injected faults are not substitutes.
- Wider orphan/job/snapshot/artifact recovery: implemented-domain report is not global certification.
- Cross-process recovery lease/fairness/scheduler: existing broader obligations are not accepted.
- Process ownership locks surviving DB close: intentional positive-liveness protocol, not a DB leak.
- Caller closing the published application store: outside its documented retained-owner contract; no production teardown currently calls it.
- Manually constructing multiple service roots: intended manifest route creates one; broader misuse prevention is not established.
- Recipe editing/rendering admission: explicitly unavailable, not an implemented editor.
- Snapshot execution/export: absent rather than falsely successful; dependent tasks remain mandatory.
- Low-memory adaptation/PSS/physical performance: accounting tests establish none of these measurements.
- Library/editor/finalization/pin release/GC/deletion: separate unfinished boundaries.
- CANCEL-M1, PICKER-M1, IMPORT-M1, SOURCE-M1, ROOM-M1, D-M1 and older storage minors: retained, not closed or re-reviewed.
- U-01 neural methods: mandatory later, no provider/asset acceptance here.
- U-04 album batch feature: mandatory later, no feature acceptance here.
- Rights/physical/offline/accessibility/release: unperformed independent gates; phone remains absolutely excluded.

## Verdict

**NEEDS FIXES for this bounded composition increment: 0 Critical / 1 Important / 1 Minor.** Correct COMPOSE-I1 and obtain focused evidence before accepting this boundary; address or explicitly disposition COMPOSE-M1. No whole T-03/T-04, scanner, release or merge acceptance is given. Retained GREEN results remain valid observations, not proof of the untested failure paths. Continue eligible authorized work under U-05 without weakening U-01/U-04 or prior gates.
