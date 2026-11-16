# T04-ROUTING-R — request-scoped photo-result routing

Original independent review, 2026-11-16. Reviewer role: senior Android/persistence reviewer; OpenCode / OpenAI GPT-6.1 Sol. Preserve this original verdict; subsequent coordinator corrections and verification belong in separate disposition records.

## Review identity and permissions

- Workspace: `X:\local-workspace\.worktrees\paperlight-v1`; branch: `feat/paperlight-v1`.
- Requested BASE and observed HEAD: `fc2283ed9fd268bb6a84204efd7c0d36a5380c04`. This review covers the uncommitted prerequisite, including its untracked files, not a BASE..HEAD committed change.
- Accepted `docs/specs/IMPLEMENTATION_PLAN.md` SHA-256: `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`, unchanged.
- Authorization: read-only Git/file inspection and memory-only Python/hash/XML/ZIP analysis; create only this report once. No nested delegation, conversation memory, network, builds, application tests, SDK/Android executables, device/ADB/console/UI access, installation, acquisition, cleanup, Git mutation or other file write was performed.
- This is prerequisite compliance/quality review, not merge, release, full T-03/T-04, physical-device, scanner or product approval. No previous reviewer finding receives an inherited re-pass here.

## Scope and method

Read the complete root and `docs/specs/AGENTS.md`, PRD, TODO, PIPELINE_CONTRACTS, incident/commands register, OPERATIONAL_PLAN, unchanged accepted IMPLEMENTATION_PLAN, CAPTURE_IMPORT_EXPORT, DATA_MODEL_AND_STORAGE, ARCHITECTURE, PERFORMANCE_AND_OFFLINE and SECURITY_PRIVACY. Read the supplied `requesting-code-review/code-reviewer.md` template and applied its requirements, quality, severity and declined-to-judge format.

Inspected all ten initial changed/untracked paths: three new production capture adapters, `PhotoResultRoutingTest.kt`, `run_photo_routing.py`, `test_routing_runner.py`, the routing receipt, and modifications to `run_application_composition.py`, TODO and progress. `git status --untracked-files=all` was essential: the production and native-test additions do not appear in `git diff HEAD`.

Inspected actual consumers/dependencies: PhotoPickerContract/PhotoSelection, PhotoImportController, ApplicationServices/ApplicationImports, disabled MainActivity, PrivateImportStore, import-session ports/DAO/Room implementation, identifier validation, RoomSourceStore, ingest preparation/phases/completion, retained picker/composition tests and inherited runner/safety checks. The only current consumers of the three new adapters are the native tests through reflection; no Activity or production service calls them yet.

Read-only commands used: `git --no-optional-locks status --short --branch --untracked-files=all`, `git --no-optional-locks diff --stat HEAD`, scoped `git --no-optional-locks diff HEAD`, `git --no-optional-locks diff --check HEAD`, `git --no-optional-locks rev-parse HEAD`, and `git --no-optional-locks branch --show-current`. Inline `python -B -` programs read/hash existing files, parse saved JSON/XML/instrumentation transcripts, inspect retained APK ZIP contents/offsets and read shared allocation JSON records; they did not import/execute the operational runners or run application tests. One initial printing-only program ended with a Windows stdout encoding error after its hash checks; a subsequent UTF-8 stdout read recovered the omitted TODO line and report-absence check. It caused no file or application change.

## Strengths

