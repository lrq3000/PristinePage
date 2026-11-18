"""Run the producer's reuse queries against actual exported Room v1 DDL, not reimplemented SQL."""

import unittest

import test_document_catalog_bounds as catalog_bounds
import test_export_render_job_read_bounds as render_bounds


class ExportFreezeReuseBoundsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = render_bounds.ExportRenderJobReadBoundsTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.db = self.fixture.db
        self.job = self.fixture.job
        self.payload = self.fixture.payload
        self.key = self.fixture.identity(self.job, self.payload)[2]

    def lookup(self, key):
        sql = self.fixture.query_sql("renderJobIdByKey")
        # Keep the witness safe even if a regression projects a large value.
        rows = self.db.execute(
            f"WITH matched(id) AS ({sql}) "
            "SELECT CASE WHEN typeof(id)='text' AND length(CAST(id AS BLOB))<=36 THEN id ELSE NULL END FROM matched",
            {"key": key},
        )
        return rows.fetchall()

    def plan(self, sql, **params):
        return [row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + sql, params)]

    def assert_search(self, plan, table, *keys):
        self.assertTrue(any(f"SEARCH {table} " in step and all(key in step for key in keys) for step in plan), plan)
        self.assertFalse(any("SCAN " in step or "TEMP B-TREE" in step for step in plan), plan)

    def test_exact_key_projects_only_job_id_and_uses_unique_index(self):
        self.assertEqual([(self.job,)], self.lookup(self.key))
        other = self.fixture.identity(self.fixture.other, self.fixture.other_payload)[2]
        self.assertEqual([(self.fixture.other,)], self.lookup(other))
        for absent in (self.key.upper(), self.key + "\0x", self.key.encode("ascii"), self.key[:-1]):
            self.assertEqual([], self.lookup(absent))
        sql = self.fixture.query_sql("renderJobIdByKey")
        self.assertEqual(1, len(self.db.execute(sql, {"key": self.key}).description))
        self.assert_search(self.plan(sql, key=self.key), "Job", "index_Job_idempotenceKey", "idempotenceKey=?")

    def test_bad_matched_id_is_a_present_sentinel_not_absent(self):
        for raw in (self.job.encode("ascii"), 9, "", "x" * 8192, self.job[:-2] + "\0x", self.job + "\0tail", self.job[:-2] + "é"):
            with self.subTest(raw_type=type(raw).__name__, extent=len(str(raw))):
                self.db.execute("SAVEPOINT candidate")
                try:
                    self.fixture.set_job("jobId", raw)
                    self.assertEqual([("",)], self.lookup(self.key))
                finally:
                    self.db.execute("ROLLBACK TO candidate")
                    self.db.execute("RELEASE candidate")
        # Only raw type/extent are admitted by SQL; Kotlin authenticates UUID grammar.
        self.fixture.set_job("jobId", "x" * 36)
        self.assertEqual([("x" * 36,)], self.lookup(self.key))

    def test_by_id_requires_exact_blob_extent_and_projects_no_mutable_job_payload(self):
        identity = self.fixture.query_sql("renderJobIdentity")
        guard = self.fixture.query_sql("renderJobReadIsBounded")
        for name, raw in (("short", self.payload[:-1]), ("long", self.payload + b"\0"), ("same-size TEXT", self.payload.decode("ascii"))):
            with self.subTest(name=name):
                self.fixture.set_job("input", raw)
                self.assertEqual([(0,)], self.fixture.gate())
        self.fixture.set_job("input", self.payload)
        for field, value in (("checkpoint", b"x" * 524_289), ("errorCode", b"unsupported"), ("createdAt", b"not-an-epoch"), ("updatedAt", "also-not-an-epoch")):
            self.fixture.set_job(field, value)
        self.assertEqual([(1,)], self.fixture.gate())
        result = self.db.execute(identity, {"jobId": self.job})
        self.assertEqual(("jobId", "kind", "idempotenceKey", "canonicalInputSha256", "input"), tuple(x[0] for x in result.description))
        self.assertEqual([self.fixture.identity(self.job, self.payload)], result.fetchall())
        self.assert_search(self.plan(guard, jobId=self.job, expectedInputBytes=len(self.payload)), "Job", "jobId=?")
        self.assert_search(self.plan(identity, jobId=self.job), "Job", "jobId=?")

    def test_pins_project_only_two_exact_owner_ids_including_bad_rows_and_no_audit(self):
        self.fixture.pin(render_bounds.uuid(31), "EXPORT_PAGE")
        self.fixture.pin(render_bounds.uuid(32), owner_id=self.fixture.other)
        self.assertEqual([], self.fixture.pins())
        self.fixture.pin(b"A" * 36)  # Stored as BLOB; has a parent, and audit data is also raw BLOB.
        self.assertEqual([""], self.fixture.pins())
        self.fixture.pin(render_bounds.uuid(33))
        self.assertCountEqual(["", render_bounds.uuid(33)], self.fixture.pins())
        self.fixture.pin(render_bounds.uuid(34))
        self.assertEqual(2, len(self.fixture.pins()))
        sql = self.fixture.query_sql("boundedRenderJobPinSourceIds")
        self.assert_search(self.plan(sql, jobId=self.job), "SourceReference", "ownerType=?", "ownerId=?")

    def test_catalog_guard_checks_the_exact_source_and_linked_blob_before_projection(self):
        catalog = catalog_bounds.DocumentCatalogBoundsTest()
        catalog.setUp()
        self.addCleanup(catalog.doCleanups)
        self.assertEqual((1,), catalog.query("sourceReadIsBounded", id=catalog.source).fetchone())
        self.assertIsNone(catalog.query("sourceReadIsBounded", id=render_bounds.uuid(999)).fetchone())
        with self.subTest(chain="present malformed Blob"):
            catalog.db.execute("UPDATE SourceBlob SET encodedWidth=4294967299 WHERE sourceBlobId=?", (catalog.blob,))
            self.assertEqual((0,), catalog.query("sourceReadIsBounded", id=catalog.source).fetchone())
            self.assertEqual((0,), catalog.query("sourceBlobLinkIsMissing", id=catalog.source).fetchone())
        with self.subTest(chain="missing exact Blob"):
            catalog.db.execute("UPDATE Source SET sourceBlobId=? WHERE sourceId=?", (render_bounds.uuid(998), catalog.source))
            self.assertEqual((0,), catalog.query("sourceReadIsBounded", id=catalog.source).fetchone())
            self.assertEqual((1,), catalog.query("sourceBlobLinkIsMissing", id=catalog.source).fetchone())
        for method in ("sourceReadIsBounded", "sourceBlobLinkIsMissing"):
            plan = [r[3] for r in catalog.db.execute("EXPLAIN QUERY PLAN " + catalog.sql(method), {"id": catalog.source})]
            self.assert_search(plan, "s", "sourceId=?")
            self.assert_search(plan, "b", "sourceBlobId=?")


if __name__ == "__main__":
    unittest.main(verbosity=2)
