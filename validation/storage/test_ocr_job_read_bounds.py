"""Exercise the OCR-only raw job admission against the exported Job table."""

from hashlib import sha256
from pathlib import Path
import re
import unittest
from uuid import UUID

import test_room_schema


DAO = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db/JobLeaseDao.kt"


class OcrJobReadBoundsTest(unittest.TestCase):
    def setUp(self):
        fixture = test_room_schema.RoomSchemaTest()
        self.addCleanup(fixture.doCleanups)
        self.db = fixture.open_schema()
        self.job, self.missing = (str(UUID(int=i)) for i in (11, 12))
        self.key = f"ocr-page-v2/{UUID(int=13)}/1"
        self.body = b"F" * 429  # Kotlin separately checks the canonical payload.
        self.digest = sha256(self.body).hexdigest()
        self.db.execute(
            """INSERT INTO Job (jobId,kind,idempotenceKey,canonicalInputSha256,input,state,
               attempt,leaseGeneration,createdAt,updatedAt) VALUES (?,'PAGE_OCR',?,?,?,'ADMITTED',0,0,10,10)""",
            (self.job, self.key, self.digest, self.body),
        )
        queries = re.findall(
            r'@Query\("""((?:(?!""").)*)"""\)\s*internal abstract fun ocrJobReadIsBounded\(',
            DAO.read_text(encoding="utf-8"), re.S,
        )
        self.assertEqual(1, len(queries), "Missing OCR-only scalar admission")
        self.sql = queries[0]

    def gate(self, job=None, extent=None):
        # Even if the query regresses to a wide value, do not materialize that value on the host.
        return self.db.execute(
            f"WITH admitted(flag) AS ({self.sql}) "
            "SELECT CASE WHEN typeof(flag)='integer' AND flag IN (0,1) THEN flag ELSE -1 END FROM admitted",
            dict(id=job or self.job, expectedKey=self.key, expectedDigest=self.digest,
                 expectedInputBytes=len(self.body) if extent is None else extent),
        ).fetchall()

    def alter(self, column, value):
        self.db.execute(f"UPDATE Job SET {column}=? WHERE jobId=?", (value, self.job))

    def test_exact_row_and_missing_row_are_distinct(self):
        self.assertEqual([(1,)], self.gate())
        self.assertEqual([], self.gate(job=self.missing))
        for column, value in (
            ("kind", "PAGE_RENDER"), ("kind", b"PAGE_OCR"), ("canonicalInputSha256", b"a" * 64),
            ("canonicalInputSha256", "a" * 64),
            ("idempotenceKey", self.key.replace("-v2/", "-v1/")),
            ("idempotenceKey", self.key + "\0hidden"), ("pageOrdinal", 1 << 32),
        ):
            with self.subTest(column=column, value=value):
                with self.db:
                    self.db.execute("SAVEPOINT fixture")
                    self.alter(column, value)
                    self.assertEqual([(0,)], self.gate())
                    self.db.execute("ROLLBACK TO fixture")
                    self.db.execute("RELEASE fixture")

    def test_input_requires_exact_extent_and_actual_blob_class(self):
        for body, size, expected in (
            (self.body.decode("ascii"), len(self.body), 0),
            (self.body[:-1], len(self.body), 0), (self.body + b"X", len(self.body), 0),
            (b"", 0, 0), (b"X" * 3532, 3532, 1), (b"X" * 3533, 3533, 0),
        ):
            with self.subTest(body_type=type(body), size=size, expected=expected):
                self.alter("input", body)
                self.assertEqual([(expected,)], self.gate(extent=size))

    def test_scalar_admission_covers_all_materialized_lease_and_audit_fields(self):
        for column, value, baseline in (
            ("state", "ADMITTED\0hidden", "ADMITTED"), ("attempt", 4, 0), ("attempt", b"0", 0),
            ("leaseGeneration", -1, 0), ("leaseOwner", b"X" * 36, None),
            ("leaseOwner", self.job[:-2] + "\0x", None), ("leaseUntil", -1, None),
            ("checkpoint", b"X", None), ("errorCode", "ERROR\0hidden", None),
            ("errorCode", "file/private.jpg", None), ("errorCode", "X" * 65, None),
            ("createdAt", b"10", 10), ("updatedAt", "invalid", 10),
        ):
            with self.subTest(column=column, value=value):
                self.alter(column, value)
                self.assertEqual([(0,)], self.gate())
                self.alter(column, baseline)
        self.assertEqual([(1,)], self.gate())

    def test_recovers_bounded_running_and_terminal_fields_without_discarding_error(self):
        self.db.execute(
            "UPDATE Job SET state='RUNNING', attempt=3, leaseOwner=?, leaseUntil=80, leaseGeneration=3",
            (self.job,),
        )
        self.assertEqual([(1,)], self.gate())
        self.db.execute("UPDATE Job SET state='FAILED', leaseOwner=NULL, leaseUntil=NULL, errorCode='ENGINE_FAILED'")
        self.assertEqual([(1,)], self.gate())

    def test_v2_uses_ocr_states_not_alternate_generic_job_phases(self):
        for state in ("PENDING", "ADMITTED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELED"):
            with self.subTest(state=state):
                self.alter("state", state)
                self.assertEqual([(1,)], self.gate())
        for state in ("WAITING_RESOURCE", "RETRYABLE", "INTERRUPTED", "CHECKPOINTED"):
            with self.subTest(state=state):
                self.alter("state", state)
                self.assertEqual([(0,)], self.gate())
