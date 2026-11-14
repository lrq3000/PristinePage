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

No application implementation incident is recorded at package creation.
