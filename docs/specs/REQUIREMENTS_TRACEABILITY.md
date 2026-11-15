# Requirement → task → validation coverage

All26 live PRD requirements are mapped below, including U-04/FR-17. A mapping is not a passed test. At package creation every row was not run; scoped bootstrap evidence does not pass any complete scanner requirement. V-01–V-07 remain independent; exact small vectors are in TEST_FIXTURES/domain documents. U-01 requires multiple actual neural geometry/appearance methods; original all-neural-deferral wording is superseded. [BATCH_EDITING](BATCH_EDITING.md) supplements existing task acceptance for cumulative album edits without replacing the accepted plan.

| Requirement | Authoritative specification | Owning tasks | Required acceptance evidence |
|---|---|---|---|
| FR-01 | CAPTURE_IMPORT_EXPORT; UX_SPECIFICATION | T-05 | CaptureLifecycleTest, denial/revocation, orientation/mirror, manual shutter without models; V-04,V-06. |
| FR-02 | CAPTURE_IMPORT_EXPORT; PIPELINE_CONTRACTS | T-03,T-04 | DecodeAdmissionTest, EXIF1..8, content/profile/alpha cases, revokedMidCopyDoesNotCommitPage; V-01,V-02,V-04. |
| FR-03 | DATA_MODEL_AND_STORAGE; UX_SPECIFICATION | T-03,T-10 | DocumentOrderingTest, independent duplicate recipes/shared-source lifetimes, Undo/reopen; V-02,V-04. |
| FR-04 | GEOMETRY_ALGORITHMS; UX_SPECIFICATION | T-06,T-09 | GEO-01, invalid quad/labels, manual correction/full-frame reset, detected=false fallback; V-01,V-04. |
| FR-05 | GEOMETRY_ALGORITHMS; MODEL_PROVIDERS | T-06,T-08 | Manual curves, inverse-map/corner-Jacobian/global overlap, crop/mask alignment; learned providers individually gated; V-01,V-03,V-05. |
| FR-06 | PHOTOMETRIC_ALGORITHMS; PIPELINE_CONTRACTS | T-07,T-09 | ALG-01–ALG-06, ALG-10–ALG-11, stage-isolated white/natural/binary metrics; V-01,V-04,V-05. |
| FR-07 | MODEL_PROVIDERS; LICENSES_AND_SOURCES | T-08 | ProviderGateTest, no-asset/no-network, contract/rights/asset checks; V-03 for each shipped model, no fake implemented card. |
| FR-08 | PHOTOMETRIC_ALGORITHMS; PIPELINE_CONTRACTS | T-07 | ALG-07–ALG-09, exact off, one pass/headroom/denoise order; V-01,V-05. |
| FR-09 | UX_SPECIFICATION; PERFORMANCE_AND_OFFLINE | T-09 | Same-geometry cards, synchronized input/result, stale-generation and no-substitute errors, real examples; V-04,V-05,V-06. |
| FR-10 | PIPELINE_CONTRACTS; DATA_MODEL_AND_STORAGE | T-02,T-03,T-09 | RecipeCodecTest, revision CAS, draft/Apply/Undo/Redo and STATE-01; V-01,V-02,V-04. |
| FR-11 | DATA_MODEL_AND_STORAGE; SECURITY_PRIVACY | T-03,T-10 | SourceJournalTest, migration/process-death, rename/reopen/confirmed deletion and shared-source retention; V-02,V-04. |
| FR-12 | CAPTURE_IMPORT_EXPORT; PIPELINE_CONTRACTS | T-11 | STATE-02, exportUsesFrozenOrderAndRecipes, mixed-aspect PDF independent rasterization, PNG/JPEG decode/limits; V-02,V-07. |
| FR-13 | CAPTURE_IMPORT_EXPORT; SECURITY_PRIVACY | T-11,T-12 | FileProvider scope/grants/readback, no private-path exposure, destination revocation/partial copy; V-04,V-07. |
| FR-14 | PIPELINE_CONTRACTS; UX_SPECIFICATION; DATA_MODEL_AND_STORAGE | T-02,T-03,T-08,T-09,T-11 | Failure-injection for each status, exact stage-input DEGRADED result, terminal skips/no recursive fallback, acceptedFallbackReusesPinnedActualRaster with frozen policy/actual dimensions and changed/lost-pin failure; V-01,V-02,V-04,V-07. |
| FR-15 | UX_SPECIFICATION | T-09,T-12 | EditorAccessibilityTest plus physical TalkBack, font scaling,48dp targets/non-gesture controls; V-04,V-06. |
| FR-16 | PERFORMANCE_AND_OFFLINE; SECURITY_PRIVACY | T-01,T-08,T-12 | Fresh-install airplane-mode every shipped path, local examples/assets and missing-provider behavior; V-06,V-07. |
| FR-17 | BATCH_EDITING; UX_SPECIFICATION; DATA_MODEL_AND_STORAGE; PIPELINE_CONTRACTS | T-02,T-03,T-09,T-10,T-12 | A1/B2/C2/D0 changing-selection rotation; Done includes deselected edited pages; subset filters preserve unrelated geometry/settings; Mixed values; atomic revision-CAS save/rollback and one Undo; Cancel, stale previews, duplicate Done, reopen and accessible album journey. BatchRecipeTest/BatchEditSessionTest/BatchEditingJourneyTest; V-01,V-02,V-04. Not implemented. |
| NFR-01 | PHOTOMETRIC_ALGORITHMS; GEOMETRY_ALGORITHMS; VALIDATION_PLAN | T-06,T-07,T-08,T-12 | Per-stroke/color/geometry/failure measurements and real-page review, separate lossy-binary intent; V-05. |
| NFR-02 | PERFORMANCE_AND_OFFLINE; CAPTURE_IMPORT_EXPORT | T-04,T-06,T-07,T-08,T-11,T-12 | Preallocation guards, peak owned/PSS memory, repeated cycles, controlled RESOURCE_LIMIT/no silent resize; V-06. |
| NFR-03 | ARCHITECTURE; UX_SPECIFICATION; PERFORMANCE_AND_OFFLINE | T-05,T-09,T-12 | UI-thread/queue/cancel tracing, rapid-switch stress, cold/warm timing distributions and truthful slow-state labels; V-04,V-06. |
| NFR-04 | SECURITY_PRIVACY | T-01,T-03,T-04,T-11,T-12 | Manifest/SDK/backup/logging audit, scoped URI sharing, offline capture/import/export; V-07. |
| NFR-05 | DATA_MODEL_AND_STORAGE; CAPTURE_IMPORT_EXPORT | T-03,T-10,T-11 | All journal boundaries, duplicate scheduling, migration, interrupted publish/resume and snapshot immutability; V-02,V-07. |
| NFR-06 | PIPELINE_CONTRACTS; TEST_FIXTURES | T-02,T-07,T-08,T-09,T-11 | Canonical recipes/hash, ordered binary64 ALG-12 composer, source/pixel/provider/runtime identities, requested/actual output sizes, accepted execution/raster hashes and preview/detail distinction; V-01,V-03,V-07. |
| NFR-07 | LICENSES_AND_SOURCES; MODEL_PROVIDERS | T-01,T-08,T-12 | Exact artifact notices/permissions, default allowlist, code/weights/data/sample separation; V-07. |
| NFR-08 | VALIDATION_PLAN; IMPLEMENTATION_PLAN | T-01–T-12 | Actual current-build evidence, complete failure and physical-device coverage, independent review; V-01–V-07. |
| NFR-09 | README; PIPELINE_CONTRACTS; SPEC_PACKAGE_VALIDATION | T-01–T-12 | Package standalone/neutral-name/link checks, no proprietary assets or hidden local directory dependency; documentation receipt plus release audit. |

