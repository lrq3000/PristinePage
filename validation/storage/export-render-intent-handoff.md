# feat(storage): journal exact leased frozen render attempts

**Initial motivation:** In T-04/U, a verified frozen render job and an acquired lease still do not define an operation-owned output target. A writer needs a durable, attempt-specific intent and a separate entry into COPYING. Authority: primary `fb2466b2b711e02cbc165580489c8d3c1e4ee17d:docs/specs/TODO.md`, the accepted implementation plan, DATA_MODEL_AND_STORAGE and CAPTURE_IMPORT_EXPORT. Base `70ea1d56ce22507657909379a8f32f90a1bca70b`, branch `work/pristinepage-export-render-intent`, worktree `X:/local-workspace/.worktrees/pristinepage-export-render-intent`.

**Final purpose:** `RoomFrozenRenderReads.verified` returns a detached, non-copyable `Verified` binding only after the existing proof succeeds with the current ordinary profile. The existing `verify` remains a Unit-returning entry point. In data, call suspend `RoomRenderIntents.prepare(proof, lease)` to create or retrieve the unsealed intent, then separately call `copying(proof, lease, intent)` to advance the committed INTENT exactly once. The latter returns normally only after the metadata transition commits. The caller must use a same-database verifier, pair the job with its authenticated assembly ordinal, retain the frozen/job owners, hold the scoped runner from the same local registry and boot as lease acquisition, and invoke both methods outside any other transaction. These methods do not own file IO, actual raster or disk admission, copy restart, or later checkpoints.

**How and why:**

- Reuse the existing ordinary proof and the existing `JobLeaseFence`, not a timeout-only or separately maintained lease predicate. Inside each writer transaction, apply `renderJobReadIsBounded` to authenticate raw identity types and exact expected BLOB length, then apply the new scalar `renderJobLeaseReadIsBounded` to guard the remaining columns (including up to 512 KiB of checkpoint and raw integer audit/lease fields) before extracting the full row. Apply the existing fence to that actual guarded row; the adapter never fabricates unused fields or fetches a second unguarded row. Compare exact canonical input bytes/key/hash, null job snapshot/ordinal, exact job/owner/generation/deadline/state, live local owner and the single retained JOB pin ID. The legacy ordinary producer starts `attempt` and `leaseGeneration` at zero; the existing grant increments both together. This consumer refuses a mismatch instead of assigning an unproven attempt to a JobLease that has no independent attempt field.
- Lookup by `RENDER` kind, exact JobId and exact attempt using the existing unique index. The nullable scalar guard distinguishes absent from invalid and bounds every projected raw SQLite field before materialization, including byte extent for TEXT (not `length(TEXT)` alone) and exact nonzero payload BLOB length. Validate UUID and both literal paths, owner/job/attempt, known phase, canonical payload equality and absent sealed/error fields on INTENT/COPYING. New rows use two independently generated UUIDs, `staging/<tx>/derived.part`, `renders/<renderId>/page.bin`, and an ABORT insert. Reuse the same authenticated IDs on exact replay, including already-COPYING metadata, without allocating or granting another transition. No commands accept a caller-selected new target. Protect the transition with exact transaction/kind/job/attempt/phase CAS; require the supplied intent to equal the intent rebuilt from the persisted row and proof.
- The new private journal envelope is not a change to v1 render Job input/key/hash, prior journal kinds, or schema. Reconstruct its fixed-length payload rather than executing a general parser on the stored BLOB. Encode ASCII `pristinepage-ordinary-render-intent-v1` plus NUL, then transaction/render/job/runner UUIDs (36 bytes each), generation and attempt (big-endian signed Longs constrained positive), canonical job input SHA-256 (64 ASCII bytes), frozen page/source/source-blob and exact JOB-pin source UUIDs, frozen original-byte and normalized-pixel hashes, recipe revision Long and recipe hash, and exact width/height (big-endian Ints). The domain fixes ordinary FAIL, EXPORT and equal requested/actual size; the v1 job digest binds the remaining rendering semantics. Snapshot order/ID are not render ownership (a v1 job can be shared across snapshots); exact frozen page and source identities are still bound. An equivalent but distinct existing JOB pin remains legitimate when previously verified; changing the pin after verification is not replay.
- Verify the lease & immutable identity before mutation and re-read/verify exact journal state and the same lease binding before leaving the writer transaction. Use elapsedRealtime for expiry and the independent audit clock only for `updatedAt`. Throw inside the transaction on a post-write mismatch, lease expiry, cancellation or lost CAS so the first write cannot survive alone. `REVISION_CONFLICT/render_intent_not_current` covers wrong lease/phase, expired ownership, missing committed intent or lost CAS; `INTEGRITY_FAILED/render_intent_binding_invalid` covers unbounded or misbound stored values; durable cancellation is `CANCELED/render_intent_canceled`, coroutine cancellation propagates, and unexpected exceptions become retryable `STORAGE_FAILED/render_intent_storage_failed` without private exception data.

