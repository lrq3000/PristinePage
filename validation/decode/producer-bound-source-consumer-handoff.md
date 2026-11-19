# fix(export): consume the exact admitted producer original

**Disposition:** U-BOUND-SOURCE is host-verified and left on `work/pristinepage-bound-source-consumer` in `X:/local-workspace/.worktrees/pristinepage-bound-source-consumer`. The sole parent is canonical `main` at `9c5353aea8902249e9d2783519c62e1698a95837` (tree `48af47047c66fd0c3bf92597a4e32135a307d886`). Not integrated; this is a source-to-raster prerequisite for T-11, not an active export or a change to any acceptance state.

## Intent and proof edges

A frozen consumer page and its reused PAGE_RENDER job may refer to byte-distinct originals with the same normalized pixels. The producer must render the actual admitted JOB original, not whatever an ordinary read later returns and not the consumer's original. A detached preload is insufficient: the loaded value that passed proof must be the value the codec consumes.

1. **Upstream authority, unchanged:** `RoomFrozenRenderAdmission.Admitted` retains the preflighted `Verified.pinnedBinding`; `FrozenRenderAcquisition.Acquired` carries that admission and the exact same-store lease after live revalidation. A future data-owned caller must retain the same store, owner and `Acquired`, use its JOB binding and source ID, and keep that authority through render/file/completion. An `ExportSourceBinding` describes expected rows; it does not mint admission, a lease, a pin, or permission to open an arbitrary path. Only the data producer knows the authorized frozen page, recipe, ordinal and lease. See [storage authority](../../docs/specs/DATA_MODEL_AND_STORAGE.md) and [admission receipt](../storage/ordinary-admitted-render-proof-handoff.md).
2. **Data load:** `ProducerCommittedSources.loadForProducer(SourceId, ExportSourceBinding)` is a separate read-only capability, implemented by the same `RoomCommittedSources` object as `CommittedSources`, not by a default ordinary-load adapter. One metadata transaction runs `sourceReadIsBounded` before either typed projection and compares the complete live Source and Blob rows to the admitted rows in that transaction. Row equality includes both creation times, ingest transaction, path and state; only `refCount` and `sourceCount` are excluded from equality, and each live count must be positive. The existing typed ID, READY, path and inspection checks remain. Outside Room the same `SourceFiles.verifyOriginal` resolves the cooperative canonical private path and checks the exact bounded extent and closed SHA-256 with cancellation. The sole returned `CommittedSource` borrows that process-private `File`; no provider read, raw expected path, metadata mutation or second lookup.
3. **Actual decode:** The existing two-argument `SourceDecoder` selects the producer view of its own source object. Malformed/foreign IDs and out-of-bounds destination refuse before lookup; a missing producer view is `PROVIDER_UNAVAILABLE` without an ordinary load or codec call. The returned opaque IDs, Blob linkage, byte count, SHA-256, and all `SourceInspection` fields (MIME, both extents, EXIF, profile, conversion fingerprint, normalized pixel hash) must match before `SourceFileDecoder` receives that exact returned file and digest. The same normal decoder body checks the codec's returned identity, actual full-source raster extent and cancellation; undelivered images and their permits close together. `AndroidSourceInspector` still does its own existing byte/decoding checks; this is not a second source lookup. The ordinary decoder constructor, editor load and preview behavior are unchanged.
4. **Actual render:** Internal `DefaultRenderCoordinator.renderForProducer` admits only `FAIL`/`EXPORT`, reuses the ordinary opaque-ID/recipe/output preflight, and calls the bound decoder in the same `Execution`, on the same queue and budget. The actual decoded binding must still match the request. Geometry/appearance use the existing complete-owner body; the source and geometry close before normal delivery, and the complete final appearance owner transfers to the caller (or closes on canceled delivery). No new renderer, dispatch queue, ledger, callback/file access or unbound fallback. The [full-source and whole-owner contract](../../docs/specs/PIPELINE_CONTRACTS.md) still applies.

## Exact write set and provenance

