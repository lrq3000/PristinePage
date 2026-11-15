# T03-FILE-R — partial persistence-file review

## Scope and inspected evidence

Reviewed2026-11-15, worktree `paperlight-v1`, branch `feat/paperlight-v1`, independently observed HEAD/base `74bba9fb9542f14d48157d6a2bf1219cda882bc0`. Read the actual complete uncommitted/untracked SourceFiles.kt and SourceJournalTest.kt, not merely Git diff; root/spec AGENTS, complete PRD/TODO/PIPELINE/incident/operational/storage contracts, accepted T-03 excerpt, related architecture/capture/privacy/resource/batch contracts, receipt, P-02 and review template. This is only the caller-journaled file helper, not SourceStore/Room/repository/task acceptance.

Frozen input SHA-256: SourceFiles.kt `75b34a92d94162178c6671a2d6b2d1426311fa5ed044c61de491d045b63560ca`; SourceJournalTest.kt `ea6d81a45c7778220e0dbf5399fa646570eb50fa89e8c39d72817fd378b7e9a9`.

Read-only XML comparison independently found583→584 components/994→996 artifacts, precisely the named KSP2.3.14 JAR/POM, no changed/removed previous records. Settings/catalog/data build configuration and historical inventory/snapshot match HEAD. Independently hashed both cached artifacts and byte-compared all four retained legal texts with their JAR entries: match the delta document. P-02 explicitly covers KSP2.3.14 required processor/compiler support. Acquisition-scope compliance: PASS for this inspected delta; retained hashes/declarations do not establish publisher authentication, exhaustive shaded licensing or distribution clearance.

No tests/builds ran, no changing reports/APKs were inspected, and no device/network/Git mutation occurred. Receipt/coordinator report intended six absent-helper assertions followed by focused green and full offline strict units/lint exit0/138tasks; these are attributed reports, not independently harvested JUnit evidence. All counterexamples/oracles below are static findings/proposed regressions, not executed reproductions.

## Implemented safeguards

CREATE_NEW staging/seal, bounded streaming/count/hash, full payload verification, copied metadata exposure, opaque canonical UUID paths, descendant symlink checks, atomic-move-only behavior, final conflict preservation and stage/final replay validation form a coherent small file boundary. No method publishes a page or deletes recovery evidence. Work is O(payload bytes), with bounded metadata/buffers.

## Findings

### Critical

None identified in this bounded inspection.

### Important

**I-1 — Newly created directory ancestry is not synced.** `data/src/main/kotlin/app/pristinepage/data/files/SourceFiles.kt:147-150`, with callers at43/66 and96/99. Verified omission: createDirectories may create `staging/<tx>` and `sources/<blob>`, but only the leaf directories are forced. Counterexample: start with an empty existing store; stage/seal/move successfully; the caller commits metadata. On a filesystem requiring separate parent-directory persistence, a crash may lose the `staging/<tx>` or `sources/<blob>` directory entries despite every requested leaf/file force succeeding. The original can then be unreachable by its committed path. This is an omitted available sync, not a demand for a universal hardware guarantee; the actual target outcome is untested. Minimal remedy: track newly created directories and force their containing directories, including the owned store root when creating its immediate children, before returning the corresponding durable boundary. Oracle: an injected directory-sync recorder on a fresh root must observe persistence of the `store→staging→tx` and `store→sources→blob` links, not only tx/blob contents; retain later filesystem/crash validation. Contract: DATA_MODEL_AND_STORAGE:132/153.

**I-2 — Real directory-sync IO failures become “unsupported” success.** `data/src/main/kotlin/app/pristinepage/data/files/SourceFiles.kt:168-171`. Counterexample: directory channel open succeeds on a supported filesystem, but force(true) throws IOException for an IO/storage fault. The catch suppresses it, sets directorySyncSupported=false, and stage/seal/move return normally. A correct caller cannot distinguish that fault from an unavailable primitive and may advance its journal/commit. Minimal remedy: distinguish explicitly recognized unsupported-directory-descriptor cases from operational IO failures; propagate unexpected open/force failures while preserving files. Oracle: inject a successful directory open followed by force IOException; the operation must fail rather than return a completed token/path, with payload/seal evidence retained. Separately inject genuine unsupported capability and assert the documented degraded capability flag. The current tests never exercise this distinction. Contract: DATA_MODEL_AND_STORAGE:153.

### Minor

**M-1 — Early staging failures leave the supplied stream open.** `data/src/main/kotlin/app/pristinepage/data/files/SourceFiles.kt:39-46`. The method closes input on normal/read failures, but validation/path/directory creation precede input.use. Counterexample: an existing regular file at `root/staging` makes directory creation throw before a tracking provider stream is closed. Repeated storage failures can retain descriptors unless callers separately own a use scope. Minimal remedy: establish consistent stream ownership around the entire operation, or explicitly require/document caller ownership. Oracle: the tracking stream is closed exactly once on this early failure. No production caller was reviewed.

## Considered / declined to judge

- Atomic-move destination races and path TOCTOU: no finding assuming the explicitly required exclusive fenced lease/namespace ownership; ATOMIC_MOVE alone is not a portable no-replace concurrency primitive. Enforce that precondition in later integration.
- Missing/partial seals and conflicting payloads: rejection preserves evidence; caller-controlled quarantine/new attempts remain legitimate, not required helper auto-repair.
- SourceInspector meaning, typed errors/cancellation, Room visibility/CAS/refcounts/snapshots/batch save/recovery leases: future caller/integration gates, not missing features of this partial helper.
- Android filesystem support, real process death, physical performance and power-loss survival: no runtime evidence/permission; host files cannot prove them.
- Exhaustive rights, historical per-request network origins and final APK contents: beyond the inspected compiler delta; limits are disclosed.

## Verdict

Bounded file-layer specification compliance: NEEDS FIXES for I-1/I-2. Code quality: coherent separation, but storage-failure handling needs those corrections. No whole-T-03 acceptance, merge/push permission or device/rights acceptance is given. Re-review focused fixes and fault oracles before treating this helper as a durable journal foundation.
