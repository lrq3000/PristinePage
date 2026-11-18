# feat(storage): execute leased render files through moved

**Initial motivation:** An ordinary verified frozen page, a live `JobLease`, and a committed render intent do not establish that any encoded bytes exist or are safe to use. `U-RENDER-FILES-20261118` joins those existing authorities to the private-file protocol without turning a file checkpoint into render completion. Authority: `e35d2471bcf8c81afd10c247579d24ab43f95f54:docs/specs/TODO.md`, DATA_MODEL_AND_STORAGE, CAPTURE_IMPORT_EXPORT, PIPELINE_CONTRACTS and the accepted implementation plan. Exact clean base: `54b707bf6909593c0335515ac584e23a10c3dafa`. Worktree: `X:/local-workspace/.worktrees/pristinepage-export-render-files`; branch: `work/pristinepage-export-render-files`. The [render-read](export-render-read-integration-receipt.md), [intent](export-render-intent-integration-receipt.md), and [derived-file](../export/derived-files-integration-receipt.md) integration receipts retain their original commits, evidence and review dispositions. No prerequisite commit is being re-adopted here.

**Final purpose achieved:** The internal data-owned `RoomRenderFileExecution.execute` consumes a `RoomFrozenRenderReads.Verified`, the already acquired current same-boot `JobLease`, and the exact committed `RenderFileIntent`. The caller retains the authenticated assembly/frozen owners and continuously excludes other operations against the stable private root, transaction and target. Invoke from outside Room. The executor never calls `prepare`, admission, acquisition or readmission. A normal return means this exact attempt reached or reverified `MOVED`; it returns no path, rendered page, completed job or ready export. For replay, retain the original intent value in the same live owner scope: the existing `prepare` semantics are unchanged and do not furnish a new intent after `SEALED`.

## What changed and why

- **Journal before writer:** Read the scoped raw-admitted journal and authenticate the full fixed v1 intent envelope, both private paths, exact frozen job input/attempt/lease, and the matching single JOB pin. An exact CAS from `INTENT` to `COPYING` must commit before opening the `CREATE_NEW` stage. Supply an explicit positive byte cap; the existing bounded writer enforces it during streaming and latches a failure even if the callback swallows it. The writer receives only a synchronous thread-confined `OutputStream`, not a writable path or descriptor.
- **Closed readback and seal:** After force/close, verify size and SHA-256 against the stage receipt, then borrow the exact owned closed `File` for trusted synchronous read-only verification. The caller must verify complete lossless content against that request and supply its canonical raster SHA-256 (or `null` to reject); it must not retain the file or mutate the store, and it owns native decode admission. `DerivedFiles.seal` re-hashes/rechecks extent after the callback and before writing the seal. The File borrow is the smallest seam that permits a future file-backed Android decoder without buffering the full file or passing a write path to the encoder. It is a cooperative read-only contract, not an OS-level read-only capability. Semantic refusal produces neither a seal nor a phase advance.
- **Semantic provenance:** Keep `RenderFileIntent` v1 and the generic seal format unchanged. The seal metadata is ASCII `pristinepage-ordinary-render-readback-v1`, NUL, the complete 615-byte v1 intent envelope, and exactly 64 lowercase ASCII hex characters identifying the read-back raster. The frozen input digest inside the envelope binds the ordinary FAIL/EXPORT request and immutable rendering profile; both UUIDs, job and runner owner, generation/attempt, frozen source/page/recipe/output and exact pin identities are carried by the envelope. The generic canonical seal separately binds target kind/ID, transaction ID, encoded bytes SHA-256 and positive byte count, and its SHA-256 enters the journal. A generic valid file/hash or unrelated generic seal is not sufficient.
- **Same-attempt recovery:** Read the exact phase and any recorded seal hash, byte hash and length under the live lease. Refuse a recorded extent over the cap before opening the seal; for a lagging journal validate the seal's exact expected `RENDER` target, cap, canonical format, transaction, v1 provenance, semantic hash grammar and any recorded byte identity before resolving payload paths. Validate both stage and final when both survive; repeat trusted readback on one exact copy and match its raster hash. Re-resolve both paths and require unchanged presence and complete bytes before forcing the already authenticated seal descriptor and syncing the supported directory barrier. A missing seal in `COPYING` refuses without another write. `SEALED` with a verified final can replay without moving or rewriting any bytes; `MOVED` requires the exact final and never repairs from a remaining stage. Identical copies are retained; mismatching copies are never chosen by timestamps, replaced, or discarded.
- **Exact metadata fences:** The same-database constructor composes the existing `RoomRenderIntents` participants. Reuse existing bounded job/lease/journal/pin queries and `JobLeaseFence` against the authenticated JobRow, not new lease rules. Scope the additive SQL CAS to exact transaction, kind, owner, attempt, phase, audit version, paths, v1 BLOB and previous null/seal fields. It accepts only `INTENT → COPYING → SEALED → MOVED`; only `SEALED` sets the identity triplet and `MOVED` must preserve it. Re-admit and compare the entire post-write row including BLOB content and recheck the live job/pin/lease and coroutine before leaving the transaction. Failures throw inside the transaction so the write rolls back. The file protocol checks the durable seal and copies, does an exact read-only phase fence before atomic move/final reuse, then the executor fences `MOVED` after supported sync and verification. The bounded local checkpoint reuses the last admitted row with monotonic clock, local liveness and coroutine cancellation; it does not read Room per chunk. No file work, hashes of files, or semantic verification runs inside Room. The sampled clock, SQLite commit, and atomic rename are separate boundaries.
- **Failure behavior:** A known cancellation or failing fence prevents new acknowledgment. Intended bytes may already be durable, and committed metadata may outlive a cancellation during delivery. If the file side effect won but the post-move fence fails, retain final and seal with the last truthful journal phase. Cooperative ownership remains necessary through the return boundary. Existing public `DerivedFiles` behavior, journal producer, lease acquisition and the Room schema are unchanged.

