# T04-PICKER-R — independent Android input / validation-harness review

## Scope and method

Reviewed actual uncommitted/untracked adapter, eight native tests, Python runner, receipt and progress against `404314466d94a6c4d63fc4ea74d26ed6aa9276d6`, `feat/paperlight-v1`, T04-20261116-A/P-01/U-05. Read complete mandatory root/spec authorities, accepted IMPLEMENTATION_PLAN, CAPTURE_IMPORT_EXPORT, SECURITY_PRIVACY, ARCHITECTURE, PERFORMANCE_AND_OFFLINE, DATA_MODEL_AND_STORAGE, P-04, both emulator lifecycle references, Windows/debugging references and requesting-code-review/code-reviewer.md. PERFORMANCE_AND_OFFLINE is the resource contract; RESOURCE_SCHEDULING.md does not exist.

Only this report was written. Used read-only Git/file/hash/XML/ZIP/DEX-string inspection and memory-only execution of extracted runner methods with fake records/lock/commands. No actual runner initialization/subprocesses, build/Gradle/SDK/device/network/acquisition, indexing, recall, Git mutation, cleanup or nested delegation. Saved Android/JVM results are coordinator evidence, not reviewer reruns.

## Strengths

- `app/src/main/kotlin/app/pristinepage/capture/PhotoPickerContract.kt:22–29` reuses pinned AndroidX availability/fallback and image-only selection; OPEN_DOCUMENT alone receives OPENABLE/supported MIME hints. No new permission, dependency, UI or backport-installation service.
- `PhotoPickerContract.kt:31–46` separates cancellation from malformed success, treats ClipData as authoritative, preserves order/duplicates, rejects oversized selections before list copying and admits only bounded content references. Offered READ/PERSISTABLE bits are not grants or byte ownership. Complete Unicode/syntax/private binding remains data-owned before IO.
- Tests use actual Android Intent/Uri/ClipData. The existing controller characterization is accurately distinguished from seven missing-contract REDs and from real byte ingestion.
- Ordinary runner targeting/artifact checks are narrowly scoped. File-backed subprocess output avoids inherited-pipe hangs; successful shutdown verifies original player/VM starts and Windows wait/exit evidence, not merely closed windows.

## Evidence checked

Matched 37 source/build/runner hashes, 49 current XML hashes and 454 recorded cases with zero failures/errors: app18 executed; unchanged data361/image37/core23/fixtures15 reused. Integration log records 56 seconds/195 tasks, compiler warnings0; lint errors0/three existing warnings. Accepted-plan hash remains `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa`.

Built/retained APKs match main `a20d5d14613b9d7b6f4e2b48c7bb2e323913470e86219c117c74128015c953a6`/17,308,204 bytes and test `e86d797559980463810834516b5e30e382c3eb1c9b7fea82a010ca7200fbc4d1`/1,071,550 bytes; ZIP checks pass. Saved SDK records identify S-01, correct packages/runner/target and no network/broad-storage/backport-installation capability. Main DEX contains the new contract; unchanged MainActivity remains disabled bootstrap.

Matched all 16 final evidence hashes plus runner and anchor `e2531a2ab4aa4d1c7f0bc3022cdde0edb45084a7d9b3d09ebb6cf342ce35bb30`. Independently parsed eight named GREEN starts/passes, seven intended RED assertion blocks plus one existing pass, transcript-prefix hashes and individual stdout/stderr files. GREEN updated only main; corrected RED reused installed bytes. Historical manifests match: failed-registry runner differs from current; corrected RED-target runner actually matches current. Compile/registry failures are not application REDs.

Saved shutdown and current file-only registry agree on generation `bddf52a0-79a0-48ca-be3f-a9ad58e7748b`: original player15748/VM1504 wait0/exit0, all listed rows stopped, owner-null/released, completed cleanup attempt, no reservation/lock. No fresh live process/device check occurred.

## Findings

Critical: **0**. Important: **2**. Minor: **1**.

### PICKER-I1 — failed cleanup attempt remains falsely running

