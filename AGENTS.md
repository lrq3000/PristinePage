# PristinePage implementation workspace

Read the complete operating contract in `docs/specs/AGENTS.md`, then `docs/specs/PRD.md`, `docs/specs/TODO.md`, `docs/specs/PIPELINE_CONTRACTS.md`, `docs/specs/common-mistakes-and-frequently-used-commands.md` and `docs/specs/OPERATIONAL_PLAN.md` before work and after resumption. Read the complete domain specification for the assigned task. The accepted implementation plan is `docs/specs/IMPLEMENTATION_PLAN.md`; do not replace it with a second implementation plan.

The visible application name is PristinePage. The operator-requested future store title is `PristinePage: Documents & Photos Scanner`; publication and store-specific title compliance remain separate gates. Approved paths retain the Paperlight working label, and the internal package namespace remains `app.paperlight`, with development application ID `app.paperlight.debug`.

The original source package at `X:\local-workspace\new-scan-app-specs` is immutable. This repository's root checkout is immutable after its single approved documentation bootstrap commit. Perform application edits, builds and milestone commits only inside newly created worktrees under this repository's `.worktrees/`. Do not reuse or alter another worker's worktree. Preserve unrelated changes and never reset, clean, overwrite or delete them.

The coordinator alone publishes task claims, live authority changes, shared contracts, build configuration, dependency versions, database migrations, acceptance and integration decisions. Workers receive bounded task/attempt IDs, input versions, exclusive paths, interfaces, required tests and report paths. No nested delegation without coordinator authorization. Independent review is required at pipeline, persistence/export and final artifact boundaries.

P-01 authorizes local implementation and coherent verified local Git commits, not dependency/model acquisition, installations, debug/production signing, physical-device operations, external file publication/sharing, conversation recall/capture, spending, pushing or merging. Read `OPERATIONAL_PLAN.md` for the exact gates and resource limits. An explicit later permission must be recorded there before the corresponding operation.

Build output is not acceptance. Keep every task and requirement open until its actual current-build evidence exists. Preserve nonregenerable tests, fixtures and operational decisions; ignore regenerable build outputs. Do not report an installable APK or a passed device/visual gate before actually verifying it.