## Cross-file integration boundaries

| Boundary | Contract that must agree | Review/check owner |
|---|---|---|
| Product→tasks→tests | P0 scope, optional-model definition, all FR/NFR IDs, no premature completion | Integration owner; T-12. |
| Decode→geometry | EXIF orientation, source identity, dimensions, pixel centers and normalized controls | T-04/T-06. |
| Detector→still editor | Letterbox/rotation/mirror inverse transform; proposal not accepted geometry | T-05/T-08/T-09. |
| Geometry→appearance | Actual destination raster, aligned float protection, source crop/map provenance | T-06/T-07. |
| Algorithms→renderer/UI | Parameter defaults, candidate precision, exactly one blend/stage, integrated model stages | T-07/T-08/T-09. |
| Editor→persistent data | Draft versus accepted revision, CAS and stable page IDs, source reference lifetime | T-03/T-09/T-10. |
| Library→export | Frozen page order/recipe/provider/output snapshot; no current-state rereads | T-10/T-11. |
| Album batch draft→persistent pages | Stable captured PageIds/revisions, selection-independent edits, final per-page recipes, atomic aggregate Done and one Undo; frozen exports unchanged | T-03/T-09/T-10; FR-17. |
| Export→external URI/share | Private complete artifact versus non-atomic external copy; ownership/grant/cleanup | T-11/T-12. |
| Provider→release | Exact rights/hash/source/exported CPU/device/quality statuses | T-08/T-12. |

