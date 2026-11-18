"""Exercise actual DAO projections on exported Room v1 DDL; this is not a terminal Room consumer or file proof."""

import sqlite3
import unittest

import test_export_render_job_read_bounds as existing_render
import test_room_schema


uid = existing_render.uuid
V1_EXECUTION_CAP = 39_309  # Fixture input, not a second production limit.


class ExportTerminalRenderReadSqlTest(unittest.TestCase):
    def setUp(self):
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()
        schema.seed_source(self.db)
        self.extract_sql = existing_render.ExportRenderJobReadBoundsTest().query_sql
        self.job, self.other_job, self.render, self.page, self.source = map(uid, range(1, 6))
        self.db.execute("UPDATE Source SET sourceId=? WHERE sourceId='source'", (self.source,))
        for n, job in enumerate((self.job, self.other_job), 1):
            # Immutable job admission is outside this test. Neither input nor checkpoint should be projected here.
            self.db.execute(
                "INSERT INTO Job VALUES (?,'PAGE_RENDER',?,?,NULL,NULL,?,'SUCCEEDED',3,NULL,NULL,3,zeroblob(65536),NULL,0,0)",
                (job, "page-render-v1:" + f"{n:064x}", f"{n:064x}", b"opaque job input"),
            )

    def rows(self, method, **params):
        cursor = self.db.execute(self.extract_sql(method), params)
        self.assertEqual(1, len(cursor.description), f"{method} must project only one column")
        return cursor.fetchall()

    def attempt(self, job=None):
        rows = self.rows("terminalRenderAttempt", jobId=job or self.job)
        self.assertLessEqual(len(rows), 1)
        value = rows[0][0] if rows else None
        if value is not None:
            self.assertIs(type(value), int)
        return value

    def ids(self, job=None):
        return [row[0] for row in self.rows("terminalRenderIds", jobId=job or self.job)]

    def guard(self, **overrides):
        args = dict(renderId=self.render, jobId=self.job, pageId=self.page, recipeRevision=7,
                    width=3, height=2, maxExecutionBytes=V1_EXECUTION_CAP)
        return self.rows("terminalRenderReadIsBounded", **(args | overrides))

    def set_fields(self, table, key, identity, **changes):
        # Identifiers come only from the fixed test fixture, not from the synthetic stored values.
        columns = ", ".join(f'"{name}"=?' for name in changes)
        self.db.execute(f'UPDATE "{table}" SET {columns} WHERE "{key}"=?', (*changes.values(), identity))

    def insert_render(self, render=None, key=1, **overrides):
        render = self.render if render is None else render
        row = dict(renderId=render, jobId=self.job, sourceId=self.source, pageId=self.page, recipeRevision=7,
                   cacheKey="render-cache-v1:" + f"{key:064x}", execution=b"\x00raw", executionSha256="d" * 64,
                   relativePath=f"renders/{render}/page.bin", byteSha256="e" * 64, rasterPixelSha256="f" * 64,
                   byteCount=17, width=3, height=2, renderStatus="OK", state="READY") | overrides
        self.db.execute(f"INSERT INTO RenderedPage ({', '.join(row)}) VALUES ({', '.join(':' + key for key in row)})", row)

    def test_terminal_attempt_is_not_a_runnable_or_failed_attempt(self):
        self.assertEqual(3, self.attempt())
        self.assertIsNone(self.attempt(uid(99)))
        for state in ("PENDING", "WAITING_RESOURCE", "ADMITTED", "RUNNING", "CHECKPOINTED", "RETRYABLE",
                      "INTERRUPTED", "CANCELED", "FAILED", "succeeded", "SUCCEEDED\0tail", b"SUCCEEDED"):
            with self.subTest(state=state):
                self.set_fields("Job", "jobId", self.job, state=state)
                self.assertIsNone(self.attempt())
                self.assertEqual(3, self.attempt(self.other_job))

    def test_terminal_attempt_is_a_positive_raw_integer_match_and_has_no_lease_or_error(self):
        baseline = dict(kind="PAGE_RENDER", attempt=3, leaseGeneration=3, leaseOwner=None, leaseUntil=None, errorCode=None)
        for drift in (dict(kind="EXPORT_ASSEMBLY"), dict(kind=b"PAGE_RENDER"), dict(kind="PAGE_RENDER\0tail"),
                      dict(attempt=2), dict(leaseGeneration=4), dict(attempt=0, leaseGeneration=0),
                      dict(attempt=-1, leaseGeneration=-1), dict(attempt=3.25, leaseGeneration=3.25),
                      dict(attempt=b"3", leaseGeneration=b"3"),
                      dict(leaseOwner=uid(9)), dict(leaseUntil=0), dict(errorCode="")):
            with self.subTest(drift=drift):
                self.set_fields("Job", "jobId", self.job, **(baseline | drift))
                self.assertIsNone(self.attempt())
        wide_attempt = (1 << 32) + 3
        self.set_fields("Job", "jobId", self.job, **(baseline | dict(attempt=wide_attempt, leaseGeneration=wide_attempt)))
        self.assertEqual(wide_attempt, self.attempt())

    def test_job_index_returns_at_most_two_ids_including_nonready_and_malformed_siblings(self):
        self.insert_render(uid(10), key=10, jobId=self.other_job)
        self.assertEqual([], self.ids())
        self.insert_render()
        self.assertEqual([self.render], self.ids())
        self.insert_render(uid(11), key=11, state="QUARANTINED", execution=bytes(V1_EXECUTION_CAP + 1))
        self.assertCountEqual([self.render, uid(11)], self.ids())
        self.insert_render(uid(12), key=12)
        candidates = self.ids()
        self.assertEqual(2, len(candidates))
        self.assertEqual(2, len(set(candidates)))
        self.assertTrue(set(candidates) <= {self.render, uid(11), uid(12)}, candidates)
        sql = self.extract_sql("terminalRenderIds")
        plan = [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + sql, {"jobId": self.job})]
        self.assertTrue(any("SEARCH " in step and "index_RenderedPage_jobId" in step for step in plan), plan)
        self.assertFalse(any("SCAN " in step or "TEMP B-TREE" in step for step in plan), plan)

    def test_malformed_sibling_id_counts_as_a_bounded_sentinel(self):
        self.insert_render()
        for raw_id in (uid(11).encode("ascii"), "x" * 8192, uid(11) + "\0tail",
                       uid(11)[:-2] + "\0x", uid(11)[:-1] + "é"):
            with self.subTest(storage=type(raw_id).__name__, extent=len(raw_id)):
                self.db.execute("SAVEPOINT sibling")
                try:
                    self.insert_render(raw_id, key=11, relativePath="synthetic-refused-sibling", state="QUARANTINED",
                                       execution=bytes(V1_EXECUTION_CAP + 1))
                    self.assertCountEqual([self.render, ""], self.ids())
                finally:
                    self.db.execute("ROLLBACK TO sibling")
                    self.db.execute("RELEASE sibling")

    def test_render_guard_distinguishes_absent_from_wrong_frozen_binding(self):
        self.assertEqual([], self.guard())
        self.insert_render()
        self.assertEqual([(1,)], self.guard())
        self.assertEqual([], self.guard(renderId=uid(99)))
        for wrong in (dict(jobId=self.other_job), dict(pageId=uid(8)), dict(recipeRevision=8), dict(width=2), dict(height=3)):
            with self.subTest(wrong=wrong):
                self.assertEqual([(0,)], self.guard(**wrong))

    def test_render_guard_uses_the_callers_inclusive_blob_extent_not_text_length(self):
        self.insert_render()
        for execution, cap, expected in (
            (b"x", V1_EXECUTION_CAP, 1), (bytes(V1_EXECUTION_CAP), V1_EXECUTION_CAP, 1),
            (bytes(V1_EXECUTION_CAP + 1), V1_EXECUTION_CAP, 0), (bytes(V1_EXECUTION_CAP), V1_EXECUTION_CAP - 1, 0),
            # Deliberate SQL-only alternate cap; this does not admit v1 bytes in production.
            (bytes(V1_EXECUTION_CAP + 1), V1_EXECUTION_CAP + 1, 1),
            (b"", V1_EXECUTION_CAP, 0), (b"x", 0, 0), ("x", V1_EXECUTION_CAP, 0),
        ):
            with self.subTest(storage=type(execution).__name__, extent=len(execution), cap=cap):
                self.set_fields("RenderedPage", "renderId", self.render, execution=execution)
                self.assertEqual([(expected,)], self.guard(maxExecutionBytes=cap))

    def test_render_guard_refuses_raw_dimension_count_path_and_status_mismatches(self):
        self.insert_render()
        cases = (
            ("width", 3, 3.25, "real"), ("height", 2, (1 << 32) + 2, "integer"),
            ("recipeRevision", 7, 7.25, "real"), ("byteCount", 17, 17.5, "real"),
            ("byteCount", 17, 0, "integer"), ("byteCount", 17, -1, "integer"),
            ("relativePath", f"renders/{self.render}/page.bin", f"renders/{uid(9)}/page.bin", "text"),
            ("state", "READY", "QUARANTINED", "text"), ("renderStatus", "OK", "DEGRADED", "text"),
        )
        for field, valid, raw, storage in cases:
            with self.subTest(field=field, raw=raw):
                self.set_fields("RenderedPage", "renderId", self.render, **{field: raw})
                self.assertEqual((storage,), self.db.execute(
                    f'SELECT typeof("{field}") FROM RenderedPage WHERE renderId=?', (self.render,)
                ).fetchone())
                self.assertEqual([(0,)], self.guard())
                self.set_fields("RenderedPage", "renderId", self.render, **{field: valid})
        self.assertEqual([(1,)], self.guard())

    def test_render_guard_checks_text_bytes_and_chars_before_row_projection(self):
        self.insert_render()
        fields = dict(cacheKey="render-cache-v1:" + f"{1:064x}", executionSha256="d" * 64,
                      byteSha256="e" * 64, rasterPixelSha256="f" * 64)
        for field, valid in fields.items():
            for raw in (valid.encode("ascii"), valid + "\0" + "x" * 8192, valid[:-2] + "\0x",
                        valid[:-1] + "é", valid[:-1]):
                with self.subTest(field=field, storage=type(raw).__name__, extent=len(raw)):
                    self.set_fields("RenderedPage", "renderId", self.render, **{field: raw})
                    self.assertEqual([(0,)], self.guard())
            self.set_fields("RenderedPage", "renderId", self.render, **{field: valid})
        self.set_fields("RenderedPage", "renderId", self.render, cacheKey="x" + fields["cacheKey"][1:])
        self.assertEqual([(0,)], self.guard())
        self.set_fields("RenderedPage", "renderId", self.render, cacheKey=fields["cacheKey"])

        # A real FK parent lets us isolate the projected source ID's raw shape without disabling FK enforcement.
        self.assertEqual([(1,)], self.guard())
        for n, raw in enumerate((self.source.encode("ascii"), self.source + "\0tail", self.source[:-1] + "é"), 1):
            with self.subTest(source_id=n):
                self.db.execute("INSERT INTO Source VALUES (?,'blob',?,3,2,1,?,NULL,0,'READY',0)",
                                (raw, "b" * 64, f"synthetic-{n}"))
                self.set_fields("RenderedPage", "renderId", self.render, sourceId=raw)
                self.assertEqual([(0,)], self.guard())

    def test_older_committed_journal_is_not_the_exact_requested_attempt(self):
        def seed_journal(tx, kind, owner, attempt, state, payload):
            self.db.execute(
                "INSERT INTO FileTransaction VALUES (?,?,?,?,?,?,?,?,?,?,?,NULL,0)",
                (tx, kind, owner, state, f"staging/{tx}/derived.part", f"renders/{self.render}/page.bin",
                 "a" * 64, "e" * 64, 17, payload, attempt),
            )

        payload = b"v" * 615  # Correct v1 extent only; no canonical payload or commit proof.
        seed_journal(uid(20), "RENDER", self.job, 2, "COMMITTED", payload)
        seed_journal(uid(21), "EXPORT", self.job, 3, "COMMITTED", payload)
        seed_journal(uid(22), "RENDER", self.other_job, 3, "COMMITTED", payload)
        gate = "renderIntentReadIsBounded"
        self.assertEqual([(1,)], self.rows(gate, jobId=self.job, attempt=2, expectedPayloadBytes=len(payload)))
        self.assertEqual([], self.rows(gate, jobId=self.job, attempt=3, expectedPayloadBytes=len(payload)))
        seed_journal(uid(23), "RENDER", self.job, 3, "MOVED", payload)
        self.assertEqual([(1,)], self.rows(gate, jobId=self.job, attempt=3, expectedPayloadBytes=len(payload)))
        selected = self.db.execute(self.extract_sql("renderIntentRow"), {"jobId": self.job, "attempt": 3})
        selected.row_factory = sqlite3.Row
        row = selected.fetchone()
        self.assertIsNotNone(row)
        self.assertEqual((uid(23), "MOVED", 3), (row["transactionId"], row["state"], row["attempt"]))
        self.assertIsNone(selected.fetchone())
        self.set_fields("FileTransaction", "transactionId", uid(23), payload=payload[:-1])
        self.assertEqual([(0,)], self.rows(gate, jobId=self.job, attempt=3, expectedPayloadBytes=len(payload)))


if __name__ == "__main__":
    unittest.main(verbosity=2)