**Adoption and limits:** Coordinator owns independent persistence review before adoption. Review raw projections, exact phase CAS and rollback, same-boot ownership, and frozen identity reuse separately. A native follow-up should exercise real Room reopen and rollback, concurrent duplicate transitions, and durable crash recovery, including the cancellation window after metadata commit but before the caller obtains the result. A present INTENT/COPYING row is never evidence of file presence, an output permit or completed ownership transfer. Prior attempts remain untouched; export recovery, writer construction/verification/seal/move, `RenderedPage`/job completion, assembly, `PRIVATE_READY`, JPEG/PNG/PDF, sharing, APK/native, and T-11 acceptance remain separate work. The lease fence is a database guard, not a defense against coherent malicious same-UID database or path replacement; maintaining exclusive ownership through eventual IO is required.

## Verification protocol and evidence

Commands ran from the assigned worktree. Focused JVM: `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest --tests *RenderFileIntentTest`. Full affected JVM: `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest`. Python 3.13.12 / SQLite 3.51.1 exercised the actual SQL below on exported DDL in memory. No tests or builds ran in the primary worktree.

Each wrapper invocation was captured quietly with a 420-second deadline, at most four Gradle workers, and the existing `-Xmx4g`. The checked process-local environment was `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (21.0.8), `ANDROID_HOME=C:\Users\local-user\AppData\Local\Android\Sdk` (SDK 36/Build Tools 35.0.0), `GRADLE_USER_HOME=C:\Users\local-user\AppData\Local\Temp\opencode\paperlight-build-cache\gradle`, `ANDROID_USER_HOME=X:\local-workspace\.worktrees\paperlight-v1\.android-user-home`; `ANDROID_SDK_HOME` was unset. The exact 8.13 cached extraction and `.zip.ok` marker were present, launcher SHA-256 `56a30262647231bf3ab30b2bf090f5ecacff9329d3f1af1a1de73fd1ab4c64b4`, wrapper JAR `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`, configured distribution ZIP pin `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`; no acquisition. Pins, dependencies, schema `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d` and accepted plan `791dba54b888ac430cf12c0a4d83a363a24dd570f5305e989c91815fb9be78fa` are unchanged.

- `jvm-red`: wrapper exit 1 in 2m45s. All ten fresh JUnit cases failed with `Fenced render FileTransaction INTENT/COPYING seam is absent` before any production edit. RED reflection was replaced by typed calls; the final fixture freezes real producer inputs and uses actual assembly/reader proof and `RoomJobLeases` acquisition. It substitutes only the transaction and SQLite ports. Source-pin substitution was additionally covered after RED; the initial RED counts only the original ten.
- `jvm-green`: first GREEN attempt exited 1 in 1m10s at test Kotlin compilation, with zero JUnit reports. `FrozenRenderReadTest`'s expression-bodied Unit wrapper detected the return-type change of `verify`; preserved the original entry point and split off `verified` instead of editing the out-of-scope test or weakening the checks.
- `jvm-green-compatible`: exit 0 in 47s (7 tasks executed, 15 up to date); 11 fresh focused tests passed. `jvm-full`: exit 0 in 23s (1 executed, 21 up to date); 476 fresh data tests passed across 51 XMLs; zero failures, errors or skips on either run. The counts overlap.
- SQL RED: three intended missing-query failures (initial discovery also included 13 unchanged existing cases because imported test classes were in module scope). Final focused SQL: three passes, zero failures, exit 0; the appendix uses module imports so discovery is restricted to these three witnesses. It exercises exact keys, unique attempt scope, CAS phase/target, guard class/extent, valid nullable checkpoint handling and bounded refusal. It does not run Android Room. Generated DAO getters were inspected separately for current field coverage.

Ignored retained evidence is under `build/render-intent-evidence/`. The harness records the exact argv/environment/cache checks, per-run logs, wrapper exits, 222 RED or 224 current source pins, fresh XMLs, and explicit native daemon-exit observations. Free C: bytes in run order were 51,664,216,064 / 51,649,966,080 / 51,650,605,056 / 51,643,142,144. Invocation-matched daemon PIDs 6928 / 21580 / 2024 / 32756 each returned native wait 0 / exit 0, with no Gradle/Kotlin build JVM remaining afterward. No timeout, transport failure, force stop, cache cleanup, device, signing, push, merge or primary write. The sole build slot is released upon handoff to the coordinator.

| Evidence (relative to retained evidence root unless noted) | SHA-256 |
|---|---|
| `jvm-red/result.json` | `aa569024051464a0e9f8a1f9bca5ae752e2ec477e804b0d08c581e372c7f38f7` |
| `jvm-green/result.json` (compile stop) | `ac02438d5e3d4e81d5e88fdbb0274345fb9127086d0cc3fadc3dde4df4953c8f` |
| `jvm-green-compatible/result.json` | `694874dbc30581bed5e059979a6b439a98202bf9f5519e134d079085308f47d3` |
| `jvm-full/result.json` | `b5ceab3ee923a222422af6dbd7a2cda6971ae24273279d7c126e7341328b8bcc` |
| `jvm-full/invocation.json` | `9826f51c5fcf14c28213f63a8cd48abe15cce301cc436f7dcf29799d03701ce8` |
| `jvm-full/ownership.json` | `9b270564ac58463c95c98539d0258b11b137fa3cfcdbff3ebb5fc8eb00887152` |
| `jvm-full/sources.json` | `d2d9b5a470725fc7715b727cbcbf4850d062d089101618407c03409aa6634193` |
| `jvm-full/xml/TEST-app.pristinepage.data.RenderFileIntentTest.xml` | `afbbff4ecafe46d53ad4b3b09a562f6dcbf2feb2af2b2d5e4ab2d2214943d16c` |
| `sql-red.log` / `sql-green.log` | `58df715d0740a2ef93f26eaa7abbcce71c748938b9c8a985ddbc9932d6bfe723` / `ad76beb0108f4e7ae186228214a63844331dcfff1eaee006dab4cff76be9439d` |
| Generated `ExportSnapshotDao_Impl.kt` | `fb074504d9b17305a7b63acec27b0b01518702a5b8624a812a70fc6819e7b12e` |
| Input `ExportSnapshotDao.kt` | `41f355017595c6c183a1c8c17727342c030dfe91083dd1b1f66b3e5707af6e07` |
| Input `RoomFrozenRenderReads.kt` | `edc84e5a3547c111789ba240bbefa8572baf470b651e87a835ebcf1bb2999016` |
| Input `RoomRenderIntents.kt` | `9761720c0b9ac70f3344fd5f7602b3ed0365a1dfe3e073d8f75e61d70f8b97b0` |
| Input `RenderFileIntent.kt` | `989182c1927cce05ba96509fbf3089b4942fb51ae5adb9d9ec54fa3e25389ed4` |
| Input `RenderFileIntentTest.kt` | `2c82b6e8099680a4b6a0285ddc27d38d902e4e483f1bd8d841e510e967e4aaf6` |

**Included commit:** One verified local commit, authored and committed by lrq3000 `<1118942+lrq3000@users.noreply.github.com>`: `feat(storage): journal exact leased frozen render attempts`. It records the scoped metadata intent and one-use copy transition, guard and rollback rationale, and exact checks. The handoff is part of that commit; obtain its immutable hash and full attributed description with `git -C X:/local-workspace/.worktrees/pristinepage-export-render-intent log --format=fuller 70ea1d56ce22507657909379a8f32f90a1bca70b..work/pristinepage-export-render-intent`. Agentic stack: OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`), reasoning level not exposed.

