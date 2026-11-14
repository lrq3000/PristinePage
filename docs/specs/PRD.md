# Product requirements — PristinePage

Version 1.0, 2026-11-14. This is the central product entrypoint. [TODO.md](TODO.md) is the task-state authority; [REQUIREMENTS_TRACEABILITY.md](REQUIREMENTS_TRACEABILITY.md) maps requirements to implementation and acceptance. [SPEC_PACKAGE_VALIDATION.md](SPEC_PACKAGE_VALIDATION.md) concerns this documentation, not app implementation status.

## Active implementation authority and branding

The operator approved [P-01](OPERATIONAL_PLAN.md) and launched local implementation on 2026-11-14. The confirmed visible app name is **PristinePage**; the exact future store title selected through the subsequent branding form is **PristinePage: Doc & Photo Scan**, 30 characters. Earlier40-character and34-character title requests are superseded. Paperlight is the historical specification/working-path label, not the user-facing app name. The selected title meets Google's length constraint, not a claim of complete listing/trademark approval. [Google Play metadata policy, inspected 2026-11-14](https://support.google.com/googleplay/android-developer/answer/9898842?hl=en). Other-store requirements and rights remain unverified; no listing publication is authorized.

The application workspace is `X:\local-workspace\.worktrees\paperlight-v1`, branch `feat/paperlight-v1`; this `docs/specs` copy is the live application authority. The original 24-file v1.0 package at `X:\local-workspace\new-scan-app-specs` remains unchanged. The root checkout is an immutable documentation seed. Reuse [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md); read [validation/progress.md](../../validation/progress.md) for current execution/restart evidence and [the incident register](common-mistakes-and-frequently-used-commands.md) before work.

P-01 authorizes local source/test/record changes, new isolated worktrees and verified local commits, but retains separate dependency-acquisition, signing, device, real-asset, external-publication/sharing and conversation-memory gates. No such permission was added by the launch message. API26+/arm64 and internal namespace `app.paperlight` remain the approved baseline; development application ID is `app.paperlight.debug`. No application acceptance gate has passed at this initial setup checkpoint.

### User amendment U-01 — neural processing is mandatory

On 2026-11-14, after approving P-02's foundation dependency bill, the operator clarified that several genuinely implemented neural methods are central to the requested app, alongside non-neural methods, covering learned geometric correction and appearance/illumination/color restoration for document and photograph scanning. This current explicit requirement supersedes the original v1.0 permission to finish the app with all neural models deferred. Classical import→edit→save→export still comes first; a registry, disabled cards, model loading alone or classical substitutes under neural names cannot satisfy this amended completion condition. Detection alone is not learned curved-page correction.

The exact selected providers/count, rights-cleared assets/runtime, their output contracts and device/quality acceptance are to be specified in an approved neural implementation/acquisition amendment before dependent operations. Existing technical parity, mask, provenance, resource, offline and content-fidelity gates remain binding; this requirement does not authorize downloads outside P-02, unlicensed assets, spending or unsupported quality claims. Original optional-model text below and in linked v1.0 domain documents is historical on this one scope point and must not be used to omit all neural implementations. T-08/T-12 acceptance is blocked pending that reconciled provider plan; independent approved foundation work continues.

Branding decision U-02: after the34-character replacement request, the operator explicitly selected `PristinePage: Doc & Photo Scan` on the branding form, retaining `PristinePage` spelling and a30-character future title. No listing was edited, and no package/workspace rename or publication was authorized.

## Purpose and users

Turn ordinary photographs of receipts, printed pages, handwritten notes, colored documents and curved book pages into useful scan-like digital documents, while retaining faint marks, colored content and the original photograph. The user must be able to choose between faithful appearance and white-paper aesthetics rather than have information erased by an invisible enhancement decision.

Version 1 is a complete local scanner, not just an image-filter demonstrator. It must remain useful without any research model installed. Multiple strong algorithm alternatives are retained only when they fill distinct gaps; installation availability and device evidence govern what is selectable, not paper recency or attractive publisher examples.

