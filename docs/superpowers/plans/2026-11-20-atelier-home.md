# Atelier Home and Album Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for serial execution with independent review of the complete increment. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose real album titles, dates, sorting, thumbnails and offline settings in the Android interface, using the approved Atelier design and official icon.

**Architecture:** Keep the existing Room and Application graph. Add a small, guarded read-only catalog projection and reuse the revision-checked rename command. Compose consumes stable metadata and lifecycle-owned page images; preferences remain local. The direct camera shortcut and draft completion go live together only after the mixed-acquisition owner can avoid publishing empty or partially saved albums, so this prerequisite does not close all of AT-01.

**Tech Stack:** Kotlin, Room, Android SharedPreferences, Compose Material 3, the existing renderer/decoder and pinned Gradle 8.13 toolchain.

**Spec:** [Atelier](../../specs/ATELIER.md) and [v1 components and tokens](../../design/atelier/v1/README.md). This is a T-10 catalog prerequisite, not a replacement for the accepted T-01–T-12 plan.

## Global constraints

- Use the sanitized `main` and the isolated `feat/atelier-contract` worktree. Keep private Git objects, paths, test evidence, originals and credentials out of all commits. Before staging verify exact files and author metadata.
- Preserve API26+, package IDs, existing DB schema/records, JPEG compatibility and source byte identity. No new dependency, model, signing change, device operation, upload or deletion in this increment.
- The default sort is last modification descending; both date orders are descending. Name uses locale collation excluding case, and every tie uses stable ascending album ID. Sort before paging. Respect 48dp action minimum and localizable/scalable text.
- Use the verified existing Gradle distribution, SDK/JDK and cache only. Check the 8GiB free disk floor and shared build slot. Use process-local `JAVA_HOME`, `ANDROID_HOME`, `GRADLE_USER_HOME`, `.android-user-home`, and no `ANDROID_SDK_HOME` as described by the root README; no wrapper invocation if its distribution is not verified available.
- Keep release work outside this scope. Local verification creates only ignored build outputs. No emulator installation, signing or push command in this plan. Propose the exact squash and `archive/feat/atelier-contract` publication after the stable checkpoint has its own reviewed evidence.

## Review focus

- Accented and case-variant titles crossing a page boundary must use app locale, not SQLite ASCII `NOCASE` or order within the current viewport (Task 1).
- Stale ordering or a renamed title must not resurrect a previously loaded page or overwrite newer database state (Task 1 and Task 3).
- Identical page/source IDs in different display revisions must not show obsolete or mislabeled thumbnails as current results (Task 3).
- Existing invalid preferences and a canceled Settings or rename interaction must not reset unrelated values, touch source bytes or alter a timestamp (Tasks 2 and 3).
- Changing Home layout must not hide imports, lose old albums, create an eager empty album under a new `+`, or take credit for the not-yet-implemented mixed flow (Task 3).

### Task 1: Guarded sort-aware catalog

**Files:** `data/src/main/kotlin/app/pristinepage/data/model/DocumentCatalog.kt`, new `data/src/main/kotlin/app/pristinepage/data/model/AlbumOrder.kt`, `data/src/main/kotlin/app/pristinepage/data/repository/{DocumentCatalog,RoomDocumentCatalog}.kt`, and `data/src/test/kotlin/app/pristinepage/data/AtelierAlbumCatalogTest.kt`. Keep the current ID-cursor `list` for existing clients.

**Interfaces:** Add `AlbumOrder { MODIFIED, CREATED, TITLE_AZ, TITLE_ZA }`; `AlbumPosition(documentId: DocumentId, title: String, createdAt: Long, updatedAt: Long)`; `AlbumPage(albums: List<DocumentSummary>, next: AlbumPosition?)`. In `DocumentCatalog.kt` add a separate `AlbumCatalog.listAlbums(order: AlbumOrder, locale: Locale, after: AlbumPosition?, limit: Int): AlbumPage` contract, implemented by the same `RoomDocumentCatalog` and supplied through the existing Application graph to the new consumer. Existing port doubles need not implement an unrelated sort. `DocumentSummary` carries guarded nullable `createdAt` / `updatedAt` for old test/double construction only; real catalog results always populate both.

