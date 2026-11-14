# Common mistakes and frequently used commands

Read this file at task start and after resumption. These are anticipated pitfalls unless an actual implementation incident is recorded below; they are not invented past application bugs.

## Avoid these errors

- Inferring neural capability from an available file or treating restored RGB as an illumination divisor or inverse grid.
- Mixing RGB/BGR, NHWC/NCHW, Lab8 lightness and linear luminance, EXIF display rotation and source-pixel orientation, or normalized corner/edge coordinates.
- Mixing corner-aligned learned-grid interpolation with half-pixel resizing; applying a second warp to an image-output provider.
- Applying strength/protection before clipping when the recipe specifies candidate-then-blend, or blending twice in adapter and orchestration.
- Sharpening/denoising by default to compensate for poor illumination estimates; clipping faint content before a later supposed restoration step.
- Treating preview processing as a full-resolution export oracle; using an arbitrary tile for a global-context algorithm without equivalence evidence.
- Updating a document's current recipe/order while an export rereads it; publishing a result whose generation no longer matches.
- Treating SAF URI access as a durable private copy; assuming provider rename/delete is atomic; reporting a partial external output as complete.
- Assuming moving a file and updating Room is one transaction. Use journals and reconcile both sides after interruption.
- Assuming WorkManager runs immediately or completes after force-stop. Show pending/interrupted state truthfully.
- Packaging unresolved/research-restricted weights under a permissive code license; installing both inference runtimes before a selected provider requires them.

## Commands after implementation bootstrap

The future application workspace must contain its own Gradle wrapper before these commands are valid. On Windows invoke `gradlew.bat`; on POSIX invoke `./gradlew`. Never invent a successful run from the existence of these examples.

```bash
./gradlew :core:test
./gradlew :data:testDebugUnitTest
./gradlew :image-android:testDebugUnitTest
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

For physical-device tests select an explicit authorized ADB serial and preserve it in the test record. Build first, then run the appropriate connected test task against that target; never install/test on every connected device implicitly. Use normal incremental tasks, not `clean` or `--rerun-tasks` by default. Signing, installs, emulator launches and publication require the operator's actual authorization.

## Incident record template

Record incident ID/date/build/device, reproducible symptom, known cause versus hypothesis, affected data/contracts, smallest reproduction, attempted commands, verified remedy, regression test/evidence, remaining limits and impacted tasks. Do not paste photos, URIs containing private names, credentials or full sensitive logs into this file.

No application implementation incident was recorded at package creation. The entries below are actual implementation-preparation observations, separate from that historical receipt.

### INC-001 — source receipt filename-order convention

Date 2026-11-14; attempt T01-20261114-A; no app build/device involved. The first setup guard computed technical-content SHA-256 `823ddf2d31a203ae5ea9c913753f365fd8ee59b1b79569f923a85ceea2e6885b` and stopped before creating any files, rather than copying a package with an unexplained identity mismatch. Root cause: Python code-point filename sorting differs from the receipt's case-insensitive filename ordering for the lowercase incident/working-file names. Holding file bytes/hash-stream encoding constant, case-insensitive ordering reproduced the documented `54f5d3f72134075b40ca178f8e68f39a626352de2632b2e7db4fe598aba263c3`; the source files had not changed. Corrected the setup guard to use `name.casefold()` and verified all 24 copied files plus unchanged original hashes. This was a provenance-check convention issue, not a passed application regression test or an amendment to SPEC. Retain both digest observations; future package-identity checks use the recorded explicit ordering.
