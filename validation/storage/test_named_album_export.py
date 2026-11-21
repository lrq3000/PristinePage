"""Exercise the actual named artifact and provider-write CAS against exported Room DDL."""

import unittest
from hashlib import sha256

import test_export_render_completion as completion

uid = completion.uid


class NamedAlbumExportSqlTest(unittest.TestCase):
    def setUp(self):
        harness = completion.ExportRenderCompletionSqlTest()
        harness.setUp()
        self.addCleanup(harness.doCleanups)
        self.db = harness.db
        self.query = harness.query
        self.snapshot, self.artifact, self.tx, self.publication = [uid(n) for n in range(51, 55)]
        doc = self.db.execute("SELECT documentId FROM Document LIMIT 1").fetchone()
        if doc is None:
            self.db.execute("INSERT INTO Document VALUES ('doc','Invoices',1,'ACTIVE',0,0,NULL)")
            doc = ("doc",)
        self.db.execute("INSERT INTO ExportSnapshot VALUES (?,?,1,?,?, 'FROZEN',0,999)", (self.snapshot, doc[0], "a" * 64, b"v2"))
        self.args = dict(id=self.tx, owner="named-album-v1:" + uid(77), stage=f"staging/{self.tx}/derived.part",
                         final=f"exports/{self.snapshot}/{self.artifact}.bin", payload=b"frozen", audit=1,
                         seal="b" * 64, hash="c" * 64, size=42)
        a = self.args
        self.db.execute("INSERT INTO FileTransaction VALUES (?,'EXPORT',?,'COPYING',?,?,NULL,NULL,NULL,?,1,NULL,0)",
                        (a["id"], a["owner"], a["stage"], a["final"], a["payload"]))

    def test_altering_exact_binding_or_replaying_an_old_phase_is_rejected(self):
        seal = self.args | dict(previous="COPYING", next="SEALED", previousSeal=None, previousHash=None, previousSize=None)
        for drift in (dict(payload=b"other"), dict(final="other"), dict(owner="other"), dict(previous="MOVED")):
            self.assertEqual(0, self.query("advanceNamedAlbumArtifact", seal | drift).rowcount)
        self.assertEqual(1, self.query("advanceNamedAlbumArtifact", seal).rowcount)
        self.assertEqual(0, self.query("advanceNamedAlbumArtifact", seal).rowcount)
        follow = self.args | dict(previousSeal=self.args["seal"], previousHash=self.args["hash"], previousSize=42)
        self.assertEqual(1, self.query("advanceNamedAlbumArtifact", follow | dict(previous="SEALED", next="MOVED")).rowcount)
        self.assertEqual((0,), self.query("namedAlbumJournalMatches", self.args).fetchone())
        self.assertEqual(1, self.query("advanceNamedAlbumArtifact", follow | dict(previous="MOVED", next="COMMITTED")).rowcount)
        self.assertEqual((1,), self.query("namedAlbumJournalMatches", self.args).fetchone())

    def test_a_second_or_foreign_provider_result_cannot_reuse_the_original_consent(self):
        a = self.args
        self.db.execute("INSERT INTO ExportArtifact VALUES (?,?,0,'application/zip',?,?,42,2,'NAMED_ALBUM_READBACK_V1','READY',0)",
                        (self.artifact, self.snapshot, a["final"], a["hash"]))
        private = dict(id=self.artifact, snapshot=self.snapshot, path=a["final"], mime="application/zip",
                       size=42, hash=a["hash"], pages=2)
        self.assertEqual((0,), self.query("namedAlbumArtifactMatches", private | dict(now=50)).fetchone())
        self.db.execute("UPDATE ExportSnapshot SET state='PRIVATE_READY' WHERE snapshotId=?", (self.snapshot,))
        self.assertEqual((1,), self.query("namedAlbumArtifactMatches", private | dict(now=50)).fetchone())
        self.assertEqual((0,), self.query("namedAlbumArtifactMatches", private | dict(now=1000)).fetchone())
        self.db.execute("""INSERT INTO Publication VALUES (?,?,NULL,'WAITING_DESTINATION',?,0,?,42,0,NULL,NULL,0,0)""",
                        (self.publication, self.artifact, self.publication, a["hash"]))
        request = dict(id=self.publication, artifact=self.artifact, previous="WAITING_DESTINATION", next="WRITING",
                       uri="content://synthetic/export", sent=0, readback=None, error=None, digest=a["hash"], extent=42, now=1)
        self.assertEqual(0, self.query("updateNamedPublication", request | dict(artifact=uid(99))).rowcount)
        self.assertEqual(0, self.query("updateNamedPublication", request | dict(digest="d" * 64)).rowcount)
        self.assertEqual(1, self.query("updateNamedPublication", request).rowcount)
        self.assertEqual(0, self.query("updateNamedPublication", request | dict(uri="content://other/file")).rowcount)
        finish = request | dict(previous="WRITING", next="OUTPUT_COMPLETED", sent=42)
        self.assertEqual(0, self.query("updateNamedPublication", finish | dict(uri="content://other/file")).rowcount)
        self.assertEqual(1, self.query("updateNamedPublication", finish).rowcount)
        self.assertEqual(("content://synthetic/export", 1), self.db.execute(
            "SELECT targetUri,targetCreatedByAttempt FROM Publication").fetchone())

    def test_only_a_live_lease_for_that_exact_ready_artifact_can_open(self):
        a = self.args
        share = uid(88)
        path = f"shared-exports/{share}/Invoices.zip"
        self.db.execute("UPDATE ExportSnapshot SET state='PRIVATE_READY' WHERE snapshotId=?", (self.snapshot,))
        self.db.execute("INSERT INTO ExportArtifact VALUES (?,?,0,'application/zip',?,?,42,2,'NAMED_ALBUM_READBACK_V1','READY',0)",
                        (self.artifact, self.snapshot, a["final"], a["hash"]))
        self.db.execute("INSERT INTO ShareLease VALUES (?,?,?,'ACTIVE',100,200,NULL)", (share, self.artifact, path))
        request = dict(id=share, path=path, size=42, now=150)
        self.assertEqual((1,), self.query("namedShareCanOpen", request).fetchone())
        for drift in (dict(now=99), dict(now=200), dict(size=41), dict(path=f"shared-exports/{share}/Other.zip"), dict(id=uid(87))):
            self.assertEqual((0,), self.query("namedShareCanOpen", request | drift).fetchone())
        self.db.execute("UPDATE ShareLease SET state='REVOKED' WHERE shareId=?", (share,))
        self.assertEqual((0,), self.query("namedShareCanOpen", request).fetchone())

    def test_failure_terminalizes_only_the_exact_incomplete_attempt_and_preserves_file_identity(self):
        a = self.args
        request = dict(id=a["id"], owner=a["owner"], stage=a["stage"], final=a["final"],
                       payload=a["payload"], now=10, error="CANCELED")
        self.db.execute("UPDATE FileTransaction SET state='MOVED', sealSha256=?, expectedByteSha256=?, expectedByteCount=42 WHERE transactionId=?",
                        (a["seal"], a["hash"], a["id"]))
        self.assertEqual(0, self.query("failNamedAlbumIntent", request | dict(payload=b"other")).rowcount)
        self.assertEqual(1, self.query("failNamedAlbumIntent", request).rowcount)
        self.assertEqual(0, self.query("failNamedAlbumIntent", request).rowcount)
        self.assertEqual(("FAILED", "CANCELED", a["seal"], a["hash"], 42), self.db.execute(
            "SELECT state,errorCode,sealSha256,expectedByteSha256,expectedByteCount FROM FileTransaction WHERE transactionId=?", (a["id"],)).fetchone())
        self.db.execute("UPDATE FileTransaction SET state='COMMITTED',errorCode=NULL WHERE transactionId=?", (a["id"],))
        self.assertEqual(0, self.query("failNamedAlbumIntent", request).rowcount)

    def test_named_job_has_a_distinct_bounded_intent_and_admits_only_its_exact_bytes(self):
        job = uid(90)
        key = f"named-album-out-v1/{self.snapshot}"
        payload = f"pristinepage-named-album-out-v1\0{self.snapshot}\0{'a' * 64}".encode()
        digest = sha256(payload).hexdigest()
        self.db.execute("""INSERT INTO Job(jobId,kind,state,idempotenceKey,canonicalInputSha256,snapshotId,input,
            attempt,leaseGeneration,createdAt,updatedAt) VALUES (?,'ALBUM_ARTIFACT','PENDING',?,?,?,?,0,0,0,0)""",
                        (job, key, digest, self.snapshot, payload))
        self.assertEqual((1,), self.query("assemblyJobReadIsBounded", dict(jobId=job)).fetchone())
        exact = dict(jobId=job, snapshotId=self.snapshot, key=key, digest=digest, input=payload)
        self.assertEqual((0,), self.query("freshAssemblyInputMatches", exact | dict(input=payload[:-1] + b"b")).fetchone())
        self.assertEqual((1,), self.query("freshAssemblyInputMatches", exact).fetchone())
        admit = exact | dict(previousUpdatedAt=0, updatedAt=1)
        self.assertEqual(1, self.query("admitPendingAssembly", admit).rowcount)
        self.assertEqual(0, self.query("admitPendingAssembly", admit).rowcount)
        self.db.execute("UPDATE Job SET input=? WHERE jobId=?", (payload + b"\0", job))
        self.assertEqual((0,), self.query("assemblyJobReadIsBounded", dict(jobId=job)).fetchone())
