# Design decisions, alternatives and unresolved gates

Version1.0,2026-11-14. These are product/engineering decisions, not evidence that an implementation exists. The operator selected a complete offline scanner scope and approved documentation production; implementation, signing and publication remain separate authorization.

## Accepted design decisions

| ID | Decision / reason | Alternative and consequence |
|---|---|---|
| D-01 | Native Kotlin/Compose and platform camera/picker/storage mechanisms; fits Android lifecycle and avoids a new UI/runtime layer | A cross-platform stack could be viable but adds bridging/runtime cost without a stated second-platform requirement. |
| D-02 | Deterministic classical core ships without models; providers are additive and gated | Neural-first could improve some cases but makes basic function depend on rights, conversion, memory and device results not yet established. |
| D-03 | Separate White paper and Natural lightness intents | One neutral-white preset cannot infer both authentic tinted substrate and colored illumination from an uncalibrated photograph. |
| D-04 | Geometry-first canonical product pipeline with source-preserving inverse maps | Other stage orders are separate recipes requiring comparative quality evidence; no universal recovered ordering is claimed. |
| D-05 | Manual perspective and ruled-surface curve are required fallbacks | Learned geometry alone is brittle when assets/maps fail and cannot reconstruct hidden content. |
| D-06 | One candidate-after-clipping strength/protection blend per appearance adapter | Gain-space attenuation before clipping differs; pixel200,gain1.5,halfstrength must produce228 under this contract. |
| D-07 | Explicit OutputSpec dimensions and purpose, no overloaded universal cap | Reduced-input preview and final downsample have different model/context semantics. |
| D-08 | Immutable originals, typed recipes, revision checks, frozen export snapshots and journaled file/DB transitions | Destructive edits or rereading mutable current state can corrupt recovery or export the wrong version. |
| D-09 | Airplane-mode local operation; no accounts/cloud/OCR in version1 | Optional later features require a distinct privacy/rights/performance PRD. |
| D-10 | Five coarse modules; no generic workflow engine | Extra abstraction/modules increase coordination and build cost without helping the selected small algorithm set. |
| D-11 | Default API26/arm64 and recorded stable-toolchain bootstrap | Older API/other ABI support is an explicit compatibility expansion, not silently assumed. |
| D-12 | Rights and performance are per asset/adapter/build, not inferred from paper labels | A code MIT notice does not automatically identify external weights; desktop timings do not establish handset readiness. |

## External/implementation gates

| Risk ID | Risk, status and effect | Owner and discriminating action |
|---|---|---|
| R-01 | Paper/background estimators can erase broad colors, faint marks or dark graphics; unresolved empirical fit | T-07/T-12: stage-isolated fixtures, real pages, protection/strength and retained failure cases. |
| R-02 | Hard shadow/glare/occlusion cannot always be corrected faithfully; no unconditional restoration claim | T-06/T-07: detect/warn unsupported cases, retain source, recommend recapture when content was not recorded. |
| R-03 | Model code/weight/package differences; unresolved assets and conversion | T-08: pin manifest/hash, source/exportCPU/device parity and rights approval before enabling. |
| R-04 | Geometry can fold, overlap, crop or duplicate content despite smooth maps | T-06/T-08: per-cell corner Jacobians, boundary/global overlap checks and content/line coverage. |
| R-05 | Large photos, high-channel activations and PDF assembly can exceed memory | T-04/T-08/T-11: measured admission, row/coarse-map processing, export-specific bounds and controlled errors. |
| R-06 | OS/provider kills, deferred workers, revoked URI access and disk full | T-03/T-04/T-11: journal/reconcile/idempotence, durable originals and truthful interrupted state. |
| R-07 | Copyleft/research-restricted/unresolved source/weights may not fit chosen distribution | T-01/T-08/T-12: allowlist and independent review; never silently change the application license. |
| R-08 | Preview may look better than full export or hide faint-detail/halo damage | T-09/T-12: true target-resolution detail view and identical recipe/provider identities. |
| R-09 | A stale worker can publish wrong-page/revision output | T-02/T-03/T-09/T-11: complete immutable request identities and publication CAS tests. |
| R-10 | Telemetry/backup/transitive SDKs can violate local-only intent | T-12: merged-manifest/dependency/backup audit and airplane-mode cold-start tests. |

## Decisions intentionally left to implementation/release

Select and record the exact compatible stable toolchain/dependency versions at T-01; choose physical target devices and derive final quality/speed thresholds from measured outputs. Decide final app name/application ID/signing identity/distribution license and destination policies before release. Optional provider asset permissions, source download approvals, package variants and selected gallery display rights are explicit gates. No model training or dataset redistribution is required.

The PDF writer/backend and its tested document-size limit must follow CAPTURE_IMPORT_EXPORT and its independent rasterization/memory gate. A new backend must implement the same ExportSnapshot and publication contract. Lack of an optimal backend is not permission to deliver an unbounded or silently truncated export.

## Change record procedure

Append decision ID/date, exact question, considered alternatives, owner/authority, adopted rule, evidence or DESIGN status, affected requirements/tasks/files and tests reopened. The integration owner edits shared definitions before dependent task text. Preserve earlier rejected/historical decisions as historical; do not relabel a later measurement as proof that the earlier proposal was already tested.

No future implementation risk above is reported as resolved at specification creation. Documentation review may resolve contradictions without resolving model rights, physical-device cost or visual acceptance.
