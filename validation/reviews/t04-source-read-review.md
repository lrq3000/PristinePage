# T04-SOURCE-R — independent committed-source boundary review

## Scope and evidence

Senior Android imaging/persistence/pipeline review of the actual nine-path uncommitted code/script increment, including untracked files, on `feat/paperlight-v1`, HEAD/base `8ba59c0b2dd2559a4e01c4aa14c69cbf12441f74`. Read the complete mandatory authorities, accepted plan, input/storage/resource/architecture/privacy/fixtures/validation specifications, supporting contracts and reviewer template. This report is the only write. No application execution, build, Gradle/SDK/ADB/console/UI/device/network/acquisition/recall/indexing/delegation/Git mutation/cleanup occurred. Python analysis used only bounded inputs and in-memory SQLite.

Independently matched all23 source/build/script hashes and47 suite hashes:435 recorded JVM cases, zero failures/errors/skips; data351/app9 executed in the final integration, image37/core23/fixtures15 reused. Retained RED XML confirms eight missing-reader and nine missing-consumer assertions; the preceding Kotlin backup compilation failure is not JUnit RED. The normal integration log records49seconds/174tasks. Catalog/verification/all five module locks/schema/accepted-plan/build-wiring bytes match HEAD.

All13 runtime-manifest entries match sizes/hashes; final anchor is `5f10e277a5fb9cf0b5ea76a6c256490bbb74f58e70a7646198a1eea803716ee1`. Both APK copies match `9a00b17d30b01e5a5372f6be3621c39c25d14c886dafe2a38117277c8187046d`,3435521bytes; ZIP CRCs pass/no native libraries. Saved SDK output identifies S-01/self-target/runner/no INTERNET/private Room service. All21 source-derived native names have exactly one start/pass; retained instrumentation stdout matches the command transcript, recording3.058seconds. The transcript-prefix anchor matches; saved shutdown records show both original process identities signaled/exit0 and same-generation released ownership. These are retained-evidence checks, not reviewer runtime reruns or fresh live allocator/process observations.

## Strengths

Data retains file/metadata ownership without a dependency cycle; the reader neither consults current recipes/pages nor writes pins. Same-transaction raw guards correctly precede generated Long-to-Int projections; Kotlin revalidates READY/path/inspection identity and proves closed original bytes outside Room. SourceDecoder preserves full oriented source identity independently of downstream OutputSpec, compares the complete inspection/byte identity, refuses historical conversion drift and closes undelivered RGB plus reservation. Reflection tests exercise real business checks, values and ownership, not merely class presence; native tests add actual Room/codec composition and provider-free reopen.

## Findings — Critical0 / Important1 / Minor1

### SOURCE-I1 — TEXT length does not bound materialization

**True, reproduced with actual DAO SQL and exported Room DDL in host SQLite3.51.1.** `data/src/main/kotlin/app/pristinepage/data/db/CatalogDao.kt:15–17,22,26` uses `length(TEXT)` as its pre-materialization bound. SQLite stops that character count at the first NUL. Bind `conversionFingerprint = "srgb-v1" + NUL + "x"*524288`: storage class remains TEXT, SQL length is7, byte length524296, and `sourceReadIsBounded` returns1. The same bounded experiment passes for pixelSha256, profileSha256, byteSha256 and ingestTransactionId using their valid-length prefixes.

`data/src/main/kotlin/app/pristinepage/data/repository/RoomCommittedSources.kt:24–25` consequently materializes these oversized rows before Kotlin rejection; generated CatalogDao_Impl uses getText for these fields. A damaged catalog can therefore force arbitrarily large metadata extraction or an allocation/cursor failure instead of bounded integrity refusal. No provider-import route to such catalog values or actual OOM is claimed. This is Important because the accepted corrupt-metadata read path defeats its resource boundary, not because of specification silence.

Minimal remedy: add raw byte-length ceilings using `length(CAST(field AS BLOB))` for every length-only TEXT field, preserving intended Unicode support for the fingerprint; retain type, semantic and dimension checks. Reject embedded NUL explicitly where invalid. Add actual-query TEXT regressions with valid prefixes/NUL/large suffixes and a bounded native guard-before-projection case. Current oversized tests use BLOBs in TEXT columns, which fail typeof and miss this bypass. Do not attempt a giant allocation to demonstrate impact.

