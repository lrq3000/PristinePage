# fix(storage): bind frozen render leases to first admission

**Disposition:** Host-verified candidate for independent persistence review, not integrated or active. `U-ADMITTED-RENDER-PROOF` follows only the final continuation in `bb0f6ea22fd25c223a4116c7679c74e1207d2672:validation/export/ordinary-export-local-queue.md` (SHA-256 `9949873e21597036ae50f2a7612302a58dfbe4bc60d49a552eb948621419a3fa`). Exact clean canonical `main` base `426570b34eead6486f5f52d3b62fc8e62659ab83`; branch `work/pristinepage-admitted-render-proof`, ignored worktree `X:/local-workspace/.worktrees/pristinepage-admitted-render-proof`. Git fallback created only the authorized path/ref; no native action supported that exact setup without a session move/creation or unrelated workspace registration. This is the third distinct intake; the coordinator owns full-queue reassessment, independent review and destination verification.

## Motivation and achieved scope

The canonical ordinary job identity intentionally permits byte-distinct originals with identical normalized pixels. The former Boolean admission result discarded the identity of the JOB pin that actually passed preflight and the writer. The former render lease entry accepted any `Verified`, so a fresh read after admission could successfully bless normalized-equivalent replacement B and acquire for a job admitted with A. The frozen recipe and canonical job can stay unchanged throughout that drift.

- Admission now returns `RoomFrozenRenderAdmission.Admitted?`. The receipt wraps the `Verified` constructed from the detached initial projection, before preflight. The exact same immutable JOB Source/Blob object passed to preflight remains available through `receipt.proof.pinnedBinding` for the later producer; it is not a fresh observation of live counters or the frozen consumer's original. The prepared proof and original binding are rechecked in the existing writer. The private receipt constructor is reachable only in the complete admission path, after exactly one existing CAS write, inside the transaction that returns it. Zero yields `null`; impossible counts still throw inside the writer so it rolls back. There is no generic constructor/factory accepting `Verified`, retry, re-read on cancellation, or way to recover a lost post-commit receipt in this slice.
- `FrozenRenderAcquisition.acquire` takes `Admitted`, not a standalone `Verified`. It unwraps the very same proof; the existing independent lease transaction bounds the narrow identity, authenticates the live JOB binding against A, and requires the lease scalar guard before the wide read. Counts must still be positive but may legitimately have changed. Existing row identity matching, owner liveness, busy/takeover, generation/attempt/deadline arithmetic, clocks, cancellation and CAS are unchanged. No new transaction or authority to open files, retain a source, reserve resources or revive a stale owner; `Verified` still serves its existing read-only, file and terminal consumers without ever being sufficient to acquire here. Canonical job bytes stay private to `Verified`, separate from the copy passed to CAS.

## What ran

The host fixture uses the real freeze, assembly-reference proof, admission and lease methods against transaction-aware metadata/DAO doubles. The test first captured ordinarily admitted A, changed only the JOB binding to normalized-equivalent byte-distinct B between the writers, and proved the real narrow reader independently accepted B with the same canonical JobId/digest. On the old entry, using that fresh observation returned `Granted`. In GREEN the receipt still carries A and the separate writer rejects before lease bounds, `SELECT *`, owner lookup or update, with the unchanged `INTEGRITY_FAILED/frozen_render_lease_binding_changed` diagnostic. A stable byte-distinct JOB pin selected before admission still succeeds, independently of the frozen page source.

