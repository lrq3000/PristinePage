# Implementation-agent operating contract

Read PRD.md, TODO.md, PIPELINE_CONTRACTS.md, and common-mistakes-and-frequently-used-commands.md before work and after resumption. Read the complete domain document for the task you claim. Resolve relative links within this package; do not assume access to another workspace or a previous conversation.

## Authority and scope

This package authorizes a future implementation plan; creating this documentation did not execute its tasks. Obtain the operator's implementation authorization and application workspace before scaffolding or installing. Follow the operator's actual permissions for writes, dependencies, emulators, Git and deployment. Never infer authority to publish, delete user documents, train models, upload photos or spend API credits.

Use independent code and correctly licensed dependencies. Do not copy proprietary assets or weights. Preserve source notices for any public code port. A new name, translated code or rewritten comments do not remove upstream license obligations. Keep unverified models out of the distribution allowlist. Do not describe algorithm output as original documentary truth merely because it looks cleaner.

## Work discipline

1. Claim exactly one task ID in TODO.md, record dependencies, attempt ID, permitted paths and validation evidence. Multiple workers have disjoint file ownership. The integration owner serializes shared types, version catalog, database migrations and release configuration.
2. Implement the smallest working vertical slice. Use test-first development for deterministic algorithms, state transitions and failures; retain regression fixtures. Write a failing test, observe the intended failure, implement, and rerun the focused test. UI prototypes and subjective visual acceptance are labeled separately.
3. Respect immutable originals and revisioned recipes. Do not edit source images in place, overwrite a prior export silently, reuse stale previews, or publish half-written files as completed documents.
4. Treat public inference wrappers as versioned contracts. Establish source-to-exported CPU numerical parity before delegate optimization. A model that loads, a screenshot, or a successful inference call is not quality/device acceptance.
5. Record proposed defaults versus measured results. Keep slow models, missing rights, failed conversions and unsupported cases explicit. Never mark an unavailable neural option as implemented using a classical output under its label.
6. Run targeted checks, then relevant integration tests. Avoid repeated clean/full rebuilds or unbounded logs. Commit coherent tested milestones if Git is in use and permitted. Do not overwrite other workers' changes.
7. Update requirements/task/test links and affected docs when changing a shared contract. Read the whole changed definition and its dependents, not only matching lines. Obtain independent review at pipeline, persistence/export and release boundaries.

## Style and terminology

Use Kotlin names from PIPELINE_CONTRACTS. Distinguish source, oriented source, crop, rectified page, preview, thumbnail, rendered page and export. A page mask identifies page coverage; a protection mask limits photometric changes; a confidence score is not a calibrated probability. A backward map maps destination centers to source centers. A recipe describes requested processing; an execution record describes what actually happened.

Documentation and nontrivial comments use one physical line per paragraph. Explain units, coordinate spaces and why an operation exists. Prefer small classes with explicit ownership over a generic node-workflow framework. Do not duplicate algorithm constants in UI and native code: serialize the typed recipe and pass it through the same renderer.

## Completion reporting

Report implemented tasks, exact version/commit, focused/full checks actually run, unchanged or migrated persistent data, source/weight identities, measured device/quality limitations, open gates and the next actionable task. An empty unchecked TODO is not proof of correctness. Keep test failure, unsupported capability, user rejection, cancellation and resource limits as distinct outcomes. Never report the application complete while required PRD features or hard acceptance gates remain open.
