# test(export): verify ordinary terminal reader and PNG RGB bridge together

**Initial motivation:** `U-EXPORT-JOIN-CHECK`, under the existing T-04/U continuation and P-01/U-05 authority, checks whether the independently prepared terminal execution-v1 reader and the PNG codec with actual decoded-RGB digest can coexist on the same pinned `main` without changing the existing writer identity. A compressed PNG hash is not the canonical raster hash; the expected raster is a comparison target, not a source of readback proof. Conversely, a valid execution envelope and matching stored metadata do not prove a terminal producer or authorize access to a file.

**Final purpose:** Verify the four existing changes together on a fresh, registered, detached local worktree, without activating any consumer or changing behavior beyond the supplied sources. The target is `main` at `2ae2eed9643c10b5a2718bf16ae465995d9ba05f`, not a merge target under this assignment. The verification checkout is `X:/local-workspace/.worktrees/pristinepage-export-join-check`; the four-replay tested tip is `b9cacd0f2b57bbbf49b83f6084466c8d08cf136b`. This handoff-only receipt is self-excluded from the included replay commits: obtain its full reachable HEAD with `git -C X:/local-workspace/.worktrees/pristinepage-export-join-check rev-parse HEAD` and confirm the `detached` registration with `git -C X:/local-workspace worktree list --porcelain`.

## Reproduce the candidate

1. Use the canonical instructions and accepted plan from the pinned base. Confirm a clean, unchanged canonical `main`, the coordinator's serialized slot, no remaining previous build process, a protected and absent destination, and at least 8 GiB free on C:. Create exactly `git -C X:/local-workspace worktree add --detach X:/local-workspace/.worktrees/pristinepage-export-join-check 2ae2eed9643c10b5a2718bf16ae465995d9ba05f`. Do not reuse this evidence worktree or any other worker checkout for a new attempt.
2. In the detached worktree only, replay in the order below with `git -c commit.gpgsign=false -c core.protectNTFS=true cherry-pick -x <source>`. Reproduction may yield different commit IDs because committer timestamps are not part of the source. Verify the per-step bytes and the dry merged writer, not just whether Git found a clean merge.
3. Run the two commands in **Verifications** once on the exact composed destination. Preserve raw outputs and fresh XML, identify the JVM golden and both new suites by case name, distinguish compiled Android tests from executed tests, verify v1/schema/base protections and reconcile the wrappers and scoped build processes before releasing the slot.

| Sequence | Exact source | Exact replay |
| --- | --- | --- |
| Terminal reader onto `main` | `b1dd30aadea6f1aa428a7a63d4eea55d306f7886` | `f808890c7656b9c3d10269d7d582038d4e78778f` |
| Standalone PNG codec replay | `48d8c842c4ebe06a48beaa9262f26fb085763b16` | `6a954b62d1df7b469445ee27f176c903c8606657` |
| Actual decoded PNG rows → raster digest | `cd121818505f1ca1e16ec9c285255fe4917854ea` | `718f3c6943f9551edfceaa6485e58d55d548da7f` |
| PNG/RGB bridge handoff | `f4829f9e0f1c13c6c49b6ee07c4851c24a6156a9` | `b9cacd0f2b57bbbf49b83f6084466c8d08cf136b` |

The source tips belong to the existing `work/pristinepage-terminal-reader-main` and `work/pristinepage-png-rgb-bridge` branches. The terminal reader's original worker tip is `ebc31502c2a8550f1c403459b7c75a0149b94ccb` (implementation `19228ae8969a5c8335649885d896a2b2021547e9`, base `b950380815763fea7657fc82d9ca0f1bb9232f58`). The standalone codec's original worker tip is `9b9e3dc3ee42144e5d5eb3bfdd9b0d7cf8c4aa23` (base `ea66b1e2b355cf1caa736ed2e3f78d250efca5f5`). Prior intent, original dispositions and evidence remain in the [terminal reader destination handoff](../storage/ordinary-terminal-execution-main-handoff.md), [standalone codec handoff](ordinary-png-codec-handoff.md) and [PNG/RGB bridge handoff](ordinary-png-rgb-bridge-handoff.md). Counts inside the replayed commit messages describe their original runs, not this composition.

## What changed, how, and why

