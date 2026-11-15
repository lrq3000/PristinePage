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
    private fun metadataState(f: RoomRepositoryIntegrationTest.Fixture) = f.metadataState()

    private fun protectionMask(f: RoomRepositoryIntegrationTest.Fixture): String {
        val mask = UUID.randomUUID().toString()
        f.database.runInTransaction {
            f.database.openHelper.writableDatabase.execSQL("INSERT INTO Mask(maskId,sourceId,kind,sha256,width,height,relativePath,encodingVersion,refCount,state) VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(mask, f.source, "PROTECTION", "c".repeat(64), 3, 2, "masks/$mask/mask.bin", 1, 1L, "READY"))
            val q = f.database.catalog()
            q.insertRecipeMask(RecipeMaskRow(f.pages[0].value, 1, mask, "PROTECTION"))
            q.insertReference(SourceReferenceRow("MASK", mask, f.source, 0))
            assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
        }
        return mask
    }

    /** Wrong-source pins remain FK-valid and counter-balanced, exposing binding errors rather than unrelated counter failures. Caller holds the fixture transaction. */
    private fun anotherSource(f: RoomRepositoryIntegrationTest.Fixture): String {
        val source = UUID.randomUUID().toString(); val blob = f.database.catalog().source(f.source)!!.sourceBlobId
        val sql = f.database.openHelper.writableDatabase
        sql.execSQL("INSERT INTO Source(sourceId,sourceBlobId,pixelSha256,width,height,exifOrientation,conversionFingerprint,profileSha256,refCount,state,createdAt) SELECT ?,sourceBlobId,pixelSha256,width,height,exifOrientation,?,profileSha256,1,state,createdAt FROM Source WHERE sourceId=?", arrayOf<Any>(source, "independent-$source", f.source))
        sql.execSQL("UPDATE SourceBlob SET sourceCount=2 WHERE sourceBlobId=?", arrayOf<Any>(blob))
        return source
    }

    private suspend fun assertRequiredPinFailure(f: RoomRepositoryIntegrationTest.Fixture, id: String, state: String) {
        f.reopen(); val before = metadataState(f); val audit = RoomReferenceAudit(f.database)
        assertEquals(0L, audit.audit().blocked)
        val report = audit.auditRequiredPins(); assertEquals(1L, report.blocked)
        val issue = report.safeIssues.single()
        assertEquals(id, issue.operationId); assertEquals(state, issue.state)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, issue.error!!.code); assertFalse(issue.error.retryable)
        assertEquals("required_source_pin_missing", issue.error.safeMessageKey); assertEquals(RecoveryAction.REVIEW_INTEGRITY, issue.action)
        assertEquals(0L, report.completed); assertEquals(0L, report.resumed); assertEquals(0L, report.quarantined)
        assertEquals(report, audit.auditRequiredPins())
        f.reopen(); assertEquals(before, metadataState(f)); f.assertForeignKeys()
    }

    private suspend fun assertInvalidDirectOwner(f: RoomRepositoryIntegrationTest.Fixture, source: String = f.source) {
        f.reopen(); val before = metadataState(f); val audit = RoomReferenceAudit(f.database)
        assertEquals(0L, audit.audit().blocked); assertEquals(0L, audit.auditRequiredPins().blocked)
        val report = audit.auditDirectOwners(); assertEquals(1L, report.blocked)
        val issue = report.safeIssues.single()
        assertEquals(source, issue.operationId); assertEquals("SOURCE_DIRECT_OWNERS", issue.state)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, issue.error!!.code); assertFalse(issue.error.retryable)
        assertEquals("invalid_direct_source_owner", issue.error.safeMessageKey); assertEquals(RecoveryAction.REVIEW_INTEGRITY, issue.action)
        assertEquals(0L, report.completed); assertEquals(0L, report.resumed); assertEquals(0L, report.quarantined)
        assertEquals(report, audit.auditDirectOwners())
        f.reopen(); assertEquals(before, metadataState(f)); f.assertForeignKeys()
    }

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

    @Test fun requiredPinsRemainCompleteForEditedDuplicateTombstoneAndMaskAcrossReopen() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            protectionMask(f)
            val duplicate = f.repository.duplicatePage(f.pages[0], 1) as DocumentMutationResult.Applied
            val copy = duplicate.affectedPageIds.single()
            assertTrue(f.repository.saveRecipe(copy, 1, f.turned(2)) is SaveRecipeResult.Saved)
            assertTrue(f.repository.undoDocumentEdit(checkNotNull(duplicate.undoId), 3) is DocumentMutationResult.Applied)
            f.reopen(); val before = metadataState(f); val audit = RoomReferenceAudit(f.database)
            assertEquals(RecoveryReport(7, 0, 0, 0, 0, emptyList()), audit.auditRequiredPins())
            assertEquals(RecoveryReport(7, 0, 0, 0, 0, emptyList()), audit.auditRequiredPins())
            assertEquals(0L, audit.audit().blocked)
            assertEquals(RecoveryReport(1, 0, 0, 0, 0, emptyList()), audit.auditDirectOwners())
            f.reopen(); assertEquals(before, metadataState(f))
            assertEquals("REMOVED", f.database.catalog().page(copy.value)!!.state)
            assertEquals(2L, f.database.catalog().page(copy.value)!!.currentRecipeRevision); f.assertForeignKeys()
        }
    }

    @Test fun requiredPagePinRejectsMissingAndWrongSourcePinsDespiteBalancedCounters() = runBlocking {
        for (wrongSource in listOf(false, true)) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val page = f.pages[0].value
            f.database.runInTransaction {
                val sql = f.database.openHelper.writableDatabase
                if (wrongSource) sql.execSQL("UPDATE SourceReference SET sourceId=? WHERE ownerType='PAGE' AND ownerId=? AND sourceId=?", arrayOf<Any>(anotherSource(f), page, f.source))
                else sql.execSQL("DELETE FROM SourceReference WHERE ownerType='PAGE' AND ownerId=? AND sourceId=?", arrayOf<Any>(page, f.source))
                val q = f.database.catalog(); assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            assertRequiredPinFailure(f, page, "PAGE_SOURCE_PIN")
        }
    }

    @Test fun everyOldHistoryPinMustMatchCanonicalRevisionKeyAndItsOwnSource() = runBlocking {
        for (fault in listOf("MISSING", "WRONG_SOURCE", "NONCANONICAL")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val page = f.pages[0].value
            assertTrue(f.repository.saveRecipe(f.pages[0], 1, f.turned(1)) is SaveRecipeResult.Saved)
            f.database.runInTransaction {
                val sql = f.database.openHelper.writableDatabase; val old = "recipe-v1/$page/1"
                when (fault) {
                    "MISSING" -> sql.execSQL("DELETE FROM SourceReference WHERE ownerType='RECIPE_HISTORY' AND ownerId=? AND sourceId=?", arrayOf<Any>(old, f.source))
                    "WRONG_SOURCE" -> sql.execSQL("UPDATE SourceReference SET sourceId=? WHERE ownerType='RECIPE_HISTORY' AND ownerId=? AND sourceId=?", arrayOf<Any>(anotherSource(f), old, f.source))
                    "NONCANONICAL" -> sql.execSQL("UPDATE SourceReference SET ownerId=? WHERE ownerType='RECIPE_HISTORY' AND ownerId=? AND sourceId=?", arrayOf<Any>("recipe-v1/$page/01", old, f.source))
                }
                val q = f.database.catalog(); assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            assertRequiredPinFailure(f, page, "RECIPE_SOURCE_PINS")
            assertEquals(2L, f.database.catalog().page(page)!!.currentRecipeRevision)
            assertEquals(3L, f.count("RecipeRevision"))
        }
    }

    @Test fun requiredMaskSourcePinIsNotReplacedByCorrectMaskRefcount() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val mask = protectionMask(f)
            f.database.runInTransaction {
                f.database.openHelper.writableDatabase.execSQL("DELETE FROM SourceReference WHERE ownerType='MASK' AND ownerId=? AND sourceId=?", arrayOf<Any>(mask, f.source))
                val q = f.database.catalog(); assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            assertRequiredPinFailure(f, mask, "MASK_SOURCE_PIN")
            assertEquals(1L, f.database.catalog().mask(mask)!!.refCount)
            assertEquals(1L, f.database.catalog().countMaskReferences(mask))
        }
    }

    @Test fun retainedRemovedPageRequiresSourcePinBeforeAnyFinalization() = runBlocking {
        RoomRepositoryIntegrationTest.Fixture().use { f ->
            val page = f.pages[0].value
            assertTrue(f.repository.removePage(f.pages[0], 1) is DocumentMutationResult.Applied)
            f.database.runInTransaction {
                f.database.openHelper.writableDatabase.execSQL("DELETE FROM SourceReference WHERE ownerType='PAGE' AND ownerId=? AND sourceId=?", arrayOf<Any>(page, f.source))
                val q = f.database.catalog(); assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            assertRequiredPinFailure(f, page, "PAGE_SOURCE_PIN")
            assertEquals("REMOVED", f.database.catalog().page(page)!!.state)
            assertEquals(2L, f.count("RecipeRevision")); assertEquals(1L, f.count("UndoEntry"))
        }
    }

    @Test fun danglingPageMaskAndHistoryOwnersRemainInvalidWithAllRequiredPinsIntact() = runBlocking {
        for (kind in listOf("PAGE", "MASK", "RECIPE_HISTORY")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val owner = if (kind == "RECIPE_HISTORY") "recipe-v1/${f.pages[0].value}/2" else UUID.randomUUID().toString()
            f.database.runInTransaction {
                val q = f.database.catalog()
                q.insertReference(SourceReferenceRow(kind, owner, f.source, 0))
                assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            assertInvalidDirectOwner(f)
        }
    }

    @Test fun actualSqlHistoryLookupRejectsAliasesAndOverflowWithoutRejectingCanonicalLongMax() = runBlocking {
        for (revision in listOf("01", "+1", "1extra", "1/extra", "9223372036854775808")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val page = f.pages[0].value
            f.database.runInTransaction {
                val q = f.database.catalog()
                q.insertRecipe(q.recipe(page, 1)!!.copy(recipeRevision = Long.MAX_VALUE, parentRevision = 1))
                q.insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/$page/${Long.MAX_VALUE}", f.source, 0))
                assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            // The highest valid decimal Long must match; SQLite's saturating overflow cast alone must not let the next value alias it.
            assertEquals(RecoveryReport(1, 0, 0, 0, 0, emptyList()), RoomReferenceAudit(f.database).auditDirectOwners())
            f.database.runInTransaction {
                val q = f.database.catalog()
                q.insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/$page/$revision", f.source, 0))
                assertEquals(1, q.setSourceRefCount(f.source, q.countSourceReferences(f.source)))
            }
            assertInvalidDirectOwner(f)
            assertNotNull(f.database.catalog().recipe(page, Long.MAX_VALUE))
        }
    }

    @Test fun extraWrongSourceDirectPinsDoNotInvalidateOrReplaceGenuineOwnerPins() = runBlocking {
        for (kind in listOf("PAGE", "MASK", "RECIPE_HISTORY")) RoomRepositoryIntegrationTest.Fixture().use { f ->
            val owner = when (kind) {
                "MASK" -> protectionMask(f)
                "RECIPE_HISTORY" -> "recipe-v1/${f.pages[0].value}/1"
                else -> f.pages[0].value
            }
            lateinit var other: String
            f.database.runInTransaction {
                other = anotherSource(f)
                f.database.catalog().insertReference(SourceReferenceRow(kind, owner, other, 0))
            }
            assertInvalidDirectOwner(f, other)
        }
    }
}
