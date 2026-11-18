# feat(storage): bound ordinary terminal render reads

Owner: `U-TERMINAL-RAW-READ` (PristinePage T-04/U preparation worker; live claim, serialized test slot and integration belong to the coordinator). Canonical base: `X:/local-workspace` `main` at `2ae2eed9643c10b5a2718bf16ae465995d9ba05f`. Worktree: `X:/local-workspace/.worktrees/pristinepage-terminal-raw-read`, branch `work/pristinepage-terminal-raw-read`. Agent stack: OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).

## Motivation and achieved scope

The existing writer commits one ordinary render, but the future terminal reader must distinguish the exact terminal attempt and detect conflicting rows without materializing unbounded row contents or narrowing a stored SQLite INTEGER first. This milestone adds four internal queries to `data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt` and host tests in `validation/storage/test_export_terminal_render_read.py`. It adds no caller, interface, journal mechanics, index, migration, build/dependency/config change, or application wiring. Canonical `main` and its ledger were not changed.

## Contract and rationale

- `terminalRenderAttempt(jobId): Long?` searches the exact `Job` primary key and projects only a signed 64-bit SQLite INTEGER: positive `attempt`, raw INTEGER equal `leaseGeneration`, exact TEXT `PAGE_RENDER`/`SUCCEEDED`, and null owner/deadline/error. Missing or ineligible yields null. No immutable job or checkpoint content is projected or authenticated by this query.
- `terminalRenderIds(jobId): List<String>` uses the existing `index_RenderedPage_jobId` and `LIMIT 2`, without state or payload filtering. A non-TEXT or mis-sized ID produces `''` in place, not a dropped row. Exactly 36 stored bytes and 36 characters are raw extent checks for the ASCII UUID representation, not UUID grammar or identity proof. No result ordering is promised or needed for uniqueness.
- `terminalRenderReadIsBounded(renderId, jobId, pageId, recipeRevision, width, height, maxExecutionBytes): Boolean?` searches one render primary key. It returns null on absence and false on a present mismatch. It checks exact render/job/page, positive matching raw INTEGER revision and dimensions (the latter bound from Kotlin `Int`), positive INTEGER `byteCount`, and TEXT extents: IDs 36 bytes/chars including the producer source ID, cache key 80 bytes/chars with `render-cache-v1:` prefix, three SHA-256 fields 64 bytes/chars each, exact `renders/<renderId>/page.bin` path, and explicit `READY`/`OK`. Raw BLOB length is in `1..maxExecutionBytes`, inclusive; the caller supplies the writer-derived v1 cap (39,309), not SQL. These checks bound projected storage types and sizes; they do not validate digest grammar, semantics, actual encoded length or file contents, and are not a SQLite scratch-usage guarantee.
- `terminalRenderRow(renderId): RenderedPageRow?` is only a row projection, not safe in isolation. A future consumer must authenticate immutable job/frozen expectations, require the exact terminal attempt and exactly one non-sentinel canonical render ID, then require the raw guard to be true for that ID before typed extraction, all in the same Room transaction. Use the existing `renderIntentReadIsBounded` then `renderIntentRow` for the exact `(RENDER, jobId, attempt)` in that snapshot. The test's older COMMITTED, wrong kind/job decoys, and exact MOVED row show selector behavior, not commitment. Later composition must separately require COMMITTED and authenticate canonical intent/execution, the actual producer Source/Blob/byte tuple (the normalized-equivalent JOB pin is not producer proof), and sealed final bytes under stable ownership outside Room. Preserve frozen assembly order. No terminal readiness or `PRIVATE_READY` is established here.

The selective scalar reads keep the corruption-versus-absence distinction and avoid projecting an oversized execution or inspecting irrelevant job fields. The full row query remains separate to avoid turning the guard into a standalone entity reader. The host fixtures execute the real annotated statements on exported v1 DDL with FKs enabled, without copying the production SQL into tests. The generated Room implementation was inspected but the new methods were not invoked in Android.

## Reproduction and evidence

Created the worktree after checking clean exact `main`, an absent target branch/path and `/.worktrees/` ignore: `git -C X:/local-workspace worktree add -b work/pristinepage-terminal-raw-read X:/local-workspace/.worktrees/pristinepage-terminal-raw-read 2ae2eed9643c10b5a2718bf16ae465995d9ba05f`. The serialized slot preflight at `2026-11-18T21:06:21.2978760Z` independently found no Java/Gradle wrapper, 24,576,733,184 free bytes on C:, exact clean canonical `main`, and the two intended untracked drafts. Run the following from the worker root, in sequence under the sole slot; set the process-local JBR/SDK/Gradle paths from canonical README, use this worktree's ignored `.android-user-home`, unset `ANDROID_SDK_HOME`, and verify locally cached Gradle before invoking its wrapper.

