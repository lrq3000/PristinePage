# fix(storage): bound both export freeze preparations before extraction

**Initial motivation:** T-04/U `U-FREEZE-PREPARE-BOUNDS-20261118`, authority `23fbae198623370bef2ac3df98a6744c18c2b1cd:docs/specs/TODO.md`. The producer assembled a typed document, active membership, source/blob and recipe payloads twice, before and after outside-transaction original proof and export admission. A raw cell can be more expensive or malformed than the typed value suggests (for example, an INTEGER schema alias or the bytes beyond a TEXT NUL). Canonical equality after projection cannot prevent the initial allocation or catch a lossless-looking narrowing alias. The 8 MiB identity cap previously ran after recipe extraction.

**Exact inputs:** Consumer branch `work/pristinepage-export-freeze-prepare-bounds` in `X:/local-workspace/.worktrees/pristinepage-export-freeze-prepare-bounds`, clean base `5de237e3eefdb11564be44ad8ad04963e2d168c5`. The immutable implementation plan SHA-256 is `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`; exported Room v1 JSON SHA-256 is `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`. The consuming review disposition is `U-FREEZE-BOUNDS-PREP-R` in primary `validation/progress.md`.

## Draft disposition

- **Adapted host selected recipe:** In the real repository facade and freeze producer, leave the typed v1 row intact but model the exact selected raw bound returning false. Observe no typed recipe projection, then prove the exact `(pageId, revision)` false branch ran; no proof, admission or writes/pins. Separate host probes fail selected document, membership and source/blob scalar guards and record the affected typed calls.
- **Deferred native malformed title:** Do not adopt or count the unrun native draft. The host guard witness and existing SQLite NUL-suffix test cover different layers. Before a later native adoption, first run a clean-title control with real original proof and a distinctive outside-transaction admission veto that demonstrably reaches no write. Only then mutate the already-reviewed document to the tiny raw 642-byte TEXT with `length(title)=1` and compare refusal/proof observations. Native proof-entry counting alone does not establish generated-adapter pre-projection order.
- **Deferred native final schema alias:** The host final-prepare witness puts valid typed values through real closed-file length/hash proof, completes outside-transaction admission and arms the exact raw false branch there without changing the typed values. It observes actual attempted writes/pins, not just rollback state. Before later native adoption, independently measure both raw INTEGER `4294967297` and the **exact typed revision projection** returned by the pinned Room adapter on the tiny fixture. Attribute baseline behavior to an alias only if typed v1 is actually observed; unrelated admission or projection failure is not an intended RED. A reopened inventory proves no surviving writes, not no intermediate write attempts.
- **Deferred native missing-document control:** The host exercises the real producer's absent document refusal and the existing scalar SQL has a missing-row case. The original native control remains uncompiled and unexecuted in this attempt.
- **Compatibility:** Both strict existing hosts are kept: `ExportSnapshotTest` snapshot/job proxy and `ExportAssemblyInputTest`. No constructor, job-reuse/pin SQL or out-of-scope fixture changes. Existing v1 identity, ordered assembly bytes and repeated freeze controls remain separate.

No edits to the sibling prep worktree. Its three original working SHA-256 identities were observed as `7ef7f86214e5580f39c75fd4efe87dfcdb5f64245aeda2c09da03c15754046d5` (host), `a6bde575bc0066dc2e4f4a572f1e1d62e763db050d8fee3f8d57909ab4b797af` (native) and `833a0b831009a5c8d7b8ba887020eee6a620846d259fc3b975518262dc84d7ab` (candidate handoff), matching the earlier review record.

## Change and rationale