- The execution reader binds one already raw-admitted, stable execution BLOB to a verified frozen job and supplied stored raster metadata. It returns detached actual ordered stage observations and warnings, plus an explicitly unproven Source/Blob/byte-SHA tuple. Exact source/blob aliases must agree; normalization-compatible reuse does not substitute the consumer or original JOB pin for the actual producer. It never reconstructs the execution from a recipe or builds a `RenderResult`.
- The writer remains the v1 envelope authority: `pristinepage-ordinary-render-execution-v1` + NUL, big-endian signed Int/Long framing, a writer-derived maximum of **39,309 bytes**, 3–16 stages, 0–16 warnings per list, 1–128 ASCII bytes per token (`[A-Za-z0-9_.:-]`), and only `OK`, `EXACT_IDENTITY`, `EXACT_PERMUTATION` stage outcomes. The reader shares the tag, bounds/extent, token and outcome checks, exact `page-render-v1:` plus lowercase SHA-256 pre-execution-key validation, full-envelope `sha256` and `cacheKey` helpers. It requires `EXPORT`/`FAIL`/`OK`, positive versions/dimensions, correct stage-size chain and exact EOF, and refuses disagreement with frozen binding, stored execution/cache identity or raster metadata. The cache remains `render-cache-v1:` plus SHA-256 of UTF-8 `pristinepage-ordinary-render-cache-v1` + NUL + pre-execution key + execution digest. Stored v1 fields, order, and identity domains are unchanged.
- The writer delegates only `rasterSha256` to data-owned `OrdinaryRasterDigest`. One SHA-256 grammar hashes UTF-8 `pristinepage-ordinary-render-rgb8-v1` + NUL, two positive big-endian 32-bit dimensions and active opaque RGB samples in row order. The accumulator borrows one primitive ARGB sample at a time, excludes stride padding, checks cancellation per block and requires exact row coverage and one finish. Its at-most-12 KiB scratch block drops after the last row. The writer retains its final cancellation checkpoint and neither imports `ByteBuffer` nor redeclares the raster grammar.
- The unactivated PNG writer and readback candidate retains a bounded structural/privacy pass and pinned Android codecs (`Bitmap.compress(PNG)` and strict `ImageDecoder`); API below 28 refuses for this candidate. Under stable closed-file and caller-owned complete-raster assumptions, `OrdinaryPngVerifier` scans the encoded container, decodes unscaled opaque software sRGB, feeds the **actual** validated samples into the shared accumulator, releases native phase resources, rescans the closed bytes, compares extent and compressed SHA-256, checks cancellation, then finalizes the raster receipt. Neither the expected raster digest nor the compressed digest is used as the decoded proof. The existing budget remains provisional accounting, not measured native memory. This source-level ordering observation is separate from any Android runtime evidence.

### Sole overlap and exact replay

The source ranges intersect only at `data/src/main/kotlin/app/pristinepage/data/model/OrdinaryRenderExecution.kt`. The strict two-way `git apply --check --whitespace=error-all` passed for steps 1, 2 and 4; step 3 refused at the reader-modified context around line 40. A dry `git merge-file -p --diff3` exited 0. Its text and the actual three-way cherry-pick were byte-for-byte identical to the independent composition: exact reader text except removal of the unused `ByteBuffer` import and `rasterDomain`, then substitution of the bridge's exact `rasterSha256` KDoc/body while retaining the reader-visible following `sha256` helper. No unmerged entries or manual follow-up edits. The writer SHA-256 is `cff7049dd1b3f349056ec23ce97fcdc512844f85bb658315a4e191455028412a`; all other affected destination blobs match the appropriate source at each replay step. Raw source patches, strict check diagnostics, dry inputs/output and per-step replay receipts are retained.