## Included original implementation commit

The handoff is a separate receipt-only commit after the verified implementation; use `git log --format=fuller 54b707bf6909593c0335515ac584e23a10c3dafa..work/pristinepage-export-render-files` for the handoff carrier's own Git identity.

```text
commit 3782095b72ff90cf8490ebae5b6031e8df21e3ee
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 08:43:20 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 08:43:20 2026 +0100

    feat(storage): execute leased render files through moved

    Join an exact frozen render intent to bounded private staging, synchronous closed-payload verification, an attempt-bound durable seal and an atomic move. Reuse the existing lease and journal guards; exact metadata CAS and readback stop at MOVED.

    Recover only the same live attempt. Check the expected target, cap and journal identities before opening recovered payloads, verify every surviving copy and retain bytes on refusal. Never rerun an unsealed COPYING writer or complete a render or export.

    Verification: 12 initial missing-seam JVM REDs and four missing-DAO SQLite RED cases, plus one conflicting-copy replay RED. Final focused: 4 SQLite and 53 data JVM passes. Full affected: 51 SQLite and 499 data JVM passes; unchanged schema.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

**Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).

## Verification and standalone reproduction

All commands ran from the worker worktree, serialized, with 420-second tool deadlines. Set process-local `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (JBR 21.0.8), `ANDROID_HOME=C:\Users\local-user\AppData\Local\Android\Sdk`, `GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`, and `ANDROID_USER_HOME=X:\local-workspace\.worktrees\pristinepage-export-render-files\.android-user-home` (the README's project-local pattern, not a write to its primary example). Leave `ANDROID_SDK_HOME` unset. C: had 50,022,330,368 free bytes at initial RED and 50,992,504,832 at the full JVM preflight; every JVM run rechecked the 8 GiB floor and Java ownership. The cached exact 8.13 extraction and `.zip.ok` came from the pinned local cache. Wrapper SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, cached launcher `56a30262647231bf3ab30b2bf090f5ecacff9329d3f1af1a1de73fd1ab4c64b4`, distribution ZIP pin `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`. The unchanged Gradle properties specify `-Xmx4g` and in-process Kotlin compilation. No network acquisition or settings change.

```text
python -B -m unittest discover -s validation/storage -p test_export_render_file_phases.py -v
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests *RenderFileExecutionTest
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests *RenderFileExecutionTest --tests *RenderFileIntentTest --tests *DerivedFiles*Test
python -B -m unittest discover -s validation/storage -v
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest
```

- **Initial assertion RED:** On the exact clean production base put the evidence-root `red-input-snapshot/RenderFileExecutionTest.kt` and `red-input-snapshot/test_export_render_file_phases.py` at their corresponding source/test paths in an independently authorized reproduction worktree. They are byte-identical to the tested intake (compare `red-inputs.csv`). Run the first two commands: Python 3.13.12 / SQLite 3.51.1 hit missing actual `@Query` in the four new SQL cases (14 failed assertions including subtests, six upstream cases passed because of a class alias imported into the module). Gradle exit 1 in 2m42s with 12 JUnit `Missing leased render file execution seam` assertions, zero errors/skips. Reflection only supplied the missing-class assertion on base; final JVM tests call the typed production seam. No compile failure or substituted SQL counted as RED.
- **Initial focused GREEN:** SQL 4/4 and JVM 48/48 (12 execution, 17 intent, 19 derived files); Gradle exit 0 in 1m08s. Later added tests cover database lease replacement even with a still-live local sample, real coroutine cancellation after atomic move, lagging-seal pre-payload cap ordering, `MOVED` missing-final non-repair, and a conflicting copy appearing during readback. In that supplemental focused run 16/17 passed, and `movedReplayRejectsAConflictingCopyAppearingDuringReadback` failed with `Expected INTEGRITY_FAILED`. The pre-fix version re-verified only copies already present. To reproduce that RED, retain the final tests but use the earlier `DerivedFiles` post-readback checks: `if (stageAttrs != null) verifyPayload(stage, attributes(stage), staged, checkpoint)` and `if (finalAttrs != null) verifyPayload(final, attributes(final), staged, checkpoint)`, instead of re-resolving/checking both paths. Restore the candidate to re-run GREEN; this fault injection intentionally violates the read-only ownership contract.
- **Final focused GREEN:** 4 focused real-DAO SQLite cases (unchanged SQL since that pass); 53 JVM cases across four XMLs (17 execution, 17 intent, 19 file layer), zero failures/errors/skips, Gradle exit 0 in 45s with 7/22 tasks executed. The JVM fixtures use real temp files, existing freeze and proof logic, acquired jobs, deterministic synthetic semantic readback and transaction-port doubles. They check journal-before-writer ordering, callback isolation, refusal, cap/identity ordering, lost fences and rollback, lagging and final-only replay, and exact copy preservation. SQLite runs the real scoped CAS extracted from `@Query` against exported Room v1 DDL, not a handwritten replacement.
- **Full affected GREEN:** 51 real host SQLite cases, exit 0, followed by 499 fresh data JVM passes across 52 XMLs, zero failures/errors/skips; Gradle exit 0 in 23s, 1/22 tasks executed (the actual test task). Focused and full counts overlap. Generated `ExportSnapshotDao_Impl.kt` SHA-256 `daf8d66a03b90444c6eec58d464be7074fd5ec6cee9471ac8a2fedb346943047`; schema `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d` and accepted plan `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa` unchanged.
- **Ownership:** Early RED daemon 30096 and initial GREEN daemon 34396 are attributed by the invocation-scoped daemon logs (`StopRequested`, finished, JVM shutdown hook) and post-run absence of Java processes; no native exit status was captured for those two. Gradle's registry-removal shutdown-hook diagnostic is retained, not equated with live Java. The supplemental RED, final focused and full daemons 36620 / 28048 / 36328 and captured Java children were positively observed signaled and exited 0 on retained process handles. Wrapper exit status was 1 / 0 / 0 respectively. Final evidence recorded no live Java at `2026-11-18T07:41:42.2977480Z`; no unresolved transport, timeout or ownership. The sole test/Gradle slot returns to the coordinator at handoff.

Evidence root is ignored and retained: `X:/local-workspace/.worktrees/pristinepage-export-render-files/build/render-file-evidence/`. Test fixtures under the ignored data `build` directory are also retained. The manifests pin exact logs, invocations, XML, daemon logs/exit records and input bytes:

| File | SHA-256 |
|---|---|
| `input-sha256.json` (254 pins, plus generated DAO hash) | `c6489052005efd1f8a6524b22b640a4a05ff0ebeba2194b89f564d4e35ba436d` |
| `evidence-sha256.json` (95 files) | `f338aa8826d0738e735f9275ea56f6a759292f8e450ab42418b9c20809a60d8b` |
| `red-jvm.xml` | `f052187d0b57dc56c817405bcbce3ad1213917ad891e529bac1d76c1011edfc5` |
| `red-sql.log` | `4bac216fbbfe2d8f6907bcf3cd5efed07fc9df907a7122bc5df0de9e43387c62` |
| `supplemental-red-exit.json` | `38e12a5965c34b42d9bdf246795a5a2baa1e52a610ab5bc051d4981157cb099d` |
| `full-exit.json` | `b83ce71d4c1af90e703462a8ffba3c37a6bf61ca92ce094b1bfdaa3e0a5c2901` |
| `full-sql.log` | `640d45cda8b4f4c206eac5bdb56f253bc2cc7e3838068717bc8be055fa4f870a` |

## Limits and adaptation

Independent persistence review and adoption belong to the coordinator. Compare the exact base and worker implementation commit against the current destination before replay. If destination code moved, adapt the intent/bounded job/phase participants as one same-DB boundary, retain the current lease authority and verified frozen proof, and re-run actual DAO-on-exported-DDL and focused/full affected JVM checks. In particular confirm no earlier render completion route reports a new checkpoint on cancellation, no work under Room performs file IO, and no path mutation ever switches the exact expected target. A later Android decoder adapter should own format/structure, extent, resolution, opaque RGB/color, complete decode, expected raster identity and memory-admission verification using only the read-only owned File; recovery must apply the same stable semantic oracle without the writer. Actual Room/WAL crash/reopen and rollback, device atomic rename/directory force semantics, commit-to-return timing, and real codec/runtime coverage require separate current-build evidence.

Out of scope: new attempt/takeover or cross-boot recovery, repair/reclaim/cleanup, full execution/stage provenance for `RenderedPage`, job completion, export assembly, real PNG/JPEG/PDF, `PRIVATE_READY`, app wiring, APK/signing/device/network/settings operations and adoption. No files were deleted to make space, no merge or push was performed, and the primary worktree was not modified. These host results do not establish private export readiness, scanner acceptance or a device/power-loss durability guarantee.

## Fix round 1/5 — fence destination setup before directory writes

**Motivation, scope and achieved behavior:** The reviewer Important finding was confirmed on exact clean fix base `839826351c6328e302a2565fa3c2211702fefa69` on the same worker branch and worktree, under the original unchanged brief. A DB owner/generation replacement during move-side `SEALED` seal forcing can escape the cached local deadline/liveness check. Previously the next exact metadata fence prevented rename and `MOVED` but ran after `createDirectories`, which could create and sync `renders/<renderId>/` under a now-stale attempt. The fix calls the same exact metadata-only authorization callback after seal re-verification and before destination setup. It also retains the second callback after setup and before rename or final reuse, so an invalidation during setup still cannot rename or publish. Simply moving the only callback earlier would lose that guarantee. Public `DerivedFiles.move` still uses no-op authorization, and the data executor, file formats, lease rules, SQL/DAO/schema and write semantics are unchanged. Clock observations, the DB transaction, directory operations and atomic rename remain separate; no atomicity or race-free guarantee is claimed.

**Reproduction and verification:** On the exact fix base, apply only the test-file diff from the implementation fix commit below (both test witnesses and the fixture hooks), leaving `DerivedFiles` unchanged, then run the first command. `databaseLeaseLostDuringMoveSealForceCannotCreateDestinationDirectories` reaches the real-file move-side force with journal `SEALED` and both destination directories absent; changes DB owner, attempt and generation without changing local liveness, elapsed time or lease deadline; observes `REVISION_CONFLICT`, the original exact `SEALED` row, stage bytes and seal bytes, and no final. Its destination leaf absence assertion failed with `Stale attempt created the destination leaf directory`. The second witness passed on the base and remains green: it replaces the DB lease during an authorized destination directory sync, would catch removal of the second fence, and proves no rename or `MOVED`. In that case retaining the already created empty directory is intentional; it is not cleaned up by this operation. After replaying the production hunk, run the last two commands.

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests *RenderFileExecutionTest
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests *RenderFileExecutionTest --tests *RenderFileIntentTest --tests *DerivedFiles*Test
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest
```

- **RED:** wrapper exit 1, 36s; 19 executed cases, one intended assertion failure, zero errors/skips. **Focused GREEN:** exit 0, 45s, 6/22 tasks executed; all 55 cases, zero failures/errors/skips. **Full affected GREEN:** exit 0, 25s, 1/22 tasks executed (test task); 501 fresh cases across 52 XMLs, zero failures/errors/skips. All three used the same pinned local process-only toolchain/homes and existing 4 GiB heap, with 420s deadlines. C: free bytes before each were respectively 50,852,380,672 / 50,853,023,744 / 50,848,346,112.
- **Unchanged SQL:** SQL was not rerun. Rechecked 252 unchanged pinned inputs and the unchanged generated DAO. The exact DAO is `c2ed2111c5b6271a7e9387dad752c25458e73a3f9d062b033acd5f8c64e72ece`; generated DAO `daf8d66a03b90444c6eec58d464be7074fd5ec6cee9471ac8a2fedb346943047`; SQL witness `14b6587bf98d40e0016662f794c59892e2007b57180addd32a7509f99ff77ee6`; schema `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`. The original SQL results remain evidence for those exact inputs, not new execution.
- **Ownership:** Daemons 19408 / 20828 / 10216 and every captured Java child were observed signaled on retained handles. All exited 0 except the initial RED wrapper (exit 1, expected). No live Java was present after the full run at `2026-11-18T08:01:45.0819043Z`. The sole Gradle test slot returns to the coordinator at handoff. No acquisition, deletion, signing, device, primary edit, merge, push, or adoption.

Evidence is retained under the original ignored `build/render-file-evidence/` with separate `fix-r1-*` names. The fix manifest references 74 actual invocation, log, XML, source-pin and process-exit files without duplicating original input manifests or receipts. Its SHA-256 and selected file identities:

| Evidence | SHA-256 |
|---|---|
| `fix-r1-evidence-sha256.json` | `a94c87cdc0d7c043299dd922945db004feb81d40be64027dbaf7dd9109c90d6a` |
| `fix-r1-red-inputs.json` | `06737460c109260dd271a25d7431a4830d6c4aec5d2ab9f9b7c8cabe0e291899` |
| `fix-r1-green-inputs.json` | `3930bdcea1c3db2759a8bf921eeaf0748f7466a7c4f18cdd8c32130bca5b79f9` |
| `fix-r1-red-xml/TEST-app.pristinepage.data.RenderFileExecutionTest.xml` | `8ec017e85c1e3baf7fcaddc4e78a70ced431c1878077a2d885383c4f3264a2a3` |
| `fix-r1-focused-green-xml/TEST-app.pristinepage.data.RenderFileExecutionTest.xml` | `0ce87cf93560f36c699061c9b71cbc36636dcfa913d31f5582191b247cc5a839` |
| `fix-r1-full-green-exit.json` | `d44693b58081a8bbf671c4c167c47e6b4d75397d0bcd0bb2de74a6b95de7e84e` |

**Included original fix commit** (the receipt-only carrier follows this commit, as shown in `git log --format=fuller 839826351c6328e302a2565fa3c2211702fefa69..work/pristinepage-export-render-files`):

```text
commit 4e90bfadb8a70ef935f374195057749270cc8e6c
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 09:02:42 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 09:02:42 2026 +0100

    fix(storage): fence render destination setup before directory writes

    A lease replaced during SEALED move-side seal re-verification could leave the destination directory behind before the pre-rename fence refused the old attempt. Reuse the exact metadata fence before setup and retain the existing fence after setup and before rename or final reuse.

    Real private-file witnesses verify both orderings, preserve the staging payload and seal, and keep the journal at SEALED on refusal. The setup witness failed as intended before the fix; 55 focused and 501 full affected JVM tests pass. The DAO, generated DAO, SQL test and Room schema are unchanged.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

**Adaptation and limits:** Independent scoped re-review should verify that no destination setup action precedes the fresh exact fence after move-side re-verification, and that authorization after setup still gates both rename and final reuse. If the destination branch has changed, preserve both boundaries and replay the two deterministic host witnesses before adoption. This fix does not change recovery/path/channel checkpoints (M1) or extend exact post-CAS/semantic mismatch coverage (M2); both remain separately deferred. It adds no Android, Room runtime/WAL, codec, readiness or crash-durability evidence.