## Accepted scope and design defaults

- Fully offline processing and bundled local examples; no account, telemetry endpoint, first-run model download or Play-services requirement.
- Native Kotlin/Compose app, CameraX, Room, coroutine-based interactive processing, WorkManager for recoverable committed jobs where supported. Reuse established image primitives; optional runtime adapters are isolated.
- DESIGN minimum Android API 26; arm64-v8a required. Other ABIs are optional build variants after dependency/runtime checks. Pin a mutually compatible stable toolchain at bootstrap and record it; no unverified claim about the latest SDK or store policy is made here.
- DESIGN initial target class: physical mid-range arm64 devices with 4–6 GB RAM. Preview long edge 1024 pixels, card long edge 224 pixels, at most one expensive neural session active. These are validation starting points, not measured device guarantees.
- Local document library, multiple independently editable pages, source-preserving revisions, image-only PDF, JPEG and PNG export, and Android sharing.
- Algorithm descriptions and geometric correction are first-class implementation specifications, not placeholder menu entries. All mandatory classical algorithms have full equations and deterministic tests. Learned providers are optional, separately licensed and release-gated.

## Functional requirements

| ID | Requirement and acceptance intent |
|---|---|
| FR-01 | Camera capture supports permission refusal/revocation, orientation, flash control when available, manual shutter and a usable crop suggestion or manual fallback. Live auto-capture is not required. |
| FR-02 | Import one or more permitted JPEG/PNG/WebP photos through system pickers; validate actual content, decode limits and orientation; copy permitted source bytes into app-owned durable storage. Unsupported/corrupt/HDR inputs produce an actionable error. PDF import is deferred. |
| FR-03 | A document contains ordered pages; add, remove, duplicate, rotate and reorder pages with Undo where still reversible. Source sharing across duplicated pages uses explicit reference counting. |
| FR-04 | Supply editable labeled four-corner crop, full-frame reset, magnifier and perspective rectification. Detection failure never pretends the full frame was detected. |
| FR-05 | Offer a fully specified manual curved-page correction and optional learned dewarp providers. Validate inverse maps, retain content and make fallback visible; perspective alone is not curved-page flattening. |
| FR-06 | Ship No enhancement, conservative white-paper gain, natural lightness, and explicit grayscale/binary recipes, with strength, documented constraints and unchanged-original comparison. |
| FR-07 | Support the provider registry and at least one optional appearance/dewarp evaluation path; no specific neural model is mandatory to ship version 1 unless rights, asset, numerical, device and quality gates pass. A disabled provider has a reason. |
| FR-08 | Optional bounded luminance sharpening and noise suppression apply once in a fixed order; off is exact identity for the respective stage. |
| FR-09 | Touch-friendly same-document carousel compares selectable recipes under the same geometry, supports synchronized inspection and input/result comparison, and never substitutes another method's output under a failed card. |
| FR-10 | Persist typed versioned recipes and execution provenance; editor Undo/Redo changes recipes, not source pixels. Stale/canceled jobs cannot overwrite newer selections. |
| FR-11 | Durable local library supports creation, rename, reopen and explicitly confirmed deletion. Process death and storage failures do not leave committed metadata pointing to missing source files. |
| FR-12 | Export a user-confirmed immutable document snapshot as image-only multi-page PDF or per-page JPEG/PNG, preserving order, aspect and explicit resolution/compression settings. Final output is verified before successful publication. |
| FR-13 | Share finished app-owned exports through content URIs with temporary grants; revoke/expire grants and retention deliberately. Never expose arbitrary private file paths. |
| FR-14 | Structured errors distinguish invalid input, unavailable provider, invalid map, processing failure, cancellation, permission loss, storage failure and resource limit; original and last accepted recipe remain retrievable. |
| FR-15 | Accessible navigation, selected-state descriptions, at least 48dp targets, large-font layouts and non-gesture alternatives for crop/curve adjustments and comparison. |
| FR-16 | Cold-start capture/import, every shipped algorithm, examples, saved documents and export work in airplane mode with only installed assets. |