- The existing `prepare` entry is shared by both metadata transactions. Require its catalog to supply `EditablePageQueries` without changing constructor shape. Guard the document before the typed lookup, preserve missing versus malformed, then check the real document revision/lifecycle/pending-import conflict. Check active count and the existing raw active membership predicate before loading the ordered set. Verify projected membership and exact reviewed order, then use the catalog's joined source/blob guard and selected recipe row guard. No prepared recipe, source or blob is projected until the bounded pass succeeds.
- Add only `CatalogDao.recipeBlobByteCount(pageId, revision)`: scalar byte length of a raw BLOB, null for no row and `-1` for wrong storage class. The exact composite key is indexed in the exported v1 DDL. Iterate over at most 100 already bounded reviewed page/current-revision keys in the same transaction; do not scope the budget to all history or let a missing value contribute zero. Keep the full revision predicate authoritative for row bounds and Int narrowing. Use the old `recipeHasMasks` exact-revision `EXISTS` in the ordinary capability check instead of materializing `RecipeMask` associations; keep schema and decode classification before the mask capability check on each admitted row.
- Share a checked `Long` v1 metadata budget at the early scalar pass and typed identity builder: encoded reviewed settings size plus 4,096, then per page actual recipe + ordinary `[]` mask + encoded provider descriptor sizes plus 4,096. Each BLOB must be in `1..524288`; charge at or below 8 MiB is admitted. Reuse the descriptor bytes being charged for the resulting rows. The alternative of a broader joined SQL aggregate is unnecessary: the indexed pass avoids duplicating the raw row guards and preserves exact revision selection. Preserve the existing typed identity's separate final canonical stream size check, bytes, order and defaults.
- Only a fully prepared snapshot proceeds to the existing distinct original-file proofs and supplied admission outside Room. Both complete before the final guarded metadata read, canonical/binding comparison, cancellation fence and first write. No reuse/pin metadata query or write path is changed.

**Limits:** Scalar SQLite tests validate storage class, exact length, index use and independently charged fixtures at 8 MiB ± 1 with the exported DDL, not generated Android projection or producer behavior. The JVM fixture validates the production application of that charge, and deliberately supplies undecodable synthetic recipes near the raw budget: below/at observe a typed read and the subsequent unsupported decode, above observes no recipe projection. This is not a heap or cursor-window limit, measured resource admission, raster/PDF bound, or proof that a near-limit recipe is semantically valid. No native source/compile/run evidence, app wiring, schema/dependencies/build setting edits, device/signing/APK, rendering, private readiness, publication or job-reuse/pin hardening. Adoption and destination reruns are coordinator-owned.

## Reproduce on the host

Use the exact base; for RED apply the retained `red/tracked.diff` (host test only) and take `test_export_freeze_prepare_bounds.py` from the implementation commit. Without the diff, take the same two test files from that commit and reconstruct RED by omitting the four extent-observation lines from `ExportSnapshotTest.kt` (`extentReads` declaration, two-line `recipeBlobByteCount` override, “Charge exactly the selected revisions” assertion) and undoing its two final warning fixes (`setSourceRefCount` used `source` instead of `sourceId`, and `parentFile.mkdirs()` had no `checkNotNull`). `RepositoryFixture.kt` implements the new port only on GREEN. RED test SHA-256 is `43364d772f1e900042e4fd24facd55ddfa3d32e3b6a10e799ff990075f3be972` (Kotlin) and `a3a22a2dd2f0e0dc66a7c5e2a5ed4b7e98fcda12a8aab9bdda6fb9d547c08a16` (Python). For GREEN take the full implementation commit and run focused then full. Use README process-local Java/SDK/Gradle homes (`ANDROID_USER_HOME` points at this worktree; deprecated `ANDROID_SDK_HOME` is unset), confirmed local Gradle 8.13 distribution and wrapper SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, strictly offline, 4 GiB/4 workers, sole slot, no prior owned JVM and C: ≥8 GiB. Each execution has a 420-second deadline.

```powershell
python -B -m unittest discover -s validation/storage -p test_export_freeze_prepare_bounds.py -v
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests app.pristinepage.data.ExportSnapshotTest --tests app.pristinepage.data.ExportSnapshotIdentityTest --tests app.pristinepage.data.ExportAssemblyInputTest
python -B -m unittest discover -s validation/storage -p 'test_*.py' -v
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest
```

**Actual RED:** SQL exit 1, two tests, four assertion failures for the intentionally absent `recipeBlobByteCount` query (one missing-row expectation and three boundary subcases). Gradle exit 1, 22 executed tasks, 45 fresh cases, six product assertion failures. The selected current recipe was projected in both preparations; the final indistinguishable typed row actually froze after file proof and admission; one byte over reached a typed recipe read; the selected document branch reached source proof; missing document used a typed lookup; mask refusal projected associations. Both strict compatibility classes passed (7 assembly + 8 identity).