| Repository path | SHA-256 observed in GREEN/full (working bytes) |
|---|---|
| `data/src/main/kotlin/app/pristinepage/data/repository/CommittedSources.kt` | `4ac3eae74da4ce95937e889dfcc17a7e9706ff123d3f8598581303d3e12f68ae` |
| `data/src/main/kotlin/app/pristinepage/data/repository/RoomCommittedSources.kt` | `1466354bb1da5e6d8118f0415daef208be92b1eb74fc6ccf0696481331bb619b` |
| `image-android/src/main/kotlin/app/pristinepage/image/decode/SourceDecoder.kt` | `ded623837bfebb25723d739dfc23c0420410327d21d0b10934ecc5803cbebf11` |
| `image-android/src/main/kotlin/app/pristinepage/image/render/DefaultRenderCoordinator.kt` | `34926cfca39aedf8fc454e6ecd5a6216d66b3f3f989b48241eea1bf43a69455d` |
| `data/src/test/kotlin/app/pristinepage/data/CommittedSourceReadTest.kt` | `c27834a6c05f0d56b473ea6dcc6fdffcc841504eb01464868507e69215a2952b` |
| `image-android/src/test/kotlin/app/pristinepage/image/CommittedSourceDecodeTest.kt` | `6f168da3658c40ce8adb22beab3d6250bd84cbaafd4bbedd257a82fbecc2c997` |
| `image-android/src/test/kotlin/app/pristinepage/image/RenderCoordinatorTest.kt` | `1ae49c13422098be07a6bfca6c808c31b71ba7dbc0407f46b550e261ce963a18` |
| `validation/decode/producer-bound-decode-handoff.md` | `1f8424b053c8594f7d69c2a93b29c8911750ba1e3d44c47e2e690f11ab7bfa47` |
| `validation/decode/producer-bound-source-read-handoff.md` | `7fb148730ff83097cd56db41d61f4249378d59604109a082d9f4a2711f4ebd38` |
| `validation/decode/producer-bound-source-consumer-handoff.md` | This receipt, added after execution; its own hash is not a test input. |

Checked clean canonical `main`, the absent ignored destination and unused ref before `git -c core.hooksPath=NUL worktree add -b work/pristinepage-bound-source-consumer X:/local-workspace/.worktrees/pristinepage-bound-source-consumer 9c5353aea8902249e9d2783519c62e1698a95837`. At all four production paths, raw blobs from `dac9fe37ff6f5b22df4fb58f38a523ec8faf278d`, exact `main`, and both worktree working copies were identical before RED (respectively `1e10b3a26ead773c326884fa71247ed39b47c8033bb8bd2e1db9912d9d9a0220`, `d8097dbd40d8c693239d3e54ec97215a654185b2187ee6374709c7dc81100f84`, `43544e603b6ad890010d239e849f324fb1f699e1c2d336baecf9350dbe980682`, `47ce4596aaac812eb9184de25679a9a4b3aab94d160dc06cd7a4aeaef688fd65`, in production table order). Other already-adopted data/image paths may differ from `dac9fe3`; they are not candidate edits.

Verified the four inherited files against the originating draft worktrees, their handoffs and the coordinator's replay manifest. Copied only those and the single-case renderer draft from `pristinepage-bound-source-replay`, byte for byte. RED SHA-256 for the data, decoder and coordinator tests, respectively: `f816fc1e8e7ff21d2fb5dfc6ed7bd7e2d08b5e462f4121ddefc6dea5eb94c5dc`, `806d32ed5d05d1bff4bff9ee7c0ec6fa7888e085be75f9ea374fc384ec1f21d6`, `82f1a6a6de5344216501908bd955ed916662f6d0af98ad0a66d92c89e1a59c1f`. The replay and both original draft worktrees were left untouched. Historical handoffs remain verbatim; their “unrun” disposition refers to the original drafts.

