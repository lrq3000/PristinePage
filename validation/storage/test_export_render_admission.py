"""Exercise the actual first render-admission CAS against exported Room v1 DDL."""

from contextlib import contextmanager
from hashlib import sha256
import re
import sqlite3
import unittest
from uuid import UUID

from test_export_job_read_bounds import DAO
import test_room_schema


def uid(number):
    return str(UUID(int=number))


class ExportRenderAdmissionSqlTest(unittest.TestCase):
    def setUp(self):
        # As in the render-read suite, require a real annotation, never a SQL stand-in.
        matches = re.findall(
            r'@Query\s*\(\s*(?:"""((?:(?!""").)*)"""|"([^"\\\r\n]*)")\s*\)\s*'
            r'(?:(?:internal|abstract)\s+)*fun\s+admitPendingRender\s*\(',
            DAO.read_text(encoding="utf-8"), re.S,
        )
        self.assertEqual(1, len(matches),
                         f"Expected one actual ExportSnapshotDao.admitPendingRender @Query (found {len(matches)})")
        raw, quoted = matches[0]
        self.sql = raw or quoted
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()
        self.db.row_factory = sqlite3.Row
        schema.seed_source(self.db)
        self.job = uid(0xA1)
        self.other_job = uid(0xB2)
        # Opaque SQL equality fixtures, not vectors for the v1 canonical render encoder.
        self.input_prefix = b"A" * 727 + b"\0"
        self.args = self.insert_pending(self.job, self.input_prefix + b"a")
        self.insert_pending(self.other_job, self.input_prefix + b"b")
        self.db.commit()

    def insert_pending(self, job, payload):
        digest = sha256(payload).hexdigest()
        args = dict(jobId=job, idempotenceKey=f"page-render-v1:{digest}",
                    canonicalInputSha256=digest, input=payload, updatedAt=11)
        self.db.execute(
            """INSERT INTO Job (jobId, kind, idempotenceKey, canonicalInputSha256, snapshotId, pageOrdinal,
                               input, state, attempt, leaseOwner, leaseUntil, leaseGeneration, checkpoint,
                               errorCode, createdAt, updatedAt)
               VALUES (:jobId, 'PAGE_RENDER', :idempotenceKey, :canonicalInputSha256, NULL, NULL,
                       :input, 'PENDING', 0, NULL, NULL, 0, NULL, NULL, 3, 10)""", args,
        )
        return args

    def admit(self, **overrides):
        cursor = self.db.execute(self.sql, self.args | overrides)
        self.assertIsNone(cursor.description, "Admission must report only the UPDATE count, not project rows")
        return cursor.rowcount

    def snapshot(self):
        # Full rows are read only by the independent test oracle, from this small synthetic database.
        return {
            table: [dict(row) for row in self.db.execute(f"SELECT * FROM {table} ORDER BY {key}")]
            for table, key in (
                ("Job", "jobId"), ("RenderedPage", "renderId"), ("FileTransaction", "transactionId"),
                ("SourceReference", "ownerType, ownerId, sourceId"),
            )
        }

    def assert_admitted(self):
        expected = self.snapshot()
        target, = (row for row in expected["Job"] if row["jobId"] == self.job)
        target.update(state="ADMITTED", updatedAt=11)
        before = self.db.total_changes
        self.assertEqual(1, self.admit())
        self.assertEqual(1, self.db.total_changes - before)
        self.assertEqual(expected, self.snapshot())

    def assert_refused(self, **overrides):
        expected = self.snapshot()
        before = self.db.total_changes
        self.assertEqual(0, self.admit(**overrides))
        self.assertEqual(before, self.db.total_changes)
        self.assertEqual(expected, self.snapshot())

    @contextmanager
    def isolated(self):
        self.db.execute("SAVEPOINT admission_fixture")
        try:
            yield
        finally:
            self.db.execute("ROLLBACK TO admission_fixture")
            self.db.execute("RELEASE admission_fixture")

    def assert_refused_with_field(self, column, value, storage_class):
        with self.subTest(column=column, value=value), self.isolated():
            # All interpolated column names are literals in the test matrix. Verify what survived SQLite affinity.
            self.db.execute(f"UPDATE Job SET {column}=? WHERE jobId=?", (value, self.job))
            stored = self.db.execute(f"SELECT typeof({column}) FROM Job WHERE jobId=?", (
                value if column == "jobId" else self.job,
            )).fetchone()
            self.assertIsNotNone(stored)
            self.assertEqual(storage_class, stored[0])
            self.assert_refused()

    def insert_render(self, job, state="READY"):
        self.db.execute(
            """INSERT INTO RenderedPage (renderId, jobId, sourceId, pageId, recipeRevision, cacheKey,
                                        execution, executionSha256, relativePath, byteSha256, rasterPixelSha256,
                                        byteCount, width, height, renderStatus, state)
               VALUES (?, ?, 'source', ?, 1, ?, x'00', ?, ?, ?, ?, 17, 3, 2, 'OK', ?)""",
            (uid(4), job, uid(5), "render-cache-v1:" + "a" * 64, "b" * 64, f"renders/{uid(4)}/page.bin",
             "c" * 64, "d" * 64, state),
        )

    def insert_journal(self, owner, kind="RENDER", attempt=1, state="INTENT"):
        self.db.execute(
            """INSERT INTO FileTransaction (transactionId, kind, ownerId, state, payload, attempt, updatedAt)
               VALUES (?, ?, ?, ?, x'01', ?, 10)""",
            (uid(6), kind, owner, state, attempt),
        )

    def test_admits_only_exact_row_changes_only_state_and_timestamp_and_refuses_replay(self):
        self.assert_admitted()
        self.assert_refused()
        self.assert_refused(updatedAt=12)

    def test_refuses_independent_job_id_key_digest_and_input_drift(self):
        # Keep the original exact row present for wrong-ID binds; unrelated pending jobs must not authorize it.
        for job in (uid(0x99), self.other_job):
            with self.subTest(job=job):
                self.assert_refused(jobId=job)
        for column, value, storage_class in (
            ("idempotenceKey", "page-render-v1:" + "f" * 64, "text"),
            ("canonicalInputSha256", "e" * 64, "text"),
            ("input", self.input_prefix + b"b", "blob"),
            ("input", self.args["input"] + b"\0", "blob"),
        ):
            self.assert_refused_with_field(column, value, storage_class)

    def test_refuses_raw_identity_kind_state_and_input_aliases(self):
        for column, valid in (
            ("jobId", self.job), ("idempotenceKey", self.args["idempotenceKey"]),
            ("canonicalInputSha256", self.args["canonicalInputSha256"]),
            ("kind", "PAGE_RENDER"), ("state", "PENDING"),
        ):
            self.assert_refused_with_field(column, valid.encode("ascii"), "blob")
            self.assert_refused_with_field(column, valid + "\0suffix", "text")
            self.assert_refused_with_field(column, valid.swapcase(), "text")
        self.assert_refused_with_field("input", self.args["input"].decode("ascii"), "text")

    def test_refuses_nonpending_nonrender_or_nonvirgin_metadata(self):
        for state in ("ADMITTED", "RUNNING", "CHECKPOINTED", "SUCCEEDED",
                      "WAITING_RESOURCE", "RETRYABLE", "FAILED", "CANCELED", "INTERRUPTED"):
            self.assert_refused_with_field("state", state, "text")
        self.db.execute("INSERT INTO ExportSnapshot VALUES (?, 'doc', 1, ?, x'01', 'FROZEN', 1, 100)",
                        (uid(7), "a" * 64))
        for column, value, storage_class in (
            ("kind", "EXPORT_ASSEMBLY", "text"), ("snapshotId", uid(7), "text"), ("pageOrdinal", 0, "integer"),
            ("leaseOwner", uid(8), "text"), ("leaseUntil", 0, "integer"),
            ("checkpoint", b"", "blob"), ("errorCode", "", "text"),
        ):
            self.assert_refused_with_field(column, value, storage_class)

    def test_attempt_and_generation_require_raw_integer_zero_without_narrowing(self):
        for column in ("attempt", "leaseGeneration"):
            for value, storage_class in ((1, "integer"), (-1, "integer"), (1 << 32, "integer"),
                                         (0.5, "real"), (b"0", "blob"), ("0x0", "text")):
                self.assert_refused_with_field(column, value, storage_class)

    def test_any_same_job_render_blocks_admission_even_if_not_ready(self):
        for state in ("READY", "QUARANTINED"):
            with self.subTest(state=state), self.isolated():
                self.insert_render(self.job, state=state)
                self.assert_refused()

    def test_any_same_job_render_journal_blocks_without_filtering_attempt_or_phase(self):
        for attempt, state in ((1, "INTENT"), (7, "COMMITTED"), (b"0", "FAILED")):
            with self.subTest(attempt=attempt, state=state), self.isolated():
                self.insert_journal(self.job, attempt=attempt, state=state)
                self.assert_refused()

    def test_other_job_render_other_job_render_journal_and_other_kind_do_not_block(self):
        # Introduce each control alone, so one scope error cannot be masked by another fixture.
        with self.subTest(control="another job's render"), self.isolated():
            self.insert_render(self.other_job)
            self.assert_admitted()
        with self.subTest(control="another job's RENDER journal"), self.isolated():
            self.insert_journal(self.other_job)
            self.assert_admitted()
        with self.subTest(control="this job's non-RENDER journal"), self.isolated():
            self.insert_journal(self.job, kind="EXPORT")
            self.assert_admitted()

    def test_admission_seeks_existing_exact_indexes_without_scan(self):
        plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + self.sql, self.args)]
        self.assertFalse(any("SCAN " in step or "TEMP B-TREE" in step for step in plan), plan)
        # Either unique index can limit the UPDATE to one candidate; the wrong-ID case still enforces exact jobId.
        for alternatives in (
            (("sqlite_autoindex_Job_1", "jobId=?"), ("index_Job_idempotenceKey", "idempotenceKey=?")),
            (("index_RenderedPage_jobId", "jobId=?"),),
            (("index_FileTransaction_kind_ownerId_attempt", "kind=?", "ownerId=?"),),
        ):
            self.assertTrue(any("SEARCH " in step and all(token in step for token in alternative)
                                for step in plan for alternative in alternatives), plan)


if __name__ == "__main__":
    unittest.main(verbosity=2)
