"""Dormant T-11 EXPORT item mutations: execute the actual DAO statements on Room's v1 DDL."""

import re
import sqlite3
import struct
import unittest

import test_export_item_bounds
import test_room_schema
from test_export_item_bounds import expected_key, uid


def witness_lazy_blob_comparison(test, db, sql, column, parameter, extent, args, malformed, rejected, accepted):
    # Scope the probe to the actual THEN branch; the valid control must reach it. No opcode positions or optimizer-specific AND order.
    predicate = (rf"\bCASE\s+WHEN\s+((?:(?!\b(?:CASE|THEN|END)\b).)*)\bTHEN\s+"
                 rf"({column}\s*=\s*:{parameter})\s+ELSE\s+0\s+END")
    matches = tuple(re.finditer(predicate, sql, re.S))
    test.assertEqual(1, len(matches), f"No unique lazy comparison of {column}")
    match, = matches
    # Require all guards in this CASE's own WHEN, not elsewhere in the statement.
    guard = match[1]
    for operand in (column, ":" + parameter):
        test.assertRegex(guard, rf"typeof\({operand}\)\s*=\s*'blob'")
        test.assertRegex(guard, rf"length\({operand}\)\s*{extent}")
    calls = []
    db.create_function("fixture_blob_eq", 2, lambda a, b: calls.append((a, b)) or int(a == b))
    probe = sql[:match.start(2)] + f"fixture_blob_eq({column}, :{parameter})" + sql[match.end(2):]
    for value in malformed:
        with test.subTest(operand="bound", sample=repr(value)[:32]):
            test.assertEqual(rejected, db.execute(probe, args | {parameter: value}).rowcount)
            test.assertEqual([], calls)
    # Test fixtures in this module and the final-metadata module each seed only the target row for this probe.
    table = {"payload": "FileTransaction", "settings": "ExportSnapshot"}[column]
    for value in malformed:
        with test.subTest(operand="persisted", sample=repr(value)[:32]):
            db.execute(f"UPDATE {table} SET {column}=?", (value,))
            test.assertEqual(rejected, db.execute(probe, args | {parameter: value}).rowcount)
            test.assertEqual([], calls)
    db.execute(f"UPDATE {table} SET {column}=?", (args[parameter],))
    test.assertEqual(accepted, db.execute(probe, args).rowcount)
    test.assertEqual([(args[parameter], args[parameter])], calls)


