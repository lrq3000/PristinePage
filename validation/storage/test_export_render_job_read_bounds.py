"""Execute the render reader's actual SQL on exported Room v1 DDL; refused bodies never cross the SQLite boundary."""

from hashlib import sha256
import re
import unittest
from uuid import UUID

from test_export_job_read_bounds import DAO
import test_room_schema


def uuid(number):
    return str(UUID(int=number))


class ExportRenderJobReadBoundsTest(unittest.TestCase):
    def setUp(self):
        self.guard_sql = self.query_sql("renderJobReadIsBounded")
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()
        self.job = uuid(1)
        self.other = uuid(2)
        # Opaque, distinct, same-size SQL witnesses; the Kotlin suite authenticates real v1 bytes.
        self.payload = b"A" * 729
        self.other_payload = b"B" * len(self.payload)
        self.insert_job(self.job, self.payload)
        self.insert_job(self.other, self.other_payload)

    def query_sql(self, method):
        annotation = re.compile(
            r'@Query\s*\(\s*(?:"""((?:(?!""").)*)"""|"([^"\\\r\n]*)")\s*\)\s*'
            r'(?:(?:abstract|override|internal|suspend|public)\s+)*fun\s+'
            rf'{re.escape(method)}\s*\(',
            re.S,
        )
        matches = annotation.findall(DAO.read_text(encoding="utf-8"))
        self.assertEqual(1, len(matches), f"Expected one render-read @Query for {method} (found {len(matches)})")
        raw, quoted = matches[0]
        return raw or quoted

    @staticmethod
    def identity(job, payload):
        digest = sha256(payload).hexdigest()
        return (job, "PAGE_RENDER", f"page-render-v1:{digest}", digest, payload)

    def insert_job(self, job, payload):
        self.db.execute(
            "INSERT INTO Job (jobId,kind,idempotenceKey,canonicalInputSha256,input,state,attempt,leaseGeneration,"
            " createdAt,updatedAt,checkpoint,leaseOwner,errorCode)"
            " VALUES (?,?,?,?,?,'CANCELED',3,4,1,2,zeroblob(8192),x'626c6f62','CANCELED')",
            self.identity(job, payload),
        )

    def gate(self, job=None, extent=None):
        # The CTE returns flags only, even if a bad edit projects an unbounded value instead of a scalar.
        sql = (f"WITH admitted(flag) AS ({self.guard_sql}) "
               "SELECT CASE WHEN typeof(flag)='integer' AND flag IN (0,1) THEN flag ELSE -1 END FROM admitted")
        return self.db.execute(sql, {
            "jobId": job if job is not None else self.job,
            "expectedInputBytes": extent if extent is not None else len(self.payload),
        }).fetchall()

    def set_job(self, column, value):
        self.db.execute(f"UPDATE Job SET {column}=? WHERE jobId=?", (value, self.job))

    def test_two_independently_admitted_rows_project_only_their_exact_identity(self):
        for job, payload in ((self.job, self.payload), (self.other, self.other_payload)):
            with self.subTest(job=job):
                self.assertEqual([(1,)], self.gate(job))
                result = self.db.execute(self.query_sql("renderJobIdentity"), {"jobId": job})
                self.assertEqual(("jobId", "kind", "idempotenceKey", "canonicalInputSha256", "input"),
                                 tuple(column[0] for column in result.description))
                self.assertEqual([self.identity(job, payload)], result.fetchall())
        self.assertEqual([], self.gate(uuid(99)))

    def test_requires_actual_blob_of_exact_nonzero_expected_extent(self):
        for name, stored, expected in (
            ("same extent TEXT", self.payload.decode("ascii"), len(self.payload)),
            ("one byte short", self.payload[:-1], len(self.payload)),
            ("one byte long", self.payload + b"\0", len(self.payload)),
            ("other large BLOB", b"X" * 524_288, len(self.payload)),
            ("zero extent requested and stored", b"", 0),
        ):
            with self.subTest(name=name):
                self.set_job("input", stored)
                self.assertEqual([(0,)], self.gate(extent=expected))
        # A nonzero caller-supplied exact extent is not the assembly's cap.
        self.set_job("input", b"Y" * 4145)
        self.assertEqual([(1,)], self.gate(extent=4145))
        self.assertEqual([(0,)], self.gate(extent=4144))

    def test_projected_text_requires_actual_text_and_rejects_nul_at_same_or_larger_extent(self):
        fields = dict(zip(("kind", "idempotenceKey", "canonicalInputSha256"), self.identity(self.job, self.payload)[1:4]))
        for column, valid in fields.items():
            for name, stored in (
                ("same extent BLOB", valid.encode("ascii")),
                ("NUL with suffix", valid + "\0" + "x" * 8192),
                ("NUL at same byte extent", valid[:-2] + "\0x"),
                ("multibyte at same byte extent", valid[:-2] + "é"),
                ("oversized plain TEXT", valid + "x"),
            ):
                with self.subTest(column=column, name=name):
                    self.set_job(column, stored)
                    self.assertEqual([(0,)], self.gate())
            self.set_job(column, valid)
        self.assertEqual([(1,)], self.gate())

    def test_raw_guard_preserves_render_kind_and_key_version(self):
        self.set_job("kind", "OTHER_CLASS")
        self.assertEqual([(0,)], self.gate())
        self.set_job("kind", "PAGE_RENDER")
        self.set_job("idempotenceKey", "page-render-v2:" + sha256(self.payload).hexdigest())
        self.assertEqual([(0,)], self.gate())

    def test_snapshot_and_ordinal_are_raw_null(self):
        self.db.execute("INSERT INTO Document VALUES (?, 'Fixture', 1, 'ACTIVE', 0, 0, NULL)", (uuid(10),))
        self.db.execute("INSERT INTO ExportSnapshot VALUES (?, ?, 1, ?, x'01', 'FROZEN', 1, 100)", (uuid(11), uuid(10), "a" * 64))
        self.set_job("snapshotId", uuid(11))
        self.assertEqual([(0,)], self.gate())
        self.set_job("snapshotId", None)
        for value in (0, 1 << 32, b"0"):
            with self.subTest(ordinal=value):
                self.set_job("pageOrdinal", value)
                self.assertEqual([(0,)], self.gate())

    def pin(self, source, owner_type="JOB", owner_id=None):
        self.db.execute("INSERT OR IGNORE INTO SourceBlob VALUES (?, ?, 4, 'image/png', 3, 2, ?, 'READY', 1, ?, 0)",
                        (uuid(20), "a" * 64, f"sources/{uuid(20)}/original.bin", uuid(21)))
        count = self.db.execute("SELECT count(*) FROM Source").fetchone()[0]
        self.db.execute("INSERT INTO Source VALUES (?, ?, ?, 3, 2, 1, ?, NULL, 1, 'READY', 0)",
                        (source, uuid(20), "b" * 64, f"conversion-{count}"))
        self.db.execute("INSERT INTO SourceReference VALUES (?, ?, ?, ?)",
                        (owner_type, self.job if owner_id is None else owner_id, source, b"unprojected timestamp"))

    def pins(self, job=None):
        # Preserve exact selected IDs/sentinels, but never transfer accidentally unbounded output from a regressed SQL annotation.
        sql = (f"WITH pins(id) AS ({self.query_sql('boundedRenderJobPinSourceIds')}) "
               "SELECT CASE WHEN typeof(id)='text' AND length(CAST(id AS BLOB))<=36 THEN id ELSE NULL END FROM pins")
        return [row[0] for row in self.db.execute(sql, {"jobId": job if job is not None else self.job})]

    def test_pin_lookup_keeps_invalid_present_and_caps_witnesses_at_two(self):
        self.pin(uuid(3), owner_type="EXPORT_PAGE")
        self.pin(uuid(4), owner_id=self.other)
        self.assertEqual([], self.pins())
        self.assertEqual([uuid(4)], self.pins(self.other))
        self.pin(uuid(5))
        self.assertEqual([uuid(5)], self.pins())
        self.pin(b"A" * 36)
        self.assertCountEqual([uuid(5), ""], self.pins())
        self.pin(uuid(6))
        self.assertEqual(2, len(self.pins()))

    def test_corrupt_single_pin_is_not_missing_and_never_projects_the_original_value(self):
        for source in (b"A" * 36, uuid(7)[:-2] + "\0x", uuid(8) + "\0" + "x" * 8192, "x" * 8192):
            with self.subTest(source_type=type(source).__name__, extent=len(source)):
                # Keep FKs enabled: each malformed pin has an equally malformed parent identity.
                self.db.execute("SAVEPOINT pin_fixture")
                try:
                    self.pin(source)
                    self.assertEqual([""], self.pins())
                finally:
                    self.db.execute("ROLLBACK TO pin_fixture")
                    self.db.execute("RELEASE pin_fixture")


if __name__ == "__main__":
    unittest.main(verbosity=2)