## Focused SQL regression

This executable appendix uses the existing read-only SQL extractor and exported Room v1 DDL fixture. It tests the actual annotations, not a copy of their SQL. From the assigned worktree, run `python -B -c "from pathlib import Path; s=Path('validation/storage/export-render-intent-handoff.md').read_text(encoding='utf-8'); exec(s.split('\x60'*3+'python\n')[1].split('\n'+'\x60'*3,1)[0])"`. The command creates no database file or bytecode; the fixture uses in-memory SQLite. Keep it with the handoff because the claim does not own a standalone Python source path.

```python
import sqlite3
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path.cwd() / "validation/storage"))
import test_export_render_job_read_bounds as render_reads
import test_room_schema

uuid = render_reads.uuid


class RenderIntentSqlTest(unittest.TestCase):
    query_sql = render_reads.ExportRenderJobReadBoundsTest.query_sql

    def setUp(self):
        fixture = test_room_schema.RoomSchemaTest()
        self.addCleanup(fixture.doCleanups)
        self.db = fixture.open_schema()
        self.job = uuid(1)
        self.tx = uuid(2)
        self.owner = uuid(3)
        self.render = uuid(4)
        # Independently count 39 tag/NUL bytes, eight UUIDs, four hashes, three 64-bit fields and two 32-bit fields.
        self.args = dict(jobId=self.job, attempt=1, transactionId=self.tx, updatedAt=202, expectedPayloadBytes=615)
        self.db.execute(
            "INSERT INTO Job VALUES (?,'PAGE_RENDER',?,?,NULL,NULL,x'0102','RUNNING',1,?,150,1,NULL,NULL,1,2)",
            (self.job, "page-render-v1:" + "a" * 64, "a" * 64, self.owner),
        )
        self.journal(self.tx, "RENDER", self.job, 1)

    def journal(self, tx, kind, owner, attempt):
        self.db.execute(
            "INSERT INTO FileTransaction VALUES (?,?,?,'INTENT',?,?,NULL,NULL,NULL,zeroblob(615),?,NULL,101)",
            (tx, kind, owner, f"staging/{tx}/derived.part", f"renders/{self.render}/page.bin", attempt),
        )

    def flags(self, name, **overrides):
        sql = self.query_sql(name)
        return self.db.execute(
            f"WITH admitted(flag) AS ({sql}) SELECT CASE WHEN typeof(flag)='integer' AND flag IN (0,1) THEN flag ELSE -1 END FROM admitted",
            self.args | overrides,
        ).fetchall()

    def check_guard(self, query, table, changes, key, value):
        self.assertEqual([(1,)], self.flags(query))
        for column, adversaries in changes.items():
            for stored in adversaries:
                with self.subTest(column=column, adversary_type=type(stored).__name__):
                    self.db.execute("SAVEPOINT adversary")
                    try:
                        self.db.execute(f"UPDATE {table} SET {column}=? WHERE {key}=?", (stored, value))
                        self.assertEqual([(0,)], self.flags(query))
                    finally:
                        self.db.execute("ROLLBACK TO adversary")
                        self.db.execute("RELEASE adversary")

    def test_full_lease_projection_supplement_checks_every_extra_storage_class_and_extent(self):
        self.assertEqual([], self.flags("renderJobLeaseReadIsBounded", jobId=uuid(9)))
        self.check_guard("renderJobLeaseReadIsBounded", "Job", {
            "state": [b"RUNNING", "RUNNING\0" + "a" * 512],
            "attempt": [2.5, b"1", -1],
            "leaseOwner": [self.owner.encode(), self.owner + "\0" + "a" * 512],
            "leaseUntil": [150.5, b"150", -1],
            "leaseGeneration": [1.5, b"1", -1],
            "checkpoint": ["not a BLOB", b"a" * 524289],
            "errorCode": [b"ERROR", "E\0" + "a" * 512],
            "createdAt": [0.5, b"0"],
            "updatedAt": [0.5, b"0"],
        }, "jobId", self.job)
        self.db.execute("UPDATE Job SET state='CHECKPOINTED', checkpoint=zeroblob(524288) WHERE jobId=?", (self.job,))
        self.assertEqual([(1,)], self.flags("renderJobLeaseReadIsBounded"))

    def test_journal_guard_nests_neither_coercion_nor_unbounded_result(self):
        self.check_guard("renderIntentReadIsBounded", "FileTransaction", {
            "transactionId": [self.tx.encode(), self.tx[:-2] + "\0x", "t" * 2048],
            "state": [b"INTENT", "INTENT\0" + "t" * 512],
            "stagingPath": [f"staging/{self.tx}/derived.part".encode(), f"staging/{self.tx}/derived.part\0x", None],
            "finalPath": [f"renders/{self.render}/page.bin".encode(), f"renders/{self.render}/page.bin\0x", None],
            "sealSha256": [b"a" * 64, "a" * 62 + "\0x"],
            "expectedByteSha256": [b"a" * 64, "a" * 64 + "\0" + "x" * 1024],
            "expectedByteCount": [1.5, b"1", 0],
            "payload": ["x" * 615, b"z" * 614, b"z" * 616, b"z" * 521, b"z" * 8192],
            "errorCode": [b"ERROR", "E\0" + "a" * 512],
            "updatedAt": [0.5, b"0"],
        }, "transactionId", self.tx)
        self.assertEqual([(0,)], self.flags("renderIntentReadIsBounded", expectedPayloadBytes=0))
        self.assertEqual([(0,)], self.flags("renderIntentReadIsBounded", expectedPayloadBytes=521))

    def test_malformed_attempt_is_stored_but_invisible_to_exact_integer_lookup(self):
        self.journal(uuid(5), "RENDER", self.job, 2)
        for raw, storage_class in [(1.25, "real"), ("bad", "text"), (b"1", "blob"), (0, "integer"), (-1, "integer")]:
            with self.subTest(raw=raw):
                tx = uuid(6)
                self.db.execute("SAVEPOINT malformed")
                try:
                    self.journal(tx, "RENDER", self.job, raw)
                    actual = self.db.execute("SELECT typeof(attempt) FROM FileTransaction WHERE transactionId=?", (tx,))
                    self.assertEqual((storage_class,), actual.fetchone())
                    self.assertEqual([], self.flags("renderIntentReadIsBounded", attempt=3))
                    self.assertEqual([(1,)], self.flags("renderIntentReadIsBounded", attempt=2))
                finally:
                    self.db.execute("ROLLBACK TO malformed")
                    self.db.execute("RELEASE malformed")

    def test_malformed_attempt_scalar_is_owner_and_kind_scoped(self):
        self.journal(uuid(5), "RENDER", self.job, 2)
        self.journal(uuid(6), "RENDER", uuid(9), 1.25)
        self.journal(uuid(7), "EXPORT", self.job, "bad")
        query = "renderIntentHasMalformedAttempts"
        self.assertEqual([(0,)], self.flags(query))
        self.assertEqual([(1,)], self.flags(query, jobId=uuid(9)))
        self.assertEqual([(0,)], self.flags(query, jobId=uuid(99)))
        for raw in (1.25, "bad", b"1", 0, -1):
            with self.subTest(raw=raw):
                self.db.execute("SAVEPOINT malformed")
                try:
                    self.journal(uuid(8), "RENDER", self.job, raw)
                    self.assertEqual([(1,)], self.flags(query))
                finally:
                    self.db.execute("ROLLBACK TO malformed")
                    self.db.execute("RELEASE malformed")

    def test_projection_and_cas_are_exactly_scoped_and_never_replace(self):
        self.journal(uuid(5), "RENDER", self.job, 2)
        self.journal(uuid(6), "RENDER", uuid(9), 1)
        self.journal(uuid(7), "EXPORT", self.job, 1)
        with self.assertRaises(sqlite3.IntegrityError):
            self.journal(uuid(8), "RENDER", self.job, 1)
        self.assertEqual([], self.flags("renderIntentReadIsBounded", attempt=3))
        for attempt, tx in [(1, self.tx), (2, uuid(5))]:
            self.assertEqual([(1,)], self.flags("renderIntentReadIsBounded", attempt=attempt))
            projected = self.db.execute(self.query_sql("renderIntentRow"), self.args | {"attempt": attempt})
            self.assertEqual(tx, projected.fetchone()[0])
            self.assertIsNone(projected.fetchone())
        cas = self.query_sql("markRenderIntentCopying")
        for override in (dict(jobId=uuid(9)), dict(attempt=2), dict(transactionId=uuid(7))):
            self.assertEqual(0, self.db.execute(cas, self.args | override).rowcount)
        self.assertEqual(1, self.db.execute(cas, self.args).rowcount)
        self.assertEqual(0, self.db.execute(cas, self.args).rowcount)
        counts = self.db.execute("SELECT state, updatedAt, count(*) FROM FileTransaction GROUP BY state, updatedAt ORDER BY state").fetchall()
        self.assertEqual([("COPYING", 202, 1), ("INTENT", 101, 3)], counts)


unittest.main(argv=["render_intent_sql"], verbosity=2)
```