## Verifications

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest :image-android:testDebugUnitTest :image-android:compileDebugAndroidTestKotlin
python -B -m unittest discover -s validation/storage -p "test_*.py" -q
```

Only the required Android test compile task was requested; no independent `:data:compileDebugAndroidTestKotlin` invocation. The process-local toolchain is JBR `21.0.8` at `C:\Program Files\Android\Android Studio\jbr`, SDK `C:\Users\local-user\AppData\Local\Android\Sdk` (platform 36 revision 2, Build Tools 35.0.0), and approved cache `C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`. Android preferences stay in this worktree's ignored `.android-user-home`; `ANDROID_SDK_HOME` is unset, and both SDK aliases use the same installed SDK. The existing Gradle 8.13 extraction, `.zip.ok`, launcher SHA-256 `56a30262647231bf3ab30b2bf090f5ecacff9329d3f1af1a1de73fd1ab4c64b4`, wrapper JAR/properties, SDK/JBR files, dependency cache roots and locks were verified before launch. No download, dependency/configuration or signing operation. The existing 4 GiB heap, in-process compiler and four-worker cap were retained.

- [x] **Gradle:** Exit 0, 153.093 seconds wrapper time, 56/56 actionable tasks executed. Fresh XML: **556 data tests / 55 XMLs** and **157 image-android tests / 17 XMLs** (**713 / 72**), zero failures, errors or skips. Compiler warnings were confined to unchanged `DirectorySync`, `DocumentCommandsTest`, `ExportSettingsTest`, `FileOperationOwnersTest` and `RepositoryContractTest`. The raw task output identifies both JVM tasks and the Android test Kotlin compile without `UP-TO-DATE` or cache markers on those tasks.
- [x] **Fresh writer/reader/digest witnesses:** `RenderCompletionTest` 13 passes, including `executionGoldenRecordsActualOrderWarningsAndRasterWithoutOwningTheImage`, XML SHA-256 `db51d19bf01e05afd9233b74d7698652cde113d759d4fccde52d66202d2c4350`; `OrdinaryRenderExecutionReadTest` 11 passes including `readsIndependentV1Vector`, `readsUnchangedWriterBytes`, and `readsWriterAtCombinedLimitsAndRefusesOneExtraByte`, XML `103ddcc90b537775ff6200b9690db14564c84762f28b655fde03f84a3f43ca4c`; `OrdinaryRasterDigestTest` 7 passes including the independent unsigned RGB vector and 4097-pixel row cancellation boundary, XML `82f78175ec16b9b97638e07b31b8148bbff13bb101bfecf21f120e29aa2a4a9e`; `OrdinaryPngContainerTest` 10 passes, XML `107c365fad24796db1075b19e41e916aacfa83b9ad09781f29099c946d1d30e6`. These cases are included in the totals, not additional runs.
- [x] **V1 compatibility:** The unchanged `RenderCompletionTest` Git blob `1850a2e61674c7d4c59677d120a78fa0da72c23e` confirms the independent 791-byte envelope golden: execution SHA-256 `6b5e82b5f7f7939fa67f57d0c9b1090a93807be035549904ad9c9464cf4bf065`, raster SHA-256 `69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4` and cache key `render-cache-v1:2d64c0a4ac5078042f43e8f2ffa9cf70b5ddbb479f291fd465bf3127721f5742`. The reader also compares the writer's entire output against the independent vector. Room v1 schema is byte-identical to base (SHA-256 `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`, Git blob `9ccfdb17bbb6d9a12634ecd2ea1db58a6a6a74db`). DAO, app, build inputs/dependency locks and canonical specs did not change; accepted plan SHA-256 remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.
- [x] **Host storage:** 64 Python checks, exit 0, `Ran 64 tests in 0.465s` / `OK`, with raw stdout and stderr retained separately.
- [x] **Android compile only:** `image-android/build/tmp/kotlin-classes/debugAndroidTest/app/pristinepage/image/export/OrdinaryPngExportTest.class` and its three associated nested class files were generated; the fresh main class copied into evidence has SHA-256 `ef59a90d58fafcee0766293b7546d98ffda153e07a405da4ce6e453697c4b0d7`. The source contains 17 `@Test` cases, zero executed. No APK was produced.
- [x] **Scope and slot release:** `git diff --check` passed for the replay range and the receipt. 730 tracked inputs were hashed before the build and all were revalidated after it. Prelaunch free space was 24,751,251,456 C: bytes, above the 8 GiB floor, with no active build tools. The historical positive-exit receipts were checked separately; an inline registration WQL quoting error was corrected by independent pre-registration, pre-replay and prelaunch observations. Gradle wrapper PID `38484` and Python test PID `39204` exited 0. Windows process handles showed `WAIT_OBJECT_0` and exit 0 for both wrappers and all observed children (12 Java, one AAPT2 and 11 console hosts). A separate process inventory also found neither wrapper, the runner, nor outer shell alive. Slot released at `2026-11-18T21:02:33.826997+00:00`, with 24,574,357,504 C: bytes free and no Java/Javaw/build tool observed. No later worker build or host test.
- [x] **Original checkout and histories:** `main` and its root checkout are still clean at the pinned commit; all 29 original refs and original worktree registrations matched the initial snapshot. Two unrelated new branches/worktrees appeared during the attempt (`work/pristinepage-terminal-raw-read`, `work/pristinepage-coordination`), logged separately and not created or modified by this check. All four previous evidence anchors and the full 108-entry bridge evidence manifest matched; prior evidence remains in its original locations.

**Raw evidence:** `validation/raw/export/ordinary-export-join-check/` in the detached worktree is ignored and retained in place. `test-evidence.sha256` pins 135 files (SHA-256 `6ed05b583de3e90739babfa32a3ccc8cc5caa0cf982d047e49cd9eecd7cebc13`). This includes `gradle/` and `storage/` exact argv, CWD, admission, stdout, stderr, exits and process records; all XML and the copied compile-only class; dry and actual replay records; source/toolchain hashes; and `jvm-summary.json`, `android-compile-only.json`, `slot-release.json`, and `outer-process-exit.json`. The slot receipt SHA-256 is `9dde3137a59989fdfa1237cf013f0339d0bf485fcce213b8aaedf7f4dec9586d`. Final checkout protection is separately recorded in `final-protection.json` after the receipt commit.

## Native and product limits

No Android test executed, and no APK assembly/signing, emulator, physical device, provider, or publication operation was authorized or performed. Actual Android PNG writer chunk inventory, RGBA/alpha/profile handling, pre/post closed-byte check under controlled runtime inputs, resource/cancellation behavior, native memory and tagged-source metadata nonpropagation still need authorized runtime evidence. Preserve the original reader coverage limitations (isolated extent-cap and full safe error-envelope oracles), and the standalone PNG review's stage discrimination, streaming/chunk-count witnesses and unobserved reservation-object OOM issue. No review follow-up fixes were attempted.

No new DAO or production wiring was added. Before consuming a persisted terminal result, bind the exact `SUCCEEDED` job, unique `READY` render and exact `COMMITTED` producing journal attempt with guarded typed Source/Blob reads, stable operation ownership and independent sealed-file byte/raster proof outside Room; an execution digest or a normalized-compatible pin alone is insufficient. Actual runtime PNG proof and this terminal join are separate gates. No seal/durability claim, callback integration, profile activation, assembly-order proof, complete export/`PRIVATE_READY`, EXP-02, T-04/T-11 or product acceptance follows from this check. Any future target movement warrants rechecking the overlap and rerunning affected checks. Adoption and independent boundary review remain with the coordinator; there is no merge, push, branch cleanup, nested delegation, discussion or index access.

## Included commits

<details>
<summary>Full git descriptions and authorship of the four replay commits</summary>

```text
commit f808890c7656b9c3d10269d7d582038d4e78778f
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 21:27:42 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 21:56:32 2026 +0100

    feat(data): prepare bounded terminal execution reader for main

    Replay the independently reviewed execution-v1 reader byte-for-byte onto the exact main base. Bind observed stages and raster metadata to the frozen job without substituting the JOB pin for the unproven producer.

    Preserve the original implementation and review dispositions. Destination checks: 549 fresh JVM and 64 host storage tests; terminal file/journal composition and actual export remain separate.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).

    (cherry picked from commit b1dd30aadea6f1aa428a7a63d4eea55d306f7886)
