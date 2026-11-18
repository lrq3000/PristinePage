# feat(export): bridge ordinary PNG readback to the canonical RGB digest

**Attempt and intent:** `U-PNG-RGB-BRIDGE-20261118`, bounded T-04/U / EXP-02. The [standalone PNG codec handoff](ordinary-png-codec-handoff.md) returned only the compressed-byte proof. Canonical `validation/progress.md:19` records `U-PNG-CODEC-R` static PASS/PASS WITH LIMITATIONS and an Important join blocker: `RoomRenderCompletion` requires a hash computed from actual decoded opaque RGB, not compressed PNG bytes or the retained expected hash. This candidate supplies that proof without wiring the storage callback or activating a PNG profile.

## Exact base, scope, and replay

- Local target `main` at `2ae2eed9643c10b5a2718bf16ae465995d9ba05f`; clean fresh candidate `work/pristinepage-png-rgb-bridge` at `X:/local-workspace/.worktrees/pristinepage-png-rgb-bridge`. Canonical `AGENTS.md`, `docs/specs/AGENTS.md`, PRD, TODO, PIPELINE_CONTRACTS, incident commands, OPERATIONAL_PLAN, and CAPTURE_IMPORT_EXPORT supplied operating scope; preview-safe `validation/progress.md:15–23` supplied review dispositions.
- Reconcile and replay the six additions from `ea66b1e2b355cf1caa736ed2e3f78d250efca5f5..9b9e3dc3ee42144e5d5eb3bfdd9b0d7cf8c4aa23` first. The old base is ancestral to the new base, all six paths were absent at destination, and the module build/wrapper inputs were unchanged. The exact `git diff --binary --full-index --no-ext-diff` scoped patch passed `git apply --check --whitespace=error-all`, `git apply --whitespace=error-all --index`, staged `git diff --check` and candidate blob equality for all six paths. `reviewed-codec.patch` SHA-256 `749ce36990b6a4acdd714edc8e65aad3838a52b57ae0d19d2e26660a69dddfcb`. Replay commit `48d8c842c4ebe06a48beaa9262f26fb085763b16`; original candidate and handoff worktree unchanged. The preserved handoff is historical: its suggestion to reuse a retained raster hash is superseded by the reviewed actual-decoded-sample ruling.
- Next replay bridge commit `cd121818505f1ca1e16ec9c285255fe4917854ea` only, then this evidence-only handoff (the receipt commit is identified externally). On the eventual destination, perform the strict patch check for each step and rerun the affected suite after any reconciliation. No shared specification, app, Room callbacks, persisted schema/envelope layout, dependencies, Gradle configuration, or other production path changes.
- Separate `b1dd30aadea6f1aa428a7a63d4eea55d306f7886` terminal-reader candidate was not replayed or modified. Its only path intersection is `data/src/main/kotlin/app/pristinepage/data/model/OrdinaryRenderExecution.kt`; the bridge removes the raster-domain member and moves only `rasterSha256` internals to the row API (with an unused `ByteBuffer` import removed). The reader candidate retains the old raster code and edits neighboring writer constants and helpers for the envelope, validation, cache key and envelope SHA. At later composition, start from the then-current destination: keep the exact reviewed PNG replay, preserve both candidates' unrelated intent, reconcile context around the removed `rasterDomain` and the method/footer manually if necessary, and retain the new least-scope raster delegation plus the separately reviewed reader's envelope helpers. Do not restore a duplicate raster grammar or adopt terminal behavior under this receipt. Rerun the existing writer golden, row tests, terminal reader, full affected JVMs, and Android test compile on the actual combined destination; this branch did not test that composition.

| Exact replay path | SHA-256 at replay |
|---|---|
| `image-android/src/main/kotlin/app/pristinepage/image/export/OrdinaryPngContainer.kt` | `b5db83fe8381180d0505c94b6667ccd0ad2d08afaacedfa7354a35c36825973f` |
| `image-android/src/main/kotlin/app/pristinepage/image/export/OrdinaryPngWriter.kt` | `d5f7813d57c91a8bc2ecede17deea0e6019d3e49d7a7b2ab5b7ddd5bae80facd` |
| `image-android/src/main/kotlin/app/pristinepage/image/export/OrdinaryPngVerifier.kt` | `4d335bca067edf8ae372bf93996de4bb8202cf05bedae2e9ec2e75668308c1e6` |
| `image-android/src/test/kotlin/app/pristinepage/image/export/OrdinaryPngContainerTest.kt` | `b3690a2319982eeac7555f7e5fb358fa32a727efba397cc6793942be5aa980b2` |
| `image-android/src/androidTest/kotlin/app/pristinepage/image/export/OrdinaryPngExportTest.kt` | `bd153eb53195ed6c27892c7012971cd8504fee65bfe173bb4f7510e19d79daa3` |
| `validation/export/ordinary-png-codec-handoff.md` | `5c869d1005d834fefa0fb2588c6b092ef01775884bfde69f15d7ef05f453aa2f` |

