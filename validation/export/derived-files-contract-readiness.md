# U — inspected file/checkpoint contract; implementation remains blocked

Scope: coordinator's read-only preparation during goal resumption20261117-H, production base4cdb88d/document checkpointf9c4010. This is a contract-to-existing-code comparison, not a second implementation plan, completed U milestone, independent code review or executed test result. The accepted IMPLEMENTATION_PLAN and live TODO remain authoritative. No new production class, stream, directory, checkpoint, database row or output artifact was created by this inspection.

## Inputs actually inspected

Complete CAPTURE_IMPORT_EXPORT.md, DATA_MODEL_AND_STORAGE.md, PERFORMANCE_AND_OFFLINE.md, SECURITY_PRIVACY.md and ARCHITECTURE.md were read, alongside the complete194-line SourceFiles,22-line DirectorySync,39-line JobLeasePorts,75-line RoomJobLeases,40-line JobLeaseTransactions and98-line prepared DerivedFilesTest. Relevant cross-module semantics were read from PIPELINE_CONTRACTS. Hash pins below identify the inspected bytes; this is not an independent audit of every existing storage caller.

- CAPTURE_IMPORT_EXPORT.md: `3ba7cf628f751ad42a6cb031d3388775faafdc1c5bf937bbe23753695ebb3e33`.
- DATA_MODEL_AND_STORAGE.md: `1f425e48d6b689fd7e2d44a760ab27b64f0779ef671cdaec761cadb2340e11a1`.
- PERFORMANCE_AND_OFFLINE.md: `66c311ef922a86537543e38fa0e5d99819b8903df8b459b50377fc33f831aa60`.
- SECURITY_PRIVACY.md: `90eeb5dab79b4b127f570778f49dc1fb7d5935e88d4226f5e316a6f6ff981ea9`.
- SourceFiles.kt: `cdda773f8025dc2fb28576d8786deb3ce63b1925c11f43e7af1f31c8f708f80c`.
- DirectorySync.kt: `285820e3f61a9495166031825ee1c1e674ea914b0476a66d8bb09163fcda92ec`.
- JobLeasePorts.kt: `50a36c53fbbb7320d4aa729a0037951c38fabfb3f0b0b922547fc80f5899294d`.
- RoomJobLeases.kt: `1381b32200b1067f3123845f7e52db072bf6d510faddbd6af193db7a1020d74c`.
- JobLeaseTransactions.kt: `3ae4d5a6f5a6ffaa7a1bab1f46ee5d28c38d6b4104b4377a6825cee0cd95905c`.
- DerivedFilesTest.kt: `899f53c352150712aba14a43f6acaceb862644f899a40465f353f66c9c9f46de`.

The existing scoped MemPalace export retrieval receipt remains byte-identical (`e033c884ed1f0bcff58f39ff723eaf4cfacb150a607bf877352617c2cc80a484`). Its three canonical domain hashes still match; all23 returned full passage bodies/character locators were authenticated again against originals without replaying the engine. Reuse supports immutable ordinal checkpoints, actual encoder verification and measured PDF admission; it does not assert current freshness for other palace entries. No new indexing, model initialization, conversation capture or recall occurred.

## Reuse and boundary decisions