**First focused implementation run (not GREEN):** SQL passed 2/2. Gradle compiled but 25 producer tests surfaced an `AbstractMethodError` at the new query. The focused output showed the Kotlin incremental compiler recompiled `MemoryMetadata.class` but left the generated `ExportSnapshotTest$Harness$catalog$1.class` at the RED timestamp, missing the new delegation method. Add the explicit extent forwarder and assert the exact ordered keys charged at each budget boundary; this is a behavior observation as well as a direct change to the class that needed recompilation. The retry uses ordinary incremental settings. No cache cleanup, forced tasks, dependency/Gradle configuration changes or product workaround.

**Focused GREEN:** All 45 fresh tests (30 real-producer host cases, 8 identity, 7 assembly) and 2 SQLite tests passed with exit 0. Gradle took 39 seconds with 3 executed and 19 up-to-date tasks. The host compiler flagged two test-only warnings (override parameter name and nullable Java `parentFile`); corrected those before the full run. The SQL and producer suites are independent; the Android adapter was compiled by host Gradle/KSP, not executed.

**Full affected GREEN:** `:data:testDebugUnitTest` exited 0 in 45 seconds (3 executed/19 up-to-date), **506 fresh tests in 52 XML suites; 0 failures/errors/skips**. Host storage discovery exited 0 with **53 tests** (0.408 seconds). Full run preflight showed 48,943,210,496 free bytes and no previous worktree build JVM. The Python PID 28220 and Gradle-owned `cmd`/wrapper JVM/daemon/worker descendants (7404, 13644, 30268, 29504, 21020, 16524, 29672) are recorded exited by the owned process objects; no worktree Java process remained. Native draft working hashes were rechecked unchanged at handoff. No further use of the build slot by this worker.

Evidence is retained locally under `.gradle/freeze-evidence/` in the consumer worktree. Each run directory contains exact arguments/exit/timing/owned PID recordings, file-level SHA-256 for inputs, working diff/status, separate stdout/stderr and fresh JUnit XML. The retained `red`, `green`, and `green-retry` directory manifest SHA-256 values are respectively `c3052a445f4a017572ca968bb626e95591dc0a4336a67d0610c085c8437ede37`, `5a8801db944a4ae11b3042d235d0d229771793e1f5e3ddf0b02cbbf69431e714` and `4d9b32c8dc79125b0779df45110e532685ef619614e6fca2c92309000a7751ce`. For focused RED and GREEN the input manifest SHA-256 values are `244f2e2c11b29375921dbef3d78e5045f0ffbc04bcd22ea71bf4bf1de36be321` and `8642c1a577a4b8c4ebca490e9ecac3c2f6b8fe3fe2a1a4da39a4fa11dd3133e0`; producer JUnit XML hashes are `ff423d03215e214c2b51207c6f1257ee4ceeb77dd064b876c8271b194c09ce8a` and `871718c7132f55182d50dda1eae42fb346a41e0b3f2d75accb6761d993338cd1`. Python 3.13.12 / SQLite 3.51.1, JBR 21.0.8. Local Gradle cache contained the historically checksum-verified 8.13 distribution and its successful wrapper marker, not the original ZIP.

Full run SHA-256 anchors:

| Retained file | SHA-256 |
|---|---|
| `full/inputs.sha256` | `9e3e0585ade8d5dbd85d8a9450fe3b1211e4e590a78f21de6771f49de8b35be5` |
| `full/evidence.sha256` | `6bfb2cda437d7e4a17402b19c4ebb1194b484fb5c4a498002059c239777f45b4` |
| `full/jvm.out.log` | `6f7ae739a5c87221ab7ff755e801bb53e285f06d0756f54a09ed2e2ea3486d87` |
| `full/sql.err.log` | `4bbf1c45326b87d965d8f9700dfb508db6b48903eff66aa8e9c3d40b4fe37f27` |
| `full/xml/TEST-app.pristinepage.data.ExportSnapshotTest.xml` | `bb65e7984353d7524899f531a5a797aa2d1199fe3d03b201e610ec64a3ad4fd1` |
| `run-host.ps1` (final local evidence runner) | `e237c80efafb122873145d1309ba9286f469dfe1a21e8248add07b13887db3b7` |

