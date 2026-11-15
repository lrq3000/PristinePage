package app.pristinepage.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pristinepage.data.db.RecipeMaskRow
import app.pristinepage.data.db.SourceReferenceRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomReferenceAudit
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SQL counts on generated metadata; databases stay retained. Repeated audit/reopen is not process-death, owner-validity, decoder or reclamation evidence. */
@RunWith(AndroidJUnit4::class)
class RoomReferenceAuditIntegrationTest {
    @Test fun sourceAndBlobCountersCompareActualRowsAndNeverRepairMismatch() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            assertEquals(RecoveryReport(2, 0, 0, 0, 0, emptyList()), RoomReferenceAudit(f.database).audit())
            val blob = f.database.catalog().source(f.source)!!.sourceBlobId
            f.database.runInTransaction {
                assertEquals(1, f.database.catalog().setSourceRefCount(f.source, 0))
                f.database.openHelper.writableDatabase.execSQL("UPDATE SourceBlob SET sourceCount=0 WHERE sourceBlobId=?", arrayOf<Any>(blob))
            }
            f.reopen()
            val audit = RoomReferenceAudit(f.database); val report = audit.audit()
            assertEquals(2L, report.inspected); assertEquals(2L, report.blocked); assertEquals(setOf(f.source, blob), report.safeIssues.map { it.operationId }.toSet())
            assertEquals(report, audit.audit()); assertEquals(0L, report.completed); assertEquals(0L, report.quarantined)
            f.reopen()
            assertEquals(0L, f.database.catalog().source(f.source)!!.refCount); assertEquals(0L, f.database.catalog().sourceBlob(blob)!!.sourceCount)
            assertEquals(4L, f.count("SourceReference")); assertEquals(0L, f.count("UndoEntry")); f.assertForeignKeys()
        }
    }

    @Test fun maskCountsBothOwnerKindsAndBlobCountsRetainedConversionsAcrossReopen() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val mask = UUID.randomUUID().toString(); val source = f.database.catalog().source(f.source)!!; val retained = UUID.randomUUID().toString()
            f.database.runInTransaction {
                val sql = f.database.openHelper.writableDatabase
                sql.execSQL("INSERT INTO Mask(maskId,sourceId,kind,sha256,width,height,relativePath,encodingVersion,refCount,state) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(mask, f.source, "PROTECTION", "c".repeat(64), 3, 2, "masks/$mask/mask.bin", 1, 1L, "READY"))
                f.database.catalog().insertRecipeMask(RecipeMaskRow(f.pages[0].value, 1, mask, "PROTECTION"))
                f.database.catalog().insertReference(SourceReferenceRow("MASK", mask, f.source, 0))
                assertEquals(1, f.database.catalog().setSourceRefCount(f.source, 5))
                sql.execSQL("INSERT INTO Source(sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,refCount,state,createdAt) SELECT ?,sourceBlobId,pixelSha256,width,height,exifOrientation,'retained-v2',profileSha256,0,'QUARANTINED',createdAt FROM Source WHERE sourceId=?", arrayOf<Any>(retained, f.source))
                sql.execSQL("UPDATE SourceBlob SET sourceCount=2 WHERE sourceBlobId=?", arrayOf<Any>(source.sourceBlobId))
            }
            assertTrue(f.repository.saveRecipe(f.pages[0], 1, f.turned(1)) is SaveRecipeResult.Saved)
            f.reopen()
            assertEquals(3L, f.database.catalog().mask(mask)!!.refCount)
            assertEquals(RecoveryReport(4, 0, 0, 0, 0, emptyList()), RoomReferenceAudit(f.database).audit())
            assertEquals(1, f.database.catalog().setMaskRefCount(mask, 2))
            val report = RoomReferenceAudit(f.database).audit(); assertEquals(1L, report.blocked); assertEquals(mask, report.safeIssues.single().operationId)
            f.reopen()
            assertEquals(2L, f.database.catalog().mask(mask)!!.refCount); assertEquals(3L, f.database.catalog().countMaskReferences(mask))
            assertEquals(2L, f.database.catalog().sourceBlob(source.sourceBlobId)!!.sourceCount)
            assertEquals("QUARANTINED", f.database.catalog().source(retained)!!.state)
            assertEquals(7L, f.count("SourceReference")); f.assertForeignKeys()
        }
    }

    @Test fun realSqlPaginationCountsAllMismatchesBeyondItsBoundedIssueList() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val blob = f.database.catalog().source(f.source)!!.sourceBlobId
            f.database.runInTransaction {
                val sql = f.database.openHelper.writableDatabase
                repeat(130) { index ->
                    sql.execSQL("INSERT INTO Source(sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,refCount,state,createdAt) SELECT ?,sourceBlobId,pixelSha256,width,height,exifOrientation,?,profileSha256,1,'DELETING',createdAt FROM Source WHERE sourceId=?", arrayOf<Any>(UUID.randomUUID().toString(), "retained-$index", f.source))
                }
                sql.execSQL("UPDATE SourceBlob SET sourceCount=131 WHERE sourceBlobId=?", arrayOf<Any>(blob))
            }
            f.reopen()
            val audit = RoomReferenceAudit(f.database); val report = audit.audit()
            assertEquals(132L, report.inspected); assertEquals(130L, report.blocked); assertEquals(128, report.safeIssues.size)
            assertEquals(report, audit.audit()); assertEquals(0L, report.completed); assertEquals(0L, report.resumed); assertEquals(0L, report.quarantined)
            assertTrue(report.safeIssues.all { it.action == RecoveryAction.REVIEW_INTEGRITY && it.error?.code == OperationErrorCode.INTEGRITY_FAILED })
            assertEquals(4L, f.count("SourceReference")); assertEquals(131L, f.database.catalog().sourceBlob(blob)!!.sourceCount); f.assertForeignKeys()
        }
    }

    @Test fun initialSqlPageIncludesEmptySourceBlobAndMaskIdsThenAuditRejectsUnchanged() = runBlocking {
        for (kind in listOf("SOURCE", "BLOB", "MASK")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val blob = f.database.catalog().source(f.source)!!.sourceBlobId; val token = UUID.randomUUID().toString()
            f.database.runInTransaction {
                val sql = f.database.openHelper.writableDatabase
                when (kind) {
                    "SOURCE" -> {
                        sql.execSQL("INSERT INTO Source(sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,refCount,state,createdAt) SELECT '',sourceBlobId,pixelSha256,width,height,exifOrientation,?,profileSha256,1,state,createdAt FROM Source WHERE sourceId=?", arrayOf<Any>(token, f.source))
                        sql.execSQL("UPDATE SourceBlob SET sourceCount=2 WHERE sourceBlobId=?", arrayOf<Any>(blob))
                    }
                    "BLOB" -> sql.execSQL("INSERT INTO SourceBlob(sourceBlobId,byteSha256,byteCount,mime,encodedWidth,encodedHeight,relativePath,state,sourceCount,ingestTransactionId,createdAt) SELECT '',byteSha256,byteCount,mime,encodedWidth,encodedHeight,?,state,1,?,createdAt FROM SourceBlob WHERE sourceBlobId=?", arrayOf<Any>("sources/$token/original.bin", token, blob))
                    "MASK" -> sql.execSQL("INSERT INTO Mask(maskId,sourceId,kind,sha256,width,height,relativePath,encodingVersion,refCount,state) VALUES('',?,?,?,?,?,?,?,?,?)", arrayOf<Any>(f.source, "PROTECTION", "c".repeat(64), 3, 2, "masks/$token/mask.bin", 1, 1L, "READY"))
                }
            }
            f.reopen()
            fun projections() = f.database.referenceAudit().let { listOf(it.sourcesAfter(null, 64), it.sourceBlobsAfter(null, 64), it.masksAfter(null, 64)) }
            val before = projections(); assertEquals(1, before.flatten().count { it.objectId.isEmpty() && it.storedCount != it.actualCount })
            try { RoomReferenceAudit(f.database).audit(); fail("Empty $kind ID was skipped by actual SQL") }
            catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code); assertFalse(failure.error.retryable); assertNull(failure.error.affectedId) }
            f.reopen(); assertEquals(before, projections())
            assertEquals(4L, f.count("SourceReference")); assertEquals(0L, f.count("UndoEntry")); assertEquals(2L, f.count("RecipeRevision")); f.assertForeignKeys()
        }
    }
}
