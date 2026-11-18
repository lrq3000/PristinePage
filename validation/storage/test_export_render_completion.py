"""Exercise completion statements on exported v1 DDL, not a native Room/WAL test."""

from pathlib import Path
import re
import sqlite3
import unittest
from uuid import UUID

import test_room_schema


ROOT = Path(__file__).resolve().parents[2] / "data/src/main/kotlin/app/pristinepage/data/db"


def uid(n):
    return str(UUID(int=n))


class ExportRenderCompletionSqlTest(unittest.TestCase):
    def setUp(self):
        schema = test_room_schema.RoomSchemaTest()
        self.addCleanup(schema.doCleanups)
        self.db = schema.open_schema()
        schema.seed_source(self.db)
        self.job = uid(2)
        self.owner = uid(3)
        self.tx = uid(4)
        self.render = dict(renderId=uid(5), jobId=self.job, sourceId="source", pageId=uid(6), recipeRevision=1,
                           cacheKey="render-cache-v1:" + "c" * 64, execution=b"\x00actual execution",
                           executionSha256="d" * 64, relativePath=f"renders/{uid(5)}/page.bin", byteSha256="e" * 64,
                           rasterPixelSha256="f" * 64, byteCount=17, width=3, height=2)
        self.moved = dict(transactionId=self.tx, jobId=self.job, attempt=3, stagingPath=f"staging/{self.tx}/derived.part",
                          finalPath=self.render["relativePath"], payload=b"v" * 615, previousUpdatedAt=10,
                          updatedAt=11, seal="a" * 64, hash=self.render["byteSha256"], count=self.render["byteCount"])
        self.db.execute("INSERT INTO Job VALUES (?,?,?,?,NULL,NULL,?,'RUNNING',3,?,150,3,NULL,NULL,0,10)",
                        (self.job, "PAGE_RENDER", "page-render-v1:" + "b" * 64, "b" * 64, b"input", self.owner))
        self.db.execute("INSERT INTO SourceReference VALUES ('JOB',?,'source',0)", (self.job,))
        m = self.moved
        self.db.execute("INSERT INTO FileTransaction VALUES (?,'RENDER',?,'MOVED',?,?,?,?,?,?,3,NULL,10)",
                        (self.tx, self.job, m["stagingPath"], m["finalPath"], m["seal"], m["hash"], m["count"], m["payload"]))
        self.db.commit()

    def query(self, method, args, filename="ExportSnapshotDao.kt"):
        text = (ROOT / filename).read_text(encoding="utf-8")
        match = re.search(r'@Query\((?:"""((?:(?!""").)*)"""|"([^"]*)")\)\s*'
                          rf'(?:internal )?abstract (?:override )?fun {method}\(', text, re.S)
        self.assertIsNotNone(match, f"Missing actual DAO statement for {method}")
        return self.db.execute(match[1] or match[2], args)

    def test_exact_moved_commit_preserves_v1_binding(self):
        for drift in (dict(transactionId=uid(9)), dict(jobId=uid(10)), dict(attempt=4), dict(previousUpdatedAt=9),
                      dict(stagingPath="wrong"), dict(finalPath="wrong"), dict(payload=b"x" * 615),
                      dict(seal="0" * 64), dict(hash="1" * 64), dict(count=18)):
            with self.subTest(drift=drift):
                self.assertEqual(0, self.query("commitMovedRender", self.moved | drift).rowcount)
        self.assertEqual(1, self.query("commitMovedRender", self.moved).rowcount)
        self.assertEqual(0, self.query("commitMovedRender", self.moved | dict(previousUpdatedAt=11)).rowcount)
        m = self.moved
        self.assertEqual(("COMMITTED", m["payload"], m["seal"], m["hash"], m["count"], 11),
                         self.db.execute("SELECT state,payload,sealSha256,expectedByteSha256,expectedByteCount,updatedAt FROM FileTransaction").fetchone())

    def test_bounded_conflict_and_exact_readback_never_project_an_execution_blob(self):
        self.assertEqual((0,), self.query("renderCompletionOccupied", self.render).fetchone())
        self.query("insertReadyRender", self.render)
        self.assertEqual((1,), self.query("readyRenderMatches", self.render).fetchone())
        keys = {"renderId", "jobId", "cacheKey", "relativePath"}
        different = dict(renderId=uid(100), jobId=uid(101), cacheKey="different", relativePath="different")
        for key in keys:
            with self.subTest(key=key):
                self.assertEqual((1,), self.query("renderCompletionOccupied", different | {key: self.render[key]}).fetchone())
        with self.assertRaises(sqlite3.IntegrityError):
            self.query("insertReadyRender", self.render | dict(execution=b"replacement"))
        self.assertEqual((1,), self.query("readyRenderMatches", self.render).fetchone())
        self.db.execute("UPDATE RenderedPage SET execution=zeroblob(524288)")
        self.assertEqual((1,), self.query("renderCompletionOccupied", self.render).fetchone())
        self.assertEqual((0,), self.query("readyRenderMatches", self.render).fetchone())
        self.db.execute("UPDATE RenderedPage SET execution=?, width=3.5", (self.render["execution"],))
        self.assertEqual((0,), self.query("readyRenderMatches", self.render).fetchone())

    def test_late_lease_cas_miss_rolls_back_real_insert_and_journal_update(self):
        class Refusal(Exception):
            pass

        with self.assertRaises(Refusal):
            with self.db:
                self.query("insertReadyRender", self.render)
                self.assertEqual(1, self.query("commitMovedRender", self.moved).rowcount)
                args = dict(id=self.job, state="SUCCEEDED", attempt=3, owner=None, until=None, generation=3,
                            checkpoint=None, error=None, updated=12, expectedGeneration=4)
                self.assertEqual(0, self.query("updateLeaseState", args, "JobLeaseDao.kt").rowcount)
                raise Refusal()
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM RenderedPage").fetchone()[0])
        self.assertEqual(("MOVED", self.moved["payload"], 10), self.db.execute(
            "SELECT state,payload,updatedAt FROM FileTransaction").fetchone())
        self.assertEqual(("RUNNING", self.owner, 150), self.db.execute("SELECT state,leaseOwner,leaseUntil FROM Job").fetchone())
        self.assertEqual(("JOB", self.job, "source"), self.db.execute("SELECT ownerType,ownerId,sourceId FROM SourceReference").fetchone())