- Before production: `python -B validation/storage/test_export_terminal_render_read.py` exited **1**. Nine cases ran; all 60 failures were absent-query assertions across eight cases, not behavioral observations. The existing exact-attempt journal case passed; no fixture errors. The untouched test draft then ran against the four additive queries.
- After production: same focused command exited **0**, **9** passed. `python -B -m unittest discover -s validation/storage -p 'test_*.py' -v` exited **0**, **73** passed including the focused nine. SQLite witnesses isolate exact job state/counters including values wider than `Int`, index/limit and malformed sibling cardinality, embedded NUL and character/byte bounds, raw REAL width/Int-alias height, exact path, non-READY/non-OK refusal, and cap-correct BLOBs. No unguarded `terminalRenderRow` invocation.
- `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest` exited **0** in 1m14s, all 22 tasks executed including `:data:kspDebugKotlin` and the JUnit task. The worktree had no prior reports: **538 fresh data JVM tests in 53 XMLs**, 0 failures/errors/skips. Ten Kotlin warnings are in unchanged files; stderr was empty. Generated `ExportSnapshotDao_Impl.kt` uses `getLong` for attempt, one `getText` per ID, one nullable boolean for the guard, and full BLOB/Int getters only in the separate row query. Exported Room v1 schema bytes match the base and prebuild hash. Two evidence-only PowerShell attempts failed on date conversion and an unavailable `-DateKind` switch; parsing the raw ISO start resolved reporting without another build.
- The Windows `cmd.exe` wrapper (PID 41320) and seven captured Java process handles (wrapper JVM, single-use daemon, test executor, three identified children and one short-lived Java process lacking a command-line capture) were signaled and each exited 0. Independent postflight found no active Gradle wrapper or Java process. This milestone did not exercise Android runtime, terminal file/codec, app/assembly, APK or device behavior; no acquisition was performed.

Evidence paths are relative to the worker: `validation/raw/export/ordinary-terminal-raw-read-20261118/`. `evidence-sha256.txt` lists 66 SHA-256 entries (logs, invocations/exits, generated DAO and fresh XML copies); its own SHA-256 is `fde96722d50fca6705401edc0f518a67308c977327c9d4ff40fe1721856b511d`. Useful anchors:

- RED `sql-red.log` `71a916cd5684656cbda42cf36e10b1f8630445ef919df6958d8ca6bb2d49d794`; focused GREEN `sql-green.log` `216d8142fcf86dc9ad57c977f37be531c14b8b38cc9e1dd6d44c43ade99c731d`; suite `sql-storage.log` `4af8969cd763ca5a81b29b1a2c53e9e1bb97e6262ebe344ebe22ee32d4515329`.
- `gradle-data.stdout.log` `645e2ceafcc709c068f724cc72a3dfdacbf55de91e60c44f75411163281b0fc7`; `room-jvm-evidence.json` `85d3aaebb0d64318eec1541f715da3be47a45e1e5ae565090ac25274f89fbf09`.
- Room v1 `1.json` `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`; generated `ExportSnapshotDao_Impl.kt` `1e5c5277e0c05a0536de2f6f512ddeae61ebbef9cd1d13eccc581450338e1ce5`.
- `gradle-processes.json` `a887487880fa9ea367bc9d05d093594bb55a01912094d2d4bc6a12647df5a3ab`; `slot-postflight.json` `e902569d7d672cfd1169f0e689310438fc27a9c22a3214b43696fcdd86244b4b`.

## Included code commit

Base `2ae2eed9643c10b5a2718bf16ae465995d9ba05f`; verified code head `2683b493c26eb38b3b347f044ea6d0a1136cfe15`. This handoff is a separate receipt commit; no changes to the tested inputs follow the code commit. Independent review and any destination replay are coordinator-owned.

```text
commit 2683b493c26eb38b3b347f044ea6d0a1136cfe15
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:11:32 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:11:32 2026 +0100

    feat(storage): bound ordinary terminal render reads

    Keep terminal attempt and render discovery scalar before admitting one exact render row. Preserve malformed sibling cardinality, bind the writer cap, and verify actual DAO SQL against exported Room v1 including exact-attempt journal selection.

    Agentic harness: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol)
```