| Exact bridge path | Tested SHA-256 |
|---|---|
| `data/src/main/kotlin/app/pristinepage/data/model/OrdinaryRasterDigest.kt` | `45956cdef021036a7cc56db9fb08444c2767c3ee3d768bfa714aa49cd1a318ae` |
| `data/src/main/kotlin/app/pristinepage/data/model/OrdinaryRenderExecution.kt` | `7bee21d7ab4b8062827e375862669bd930f65e062f4e084d3b239cd788abf3b9` |
| `data/src/test/kotlin/app/pristinepage/data/model/OrdinaryRasterDigestTest.kt` | `a6d79c14a3b33fe11dfda19802f403d46536a03c6eed026edf18fa37d74b9023` |
| `image-android/src/main/kotlin/app/pristinepage/image/export/OrdinaryPngVerifier.kt` | `b6985ffbc205565dcd4d2cc7fe2e6e5bf7f5ea99053c3c6705b8247bb28c160d` |
| `image-android/src/androidTest/kotlin/app/pristinepage/image/export/OrdinaryPngExportTest.kt` | `33dd86856b6e2da8ffc37bc02c6993aff2ed935ae456022f7693a244af109059` |

```text
commit 48d8c842c4ebe06a48beaa9262f26fb085763b16
Author: lrq3000 <1118942+lrq3000@users.noreply.github.com>
Commit: lrq3000 <1118942+lrq3000@users.noreply.github.com>

    feat(image): replay reviewed ordinary PNG codec candidate

    Replay the exact five source/test paths and original handoff from 9b9e3dc3 on current main. Preserve the standalone candidate and keep the codec unactivated; the decoded RGB proof is a separate continuation.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
--
commit cd121818505f1ca1e16ec9c285255fe4917854ea
Author: lrq3000 <1118942+lrq3000@users.noreply.github.com>
Commit: lrq3000 <1118942+lrq3000@users.noreply.github.com>

    feat(export): hash verified decoded PNG rows with the ordinary raster grammar

    Share only the data-owned v1 raster hashing grammar. Stream the actual opaque Android samples after exact comparison; complete the receipt only after the post-decode byte identity matches. Keep the existing encoded proof, budget accounting, and execution envelope.

    Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (gpt-6.2-sol).
```

## Behavior and review focus

`data/.../model/OrdinaryRasterDigest.kt` owns SHA-256 of UTF-8 `pristinepage-ordinary-render-rgb8-v1`, NUL, two big-endian 32-bit positive dimensions, then active unsigned RGB in y-major row order. A synchronous primitive `Int` ARGB sample reader is borrowed by `appendRow`, never retained. The declared width is checked before requesting samples, opacity before hashing, and every sample in each complete row must be supplied. Exact row count is required; a bad/extra row, sample/checkpoint exception, early finish, or repeated finish cannot yield a partial digest. No stride padding or whole packed raster. One fixed-cap block of exactly `3 * min(width, 4096)` bytes, no more than 12 KiB, is digested in place and its reference is dropped on the last row. `OrdinaryRenderExecution.rasterSha256` keeps the existing borrowed `RgbImage`, active traversal and per-block/final cancellation; its envelope serialization and execution/cache identities are unchanged. A one-primitive-sample interface avoids a per-channel callback or boxed pixel pipeline; copying a full raster or an additional width-sized RGB row is unnecessary.

`OrdinaryPngVerifier` still checks the encoded profile before native allocation and keeps the private decode, API floor, sRGB/unscaled/opaque checks, typed refusals, stable closed-file and expected-raster ownership. Every callback checks the actual Android ARGB sample against the retained expected RGB and returns **that actual sample** to the accumulator. The verifier releases its private bitmap/native reservation, rescans the closed bytes, compares both encoded size and SHA-256, checks cancellation, and only then finalizes the raster SHA and returns `VerifiedPngIdentity(encoded, rasterSha256)`. Pixel mismatch, opacity/format/profile rejection, cancellation, I/O and resource errors return no receipt. The existing budget, accounting formula and `4W` ARGB scratch charge are unchanged: the digest block uses at most 12 KiB of the existing 1 MiB fixed work allowance (remaining at least 1012 KiB for other provisional work); only small fixed digest state crosses into rescan, not the block or native raster. Finalization OOM maps to the same typed allocation failure. The allowance is not measured PSS, and dropping a reference does not promise immediate GC.

Tests use independent literal and JCA vectors, including high unsigned bytes, row order/shape, packed versus padded storage (with no final padding), wrong widths and row counts, truncated/nonopaque samples, one-shot finish, and cancellation between blocks on a 4097-pixel row. The existing `RenderCompletionTest` oracle still binds the 791-byte envelope and exact execution/cache/raster hashes. The compiled Android fixture creates its own asymmetric 3×2 RGB PNG without the writer; it verifies exact reopening, encoded extent/JCA SHA-256, native RGB, and literal canonical raster SHA-256 `9434457103f2fbf1dd09735edfea2d591e550254ebca19429aa6b1af9224ff83`, distinct from the compressed file. No expected hash is fed back as a proof.

## Commands and retained evidence

