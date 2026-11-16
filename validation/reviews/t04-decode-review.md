# T04-DECODE-R — independent Android input/pipeline review

## Scope and evidence

Senior Android imaging/input/persistence review of the actual uncommitted ten-path increment, including untracked files, against HEAD/base `da918f7ac65766cd12f518be330d0b2e69ea7fbb`, `feat/paperlight-v1`, T04-20261116-A/P-01/U-05. Read complete required authorities/domain documents, accepted plan, reviewer template and SourceInspector/SourceInspection/RgbImage contracts. No replacement plan, delegation, application execution, acquisition or device operation; this report is the only write.

Independently matched all ten source/build hashes, all44 suite hashes/409 recorded JVM cases with zero failures/errors/skips, and all39 retained runtime-evidence hashes/sizes. Retained XML confirms12 missing-pure-class failures and seven missing-container failures. Native transcripts individually corroborate five missing-bridge failures, then2PASS/3FAIL, then five passes on API28. Current built/retained APK both match `e8829b384c470711709bfb781e0822b0d995f01908c353e21563293fc7456cb5`,3237004bytes; ZIP integrity/no native libraries checked. Saved SDK output identifies S-01/self-target/runner/no INTERNET; no fresh signature execution. Final saved generation `ed0494c0-582e-4189-8403-005eb1ab8084` records both original processes exited0 and ownership released. Current log records63seconds/174tasks; schema/catalog/verification/locks/accepted-plan tracked content is unchanged. Independently reconstructed77-byte pixel-hash vector matches. These are retained-evidence checks, not reviewer test reruns.

## Strengths

Shared synchronized subtraction-first accounting and idempotent reservations avoid overflow/double release. Exact EXIF permutations, straight-alpha white composition, immutable owned RGB and padding-independent full-source hashing have independent oracles. Android decoding neither resamples nor reapplies EXIF; bitmap/permit finally scopes and undelivered-result cancellation cleanup are explicit. Original SHA verification and the data-owned inspection port preserve module/ownership boundaries. Receipts retain failures and accurately restrict native evidence.

## Findings — Critical1 / Important2 / Minor1

### D-C1 — Untrusted EXIF counts allocate before admission

**True, static allocation-path finding; no dangerous allocation executed.** `image-android/src/main/kotlin/app/pristinepage/image/decode/AndroidSourceInspector.kt:49–57` constructs ExifInterface before reserving any permit. `image-android/src/main/kotlin/app/pristinepage/image/decode/SourceContainer.kt:123–129` caps APP payload bytes but never validates TIFF component counts/offsets. Counterexample: an otherwise ordinary tiny JPEG with a32-byte EXIF payload whose IFD Orientation entry is SHORT, count1,000,000,000, data offset8. The pinned parser accepts the compatible type/product2,000,000,000, seeks to that valid small offset, then allocates that byte array BEFORE readFully discovers insufficient bytes. Retained `validation/raw/decode/exif-1.4.2-bytecode-diagnostic.txt:8128–8159,8256–8259,8438–8450` proves this order; Orientation's SHORT registration is at11339–11345. Encoded/metadata ceilings and a32MiB shared budget do not stop it. Consequence: importing a small malformed selection can terminate the process instead of controlled refusal, violating NFR-02/SEC-04. Minimal remedy: bounded TIFF validation before invoking the general parser, checking types, counts, offsets, recursion/work and aggregate materialization; alternatively use an already validated bounded metadata reader. Merely reserving the existing estimate or catching OOM does not fix this. Retain safe pre-parser rejection tests without attempting the giant allocation.

### D-I1 — Parser-discarded orientation is mistaken for genuine absence

**True classification defect; native counterexample not replayed.** `image-android/src/main/kotlin/app/pristinepage/image/decode/AndroidSourceInspector.kt:50–55` interprets any missing/synthesized attribute as absent on disk. Counterexample: a present0x0112 Orientation entry with invalid type0; the pinned library skips invalid types (`validation/raw/decode/exif-1.4.2-bytecode-diagnostic.txt:8077–8096`), then synthesizes Orientation0/offset−1. It also swallows metadata IO exceptions and adds defaults (2700–2875). Thus range−1 proves a synthesized value, not a valid TIFF lacking Orientation. A codec that ignores malformed ancillary EXIF can complete normally, allowing guessed orientation/provenance instead of mandatory refusal. Minimal remedy: distinguish validated absence from malformed/present-but-discarded tags in the bounded metadata preflight; reject malformed TIFF/type/count/duplicate conflicts. Preserve the working no-EXIF default1 and encoded0/9 refusals. The current tests cover neither distinction.

### D-I2 — Valid low-bit-depth PNG scans are unnecessarily refused