## Change and completion rules

When a contract changes, identify every boundary/row above it affects and reopen that evidence. A changed source hash invalidates prior parity; a different render dimension may enter another model branch; a new backup/manifest/dependency changes privacy review. Preserve a prior result as historical, not current. An unimplemented optional model is recorded as intentionally deferred; unimplemented mandatory behavior is blocked, not silently moved out of scope.

The documentation package's own requirement coverage and review are in SPEC_PACKAGE_VALIDATION. Application completion requires every mandatory row's actual passing evidence, independent current-build review and operator acceptance of visual intent and explicit capability limits.

## Current partial bootstrap evidence — T01-20261114-A

Identity amendment I-01/U-03 (attemptT01-20261115-I01) supersedes the legacy executable package identity only; the earlier artifact evidence below remains historical. [Replacement identity validation](../../validation/bootstrap/identity-receipt.md) must establish the new package/class/signer/resource evidence before LDPlayer installation. Repo/cache paths and accepted-plan/source bytes remain unchanged; no complete requirement row is passed by this rename.

Current replacement source commitb3759b2 and [LDPlayer receipt](../../validation/bootstrap/ldplayer-receipt.md) now retain actual absent-package installation, installed-byte/AGP-metadata consistency, explicit launcher/foreground/PID/UI/log checks and verified graceful player/VM shutdown. [Independent runtime review](../../validation/reviews/t01-runtime-review.md) passed bounded scope with Minor transcript/post-shutdown-anchor limitations, retained in the receipt and coordinator disposition. T-01 bootstrap is accepted only within that scope. This adds bootstrap runtime evidence to NFR-08 and capability-free-launch preparation for FR-16/NFR-04; it does not pass complete journeys, native ARM64/API26/current-API coverage, physical performance, airplane-mode or any full FR/NFR row.

[APK receipt](../../validation/bootstrap/apk-receipt.md) identifies the exact development artifact SHA-256387217986018f31b7854abe153efb0febd0c0d2bd1029d7eb4e5e1b341ec7183 and actual9-test/offline-build/lint/signature/resource/ABI checks. [Toolchain receipt](../../validation/bootstrap/toolchain-receipt.md), [code review](../../validation/reviews/t01-code-review.md), [dependency audit](../../validation/reviews/t01-dependency-audit.md), approved [P-02A](../../validation/bootstrap/dependency-scope-amendment.md) and [notice register](../../validation/reviews/t01-notice-register.md) retain boundary-specific findings and permissions. These support only these portions:

- FR-16/NFR-04: bootstrap configuration, merged debug/release network/backup assertions and actual debug packaged resources; actual fresh-install airplane-mode scanner journeys and OEM transfer behavior remain not run.
- NFR-06/NFR-08: exact bootstrap build/artifact/tool identities, observed RED→GREEN and retained independent reports; image/recipe/provider provenance and all algorithm/state/device tests remain unimplemented/unverified.
- NFR-07: acquired583-component register and69 deduplicated embedded legal texts; missing declarations, unknown roles, packaged-notice obligations and all legal/distribution clearance remain open.
- NFR-09: original24 source/root-seed identities and accepted-plan hash reverified, live relative links checked; this is not final standalone app/release acceptance.

