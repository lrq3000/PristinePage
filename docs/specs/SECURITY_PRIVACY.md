# Offline security, privacy and release controls

Version 1.0, 2026-11-14. **DESIGN policy, not a completed security audit or tested application.** Applies to all production variants, dependency startup, storage, camera/import, render workers, export/publication and optional ML assets. Read [PRD.md](PRD.md), [ARCHITECTURE.md](ARCHITECTURE.md), [DATA_MODEL_AND_STORAGE.md](DATA_MODEL_AND_STORAGE.md), [CAPTURE_IMPORT_EXPORT.md](CAPTURE_IMPORT_EXPORT.md), [PERFORMANCE_AND_OFFLINE.md](PERFORMANCE_AND_OFFLINE.md), [LICENSES_AND_SOURCES.md](LICENSES_AND_SOURCES.md) and [VALIDATION_PLAN.md](VALIDATION_PLAN.md).

## Evidence, protected data and honest boundaries

**VERIFIED DOCUMENTATION** marks specific inspected public API behavior, with sources read through Svipall on 2026-11-14. **DESIGN** marks requirements for future implementation. **UNVERIFIED IMPLEMENTATION** covers behavior that still needs the pinned build, providers and actual devices. No penetration test, manifest extraction, network trace, backup/restore experiment, native-code audit or application test was performed while writing this document.

Protect original bytes, normalized pixels, thumbnails, masks, recipes, execution history, titles/page order, source/provider URIs, EXIF/profile metadata, export files and share/publication records. Threats include malicious/malformed selected images, hostile document providers or sharing recipients, unintended component access, path traversal, dependency networking, OS backup transfer, stale jobs, overbroad logs and accidental deletion/overwrite. Integrity and source retention matter as much as confidentiality: an attractive output must not replace the immutable documentary source.

The application controls its own storage, processing, manifests and grant choices. It cannot promise confidentiality against a compromised/rooted OS, an already unlocked device attacker, malicious keyboard/accessibility services, another camera photographing the display, recipient-made copies or a user-selected cloud-backed provider. App-private storage is not a claim of application-level end-to-end encryption. No password-protected PDF, cryptographic authenticity, forensic equivalence, tamper-proof storage or secure physical erasure is promised by P0.

## Offline by construction

DESIGN production manifests must contain **no INTERNET permission** and no ACCESS_NETWORK_STATE permission. There is no account, telemetry/crash-upload SDK, remote license check, hosted example, remote font, first-use model download, cloud fallback or remote configuration dependency. Offline functionality must work on a fresh install with only installed assets. A network-capable SDK is not acceptable merely because its advertised API is “on-device.”

Audit the **merged manifests and final packaged artifacts for every production variant**, not just app/src/main/AndroidManifest.xml. Dependency manifests, initializers, services, native libraries and optional runtime flavors can add permissions or network code. Treat a reintroduced INTERNET permission as a release failure; remove the dependency/capability or seek a separately approved product change rather than trusting an unused setting. Build-time dependency downloads happen in the separately authorized development environment and are not an application runtime capability.