1. DATA_MODEL_AND_STORAGE123–177 and229–235 require operation-owned fixed private paths, intent before IO, exclusive staging, hash/length proof, closed/synced bounded seals, idempotent move/recovery and metadata visibility only after commit. SourceFiles38–125 already implements the original-specific counterpart; DerivedFiles must not write `original.part`, `seal.json` or `sources/.../original.bin`. Preserve the existing original seal/version/path bytes and behavior during resolver extraction.
2. SourceFiles144–167 contains the beneath-root/non-symbolic/canonical-location checks and directory-ancestry sync to reuse, not a reason to create another path convention. Its cooperative stable private-root contract remains explicit: a path check is not an atomic defense against malicious same-UID replacement. A preexisting final object must verify against the intended target/hash/length before reuse. `ATOMIC_MOVE` is not independently a no-clobber race proof; the caller's exclusive operation/target ownership must remain a real precondition, and later concurrent/fenced integration must test it.
3. DirectorySync reports explicit unsupported capability separately from IO failure. Windows host tests using `false` cannot establish Android directory/power-loss durability. Payload/seal force failures and real directory errors must prevent advancement; retaining bytes is not a success receipt.
4. RoomJobLeases17–60 already requires an ALIVE requester, positive old-owner ENDED evidence for takeover, a new generation and one monotonic clock. JobLeaseTransactions15–24 already composes metadata with initial/final exact fences and transactional rollback. Reuse these rather than introduce timeout-derived ownership or publish domain metadata with standalone `complete` after a separate commit. File/hash/codec work remains outside Room; completion must recheck the exact operation and job generation.
5. The100MiB source acquisition ceiling is not automatically a derived-raster/PDF ceiling. For example48MP packed RGB is144,000,000bytes before encoding, even though the original compressed source can be smaller. The derived writer must enforce the caller's admitted explicit bound with checked Long accounting and bounded scratch, without silently changing the requested raster or pretending that a file-size cap reserves image/PDF memory. Exact encoder/artifact admission remains later implementation work, not a newly accepted universal output limit.
6. CAPTURE_IMPORT_EXPORT113–139 requires actual PNG decoded-rendered RGB equality, actual JPEG SOF sampling policy/tolerances, and independent admitted PDF readback before complete PRIVATE_READY. A verified generic byte checkpoint cannot satisfy any of those codec or complete-artifact-set gates. Ordinary FAIL and reviewed accepted-degraded lossless checkpoints also remain distinct; no missing fallback file may be regenerated under old acceptance.

## Prepared cases and unperformed coverage

Exactly eight `@Test` methods are present. Intended coverage is fixed render/export paths with twice-idempotent recovery; empty/over-limit/throwing writer refusal; escaped-stream lifetime; existing-stage/conflicting-final preservation; changed seal/payload refusal; invalid target/transaction rejection; sync failure; and cooperative cancellation before durable completion. These are prepared intentions only: the existing infrastructure failure did not reach JUnit. No missing-class assertion was observed and production implementation must not begin on that failure.

The eight cases are not exhaustive filesystem acceptance. Additional boundary coverage remains necessary for malformed/oversized/future-version seals and metadata, missing/both-payload recovery combinations, destination ancestry/link redirection, failure after payload/seal/move durability points, actual cross-store exclusive-target/lease-loss races, distinct typed errors and late cancellation/close delivery. Some may be coherent extensions of the focused file batch; Room/Android/codec/process-death/resource evidence belongs to consuming integration and independent persistence review. No synthetic Windows test is substituted for those gates.

Next action remains the blocker receipt's positive native worker-exit reconciliation, bounded transport diagnosis and one focused actual-RED command. No build, test, APK assembly/install, device operation, deletion, final artifact or new acceptance occurred during this preparation.

## Actual retained artifact reception, not current-build delivery

Read-only hashing confirms `app/build/outputs/apk/debug/app-debug.apk` is still the18,595,488-byte historical Q main `d8d80c5f3e5d3763b49cdf5cd4fffcce2d76a2545545d742877ded84f7f1d3b0`; the Android-test APK is still the1,520,560-byte compiled/unrun S test `4edc1edf53c2d4d997a2be9c669445145159e83980ec409526581abf1c3b46c5`. Both match their historical proof, not current executable sources. The old341-input manifest now has changed app/build.gradle.kts, RoomExportSnapshots.kt and P06ImportJourneyTest.kt; newly added source/test files are not made current merely because that old allowlist omits them. `dist/` contains only the two historical T01/I01 bootstrap APKs. No fresh goal-complete or current-export APK is delivered by retaining or renaming these files; new assembly/signature/metadata/current native evidence remains necessary after the host gate clears.