## Fix round 1/5: lease observation and malformed attempt keys

**Initial motivation and authority:** Independent `U-RENDER-INTENT-R` required two Important fixes before adoption (0 Critical / 2 Important / 2 Minor, coordinator gate `a40a47bb6da780957ae387a6288f1f26e715ae94:validation/progress.md`). Exact fix base `407efd31c3da6609502461b5a69c20b80ed53306` in the same worker branch and worktree. Preserve the base implementation and receipts above as historical evidence; the executable SQL appendix now reproduces the corrected scope.

**I1 — sample after the final SQL read:** Authenticate the bounded current `JobRow` as before, including canonical frozen input identity and explicit durable `CANCELED`. Cache the exact JOB-pin match before sampling the bounded local owner's liveness and boot-monotonic time; call the existing `JobLeaseFence` with the same authenticated row via the existing in-memory adapter. No SQL follows that decisive sample on the final fence. A stale/dead/expired lease wins over an observed pin or attempt mismatch; a raw-unbounded or non-authentic job still fails before trusting the row. Both post-write paths propagate a failure from inside the transaction and roll back. There is still a gap between the final sample and SQLite COMMIT: neither transaction serialization nor this check can atomically join a database commit with the monotonic clock or owner scope.

**I2 — refuse allocation on an ambiguous attempt miss:** Only on the create path after its exact-key guard returns missing, use `SELECT EXISTS` on the existing `RENDER` kind + exact JobId owner key prefix. Any sibling with a non-INTEGER storage class or nonpositive integer stops creation with `INTEGRITY_FAILED`, before allocating either UUID or inserting. The query is scalar and never materializes an attempt, payload, seal, or path. A positive INTEGER sibling does not block a new attempt; other kinds/owners do not participate. Exact present keys and the `COPYING` phase CAS are unchanged; existing attempts keep their own data. The key-range query may inspect the matching owner's attempt entries, not row bodies or all operation owners. No schema/index/identity-wire changes.

