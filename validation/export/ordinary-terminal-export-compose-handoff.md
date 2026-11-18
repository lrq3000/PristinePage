# docs(export): record terminal export composition verification

**Initial motivation:** `U-TERMINAL-EXPORT-COMPOSE` under P-01/U-05 verifies whether the reviewed ordinary-export reader, PNG bridge and hardening, bounded DAO, terminal metadata, and sealed-final file inspection coexist on the same exact `main`. It is a local host-verification handoff, not a new implementation, product wiring, or permission to adopt the replay.

**Final purpose achieved:** Exact base `2ae2eed9643c10b5a2718bf16ae465995d9ba05f`; owned branch `work/pristinepage-terminal-export-compose`, worktree `X:/local-workspace/.worktrees/pristinepage-terminal-export-compose`; tested tip `b379663077e6d32abebff8ca111402e445cc781d`, tested tree `e5eb6c3a0bb42d6673a517343174c93e258fb779`. This documentation-only child records the tested result and a narrowly approved postflight reconciliation. Its parent, not this receipt commit, is the exact test input.

## Replay and invariants

Replay the sources in the exact order below, in a fresh ignored worktree at the exact base, with `git -c commit.gpgsign=false -c core.protectNTFS=true -c rerere.enabled=false cherry-pick -x <source>`. The native tools did not expose the exact path and base together, so the authorized Git worktree fallback was used. Before writing, verify the ordered five-commit reviewed range from the base, the two-commit DAO range from the base, the explicit parents of the independent deltas, and the exact prerequisite blobs. Stop on any conflict or actual guard mismatch; do not adapt, stage from a source worktree, or replay prerequisites a second time. Destination IDs may vary with committer timestamps.

| Order | Pinned source | Destination |
|---:|---|---|
| 1 | `f808890c7656b9c3d10269d7d582038d4e78778f` | `15e70e43c86debbbe3ad8af8451ffe4976006604` |
| 2 | `6a954b62d1df7b469445ee27f176c903c8606657` | `fc23c86441e17071f272c750b1ad19f3891f9e35` |
| 3 | `718f3c6943f9551edfceaa6485e58d55d548da7f` | `5843cd5e11d08701cfbd57fef261973a2b322446` |
| 4 | `b9cacd0f2b57bbbf49b83f6084466c8d08cf136b` | `15eb774836c8a9f434363e02a2974ca4eceb5af6` |
| 5 | `ba74e33b5f316877d577fba25e062c0438a20ccb` | `0592761de9b4c35acb2365032af4e6bc88881c7a` |
| 6 | `2683b493c26eb38b3b347f044ea6d0a1136cfe15` | `831ad3f124570f065bbc981a24020514a2869f10` |
| 7 | `c8125ef30af3cf8eda1d4a45b73658ecb9b76287` | `a04ca4be5834517891f0fb7ab769a6afa1f17e73` |
| 8 | `83c25117d78f7c7abb3accf6802e7185ae708002` | `5fc768797fca45a9b7e73f44b7ac715916681463` |
| 9 | `65a4cef335dbbaa6a1ddd290b4114c10fc2ff1df` | `ccaa083809de5a000e34ec93f55f3c50518ceba3` |
| 10 | `950be487cbc7c8c1e07f2b44b025ba0ab5b400d1` | `cb737be0d083ce330e96a4053883cc611a7d3d13` |
| 11 | `2ce86652246f8fcc70bdb179ec13ffabafc66d19` | `b379663077e6d32abebff8ca111402e445cc781d` |

**What changed and why:** The execution-v1 reader retains actual stage history and the explicitly unproven producer tuple; the PNG bridge computes raster identity from actual decoded RGB while preserving encoded-byte proof separately. The DAO bounds raw reads before projection. Terminal metadata binds the immutable request, exact `SUCCEEDED` attempt, unique `READY/OK` render, exact `COMMITTED` journal, original JOB pin, and separately authenticated producing Source/Blob. Integrity and cancellation precede historical availability. The sealed-final entry point requires stable caller-owned store/transaction/target, exact seal and bound read-only checks outside Room. None of these individual boundaries grants file authority from a metadata row, rerenders a terminal result, or establishes complete-set `PRIVATE_READY`.

**Exact composition:** No conflicts or adaptations. All 28 changed blobs and modes match the last ordered source for each path; the full tracked tree had 739 entries, of which 711 were unchanged from base. The independent writer composition keeps the envelope helpers of `b1dd30aadea6f1aa428a7a63d4eea55d306f7886`, removes only the unused raster-domain field/import, and applies the exact bridge `rasterSha256` body from `f4829f9e0f1c13c6c49b6ee07c4851c24a6156a9`. The result equals the reviewed combined blob `5fe4bdc3b00f884c3249b93c70f3f34d81d1a9f0` (SHA-256 `cff7049dd1b3f349056ec23ce97fcdc512844f85bb658315a4e191455028412a`). The three hardening overlaps (`OrdinaryRasterDigest.kt`, `OrdinaryRasterDigestTest.kt`, `OrdinaryPngExportTest.kt`) exactly match predecessor `80898e13f1dab8f749908e9659df92ea3a4b29c8` before step 8. The metadata predecessor `be9b1cb7cc3dfdbbc85e125d11ef7051fed926a1` has the exact reviewed reader and the exact DAO and host SQL blobs; its pre-bridge writer is accounted for by the separately checked writer join. Neither predecessor range was replayed again.

