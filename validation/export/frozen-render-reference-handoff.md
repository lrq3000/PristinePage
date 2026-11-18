# feat(data): prove ordinary frozen render job references

## Initial motivation and achieved scope

`U-RENDER-REFERENCE-20261118` continues T-04/U under the primary TODO claim at `3aabd9c`, after [assembly-input integration](assembly-input-integration-receipt.md). Assignment: `X:/local-workspace/.worktrees/pristinepage-export-render-reference`, branch `work/pristinepage-export-render-reference`, clean base `2b7dbab1815fb097a4c117445f28b73227b7d06b`. A valid assembly digest authenticates neither the choice of render job nor the association between that job and the frozen ordinal: swapping two valid references and recomputing the assembly digest still parses. This candidate adds the internal per-page metadata check and its test, with no change to the producer or stored format.

## What changed, how and why

- `FrozenRenderJobProof.verify(expectedJobId: JobId, page: ExportPageSnapshot, row: JobRow, pins: List<SourceReferenceRow>, pinnedSource: ExportSourceBinding?, profile: OrdinaryExportProfile)` either returns normally or throws nonretryable `RepositoryFailure(OperationError(INTEGRITY_FAILED, false, "frozen_render_job_invalid", expectedJobId.value))`. Pair `ExportAssemblyInput.verify(...)[ordinal]` with that ordinal's page from the separately authenticated frozen snapshot. Pass a separately selected trusted profile and already materialized, admitted, stable metadata. The proof neither selects an ordinal nor loads a row. No current Page, order or recipe is consulted.
- Before matching the row, require FAIL, no accepted render/execution, exact empty mask bytes and empty asset-set hash, exact frozen renderer and provider descriptor including encoding/backend/precision, and equal requested/actual EXPORT output. Decode with `RecipeCodec`, check schema, canonical bytes and hash, and require the recipe's complete normalized source binding to equal the frozen size/hash/conversion version; decline learned geometry and provider appearance even if the descriptor claims to be empty.
- Construct the original request from the frozen source UUID, pixel hash, original page UUID, recipe revision and decoded recipe, EXPORT dimensions, generation 0, null protection and FAIL. Use `RenderJobIdentity.create` with empty mask hashes, the frozen asset/conversion identities and the exact profile. Check the requested job ID, `PAGE_RENDER`, null snapshot/ordinal columns and exact expected canonical bytes, key and lowercase digest. A match of a stored digest to stored bytes alone would not rule out a different page or forged request. No v1 encoder, key grammar or row emission was changed.
- Following `RoomExportSnapshots.verifyJobSource` and the [storage spec](../../docs/specs/DATA_MODEL_AND_STORAGE.md#freeze-committed-jobs-and-read-only-outputs), take exactly one source pin for this job. The caller supplies a complete singleton or the bounded result that can expose two owners, not a truncated `LIMIT 1` result; the existing freeze reader uses `LIMIT 2`. A foreign owner/type, missing or duplicated pin, missing binding or broken reference→Source→Blob chain refuses. Validate READY values, positive lower-bound counters, opaque UUIDs, canonical source-blob path and byte hash/count; use the existing `SourceInspection` value to check MIME, encoded and normalized dimensions, EXIF orientation/extent consistency and the profile/hash/conversion shapes. Counters are not audited for equality to global reference totals, and transaction ID shape is not journal proof.
- Require exact normalized dimensions, pixel hash and conversion fingerprint, not literal equality with the frozen SourceId or encoded object for a different source. However, the same SourceId cannot identify a different blob, and the same SourceBlobId cannot have a different byte hash. Distinct valid originals can normalize to identical inputs; a different encoded extent, EXIF orientation or byte identity is allowed when its own metadata is consistent and the frozen normalized identity matches. The caller must retain the relevant ownership through actual use; these values are not proof of committed bytes, whole-store/transitive integrity, current admission/state, authorization or renderer behavior.

## Test-first and producer evidence

The fixture runs unchanged `RoomExportSnapshots.freezeExport` with the existing in-memory metadata transaction and synthetic proof/admission ports. It produces two pages with identical render input bits after the original page UUID, verifies the correct reference for each, then detects a canonical assembly swap and an identity transplanted into the other job row with a recomputed key/digest. The GREEN path also changes live recipe and page order before verifying the frozen input. Separate cases verify byte-distinct equivalent pinned metadata, missing/foreign/duplicate pins, inconsistent normalized/encoded metadata, changed job/recipe/profile/descriptor fields and no permissive fallback or provider substitution.

The prior deferred second-freeze Minor has a direct host witness here: freeze the same ordered fixture twice, match render rows by their embedded original page UUID, and check their existing IDs at the fixed UUID offsets in both actual assembly BLOBs, plus only two PAGE_RENDER rows and two JOB pins. This characterization passed even before the helper existed. The byte-distinct pin case supplies separately materialized synthetic metadata; it does not claim the live editor can reassign an immutable source or that a native Room or codec was exercised.

## Executed checks and retained evidence

Process-local environment from README: `JAVA_HOME=C:/Program Files/Android/Android Studio/jbr`, `ANDROID_HOME=C:/Users/local-user/AppData/Local/Android/Sdk`, `ANDROID_USER_HOME=X:/local-workspace/.worktrees/paperlight-v1/.android-user-home`, `GRADLE_USER_HOME=C:/Users/local-user/AppData/Local/Temp/opencode/paperlight-build-cache/gradle`, deprecated `ANDROID_SDK_HOME` unset. Rechecked clean exact base before edits, the approved extracted Gradle 8.13 and `.zip.ok` marker at `wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d`, distribution pin `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78` against the prior publisher-checksummed acquisition record, actual wrapper JAR SHA-256 `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, and extracted launcher JAR SHA-256 `56a30262647231bf3ab30b2bf090f5ecacff9329d3f1af1a1de73fd1ab4c64b4`. No ZIP was reacquired. C: free bytes at invocations: 66,552,717,312 / 63,432,847,360 / 61,827,358,720. Build heap `-Xmx4g`; each foreground call had a 420-second bound, with low-output plain logs retained.

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests *FrozenRenderJobProofTest
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest
```

| Phase | UTC 2026-11-18 | Exit and fresh JUnit | Gradle log SHA-256 |
|---|---|---|---|
| Focused RED | `05:04:20.4149982`–`05:06:28.2946368` | 1; one existing producer pass, seven intended missing-type failures, no errors/skips | `e50ff9454493597620d9d7aaa36ddbb4988a2bd946f13461ef91ca1a6b45d423` |
| Focused GREEN | `05:08:23.5562162`–`05:09:01.9517826` | 0; eight passes, no failures/errors/skips | `f3c2ba2d9d079fa64f39b44a26d316f564f7640ad4fe68c36e82f55063bbac32` |
| Full affected `:data` GREEN | `05:10:27.8116281`–`05:10:50.7902692` | 0; 444 passes across 48 XMLs, no failures/errors/skips | `2053deb90d443de416303d8719ce77bbefd8708e183b2d6c40971889b5ec39c1` |

All three invocations executed JUnit; focused cases are included in the full count, not added to it. The RED reflection adapter made missing proof an assertion instead of a compilation error. After observing the expected failures, replaced only the dispatch with a typed call and added the live-edit/order assertions to the ordered fixture. RED compilation warnings were in unchanged files. No Gradle worker transport failure, timeout, rerun-forcing, alternate test runner, clean, acquisition, environment repair, signing, network or device operation.

Retained ignored evidence in this worktree: `validation/raw/export/U-RENDER-REFERENCE-20261118/`; `evidence-sha256.json` covers 72 files and has SHA-256 `e2f82ace00c7ce227b31c09f3637cf3af3e2f01fa0b836cf0c835d0dda1c33fb`. Each run preserves the invocation, actual exit/times, log, input hashes and fresh XML; fixture inputs are inventoried separately. RED had 188 inputs (manifest SHA-256 `c5da521694c5d9b166b88979f12617ea9fc06d7a31d0f47c007eb06c5973df17`); both passing runs had the same 189 pins (`8fdcc7044616732eab195eca45a930fc5c8e68181c59366a01c33e97e4411a5f`). Exact passing production/test working-byte SHA-256: `53cab315c12ebc2f0d1bc1251aa092333f06e9de2a2f24506754a4f08113b879` / `f7acdb56453a2174ce15819ecad07763282d3abc83dd37698d2e765c9ba339ed`.

## Gradle slot exit

Daemon logs bind `3268`, `27944` and `6688` to these invocations and record command completion and single-use shutdown. The immediate focused receipt caught `27944` during exit, so the shell halted before the next build; native read-only reconciliation matched its exact creation time and found wait0/exit0, before the full run. The final reconciliation found RED's `3268` signal/exit0 with creation inside the invocation and `OpenProcess` error 87 for `6688` after its bound completion/shutdown. The daemon logs also report a DEBUG-level shutdown-hook registry removal fallback (`Cannot start managing file contention because this handler has been closed`), after completed commands, distinct from transport/test failure. Only receipt parsing was corrected after initial diagnostic script errors; no build was retried. No matching JVM was active at the final inventory; test worker IDs were not individually sampled. No termination or cleanup. Release the sole Gradle slot to the coordinator.

## Included commit and agentic stack

One unsigned local commit authored and committed by lrq3000 `<1118942+lrq3000@users.noreply.github.com>`. Obtain the enclosing hash and exact authorship/date record without a self-reference using `git log --format=fuller 2b7dbab1815fb097a4c117445f28b73227b7d06b..work/pristinepage-export-render-reference`. Full commit message:

```text
feat(data): prove ordinary frozen render job references

Bind an assembly job reference to the canonical ordinary render identity rebuilt from its frozen page. Require an exact JOB pin and internally consistent equivalent source metadata without treating distinct encoded originals as different render inputs.

Seven intended missing-proof REDs preceded eight focused passes and 444 fresh data passes. Characterize second-freeze ordered reuse without changing the producer or adding retrieval, execution or publication authority.

Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

## Reception and limits

Ready for coordinator-owned independent persistence review, not an integration or acceptance decision. Compare destination with the exact base before replaying; any unrelated drift should remain untouched. Classify candidate hunks as applied, adapted with rationale, or already satisfied if the destination changed. Reverify focused and full affected data tests there. Preserve byte-exact canonical comparison and the distinction between equivalent normalized source metadata and literal encoded identity. Review the eventual retrieval separately: scalar bounds must precede Room BLOB/TEXT materialization and narrowing; a partial pin list cannot authorize success.

No schema or producer transition, new query, public contract, live-authority edit, execution/lease, source-file or artifact proof, device, codec, PDF, `PRIVATE_READY`, publication, push, merge or nested delegation. Bounded retrieval, authoritative admission and retention, actual file/raster verification, checkpoint/recovery and end-to-end export remain open.