- [ ] Write a regression where 62 fixture albums are inserted in ID order opposite their timestamp order. Page with limits 1 and 10 and assert exact same ordered IDs as a full reference, no skips/duplicates, accent/case and equal-date ties, including `TITLE_ZA` ID ties. Assert read of both original stored timestamp values and typed rejection of invalid limit or metadata, plus existing list/load behavior.
- [ ] Run `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests "*AtelierAlbumCatalogTest"` and observe the missing-behavior RED.
- [ ] Implement using existing bounded ID scanning under a single guarded metadata read per requested page. For v1, retain at most `limit + 1` ordered metadata candidates in a heap, with raw guards before any title projection or narrowing, never render images in this transaction. Apply the locale `Collator.SECONDARY` over titles and an ascending ID tie-breaker independently of direction. Enrich just the selected bounded page with needed summary detail when possible. Check cancellation between bounded chunks. Invalidate the consumer cursor whenever order, locale or a known mutation changes. If a run over a representative large synthetic catalog is too slow, profile the read path and design proper indexed projections before claiming this slice production-ready; do not replace collation with per-page sorting.
- [ ] Run the focused command again; then run the data suite and confirm no changed Room schema. Verify actual stored date values and rename conflict behavior in the Room-backed witness before native acceptance.
- [ ] Commit `feat(data): expose guarded album dates and ordering`.

### Task 2: Native design roles, offline preferences and launcher icon

**Files:** new `app/src/main/kotlin/app/pristinepage/design/{AtelierTheme,AtelierPreferences}.kt`; `app/src/main/AndroidManifest.xml`; new `app/src/main/res/drawable/{pristinepage_icon_legacy,pristinepage_icon_foreground,pristinepage_icon_monochrome}.xml` and `app/src/main/res/mipmap-anydpi-v{26,33}/pristinepage_launcher.xml`; `app/src/test/kotlin/app/pristinepage/AtelierThemeContractTest.kt` and `app/src/androidTest/kotlin/app/pristinepage/AtelierPreferencesTest.kt`. Keep base dependency pins and package names.

**Interfaces:** `AtelierTheme(content: @Composable () -> Unit)` maps v1 dark roles to Compose, plus named canvas/outline/editor roles exposed as needed. `AtelierSettings(order: AlbumOrder = MODIFIED, showCreationInNameSort: Boolean = false, showCameraGrid: Boolean = true)` and `AtelierPreferences(context: Context)` hold only namespaced local preferences; expose `settings: StateFlow<AtelierSettings>`, `setOrder`, `setShowCreationInNameSort` and `setShowCameraGrid`. Invalid persisted values resolve to each field's default without clearing other values.

- [ ] Write role parity tests for baseline colors and the manifest/resource icon contract; add a native preference test reopening the same named preferences after setting and individually corrupting keys. No default key updates when a sheet is only opened/dismissed.
- [ ] Run `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :app:testDebugUnitTest --tests "*AtelierThemeContractTest"` and observe RED. Android test source compiles as part of the focused integration command; do not claim that it ran.
- [ ] Implement the palette from `docs/design/atelier/v1/tokens.json` and VectorDrawable resources from `docs/design/brand/pristinepage-icon-v1.svg`, with a v33 monochrome layer and v26 adaptive layers. Reuse the same asset for launcher/round placements. Keep composable color/font/size roles and preferences separate; no new theme toggle.
- [ ] Run the focused test, `python -B validation/ui/verify_atelier_kit.py`, and `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :app:lintDebug :app:compileDebugAndroidTestKotlin`; compile only, no device.
- [ ] Commit `feat(ui): add Atelier theme, settings state and launcher mark`.

### Task 3: Home, rename, page grid and visible controls

