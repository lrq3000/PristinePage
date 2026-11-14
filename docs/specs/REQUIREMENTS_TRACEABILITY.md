# Requirement → task → validation coverage

All25 PRD requirements are mapped below. A mapping is not a passed test. Initial implementation evidence is **not run** for every row; update evidence links against actual builds during implementation. V-01–V-07 are independent gates in VALIDATION_PLAN; exact small test vectors are in TEST_FIXTURES and the algorithm documents.

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
| Export→external URI/share | Private complete artifact versus non-atomic external copy; ownership/grant/cleanup | T-11/T-12. |
| Provider→release | Exact rights/hash/source/exported CPU/device/quality statuses | T-08/T-12. |

## Change and completion rules

When a contract changes, identify every boundary/row above it affects and reopen that evidence. A changed source hash invalidates prior parity; a different render dimension may enter another model branch; a new backup/manifest/dependency changes privacy review. Preserve a prior result as historical, not current. An unimplemented optional model is recorded as intentionally deferred; unimplemented mandatory behavior is blocked, not silently moved out of scope.

The documentation package's own requirement coverage and review are in SPEC_PACKAGE_VALIDATION. Application completion requires every mandatory row's actual passing evidence, independent current-build review and operator acceptance of visual intent and explicit capability limits.