Positive coverage pins frozen page identity, canonical `JobId`/digest, object identity of the preflighted binding even when positive reference counters change in the writer, exact grant (generation 1, attempt 1, elapsed deadline 540, independent Unix audit time 7000), and unchanged job BLOB/pins. The fixture poisons a borrowed initial projection buffer after the read and, in the lease composition, the separate CAS argument after the write; neither can alter the retained proof used to grant. Both zero admission CAS paths return no receipt, with the PENDING row or prior ADMITTED row unchanged. Independent zero/negative live Source and Blob counters refuse on the typed check even with the scalar double permissive, before wide read or write. The preflight refusal/cancellation, impossible-count rollback, raw guards, same-extent identity drift, nonadmitted/terminal states, and existing lease-generation/owner controls were retained.

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests app.pristinepage.data.RoomFrozenRenderAdmissionTest --tests app.pristinepage.data.RoomFrozenRenderLeaseTest
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest
python -B -m unittest discover -s validation/storage -p "test_*.py" -v
```

| Phase | Wrapper exit / wall seconds | Fresh result |
|---|---:|---|
| Focused RED, test-only | 1 / 168.905 | 35 pass, two intended assertion failures in two XMLs: the old Boolean path returned `false` for the zero-CAS replay; the old `Verified` path returned `Granted` for B. Zero errors or skips. |
| Focused GREEN | 0 / 52.343 | 38 pass in two XMLs, no failures, errors or skips. |
| Full data JVM | 0 / 28.580 | 623 pass in 59 fresh XMLs, no failures, errors or skips. |
| Serialized SQLite | 0 / 0.741 | 82 pass, including nine admission CAS and nine terminal raw-query checks against the existing DAO SQL and v1 DDL. |

A separate PowerShell attempt at the full suite exited 1 before invoking Gradle or SQLite because the focused GREEN receipt did not yet exist (`data-prelaunch-refusal.{json,log}`); it produced no JUnit/SQLite result. The actual full suite began after verified GREEN exit. Compiler warnings in the RED compile were in unchanged `DirectorySync`, `DocumentCommandsTest`, `ExportSettingsTest`, `FileOperationOwnersTest` and `RepositoryContractTest` sources.

## Reproduction pins and release

Each invocation independently verified 750 tracked input hashes, no unrelated edits or ref drift, positive sole-slot preflight, and the live 8 GiB floor. Prelaunch free C: bytes respectively: `9684025344`, `9653063680`, `9413894144`, `9301655552`. The existing isolated cache was used with JBR 21.0.8, wrapper Gradle 8.13, AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.14, Room 2.8.5, JUnit 4.13.2, Android SDK 36 rev. 2/build tools 35.0.0, Python 3.13.12/SQLite 3.51.1 and Git 2.54.0.windows.1. Per-phase records authenticate the cached launcher/distribution marker, tool and dependency pins, child-worktree preference/temp paths, and inherited process harness. No cache priming, downloads, forced reruns or cleanup.

Raw evidence is under ignored `validation/raw/u-admitted-render-proof/` in the worktree. All 145 entries of `evidence.sha256` were rehashed; manifest SHA-256 `b919fd22c627830a5538fa99b339106d1cd63b405ec69edc8c212ff33de77252`. It contains the exact runner, test/production snapshots as applicable, logs, command and process exits, setup-only refusal and fresh XML; temporary workspace and build outputs are left in place outside the manifest. RED `build-inputs.json` SHA-256 `860e9e33c9980d8ecb252e98ec95f9745191d081a4c256e6cb1b4af980d4d8fc`; GREEN/full/SQLite use identical `dbe20779699efc5f89deb24555ad4474ab26340ff9032da1be1d2faec7592d46`. Room v1 schema SHA-256 stayed `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`; the generated `ExportSnapshotDao_Impl.kt` matched the existing pin before and after, `555d6b0ba72a1ac692d2cef08a5799f1fbd58ca93b26a07021440c7289bbf6f4`. No DAO, schema, lock, app, image, scheduler, terminal, public ingest or file-path change.

| Phase | Wrapper PID / exit | Observed wrapper/descendant handles | Supervisor PID / exit |
|---|---:|---:|---:|
| RED | 11724 / 1 | 10 | 10364 / 0 |
| GREEN | 34216 / 0 | 10 | 31860 / 0 |
| Data | 32540 / 0 | 20 | 19972 / 0 |
| SQLite | 18512 / 0 | 1 | 40584 / 0 |

All 41 observed wrapper/descendant handles were signaled (`wait=0` with creation/exit FILETIMEs), with four supervisors positively exited. The intended RED wrapper and its launcher JVM (`40080`) exited 1. No handle-open race or unresolved process remained. `release.json` SHA-256 `00f7e5e6fcd4d0ef7539cef84b6059e10fc0125d980a9f8a221755c40e30bede` records no live build/storage tools or owned shell/supervisor/wrapper PIDs, and `9301491712` free C: bytes. The exclusive HOST slot is released to the coordinator; no further worker tests are scheduled.

**Limits and next action:** Independently review the receipt's minting and binding paths and verify the exact destination before adoption. Android Room contention, invalid pin cardinality/storage-class cases on native Room, process death, actual PNG capability and codec execution, stable terminal readback, late resource/cancellation/byte-cap failures, complete-set `PRIVATE_READY` and app activation remain open. Native/API26/27/PSS/fidelity testing is not inferred from host doubles or SQLite. No APK/device, new IO, scheduler, external Git, memory or cleanup operation.

**Included commit:** The enclosing single-parent local commit by lrq3000, `fix(storage): bind frozen render leases to first admission`, keeps the exact preflighted JOB Source/Blob across the two transactions and rejects normalized-equivalent replacement before the wide lease read. It does not integrate or activate export. **Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).