- `PhotoImportRequest.kt:9-34` reuses a canonical opaque ImportSessionId, derives a versioned per-batch registry key and saves only the ID. It does not duplicate document/anchor authority, allocate replacement sessions or place provider references in the app-owned Bundle. Malformed identity errors contain a safe key, not the rejected input.
- `PhotoPickerRegistration.kt:5-10` captures the request object in the callback, rather than consulting navigation. `PhotoImportResultRouter.kt:10-19` introduces no detached coroutine, provider loader, grant acquisition or alternate ingestion engine. Actual selection, capacity, receipts, copy/codec admission and publication fencing remain data/controller responsibilities.
- Rejected over-selection closes the captured reservation using the metadata-only lifecycle command, with no valid-prefix import. Selected results preserve the existing complete-selection reservation and ordinal child identities. The native suite uses actual AndroidX registry dispatch/parsing and actual Room/controller/private-file/codec composition, while clearly substituting external launch/provider delivery.
- Runner generalization changes only the expected case count and missing-class marker. Named Counter comparisons still require exactly the expected starts and terminal outcomes, and the inherited non-optimized-Python refusal, owned-instance guards, paired-APK checks and original-process shutdown proof remain present.

## Bounded requirements assessment

| Behavior | Assessment and evidence |
|---|---|
| Delayed result after another document/request is registered | Supported by immutable closure/key design and saved native `earlierCallbackKeepsItsDocumentAfterANewerRequestIsRegistered`; two request codes, only the original document gains a page. |
| Request/registry restoration plus real DB reopen | Supported for the tested mapping-restoration path; the old request's ID survives and a later document remains untouched. Pending-before-registration coverage is limited by ROUTING-M1 below. |
| Missing/malformed saved identity | Null/missing returns no request; malformed/wrong-type values throw structured SESSION_EXPIRED. No fallback document/session is invented. Six native cases do not exercise every syntactically valid but absent/wrong-kind durable ID; source inspection shows Room rejects these before provider IO. |
| Fresh picker cancellation | Existing empty-selection controller path closes a fresh unselected batch and returns parent-scoped CANCELED without provider IO. The native test checks its result and absence of pages/provider opens, but not the durable row/new-batch postcondition. |
| Cancellation of an expired unselected request | **Fails static review: ROUTING-I1.** The router uses a selection-preparation operation whose expiry precondition prevents abandonment. |
| Malformed/over-limit picker rejection | Router invokes cancelBatch before returning a structured parent-scoped error; the native over-selection case additionally proves subsequent selection cannot bind and no provider/prefix page is imported. |
| Late selection for an explicitly canceled old request | Tested refusal remains bound to the old batch, reads no provider and leaves the newer batch usable. No rerouting was found. |
| Replay, errors and cancellation | Selection digest/child IDs and existing SourceStore replay prevent rebinding/duplicate publication; new adapters do not catch coroutine cancellation or convert structured failure into success. An already-selected batch is not silently replaced by an empty/different selection. Full router replay/race coverage is not claimed from these six tests. |
| Resource/privacy boundaries | New app-owned state is one bounded UUID plus key; no bitmap/URI/file owner or grant is retained by these adapters. Selected IO remains on existing suspend/IO/admitted paths. Rejection/cancellation metadata paths do not call a provider. AndroidX's separate pending-result state and actual grant/lifecycle privacy still require integration validation. |
| Schema, dependencies, identity and launcher | No changed schema/build/dependency/namespace path was observed. The three production additions use `app.pristinepage`; MainActivity import/capture buttons remain disabled at lines 54-59. |

## Independently reconciled saved evidence

These are independently inspected records of coordinator execution, not builds, device tests or host unit tests executed by this reviewer.