## Included commit

```text
commit 87a013e120d4254e873c908d536d237537de094c
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 10:09:24 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 10:09:24 2026 +0100

    fix(storage): bound export freeze metadata before extraction

    Apply existing catalog bounds inside both prepare transactions and charge the exact selected recipe BLOB lengths against the v1 metadata budget before first recipe extraction. Preserve freeze bytes, ordering, mask capability refusal and proof/admission ordering.

    Test the producer gates and final revalidation, plus scalar extent SQL against exported v1 DDL.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

This handoff is the only content of the following documentation commit; its own hash is reported separately.

**Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`); reasoning level not exposed.

## U-FREEZE-PREPARE-BOUNDS-R correction, round 1/5

**Intent and authority:** At exact fix base `ba1d9bfedfa4626921be37d5df255cfbb9a002dd` on `work/pristinepage-export-freeze-prepare-bounds`, address only the confirmed Important finding and slot transfer from `3fd0a43a8a944b2d072fb070ad3ddb710068bc1b:validation/progress.md`. The shared `prepare` used `!= true` on raw source and recipe guards. Missing rows still failed closed but lost their established nonretryable `INTEGRITY_FAILED` reason keys and affected IDs. In particular the joined guard alone cannot distinguish a canonical link whose Blob is absent from an invalid present chain. The revision-2 aggregate-witness Minor remains deferred.

**Correction:** In both prepares, a null exact recipe guard now yields `recipe_history_missing` for the page, and a null source guard yields `source_unavailable` for the selected source. A false recipe guard keeps `export_recipe_metadata_invalid`. Only a false joined source guard invokes `sourceBlobLinkIsMissing(id)`. It reads one scalar by the exact Source key and, for a raw TEXT canonical lowercase UUID with exact character/byte counts, checks `NOT EXISTS` on the exact indexed Blob key. The canonical check requires hyphens at the UUID positions, exactly 32 remaining characters, and all remaining characters lowercase ASCII hex (same round-trip shape as `requireOpaqueId`). Only a positive missing-link result yields `source_unavailable`; false or a contradictory null remains `export_source_metadata_invalid`, with the selected source ID. An existing Blob, healthy or malformed, is never treated as absent. The scalar diagnoses linkage only, does not certify the rest of a Source or Blob, and never overrides the joined guard to admit a row. No entity fallback, editor/committed-source guard changes, schema, defaults, v1 bytes/order, write/reuse/pin, proof/admission, or app behavior changes.

**Case isolation:** The host fixture removes only the selected recipe while an unrelated recipe remains; substitutes an absent exact Source or canonical backing key with an unrelated Blob still present; or corrupts the selected existing recipe, backing Blob or link. These are isolated test maps below the real producer. Each refusal records the exact original scalar result before asserting the complete structured error and no affected projection, proof, admission, or write attempts. The valid control reaches an independent, distinctive admission veto after proof, also without writes. On GREEN, the test double explicitly forwards and records the new port to prove it is used exactly once on the matching false branch, and not at all on a null or valid source guard. Actual SQLite uses exported Room v1 DDL in memory, deferred cross-row checks without committing corrupt data, and the annotated query, not the fixture's Kotlin shape check. It distinguishes valid dangling linkage from wrong key type, noncanonical text and malformed present Blob; `EXPLAIN QUERY PLAN` requires indexed searches for both exact keys. No native extraction/run claims.

**Reproduction:** On the fix base, apply only the retained `.gradle/freeze-evidence/fix1-red/tracked.diff` (the two test drafts). Their exact working SHA-256 values were `cf5e3ab99f16cc95a4f3654b70ba926b5c8eef5df9f91a90b720771ae13a036b` (JVM) and `8a6bc1792c5c303c0b7a0b541d0dfead4469dfcf289ef3f587d56bdaa28a61d6` (Python). Run the same focused JVM and SQLite commands listed above with the pinned isolated offline homes and 4 GiB/four-worker/420-second limits. For GREEN take the full round-1 code/test commit and run focused, then full, without cleaning or forcing tasks. To reconstruct the uncompiled-port RED draft from the GREEN host test, omit the `missingBlobLinkReads` field, the explicit `sourceBlobLinkIsMissing` forwarder and two observations of that field.

