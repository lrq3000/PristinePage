# Carnet presentation implementation plan

> **For agentic workers:** Use superpowers:executing-plans for the approved isolated Carnet continuation.

**Goal:** Apply visual direction A and save a Home-only Stack or Row preference without changing the application journeys.

**Architecture:** Keep the existing Material and thumbnail renderer boundaries. Use a lazy row over guarded ordered metadata, not an eager bitmap list.

**Tech Stack:** Kotlin, Compose Material 3, existing SharedPreferences and host Gradle tests.

**Spec:** [Carnet](../../design/carnet/README.md). This adds a presentation slice, not a replacement scanner plan.

## Constraints and review focus

Verify independent fallback for old or malformed preferences, multiple rows and the last page, stale/replaced thumbnails, small-screen text and camera contrast. No schema, dependencies, signing, device access, publication of Carnet or changes to `main`.

- [x] Add host tests for `AtelierSettings.fromStored` and `HomeThumbnailLayout.pages` (default, corrupt settings, literal ordered 0/1/3/5-page cases). Observe missing-contract RED before implementation.
- [x] Implement the existing preference owner's set/get path, Home selector, and lazy row/stack using `AtelierThumbnail`. Adapt Compose roles, shapes, Home and Camera chrome to Carnet. Compile Android preference test and preserve the same saved page requests.
- [x] Run the new tests within the normal offline module suites, app lint and Android-test compilation; check the design and English resources, obtain independent review, and record the [evidence](../../../validation/ui/carnet-presentation-checkpoint.md). Commit the coherent diff without publishing Carnet.
- [x] Verify the published Atelier tree and ref, local archival identity, all active branch history, and a repo-local push guard with allowed/refused input.