No import/capture/edit/save/reopen/export/share journey, deterministic image algorithm, actual learned model or physical-device/visual gate has passed. The development artifact is intentionally labeled a bootstrap with unavailable actions, not a substitute for mandatory functionality.

## Current bounded core evidence — T02-20261115-A

[T-02 receipt](../../validation/contracts/t02-receipt.md) adds owned RGB/typed-mask, numeric/coordinate, strict canonical recipe/default/hash and F-01–F-05 fixture evidence to FR-10/FR-14/FR-17 and NFR-06/NFR-08 preparation. Current47 tests include23 core,15 fixture and9 bootstrap cases;129 named variants repeat complete dataset manifests. Independent Important Unicode-identity/fixture omissions were reproduced and corrected RED→GREEN; original review verdict is retained and remaining Minor coverage is explicit. Four turns restore canonical recipe identity with unchanged source controls/independent duplicate. No complete requirement row passes from types/fixtures alone; storage revisions, actual geometry/appearance/neural execution, album batch UI, export and current-artifact runtime/physical/offline/visual gates remain open.

## Current incomplete storage evidence — T03-20261115-A

[Storage receipt](../../validation/storage/t03-receipt.md) now retains schema/recipe-CAS/atomic batch/Undo/duplicate/reservation/URI binding, file seals/phases/joint completion/replay, ended-attempt readmission/live-requester leases and initial seal-backed ingest reconciliation. Latest212 JVM tests/168-task checks passed with unchanged schema/audit/accepted-plan bytes. [Current native recovery evidence](../../validation/storage/t03-ingest-recovery-runtime-receipt.md) adds five actual Room/private-file same-process cases on exact testAPK31ac53b5f36249122965465cddfe6b92d8680914deb0121ab9ba54074fffb803, followed by verified owned player/VM shutdown/release. Independent Important defects were reproduced and fixed RED→GREEN; original NEEDS FIXES reports remain, without reviewer rerun/PASS. This contributes bounded preparation/evidence to FR-02/03/10/11/14/17 and NFR-04/05/06/08, not completion of any full row. Providers/normalized inspection remain synthetic, DB reopen is not process death, and native cross-process proof/preserved retry attempts/provider resumption/other recovery domains/facade/freeze/export/remaining commands/real decoder and full UI journeys stay open. Recovery M-1 terminal action guidance, prior M-2 future-version labeling and seal retention remain explicit precautions.

Subsequent [positive native ownership increment](../../validation/storage/t03-process-ownership-receipt.md) passed220 JVM tests/168-task checks and one actual separate-process LDPlayer/API28 lock/termination proof case on exact testAPK7b41f283cc3a298bbee083e90f0a3f14ecea3be44f2521c341654b49f56210b0. This adds bounded NFR-05/NFR-08 evidence, including no local requester privilege from a live foreign owner, native ENDED only after actual lock proof, absent fixture/service and verified owned emulator exit/release. Independent monitor coupling was reproduced in real metadata participants and corrected RED→GREEN; original verdict remains retained. No current-build full-ingest/RoomWAL crash, production wiring, reboot/powerloss or complete requirement row is accepted; preserved retries/provider resumption and the other explicitly retained gates remain open.

Latest [preserved retry increment](../../validation/storage/t03-ingest-attempt-receipt.md) adds239 JVM tests/168-task checks using a recorded invocation-only Kotlin compiler workaround and four actual native Room retry/SQLite rollback/sync/reopen cases on exactfc4716e83ef62612a573167eeae7179403a85a2800e6aebe616d503547d467f7. Repeated callbacks follow stable successor identity, old partial bytes remain retained, and a valid sealed owner-free retry commits without provider recopy. Both independent Important findings have retained RED→GREEN coordinator evidence; original NEEDS FIXES remains, no reviewer rerun/PASS. This supplies bounded FR-02/11/14/NFR-05/08 evidence only. Actual provider/decoder, full-ingest process death/WAL, startup resumption, production wiring and all other retained gates remain open; no requirement row or whole task becomes complete from these cases. Exact owned LDPlayer exit/release and fourteen runtime evidence hashes were reverified.