**Actual RED:** Gradle exit 1, 52 fresh focused cases, exactly three expected assertion failures: `missingSelectedRecipePreservesHistoryMissingBeforeTypedRead`, `missingSelectedSourcePreservesSourceUnavailableBeforeTypedRead`, and `missingExactlyLinkedBlobPreservesSourceUnavailableBeforeTypedRead`. XML confirms that the expected key differed from the actual key while code, retryability and affected ID matched; the assertions preceding key comparison passed. The malformed recipe/Blob/link and valid-veto controls, and both strict compatibility classes, passed. Separately host SQLite exit 1, five cases, seven missing-query assertions (including linkage subcases) in two tests; the existing guard and extent tests passed. This was the absent annotation baseline, not a runtime SQL misclassification.

**Focused GREEN:** Gradle exit 0; 52 fresh cases (37 producer, 8 identity, 7 assembly), 77 seconds, seven executed tasks and 15 up-to-date. Actual host SQLite exit 0: all five focused tests, including the exact-index plan and malformed-link subcases. KSP compiled the annotated DAO, but neither the generated adapter nor Room ran on device.

**Full affected GREEN:** Both commands exited 0: `:data:testDebugUnitTest` ran 513 fresh tests in 52 suites with zero failures/errors/skips (24 seconds, 1 task executed/21 up-to-date); host storage discovery ran 56 actual SQLite tests (0.446 seconds). Preflight confirmed 49,537,683,456 free bytes and zero live JVMs before the full run, local pinned Gradle 8.13/JBR 21.0.8 and process-local README homes. Full run owned Python PID 31632 and Gradle tree PIDs 17612, 23000, 36396, 19940, 4332 and 31236 show exited in retained process-handle records; no Java remained at release. Room v1 JSON is unchanged at `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`.

Retained logs, XML, process identities/exits, exact input files and manifests are under `.gradle/freeze-evidence/` in this worktree. SHA-256 anchors:

| Local stage | `inputs.sha256` | `evidence.sha256` | Producer JUnit XML |
|---|---|---|---|
| `fix1-red` | `9fea3c4dfe528e4d8e4991e321332928319b7c80bea2b8f144b9b30b4c356fb8` | `3c3a9c4d2921346d22f47913dc93f4107d8ef108fff7aa0770a85b2ae97502b0` | `5bc7062191e0d7064e327296eed1a285043fe398d3f235e3d6b330ae8d64c5dc` |
| `fix1-green` | `0da191de3610a784c8222564805bc18328ce0466cce510a42e16775a30f421a7` | `74b6f0b857498df44722aff362ccb954da377ba8396bff633abc7dc72a288856` | `2599b37428c9ee8ccada1d7cc758b7d4b436766bceb752d35a220915ea8f2549` |
| `fix1-full` | `0da191de3610a784c8222564805bc18328ce0466cce510a42e16775a30f421a7` | `f71eca203ac33c75895f81ac5b704027a097da1f24ebc8651a762574cc945a08` | `4555cb7479dbe439fd58d901b0629aec4fabf95be6496c6779f356e3ea3639db` |

The round-1 evidence runner `run-fix1-host.ps1` SHA-256 is `b0ca5525450bf9d18b15d0ddb626826f6f63757f09fc6ca2c12971e7792d685b`. The runner from the original slice retains its original hash.

**Included implementation commit:**

```text
commit bac0af125903c1f463f9f8dedaa3abef925ff73a
Author:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
AuthorDate: Wed Nov 18 10:36:08 2026 +0100
Commit:     lrq3000 <1118942+lrq3000@users.noreply.github.com>
CommitDate: Wed Nov 18 10:36:08 2026 +0100

    fix(storage): preserve missing freeze input diagnostics

    Keep absent selected recipe and source rows distinct from malformed rows before typed reads. Diagnose a missing backing blob by a scalar lookup of its exact canonical key when the joined source guard is false.

    Pin structured refusal keys, the no-projection boundary, and both index searches on exported Room DDL.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

The following commit contains only this handoff append. The code/test commit is available for independent re-review, not adopted. No native, APK/device, or app activation work was done. No further use of the sole test slot by this worker.