### SOURCE-M1 — Independent raster-extent guard lacks a regression oracle

**True static coverage gap, not an observed production defect.** `image-android/src/test/kotlin/app/pristinepage/image/CommittedSourceDecodeTest.kt:62–65,111–123` always constructs a3×2 image. Its size mutation changes SourceInspection, so inspection inequality alone rejects it; removing SourceDecoder's separate image-extent checks at `image-android/src/main/kotlin/app/pristinepage/image/decode/SourceDecoder.kt:35` survives these assertions. Counterexample: matching stored/decoded inspections and hashes, but an injected2×3 raster. Current production correctly refuses this; an unprotected regression could feed incompatible source coordinates to later geometry. Minimal remedy: parameterize only the delivered raster extent and assert INTEGRITY_FAILED, closed image and zero budget. Mutation reasoning only; no source mutation executed.

## Considered but declined — individual coordinator rulings

- Repeated byte hashing: intentional independent proofs; O(encoded bytes), not an incorrect fallback or premature caching requirement.
- Mismatched injected metadata/bounds databases: production fixture uses the same Room instance; no demonstrated wiring defect.
- Counter equality/owner legitimacy: nonnegative counters do not prove these; separate audits and caller-held ownership remain required.
- Automatic read pin/GC lease: explicitly absent; GC is unimplemented. Future consumers must retain a durable owner throughout consumption.
- Hostile same-UID replacement/symlink TOCTOU: outside the stated cooperative immutable-root boundary; no hostile-filesystem guarantee accepted.
- Cancellation during blocking SQL/read/native calls: cooperative checkpoints cannot guarantee immediate interruption.
- Cancellation racing the final nonsuspending return: no atomic-delivery guarantee; ownership transfers on delivery, unlike an undelivered withContext result.
- Mid-file-proof and post-load cancellation-state branch coverage: guards exist; these specific races were not independently exercised here.
- Destination aspect/upscale/purpose sizing: geometry may legitimately differ from source; actual destination admission/resizing belongs to the future renderer.
- Full-source overbudget refusal for tiny output: deliberate correct behavior, not missing preview downsampling.
- Early unsupported-conversion rejection: current design deliberately compares after fresh decode; it preserves old metadata without migration.
- Codec-origin error categorization: historical D-M1 remains separate; not independently rereviewed or closed.
- Comprehensive malformed-container/entropy/color/HDR refusal: small native fixtures do not certify every adversary.
- API26/27 strict fallback: explicitly unavailable and mandatory unfinished compatibility.
- ICC/non-sRGB conversion: unfinished, not accepted through sRGB fixtures.
- Native WebP compatibility: unperformed format gate.
- Picker/fallback/real grants/provider stalls: injected streams are not native grant or picker evidence.
- Session routing/order/per-item UI: not implemented by this consuming boundary.
- Reopen versus process death/crash-WAL/power loss: closing Room proves none of those additional failures.
- Global startup budget/scheduler/application wiring: injected composition is not integrated production ownership.
- Broader recovery/masks/providers/degraded snapshots/pin release/GC/confirmed deletion: unchanged unfinished storage work.
- Original decoder NEEDS FIXES/coordinator corrections/D-M1 and Room PASS WITH MINOR/ROOM-M1: separate retained dispositions.
- Older L-M2/F-M1/F-M2/ES-M1/ES-M2/auditM1: unchanged, not silently closed.
- Pre-runtime false flags/historical receipt checkpoints: superseded by named runtime evidence, not new contradictions.
- Emulator shutdown causation/live machine inventory: saved scoped records cannot establish unrelated current processes or historical OS causes.
- Synthetic files/APKs/raw evidence retention: intentional; no deletion authorized.
- Physical performance/fidelity/resource peaks: emulator timing supplies no physical acceptance.
- U-01 learned methods: mandatory unfinished implementations/acquisition gates.
- U-04 album editing: mandatory unfinished application journey.
- Full classical slice/camera/export/sharing/offline/backup/accessibility/rights: no completed-product acceptance inferred.

## Verdict

**NEEDS FIXES for this bounded committed-source read/decode increment.** Resolve SOURCE-I1 before accepting its metadata-allocation boundary; retain SOURCE-M1 and every declined behavior for coordinator disposition. No whole-T03/T04, scanner, merge or release acceptance follows.