--
commit 6a954b62d1df7b469445ee27f176c903c8606657
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 21:34:44 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 21:56:33 2026 +0100

    feat(image): replay reviewed ordinary PNG codec candidate

    Replay the exact five source/test paths and original handoff from 9b9e3dc3 on current main. Preserve the standalone candidate and keep the codec unactivated; the decoded RGB proof is a separate continuation.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).

    (cherry picked from commit 48d8c842c4ebe06a48beaa9262f26fb085763b16)
--
commit 718f3c6943f9551edfceaa6485e58d55d548da7f
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 21:43:54 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 21:56:34 2026 +0100

    feat(export): hash verified decoded PNG rows with the ordinary raster grammar

    Share only the data-owned v1 raster hashing grammar. Stream the actual opaque Android samples after exact comparison; complete the receipt only after the post-decode byte identity matches. Keep the existing encoded proof, budget accounting, and execution envelope.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).

    (cherry picked from commit cd121818505f1ca1e16ec9c285255fe4917854ea)
--
commit b9cacd0f2b57bbbf49b83f6084466c8d08cf136b
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 21:45:06 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 21:56:35 2026 +0100

    docs(export): record ordinary PNG RGB bridge handoff

    Pin exact replay and tested sources, assertion RED and offline JVM/compile outcomes, serialized host release and the remaining independent and Android runtime gates.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).

    (cherry picked from commit f4829f9e0f1c13c6c49b6ee07c4851c24a6156a9)
```

</details>

**Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`); thinking level was not exposed.