**No drift:** `main` is clean at the pinned base; app, core, fixtures, dependency/build inputs, schema and specifications are unchanged. Room v1 schema SHA-256 is `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`; the accepted plan SHA-256 is `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. The 739 working-file input hashes were taken before launch and rechecked against the tested tip's worktree. The historical [combined reader/PNG handoff](ordinary-export-join-check-handoff.md), [DAO handoff](../storage/ordinary-terminal-raw-read-handoff.md), [PNG hardening handoff](ordinary-png-proof-hardening-handoff.md), [metadata handoff](../storage/ordinary-terminal-metadata-handoff.md), and [sealed-final handoff](../storage/ordinary-terminal-sealed-read-handoff.md) remain distinct evidence. Counts in their messages are not fresh results here.

## Executed checks on the exact tested tree

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest :image-android:testDebugUnitTest :data:compileDebugAndroidTestKotlin :image-android:compileDebugAndroidTestKotlin
python -B -m unittest discover -s validation/storage -p "test_*.py" -v
```

- Gradle used the checksum-pinned local 8.13 extraction and marker, JBR 21.0.8, SDK platform 36/build tools 35.0.0, pinned cached deps including Room 2.8.5, 4 GiB heap, at most four workers, and ignored worktree-local Android preferences. The first ignored runner's supplemental guard incorrectly named Room 2.8.4 and refused before launch; the corrected runner authenticated the existing catalog/locks and cache, without changing build inputs. Before the actual Gradle launch, C: had 22,333,370,368 free bytes and no active build/test process. Wrapper exit 0 in 186.888 s; 67/67 actionable tasks executed.
- **742 fresh JVM tests in 74 XMLs:** 585 data in 57 and 157 image in 17, zero failures, errors or skips. Key suites: `RenderCompletionTest` (13, including the existing exact writer golden); `OrdinaryRenderExecutionReadTest` (11, including independent v1 vector and unchanged writer bytes); `FrozenRenderReadTest` (14); `RoomTerminalRenderReadsTest` (11, including exact current attempt with distinct originals and refusal of fallback to an older committed journal); `DerivedTerminalFinalReadTest` (15); `OrdinaryRasterDigestTest` (9); `OrdinaryPngContainerTest` (10). Existing compiler warnings were confined to unchanged files; full logs and XML hashes are retained.
- **73 host SQLite checks** passed (nine terminal DAO checks included, with the exact-attempt case), using actual DAO SQL on the exported v1 DDL, not native Room. The Python storage invocation began after Gradle wrapper and observed JVM/tool exits had been reconciled. Python exited 0; unittest reported 0.548 s.
- **Compile only:** Both requested Android test Kotlin compile tasks executed. Fresh representative classes: `RoomExportSnapshotIntegrationTest.class` (10 declared cases, SHA-256 `8b570f6a0a48aa6e4169824eb40f52938e25be406727dd762bc39090cb9b9b90`) and `OrdinaryPngExportTest.class` (18 declared cases, SHA-256 `1c867399717039c72d740200f6475a683f7a6ff96f61fae952246a0a668214aa`). No Android tests ran; there was no assembly, APK, signing or device operation.
- **Historical slot release:** The two command records and native handles confirm exit 0 for wrapper `16972` (26 observed children also exit 0) and storage PID `11520`; the Python supervisor `19732` returned 0. The original no-live check at `2026-11-18T22:11:35.6264946Z` found neither wrapper, supervisor, outer shell nor other live build/test candidate, with C: 22,173,253,632 bytes free. The resumed postflight owns no slot and ran no build, test or process scan; it authenticated the saved records only.

## Guard stop and narrowly approved reconciliation

The original guard stopped the handoff when `refs/heads/work/pristinepage-coordination` advanced from `ffcfd721788c41dd9aff8f632709678eb0325910` to `d14fff6f7f30d26a63bbdfadfc16b908ff566ed1`. The original `blocked-postflight.json` is preserved byte-for-byte (SHA-256 `dd430b16352a2b73e1c72e72a3078030ab6f2f32502488c9672ea0ae9920624a`). No guard checks, original manifest or blocked findings were rewritten.

On authorization to reconcile only that exact pair, the new tip was verified as the direct child of the old and its sole change as `validation/export/ordinary-export-local-queue.md` (blob `d73172778c5d85a4bbbf5f450e155e87ee919bc1` → `a22501d59bb984c51aca9c4d0d83c4212a687f0b`). The change records PNG draft intake only; the pinned host brief is unchanged and the queue doc is absent from the compose commit tree and the 739-file build-input inventory. It could not alter the compiled inputs. All other original refs and worktree registrations (including detached combined review `ba74e33b5f316877d577fba25e062c0438a20ccb` and its previously tested parent `b9cacd0f2b57bbbf49b83f6084466c8d08cf136b`) are unchanged apart from the expected owned compose registration/ref. Only the original observed delta was reconciled; any additional ref or tested-path drift is not covered.