1. RED manifest SHA-256 is exactly `050165204889325afc32e624f6294092cb8e8bf74bbf7d792c83b8bad270383a`. Its 19 evidence entries plus base runner match their recorded byte lengths/hashes. All 138 recorded input hashes match current corresponding bytes; the subsequently added three production files are intentionally absent from that RED inventory. GREEN manifest SHA-256 is exactly `ee3d8ba64d1198f69f9fe00112c8d53232c5168cb246759c572fa733cee02ba0`; all 20 evidence entries plus base runner and all 141 recorded inputs match. No reviewed-source/runtime-anchor drift was found.
2. Saved RED transcript has exactly 22 expected named starts, 16 passes, six failures carrying `Request-scoped photo-result routing is absent`, and no error outcomes. GREEN has the same 22 names, 22 passes, zero failure/error outcomes and `OK (22 tests)`, with reported time `4,115` seconds in locale notation, i.e. 4.115 seconds. The saved outcomes JSON agrees with an independent Counter reconstruction. All six routing test names were reconciled against the actual source, not inferred from a count or parent description.
3. `photo-result-routing-red-build.log` records success in 36 seconds, 113 tasks, four executed; its SHA-256 is `139d0560d6dccdd084a70dc70f7fa322aac57dac176b76e163733136dab08852`. `photo-result-routing-green-build.log` records success in 68 seconds, 195 tasks, 16 executed; SHA-256 `ab7a220a1580965182b5ecbde634e0b86c7e907b6ab5911ce054ba823c85f380`. No compiler warnings were found in that GREEN log. Most prerequisite tasks, including core/data/image JVM tests, were UP-TO-DATE; the app JVM task executed. This is not 478 freshly executed tests in that invocation.
4. The GREEN inventory's 54 XML hashes match existing reports, each test count matches its actual testcase elements, with zero failures/errors/skips: core 23, test-fixtures 15, data 381, image-android 37, app 22; total 478 recorded JVM tests. Current lint XML agrees with zero errors and four existing Warning IDs: OldTargetApi, ChromeOsAbiSupport, MissingApplicationIcon and UseKtx. The four runner/16 host SQLite passes are coordinator-reported checks; I inspected the runner test sources but did not execute those 20 checks or claim an independent host-test rerun.
5. Retained GREEN main APK hashes to `a2ab0a0335e4054df89a49508fc2b6613d10956b0d64cecb2c4a011217351ec1`, 17,511,862 bytes; retained test hashes to `b708289bf41fd009b38777e295d249300b604b246f1fc67d568cd47a8b118929`, 1,130,883 bytes. Both match their current built counterparts and AGP output metadata. RED main is the prior composition `6ec65b337f3e68c7d538663cf51c7abd8536feb51b548f329f45e3d5945c6ab9`; RED and GREEN test APK bytes are identical. The three adapter DEX descriptor strings are absent from retained RED main and present in GREEN main; the routing-test descriptor is in both test APKs.
6. Saved SDK signature outputs report successful v2 verification and signer `7ef552d3eab5d93fac15b3d2dd7233f1b50a170d2baf68460b12f6085f8dadee`. Saved manifest/badging/package outputs agree on main/test IDs, API26 minimum, main versionCode1 and test target `app.pristinepage.debug`. The test manifest omits versionCode; aapt's empty value, AGP metadata0 and installed package-manager default0 are consistent, not a release-version assumption. I did not execute SDK verification. Independently inspected ZIP CRCs are clean for both pairs; all stored entries have four-byte-aligned data offsets, and the three stored main ARM64 .so entries are 16KiB aligned. Empty saved alignment stdout alone was not treated as proof of command success or ELF/runtime compatibility.
7. All 63 RED and 43 GREEN saved command entries have exit0, their corresponding stdout/stderr files agree, and each contains one exact named instrumentation invocation whose output matches the saved instrumentation text. Installed hash records match the retained pairs: RED installs the new test only; GREEN installs the main only and reuses unchanged test bytes. Both saved pre-shutdown transcript-prefix length/hash anchors reconcile with the corresponding completed transcripts.
8. GREEN generation `787d96a0-c57f-4085-a893-80cb6c70f461` has saved original player14196/start134393175343288542 and VM10132/start134393175361620467, both wait0/exit0, stopped rows, no remaining-running row and storageDeleted=false. Read-only shared JSON inspection agrees on the same generation, owner-null/stopped-released/shutdown-verified, completed matching cleanup attempt, zero reservation and released allocation slot; allocation lock is absent. RED generation `39360183-28da-4503-b000-5561e8a9333f` retains equivalent saved shutdown proof for player13292 and VM27516. This is saved/coordination evidence, not a new live-process or console query.

