# Working files, artifacts and safe reuse

## This delivered package

All24Markdown files in this directory are active version1.0 specifications. README is the navigation index, PRD the scope authority, PIPELINE_CONTRACTS the shared interface authority, TODO the future application task state, REQUIREMENTS_TRACEABILITY the requirement/task/test map, and SPEC_PACKAGE_VALIDATION the documentation receipt. Preserve source files and earlier accepted package versions when revising. Do not silently relabel an old result as validation of a new package hash.

No application source, Android project, proprietary artifact, model checkpoint, photo gallery or benchmark output is delivered by these Markdown files. No adjacent directory or conversation is required by the receiving implementer. Public-source links identify external optional assets and parity references; rights/acquisition remain explicit.

## Future application workspace layout

```text
app/                         Compose, camera, library, editor, export UI
core/                        image/geometry/recipe contracts and reference math
image-android/               decode, renderer, native bridge and model adapters
data/                        Room, file journals, recovery and repositories
test-fixtures/               deterministic generators and semantic masks
gradle/libs.versions.toml     pinned dependency/toolchain catalog
validation/                  retained text results and source/recipe/build manifests
validation/fixtures/          small nonregenerable regression inputs and rights records
validation/device/            build/device evidence; avoid sensitive content
licenses/                    applicable dependency/source/model notices
docs/                        copy of the accepted package plus change history
```

These paths are implementation-plan decisions, not directories created by this specification task. Generate large fixture/output/build caches outside version control; retain their generator/version/hashes. Preserve nonregenerable repros, manual design decisions and meaningful tests. Store private user photographs only in the running app's explicit data areas or a separately approved test corpus, not a public repository.

## Runtime artifact classes

Originals are immutable durable source bytes; normalized/rendered pixels are reproducible only with recorded decoder/recipe/provider identities. Draft preview/cache files are disposable and cannot be the sole source. Accepted recipe revisions and export snapshots are persistent metadata. A complete app-private export remains a distinct owned artifact from its external SAF/share copy. Journals are recovery records, not user-visible completed documents.

Deleting a page and deleting its last source reference are different operations. Removing a temporary cache must not delete the original or a file still referenced by a render/share. Apply the ownership/retention rules in DATA_MODEL_AND_STORAGE and SECURITY_PRIVACY; never recursively clean an unclassified directory.

## Authoring evidence and recall

This package was synthesized from selected public-source research and task requirements. Project-scoped document indexing and a separately consent-scoped parent-conversation index are authoring aids outside this package. They are not app runtime dependencies, proof of factual claims, or a reason to automatically store future app users' documents/chats. Operational indexing receipts and source hashes belong outside the indexed source corpus to avoid self-referential reindexing.

For implementation handoff only this directory is required. For a documentation revision, consult the exact public source versions or authorized authoring records, verify current files rather than trusting a compacted status, and identify any unavailable evidence. Do not initialize a global memory database or copy unrelated sessions merely because this package mentions recall.
