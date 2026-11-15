# T03-OWNER-PROOF-R — independent bounded review

2026-11-15. Senior Android/native concurrency and persistence engineer. Findings: **0 Critical, 1 Important, 0 new Minor**. This report is not a merge recommendation or task acceptance.

## Inputs and method

Verified HEAD `51c375d752e95eb4d14abd00f692cf7384ca92ac`, branch `feat/paperlight-v1`, and the specified uncommitted production/host-test changes. Read both AGENTS, complete required PRD/TODO/PIPELINE/commands/OPERATIONAL_PLAN and DATA_MODEL_AND_STORAGE, accepted T-03/review focus, architecture/privacy/resource/capture constraints, ownership receipt and current progress. Traced FileOperationOwners, OperationOwnerProof, LocalOperationOwners, recovery admission, readmission, leases and the new tests; consulted the preceding recovery review rather than repeating the historical implementation review.

Read-only commands: `git --no-optional-locks status --short --branch --untracked-files=all`; `git --no-optional-locks diff HEAD -- data/src/main data/src/test`; `git --no-optional-locks log -1 --format=%H`. New untracked files were read directly. Report verification uses read-back and word count only; no reviewer test execution.

## Evidence and strengths

Retained author XML records five missing-class failures, then five passing owner tests; the consumer RED has one missing-refresh failure among sixteen cases. Final saved XML has owner 5/5 and recovery 16/16 passing. The integration log ends BUILD SUCCESSFUL/47s/138 actionable tasks, including executed data tests; the inventory reports 218 JVM tests, zero failures/errors, three existing lint warnings and unchanged schema/audit/plan. These are inspected author results, not reviewer reruns or Android acceptance.

The child fixture actually starts a bounded owned JVM, obtains its registered identity, requests HALT and verifies exit. Its result supports host process-lock behavior, not native Android service/process death. Provider/decoder/Android DB storage remain substituted in the consuming host test.

The implementation closes/forces an exclusive-created operation record before exposing its runner, retains the initialized process channel/lock strongly, avoids probing its own lifetime marker, validates exact bounded versioned records, and caches foreign ENDED only. A foreign locked process does not enter the requester-ALIVE cache. Recovery refresh occurs outside metadata transactions under shared copy admission. The prior I-1 requester-ALIVE guard and generation checks remain intact. No timeout, PID, absent-owner inference, provider read, original deletion or replacement was introduced.

## Findings

### Critical

None identified within the stated cooperative private-filesystem protocol.

### I-1 — Important — cached liveness can wait for registration filesystem IO while Room holds its writer

**Evidence status: true by static monitor ownership; not runtime-reproduced.** Locations: `data/src/main/kotlin/app/pristinepage/data/repository/FileOperationOwners.kt:33-35,71-84,106-122`; consuming writer lookups: `data/src/main/kotlin/app/pristinepage/data/repository/RoomJobLeases.kt:22-37` and `data/src/main/kotlin/app/pristinepage/data/repository/RoomIngestReadmission.kt:15-21`.

**Counterexample:** thread A starts an independent operation on the shared ownership root. `Pool.register`, synchronized on the Pool, holds that monitor across directory creation, channel creation/write, `force(true)` and close, including initial lifetime setup. Concurrent thread B, inside a Room lease transaction, checks a foreign cached-ended or unknown owner. Its local lookup returns UNKNOWN, so `state()` enters `Pool.ended`, synchronized on the same Pool. B cannot finish even this cache-only lookup until A's filesystem operation returns. A slow or stalled force therefore retains the metadata writer for its native-IO duration. This does not require malicious files or a second channel to the lifetime lock. The inspected ingest coordinator serializes its own copy consumers, but the public owner/lease components do not restrict all callers to that permit.

**Practical consequence:** unrelated metadata writes and recovery/lease progress can stall behind registration IO. The implementation does not actually provide the bounded registry/cache-only transaction participant promised by LeaseOwnerLiveness and readmission; moving `refresh()` outside Room does not remove this indirect dependency. No false ENDED, source loss or measured Android stall is claimed.

**Minimal remedy:** give terminal-cache reads/writes a separate short-held monitor, or an equivalent bounded concurrency-safe cache, which registration/native IO never holds. Preserve serialization/publication of the lifetime and the own-marker exclusion. Add a deterministic concurrency regression that pauses registration IO, performs an unknown/cached-foreign `state()` lookup through the metadata participant, and proves the lookup finishes before registration resumes. The existing local-ALIVE test bypasses this fallback and cannot detect the problem.

### Minor

No new Minor finding. Existing M-1 terminal-action guidance, M-2 future-version labeling and staging/seal retention remain open; this review does not close or recount them.

## Considered and declined individually

- Same-root duplicate lifetime channels: canonical-root pooling and synchronized registration prevent them for the inspected single application classloader; concurrent alias behavior is not independently executed here.
- Same-VM close hazard: the known local process token is excluded before opening its marker. Independent classloader copies, hard-link substitution and mutable root replacement are not established supported configurations; do not claim VM-global safety beyond the actual shared registry.
- Foreign concurrent probes: acquisition/overlap failures remain UNKNOWN; no new false-ended counterexample was established under immutable cooperative markers.
- Legacy/missing/future/corrupt/inaccessible evidence: conservatively UNKNOWN; lack of retroactive certification is intentional.
- Completed local evidence/cache eviction: forgetting becomes UNKNOWN, not ENDED; it can block recovery but does not authorize overlap.
- Native Android file-lock semantics and actual service/process death: explicitly unperformed, not inferred from official API passages or Windows JVM results.
- DB/WAL crashes, reboot and power loss: separate durability gates; missing marker directory durability cannot be described as proven power-loss recovery.
- Unsealed fresh attempts, owner-free retries and INTENT/provider resumption: explicitly deferred, not accepted from sealed replay.
- Committed/terminal/reference/snapshot/orphan recovery: outside this unfinished-ingest increment.
- Populated production wiring: FileOperationOwners operates only when injected; no application-wide deployment is inferred.
- Facade/freeze/export/remaining commands: separate incomplete work.
- Actual T-04 provider/decoder behavior: not established by synthetic inspection or the host consuming test.
- UI/FR-17, neural methods, physical performance, offline/fidelity and rights: independent open gates.
- Resource lifetime: one retained descriptor per used root and bounded record reads/caches are intentional. Callers must reuse a stable private-store root; arbitrary per-operation roots and aggregate on-disk marker retention are not validated resource policies. Do not add broad cleanup or weaken proof to address them.

## Separate assessments and verdict

**Operational scope: reviewer compliant.** Only this report was written. No source/test/schema/config/receipt/Git/index/HEAD/branch/worktree mutation, build/test/Gradle, device/ADB/console/emulator, network/acquisition/key/indexing/recall or nested-agent action was performed. No broader audit of coordinator operations or fresh process inventory is claimed.

**Contract: NEEDS FIXES** for the cache lookup's IO-dependent monitor. **Code quality: NEEDS FIXES** for that narrowly remediable synchronization coupling; the terminal-proof and requester distinction otherwise remain coherent under the stated assumptions. Resolve I-1 with retained concurrency evidence before relying on the new liveness implementation as a bounded Room participant. Then obtain separately scoped current-build Android/native interruption evidence. No merge, task, APK, process-death or whole-recovery acceptance is granted.