Input-proof limits: the 141-item map binds all three new production adapters, the six-case native source and changed/new runners, but is not a complete hermetic build-input manifest: it omits some unchanged resources/manifests, fixture implementation and most existing JVM-test sources. Plain Gradle output also does not retain invoking argv/environment, so the receipt's exact offline/strict invocation flags are coordinator-reported rather than independently reconstructed. The guarded duplicate preparation/runtime refusals are receipt statements, not separately authenticated new command executions here. No product-level or full reproducibility claim is inferred from these limits.

### Frozen focal-file hashes

| Path | SHA-256 |
|---|---|
| `app/src/main/kotlin/app/pristinepage/capture/PhotoImportRequest.kt` | `a42a3c4e202d85f2c9726016e491062a3acd6a461a1c9b6dbd72413630fe0540` |
| `app/src/main/kotlin/app/pristinepage/capture/PhotoPickerRegistration.kt` | `67926e9f89def55a7c14a980bd76221d557f8233d2578453bef51eeb0e2c6f28` |
| `app/src/main/kotlin/app/pristinepage/capture/PhotoImportResultRouter.kt` | `2bd9e6d9df59a1d7ccdf34301cfff979bd4ff455d5089b6a06c9e13a50b22d16` |
| `app/src/androidTest/kotlin/app/pristinepage/PhotoResultRoutingTest.kt` | `c139c51300bbe642068ffff9809c3642cfe81241625d52a876381013ce62223c` |
| `validation/decode/run_application_composition.py` | `8fc1db9983623f6f36aba05c0d2be93183aa1d4cc594b7d5745690e06b9ebea5` |
| `validation/decode/run_photo_routing.py` | `09627e41e8e3f7df97fcf187f71022c93df5b98a79f3f53ae921dc11cc526730` |
| `validation/decode/test_routing_runner.py` | `706a0695ef059e410deac55e8af72308b61ba91b0cbdcb00d80b8de70776691e` |
| `validation/decode/t04-photo-result-routing-receipt.md` | `aa5f93cdeb2f2fe0e1dae56a9856a64c0d1386a90a6bcebd9ffb7544cbc4cc59` |

## Issues

### Critical — none found in this bounded review

No wrong-document attachment, provider read on the inspected canceled/rejected route, partial-prefix publication or source-destructive behavior was found. This statement does not certify unimplemented product paths.

### Important — ROUTING-I1: picker cancellation cannot abandon an expired reservation

**Evidence status: true by inspected control flow; implementation/composition defect, not an executed test failure.** Primary location: `app/src/main/kotlin/app/pristinepage/capture/PhotoImportResultRouter.kt:14`. Dependencies: `PhotoImportController.kt:23`, `data/src/main/kotlin/app/pristinepage/data/repository/RoomImportSessions.kt:115,132-137`, and `data/src/main/kotlin/app/pristinepage/data/db/ImportSessionDao.kt:13`.

The Canceled branch delegates to `imports.import(emptyList(), request.session)`. That reaches `prepareSelection`, which rejects `parent.expiresAt <= clock()` at line115 before the empty-selection cancellation write. A normally created batch expires after 24 hours (`RoomImportSessions.kt:37-40`). Therefore a valid originally persisted WAITING_SELECTION request left in a picker overnight, then canceled, throws SESSION_EXPIRED and remains WAITING_SELECTION; no cancellation row is written. The DAO still considers that expired row active, and subsequent `beginImport` rejects `finish_import_before_new_batch` at `RoomImportSessions.kt:34`. An independent explicit abandonment is needed to unblock the document. Wrong-document/source safety is preserved, but the requested cancellation-close guarantee and ordinary retry usability are not.