class ExportItemPhasesSqlTest(unittest.TestCase):
    dao = test_export_item_bounds.ExportItemSqlTest.dao
    columns = (
        "transactionId", "kind", "ownerId", "state", "stagingPath", "finalPath",
        "sealSha256", "expectedByteSha256", "expectedByteCount", "payload", "attempt", "errorCode", "updatedAt",
    )
    triplet = dict(seal="e" * 64, byteSha256="b" * 64, byteCount=17)

    def setUp(self):
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()

    def plan(self, *, ordinal=0, attempt=3, tx=10, artifact=11):
        # Independently serialized synthetic v1 metadata, not a PNG or a file receipt. The EXPORT seal is distinct from the RENDER seal in the envelope.
        assembly, snapshot, transaction, artifact = uid(12), uid(13), uid(tx), uid(artifact)
        payload = (
            b"pristinepage-ordinary-png-export-item-v1\0"
            + (snapshot + "c" * 64 + assembly + "d" * 64 + uid(14)).encode("ascii")
            + struct.pack(">qqiii", attempt, attempt, ordinal, 3, 2)
            + (uid(15) + uid(16) + uid(17) + "a" * 64 + "b" * 64).encode("ascii")
            + struct.pack(">q", 17) + ("f" * 64 + artifact + transaction).encode("ascii")
        )
        self.assertEqual(685, len(payload))
        return dict(transactionId=transaction, assemblyJobId=assembly, snapshotId=snapshot, artifactId=artifact,
                    ordinal=ordinal, attempt=attempt, payload=payload, updatedAt=40)

    def expected(self, plan, **changes):
        transaction, artifact = plan["transactionId"], plan["artifactId"]
        return dict(transactionId=transaction, kind="EXPORT",
                    ownerId=expected_key(plan["assemblyJobId"], plan["ordinal"]),
                    state="INTENT", stagingPath=f"staging/{transaction}/derived.part",
                    finalPath=f"exports/{plan['snapshotId']}/{artifact}.bin", sealSha256=None,
                    expectedByteSha256=None, expectedByteCount=None, payload=plan["payload"],
                    attempt=plan["attempt"], errorCode=None, updatedAt=plan["updatedAt"]) | changes

    def seed(self, row):
        # Fixture-only write: also able to create malformed rows the new DAO must not change.
        self.db.execute("INSERT INTO FileTransaction (" + ",".join(self.columns) + ") VALUES (" +
                        ",".join(f":{column}" for column in self.columns) + ")", row)

    def rows(self):
        return self.db.execute("SELECT " + ",".join(self.columns) +
                               " FROM FileTransaction ORDER BY transactionId").fetchall()

    def values(self, row):
        return tuple(row[column] for column in self.columns)

    def insert(self, plan):
        return self.db.execute(self.dao("insertExportItemIntent"), plan).rowcount

    def edge(self, row, next_state, **changes):
        return dict(transactionId=row["transactionId"], ownerKey=row["ownerId"], attempt=row["attempt"],
                    previousState=row["state"], nextState=next_state, previousUpdatedAt=row["updatedAt"],
                    updatedAt=41, stagingPath=row["stagingPath"], finalPath=row["finalPath"],
                    payload=row["payload"], seal=row["sealSha256"], byteSha256=row["expectedByteSha256"],
                    byteCount=row["expectedByteCount"]) | changes

    def advance(self, args):
        return self.db.execute(self.dao("advanceExportItemPhase"), args).rowcount

    def assert_refused(self, args):
        before = self.rows()
        self.assertEqual(0, self.advance(args))
        self.assertEqual(before, self.rows())

    def sealed(self, plan, **changes):
        return self.expected(plan, state="SEALED", sealSha256=self.triplet["seal"],
                             expectedByteSha256=self.triplet["byteSha256"],
                             expectedByteCount=self.triplet["byteCount"]) | changes

    def test_insert_strict_intent_fields_and_preserves_neighbors(self):
        plan = self.plan(ordinal=99)
        self.seed(self.expected(self.plan(ordinal=99, attempt=2, tx=30)))
        self.seed(self.expected(self.plan(ordinal=99, tx=31), kind="RENDER", state="COMMITTED"))
        before = self.rows()
        self.assertEqual(1, self.insert(plan))
        self.assertEqual([self.values(self.expected(plan)), *before], self.rows())
        first = self.plan(tx=18)
        self.assertEqual(1, self.insert(first))
        self.assertEqual([self.values(self.expected(plan)), self.values(self.expected(first)), *before], self.rows())

    def test_insert_refuses_invalid_key_planned_ids_payload_and_audit_time(self):
        plan = self.plan()
        candidates = [
            ("ordinal", n) for n in (-1, 100, "0", 0.5)
        ] + [
            ("attempt", n) for n in (0, -1, "3", 3.5)
        ] + [
            ("payload", data) for data in (b"", b"x" * 684, b"x" * 686, "x" * 685)
        ] + [
            ("updatedAt", value) for value in (-1, "40", 40.5, b"40", None)
        ] + [
            (field, bad) for field in ("assemblyJobId", "snapshotId", "artifactId", "transactionId")
            for bad in (plan[field].upper(), plan[field] + "\0x", plan[field].encode("ascii"), plan[field][:-1] + "g",
                        "-" + plan[field][1:8] + "0" + plan[field][9:], None)
        ]
        for field, value in candidates:
            with self.subTest(field=field, value=repr(value)[:100]):
                self.assertEqual(0, self.insert(plan | {field: value}))
                self.assertEqual([], self.rows())

    def test_insert_does_not_replace_or_ignore_either_unique_collision(self):
        plan = self.plan()
        # Corrupt exact owner tuple, plus a separate RENDER row whose transaction ID collides.
        corrupt = self.expected(plan, state="BAD", payload="not a blob", updatedAt=b"40")
        self.seed(corrupt)
        self.seed(self.expected(self.plan(tx=19), kind="RENDER"))
        # The unmodified raw guard sees corruption, not absence. The insert cannot be a substitute for checkFresh.
        self.assertEqual([(0,)], self.db.execute(self.dao("exportItemIntentReadIsBounded"),
                         dict(ownerKey=corrupt["ownerId"], attempt=3, expectedPayloadBytes=685)).fetchall())
        before = self.rows()
        for collision, alternate in (("kind/item/attempt", self.plan(tx=20)),
                                     ("transaction ID", self.plan(ordinal=1, tx=19))):
            with self.subTest(collision=collision):
                with self.assertRaises(sqlite3.IntegrityError):
                    self.insert(alternate)
                self.assertEqual(before, self.rows())

    def test_only_three_edges_change_state_audit_and_first_seal(self):
        plan = self.plan()
        intent = self.expected(plan)
        self.seed(intent)
        self.seed(self.expected(self.plan(ordinal=1, tx=20)))
        self.seed(self.expected(self.plan(attempt=2, tx=21)))
        self.seed(self.expected(self.plan(tx=22), kind="RENDER"))
        neighbors = self.rows()[1:]
        # A repeated or backwards wall-clock observation is not elapsed lease time; the expected old timestamp is still exact.
        copying = intent | dict(state="COPYING", updatedAt=40)
        sealed = self.sealed(plan, updatedAt=39)
        moved = sealed | dict(state="MOVED", updatedAt=42)
        for args, row in ((self.edge(intent, "COPYING", updatedAt=40), copying),
                          (self.edge(copying, "SEALED", updatedAt=39, **self.triplet), sealed),
                          (self.edge(sealed, "MOVED", updatedAt=42), moved)):
            self.assertEqual(1, self.advance(args))
            self.assertEqual([self.values(row), *neighbors], self.rows())
            self.assert_refused(args)  # Replay/overlap does not win, even at the same audit time.

    def test_phase_uses_indexed_exact_kind_item_attempt_and_transaction(self):
        plan = self.plan()
        exact = self.expected(plan)
        args = self.edge(exact, "COPYING")
        self.seed(self.expected(self.plan(tx=20), kind="RENDER"))
        self.seed(self.expected(self.plan(tx=21, ordinal=1)))
        self.seed(self.expected(self.plan(tx=22, attempt=2)))
        case_alias = expected_key(plan["assemblyJobId"].upper(), 0)
        self.seed(self.expected(self.plan(tx=23), ownerId=case_alias))
        self.seed(self.expected(self.plan(tx=24), ownerId=exact["ownerId"] + "\0tail"))
        self.seed(self.expected(self.plan(tx=25), ownerId=exact["ownerId"].encode("ascii")))
        self.assert_refused(args)
        self.seed(exact)
        for field, value in (
            ("ownerKey", case_alias), ("ownerKey", expected_key(plan["assemblyJobId"], 1)),
            ("ownerKey", exact["ownerId"] + "\0tail"), ("attempt", 2), ("attempt", 3.5),
            ("transactionId", uid(20)), ("transactionId", plan["transactionId"].upper()),
        ):
            with self.subTest(field=field, value=value):
                self.assert_refused(args | {field: value})
        query_plan = [r[3] for r in self.db.execute("EXPLAIN QUERY PLAN " + self.dao("advanceExportItemPhase"), args)]
        self.assertEqual(1, len(query_plan), query_plan)
        self.assertRegex(query_plan[0], r"^SEARCH FileTransaction USING INDEX "
                         r"(?:sqlite_autoindex_FileTransaction_1 \(transactionId=\?\)|"
                         r"index_FileTransaction_kind_ownerId_attempt \(kind=\? AND ownerId=\? AND attempt=\?\))$")

    def test_phase_refuses_stale_immutable_tuple_errors_and_wrong_raw_payload(self):
        plan = self.plan()
        intent = self.expected(plan)
        self.seed(intent)
        args = self.edge(intent, "COPYING")
        for field, value in (("previousUpdatedAt", 39), ("previousUpdatedAt", "40"), ("updatedAt", -1),
                             ("updatedAt", 41.5), ("stagingPath", intent["stagingPath"] + "\0x"),
                             ("finalPath", self.expected(self.plan(artifact=9))["finalPath"]),
                             ("payload", b"x" + intent["payload"][1:])):
            with self.subTest(field=field, value=repr(value)[:100]):
                self.assert_refused(args | {field: value})
        for column, malformed, bind in (
            ("payload", b"x" * 684, "payload"), ("payload", b"x" * 686, "payload"),
            ("payload", "x" * 685, "payload"), ("updatedAt", b"40", "previousUpdatedAt"),
            ("updatedAt", 40.5, "previousUpdatedAt"), ("attempt", 3.5, "attempt"),
            ("errorCode", "", None), ("stagingPath", intent["stagingPath"].encode(), None),
            ("finalPath", intent["finalPath"].encode(), None),
        ):
            with self.subTest(raw=column, value=repr(malformed)[:100]):
                self.db.execute(f"UPDATE FileTransaction SET {column}=? WHERE transactionId=?",
                                (malformed, plan["transactionId"]))
                # Even matching same-size TEXT or byte-identical out-of-bound BLOBs are not the v1 payload.
                self.assert_refused(args)
                if bind:
                    self.assert_refused(args | {bind: malformed})
                self.db.execute(f"UPDATE FileTransaction SET {column}=? WHERE transactionId=?",
                                (intent[column], plan["transactionId"]))
        self.assertEqual(1, self.advance(args))

    def test_phase_refuses_exact_tuple_with_only_kind_wrong(self):
        intent = self.expected(self.plan())
        self.seed(intent | {"kind": "RENDER"})
        args = self.edge(intent, "COPYING")
        self.assert_refused(args)
        self.db.execute("UPDATE FileTransaction SET kind='EXPORT'")
        self.assertEqual(1, self.advance(args))

    def test_phase_payload_comparison_is_bounded_by_its_lazy_case(self):
        intent = self.expected(self.plan())
        self.seed(intent)
        witness_lazy_blob_comparison(self, self.db, self.dao("advanceExportItemPhase"), "payload", "payload",
                                     r"=\s*685", self.edge(intent, "COPYING"),
                                     (b"x" * 684, b"x" * 686, "x" * 685), 0, 1)

    def test_phase_refuses_skip_wrong_old_state_and_committed_edge(self):
        intent = self.expected(self.plan())
        self.seed(intent)
        self.assert_refused(self.edge(intent, "COPYING", previousState="COPYING"))
        for ordinal, (old, new) in enumerate((
            ("INTENT", "SEALED"), ("INTENT", "MOVED"), ("COPYING", "INTENT"), ("COPYING", "MOVED"),
            ("SEALED", "COPYING"), ("SEALED", "SEALED"), ("MOVED", "COMMITTED"), ("MOVED", "SEALED"),
            ("COMMITTED", "MOVED"), ("CANCELED", "COPYING"),
        ), start=1):
            with self.subTest(old=old, new=new):
                plan = self.plan(ordinal=ordinal, tx=20 + ordinal)
                row = (self.sealed(plan) if old in {"SEALED", "MOVED", "COMMITTED"} else self.expected(plan))
                row = row | {"state": old}
                self.seed(row)
                self.assert_refused(self.edge(row, new, **(self.triplet if new in {"SEALED", "MOVED"} else {})))

    def test_seal_requires_an_empty_old_triplet_and_only_valid_new_components(self):
        plan = self.plan()
        row = self.expected(plan)
        self.seed(row)
        for field in self.triplet:
            self.assert_refused(self.edge(row, "COPYING", **{field: self.triplet[field]}))
        copying = row | {"state": "COPYING"}
        self.db.execute("UPDATE FileTransaction SET state='COPYING'")
        for field, values in (
            ("seal", (None, "A" * 64, "g" * 64, "e" * 63, "e" * 65, "e" * 64 + "\0x", b"e" * 64)),
            ("byteSha256", (None, "B" * 64, "z" * 64, "b" * 63, "b" * 65, "b" * 63 + "\0", b"b" * 64)),
            ("byteCount", (None, 0, -1, 17.0, 17.5, "17", b"17")),
        ):
            for invalid in values:
                with self.subTest(field=field, invalid=invalid):
                    self.assert_refused(self.edge(copying, "SEALED", **(self.triplet | {field: invalid})))
        for column, bad in zip(("sealSha256", "expectedByteSha256", "expectedByteCount"), self.triplet.values()):
            with self.subTest(old_component=column):
                self.db.execute(f"UPDATE FileTransaction SET {column}=?", (bad,))
                self.assert_refused(self.edge(copying, "SEALED", **self.triplet))
                self.db.execute(f"UPDATE FileTransaction SET {column}=NULL")
                self.db.execute("UPDATE FileTransaction SET state='INTENT'")
                self.db.execute(f"UPDATE FileTransaction SET {column}=?", (bad,))
                self.assert_refused(self.edge(row, "COPYING"))
                self.db.execute(f"UPDATE FileTransaction SET {column}=NULL, state='COPYING'")
        self.assertEqual(1, self.advance(self.edge(copying, "SEALED", **self.triplet)))

    def test_move_requires_every_component_of_the_exact_valid_stored_seal(self):
        plan = self.plan()
        sealed = self.sealed(plan)
        self.seed(sealed)
        args = self.edge(sealed, "MOVED")
        for field, change in (("seal", "0" * 64), ("byteSha256", "1" * 64), ("byteCount", 18)):
            with self.subTest(expected=field):
                self.assert_refused(args | {field: change})
                self.assert_refused(args | {field: None})
        for column, field, malformed in (
            ("sealSha256", "seal", "E" * 64),
            ("expectedByteSha256", "byteSha256", b"b" * 64),
            ("expectedByteCount", "byteCount", 17.5),
        ):
            with self.subTest(raw=column):
                self.db.execute(f"UPDATE FileTransaction SET {column}=?", (malformed,))
                self.assert_refused(args)
                self.assert_refused(args | {field: malformed})
                self.db.execute(f"UPDATE FileTransaction SET {column}=?", (sealed[column],))
        self.assertEqual(1, self.advance(args))
        self.assertEqual([self.values(sealed | dict(state="MOVED", updatedAt=41))], self.rows())
