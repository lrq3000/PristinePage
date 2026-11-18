"""Run the exact render phase CAS from the DAO against the exported Room v1 DDL."""

from pathlib import Path
import re
import unittest
from uuid import UUID

import test_room_schema


DAO = (Path(__file__).resolve().parents[2] /
       "data/src/main/kotlin/app/pristinepage/data/db/ExportSnapshotDao.kt")


def uid(n):
    return str(UUID(int=n))


class ExportRenderFilePhasesTest(unittest.TestCase):
    def setUp(self):
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()
        self.args = dict(transactionId=uid(1), jobId=uid(2), attempt=3, previousState="COPYING",
                         stagingPath=f"staging/{uid(1)}/derived.part", finalPath=f"renders/{uid(4)}/page.bin",
                         payload=b"x" * 615, previousUpdatedAt=10, updatedAt=11, nextState="SEALED",
                         previousSeal=None, previousHash=None, previousCount=None,
                         seal="a" * 64, hash="b" * 64, count=17)

    def insert(self, n=1, job=2, attempt=3, kind="RENDER", state="COPYING",
               seal=None, hash_=None, count=None, error=None):
        self.db.execute(
            """INSERT INTO FileTransaction (transactionId, kind, ownerId, state, stagingPath, finalPath,
               sealSha256, expectedByteSha256, expectedByteCount, payload, attempt, errorCode, updatedAt)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (uid(n), kind, uid(job), state, self.args["stagingPath"], self.args["finalPath"],
             seal, hash_, count, self.args["payload"], attempt, error, 10),
        )

    def advance(self, **changes):
        match = re.search(r'@Query\("""((?:(?!""").)*)"""\)\s*internal abstract fun advanceRenderFilePhase\(',
                          DAO.read_text(encoding="utf-8"), re.S)
        self.assertIsNotNone(match, "Missing actual render file phase CAS SQL")
        return self.db.execute(match[1], self.args | changes).rowcount

    def phases(self):
        return self.db.execute("""SELECT state, sealSha256, expectedByteSha256, expectedByteCount, updatedAt
                                  FROM FileTransaction ORDER BY transactionId""").fetchall()

    def test_exact_render_owner_transaction_attempt_only(self):
        self.insert()
        self.insert(n=5, attempt=4)
        self.insert(n=6, job=6)
        self.insert(n=7, kind="EXPORT")
        self.assertEqual(1, self.advance())
        self.assertEqual([("SEALED", "a" * 64, "b" * 64, 17, 11)] + [("COPYING", None, None, None, 10)] * 3,
                         self.phases())

    def test_refuses_changed_row_and_wrong_scope_without_writes(self):
        self.insert()
        for changed in (dict(transactionId=uid(9)), dict(jobId=uid(9)), dict(attempt=4),
                        dict(stagingPath="wrong"), dict(finalPath="wrong"), dict(payload=b"y" * 615),
                        dict(previousState="INTENT"), dict(previousUpdatedAt=9),
                        dict(previousSeal="a" * 64), dict(previousHash="b" * 64), dict(previousCount=17)):
            with self.subTest(changed=list(changed)):
                self.assertEqual(0, self.advance(**changed))
                self.assertEqual([("COPYING", None, None, None, 10)], self.phases())

    def test_only_copy_seal_move_edges_and_exact_seal_fields(self):
        self.insert(state="INTENT")
        unsealed = dict(previousState="INTENT", seal=None, hash=None, count=None)
        self.assertEqual(0, self.advance(**(unsealed | dict(nextState="MOVED"))))
        self.assertEqual(1, self.advance(**(unsealed | dict(nextState="COPYING"))))
        self.assertEqual([("COPYING", None, None, None, 11)], self.phases())
        for fields in (dict(nextState="MOVED"), dict(nextState="COMMITTED"), dict(seal=None),
                       dict(hash=None), dict(count=None), dict(count=0)):
            with self.subTest(fields=fields):
                self.assertEqual(0, self.advance(**(dict(previousUpdatedAt=11) | fields)))
        self.assertEqual(1, self.advance(previousUpdatedAt=11, updatedAt=12))
        sealed = dict(previousState="SEALED", previousUpdatedAt=12, updatedAt=13, nextState="MOVED",
                      previousSeal="a" * 64, previousHash="b" * 64, previousCount=17)
        for changed in (dict(previousHash="c" * 64), dict(seal="c" * 64), dict(hash="c" * 64), dict(count=18)):
            with self.subTest(changed=changed):
                self.assertEqual(0, self.advance(**(sealed | changed)))
                self.assertEqual([("SEALED", "a" * 64, "b" * 64, 17, 12)], self.phases())
        self.assertEqual(1, self.advance(**sealed))
        self.assertEqual([("MOVED", "a" * 64, "b" * 64, 17, 13)], self.phases())
        self.assertEqual(0, self.advance(**(sealed | dict(previousState="MOVED", previousUpdatedAt=13))))

    def test_refuses_presealed_copy_or_error_even_if_supplied_as_expected(self):
        self.insert(seal="a" * 64)
        self.assertEqual(0, self.advance(previousSeal="a" * 64))
        self.db.execute("UPDATE FileTransaction SET sealSha256=NULL, errorCode='CANCELED'")
        self.assertEqual(0, self.advance())
