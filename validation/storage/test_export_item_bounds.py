"""T-11 ordered PNG artifact guards against Room's exported v1 DDL and actual DAO SQL."""

from pathlib import Path
import re
import sqlite3
import unittest
from uuid import UUID

import test_room_schema


DAO = (Path(__file__).resolve().parents[2] /
       "data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt")
ATTEMPT = 3


def uid(n):
    return str(UUID(int=n))


def expected_key(assembly, ordinal):
    # Independent literal wire expectation. Kotlin, not the caller, must derive this key from the frozen ordinal.
    return f"export-item-v1:{assembly}:{ordinal}"


class ExportItemSqlTest(unittest.TestCase):
    def setUp(self):
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()
        self.assembly, self.snapshot = uid(10), uid(11)
        self.payload = b"bounded synthetic intent"

    def item(self, n, ordinal, *, key=None, kind="EXPORT", attempt=ATTEMPT, **changes):
        tx, artifact = uid(n), uid(n + 100)
        values = dict(transactionId=tx, kind=kind,
                      ownerId=expected_key(self.assembly, ordinal) if key is None else key,
                      state="MOVED", stagingPath=f"staging/{tx}/derived.part",
                      finalPath=f"exports/{self.snapshot}/{artifact}.bin", sealSha256="a" * 64,
                      expectedByteSha256="b" * 64, expectedByteCount=17,
                      payload=self.payload, attempt=attempt, errorCode=None, updatedAt=2) | changes
        columns = tuple(values)
        self.db.execute("INSERT INTO FileTransaction (" + ",".join(columns) + ") VALUES (" +
                        ",".join(f":{key}" for key in columns) + ")", values)

    def dao(self, method):
        matches = re.findall(r'@Query\s*\(\s*(?:"""((?:(?!""").)*)"""|"([^"\\\r\n]*)")\s*\)\s*'
                             rf'(?:(?:abstract|override|internal|suspend|public)\s+)*fun\s+{method}\(',
                             DAO.read_text(encoding="utf-8"), re.S)
        self.assertEqual(1, len(matches), f"Missing actual DAO statement for {method}")
        return next(part for part in matches[0] if part)

    def args(self, ordinal, attempt=ATTEMPT):
        return dict(ownerKey=expected_key(self.assembly, ordinal), attempt=attempt,
                    expectedPayloadBytes=len(self.payload))

    def guard(self, ordinal, attempt=ATTEMPT):
        sql = self.dao("exportItemIntentReadIsBounded")
        # No tested query may deliver a malformed payload into Python as if it were a bounded scalar verdict.
        return self.db.execute(f"""WITH checked(flag) AS ({sql})
            SELECT CASE WHEN typeof(flag)='integer' AND flag IN (0,1) THEN flag ELSE -1 END
            FROM checked""", self.args(ordinal, attempt)).fetchall()

    def test_v1_unique_key_is_per_kind_item_and_real_attempt(self):
        self.item(1, 0)
        self.item(2, 1)
        self.item(3, 0, attempt=ATTEMPT - 1)
        self.item(4, 0, kind="RENDER")
        with self.assertRaises(sqlite3.IntegrityError):
            self.item(5, 0)
        self.assertEqual([(ATTEMPT - 1, 1), (ATTEMPT, 2)],
                         self.db.execute("SELECT attempt,count(*) FROM FileTransaction WHERE kind='EXPORT' "
                                         "GROUP BY attempt ORDER BY attempt").fetchall())

    def test_exact_guard_seeks_two_ordinals_and_isolates_old_attempt(self):
        self.item(1, 0)
        self.item(2, 1)
        self.item(3, 2, attempt=ATTEMPT - 1)
        self.assertEqual([(1,)], self.guard(0))
        self.assertEqual([(1,)], self.guard(1))
        self.assertEqual([], self.guard(2))
        self.assertEqual([(1,)], self.guard(2, ATTEMPT - 1))
        plan = self.db.execute("EXPLAIN QUERY PLAN " + self.dao("exportItemIntentReadIsBounded"),
                               self.args(0)).fetchall()
        # SQLite may label the searched relation with a table alias; the index and equality keys are the evidence.
        searched = [row[3] for row in plan if row[3].startswith("SEARCH ")
                    and "index_FileTransaction_kind_ownerId_attempt" in row[3]]
        self.assertTrue(any(re.search(r"kind=\? AND ownerId=\? AND attempt=\?", line) for line in searched), plan)

    def test_noncanonical_owner_values_do_not_match(self):
        key = expected_key(self.assembly, 2)
        self.item(1, 2, key=key.encode("ascii"))
        self.item(2, 2, key=key + "\x00suffix")
        self.item(3, 2, key=expected_key(self.assembly, "02"))
        self.item(4, 2, kind="RENDER")
        self.item(5, 2, key=key.replace("export-item-v1:", "export-item-v2:", 1))
        self.assertEqual([], self.guard(2))
        self.item(6, 2)
        self.assertEqual([(1,)], self.guard(2))

    def test_case_only_assembly_uuid_alias_is_not_an_exact_item_match(self):
        alias = self.assembly.upper()
        self.assertNotEqual(self.assembly, alias, "The fixture must exercise ASCII UUID case")
        self.item(1, 0, key=expected_key(alias, 0))
        self.item(2, 1)
        self.assertEqual([], self.guard(0))
        self.assertEqual([(1,)], self.guard(1))

        # Both spellings can coexist in v1; the canonical lookup still returns just its own row.
        self.item(3, 0)
        self.assertEqual([(1,)], self.guard(0))

    def test_matching_bad_row_returns_false_not_absence_before_projection(self):
        self.item(1, 0, payload=self.payload.decode("ascii"))
        self.item(2, 1, payload=self.payload + b"\x00")
        self.item(3, 2, expectedByteCount=17.5)
        self.item(4, 3, updatedAt=b"1")
        for ordinal in range(4):
            with self.subTest(ordinal=ordinal):
                self.assertEqual([(0,)], self.guard(ordinal))
        self.assertEqual([], self.guard(4))

    def test_matching_moved_item_rejects_each_malformed_text_field_in_isolation(self):
        self.item(1, 0)
        cases = (("stagingPath", 57), ("transactionId", 36),
                 ("sealSha256", 64), ("expectedByteSha256", 64))
        for ordinal, (field, extent) in enumerate(cases, start=1):
            with self.subTest(field=field):
                n = ordinal + 1
                self.item(n, ordinal)
                self.assertEqual([(1,)], self.guard(ordinal))
                # Change only this column on the admitted fixture. The short suffix preserves length(TEXT), but not the byte count.
                self.db.execute(f"UPDATE FileTransaction SET {field}={field} || ? WHERE transactionId=?",
                                ("\x00x", uid(n)))
                raw_shape = self.db.execute(
                    f"SELECT typeof({field}), length({field}), length(CAST({field} AS BLOB)) "
                    "FROM FileTransaction WHERE ownerId=?", (self.args(ordinal)["ownerKey"],)
                ).fetchone()
                self.assertEqual(("text", extent, extent + 2), raw_shape)
                self.assertEqual([(0,)], self.guard(ordinal))
                self.assertEqual([(1,)], self.guard(0), "The unchanged sibling remains admitted")

    def test_key_scan_detects_extra_even_when_extra_payload_is_malformed(self):
        self.item(1, 0)
        self.item(2, 1)
        self.item(3, 99, payload=b"malformed")
        self.item(4, 2, attempt=ATTEMPT - 1)
        # The candidate can use either these exact per-key seeks or a verified equivalent bounded query.
        flags = {ordinal: self.guard(ordinal) for ordinal in range(100)}
        self.assertEqual({0: [(1,)], 1: [(1,)], 99: [(0,)]}, {i: f for i, f in flags.items() if f})

    def test_typed_item_projection_is_one_exact_index_seek_and_omits_audit_column(self):
        self.item(1, 0, payload=b"a" * 685)
        self.item(2, 1, payload=b"b" * 685)
        self.item(3, 0, attempt=ATTEMPT - 1, payload=b"wrong")
        self.item(4, 0, kind="RENDER", payload=b"wrong")
        self.item(5, 0, key=expected_key(self.assembly.upper(), 0), payload=b"wrong")
        sql = self.dao("exportItemIntentRow")
        for ordinal, expected_byte in ((0, b"a"), (1, b"b")):
            args = self.args(ordinal) | {"expectedPayloadBytes": 685}
            self.assertEqual([(1,)], self.db.execute(self.dao("exportItemIntentReadIsBounded"), args).fetchall())
            cursor = self.db.execute(sql, args)
            self.assertEqual(("transactionId", "kind", "ownerId", "attempt", "state", "stagingPath",
                              "finalPath", "sealSha256", "expectedByteSha256", "expectedByteCount",
                              "payload", "errorCode"), tuple(c[0] for c in cursor.description))
            row, = cursor.fetchall()
            self.assertEqual((uid(ordinal + 1), "EXPORT", args["ownerKey"], ATTEMPT, "MOVED"), row[:5])
            self.assertEqual(expected_byte * 685, row[10])
        self.assertEqual([], self.db.execute(sql, self.args(2)).fetchall())
        plan = [r[3] for r in self.db.execute("EXPLAIN QUERY PLAN " + sql, self.args(0))]
        self.assertEqual(1, len(plan), plan)
        self.assertRegex(plan[0], r"^SEARCH .*index_FileTransaction_kind_ownerId_attempt "
                                  r"\(kind=\? AND ownerId=\? AND attempt=\?\)$")

    def test_fixed_payload_extent_and_exact_key_scope_before_projection(self):
        for n, ordinal, payload in ((1, 0, b"a" * 685), (2, 1, b"a" * 684), (3, 99, b"b" * 686)):
            self.item(n, ordinal, payload=payload)
        self.item(4, 100, payload=b"wrong")
        self.item(5, -1, payload=b"wrong")
        self.item(6, 2, key=expected_key(self.assembly.upper(), 2), payload=b"wrong")
        self.item(7, 3, attempt=ATTEMPT + 1, payload=b"wrong")
        sql = self.dao("exportItemIntentReadIsBounded")
        flags = [self.db.execute(sql, self.args(i) | {"expectedPayloadBytes": 685}).fetchall()
                 for i in range(100)]
        self.assertEqual({0: [(1,)], 1: [(0,)], 99: [(0,)]},
                         {i: f for i, f in enumerate(flags) if f})

    def test_known_stage_requires_the_matching_raw_seal_shape(self):
        unsealed = dict(sealSha256=None, expectedByteSha256=None, expectedByteCount=None)
        cases = (
            ("INTENT", unsealed, 1), ("COPYING", unsealed, 1), ("SEALED", {}, 1), ("COMMITTED", {}, 1),
            ("COPYING", {}, 0), ("MOVED", unsealed, 0), ("MOVED", dict(sealSha256=None), 0),
            ("MOVED", dict(finalPath="x" * 85 + "\x00tail"), 0), ("BAD_STATE", {}, 0),
        )
        for ordinal, (state, fields, verdict) in enumerate(cases):
            with self.subTest(state=state, ordinal=ordinal):
                self.item(ordinal + 1, ordinal, state=state, **fields)
                self.assertEqual([(verdict,)], self.guard(ordinal))

    def seed_artifacts(self, extra_id=None):
        self.db.execute("INSERT INTO Document VALUES ('doc','Synthetic',1,'ACTIVE',0,0,NULL)")
        self.db.execute("INSERT INTO ExportSnapshot VALUES (?,'doc',1,?,x'01','ASSEMBLING',0,999)",
                        (self.snapshot, "c" * 64))
        # A fourth row makes an omitted LIMIT observable; the third must not be filtered out before the limit.
        for n, ordinal, state in ((1, 0, "READY"), (2, 1, "READY"), (3, 99, "BAD_STATE"), (4, 100, "READY")):
            artifact = uid(n + 100)
            self.db.execute("INSERT INTO ExportArtifact VALUES (?, ?, ?, 'image/png', ?, ?, 17, 1, 'VERIFIED', ?, 0)",
                            (extra_id if n == 3 and extra_id is not None else artifact, self.snapshot, ordinal,
                             f"exports/{self.snapshot}/{artifact}.bin", "d" * 64, state))

    def test_artifact_inventory_cannot_hide_extra_bad_state_or_ordinal(self):
        self.seed_artifacts()
        sql = self.dao("exportArtifactIdsForSnapshot")
        args = dict(snapshotId=self.snapshot, limit=3)
        query = self.db.execute(sql, args)
        self.assertEqual(("artifactId",), tuple(column[0] for column in query.description))
        self.assertEqual([(uid(101),), (uid(102),), (uid(103),)], query.fetchall(),
                         "The n+1 sentinel must include the first extra member even in a bad state")
        plan = self.db.execute("EXPLAIN QUERY PLAN " + sql, args).fetchall()
        self.assertTrue(any(row[3].startswith("SEARCH ") and "index_ExportArtifact_snapshotId_ordinal" in row[3]
                            and "snapshotId=?" in row[3] for row in plan), plan)

    def test_negative_and_real_ordinals_keep_their_positions_in_bounded_inventory(self):
        self.seed_artifacts()
        sql = self.dao("exportArtifactIdsForSnapshot")
        args = dict(snapshotId=self.snapshot, limit=3)
        first, second, extra, moved = ((uid(n),) for n in (101, 102, 103, 104))
        self.assertEqual([first, second, extra], self.db.execute(sql, args).fetchall())

        cases = ((-1, "integer", [moved, first, second]),
                 (0.5, "real", [first, moved, second]))
        for ordinal, storage_class, expected in cases:
            with self.subTest(ordinal=ordinal, storage_class=storage_class):
                self.db.execute("UPDATE ExportArtifact SET ordinal=? WHERE artifactId=?",
                                (ordinal, moved[0]))
                # INTEGER affinity alone does not establish the stored value's class or its position relative to 0 and 1.
                self.assertEqual((storage_class, ordinal), self.db.execute(
                    "SELECT typeof(ordinal), ordinal FROM ExportArtifact WHERE artifactId=?", moved
                ).fetchone())
                self.assertEqual(expected, self.db.execute(sql, args).fetchall())

    def test_malformed_raw_artifact_id_occupies_the_extra_slot_as_a_sentinel(self):
        self.seed_artifacts()
        canonical = uid(103)
        for label, raw_id in (("BLOB", canonical.encode("ascii")),
                              ("TEXT with NUL and suffix", canonical + "\x00" + "x" * 1024),
                              ("wrong separators", canonical.replace("-", "0")),
                              ("non-hex", canonical[:-1] + "g"),
                              ("uppercase", canonical[:-1] + "A")):
            with self.subTest(raw_id=label):
                self.db.execute("UPDATE ExportArtifact SET artifactId=? WHERE snapshotId=? AND ordinal=99",
                                (raw_id, self.snapshot))
                query = self.db.execute(self.dao("exportArtifactIdsForSnapshot"),
                                        dict(snapshotId=self.snapshot, limit=3))
                self.assertEqual([(uid(101),), (uid(102),), ("",)], query.fetchall())

    def test_invalid_artifact_limit_returns_no_rows(self):
        self.seed_artifacts()
        for limit in (0, -1, 102):
            with self.subTest(limit=limit):
                self.assertEqual([], self.db.execute(self.dao("exportArtifactIdsForSnapshot"),
                                                     dict(snapshotId=self.snapshot, limit=limit)).fetchall())
        self.assertEqual(4, len(self.db.execute(self.dao("exportArtifactIdsForSnapshot"),
                                               dict(snapshotId=self.snapshot, limit=101)).fetchall()))
