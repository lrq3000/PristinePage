# fix(storage): bound render job reuse during export freeze

**Initial motivation:** In T-04/U, `U-FREEZE-REUSE-20261118` replaces the two remaining broad producer reads after both freeze preparations and fenced render completion were independently integrated. The old indexed key lookup still extracted a whole `Job` and the pin lookup extracted `SourceReference` audit data; the producer also read the retained `Source` and `SourceBlob` without raw admission. A failed read or malformed match must not turn into a new job.

**Authority and inputs:** `ea66b1e2b355cf1caa736ed2e3f78d250efca5f5:docs/specs/TODO.md`, both paragraphs of `U-FREEZE-REUSE-20261118`. Candidate branch `work/pristinepage-export-freeze-reuse` in `X:/local-workspace/.worktrees/pristinepage-export-freeze-reuse`, clean exact base `6f8faf374600a50f05fd5b8b80b5cf2ed640d9ee`. The accepted implementation plan SHA-256 is `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`; exported Room v1 DDL JSON SHA-256 is `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`. Neither changed.

## Final purpose and implementation

- Inside the same writer transaction, derive the same v1 `RenderJobIdentity` once. Search the unique exact binary `idempotenceKey` index and project one `jobId` using SQLite TEXT type and both 36-byte/character extents; return non-null empty sentinel on malformed presence. Room returns null only for no row. Validate lowercase canonical UUID in Kotlin before binding a by-ID query and never echo a malformed ID. Only an exact miss inserts the unchanged PENDING job and its JOB pin.
- A key match must pass the existing scalar `renderJobReadIsBounded(jobId, expectedInputBytes)` using the actual v1 canonical input extent, including the raw kind and null job snapshot/ordinal constraints. Then read the existing five-column `renderJobIdentity` and compare ID, kind, key, digest and bytes. Missing or contradictory guard/projection is `render_job_identity_mismatch` with no replacement. The public transaction port requires the real query methods; the already-shared restricted row DTO is visible to that port. Existing read/lease SQL semantics are unchanged.
- Use existing `boundedRenderJobPinSourceIds`: exactly one canonical JOB source ID, with any malformed item retained as evidence and a maximum of two rows from the owner index. An empty pin list, absent Source or definitely missing canonical Blob link returns `render_job_source_missing`; multiplicity, an invalid ID, a malformed chain or a contradictory admitted projection returns `render_job_source_mismatch`. Use `sourceReadIsBounded` before either typed row; the existing scalar `sourceBlobLinkIsMissing` is only a diagnostic after a false guard, never admission or an entity fallback. Use the same typed normalized/immutable-link predicate as `FrozenRenderJobProof`, which checks the admitted Source/Blob relationship, valid normalized inspection metadata and matching frozen hash, dimensions and conversion. Where the IDs are equal, preserve the existing Blob linkage/byte-hash constraints; where byte-distinct originals normalize identically, leave the retained JOB pin unchanged and pin the page's own source separately.
- No Job state, lease, checkpoint, error, timestamps or pin audit payloads are read or rewritten by reuse. No alternate original is opened or verified here. No file I/O, new suspension, or new lease/state policy enters the writer transaction. Refusal escapes it and rolls back the snapshot, pages, new jobs, pins and counters, including earlier ordinals. The existing ordinary v1 snapshot and assembly encodings, page order, file-proof/admission placement and disabled app/profile remain unchanged.

## Reproduction on the host

Reproduce RED at the exact base by applying only the host test patch `red-jvm/tracked.diff` from the retained evidence directory below and taking `test_export_freeze_reuse_bounds.py` from the implementation commit. RED working SHA-256 values: Kotlin test `d7a403c9275a75fcbd1d659efaae4eb00a83f69562518ea7a029f562eb48e7cc`, Python test `dbe7275bd0c8f23bae94a8bcb831f2cfc87f5db04f102b6c5248f67e1d3b5d78`. GREEN uses all implementation paths. Run the affected checks sequentially with the sole test slot.