Avoid Play-services availability as a functional prerequisite. The system photo picker may already exist or be backported on a particular device; its availability must be queried, and ACTION_OPEN_DOCUMENT works as the fallback without installing a module through the app. Do not add the manifest service that requests the Play-services photo-picker backport. [Official photo-picker availability/backport/fallback guidance, inspected 2026-11-14](https://developer.android.com/training/data-storage/shared/photo-picker).

The system picker may show cloud-backed content and another app receiving a share may upload it. These are external app/provider actions under the user's choice, not proof that this application's runtime uses the network. The UI must explain this boundary without claiming the provider is offline or that a recipient cannot transmit a shared file. Airplane-mode acceptance uses locally readable provider bytes and complete app-owned exports, with a controlled unavailable-source error otherwise.

## Minimum capability and component surface

- CAMERA is the only P0 dangerous permission, requested just in time for Take photo. Refusal/revocation must not block import/library. No microphone, location, contacts, advertising ID or broad media/library grant is required.
- System-selected photo/SAF URI grants replace READ_MEDIA_IMAGES, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE and MANAGE_EXTERNAL_STORAGE. Never add legacy storage permissions simply because a generic SAF tutorial also discusses other media access paths.
- Do not request all-files access, package-wide installed-app enumeration or a directory tree just to import selected photos or Save as a PDF. Share chooser intents do not require a broad package inventory.
- Launcher activity is exported only as required for launch. Other app activities, services and receivers are non-exported unless a reviewed Android/WorkManager requirement demands otherwise. Audit library components against the pinned target. The P0 app has no arbitrary external VIEW/SEND image intake or deep-link edit/delete API; adding one needs a new untrusted-input/result-routing review.
- A FileProvider is non-exported with grantUriPermissions=true, authority derived from the final application ID, and XML paths exposing only `filesDir/shared-exports/`. No `root-path`, broad `files-path path="."`, entire cache root, external-storage root, source/journal/DB path or writable grant is allowed. [FileProvider configuration and temporary-grant API, inspected 2026-11-14](https://developer.android.com/reference/androidx/core/content/FileProvider).
- WorkManager/foreground notification permissions and service declarations are selected only for the actual target-compatible scheduling path. Do not predeclare unrelated foreground service types or promise unlimited background export. Notification denial must not corrupt a queued private job.

A proposed future FileProvider path resource is narrowly scoped:

```xml
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <files-path name="finished_exports" path="shared-exports/" />
</paths>
```

Use a small application-owned FileProvider subclass if required by the supported implementation rather than relying on unsupported direct-provider assumptions. Registration must have `android:exported="false"`, `android:grantUriPermissions="true"` and a variant-correct unique authority. Do not include a document title or path in the authority. Test requests outside the allowlisted subtree, traversal-encoded URIs, wrong authority and ungranted clients.

## Private durable originals and untrusted import

The operator-approved [library portability amendment](DATA_MODEL_AND_STORAGE.md#library-portability-amendment) is an explicit exception to the private-only and rendered-output-only defaults below. It permits one user-selected local library tree and separately confirmed folder export/import, not hidden backup, global storage access, cloud sync, or a grant obtained by ordinary photo import. A portable library contains unchanged originals and may expose their embedded metadata, album titles, recipes and saved OCR to the selected storage provider or anyone with access to the folder. Keep grants and live worker state private, tell the user when a selected root is unavailable, and never silently switch to the default library. Reject an unrecognized folder non-destructively; do not treat an external database, manifest or filename as trusted input.

Sources, masks, recipes, DB/journals and committed artifacts live in app-private credential-protected storage. Durable originals never live only in cache, a temporary URI permission, a gallery-visible directory or another local workspace. Copy selected bytes privately, hash them, validate content, normalize orientation/color under the explicit policy, then commit page ownership. Provider URI access is not ownership or an immutable copy. Persisted grants can cease to work after movement/deletion; release them after a completed copy or final abandonment. [SAF access and persistable-permission caution, inspected 2026-11-14](https://developer.android.com/training/data-storage/shared/documents-files).

Treat all selected bytes, MIME declarations, names, file lengths, ICC/EXIF chunks and provider behavior as untrusted. Enforce the shared DESIGN limits: ≤100 MiB encoded, ≤48MP metadata, long edge ≤12000 and ≤100 active pages/document, followed by real resource admission. These are proposed safety starting limits, not measured product guarantees. Metadata can lie; count copied bytes, validate real container/header dimensions and decoded dimensions, reject animation/HDR/unsupported profiles/corruption, and bound parser work before allocation. Use checked Long arithmetic and test malicious/extreme/tiny cases. Decode must not execute external references, arbitrary URI loaders or native plugin formats.

Do not disable decoder safety checks to “recover” malformed documents. Pin maintained image/EXIF/color/native dependencies and minimize optional codecs. A library/runtime change reopens the relevant attack-surface and pixel-parity tests. Native OpenCV/runtimes stay behind validated dimensions/strides/ownership checks; a permissive software license is not evidence of memory safety. Optional ML assets are signed-build allowlisted by exact cryptographic hash and local provider contract, never loaded from a document-supplied arbitrary path. No custom executable operators or downloaded code are accepted through imports.

Store original metadata only inside unchanged private source bytes plus strictly necessary bounded provenance. Rendered JPEG/PNG/PDF/share files must omit GPS, serials, maker notes, original names, comments, private URIs and unapproved thumbnails/attachments. User-facing neutral names are sanitized, limited in length and independent of filesystem storage IDs. Originals remain intact; stripping metadata from an export is not a rewrite of the original. Explicit share of originals is outside P0 rather than a hidden enhancement-application behavior.

## Database/file integrity and job safety

Room ACID transactions cover metadata, not file moves or SAF writes. Journal operation IDs before staging, verify/seal/move complete private files, and commit visibility only after preconditions pass; reconcile every boundary. Source deletion requires confirmed unreferenced ownership after page/history/Undo/snapshot/job/mask pins, not an optimistic cached refcount. A duplicate page shares immutable source bytes but not mutable recipe state. Read-only frozen export pages and checkpoints cannot be modified by later editor actions. Full schema/recovery protocol is in DATA_MODEL_AND_STORAGE.

Keep expectedRevision CAS checks for recipes/document order, session-bound Activity Results, job idempotence keys and fenced execution leases. A stale generation may finish privately but cannot become current preview; an old worker cannot publish a new page/export after cancellation or lease loss. A decoder/provider failure is never silently replaced by another algorithm under the requested label. Preserve source and last accepted recipe when resources, storage, permissions or providers fail.

No SQL built from user titles or source metadata, and no caller-controlled path concatenation. Use bound Room queries and opaque IDs; canonicalize private relative paths and prohibit traversal/symlink escapes before file operations. Avoid holding a DB transaction across provider IO or inference. A missing/future schema or failed migration preserves data/read-only recovery state; destructive migration fallback is prohibited. Room explicitly warns that fallback-to-destructive-migration can permanently delete data. [Official migration guide, inspected 2026-11-14](https://developer.android.com/training/data-storage/room/migrating-db-versions).

No source image, URI string or provider credentials in WorkManager input/output Data; use JobId and private repository lookup. WorkManager is a persistent scheduler, not a guarantee of immediate execution or completion after force-stop. Use coroutine cancellation for interactive previews and persisted job checkpoints for committed work. [Official scheduling guide, inspected 2026-11-14](https://developer.android.com/develop/background-work/background-tasks/persistent).

## No hidden backup or device transfer

VERIFIED DOCUMENTATION: Auto Backup participation is normally enabled for eligible applications unless disabled. `android:allowBackup="false"` disables cloud backup, but on some Android 12+ manufacturer implementations it does not by itself disable device-to-device transfer. API 31+ target/runtime combinations use data-extraction-rules; Android 11 and lower still require legacy backup rules. Rules are transfer-type-specific. [Official Auto Backup guide, inspected 2026-11-14](https://developer.android.com/identity/data/autobackup).

DESIGN: disable backup in the merged production manifest, exclude **all** application content in both rule families and both cloud/device-transfer sections, and do not implement BackupAgent/key-value backup or account sync. This includes images, previews, share copies, DB/WAL/SHM, preferences, job state and source/provider URI records, not merely the source directory. OS/OEM behavior must still be tested; do not describe the manifest flag alone as a universal no-transfer proof.

The future manifest uses these attributes (names are future app resources, not files created by this documentation):

```xml
<application
    android:allowBackup="false"
    android:fullBackupContent="@xml/backup_rules"
    android:dataExtractionRules="@xml/data_extraction_rules">
    <!-- Other approved application declarations. -->
</application>
```

`app/src/main/res/xml/backup_rules.xml` must be a legacy full-backup-content document excluding every available app-data domain. `app/src/main/res/xml/data_extraction_rules.xml` must have identical exclusions inside both cloud-backup and device-transfer. The snippets below are normative domain/path policy; lint and actual backup/transfer testing must confirm the generated resources against the bootstrap-pinned SDK. No inclusion rule may silently re-enable one sensitive subtree.

```xml
<full-backup-content>
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
</full-backup-content>
```

```xml
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
```

Do not enable any cross-platform-transfer opt-in supported by newer Android versions. The inspected official guide documents newer transfer formats, but this is not a claim about the latest SDK or a requirement to add an unsupported XML element on older compile SDKs. At bootstrap audit that SDK's supported backup/transfer schema and defaults and apply the no-transfer policy to any new channel. Record cloud and device-transfer test results separately on supported API bands, including OEM variation; unsupported tests stay explicitly unverified.

The user must be told that private originals can be lost after uninstall, app-data clearing, device loss or unrecoverable hardware failure because automatic cloud backup/sync is disabled. Explicit PDF/JPEG/PNG exports are the P0 portability route; they preserve rendered output, not an editable full-project backup. A future manual encrypted project backup is an additional optional design, not a hidden background upload or a P0 capability claim.

## Export, external publication and recipient boundary

Only a verified complete app-private artifact may become shareable/publishable. Freeze exact page order/source/recipe/masks/provider hashes/dimensions before processing; do not export mutable current state. Verify the encoded file or PDF independently, store its hash/length and omit private metadata. Platform PdfDocument may retain finished pages, so apply the whole-document admission/profile policy from CAPTURE_IMPORT_EXPORT; no unsafe allocation attempt or hidden downscaling is a privacy/integrity workaround.

Save as is an explicit destination action, normally ACTION_CREATE_DOCUMENT; it does not overwrite an existing same-name file. SAF copy/rename/delete is not assumed to be an atomic remote publish. Stage a complete private file first, record exact target ownership/consent, copy/close/count, and perform readback where available. Mark partial/uncertain external output truthfully and retain the complete private source for retry. Cleanup may target only the newly created file of that attempt when supported and ownership is certain. Never delete provider originals, existing unrelated files or a directory tree. [SAF create-document/capability guidance, inspected 2026-11-14](https://developer.android.com/training/data-storage/shared/documents-files).

Sharing uses FileProvider content URIs, read-only temporary grants and exact URI ClipData/streams. No file:// URI, absolute private path, write permission, persistable permission, prefix permission or arbitrary source root crosses the boundary. Multiple images preserve frozen order and use compatible MIME. If URI permission is manually granted to a particular package, record and revoke that exact grant; Intent-delivered grants have recipient component/stack lifetime semantics. [Official FileProvider reference, inspected 2026-11-14](https://developer.android.com/reference/androidx/core/content/FileProvider).

DESIGN share retention is 24 hours with explicit Stop sharing and grant revocation before private share-copy cleanup. Scheduling can delay expiry processing; there is no exact-to-the-second revocation guarantee. Do not revoke immediately after launching the chooser because asynchronous recipients may not have opened the file yet. Test recipient reads across backgrounding/reboot/expiry; an app that already has a descriptor or copied bytes may retain content after revocation. Revocation is a limit on further access, not remote erasure. The chooser callback proves neither delivery nor recipient retention, and the app cannot remove copies stored in another app or cloud service.

## Logs, diagnostics, UI and retention

Production logs contain bounded safe event codes, opaque operation IDs, non-sensitive size/resource categories and sanitized exception classes only. Never log document titles, source paths/URIs, external target URIs, GPS/EXIF, source/thumbnail pixels, tensor contents, model prompts, clipboard content or raw provider exception messages that can embed filenames. Hashes can correlate documents and remain private provenance, not public telemetry identifiers. No third-party crash attachment/uploader is enabled. Debug builds are separately labeled and use synthetic/permission-cleared fixtures; diagnostic export requires preview/redaction and explicit user action.

Do not place private source URIs, titles or raster bytes in notifications, widgets, saved-state Bundles or deep links. Notifications use neutral operation progress and open an authenticated OS app context rather than exposing a file URI. Keep sensitive UI state in scoped ViewModels/private records. For app-switcher exposure, use a tested secure-window/recent-task redaction policy on document screens where feasible, with explicit accessibility/user-capture trade-offs. Do not claim FLAG_SECURE stops cameras, rooted capture or all OEM screenshots; decide and validate the exact behavior at implementation rather than advertise a privacy guarantee from a UI flag.

Undo/history retains source references by design and is disclosed: latest 20 document edits for up to 24 hours as an initial policy, with confirmed permanent deletion distinct from reversible removal. Sources referenced by active jobs/snapshots cannot be reclaimed immediately. Completed private exports remain visible and independently deletable under the disclosed storage policy; temporary share copies have a separate lease. A permanent document-delete confirmation explains retained export/snapshot copies and that external copies are unaffected. Do not promise secure flash erasure from unlinking or cache eviction.

## Dependencies, models and reproducible release

Pin compatible stable Android/Kotlin/CameraX/Room/WorkManager versions and dependency verification hashes at bootstrap; retain the actual release build/ABI/manifest/dependency record. Do not pin “latest” by prose or copy newer-family Room examples into an older artifact. Review native ABI and platform-page-size compatibility for the chosen distribution target. Updates that change decoders, normalization, recipes, assets or backend precision must preserve historical identity or migrate with visible explicit version changes.

Every packaged model is an allowlisted local asset with source/version/hash, provider contract, code/weight redistribution rights and quality/device acceptance. Missing or restricted assets remain gated and never trigger a download. Optional runtimes are included only for a chosen accepted provider, not both by default. Production needs no experimental model for basic capture/import/edit/export. Keep untrusted model files out of dynamic execution paths and exclude training or user-photo collection entirely from P0.

An Apache/MIT/BSD source-code license does not automatically cover model weights, sample photographs, trademarks or optional binaries. Retain appropriate third-party notices for any reused code; do not copy proprietary assets or claim a renamed filter is independently licensed. PdfBox-Android is a documented permissive candidate, not an approved bundled dependency in this v1 platform-writer decision. Complete dependency/model/sample licensing and security review remains a release gate.

## Acceptance and stop-ship conditions — future evidence

| Control | Required evidence |
|---|---|
| SEC-01 Production capability audit | Each final production merged/package manifest has no INTERNET/broad storage permissions, no unexpected exported components and narrowly scoped FileProvider paths; dependency initializers/native paths reviewed. |
| SEC-02 Offline cold start | Fresh install in airplane mode: camera, local import, all shipped processing/assets/examples, save/reopen, PDF/images and share handoff work without acquisition/login. Local unavailable provider content fails explicitly. |
| SEC-03 Backup/transfer | Legacy and API31+ rule families inspected in package; actual cloud-backup/restore and device-transfer attempts do not expose source/DB/preview/share/URI records; unsupported OEM/channel checks disclosed. |
| SEC-04 Hostile input | Malformed/truncated images, EXIF/ICC bombs, false MIME/length, huge products/stride, HDR/animation, stalled/revoked providers and path traversal cannot commit a page, escape roots or cause an intentional oversized allocation. |
| SEC-05 Integrity/recovery | Crash-boundary tests, reference-count races, Undo/duplicate/snapshot lifetime, lease fencing, unknown schema and low storage preserve originals/current accepted state; no destructive reset. |
| SEC-06 Sharing/publication | Unauthorized clients cannot open original/DB/out-of-root files; selected recipient can read exact finished artifact with temporary read-only grant; revoke/expiry/new-open behavior and partial external outcomes tested truthfully. |
| SEC-07 Metadata/diagnostics | Export inspection finds no GPS/private source names/URIs; logs/crash records/notifications do not carry private data; actual image/PDF metadata is checked, not inferred from encoder defaults. |
| SEC-08 Rights/assets | Exact build/dependency/asset hashes and rights records; no unresolved weights/proprietary samples/network runtime initialization in the release allowlist. |

Stop ship for unintended network/backup transmission, source overwrite/loss, wrong-document result routing, unauthorized file exposure, source/reference deletion races, silent recipe substitution or successful labeling of a partial export. Keep a failure, unsupported capability, skipped test and operator acceptance as distinct states. T-01/T-03–05/T-11/T-12 and V-02/V-06/V-07 consume these future records; none is marked passed by this specification.
