"""Run the fresh assembly scalar guards/CAS and existing lease CAS on exported Room DDL."""

from hashlib import sha256
from pathlib import Path
import re
from struct import pack
import unittest
from uuid import UUID

import test_room_schema


DB_SOURCES = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db"


class FreshAssemblyAdmissionTest(unittest.TestCase):
    def setUp(self):
        fixture = test_room_schema.RoomSchemaTest()
        self.db = fixture.open_schema()
        self.addCleanup(fixture.doCleanups)
        self.doc, self.snapshot, self.job, self.elsewhere, self.owner, first, second = (
            str(UUID(int=i)) for i in range(1, 8)
        )
        self.key = f"export-assembly-v1/{self.snapshot}"
        self.db.execute("INSERT INTO Document VALUES (?, 'Synthetic', 1, 'ACTIVE', 0, 0, NULL)", (self.doc,))
        for snapshot in (self.snapshot, self.elsewhere):
            self.db.execute(
                "INSERT INTO ExportSnapshot VALUES (?, ?, 1, ?, ?, 'FROZEN', 1, 1000)",
                (snapshot, self.doc, "a" * 64, b"settings fixture; not a typed snapshot"),
            )

        def field(s):
            b = s.encode("ascii")
            return pack(">I", len(b)) + b

        header = b"pristinepage-export-assembly-v1\0" + field(self.snapshot) + field("a" * 64) + pack(">I", 2)
        self.input = header + field(first) + field(second)
        self.reordered = header + field(second) + field(first)
        self.args = dict(jobId=self.job, snapshotId=self.snapshot, key=self.key, digest=sha256(self.input).hexdigest(), input=self.input)
        self.db.execute(
            "INSERT INTO Job (jobId, kind, idempotenceKey, canonicalInputSha256, snapshotId, input, state, attempt,"
            " leaseGeneration, createdAt, updatedAt) VALUES (:jobId, 'EXPORT_ASSEMBLY', :key, :digest, :snapshotId, :input, 'PENDING', 0, 0, 1, 1)",
            self.args,
        )

    def sql(self, method, dao="ExportSnapshotDao"):
        annotation = r'@Query\(\s*(?:"""((?:(?!""").)*)"""|"([^"\r\n]*)")\s*\)\s*(?:(?:abstract|override|internal)\s+)*fun\s+' + re.escape(method) + r"\("
        matches = re.findall(annotation, (DB_SOURCES / f"{dao}.kt").read_text(encoding="utf-8"), re.S)
        self.assertEqual(1, len(matches), f"Expected actual DAO SQL for {method}")
        return matches[0][0] or matches[0][1]

    def flag(self, method, **params):
        sql = self.sql(method)
        return self.db.execute(
            f"WITH guard(flag) AS ({sql}) SELECT CASE WHEN typeof(flag)='integer' AND flag IN (0,1) THEN flag ELSE -1 END FROM guard",
            params,
        ).fetchall()

    def phase(self, state="PENDING", attempt=0, owner=None, until=None):
        return self.flag("freshAssemblyPhaseMatches", jobId=self.job, state=state, attempt=attempt, owner=owner, until=until)

    def admission(self):
        return self.db.execute(self.sql("admitPendingAssembly"), self.args | {"updatedAt": 100, "previousUpdatedAt": 1}).rowcount

    def test_exact_blob_scalar_distinguishes_missing_and_present_mismatch(self):
        self.assertEqual([(1,)], self.flag("freshAssemblyInputMatches", **self.args))
        self.assertEqual([], self.flag("freshAssemblyInputMatches", **(self.args | {"jobId": self.owner})))
        for field, value in (
            ("kind", "EXPORT_ASSEMBLY\0"), ("snapshotId", self.elsewhere), ("pageOrdinal", 0),
            ("idempotenceKey", self.key + "\0"), ("canonicalInputSha256", "b" * 64),
            ("input", self.input.decode("ascii")), ("input", self.input[:-1]), ("input", self.reordered),
        ):
            with self.subTest(field=field, value_type=type(value).__name__):
                self.db.execute("SAVEPOINT candidate")
                self.db.execute(f"UPDATE Job SET {field}=? WHERE jobId=?", (value, self.job))
                try:
                    self.assertEqual([(0,)], self.flag("freshAssemblyInputMatches", **self.args))
                    self.assertEqual(0, self.admission())
                finally:
                    self.db.execute("ROLLBACK TO candidate")
                    self.db.execute("RELEASE candidate")
        # A recomputed digest on a coherent same-size reorder still cannot replace admitted bytes.
        self.db.execute("UPDATE Job SET input=?,canonicalInputSha256=?", (self.reordered, sha256(self.reordered).hexdigest()))
        self.assertEqual([(0,)], self.flag("freshAssemblyInputMatches", **self.args))
        self.assertEqual(0, self.admission())

    def test_phase_scalar_refuses_prior_or_malformed_attempt_fields_before_full_read(self):
        self.assertEqual([(1,)], self.phase())
        self.assertEqual([(1,)], self.flag("renderJobLeaseReadIsBounded", jobId=self.job))
        for field, value in (
            ("state", "PENDING\0x"), ("state", "ADMITTED"), ("attempt", 1), ("attempt", 0.5),
            ("leaseGeneration", 1), ("leaseGeneration", -1), ("leaseOwner", self.owner),
            ("leaseUntil", 0), ("checkpoint", b"x"), ("errorCode", "old_failure"),
        ):
            with self.subTest(field=field, value=value):
                self.db.execute("SAVEPOINT candidate")
                self.db.execute(f"UPDATE Job SET {field}=? WHERE jobId=?", (value, self.job))
                try:
                    self.assertEqual([(0,)], self.phase())
                    self.assertEqual(0, self.admission())
                finally:
                    self.db.execute("ROLLBACK TO candidate")
                    self.db.execute("RELEASE candidate")
        for field in ("createdAt", "updatedAt"):
            with self.subTest(field=field):
                self.db.execute(f"UPDATE Job SET {field}=1.5")
                self.assertEqual([(0,)], self.flag("renderJobLeaseReadIsBounded", jobId=self.job))
                self.db.execute(f"UPDATE Job SET {field}=1")

    def test_exact_admission_changes_only_state_and_audit_then_existing_lease_cas_moves_to_one(self):
        self.db.execute("UPDATE Job SET updatedAt=2")
        self.assertEqual(0, self.admission())
        self.db.execute("UPDATE Job SET updatedAt=1")
        self.assertEqual(1, self.admission())
        self.assertEqual(0, self.admission())
        self.assertEqual([(1,)], self.phase("ADMITTED"))
        self.assertEqual([(1,)], self.flag("freshAssemblyInputMatches", **self.args))
        self.assertEqual((1, 100), self.db.execute("SELECT createdAt,updatedAt FROM Job").fetchone())
        lease = self.sql("updateLeaseState", "JobLeaseDao")
        fields = dict(id=self.job, state="RUNNING", attempt=1, owner=self.owner, until=27, generation=1,
                      checkpoint=None, error=None, updated=1100, expectedGeneration=0)
        self.assertEqual(1, self.db.execute(lease, fields).rowcount)
        self.assertEqual(0, self.db.execute(lease, fields).rowcount)
        self.assertEqual([(1,)], self.phase("RUNNING", 1, self.owner, 27))
        self.assertEqual([(0,)], self.phase("RUNNING", 1, self.owner, 28))
        self.assertEqual([(1,)], self.flag("freshAssemblyInputMatches", **self.args))
        self.assertEqual(("FROZEN", 1000), self.db.execute("SELECT state,retainedUntil FROM ExportSnapshot WHERE snapshotId=?", (self.snapshot,)).fetchone())

    def test_exact_key_lookup_uses_unique_index_and_never_treats_invalid_id_as_absence(self):
        sql = self.sql("renderJobIdByKey")
        self.assertEqual([(self.job,)], self.db.execute(sql, {"key": self.key}).fetchall())
        plan = self.db.execute("EXPLAIN QUERY PLAN " + sql, {"key": self.key}).fetchall()
        self.assertIn("index_Job_idempotenceKey", " ".join(row[-1] for row in plan))
        for key in (self.key.upper(), self.key + "\0"):
            self.assertEqual([], self.db.execute(sql, {"key": key}).fetchall())
        self.db.execute("UPDATE Job SET jobId=?", (self.job + "\0",))
        self.assertEqual([("",)], self.db.execute(sql, {"key": self.key}).fetchall())

    def test_oversize_blob_refuses_at_separate_raw_guard(self):
        blob = b"x" * 4145
        self.db.execute("UPDATE Job SET input=?,canonicalInputSha256=?", (blob, sha256(blob).hexdigest()))
        self.assertEqual(4145, self.db.execute("SELECT length(input) FROM Job").fetchone()[0])
        self.assertEqual([(0,)], self.flag("assemblyJobReadIsBounded", jobId=self.job))
        # The actual equality query would accept matching bytes on its own; the consuming call must guard first.

    def test_successful_annotated_writes_can_be_rolled_back_without_changing_prior_identity(self):
        before = self.db.execute("SELECT * FROM Job").fetchone()
        self.db.execute("SAVEPOINT admission")
        self.assertEqual(1, self.admission())
        self.assertEqual([(1,)], self.phase("ADMITTED"))
        self.db.execute("ROLLBACK TO admission")
        self.db.execute("RELEASE admission")
        self.assertEqual(before, self.db.execute("SELECT * FROM Job").fetchone())
        self.assertEqual(1, self.admission())
        admitted = self.db.execute("SELECT * FROM Job").fetchone()
        lease = self.sql("updateLeaseState", "JobLeaseDao")
        self.db.execute("SAVEPOINT acquire")
        self.assertEqual(1, self.db.execute(lease, dict(id=self.job, state="RUNNING", attempt=1, owner=self.owner,
            until=27, generation=1, checkpoint=None, error=None, updated=1100, expectedGeneration=0)).rowcount)
        self.assertEqual([(1,)], self.phase("RUNNING", 1, self.owner, 27))
        self.db.execute("ROLLBACK TO acquire")
        self.db.execute("RELEASE acquire")
        self.assertEqual(admitted, self.db.execute("SELECT * FROM Job").fetchone())
