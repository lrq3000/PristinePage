# Measure build-output growth

For a heavy or repeated test run, take a snapshot of the relevant output roots before launching it. Compare against the same roots in the same worktree after success or failure; use a report path outside the scanned roots. For example, from this worktree in Bash:

```bash
report="$(mktemp "${TMPDIR:-${TEMP:-/tmp}}/pristinepage-build-before-XXXXXX")"
python -B validation/storage/report_build_growth.py data/build app/build > "$report"
# Run the build here.
python -B validation/storage/report_build_growth.py --baseline "$report" data/build app/build
```

The report shows current free bytes on the worktree volume and logical file sizes/deltas grouped by the first child of each selected root. It skips links and junctions; hard-linked files are counted only once across the selected roots, attributed to the first path visited. The report is read-only; logical growth is not an estimate of space reclaimable on a compressed volume. Check the separate Gradle and SDK volumes if they differ, and reserve enough capacity for known scratch and build outputs above the project's free-space floor before running a large test. Prefer small targeted reports at the end of high-growth runs or when handing off a checkout; there is no scheduled job or new acceptance gate.

`SourceStoreTest` has a small file-layer streaming control and a separate real 100 MiB production-limit witness. Both use JUnit-owned scratch with assured teardown. Assert the partial file while that fixture is live; do not alter production recovery or silently discard actual retained evidence. A killed test process can bypass teardown; inspect only the exact abandoned test-owned root before proposing cleanup. Never delete a full module output directory solely because it is ignored.