```powershell
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests app.pristinepage.data.ExportSnapshotTest
python -B -m unittest discover -s validation/storage -p test_export_freeze_reuse_bounds.py -v
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests app.pristinepage.data.ExportSnapshotTest --tests app.pristinepage.data.ExportSnapshotIdentityTest --tests app.pristinepage.data.ExportAssemblyInputTest --tests app.pristinepage.data.FrozenRenderJobProofTest --tests app.pristinepage.data.FrozenRenderReadTest --tests app.pristinepage.data.RenderFileIntentTest --tests app.pristinepage.data.RenderFileExecutionTest --tests app.pristinepage.data.RenderCompletionTest
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest
python -B -m unittest discover -s validation/storage -p 'test_*.py' -v
```

Process-local `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`, `ANDROID_HOME=C:\Users\local-user\AppData\Local\Android\Sdk`, `GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`; use the assigned worktree's ignored `.android-user-home` as `ANDROID_USER_HOME` to avoid writing in primary, and unset `ANDROID_SDK_HOME`. Each run rechecks more than 8 GiB free on C:, no active Java, four workers, 4 GiB heap, and a 420-second deadline. Verified the already provisioned `wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d` extraction and `.zip.ok`, Gradle wrapper SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, extracted launcher `56a30262647231bf3ab30b2bf090f5ecacff9329d3f1af1a1de73fd1ab4c64b4`, and pinned distribution ZIP `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` from the verified acquisition receipt. The ZIP itself is not retained or reacquired; strict offline Gradle starts only after these checks.

## Observed RED gates

- **Producer JVM:** Gradle exit 1 after 2m47s (22 tasks executed); 45 fresh tests in one XML, eight assertion failures, no errors/skips. A false raw-admission fixture and the otherwise valid normalized-equivalent fixture reached a forbidden wide Job read; invalid/extra pin fixtures reached wide pin projection; a missing raw Source reached forbidden typed extraction; wrong same-extent input/digest lacked the guarded identity sequence; a malformed ID was misclassified as `STORAGE_FAILED`; and both the unavailable projection and the late guard case actually froze instead of refusing. No production files were changed before this run.
- **Missing-query baseline, not producer RED:** SQLite exit 1, five methods attempted; three existing-query methods green, nine assertion reports exclusively for absent `renderJobIdByKey` in two methods (including subcases). SQL does not project a refused body into Python. The final cases also pin exact probes to the Job unique key/primary key, SourceReference owner-range index, and Source/Blob keys.
- The RED JVM runner initially represented an empty PowerShell PID list as `[null]`, causing a spurious runner postcheck error after XML and exact Gradle exit were recorded. All four retained Java process handles were signaled and a follow-up enumeration had no live Java. The first SQL runner attempt stopped before spawning Python because multiple Python candidates were returned; selected the first exact application explicitly and retained the successful launch of the actual SQL RED in its own directory. These runner issues are not product failures; no cache, network, process kill or host settings change.

## Focused verification

The first implementation run reached JUnit (134 tests, six failures, Gradle exit 1). The strict test probe included the initial fresh freeze's key lookups in the following reuse attempt's exact sequence. All six failures showed extra initial `id` events and already reached the expected refusal. Reset only the six test traces between fixture setup and the observed attempt. The final focused run passed 134 JVM cases in eight fresh XML files (42s, 3 tasks executed, 19 up-to-date), and five SQLite cases in 0.048s; both exited 0. Generated KSP `ExportSnapshotDao_Impl.kt` SHA-256 `ae53b21f19df0bb9e18c5c73daabcfb1dd0a6b90d0f3a1ab57c10fc84ccf5fd5`: one `getText(0)` for the nullable key projection, a nullable scalar exact by-ID gate, four text values plus one BLOB for restricted identity, and one `getText(0)` per pin row. This is host inspection of generated code, not native execution.

## Full affected verification and evidence

Unfiltered `:data:testDebugUnitTest` passed **538 fresh cases** in 53 XML files (23s, 1 task executed, 21 up-to-date; zero failures/errors/skips). Full host storage discovery passed **64 SQLite cases** (0.459s, exit 0). Full-run preflights observed 49,543,708,672 and 49,438,965,760 free bytes on C:. The full JVM command/PID `35028` exited 0; observed Java PIDs `18808`, `472`, `22860`, `27920`, `28600`, `32560`, `16644` had signaled handles after return. The full Python PID `3928` exited 0 and no Java remained. Earlier JVM runs' retained process handles likewise report signaled. The worker releases the sole test slot at this handoff.