## Nonfunctional requirements

| ID | Requirement and hard boundary |
|---|---|
| NFR-01 | Fidelity: do not accept erased test strokes, neutralized protected colored content, folded/overlapping geometry or unexplained margin loss merely to improve a cleanliness score. Quality acceptance is case-based and measurable. |
| NFR-02 | Bounded memory: checked dimensions and admission limits precede allocation; a resource failure returns a controlled error, not an unannounced low-resolution export or process-killing allocation attempt. |
| NFR-03 | Responsiveness: no decode/inference/export on the UI thread; generation-aware cancellation; classify measured slow options honestly. Proposed feedback target is 100ms, not an already measured result. |
| NFR-04 | Privacy: no document network transmission, no hidden backup/analytics, no sensitive pixels in logs/crash attachments. External sharing is an explicit user action. |
| NFR-05 | Durability: original-file ownership, database/file transaction recovery, recipe snapshots, job idempotence and interrupted-export state are tested across process death. |
| NFR-06 | Reproducibility: source bytes/pixel identity, color/orientation conversion, recipe version, geometry, asset hashes, backend/precision and output size identify a render. Preview approximations are labeled. |
| NFR-07 | Rights: code, weights, dependencies, training-data statements and sample-display rights are evaluated separately; restricted/unresolved assets are excluded from default release builds. |
| NFR-08 | Verification: unit, integration, physical-device, offline, accessibility and visual-quality evidence are retained and linked to exact builds; proposed or skipped checks cannot be reported as passed. |
| NFR-09 | Standalone neutral specification: no dependency on proprietary artifacts, prior conversations or another local directory; all necessary contracts are embedded and public sources attributed. |

## First release, optional experiments and exclusions

P0 release: FR-01–06 and FR-08–16, plus provider capability boundaries in FR-07. A missing neural model is allowed; a missing manual geometry or deterministic enhancement path is not. Multi-page PDF export is mandatory but must respect explicit tested admission limits. Add no model until a classical import→edit→save→export vertical slice works.

P1 gated experiments: DocQuad detection, UVDoc map-based dewarp, GCDRNet field/full appearance, DocRes task modes and coarse SyntheticDoc geometry. Keep a second candidate only for a useful measured trade-off. FSENet is a conditional slow research comparator, not promised handset functionality. [MODEL_PROVIDERS.md](MODEL_PROVIDERS.md) controls exact statuses and interfaces.

Out of scope: OCR/searchable text, cloud sync, PDF import, document authentication/forensic claims, text/content synthesis, seal/signature/watermark removal, moiré restoration, arbitrary occluded-fold recovery, stitching/book splitting, billing and app-store publication. These may be separate later PRDs; a “complete scanner” does not silently include them.

## Acceptance and user control

Functional acceptance is all P0 paths exercised, including refusal/failure branches. Algorithm arithmetic, source/exported-model parity, physical-device fit and subjective visual acceptance are separate gates. Synthetic fixtures diagnose failures; representative permission-cleared real pages are mandatory before visual acceptance. Pure white output is not a valid way to pass whitening metrics. Original and No enhancement are always available.

The distribution license, signing identity, final branding, final store target, real device models, quality tolerances learned from evaluation and optional model assets remain operator/release decisions. These do not excuse omitting deterministic default contracts or tests. Record decisions in [DECISIONS_AND_RISKS.md](DECISIONS_AND_RISKS.md); material product scope changes require review.

## Documentation construction and review contract

The operator approved this complete-app scope and a 24-file Markdown package with unusually detailed algorithm/geometry coverage. Documentation production uses local domain drafting, cross-file interface reconciliation, complete package review and comparative source/requirement checks. The technical archive and this parent session are indexed separately for task-scoped recall; memory is supporting context, not an implementation dependency or independent source validation. Documentation status is recorded separately from the future application task list.
