"""Dormant ordinary PNG final metadata on inert in-memory SQLite with the actual Room v1 DDL and DAO SQL."""

import sqlite3
import unittest

import test_export_item_phases as phases
from test_export_item_bounds import expected_key, uid


class PngFinalMetadataSqlTest(unittest.TestCase):
    def setUp(self):
        self.phase = phases.ExportItemPhasesSqlTest()
        self.phase.setUp()
        self.addCleanup(self.phase.doCleanups)
        self.db = self.phase.db

    def sql(self, method, args):
        return self.db.execute(self.phase.dao(method), args)

    def moved(self, **plan):
        return self.phase.sealed(self.phase.plan(**plan), state="MOVED")

    def commit_args(self, moved, **changes):
        return self.phase.edge(moved, "COMMITTED", updatedAt=0) | changes

    def snapshot(self, n=13, **changes):
        return dict(snapshotId=uid(n), documentId=uid(42), documentRevision=29, snapshotSha256="c" * 64,
                    settings=b"opaque fixture settings", state="FROZEN", createdAt=8, retainedUntil=99, now=98) | changes

    def artifact(self, plan=None, **changes):
        plan = self.phase.plan() if plan is None else plan
        return {k: plan[k] for k in ("snapshotId", "artifactId", "ordinal")} | dict(
            byteSha256="b" * 64, byteCount=17, createdAt=0) | changes

    def artifact_row(self, artifact, **changes):
        return artifact | dict(mime="image/png", pageCount=1, state="READY", verification="ORDINARY_PNG_ENCODED_AND_RGB_V1",
                               relativePath=f"exports/{artifact['snapshotId']}/{artifact['artifactId']}.bin") | changes

    def seed(self, table, row):
        names = ", ".join(row)
        binds = ", ".join(":" + k for k in row)
        self.db.execute(f"INSERT INTO {table} ({names}) VALUES ({binds})", row)

    def seed_header(self, snapshot, **changes):
        row = {k: v for k, v in snapshot.items() if k != "now"} | changes
        self.db.execute("INSERT OR IGNORE INTO Document VALUES (?, 'Synthetic', 71, 'TOMBSTONED', 0, 0, 1)", (row["documentId"],))
        self.seed("ExportSnapshot", row)

    def rows(self, table):
        return self.db.execute(f"SELECT * FROM {table} ORDER BY 1").fetchall()

    def refill(self, table, column, value):
        self.db.execute(f"UPDATE {table} SET {column}=?", (value,))

    def assert_refused(self, method, args, table, scalar=False):
        before = self.rows(table)
        result = self.sql(method, args)
        self.assertEqual([(0,)] if scalar else 0, result.fetchall() if scalar else result.rowcount)
        self.assertEqual(before, self.rows(table))

    def test_commit_changes_only_moved_export_state_and_audit_and_refuses_replay(self):
        row = self.moved()
        for neighbor in (self.moved(ordinal=1, tx=21, artifact=31), self.moved(attempt=2, tx=22), self.moved(tx=23) | {"kind": "RENDER"}):
            self.phase.seed(neighbor)
        neighbors = self.phase.rows()
        self.phase.seed(row)
        args = self.commit_args(row)
        self.assertEqual(1, self.sql("commitMovedExportItem", args).rowcount)
        self.assertEqual([self.phase.values(row | dict(state="COMMITTED", updatedAt=0)), *neighbors], self.phase.rows())
        self.assert_refused("commitMovedExportItem", args | {"previousUpdatedAt": 0}, "FileTransaction")

    def test_commit_refuses_wrong_kind_in_isolation_and_wrong_state(self):
        row = self.moved()
        self.phase.seed(row | {"kind": "RENDER"})
        args = self.commit_args(row)
        self.assert_refused("commitMovedExportItem", args, "FileTransaction")
        self.refill("FileTransaction", "kind", "EXPORT")
        for state in ("INTENT", "COPYING", "SEALED", "moved", "COMMITTED", "CANCELED"):
            self.refill("FileTransaction", "state", state)
            self.assert_refused("commitMovedExportItem", args, "FileTransaction")
        self.refill("FileTransaction", "state", "MOVED")
        self.assertEqual(1, self.sql("commitMovedExportItem", args).rowcount)

    def test_commit_refuses_wrong_expected_tuple_and_malformed_equal_operands(self):
        row = self.moved()
        self.phase.seed(row)
        args = self.commit_args(row)
        for field, values in dict(transactionId=(uid(9), row["transactionId"].upper()),
                                  ownerKey=(expected_key(uid(12), 1), row["ownerId"].upper(), row["ownerId"] + "\0x"),
                                  attempt=(2, "3", 3.0), previousUpdatedAt=(39, "40", 40.0),
                                  updatedAt=(-1, "0", 0.0), stagingPath=(row["stagingPath"].upper(),),
                                  finalPath=(self.phase.expected(self.phase.plan())["finalPath"].replace(uid(13), uid(14)),),
                                  payload=(b"x" + row["payload"][1:],), seal=("a" * 64,),
                                  byteSha256=("9" * 64,), byteCount=(18,)).items():
            for value in values:
                with self.subTest(field=field, value=repr(value)[:60]):
                    self.assert_refused("commitMovedExportItem", args | {field: value}, "FileTransaction")
        for column, field, value in (
            ("errorCode", None, ""), ("attempt", "attempt", 3.5), ("updatedAt", "previousUpdatedAt", -1),
            ("updatedAt", "previousUpdatedAt", 40.5), ("stagingPath", "stagingPath", row["stagingPath"].encode()),
            ("finalPath", "finalPath", row["finalPath"] + "\0x"), ("sealSha256", "seal", "E" * 64),
            ("sealSha256", "seal", "e" * 63 + "\0"), ("expectedByteSha256", "byteSha256", b"b" * 64),
            ("expectedByteCount", "byteCount", 17.5), ("expectedByteCount", "byteCount", 0),
        ):
            with self.subTest(column=column):
                self.refill("FileTransaction", column, value)
                self.assert_refused("commitMovedExportItem", args, "FileTransaction")
                if field:
                    self.assert_refused("commitMovedExportItem", args | {field: value}, "FileTransaction")
                self.refill("FileTransaction", column, row[column])
        self.assertEqual(1, self.sql("commitMovedExportItem", args).rowcount)

    def test_commit_payload_comparison_is_bounded_by_its_lazy_case(self):
        row = self.moved()
        self.phase.seed(row)
        phases.witness_lazy_blob_comparison(self, self.db, self.phase.dao("commitMovedExportItem"), "payload", "payload",
                                            r"=\s*685", self.commit_args(row), (b"x" * 684, b"x" * 686, "x" * 685), 0, 1)

    def test_insert_requires_exact_bound_shape_and_only_inserts_fixed_ready_png(self):
        self.seed_header(self.snapshot())
        item = self.artifact(ordinal=99)
        for field, values in self.bad_artifact_inputs(item).items():
            for value in values:
                with self.subTest(field=field, value=repr(value)[:60]):
                    self.assert_refused("insertReadyPngExportArtifact", item | {field: value}, "ExportArtifact")
        self.assertEqual(1, self.sql("insertReadyPngExportArtifact", item).rowcount)
        self.assertEqual([(item["artifactId"], item["snapshotId"], 99, "image/png",
                           f"exports/{uid(13)}/{uid(11)}.bin", "b" * 64, 17, 1,
                           "ORDINARY_PNG_ENCODED_AND_RGB_V1", "READY", 0)], self.rows("ExportArtifact"))

    def bad_artifact_inputs(self, item):
        return dict(ordinal=(-1, 100, "99", 99.0), byteCount=(0, -1, "17", 17.0),
                    createdAt=(-1, "0", 0.0), byteSha256=("B" * 64, "b" * 63 + "\0", "b" * 65, b"b" * 64, "z" * 64),
                    **{k: (item[k].upper(), item[k] + "\0x", "-" + item[k][1:8] + "0" + item[k][9:],
                           item[k].encode(), None) for k in ("snapshotId", "artifactId")})

    def test_insert_aborts_each_unique_collision_even_with_malformed_occupants(self):
        self.seed_header(self.snapshot())
        sql = self.phase.dao("insertReadyPngExportArtifact")
        item = self.artifact()
        base = self.artifact_row(item, byteCount=b"bad", verification="BAD", state="BAD")
        for key, occupant in (("id", base | dict(ordinal=1, relativePath="malformed")),
                              ("ordinal", base | dict(artifactId=uid(20), relativePath="malformed")),
                              ("path", base | dict(artifactId=uid(21), ordinal=1))):
            with self.subTest(key=key):
                self.db.execute("SAVEPOINT occupied")
                self.seed("ExportArtifact", occupant)
                before = self.rows("ExportArtifact")
                with self.assertRaises(sqlite3.IntegrityError):
                    self.db.execute(sql, item)
                self.assertEqual(before, self.rows("ExportArtifact"))
                self.db.execute("ROLLBACK TO occupied")
                self.db.execute("RELEASE occupied")

    def test_postcheck_is_scalar_indexed_and_requires_every_exact_immutable_field(self):
        item = self.artifact(ordinal=99)
        self.seed_header(self.snapshot())
        self.seed_header(self.snapshot(14))
        row = self.artifact_row(item)
        self.seed("ExportArtifact", row)
        sql = self.phase.dao("readyPngExportArtifactMatches")
        details = [r[3] for r in self.db.execute("EXPLAIN QUERY PLAN " + sql, item)]
        self.assertTrue(any(r.startswith("SEARCH ExportArtifact USING INDEX ") for r in details), details)
        self.assertFalse(any("SCAN ExportArtifact" in r for r in details), details)
        self.assertEqual([(1,)], self.db.execute(sql, item).fetchall())
        for field, value in dict(artifactId=uid(12), snapshotId=uid(14), ordinal=98, byteSha256="a" * 64,
                                 byteCount=18, createdAt=1).items():
            with self.subTest(expected=field):
                self.assert_refused("readyPngExportArtifactMatches", item | {field: value}, "ExportArtifact", scalar=True)
        for field, values in self.bad_artifact_inputs(item).items():
            for value in values:
                with self.subTest(expected=field, value=repr(value)[:60]):
                    self.assert_refused("readyPngExportArtifactMatches", item | {field: value}, "ExportArtifact", scalar=True)
        for field, values in dict(artifactId=(item["artifactId"].upper(), item["artifactId"].encode()),
                                  snapshotId=(uid(14),), ordinal=(98, 99.5), mime=("image/PNG", b"image/png"),
                                  relativePath=(row["relativePath"] + "\0extra", row["relativePath"].encode()),
                                  byteSha256=("B" * 64, "b" * 64 + "\0extra", b"b" * 64), byteCount=(17.5, 18),
                                  pageCount=(2, 1.5), verification=("READBACK_VERIFIED", row["verification"].encode()),
                                  state=("ready", "PENDING"), createdAt=(1, b"0")).items():
            for value in values:
                with self.subTest(persisted=field, value=repr(value)[:60]):
                    self.refill("ExportArtifact", field, value)
                    self.assert_refused("readyPngExportArtifactMatches", item, "ExportArtifact", scalar=True)
                    self.refill("ExportArtifact", field, row[field])
        self.assertEqual([(1,)], self.db.execute(sql, item).fetchall())

    def test_snapshot_cas_checks_exact_header_and_live_retention_without_editing_neighbors(self):
        header = self.snapshot()
        self.seed_header(header)
        self.seed_header(self.snapshot(14))
        before = self.rows("ExportSnapshot")
        method = "markOrdinaryPngSnapshotPrivateReady"
        for field, values in dict(snapshotId=(uid(13).upper(), uid(13).encode(), uid(15), uid(14) + "\0x"),
                                  documentId=(uid(43), uid(42).upper(), uid(42).encode()),
                                  documentRevision=(28, "29", 29.0), snapshotSha256=("a" * 64, b"c" * 64),
                                  createdAt=(-1, 9, "8", 8.0), retainedUntil=(0, 100, "99", 99.0),
                                  now=(99, 100, -1, 98.0, "98"), settings=(b"different fixture bytes",)).items():
            for value in values:
                with self.subTest(expected=field, value=value):
                    self.assert_refused(method, header | {field: value}, "ExportSnapshot")
        # Change only the target row, keeping the neighboring frozen header identical.
        for field, values in dict(state=("RENDERING", "PRIVATE_READY", "CANCELED", "frozen", b"FROZEN"),
                                  documentRevision=(29.5, 0), createdAt=(8.5, -1), retainedUntil=(99.5, 0),
                                  snapshotSha256=("c" * 64 + "\0tail", "C" * 64)).items():
            for value in values:
                with self.subTest(persisted=field, value=value):
                    self.db.execute(f"UPDATE ExportSnapshot SET {field}=? WHERE snapshotId=?", (value, header["snapshotId"]))
                    self.assert_refused(method, header, "ExportSnapshot")
                    self.assert_refused(method, header | {field: value}, "ExportSnapshot")
                    self.db.execute(f"UPDATE ExportSnapshot SET {field}=? WHERE snapshotId=?", (header[field], header["snapshotId"]))
        self.assertEqual(1, self.sql(method, header).rowcount)
        self.assertEqual([before[0][:5] + ("PRIVATE_READY",) + before[0][6:], before[1]], self.rows("ExportSnapshot"))
        self.assert_refused(method, header, "ExportSnapshot")

    def test_snapshot_settings_comparison_lazily_bounds_both_operands(self):
        header = self.snapshot(settings=b"x" * 524_288)
        self.seed_header(header)
        phases.witness_lazy_blob_comparison(self, self.db, self.phase.dao("markOrdinaryPngSnapshotPrivateReady"),
                                            "settings", "settings", r"BETWEEN\s+1\s+AND\s+524288", header,
                                            (b"", b"x" * 524_289, "opaque fixture settings"), 0, 1)

    def finalize_fixture(self, header, *, late_failure=False):
        method = "markOrdinaryPngSnapshotPrivateReady"
        for ordinal in (0, 1):
            plan = self.phase.plan(ordinal=ordinal, tx=10 + ordinal * 10, artifact=11 + ordinal * 10)
            moved = self.phase.sealed(plan, state="MOVED")
            commit = self.commit_args(moved)
            self.assertEqual([(1,)], self.sql("exportItemIntentReadIsBounded", commit | {"expectedPayloadBytes": 685}).fetchall())
            self.assertEqual(1, self.sql("commitMovedExportItem", commit).rowcount)
            item = self.artifact(plan)
            self.assertEqual(1, self.sql("insertReadyPngExportArtifact", item).rowcount)
            self.assertEqual([(1,)], self.sql("readyPngExportArtifactMatches", item).fetchall())
        self.assertEqual([(uid(11),), (uid(21),)], self.sql("exportArtifactIdsForSnapshot", header | {"limit": 3}).fetchall())
        count = self.sql(method, header | ({"now": header["retainedUntil"]} if late_failure else {})).rowcount
        if late_failure:
            self.assertEqual(0, count)
            self.assertEqual([("COMMITTED",), ("COMMITTED",)], self.db.execute("SELECT state FROM FileTransaction ORDER BY ownerId").fetchall())
            self.assertEqual(2, len(self.rows("ExportArtifact")))
            raise FixtureRefusal()
        self.assertEqual(1, count)

    def test_two_item_sql_transaction_and_late_refusal_rollback(self):
        header = self.snapshot()
        self.seed_header(header)
        self.seed_header(self.snapshot(14, state="ASSEMBLING"))
        self.phase.seed(self.moved())
        self.phase.seed(self.moved(ordinal=1, tx=20, artifact=21))
        self.db.execute("INSERT INTO Job VALUES (?, 'EXPORT_ASSEMBLY', ?, ?, ?, NULL, ?, 'RUNNING', 3, ?, 100, 3, NULL, NULL, 1, 2)",
                        (uid(12), "inert-job-key", "d" * 64, uid(13), b"inert job", uid(14)))
        self.db.commit()
        tables = ("FileTransaction", "ExportArtifact", "ExportSnapshot", "Job", "Document")
        before = {table: self.rows(table) for table in tables}
        with self.assertRaises(FixtureRefusal):
            with self.db:
                self.db.execute("BEGIN")
                self.finalize_fixture(header, late_failure=True)
        self.assertEqual(before, {table: self.rows(table) for table in tables})
        with self.db:
            self.db.execute("BEGIN")
            self.finalize_fixture(header)
        self.assertEqual([("COMMITTED",), ("COMMITTED",)], self.db.execute("SELECT state FROM FileTransaction ORDER BY ownerId").fetchall())
        self.assertEqual([("PRIVATE_READY",), ("ASSEMBLING",)], self.db.execute("SELECT state FROM ExportSnapshot ORDER BY snapshotId").fetchall())
        self.assertEqual(before["Job"], self.rows("Job"))
        self.assertEqual(before["Document"], self.rows("Document"))


class FixtureRefusal(Exception):
    """The fixture owner vetoes a late zero-row header CAS."""