No change to the DAO, Room schema, dependencies/locks, app, `AndroidSourceInspector`, PNG preflight/encoder, producer acquisition/completion, manifest or persistent identities. The v1 schema still hashes to `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d`, `CatalogDao.kt` to `7a887eef52c9331a1f5980a4030ee1741a500fd6196121782bf501fbeefe48db`, and the accepted plan to `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. There is one enclosing unsigned local commit by lrq3000, with the subject above. **Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).

## Checks and release

From canonical `X:/local-workspace`, in this order, using `C:/Users/local-user/miniconda3/python.exe`:

```text
python.exe .worktrees/pristinepage-coordination/validation/raw/run-bound-source-tests.py red pristinepage-bound-source-consumer
python.exe .worktrees/pristinepage-coordination/validation/raw/run-bound-source-tests.py focused pristinepage-bound-source-consumer
python.exe .worktrees/pristinepage-coordination/validation/raw/run-bound-source-tests.py full pristinepage-bound-source-consumer
```

The support runner targets only the worker worktree. It uses the pinned local Gradle 8.13 distribution with `--offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict -Pandroid.builder.sdkDownload=false`. RED/focused filter `:data:testDebugUnitTest` to `app.pristinepage.data.CommittedSourceReadTest` and `:image-android:testDebugUnitTest` to `app.pristinepage.image.CommittedSourceDecodeTest` and `app.pristinepage.image.RenderCoordinatorTest`; RED adds `--continue`. Full runs the unfiltered `:data:testDebugUnitTest :image-android:testDebugUnitTest :app:testDebugUnitTest` under the pinned no-APK graph guard. All three dry-run and actual graphs exclude APK packaging, signing, assemble, install and device tasks; resource intermediates for host tests are allowed. After full release, from the worker, the same pinned Python ran `python.exe -B -m unittest discover -s validation/storage -p "test_*.py" -v` (Python 3.13.12 / SQLite 3.51.1) under the same process supervision, including six actual DAO SQL/exported DDL source-bounds cases.

| Phase | Actual wrapper exit / seconds | Observed results |
|---|---:|---|
| RED | 1 / 168.527 | Data 12 (4 `loadForProducer` absent); decode 15 (6 `decodeForProducer` absent); renderer 22 (1 `renderForProducer` absent). All 11 are executed JUnit assertion failures; 38 controls pass. |
| Focused GREEN | 0 / 74.834 | Data 12, decode 15, renderer 23: all 50 pass. Temporary missing-entry reflection was replaced with typed dispatch. |
| Full host JVM | 0 / 114.751 | 922 fresh passes: data 636 in 59 XML, image 184 in 18 XML, app 102 in 15 XML. |
| Host SQLite | 0 / 0.861 | 82 passes on existing in-memory host storage checks. |

Zero errors or skips in all recorded JUnit suites; no unexpected JUnit failures. The positive renderer control uses one bound lookup, zero ordinary lookups even though the dual-view object would return distinct equal-length bytes from its ordinary view, and one codec call with the original's exact file and digest. It runs actual quarter-turn geometry and grayscale through the shared renderer, checks a literal 2×3 RGB oracle and stage order, observes the source owner already closed on delivery, then checks final raster invalidation and zero ledger usage after close. The ordinary-only control reports `UNAVAILABLE` with zero source/codec calls and zero ledger use. RED retained existing Kotlin diagnostics; focused GREEN reports a test-only Java `File.parentFile` nullability warning for the tempfile's known parent. Full also reports existing `P06GrantScopeTest` Kotlin interop warnings. No production warning was introduced by this patch.

Raw evidence is retained in the ignored worktree directory `validation/raw/decode/u-bound-source/`. Rehashed 153 entries across the four manifests (including fresh XML, input snapshots, argv/cwd, logs and process records):

| Phase directory | Entries | `evidence.sha256` SHA-256 |
|---|---:|---|
| `red` | 19 | `79e08c2330e439e7ecdce7c594f29a92812e303dad58487f8542d07b7d0c4b97` |
| `focused` | 19 | `01720b41dabe7a9facfdc98d1c0b2ff6a2942b5d0e9aafa1282763f9d2fe9278` |
| `full` | 108 | `d5d3672a3691f20227f2936c0743c77971b61543a39b981bb6fe3cc222830702` |
| `sqlite` | 7 | `5d369928aad876fe5560208081fe41cf881463371ec1e80ee231deea10894b21` |

Each Gradle stage captured and checked 756 input hashes and 43 pinned toolchain hashes. RED `inputs.json` SHA-256 is `79adf87add1f0b569737417520775bbe49c76b6c436b533d6e89806664d5a5fb`; focused/full and the subsequent SQLite input verification use `21d9e9e1a53327e30d245f97901d5690acaa3491e83b799ec6726ac9f1bdb2f5`. The runner passed the 8 GiB floor and empty-tool preflight on every command.

**Release:** `release.json` SHA-256 `05ab6dd34acfb345ea23c00abac1f0e3b07b2419ea7e06d24e3ddf2c8d18b09d`. The three dry-run wrappers (7092, 8868, 34308) exited 0. Actual wrappers (40816, 26600, 40872, 36688) exited 1/0/0/0; the only nonzero actual wrapper was intentional RED. All 99 captured wrapper/descendant handles were signaled (`wait=0` with creation/exit FILETIMEs), with no unresolved identity or handle-open race. Four supervisors and three launched background shells were absent at the final inventory, which showed zero live build/storage tools and 17,599,971,328 free bytes on C:. Gradle/SQLite ownership returns to the coordinator; no later test is scheduled.

## Limits and next action

Host data tests exercise real `RoomCommittedSources` logic and `SourceFiles` on closed synthetic files with transaction-aware metadata doubles. Host SQLite independently executes the actual unchanged scalar SQL and exported DDL; neither is native Room/codec composition. Image tests use a codec double and real decode orchestration, geometry, appearance and ledger; they do not establish that a platform codec normalized an actual PNG. Stable cooperative private roots and a retained owner are assumed; no hostile concurrent filesystem replacement, GC lease, process-death protocol, native/PSS/API support, terminal PNG readback, `PRIVATE_READY`, app activation, or end-to-end export acceptance. No device, runtime, APK, signing/key, remote, memory, cleanup or nested delegation operation.

The coordinator should independently review the exact candidate and rerun affected checks on its fresh destination before any adoption. In the next consumer slice, wire the retained `Acquired` JOB binding through the same-store reader and existing renderer, add same-SDK/different-build fingerprint coverage at the actual PNG capability/producer join, and get separately authorized native Room/codec evidence. Do not add an ordinary fallback or infer authority from a fresh reread.