**Controlled RED:** The five new failing JVM assertions were three incorrectly accepted post-write lease outcomes (expiry during the last pin lookup after INSERT and after CAS, plus owner liveness becoming ENDED during that lookup), unexpected allocation after the malformed-key miss, and absence of the guarded create-path query on two valid creates. Exact-key replay/COPYING with a malformed sibling remained a passing fast-path control. Actual exported DDL independently stored REAL, TEXT, BLOB, zero and negative attempt witnesses without selecting their payloads; the exact positive-integer lookup missed them and still found a valid sibling. The SQL suite failed only at the missing new annotation. The corrected fixture expects a 615-byte envelope and explicitly refuses the prior 521-byte fixture extent.

**Executed checks:** Ran the exact focused and full `gradlew.bat --offline --no-daemon --console=plain --max-workers=4 --dependency-verification=strict :data:testDebugUnitTest` commands above (focused adds `--tests *RenderFileIntentTest`) with the same verified cached Gradle 8.13 distribution/launcher, approved process-local JBR/SDK/cache/preferences, unset `ANDROID_SDK_HOME`, 420-second bounds and existing 4 GiB heap. `fix1-jvm-red` exited 1 in 35s (3 of 22 tasks executed): 12 passes, five intended assertion failures and zero errors/skips. `fix1-jvm-green` exited 0 in 1m03s (7 of 22 executed): 17 fresh focused passes. `fix1-jvm-full` exited 0 in 23s (1 of 22 executed): 482 fresh data cases in 51 reports. Both GREENs had zero failures, errors or skips. The SQL appendix command above exited 1 on the base (four passed, one missing-query setup failure) and 0 after correction (all five passed in 0.062s). Generated Room DAO inspection confirms the new projection extracts one scalar only. No native test or APK build.