**True, direct predicate counterexample.** `image-android/src/main/kotlin/app/pristinepage/image/decode/SourceContainer.kt:69–74` requires input bit depth exactly8. Consequently a valid static1-bit grayscale scan, or2/4-bit grayscale/indexed PNG within all resource limits, always returns UNSUPPORTED_FORMAT before the established codec can normalize it. Low bit depth is not HDR, an unknown profile or an incompatible RGB8 output contract. This excludes ordinary monochrome scanned pages, beyond the disclosed high-depth/profile exclusions. Minimal remedy: admit legal low-depth/color-type combinations through the same strict codec and retain actual pixel fixtures; continue refusing unvalidated16-bit/HDR inputs. If intentionally deferred, explicitly bound the advertised PNG capability and retain this mandatory compatibility gap rather than claiming general static-PNG support.

### D-M1 — Codec-origin read errors lose their error category

**True mapping defect; triggering file-IO provenance untested.** `image-android/src/main/kotlin/app/pristinepage/image/decode/AndroidSourceInspector.kt:97` maps every DecodeException to nonretryable INVALID_INPUT. Counterexample: a DecodeException reporting SOURCE_EXCEPTION for a source read failure is classified identically to malformed pixels. Cached `android-34/android/graphics/ImageDecoder.java:737–789` distinguishes these reasons. Consequence: incorrect corruption/retry guidance; current FileSource reproduction is not established, hence Minor rather than a claimed demonstrated ingestion failure. Minimal remedy: map the documented reason/cause to sanitized storage/source errors while retaining malformed/incomplete rejection and independent cancellation propagation; add focused classification coverage.

## Considered but declined — individual coordinator rulings required

- Fresh builds/tests/lint/signature execution: prohibited; only retained evidence inspected.
- Exact command authorship/chronology: retained logs do not independently prove every historical action.
- API26/27 support: explicit unavailable result is safe here, but mandatory unfinished compatibility, not final policy.
- ICC/non-sRGB conversion: deliberately refused; no validated transform or broad color support claimed.
- EXIF/XMP color declarations and nullable/output-only ImageInfo color space: five native cases do not establish encoded-profile rejection; require adversarial native evidence before broad support claims.
- HDR/gain maps/CMYK/high depth: static refusals inspected; comprehensive native refusal/parity remains unperformed.
- Actual WebP decode/alpha/orientation: no native entropy fixture; mandatory unverified format gate.
- PNG duplicate/order and WebP feature/metadata consistency: preflight has incomplete semantic checks; final codec rejection is unproved, not certified safe.
- Complete entropy consumption/repair: CRC/EOI/RIFF scans are not entropy validation; partial-listener refusal is not proof against every codec repair or ignored payload.
- Physical PSS/native peak bound:14N estimate explicitly provisional; mandatory measurements remain open, independently of D-C1.
- Low-memory/thermal/stress and cancellation latency: no physical or sustained-cycle evidence.
- Native-call interruption: cooperative checks cannot promise immediate syscall/codec cancellation.
- Prompt-cancel delivery ownership: cleanup is present; injected delivery-race evidence remains absent.
- Separately retaining image past DecodedSource: explicitly forbidden unless accounting transfers; future consumers must enforce that contract.
- Direct pure-normalizer calls without admission: internal caller responsibility; Android bridge supplies a permit.
- Same-UID concurrent file replacement/symlink attacks: closed cooperative private-file contract, not a hostile same-UID guarantee.
- Platform/module codec changes and historical replay: fingerprinting is present; cross-build replay/support remains unvalidated.
- Real Room ingestion/reopen/byte-pixel consistency: mandatory integrated T03/T04 gate, not exercised here.
- Repository SourceDecoder/sourceId lookup/OutputSpec sizing: mandatory unfinished consuming paths.
- Picker/fallback/session/per-item UI and grant revocation: mandatory unfinished T04 behavior.
- Production startup/global budget/render-preview accounting: not established by injectable tests.
- T03 recovery/job execution/pins/GC/confirmed deletion/migrations: unchanged unfinished boundaries.
- Earlier T03 Minors: existing dispositions unchanged, including L-M2/F-M1/F-M2/ES-M1/ES-M2/auditM1; do not reopen corrected regraded L-M1.
- FR-17 album editing: mandatory later UI/save journey, not decoder acceptance.
- U-01 neural methods: mandatory later implementations/acquisition gates, not satisfied here.
- Camera/geometry/appearance/export/sharing: no capability or completed artifact inferred.
- Production offline/backup/accessibility/privacy/real-photo fidelity: test APK inspection is not those acceptance gates.
- Physical devices/phone/rights/publication/merge/release: unperformed or prohibited, never waived.

## Recommendations and bounded verdict

**NEEDS FIXES for this decoder prerequisite.** Resolve D-C1/D-I1 and disposition D-I2 before accepting the current boundary; retain D-M1 and every declined gate explicitly. Add bounded metadata adversaries and structurally complete corrupt/color fixtures rather than relying on truncated-container tests. This verdict neither accepts whole T03/T04 nor authorizes merge, release or a scanner PASS.