**Verified:** `validation/decode/run_picker_adapter.py:264–265,292–300`. Memory-only counterexample: let targeted quit return, then inject an OSError from the first shutdown-verification inventory. The actual method releases the lock and records outer `shutdown_failed`/retained owner, but leaves `cleanup_attempt.state="running"` without a finish time. A protocol-compliant subsequent cleaner must preserve that apparently live claim until separate termination reconciliation; an ordinary inspection failure can prolong resource retention and block the next validation session.

**Minimal remedy:** once this attempt's commands have actually ended, mark its matching cleanup-attempt token failed/finished before releasing coordination, preserving original identities, usage ownership, unresolved shutdown and reservations. Do not fabricate shutdown success or end an actually in-flight operation. Retain a fake-command regression for this failure boundary.

### PICKER-I2 — optimization removes operational safety gates

**Verified:** `validation/decode/run_picker_adapter.py:125–128,145–172,302–308`. Ownership, identity, resource and artifact authorization rely on Python `assert`, without an optimization guard. Compiling the actual class in memory with `optimize=1` makes `owned()` accept another owner/generation with keep-running/handoff set; normal compilation rejects it. `python -O` or inherited PYTHONOPTIMIZE likewise removes claim/admission/hash checks before real writes/launch/install/quit. This could overwrite a cooperative peer's claim or operate outside approved artifact/resource scope; it was not observed in retained runs.

**Minimal remedy:** unconditionally reject optimized execution before any operational work, or replace safety assertions with explicit exceptions. Test optimized compilation/invocation without real external effects; an assert-based optimization guard is insufficient.

### PICKER-M1 — direct result-boundary regression oracles

**Verified coverage gap:** `app/src/androidTest/kotlin/app/pristinepage/PhotoPickerContractTest.kt:60–83`; guards at `PhotoPickerContract.kt:37–42`. Tests cover 101-item rejection but not 100-item acceptance, blank authority or the 8192/8193-unit boundary. Counterexample: changing `count > 100` to `count >= 100` passes every current test while refusing a valid full-capacity selection. Current production checks are correct; this is a nonblocking future-regression risk. Add exact-boundary and mixed-valid/invalid ClipData cases, asserting whole-result rejection rather than partial selection.

## Individually considered and declined behaviors

- Full URI syntax in Selected: intentionally provisional; inspected data binding validates before persistence/IO.
- Remaining document capacity: data transaction already checks it; this adapter supplies only the absolute ceiling.
- Persistable bit without READ: merely offered bits, not an acquired permission; consumer must enforce READ semantics.
- ClipData versus unrelated data URI: deliberate authoritative selection, not lost fallback data.
- Provider MIME/content/virtual-document coercion: adapter opens/coerces nothing; actual bytes remain downstream-validated.
- Unknown non-OK result codes: cancellation policy creates no page or false success.
- Conditional intent-route assertion: neither chosen route nor API26/no-Play/fallback UI is established; mandatory future evidence.
- Actual picker launch: not wired or exercised by these tests.
- Grant acquisition/revocation/release: not implemented by offered flags or injected interfaces.
- Recreation/pending-session/wrong-document routing: not supplied by this stateless adapter.
- Per-item UI and application composition/startup budget: unchanged bootstrap does not implement them.
- Partial launch without captured PIDs: safely preserves unresolved ownership/reservation instead of inventing exit proof; manual reconciliation remains necessary.
- Short-lock contention: fails closed without stealing; requires coordinator resolution/retry, not evidence of cleanup success.
- General untrusted-APK installer/host-wide scheduler guarantees: outside this explicitly SDK-preverified cooperative harness.
- API26/27 decoding, ICC/non-sRGB, native WebP and wider adversaries: separate unfinished decoder gates.
- Broader crash/WAL/recovery, scheduling/fairness, pin release/GC and physical performance: not established here.
- Prior reviews/coordinator corrections and IMPORT-M1/SOURCE-M1/ROOM-M1/D-M1/older minors: unchanged, unclosed by this review.
- Neural/batch/editor/export/rights/release: mandatory separate work, not adapter acceptance.

## Verdict

**NEEDS FIXES — 0 Critical / 2 Important / 1 Minor.** No blocking adapter defect was identified, and retained bounded RED/GREEN evidence is coherent. Correct the two runner safety/recovery defects before reusing it; host-only regressions can establish those corrections without duplicating Android tests. This is not whole T03/T04, scanner, release or merge acceptance.
