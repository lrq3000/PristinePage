# fix(export): keep invalidated PNG raster proofs from completing

**Initial motivation:** The PNG RGB bridge rejects nested accumulator operations, but a callback could catch that rejection and let the outer append publish a digest. Independent review also found that the Android suite did not directly distinguish a failed post-decode byte rescan from other refusals.

**Final purpose achieved:** The accumulator now fails closed on caught reentrancy. The product PNG verifier is unchanged. A compiled Android test isolates the same-pixels/different-bytes case; it has not run and cannot establish native PNG support, sealing, or readiness.

**What changed:** The data-owned single-use accumulator distinguishes an in-flight row from terminal invalidity, and refuses to resume if a borrowed sample or checkpoint invalidates it. Two JVM tests catch a nested append from the sample and a nested finish from the checkpoint, then require the outer operation and any later finish to fail. Existing successful rows, byte grammar, extent, padding exclusion, cancellation and scratch-release behavior stay unchanged. The Android test generates two equal-length valid PNG variants with the same compressed stream and two adjacent IDATs split at different offsets. It independently checks exact pixels and different encoded SHA-256, swaps only its own fixture after native release and before rescan, and requires `export_png_byte_identity_mismatch`, no receipt and zero outstanding charge.

**How and why:** The old negative row counter conflated in-flight and invalid states, allowing the outer append to reset a caught nested failure. Enter `APPENDING` only after width and scratch admission; check it after either borrowed callback. A nested call changes the state to `INVALID` before refusing, so the outer call cannot commit a row even if its callback recovers locally; `finally` keeps escaped exceptions terminal too. No new callbacks, locks, scratch arrays, public signatures, or identity domains. The Android test reuses a checkpoint and the existing shared-budget phases, not a new production test seam; it tests rescan ordering under a deliberate test stimulus, not safety under concurrent path replacement.

**Base and replay:** Main target `2ae2eed9643c10b5a2718bf16ae465995d9ba05f`. `work/pristinepage-png-proof` starts from that exact base; commits `3ca16fa` → `75562d3` → `80898e13f1dab8f749908e9659df92ea3a4b29c8` replay the already authored PNG bridge, giving a tree byte-identical to `f4829f9e0f1c13c6c49b6ee07c4851c24a6156a9`. If the bridge is already present, replay only the hardening delta after `80898e1`; do not import the base work twice. The delta does not modify `OrdinaryRenderExecution.kt`, the overlap with the terminal reader. The hardening milestone and this receipt are one new commit: obtain its full description using `git show -s --format=fuller work/pristinepage-png-proof`. The bridge's original commit descriptions are in [its handoff](ordinary-png-rgb-bridge-handoff.md).

**Verifications:** Run under the sole build slot, exact verified Gradle 8.13/JBR 21.0.8/cached SDK from the README, no dependency acquisition, signing, APK or device operations:

```text
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests app.pristinepage.data.model.OrdinaryRasterDigestTest
gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest :image-android:testDebugUnitTest :image-android:compileDebugAndroidTestKotlin
```

- [x] Two intended assertion REDs, seven passing controls before changing production. A first evidence preflight stopped on a relative-path typo before launching Gradle; a later summary expected the second test's shorter name and was corrected against the retained XML, without rerunning.
- [x] Full affected GREEN: 547 data and 157 image JVM cases, zero failures/errors/skips, including the unchanged execution writer golden. 18 PNG Android tests compiled, zero ran; `OrdinaryPngExportTest.class` SHA-256 `1c867399717039c72d740200f6475a683f7a6ff96f61fae952246a0a668214aa`.
- [x] Both wrappers exited with expected status and all observed build process handles exited. No unresolved or live build tools in either postflight. Evidence in ignored `validation/raw/export/ordinary-png-proof/`; 88 entries in `evidence.sha256`, SHA-256 `24c86e5e13ae10e5908416b1e16a77e079ffb61398a66b2d80f23f0db212cf3b`.

**Still open:** Independent review of this delta; combined replay with the terminal reader/DAO after any changes to target `main`; runtime and tagged-source metadata proof, stable file ownership and committed producer/seal checks, export profile admission/availability and artifact-set verification. No `main` update, push, publication, or export activation.

**Agentic stack:** OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).