Run only after positive upstream exit and verified toolchain readiness. The preview `assemble.json` SHA-256 `284fcfb36f18993548ef8a8df2a2026033efd621c4dcd100fba2add4ad599b56` reported exit 0 at `2026-11-18T20:17:23.614344+00:00`. The first bridge preflight found no Java/Javaw. Process-only environment: JBR 21.0.8 `C:\Program Files\Android\Android Studio\jbr`; SDK `C:\Users\local-user\AppData\Local\Android\Sdk` (platform 36 rev 2, Build Tools 35.0.0); shared `GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`; worktree-local `.android-user-home`; `ANDROID_SDK_HOME` unset. The exact pre-existing Gradle 8.13 launcher and distribution `.zip.ok`, pinned wrapper JAR/properties, SDK paths, AGP/Kotlin/test dependency cache roots, strict verification and existing lock inputs were checked. Gradle's existing 4 GiB in-process compiler setup was unchanged; each run had a guarded 8 GiB disk floor and a 395 s owned wrapper wait.

```powershell
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests app.pristinepage.data.model.OrdinaryRasterDigestTest --tests app.pristinepage.data.RenderCompletionTest :image-android:testDebugUnitTest --tests app.pristinepage.image.export.OrdinaryPngContainerTest
.\gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest :image-android:testDebugUnitTest :image-android:compileDebugAndroidTestKotlin
```

Raw files are retained in the worker's ignored `validation/raw/ordinary-png-rgb-bridge/`. The per-attempt folders contain command/setup and input hashes, exact stdout/stderr, wrapper start/exit, Java process checks, fresh XML, summary and XML hashes. The focused command was used first against a test-only absent-feature seam (deleted for GREEN). The six assertion failure messages were empty digest, wrong row order digest, missing width/dimension/count/truncation refusals, and missing large-row digest; the legacy hashing control passed. These are host REDs for the data API only, not executed PNG verifier/Android REDs.

| Attempt | Fresh results and process outcome |
|---|---|
| `red` | 20 data cases (6 assertion REDs, 14 pass) and 10 image cases pass; 3 XMLs. Wrapper PID 3148 exited 1 in 164.850 s; all 38 tasks executed, no Java at exit or after settle. RED digest XML SHA-256 `378a977a3e54c25a1bf8d16155e156be3823c3b83a021d1821640b59885f54a1`. Cold compile emitted pre-existing warnings in `DirectorySync`, `DocumentCommandsTest`, `ExportSettingsTest`, `FileOperationOwnersTest` and `RepositoryContractTest`. |
| `focused` | 20 data + 10 image cases pass (3 XMLs), including all 7 raster tests and 13 `RenderCompletionTest` cases. Wrapper PID 31808 exited 0 in 41.205 s; 12/38 tasks executed, no Java at exit or after settle. |
| `full-compile` | 545 data and 157 image JVM cases passed, 71 fresh XMLs, no failures, errors or skips. Wrapper PID 21396 exited 0 in 53.330 s; 20/56 tasks executed, including `:image-android:compileDebugAndroidTestKotlin`. `OrdinaryPngExportTest.class` SHA-256 `ef59a90d58fafcee0766293b7546d98ffda153e07a405da4ce6e453697c4b0d7`, 17 source cases compiled, zero executed. No Java at exit or after settle. |

`evidence.sha256` pins 108 retained files, including all run logs/XMLs/exit/setup/process records, the raw runner and exact replay patch. Its SHA-256 is `2f700a1bf6bc6707b56465c289caef49739e484c227eea2fcd6cbe508edffa6d`. The full-run source input index SHA-256 is `ebd99ce308e1b695440b1fbe77e4a2399f9574cbb22d6f6b92670d7e5fc4b1e6`; the evidence-only handoff was finalized after the run, while all five tested bridge paths match. Final `slot-release.txt` SHA-256 `13ebbd69aecea61da9ade514dba693092ee010dc59dd765f3a5dc964619adfcb` records `2026-11-18T20:44:14.9309808Z`, 24,831,754,240 free C: bytes, no Java/Javaw, and all three wrapper PIDs absent following their recorded exits. Serialized slot returned to coordinator; no worker Gradle, host tests or device operations after release.

## Remaining gates

Independent bridge review and local replay are coordinator decisions; no nested delegation. Verify production provenance from actual decoded samples and finalization ordering separately from the literal equivalence test, which cannot by itself catch echoing a hash of the matching, retained expected raster. All instrumentation cases are unrun. Confirm actual writer chunk inventory, full platform PNG/RGBA/color and alpha handling, cancellation/pressure behavior and measured footprint on an explicitly authorized runtime. Retain the standalone review's Minor gaps: independent format/shape and unchanged-byte stage controls, streaming block and exact inclusive chunk-count witnesses, and inherited unobserved reservation-object OOM charge risk. The bridge is not proof against concurrent byte/path replacement and does not establish a seal, durability, terminal producer/journal proof, Room callback integration, profile activation, `READY`, `PRIVATE_READY`, tagged-source nonpropagation, EXP-02 completion or user-facing acceptance. No emulator, ADB, device, acquisition, signing, APK assembly, destructive cleanup, merge, or push was performed.
