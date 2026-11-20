# Capture and gallery preview implementation plan

> **For agentic workers:** Use test-driven development with one independent final review. Steps use checkbox syntax.

**Goal:** Give the user an offline debug APK with separate camera, gallery and file entry points.

**Architecture:** The existing Library session owns the durable album reservation and exact batch identity. CameraX owns single-shot JPEG capture and submits closed bytes through the existing import controller; system UI grants access only to selected external images. No new persistence or renderer.

**Tech Stack:** Existing Kotlin, Compose, CameraX, AndroidX Activity and Room.

**Spec:** `docs/specs/CAPTURE_IMPORT_EXPORT.md` §Capture and §Photo picker, with the user-approved explicit gallery/files treatment for this preview.

## Constraints and review focus

- Exact base `bacb96dcac08c28289020bc8af302a0d6a39a2ff`; branch-local preview only, no main integration. No dependencies, broad storage access, data migration, phone, emulator/ADB, publication, PNG work, or cleanup of old artifacts. Coordinator owns the sole offline host slot. Reserve 90 active minutes inclusive of review and packaging; check at 30 minutes active, any blocker and handoff.
- Verify no silent file fallback from gallery, no external reference to private camera staging, no retargeted or stale result, no permission refusal trapping the user, and no double shutter or partial save. Native camera/provider acceptance remains separate from host and build evidence.

## POC-ACQUIRE-01 — T-04/T-05 preview slice

- [x] Add JVM regressions in `ImportLibrarySessionTest.kt` for route separation and canceled/stale capture results. Two observed failures out of 28 on the baseline, both from missing routes. Added Android contract witnesses for the picker intents and camera private handoff; no device execution.
- [x] Use the existing request/session machinery in `capture/`, `library/` and `MainActivity.kt`. Model gallery, files and capture as explicit request kinds. Preserve the established ordered parser, no provider read on cancellation, and no launch or reshoot for interrupted capture.
- [x] Wire CameraX via `capture/CaptureScreen.kt` and `CaptureController.kt`, a single manual shot and a destination-owned binding. Put staged JPEG under a narrow non-exported provider, without granting the URI to any app. Keep staging separate from `original.part` and route through the existing album ingest, keeping successful originals unchanged.
- [x] Run guarded offline `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`: 116 JVM tests passed across 17 suites and Android instrumentation compiled. Completed independent review and closed both findings; commit the tested changes locally.
- [ ] On exact reviewed preview commit, assemble `:app:assembleDebug` offline using the existing approved development signer without copying/regenerating the key. Verify signature/package/manifest/alignment and hash; deliver a new named local APK without changing previous outputs. Approval: user confirmed the isolated plan and explicitly requested a debug APK to try. Do not install.

## Evidence and rulings

- Sole slot stays serialized through `build/acquisition-evidence/` under this worktree, no second worker using Gradle. First Android compilation exposed a trailing-lambda compatibility mistake; preserving the function as the last constructor parameter fixed it without changing test callers.
- Independent source review flagged a shutter enabled before real preview and missing cleanup when CameraX writes after cancellation. The fix waits for open/streaming state, recognizes CameraX errors, and transfers staging ownership only when the live batch accepts the handoff. The callback-acknowledgment test failed as intended before the fix. A stage is best effort after process death; restoring a batch does not automatically relaunch the camera. Full device behavior remains open.
- There is no change to the existing format/decoder limits. The prototype decoder itself requires API 28+; this preview keeps the declared minimum APK SDK at 26. Full flash, capture orientation across locked rotations, provider compatibility, and the exact native correction round trip are not claimed.