**Retained evidence and ownership:** Under ignored `build/render-intent-evidence/`, the three run directories retain invocations, logs, exits, source manifests (224 inputs each), fresh XMLs and daemon evidence. Free C: bytes before RED/focused/full were 51,294,871,552 / 51,296,219,136 / 51,292,246,016. The invocation-matched Gradle daemons (PIDs 20616 / 16604 / 29248) all returned native wait 0 / exit 0 and no Gradle/Kotlin build JVM remained enumerated. Sole build slot released on handoff.

| Evidence (relative to retained evidence root unless noted) | SHA-256 |
|---|---|
| `fix1-jvm-red/result.json` | `7811c7401bcc4d03d0688abe89dc9d929a549c7543c3c3155c354ddaba15e03d` |
| `fix1-jvm-green/result.json` | `b84f9b44b014533a265fcf939566fcfa823edcfd6d90ffdceb171efd99ff3f95` |
| `fix1-jvm-full/result.json` | `e9a75f0bbeb96c88f3fab9b42902bf6a2b1ac3c52d8762a991cfe82125ec681b` |
| `fix1-jvm-full/invocation.json` | `8db5e1d4b22ff4b94de4d846a9f8357c317e21c6d0da000c2c06c2e08288d97e` |
| `fix1-jvm-full/ownership.json` | `126723c465b4ad600319df10083d9bc1eedec6cf89d954006c18212be308761c` |
| `fix1-jvm-full/sources.json` | `f0180e5f45833ac588ff4227116b3faf5c1d976c86b7bb58ba349abdc939269c` |
| `fix1-jvm-full/xml/TEST-app.pristinepage.data.RenderFileIntentTest.xml` | `61033f16c3ec00fe1cc24d66e28f8ca477ebddbdc425a8029614d994c445b1b3` |
| `fix1-sql-red.log` / `fix1-sql-green.log` | `1e5dd8957bcf436ce63aef0860babcf94588be071379ca42e27346e200805d09` / `394f53896e40cb67c6c5d23ca78d81262ac434e228a5845c17bbe74c552d8158` |
| Generated `ExportSnapshotDao_Impl.kt` | `d5dd28506eaa11e46904c5fd51afdec65d541f2da8c407f24fa206898ee2ffe5` |
| Input `ExportSnapshotDao.kt` | `18e2d229355e4e8a972d8f13bf0b95cc16343748beeabbbe8dee38b6da0d527f` |
| Input `RoomRenderIntents.kt` | `9c66309652c2f1f700ede569174ddb4254ee75732b6ee48453f40cc040b9bae4` |
| Input `RenderFileIntentTest.kt` | `2cbdae821a07c927538934e0bae65a1379e5f82d06fe2f97c8b8204af0e41db3` |
| Unchanged Room v1 schema `1.json` | `2661b0ad8c5e1d1f696aab864245628ac0d9de5e7cc44a2d38a20d10efe42a8d` |

**Included fix commit:** `fix(storage): fence final render intent reads and attempt misses`, authored and committed by lrq3000 `<1118942+lrq3000@users.noreply.github.com>`. This addendum is in that commit; retrieve its full hash and description with `git -C X:/local-workspace/.worktrees/pristinepage-export-render-intent log --format=fuller 407efd31c3da6609502461b5a69c20b80ed53306..work/pristinepage-export-render-intent`. Agentic stack unchanged: OpenCode / OpenAI GPT-6.2 Sol (`gpt-6.2-sol`).

**Limits and next gate:** This is host SQLite and JVM transaction-port evidence. It is not a native Room crash, clock/commit atomicity proof, new recovery policy, file writer or output result. The actual coroutine cancel-after-write and complete canonical-envelope golden Minors remain deferred. Coordinator must obtain scoped independent re-review before replay or adoption. No primary/integration, schema, dependencies, host repair, Android, network, signing, push, merge, cleanup or nested delegation.
