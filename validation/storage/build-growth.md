# Measure build-output growth

For a heavy or repeated test run, take a snapshot of the relevant output roots before launching it. Compare against the same roots in the same worktree after success or failure; use a report path outside the scanned roots. For example, from this worktree in Bash:

```bash
report="$(mktemp "${TMPDIR:-${TEMP:-/tmp}}/pristinepage-build-before-XXXXXX")"
python -B validation/storage/report_build_growth.py data/build app/build > "$report"
# Run the build here.
python -B validation/storage/report_build_growth.py --baseline "$report" data/build app/build
```

The report shows current free bytes on the worktree volume and logical file sizes/deltas grouped by the first child of each selected root. It skips links and junctions; hard-linked files are counted only once across the selected roots, attributed to the first path visited. The report is read-only; logical growth is not an estimate of space reclaimable on a compressed volume. Check the separate Gradle and SDK volumes if they differ, and reserve enough capacity for known scratch and build outputs above the project's free-space floor before running a large test. Prefer small targeted reports at the end of high-growth runs or when handing off a checkout; there is no scheduled job or new acceptance gate.

## Test scratch ownership

- Give each test or invocation a unique scratch root. Prefer JUnit's `TemporaryFolder` with `assureDeletion()` for JVM tests, or the existing scoped teardown primitive for the runner in use. Pass the root into shared fixture constructors. Preserve independent subroots for fixtures that intentionally exercise isolated stores or reopen operations.
- Assert transient files while the fixture is live. Close streams, Room databases, locks and workers before checked teardown on success, assertion failure or ordinary cancellation. Do not change production retention to make a test clean up. Keep authored fixture inputs, exact APKs, essential reports and intentionally retained crash evidence separate from disposable scratch.
- Use focused tests first. Group compatible cases into one incremental batch where it avoids repeated setup, without substituting host checks for a required native witness. Before repeating a high-growth run, confirm why the rerun is needed, measure the expected footprint against the operational floor, and verify the previous run left no unexplained growth.
- A killed process may bypass teardown. Prove that the exact test-owned root is abandoned and not retained evidence, check for concurrent writers and links, and obtain the applicable exact cleanup authority before deleting existing output. Start with scratch from your own runs, not SDK or offline dependency inputs, other worktrees, or VM disks. Never delete a whole module output directory solely because it is ignored. Verify both the named paths and volume free space after cleanup.

## Worktree incident: 100 MiB ingest partials

On 2026-11-25–26, the library-portability worktree retained 111 synthetic `data/build/test-source-files/source-store-*/staging/<transaction>/original.part` files at exactly 104,857,600 bytes each: 10.84 GiB. The encoded-limit case in [`SourceStoreTest`](../../data/src/test/kotlin/app/pristinepage/data/SourceStoreTest.kt) supplies 100 MiB plus one byte and verifies that streaming refuses the extra byte before decoding or publishing an original. [`SourceFiles`](../../data/src/main/kotlin/app/pristinepage/data/files/SourceFiles.kt) deliberately retains failed and partial copies for production recovery. The leak was the lifetime of the old test root, not the streaming limit or production retention.

The scoped ingest-teardown integration recorded 1,050 passing data JVM cases, including the real limit witness and a small file-layer streaming control. Both use a JUnit-owned root with assured deletion. The run produced no new retained large partial, and all 111 existing partials were left unchanged. The operator later had those files cleaned separately; the follow-up scan found no 100 MiB partials and an empty `data/build/test-owned-scratch` parent. A forward teardown fix does not reclaim old outputs.

The shared ingest fixture still had an unmanaged default root: the follow-up counted 4,692 `source-store-*` directories created after that fix, but the `original.part` files remaining in that namespace were only 0, 1 or 4 bytes. Separately, `render-producer-test-*` occupied about 365 MiB, not 10.84 GiB. If the directory count keeps growing, migrate remaining callers to explicit test-owned roots, keep the production-limit assertions, and compare before/after counts and bytes after a representative test batch. Do not infer that all ignored scratch is owned or disposable.

## Host-space attribution

During the same investigation, the shared agent temporary directory contained about 25.2 GiB of mixed test environments, duplicate build caches and retained artifacts across projects. A separately authorized, scoped cleanup reported 16.42 GiB of removed logical file bytes; measured host free space was 26.13 GiB afterward. A temporary location alone did not establish disposability: active portability caches, required offline inputs, evidence, and the live session database and journal were preserved. Confirm use through the actual build configuration or active consumer records, not a directory timestamp alone; measure volume free space independently of logical bytes removed.

The two LDPlayer guests then occupied about 5.24 GiB and 4.36 GiB, and both were stopped and retained in the shared pool. A stopped guest still occupies disk; a similar name does not prove a duplicate or authorize deletion. Reconcile the live inventory, allocator generation and retention requirements before any separately approved LDPlayer removal. An unrelated software installation was expected; a Windows restore point was observed but its storage usage was not verified. Neither is evidence of project growth. Do not infer a cause from a large current folder or a recent timestamp. Compare the same paths and volume across snapshots, and distinguish observed growth from unaccounted space.

Hibernation and paging are host settings, not cleanup targets. In this incident, an attempted hibernation-file resize to 70% increased its footprint from 25.48 to 44.58 GiB on a host with 63.69 GiB of visible RAM, bringing free space below the build floor. The previous 40% setting was restored. Measure RAM and the existing file and calculate the resulting headroom before proposing any separately authorized setting change; do not adjust host settings as part of test-scratch cleanup.