**Evidence:** Ignored `validation/raw/export/ordinary-terminal-export-compose/` in the owned worktree. Original `evidence.sha256`, 115 entries, SHA-256 `79dbeeab8506f515a8e732a4a08546e87ef40c194b99458d71bbb0fbd9de8b49`; `build-inputs.sha256` SHA-256 `88c43df802a73c09b5012b8bd396a1c00657e4e9ed35741ae7994102a645e03c`; `destination.json` SHA-256 `65bf10a75cdbb2331d20582e8b8e95bee18519d0755033fdb5c127d61a60c465`. The separately written `postflight-reconciliation.json` is not part of the original manifest; its SHA-256 is `c6226e9b6c8e28a461c61a055d7dc9c8d1dd0d71b65b31421f975e52482dafc1`. Nothing in the original evidence was relisted or updated for the reconciliation.

## What is not runnable here and what comes next

Independent composition review is next, by the coordinator before any proposed local squash. This tree has no production join wiring terminal metadata to an owned sealed final and a digest-only native PNG readback; it does not establish real Room projection or journal execution, PNG native readback or the controlled same-pixels/different-bytes rescan, API 26/27 compatibility, lossless artifact publication, complete-set `PRIVATE_READY`, or an end-to-end export. Producer admission, live ownership, native resource accounting, and the separately authorized exact Android test and product gates remain open. The separate PNG draft is not part of this candidate. No main adoption, publication, remote, device, acquisition, memory, cleanup, or delegation was performed.

**Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).

## Included source commits

<details>
<summary>Full pinned source descriptions and authorship (in replay order)</summary>

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

--
commit ba74e33b5f316877d577fba25e062c0438a20ccb
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:05:11 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:05:11 2026 +0100

    docs(export): record combined ordinary export verification

    Pin the reviewed reader and PNG RGB bridge replay, unchanged v1 identities, fresh offline verification and positive host slot release without Android activation.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).

--
commit 2683b493c26eb38b3b347f044ea6d0a1136cfe15
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:11:32 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:11:32 2026 +0100

    feat(storage): bound ordinary terminal render reads

    Keep terminal attempt and render discovery scalar before admitting one exact render row. Preserve malformed sibling cardinality, bind the writer cap, and verify actual DAO SQL against exported Room v1 including exact-attempt journal selection.

    Agentic harness: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol)

--
commit c8125ef30af3cf8eda1d4a45b73658ecb9b76287
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:13:24 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:13:24 2026 +0100

    docs(storage): record terminal render raw-read verification

    Record observed SQL RED and GREEN, fresh data JVM and Room code generation, artifact hashes, process exit reconciliation and the boundary for future consumption.

    Agentic harness: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol)

--
commit 83c25117d78f7c7abb3accf6802e7185ae708002
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:20:40 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:20:40 2026 +0100

    fix(export): reject caught reentrancy in raster readback

    Poison the whole raster proof when a borrowed callback catches a nested-call refusal. Pin the verifier phase boundary with a compile-checked same-pixel/different-PNG Android witness.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).

--
commit 65a4cef335dbbaa6a1ddd290b4114c10fc2ff1df
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:39:51 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:39:51 2026 +0100

    feat(data): join ordinary terminal render metadata

    An ordered assembly reference identifies a frozen request, not a completed render. Join its bounded immutable job, exact terminal attempt and COMMITTED journal, unique READY/OK render, original JOB pin, and independently guarded producing Source/Blob in one Room transaction.

    Authenticate the detached v1 execution and complete recomposed writer intent after Room returns. Preserve the historical owner only as payload data and return sealed-read expectations, not file authority, a lease or PRIVATE_READY. Preserve the standalone reader's prior behavior.

    Verification: eight missing-join JUnit REDs before production, eight focused GREEN cases, final 557 fresh data JVM cases and 73 host SQLite cases, no live Java/Gradle at release.

    Agentic harness: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol)

--
commit 950be487cbc7c8c1e07f2b44b025ba0ab5b400d1
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:57:17 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:57:17 2026 +0100

    fix(data): validate historical terminal renders before availability

    Authenticate the exact terminal journal, execution record and producer before reporting an intact historical render as unavailable. Confine the historical Verified to the metadata parser and retain the original standalone extent, copy and cancellation order.

    Observed three behavioral failures before product changes; both focused classes now pass (25 cases) and the full data JVM suite passes (561 fresh cases). Prior SQL evidence remains unchanged; no device or file capability was introduced.

    Agentic harness: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol)

--
commit 2ce86652246f8fcc70bdb179ec13ffabafc66d19
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 22:51:22 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 22:51:22 2026 +0100

    feat(data): inspect bound terminal derived files read-only

    Allow assembly consumers to authenticate an existing final without recovery or write authority. Reuse the bounded inspector to verify both copies before and after final readback, and isolate callback metadata scratch from the authenticated observation.

    Verification: 15 expected missing-feature assertions before implementation; 15 focused and 553 full data JVM tests passed, plus 64 host storage checks.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

</details>