The existing lifecycle command intentionally has no expiry/current-membership admission requirement (`RoomImportSessions.kt:149-175`, especially line168; supporting contract DATA_MODEL_AND_STORAGE:76). It already preserves committed children and closes the exact reservation. The router selects this correct abandonment operation for Rejected, but not Canceled. `PhotoResultRoutingTest.kt:100-113` creates only a fresh batch and checks result/page/provider counts, so its GREEN result cannot detect the trapped reservation.

Recommended minimal correction: route picker cancellation through the existing metadata-only abandonment authority, returning a structured parent-scoped CANCELED result after successful closure, without requiring fresh-selection eligibility or inventing a new batch. Do not follow cancelBatch with the old empty-selection call, which can itself reject the now-canceled no-token parent. Preserve coroutine cancellation propagation, terminal receipts and all other batches. The coordinator should choose the exact compatible API adjustment; no second journal/schema/dependency is needed.

Required regression evidence before accepting this prerequisite: expire only a fixture's unselected pending batch, deliver its canceled result, assert its durable CANCELED state, zero children/new pages/provider opens, successful new beginImport into the same document, and no change to another pending request. Retain fresh cancellation/rejection/late-result cases. No test/build was relaunched by this reviewer to reproduce this direct static case.

### Minor — ROUTING-M1: recreated-registry test omits queued delivery during registration

**Evidence status: true test-coverage limitation; no demonstrated production defect.** Locations: `app/src/androidTest/kotlin/app/pristinepage/PhotoResultRoutingTest.kt:69-85` and `app/src/main/kotlin/app/pristinepage/capture/PhotoPickerRegistration.kt:7`.

The restoration test saves an in-flight mapping, restores the registry, registers the callback and only then dispatches the result. It does not cover a raw result arriving while the callback is unregistered and being delivered synchronously by `registry.register`, an important second AndroidX path for a request-specific adapter. Nor does it parcel a registry Bundle containing a pending raw result. The existing test accurately proves its narrower mapping-restoration path; it must not be described as pending-result/actual-Activity/process-death coverage.

Add an adapter-level case dispatching the old request's result before re-registration, then register with the restored immutable request and verify exactly one delivery to that batch, no new launch/provider re-read or newer-document attachment. A pending-state save/restore variant is a useful additional precaution. This can use the existing RecordingRegistry/Room fixture without opening external picker UI; actual Activity/OS-death evidence remains a separate gate.

## Recommendations

1. Correct ROUTING-I1 and retain RED-to-GREEN regression evidence before bounded prerequisite acceptance; record coordinator dispositions separately without replacing this original report or implying a reviewer rerun.
2. Address or explicitly retain ROUTING-M1. When integrating the Activity, enforce one active registration owner per batch, registration/restoration ordering, main-thread ownership, in-flight/duplicate-launch policy and structured callback consumption rather than assuming this AutoCloseable helper supplies lifecycle management.
3. Keep the disabled launcher until visible error/cancel/recovery and grant lifetimes are tested. In particular, do not broaden immediate URI consumption into automatic provider retry after recreation using temporary grants, and inspect AndroidX pending-result/log handling as well as the app-owned ID-only Bundle.
4. Consolidate the receipt's acknowledged duplicate GREEN paragraphs without changing historical identities/results; preserve all previous task gates and findings. Do not rerun an unchanged emulator suite merely to re-prove an obvious source-level issue or obtain a cleaner narrative.

## Declined to judge

Each item below was considered but is not approved by this report; these are explicit boundary limits, not silent waivers or additional authorizations.

