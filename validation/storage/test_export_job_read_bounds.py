"""Exercise actual assembly DAO SQL on exported Room DDL, without materializing refused rows or testing lease state."""

from hashlib import sha256
from pathlib import Path
import re
from struct import pack
import unittest
from uuid import UUID

import test_room_schema


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt"
GUARD_METHOD = "assemblyJobReadIsBounded"


class ExportJobReadBoundsTest(unittest.TestCase):
    def setUp(self):
        self.guard_sql = self.query_sql(GUARD_METHOD)
        fixture = test_room_schema.RoomSchemaTest()
        self.addCleanup(fixture.doCleanups)
        self.db = fixture.open_schema()
        document, self.snapshot, self.job, self.missing = (str(UUID(int=i)) for i in range(1, 5))
        self.snapshot_hash = "c" * 64
        self.db.execute("INSERT INTO Document VALUES (?, 'Synthetic', 1, 'ACTIVE', 0, 0, NULL)", (document,))
        self.db.execute(
            "INSERT INTO ExportSnapshot VALUES (?, ?, 1, ?, ?, 'FROZEN', 1, 1000)",
            (self.snapshot, document, self.snapshot_hash, b"fixture FK parent only"),
        )
        payload = self.assembly_fixture(1)
        self.text_fields = {
            "kind": "EXPORT_ASSEMBLY",
            "idempotenceKey": f"export-assembly-v1/{self.snapshot}",
            "canonicalInputSha256": sha256(payload).hexdigest(),
            "snapshotId": self.snapshot,
        }
        self.db.execute(
            "INSERT INTO Job (jobId, kind, idempotenceKey, canonicalInputSha256, snapshotId, pageOrdinal, input,"
            " state, attempt, leaseGeneration, createdAt, updatedAt)"
            " VALUES (?, ?, ?, ?, ?, NULL, ?, 'PENDING', 0, 0, 1, 1)",
            (self.job, *self.text_fields.values(), payload),
        )

    def query_sql(self, method):
        annotation = re.compile(
            r'@Query\s*\(\s*(?:"""((?:(?!""").)*)"""|"([^"\\\r\n]*)")\s*\)\s*'
            r'(?:(?:abstract|override|internal|suspend|public)\s+)*fun\s+'
            rf'{re.escape(method)}\s*\(',
            re.S,
        )
        matches = [
            raw or quoted
            for raw, quoted in annotation.findall(DAO.read_text(encoding="utf-8"))
        ]
        self.assertEqual(
            1, len(matches), f"Expected one assembly-job @Query for {method} (found {len(matches)})"
        )
        return matches[0].strip().removesuffix(";")

    def assembly_fixture(self, count):
        # Independent fixture framing: tag + fixed ASCII fields + big-endian count + fixed ASCII job references. The surrounding snapshot is not an executable export.
        def field(text):
            value = text.encode("ascii")
            return pack(">I", len(value)) + value

        refs = b"".join(field(str(UUID(int=100 + i))) for i in range(count))
        return b"pristinepage-export-assembly-v1\0" + field(self.snapshot) + field(self.snapshot_hash) + pack(">I", count) + refs

    def gate(self, job=None):
        # Run the annotation unchanged inside a one-column CTE. Only flags cross into Python, even if a bad edit returns a payload instead of the scalar; -1 then fails the test.
        sql = (
            f"WITH job_guard(flag) AS ({self.guard_sql}) "
            "SELECT CASE WHEN typeof(flag)='integer' AND flag IN (0, 1) THEN flag ELSE -1 END FROM job_guard"
        )
        key = self.job if job is None else job
        return self.db.execute(sql, {"jobId": key}).fetchall()

    def set_input(self, value):
        raw = value.encode("ascii") if isinstance(value, str) else value
        self.db.execute(
            "UPDATE Job SET input=?, canonicalInputSha256=? WHERE jobId=?",
            (value, sha256(raw).hexdigest(), self.job),
        )

    def set_text_field(self, field, value):
        if field == "snapshotId" and value is not None and value != self.snapshot:
            # Make the same malformed key exist in the parent so the real FK remains enabled.
            self.db.execute(
                "INSERT INTO ExportSnapshot SELECT ?, documentId, documentRevision, snapshotSha256, settings,"
                " state, createdAt, retainedUntil FROM ExportSnapshot WHERE snapshotId=?",
                (value, self.snapshot),
            )
        self.db.execute(f"UPDATE Job SET {field}=? WHERE jobId=?", (value, self.job))

    def test_distinguishes_missing_job_from_malformed_present_job(self):
        self.assertEqual([(1,)], self.gate())
        self.assertEqual([], self.gate(self.missing))
        self.set_input(b"")
        self.assertEqual([(0,)], self.gate())

    def test_v1_blob_extent_includes_both_endpoints_and_rejects_adjacent_sizes(self):
        low, high = self.assembly_fixture(1), self.assembly_fixture(100)
        for size, value, flag in ((184, low, 1), (4144, high, 1), (183, low[:-1], 0), (4145, high + b"\0", 0)):
            with self.subTest(bytes=size):
                self.assertEqual(size, len(value))
                self.set_input(value)
                self.assertEqual([(flag,)], self.gate())

    def test_text_of_admitted_extent_is_not_an_input_blob(self):
        self.set_input("x" * 184)
        self.assertEqual(
            ("text", 184),
            self.db.execute("SELECT typeof(input),length(CAST(input AS BLOB)) FROM Job WHERE jobId=?", (self.job,)).fetchone(),
        )
        self.assertEqual([(0,)], self.gate())

    def test_projected_text_rejects_blob_class_and_nul_hidden_bytes(self):
        for field, original in self.text_fields.items():
            for case, kind, value, chars, size in (
                ("blob", "blob", original.encode("ascii"), len(original), len(original)),
                ("nul_suffix", "text", original + "\0" + "x" * 256, len(original), len(original) + 257),
                ("nul_same_bytes", "text", original[:-2] + "\0x", len(original) - 2, len(original)),
            ):
                with self.subTest(field=field, case=case):
                    self.set_text_field(field, value)
                    try:
                        self.assertEqual(
                            (kind, chars, size),
                            self.db.execute(
                                f"SELECT typeof({field}),length({field}),length(CAST({field} AS BLOB)) FROM Job WHERE jobId=?",
                                (self.job,),
                            ).fetchone(),
                        )
                        self.assertEqual([(0,)], self.gate())
                    finally:
                        self.set_text_field(field, original)

    def test_missing_raw_snapshot_binding_is_not_a_bounded_row(self):
        self.set_text_field("snapshotId", None)
        self.assertEqual([(0,)], self.gate())

    def test_raw_ordinal_cannot_narrow_to_an_in_range_int(self):
        raw_ordinal = 1 << 32  # A nonzero SQLite INTEGER whose low 32 bits look like ordinal zero.
        self.db.execute("UPDATE Job SET pageOrdinal=? WHERE jobId=?", (raw_ordinal, self.job))
        self.assertEqual(
            ("integer", raw_ordinal),
            self.db.execute("SELECT typeof(pageOrdinal),pageOrdinal FROM Job WHERE jobId=?", (self.job,)).fetchone(),
        )
        self.assertEqual([(0,)], self.gate())

    def test_admitted_projection_has_only_identity_fields_and_ignores_job_lifecycle(self):
        self.db.execute("UPDATE Job SET state='CANCELED', checkpoint=zeroblob(8192), leaseOwner=?, errorCode='CANCELED'", (b"not projected",))
        self.assertEqual([(1,)], self.gate())
        projected = self.db.execute(self.query_sql("assemblyJobIdentity"), {"jobId": self.job})
        self.assertEqual(
            ("jobId", "kind", "idempotenceKey", "canonicalInputSha256", "snapshotId", "input"),
            tuple(column[0] for column in projected.description),
        )
        self.assertEqual([(self.job, *self.text_fields.values(), self.assembly_fixture(1))], projected.fetchall())


if __name__ == "__main__":
    unittest.main(verbosity=2)
