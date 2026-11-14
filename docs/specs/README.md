# Paperlight — Android scanner implementation package

Package version 1.0. Specification date: 2026-11-14. Paperlight is a working project label, not a cleared trademark. This directory is a standalone engineering brief, not a working application or a claim of measured image quality.

## Start here

Read [PRD.md](PRD.md), [AGENTS.md](AGENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md), and [PIPELINE_CONTRACTS.md](PIPELINE_CONTRACTS.md), then use [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) and [TODO.md](TODO.md). Every requirement has an owner task and acceptance evidence in [REQUIREMENTS_TRACEABILITY.md](REQUIREMENTS_TRACEABILITY.md). Do not begin with model integration or a large UI scaffold: deliver a tested, deterministic import-to-export vertical slice first.

The product is a fully offline Android document scanner: camera capture and photo import, user-correctable page boundaries, perspective and curved-page geometry, selectable enhancement algorithms, same-page comparison carousel, multi-page documents, durable local library, PDF/JPEG/PNG export, and Android sharing. OCR, accounts, cloud processing, document-content removal and subscription infrastructure are outside version 1.

## Technical documents

- [PHOTOMETRIC_ALGORITHMS.md](PHOTOMETRIC_ALGORITHMS.md): reproducible illumination, color, tone, noise, sharpening and binary algorithms; mathematical defaults and failure cases.
- [GEOMETRY_ALGORITHMS.md](GEOMETRY_ALGORITHMS.md): coordinate spaces, crop/homography, manual curve, learned inverse maps, map validation and mask transformation.
- [MODEL_PROVIDERS.md](MODEL_PROVIDERS.md): independent detector/restoration/dewarp adapters, assets, licenses, conversion and device gates.
- [CAPTURE_IMPORT_EXPORT.md](CAPTURE_IMPORT_EXPORT.md), [DATA_MODEL_AND_STORAGE.md](DATA_MODEL_AND_STORAGE.md), [UX_SPECIFICATION.md](UX_SPECIFICATION.md): the complete app around the image engine.
- [PERFORMANCE_AND_OFFLINE.md](PERFORMANCE_AND_OFFLINE.md), [SECURITY_PRIVACY.md](SECURITY_PRIVACY.md), [LICENSES_AND_SOURCES.md](LICENSES_AND_SOURCES.md): resource, privacy and distribution boundaries.
- [VALIDATION_PLAN.md](VALIDATION_PLAN.md), [TEST_FIXTURES.md](TEST_FIXTURES.md): executable acceptance specifications; no future app test is already passed.
- [DECISIONS_AND_RISKS.md](DECISIONS_AND_RISKS.md): accepted design decisions, gated options and unresolved external dependencies.
- [AGENT_IMPLEMENTATION_PROMPT.md](AGENT_IMPLEMENTATION_PROMPT.md): copyable implementation handoff.
- [common-mistakes-and-frequently-used-commands.md](common-mistakes-and-frequently-used-commands.md), [working-files-organisation.md](working-files-organisation.md), [SPEC_PACKAGE_VALIDATION.md](SPEC_PACKAGE_VALIDATION.md): operational handoff and documentation-validation record.

## Evidence vocabulary and precedence

**REQ** means a normative requirement in this package. **DESIGN** means a proposed implementation decision adopted for a reproducible first version; it is not a measured optimum. **SOURCE** means an inspected public source supports the attributed behavior. **GATED** means assets, rights, conversion or device validation remain incomplete. **MEASURED** may be used only with an actual retained run and environment record. None of the app's quality, device or runtime acceptance gates has been executed by this documentation package.

For requirement scope PRD is authoritative; PIPELINE_CONTRACTS controls shared arithmetic/API/state semantics; algorithm documents control their named versioned recipes; provider documents control external model contracts. Implementation tasks cannot silently override these. A contradiction stops the dependent task and is resolved in DECISIONS_AND_RISKS before code changes. Referenced public source code is a parity reference for an optional adapter, not an instruction to import it without licensing review.

The deterministic core can be implemented from this package alone. Neural assets must be acquired independently under their actual rights. Public sources are linked directly; no other local project, historical investigation, proprietary artifact, transcript or memory index is required by the receiving implementation agent.