- Actual external photo-picker launch/selection/cancellation UI: onLaunch is deliberately replaced by RecordingRegistry; no OS picker was opened.
- Actual API26 fallback UI and no-Play-services device journey: current native evidence is API28 adapter behavior, not the required older-platform journey.
- Activity onCreate/onStart/onStop/onDestroy registration timing and automatic unregistration: there is no production Activity consumer yet.
- Real Activity recreation and framework SavedStateRegistry ordering: the test manually recreates registry/DB objects, not an Activity.
- Real process death, force-stop/reboot and parcel/unparcel of pending registry state: the routing tests do not terminate a process or exercise those framework boundaries.
- UI recovery when a saved request ID is absent or a syntactically valid batch no longer exists: adapter/storage refusal was inspected, but the actionable expired-result screen does not exist.
- Visible ordered mixed per-item results/progress/retry behavior: controller composition is retained, but the launcher/UI remains unwired.
- Visible Cancel during active copying: picker cancellation and metadata lifecycle are distinct from the forthcoming user-facing active-work cancellation control.
- Guaranteed interruption of stalled provider reads: existing code explicitly cannot promise every provider read is interruptible; the new adapters add no interruption mechanism.
- Native temporary/persistable READ-grant acquisition, revocation and release: provider delivery is injected; offered flags are not grant evidence.
- Provider grant lifetime after backgrounding/recreation and cloud-backed availability: no native provider/grant or network/offline journey occurred.
- Activity launch failure, repeated launch, duplicate same-batch registration and callback-owner teardown policy: the new wrapper delegates these operational lifecycle choices to its future Activity owner; no consumer policy exists to validate yet.
- Full concurrent result/cancel/process-recovery race matrix: source fences were inspected but no new cross-process/race coverage was executed or inferred from the six tests.
- All remaining-capacity rejection/reselection UI decisions: whole-selection preparation refuses before IO, but the user-facing retry/cancel choice is forthcoming.
- Camera permission, shutter/orientation/camera session behavior: T-05 is outside this photo-result prerequisite.
- API26/27 decoder support, ICC/non-sRGB/HDR/WebP-native and wider hostile-codec acceptance: prior decoder gates remain separately open and were not re-reviewed to a new PASS.
- Broad persistence recovery, WAL/power loss, fairness and complete owner/pin integrity: retaining the existing store/controller is not full T-03 acceptance.
- Source reclamation, staging cleanup, confirmed deletion and grant-finalization implementation: this increment performs no deletion/finalization and supplies no new reclamation authority.
- Export/render/model/mask/degraded-raster and sharing/publication behavior: these interfaces are not exercised or implemented by the routing adapters.
- Mandatory U-01 neural and U-04 album batch-editing completion: neither requirement is implemented or waived by this prerequisite.
- Physical-device memory/performance/thermal/codec/fidelity acceptance: emulator timings and tiny synthetic fixtures cannot establish these properties.
- Human visual quality, accessibility/TalkBack and large-font approval: no enabled import UI or corresponding user journey exists in this scope.
- Full production manifest/backup-transfer/security/rights/release audit: saved debug manifests/signatures and unchanged configuration are only bounded evidence.
- Hermetic build reproduction, independent APK-signature cryptography and independent execution of the reported 20 host checks: this reviewer only performed the permitted read/hash/XML/ZIP analyses and inspected retained verifier outputs.
- Earlier reviews, their original failed verdicts, coordinator corrections and deferred minors: these remain separate records; this report does not re-pass them.
- Merge, push, distribution, store title/rights clearance or release readiness: none is an authorized decision for this review seat.

## Assessment

**Bounded specification compliance: NEEDS FIXES.** The identity/key/routing design meets the narrow delayed-result destination requirement, and the saved evidence reconciles, but expired picker cancellation does not close its reservation as required.

**Bounded code/evidence quality: NEEDS FIXES — 0 Critical, 1 Important, 1 Minor.** ROUTING-I1 is a concrete cancellation composition error; ROUTING-M1 is a narrower coverage limitation, not proof of a broken restoration implementation.

**Ready to merge? Not assessed or authorized. Ready for bounded prerequisite acceptance? No, pending ROUTING-I1 correction and actual regression verification.** No product-complete, full T-03/T-04, Activity/OS-death/grant/UI or release approval is given. The only review write is this report; sources, tests, runners, raw evidence, shared records, index, HEAD and branch remain read-only.
