# T03-EXPORT-SETTINGS-R — independent persistence/export-contract review

## Scope and evidence

Senior persistence/export-contract review, 2026-11-15, T03-20261115-A/P-01/U-05, `feat/paperlight-v1`, HEAD/base `a4fa7998fc9c07843716ee6cdf16b795fd1d1f57`. Reviewed the actual four untracked source/test files and receipt, not an empty tracked diff. Read root/spec AGENTS, complete mandatory authorities/storage specification, accepted plan including T-03, and export/privacy/resource/architecture/batch contracts; inspected supporting IDs, OutputSpec, render identity and export rows. Applied requesting-code-review/code-reviewer template and reasonable-user consequences without delegation.

Read-only Git/hash/XML inspection independently matched four source hashes and all38 inventoried suite hashes:353 recorded JVM cases, zero failures/errors/skips; data306/app9 executed, core23/fixtures15 reused. Retained XML/logs corroborate12 absent-contract REDs→12GREEN, then8 absent-codec REDs alongside12GREEN→20GREEN; integration log reports99s/138tasks, no compiler warnings. Current lint XML has0errors/3existing warnings. Independently hashing the literal113-byte golden reproduces `7900b978b6bbe00f667497a3dc59778c9258380e54cc7d1d50a65420dcadb3f2`. Schema, verification metadata, six lockfiles and accepted plan match HEAD; plan SHA remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`. No application/test execution occurred; log inspection does not independently establish invocation flags or historical write chronology. Only this report written.

## Strengths and examined behavior

- `ExportSettings.kt:19–48,56–68`: format-specific options, EXPORT purposes, FAIL requested/actual equality, exact KEEP acceptance membership/dimensions, distinct page/render bindings, canonical IDs, positive revisions/versions and exact lowercase hashes are enforced. Finite-positive DPI, nonnegative consistent margins and JPEG quality/sampling-policy checks are coherent structural validation, not fabricated capability evidence.
- `ExportSettings.kt:53–54`: separate unmodifiable copies prevent subsequent caller-list mutation; nested records contain immutable scalar/value objects. No source, recipe, raster, persistence or encoder side effects occur.
- `ExportSettingsCodec.kt:20–58,61–97`: fixed domain/version tag, explicit enum names/defaults, big-endian lengths/numbers, strict UTF-8, exact presence bytes, bounded counts/text/input, trailing rejection and typed reconstruction are unambiguous. Decoder guards precede attacker-sized allocations. Encoder fields are already bounded by constructors; its final total-size check does not expose an unbounded field allocation.
- Semantic page order remains meaningful; acceptance order is canonicalized by that order. All current fields enter encoding, including requested/actual sizes and accepted raster identity. Double NaN/infinities reject; negative-zero DPI rejects and zero margins normalize deliberately. Hash excludes transient scheduling state. Decoder retains no caller bytes. No production serializer/hash omission or false freeze/export-success path established.

## Issues

### New counts: Critical0 / Important0 / Minor2

**M-1 — Reject oversized in-memory lists before copying.** `data/src/main/kotlin/app/pristinepage/data/model/ExportSettings.kt:53–57,63–67` copies both entire input lists before checking their permitted cardinalities. Counterexample: an oversized materialized page/acceptance list incurs another full allocation before inevitable rejection; a lazy large List can force that allocation without first storing its elements. Consequence: avoidable metadata-memory exposure on direct API misuse. This is Minor here because hostile persisted bytes already pass decoder count bounds and no executing consumer is added. Minimal remedy: check caller page size1..100 and acceptance size0..100 before copying, then retain validation of owned copies. No reproduction was executed.

**M-2 — Independent safety/identity regression vectors are incomplete.** `data/src/test/kotlin/app/pristinepage/data/ExportSettingsTest.kt:40–43,99–103` uses101 duplicate pages and never supplies two valid fallback pages sharing one RenderId: removing only the upper page bound or distinct-render guard is therefore not directly detected by those cases. `data/src/test/kotlin/app/pristinepage/data/ExportSettingsCodecTest.kt:37–43,57–82` uses writer/profile/encoder versions1 and does not exercise signed-zero normalization, unknown enum names or oversized acceptance counts. Counterexamples to protection: replacing encoded admissionProfileVersion with constant1, or removing margin-zero normalization, lacks a distinguishing vector. Consequence: future persistent-identity/validation regressions can escape these focused assertions despite correct current code. Minimal remedy:101 distinct pages, duplicate-render/two-page acceptance, changed writer/profile/encoder identity vectors, signed ±0 margin equality, and targeted unknown-enum/acceptance-count rejection. These are static coverage observations, not executed mutation-test results.

### Historical deferred Minor, not a new finding

Prior reference-audit M-1 malformed oversize/negative-actual projection coverage remains unchanged and deferred. This increment neither exercises nor closes it; preserve the original review/disposition separately.

## Recommendations

Disposition the two Minor items before persisting long-lived v1 settings. Preserve this byte format or introduce an explicit new version for incompatible changes. Keep settings hashing distinct from the forthcoming full snapshot identity; constructor validity cannot replace freeze-time evidence or resource admission.

## Declined to judge or execute — individually retained

- Fresh JVM/build/lint execution: expressly prohibited; only retained results inspected.
- Current APK acceptance: no APK assembled in this increment.
- Current native Room success: no device execution; prior26 Room cases are historical.
- Invocation flags/RED-before-product chronology: receipts assert them; captured output alone is not independent proof.
- Concurrent caller mutation during construction: no linearizable read promised; post-construction ownership is established, not racing-writer safety.
- Reflective final-field mutation/Unsafe: arbitrary in-process memory tampering is not an input security boundary.
- Whole active-document order: requires repository transaction, not standalone settings.
- Document/page revision and source membership: requires repository state, not standalone settings.
- Atomic freeze/rollback: not implemented here.
- Full snapshot serialization/hash: settings identity alone omits required source/recipe/provider content intentionally.
- Source-file/hash integrity: requires actual retained-file checks.
- Source pin transfer: no ownership writes implemented.
- Recipe/mask retention: no ownership writes implemented.
- Provider-asset pins: no registry/ownership operation implemented.
- Render-review lease transfer: requires actual lease verification.
- READY/DEGRADED/request/source binding: requires retained record/file verification.
- Accepted raster reuse/loss/corruption: no renderer/export consumer exists; never permit regeneration under old acceptance.
- Provider availability: separate capability preflight, not inferred from typed fields.
- Geometry validity: requires renderer preflight, not settings alone.
- Asset identity: exact registry checks remain mandatory.
- Asset rights: separate legal approval remains mandatory.
- Codec availability: no bridge execution/capability check implemented.
- JPEG SOF sampling/readback: declaration is not measured encoder evidence.
- JPEG quality/fidelity: no encoded output evaluated.
- PNG pixel equality: no image encoding performed.
- Export metadata stripping: no artifact inspected.
- PDF point rounding/MediaBox/contain/margin fit: finite structural values can still require explicit physical-placement refusal.
- PDF measured profile: positive profile version does not establish support.
- PDF minimum two-page envelope: no assembly/profile measurement performed.
- Dimension/whole-document allocation admission: positive PixelSize is not allocation permission; no silent resize permitted.
- Native buffer/session lifetime: no native execution added.
- Render execution/provenance/fallback status: acceptance fields do not prove execution occurred.
- Durable job creation: no job writes implemented.
- Job lease fencing/execution: existing identity is not new execution evidence.
- Scheduler enqueue/recovery: no new scheduler route implemented.
- Idempotent assembly: no assembly consumer exists.
- Export private completion: no artifact produced or verified.
- External publication: no destination operation implemented.
- Sharing: no grant operation implemented.
- Future-version opaque-history preservation: consumer must preserve unsupported bytes; decoder rejection alone does not implement storage retention.
- Broader recovery: unchanged independent obligation.
- Pin release: no ownership-release operation added.
- Garbage collection: no reclaim authority introduced.
- Confirmed deletion: no new deletion command implemented.
- Album/editor UI: no interaction implemented or evaluated.
- FR-17 atomic batch journey: settings do not implement batch persistence or interaction.
- Mandatory neural methods: U-01/provider acquisition/parity gates remain open.
- Physical performance: excluded devices and no measurements.
- Fidelity/real-page evaluation: no rendered outputs assessed.
- Offline full scanner journey: not executed.
- Accessibility: no UI evaluated.
- Release rights: not established by pure contracts.
- Signing/final artifact acceptance: no current artifact evaluated.
- Whole T-03 acceptance: remaining storage/freeze obligations stay open.
- Merge readiness: not authorized or established by this bounded verdict.
- Release/scanner acceptance: broader requirements remain open, not waived.

## Assessment

**Bounded static review: PASS WITH MINOR LIMITATIONS.** New0Critical/0Important/2Minor; one separate historical deferred Minor retained. Current typed settings and codec are coherent preparation for freeze, not acceptance of atomic persistence, actual exports or any broader product gate. Coordinator disposition and subsequent boundary evidence remain required.