**Files:** `app/src/main/kotlin/app/pristinepage/library/AtelierLibraryScreen.kt`, the existing library state/actions and navigation files, `app/src/main/kotlin/app/pristinepage/MainActivity.kt`, `app/src/main/res/values/strings.xml` with idiomatic English app copy only, and existing library/Activity witnesses. The French mockup remains outside Android resources.

**Interfaces:** Preserve `LibrarySession` and `LibraryState` as ownership boundaries. Add `LibraryEvent.CloseAlbum`, `SetAlbumSort(order: AlbumOrder)`, and `RenameAlbum(documentId: DocumentId, expectedDocumentRevision: Long, title: String)`; use `RoomDocumentRepository.renameDocument` via `ApplicationLibraryActions`. `AtelierHome` and `AtelierAlbum` take metadata/state and callbacks, not raw paths or Bitmaps of unchecked lifetime. `LibraryScreen` binds them to real state; editor navigation passes `DocumentId` and `PageId` only.

- [ ] Add failing tests for order change while old page result is suspended, stale rename revision, unchanged title/no write, navigating from Home directly to an album grid and selected page, and Back restoring membership/selected context. Add native empty, loading, failure, responsive library, large-font and thumbnail churn cases with synthetic fixtures. Keep existing entry/route tests as regressions; do not remove coverage by deleting their assertions.
- [ ] Run `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :app:testDebugUnitTest --tests "*AtelierLibrarySessionTest"` and observe RED for the planned behavior.
- [ ] Implement Home and sort/settings sheets, a two-column lazy grid of real pages, and rename with guarded explicit retry. Wire stored locale-formatted date/time and up to three thumbnail identities per album as specified. Load only visible previews under the shared budget and fence ownership by album/page/source/revision; use authentic accepted thumbnails or labeled original thumbnails when accepted rendering is not yet available. Keep scroll/focus by stable identity. Expose the current working camera, gallery and files entry points without changing their semantics until the new mixed-session route is ready. Do not wire the new bottom `+` to the old eager `NewDocument` branch and claim the final design: either land the real direct shortcut with its draft owner in the subsequent capture increment or retain the existing capture controls with clear labels in this stock-compatible checkpoint.
- [ ] Rerun focused tests, then `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin`. Review the exact diff independently before considering integration. Keep the runtime and wider acquisition/wizard matrix unverified explicitly until the approved target runs.
- [ ] Commit `feat(library): present real albums in Atelier`.

#### Task 3 continuation: accepted-recipe thumbnails

**Interfaces and files:** `app/src/main/kotlin/app/pristinepage/library/AtelierThumbnailLoader.kt` adds `load(album: DocumentSummary, tile: DocumentPage): RenderedPreviewBitmap`. It uses the existing editable page reader, app-owned renderer and PREVIEW bitmap converter. `MainActivity.kt` supplies the existing serialized thumbnail permit; `AtelierLibraryScreen.kt` scopes the display to STARTED and keys it by album revision and page/source/recipe identity. Use English saved-thumbnail/unavailable labels in `strings.xml`; do not display an uncropped original as the saved rendering. No schema, encoder, or persistent thumbnail cache.

- [x] `AtelierThumbnailLoaderTest` checks exact accepted recipe and sizing for a 90° turn, stale catalog identity before render, and disposal of mismatched or degraded renders before display conversion. The focused build failed before the loader existed; the three tests then passed in the full app JVM suite.
- [x] Wire both Home and the saved grid through the guarded loader, with per-tile unavailable state. Independent review found a stale render during saved batch Undo. Revalidate the accepted page after conversion and hide thumbnails until the known replacement revision reaches the catalog; the race witness first returned the obsolete display and now refuses and closes it. App lint and Android-test compilation passed. Native presentation is a separate witness.

## Exit and next eligibility

Keep the touched scenario notes and failures with exact source identities. Do not mark AT-01 complete until Home → camera is direct and a native test confirms the route, titles, dates, thumbnails and settings. The next plan is the private acquisition draft and camera review, using the contract's staged-source, resume and idempotent-save requirements. Unblock native verification only through the authorized emulator readiness process; keep learned methods gated pending the separate rights/runtime and same-input quality plan.