Ignored evidence is retained in the assigned worktree at `validation/raw/freeze-reuse-20261118/`. Each completed stage pins command/environment, inputs, working diff, logs, process exit/creation observations, and fresh XML where applicable. All stage-manifest files were individually rehashed; the full JVM input manifest was also rechecked against working files other than this subsequently updated handoff. The generated DAO was copied under `generated/`.

| File relative to evidence root | SHA-256 |
|---|---|
| `red-jvm/evidence.sha256` | `ecfb60c3f8c705d04e61b7ac0fe832d68c2656770c665c2eb096149f583c14ef` |
| `red-jvm/xml/TEST-app.pristinepage.data.ExportSnapshotTest.xml` | `1d0124ae161990cbc35de63ce77c6e41a6c7e94f7b4e1cf464adbb0a9d5a42c8` |
| `red-sql/evidence.sha256` | `9fc67047d2f877ac02c450f978dc8d5de304e3afdf9589aed42fdd4b43807e38` |
| `focused-jvm/evidence.sha256` (trace setup failure) | `22b62e52e4d2608cb365d45eab2fffbac12e09fd6b292a01c7ceefc8da9cb1b8` |
| `focused-jvm-retry/evidence.sha256` | `cafc49b11656044058d6e3eef10d2283f04d38d2c4f14a648793da26773e45bb` |
| `focused-sql/evidence.sha256` | `b32859feaf1ea77a529f1393551878defc1019bcbc703fcfa7eaddc9ef001be6` |
| `full-jvm/evidence.sha256` | `0e31f68548aacde1a72e3c8c8458e02660475a086099817a31bf1daf0c357c0f` |
| `full-jvm/inputs.sha256` | `4bce23a2f6baf63b106857a40ea8fa55b2435da9caf98c83c19c4ea91e2d6cd3` |
| `full-jvm/xml/TEST-app.pristinepage.data.ExportSnapshotTest.xml` | `0cef4f1aeda7ef11c99973f990dc77b9b4dad41d89087f9ba240f5dce500d0e3` |
| `full-sql/evidence.sha256` | `cd62606d93e4151e04cf0cfc8ef516712f36ff93611dfa83c9220b42e6af18fe` |
| `run-host.ps1` (final local evidence runner) | `0a4f9a252b46f3d35ed901e2dfd2bdc461a1c49c821e04c656d06c8214af1f7b` |

## Included implementation commit

```text
commit 0d8df29cd10412d78c095245db628c83a69a7c3a
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 11:08:59 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 11:08:59 2026 +0100

    fix(storage): bound render job reuse during export freeze

    Replace broad Job and SourceReference reads with indexed scalar admission, restricted identity and an exact bounded pin proof. Refuse malformed matches without inserting replacements and preserve equivalent normalized owners, v1 assembly order and atomic rollback.

    Verify producer REDs, actual v1 SQLite query plans, and focused and full affected host suites.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

This handoff is the only content of the following documentation commit; its own exact tip is reported separately.

## Review and replay

Coordinator: independently review the implementation and verification before adopting; replay the implementation and handoff on the then-current destination and run the focused then full affected JVM/SQLite checks there. If the destination already has the same indexed projection or owns a refactored proof, reuse it rather than add a second policy; record each adapted or already-satisfied hunk. Preserve the exact key comparison, malformed-present sentinel, pre-projection state, limit-two cardinality including invalid rows, normalized binding and rollback semantics. Keep strict test ports (fresh-only fixtures fail explicitly on unexpected reuse reads) and check generated Room cursor shape as well as `EXPLAIN QUERY PLAN`. Neither host layer establishes Android CursorWindow, platform rollback/reopen, alternate-original private bytes, lease authorization, terminal assembly/codec behavior, device behavior or T-04/T-11 acceptance. No activation, native compilation or run, schema/build/dependency/signing/APK/device/network/cleanup/merge/push or nested review was performed here.
