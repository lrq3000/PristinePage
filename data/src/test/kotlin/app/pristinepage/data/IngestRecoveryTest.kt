package app.pristinepage.data

import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.files.DirectorySync
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Actual SourceStore interruptions/private seals/files and real local owner lifetime, with only Android DB/provider/inspection substituted. Unknown restarted owners are intentionally blocked; this does not invent cross-process death proof or recover an unsealed stream. */
class IngestRecoveryTest {
    private fun coordinator(f: SourceStoreTest.Fixture, owners: OperationOwners = f.owners, queries: IngestRecoveryQueries = f.db, journal: IngestPreparationQueries = f.db, resumer: BoundSourceIngest? = null): RecoveryCoordinator {
        val type = try { Class.forName("app.pristinepage.data.repository.RoomRecoveryCoordinator") }
        catch (error: ClassNotFoundException) { throw AssertionError("Admitted seal-backed ingest reconciliation is absent", error) }
        val db = f.db
        val completion = RoomIngestCompletion(db.metadata, db, db, db, db, f.files, { db.now }, { db.now }, Dispatchers.Unconfined)
        return try {
            val args = arrayOf(db.metadata, queries, db, journal, db, f.files, RoomIngestLookup(db.metadata, db, db, db), completion, completion, RoomIngestPhases(db.metadata, db, db, db, db, { db.now }, { db.now }), RoomIngestReadmission(db.metadata, db, db, db, owners, { db.now }), RoomJobLeases(db.metadata, db, owners, { db.now }, { db.now }, Dispatchers.Unconfined), owners, Dispatchers.Unconfined)
            val consuming = type.constructors.singleOrNull { it.parameterCount == 15 }
            if (resumer != null && consuming != null) consuming.newInstance(*args, resumer) as RecoveryCoordinator
            else type.constructors.single { it.parameterCount == 14 }.newInstance(*args) as RecoveryCoordinator
        }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private suspend fun sealAheadOfJournal(f: SourceStoreTest.Fixture) {
        f.db.rejectPhaseState = "SEALED"
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertEquals("COPYING", f.db.files.values.single().state)
        assertEquals(LeaseOwnerState.ENDED, f.owners.state(f.db.jobs.values.single().leaseOwner!!))
        f.db.rejectPhaseState = null
    }

    @Test fun validSealAheadOfCopyingJournalCommitsOnceAndTwoPassesAddNoDuplicates() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        val recovery = coordinator(f); val first = recovery.reconcile()
        assertEquals(1L, first.inspected); assertEquals(1L, first.resumed); assertEquals(1L, first.completed); assertEquals(0L, first.blocked)
        assertEquals("COMMITTED", f.db.files.values.single().state); assertEquals("SUCCEEDED", f.db.jobs.values.single().state)
        assertEquals(2L, f.db.jobs.values.single().leaseGeneration)
        val second = recovery.reconcile()
        assertEquals(0L, second.completed); assertEquals(0L, second.resumed)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
        assertEquals(2L, f.db.catalog.documents[f.db.document.value]!!.documentRevision)
        assertEquals(1, f.opened); assertEquals(1, f.closed); assertEquals(1, f.inspected)
    }

    @Test fun finalFileAheadOfSealedJournalIsVerifiedAndReusedWithoutAnotherProviderRead() = runTest {
        val f = SourceStoreTest.Fixture(); f.db.rejectPhaseState = "MOVED"
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertEquals("SEALED", f.db.files.values.single().state)
        val final = File(f.root, f.db.files.values.single().finalPath!!); assertArrayEquals(f.bytes, final.readBytes())
        f.db.rejectPhaseState = null
        assertEquals(1L, coordinator(f).reconcile().completed)
        assertArrayEquals(f.bytes, final.readBytes()); assertEquals(1, f.opened); assertEquals(1, f.db.catalog.pages.size)
    }

    @Test fun movedOriginalAfterMetadataFailureRetriesOnlyTheJointCommitUnderANewOwner() = runTest {
        val f = SourceStoreTest.Fixture(); f.db.catalog.failRecipeInsertAt = 1
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertEquals("MOVED", f.db.files.values.single().state)
        f.db.catalog.failRecipeInsertAt = null
        assertEquals(1L, coordinator(f).reconcile().completed)
        assertEquals(1, f.opened); assertEquals(1, f.inspected); assertEquals(1, f.db.catalog.pages.size)
        assertEquals(2L, f.db.jobs.values.single().leaseGeneration)
    }

    @Test fun unknownRestartedRegistryCannotReadmitEvenAValidSealedOriginal() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        val job = f.db.jobs.values.single(); val report = coordinator(f, LocalOperationOwners()).reconcile()
        assertEquals(1L, report.blocked); assertEquals(0L, report.resumed); assertEquals(0L, report.completed)
        assertSame(job, f.db.jobs.values.single()); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals("COPYING", f.db.files.values.single().state)
    }

    @Test fun liveOwnerCannotBeTakenOverEvenAfterItsElapsedDeadline() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        f.owners.run { active ->
            val job = f.db.jobs.values.single()
            f.db.jobs[job.jobId] = job.copy(leaseOwner = active, leaseGeneration = 2, leaseUntil = 1000)
            val report = coordinator(f).reconcile()
            assertEquals(1L, report.blocked); assertEquals(0L, report.resumed)
            assertEquals(active, f.db.jobs.values.single().leaseOwner); assertTrue(f.db.catalog.pages.isEmpty())
        }
    }

    @Test fun unsealedCopyRemainsPreservedAndBlockedRatherThanReopenedOrAssumedValid() = runTest {
        val f = SourceStoreTest.Fixture(); f.db.rejectPhaseState = "FAILED"
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.UNSUPPORTED_FORMAT, false, "synthetic_bad_input", f.db.child.value))
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Rejected)
        val row = f.db.files.values.single(); val stage = File(f.root, row.stagingPath!!); val before = stage.readBytes()
        assertEquals("COPYING", row.state)
        val report = coordinator(f).reconcile()
        assertEquals(1L, report.blocked); assertEquals(0L, report.completed)
        assertArrayEquals(before, stage.readBytes()); assertEquals(1, f.opened); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals(RecoveryAction.RESELECT, report.safeIssues.single().action)
    }

    @Test fun canceledDestinationIsNotResurrectedBySealReconciliation() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        f.db.sessions[f.db.parent.value] = f.db.sessions[f.db.parent.value]!!.copy(state = "CANCELED", errorCode = "CANCELED")
        val report = coordinator(f).reconcile()
        assertEquals(1L, report.blocked); assertEquals(0L, report.completed)
        assertTrue(f.db.catalog.pages.isEmpty()); assertEquals("COPYING", f.db.files.values.single().state)
        assertEquals(1, f.opened)
    }

    @Test fun changedSealedBytesArePreservedAndNeverPublishedOrCountedAsRecovered() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        val row = f.db.files.values.single(); val stage = File(f.root, row.stagingPath!!)
        stage.writeBytes(byteArrayOf(9, 9, 9, 9)) // Only this operation-owned synthetic fixture is modified.
        val report = coordinator(f).reconcile()
        assertEquals(1L, report.blocked); assertEquals(0L, report.completed)
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, report.safeIssues.single().error!!.code)
        assertArrayEquals(byteArrayOf(9, 9, 9, 9), stage.readBytes()); assertTrue(f.db.catalog.pages.isEmpty())
    }

    /** New independent reserved identities, not fake recovery results: all intents use the real canonical preparation command. Provider/inspection is never needed for these deliberately unfinished records. */
    private fun pendingChildren(f: SourceStoreTest.Fixture, count: Int) {
        val base = f.db.sessions[f.db.child.value]!!
        val baseParent = f.db.sessions[f.db.parent.value]!!
        val baseDocument = f.db.catalog.documents[f.db.document.value]!!
        repeat(count) {
            // Independent documents keep this store-wide scan above one document's100-page admission limit without bypassing the real reservation/preparation guards.
            val document = UUID.randomUUID().toString(); val parent = UUID.randomUUID().toString()
            f.db.catalog.documents[document] = baseDocument.copy(documentId = document)
            f.db.sessions[parent] = baseParent.copy(sessionId = parent, documentId = document)
            val child = ImportSessionId(UUID.randomUUID().toString())
            f.db.sessions[child.value] = base.copy(sessionId = child.value, parentSessionId = parent, documentId = document, selectionOrdinal = 0, plannedPageId = UUID.randomUUID().toString(), state = "BOUND", transactionId = null)
            f.db.commands.prepare(child, f.db.uri)
        }
    }

    @Test fun paginationPassesBlockedRecordsAndCompletesOneSealedOperationExactlyOnce() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f); pendingChildren(f, 33)
        val first = coordinator(f).reconcile()
        assertEquals(34L, first.inspected); assertEquals(1L, first.completed); assertEquals(1L, first.resumed); assertEquals(33L, first.blocked)
        assertEquals(33, first.safeIssues.size); assertEquals(33, first.safeIssues.map { it.operationId }.toSet().size)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
        val second = coordinator(f).reconcile()
        assertEquals(33L, second.inspected); assertEquals(33L, second.blocked); assertEquals(0L, second.completed)
        assertEquals(1, f.opened); assertEquals(1, f.db.catalog.pages.size)
    }

    @Test fun issueLimitDoesNotTruncateInspectedOrBlockedCountsAcrossPages() = runTest {
        val f = SourceStoreTest.Fixture(); pendingChildren(f, 130)
        val report = coordinator(f).reconcile()
        assertEquals(130L, report.inspected); assertEquals(130L, report.blocked); assertEquals(0L, report.completed)
        assertEquals(128, report.safeIssues.size); assertEquals(128, report.safeIssues.map { it.operationId }.toSet().size)
        assertEquals(0, f.opened); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun initialListingFailureThrowsTypedSafeStorageFailureRatherThanRawDatabaseText() = runTest {
        val f = SourceStoreTest.Fixture()
        val listing = object : IngestRecoveryQueries {
            override fun unfinishedIngestsAfter(transactionId: String, limit: Int): List<FileTransactionRow> = throw IOException("synthetic private database location")
        }
        try { coordinator(f, queries = listing).reconcile(); fail("Failed listing was reported as successful recovery") }
        catch (failure: RepositoryFailure) {
            assertEquals(OperationErrorCode.STORAGE_FAILED, failure.error.code); assertTrue(failure.error.retryable)
            assertNull(failure.error.affectedId); assertFalse(failure.message!!.contains("private"))
        }
        assertEquals(0, f.opened); assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun laterListingFailureDoesNotUndoEarlierCommitOrEscapeUnstructured() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        val listing = object : IngestRecoveryQueries {
            override fun unfinishedIngestsAfter(transactionId: String, limit: Int): List<FileTransactionRow> {
                if (transactionId.isNotEmpty()) throw IllegalStateException("synthetic private database details")
                return f.db.unfinishedIngestsAfter(transactionId, limit)
            }
        }
        try { coordinator(f, queries = listing).reconcile(); fail("Incomplete pass was reported as successful recovery") }
        catch (failure: RepositoryFailure) { assertEquals(OperationErrorCode.STORAGE_FAILED, failure.error.code); assertFalse(failure.message!!.contains("private")) }
        assertEquals(1, f.db.catalog.pages.size); assertEquals("COMMITTED", f.db.files.values.single().state)
        assertEquals(0L, coordinator(f).reconcile().completed); assertEquals(1, f.opened)
    }

    @Test fun listingCancellationPropagatesInsteadOfBecomingStorageFailure() = runTest {
        val f = SourceStoreTest.Fixture()
        val canceled = CancellationException("synthetic cancellation")
        val listing = object : IngestRecoveryQueries {
            override fun unfinishedIngestsAfter(transactionId: String, limit: Int): List<FileTransactionRow> = throw canceled
        }
        try { coordinator(f, queries = listing).reconcile(); fail("Cancellation became a recovery report") }
        catch (actual: CancellationException) { assertSame(canceled, actual) }
        assertTrue(f.db.catalog.pages.isEmpty())
    }

    @Test fun operationMetadataFailureIsSafelyReportedWithoutChangingOwnershipOrFiles() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        val prior = f.db.jobs.values.single(); val row = f.db.files.values.single()
        val stage = File(f.root, row.stagingPath!!); val bytes = stage.readBytes()
        val journal = object : IngestPreparationQueries by f.db {
            override fun fileTransaction(id: String): FileTransactionRow? = throw IOException("synthetic private transaction metadata")
        }
        val report = coordinator(f, journal = journal).reconcile()
        assertEquals(1L, report.blocked); assertEquals(0L, report.resumed); assertEquals(0L, report.completed)
        assertEquals(OperationErrorCode.STORAGE_FAILED, report.safeIssues.single().error!!.code)
        assertSame(prior, f.db.jobs.values.single()); assertArrayEquals(bytes, stage.readBytes()); assertEquals(1, f.opened)
    }

    @Test fun invalidOrNonadvancingListingFailsSafelyRatherThanLoopingOrLeakingRawChecks() = runTest {
        for (malformed in listOf(false, true)) {
            val f = SourceStoreTest.Fixture(); f.db.commands.prepare(f.db.child, f.db.uri)
            val row = f.db.files.values.single()
            val listing = object : IngestRecoveryQueries {
                override fun unfinishedIngestsAfter(transactionId: String, limit: Int): List<FileTransactionRow> = listOf(if (malformed) row.copy(transactionId = "synthetic private bad identifier") else row)
            }
            try { coordinator(f, queries = listing).reconcile(); fail("Invalid pagination was reported as a completed pass") }
            catch (failure: RepositoryFailure) {
                assertEquals(OperationErrorCode.INTEGRITY_FAILED, failure.error.code); assertNull(failure.error.affectedId)
                assertFalse(failure.message!!.contains("private"))
            }
            assertTrue(f.db.catalog.pages.isEmpty()); assertEquals(0, f.opened)
            assertEquals("PENDING", f.db.jobs.values.single().state)
        }
    }

    @Test fun nativeProofIsRefreshedOutsideMetadataBeforeReadmittingAnExitedForeignOwner() = runTest {
        val f = SourceStoreTest.Fixture(); sealAheadOfJournal(f)
        val proofRoot = File(Files.createTempDirectory(f.root.parentFile!!.toPath(), "proof-consumer-").toFile(), "store")
        val native = fileOwners(proofRoot)
        val monitored = object : OperationOwners by native, OperationOwnerProof {
            override suspend fun refresh(owner: String): LeaseOwnerState {
                assertFalse("Native ownership IO occurred under the Room writer", f.db.catalog.inTransaction)
                return (native as OperationOwnerProof).refresh(owner)
            }
        }
        FileOperationOwnersTest.OwnerChild(proofRoot).use { child ->
            val foreign = child.owner(); val row = f.db.jobs.values.single()
            f.db.jobs[row.jobId] = row.copy(leaseOwner = foreign)
            child.stopAbruptly()
            assertEquals(LeaseOwnerState.UNKNOWN, native.state(foreign))
            val report = coordinator(f, monitored).reconcile()
            assertEquals(1L, report.completed); assertEquals(1L, report.resumed); assertEquals(0L, report.blocked)
            assertEquals(LeaseOwnerState.ENDED, native.state(foreign))
            assertEquals(1, f.db.catalog.pages.size); assertEquals(2L, f.db.jobs.values.single().leaseGeneration)
            assertEquals(1, f.opened); assertEquals(0L, coordinator(f, monitored).reconcile().completed)
        }
    }

    private suspend fun sealedStorageRetry(afterMove: Boolean): SourceStoreTest.Fixture {
        var fired = false
        val f = SourceStoreTest.Fixture(DirectorySync { directory ->
            val boundary = File(directory, if (afterMove) "original.bin" else "seal.json")
            if (!fired && boundary.isFile) { fired = true; throw IOException("synthetic one-shot private directory sync failure") }
            true
        })
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        assertTrue("The real file protocol never reached the requested fault boundary", fired)
        assertEquals("RETRYABLE", f.db.files.values.single().state); assertEquals("RETRYABLE", f.db.jobs.values.single().state)
        assertNull(f.db.jobs.values.single().leaseOwner); assertNotNull(f.files.recover(f.db.files.values.single().transactionId, null))
        f.input = { throw SecurityException("Synthetic provider access removed after sealed copy") }
        return f
    }

    private suspend fun assertSealedRetryRecoveredWithoutRecopy(afterMove: Boolean) {
        val f = sealedStorageRetry(afterMove); val original = f.db.files.values.single()
        val report = coordinator(f).reconcile()
        assertEquals(1L, report.completed); assertEquals(1L, report.resumed); assertEquals(0L, report.blocked)
        assertEquals(1, f.db.files.size); assertEquals(1, f.db.jobs.size); assertEquals(1, f.opened)
        assertEquals("COMMITTED", f.db.files[original.transactionId]!!.state); assertEquals("SUCCEEDED", f.db.jobs.values.single().state)
        assertEquals(2L, f.db.jobs.values.single().leaseGeneration)
        assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.db.catalog.references.size)
        assertArrayEquals(f.bytes, File(f.root, original.finalPath!!).readBytes())
        assertEquals(0L, coordinator(f).reconcile().completed); assertEquals(1, f.db.catalog.pages.size)
    }

    @Test fun retryableSyncFailureAfterSealCreationRecoversTheValidBytesWithoutProviderAccess() = runTest {
        assertSealedRetryRecoveredWithoutRecopy(afterMove = false)
    }

    @Test fun retryableSyncFailureAfterFinalMoveRecoversTheSameAttemptWithoutAnotherCopy() = runTest {
        assertSealedRetryRecoveredWithoutRecopy(afterMove = true)
    }

    @Test fun refusedSealedRetryPhaseLeavesOwnerFreeJobAndJournalUnchangedUntilReadmissionSucceeds() = runTest {
        val f = sealedStorageRetry(afterMove = false); val row = f.db.files.values.single(); val job = f.db.jobs.values.single()
        f.db.rejectPhaseState = "SEALED"
        val blocked = coordinator(f).reconcile(); assertEquals(1L, blocked.blocked); assertEquals(0L, blocked.resumed)
        assertSame(row, f.db.files.values.single()); assertSame(job, f.db.jobs.values.single()); assertTrue(f.db.catalog.pages.isEmpty())
        f.db.rejectPhaseState = null
        assertEquals(1L, coordinator(f).reconcile().completed); assertEquals(1, f.opened)
    }

    @Test fun initialPendingIntentUsesItsBoundProviderOnlyAfterRecoveryReleasesCopyAdmission() = runTest {
        val f = SourceStoreTest.Fixture(); f.db.commands.prepare(f.db.child, f.db.uri)
        val handoff = object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult {
                assertFalse("Recovery would deadlock by recursively acquiring its copy permit", SourceCopyAdmission.mutex.isLocked)
                assertFalse(f.db.catalog.inTransaction)
                return f.store.ingestBoundProvider(boundReference, session)
            }
        }
        val report = coordinator(f, resumer = handoff).reconcile()
        assertEquals(1L, report.completed); assertEquals(1L, report.resumed); assertEquals(0L, report.blocked)
        assertEquals(1, f.opened); assertEquals(1, f.db.catalog.pages.size)
        assertEquals(0L, coordinator(f, resumer = handoff).reconcile().completed); assertEquals(1, f.opened)
    }

    @Test fun unsealedOwnerFreeRetryResumesThroughSourceStoreAndKeepsItsOldBytes() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true)
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_private_failure", f.db.child.value))
        assertTrue(f.store.ingestBoundProvider(f.db.uri, f.db.child) is IngestResult.Pending)
        val old = f.db.files.values.single(); val retained = File(f.root, old.stagingPath!!).readBytes(); f.inspectionFailure = null
        val report = coordinator(f, resumer = f.store).reconcile()
        assertEquals(1L, report.completed); assertEquals(1L, report.resumed)
        assertArrayEquals(retained, File(f.root, old.stagingPath).readBytes()); assertEquals(1, f.db.catalog.pages.size); assertEquals(2, f.opened)
        assertEquals("QUARANTINED", f.db.files[old.transactionId]!!.state)
    }

    @Test fun resumedProviderPermissionLossIsReportedAsReselectionWithoutPublishingAPage() = runTest {
        val f = SourceStoreTest.Fixture(); f.db.commands.prepare(f.db.child, f.db.uri)
        f.input = { throw SecurityException("synthetic private permission loss") }
        val report = coordinator(f, resumer = f.store).reconcile()
        assertEquals(1L, report.blocked); assertEquals(0L, report.completed); assertEquals(0L, report.resumed)
        val error = report.safeIssues.single().error
        assertNotNull("Recovery never attempted provider resumption or returned its typed permission failure", error)
        assertEquals(OperationErrorCode.PERMISSION_LOST, error!!.code)
        assertEquals(RecoveryAction.RESELECT, report.safeIssues.single().action)
        assertTrue(f.db.catalog.pages.isEmpty()); assertNull(f.db.sessions[f.db.child.value]!!.uriText)
    }

    @Test fun providerResumptionIsBoundedPerPassWithoutLosingRemainingPendingOperations() = runTest {
        val f = SourceStoreTest.Fixture(); pendingChildren(f, 17)
        val first = coordinator(f, resumer = f.store).reconcile()
        assertEquals(17L, first.inspected); assertEquals(16L, first.completed); assertEquals(16L, first.resumed); assertEquals(1L, first.blocked)
        assertEquals(16, f.opened); assertEquals(16, f.db.catalog.pages.size)
        val next = coordinator(f, resumer = f.store).reconcile()
        assertEquals(1L, next.completed); assertEquals(0L, next.blocked); assertEquals(17, f.opened); assertEquals(17, f.db.catalog.pages.size)
    }

    @Test fun runningIntentCannotUseItsProviderUntilTheRealOwnerScopeEnds() = runTest {
        val f = SourceStoreTest.Fixture(); val intent = f.db.commands.prepare(f.db.child, f.db.uri)
        val phases = RoomIngestPhases(f.db.metadata, f.db, f.db, f.db, f.db, { f.db.now }, { f.db.now })
        assertTrue(phases.admit(intent))
        f.owners.run { owner ->
            val leases = RoomJobLeases(f.db.metadata, f.db, f.owners, { f.db.now }, { f.db.now }, Dispatchers.Unconfined)
            assertTrue(leases.acquireAdmitted(intent.jobId, owner, 100) is AcquireLeaseResult.Granted)
            val blocked = coordinator(f, resumer = f.store).reconcile()
            assertEquals(1L, blocked.blocked); assertEquals(0L, blocked.resumed); assertEquals(0, f.opened)
        }
        assertEquals(1L, coordinator(f, resumer = f.store).reconcile().completed)
        assertEquals(1, f.opened); assertEquals(2L, f.db.jobs.values.single().leaseGeneration)
    }

    @Test fun providerHandoffCancellationPropagatesWithoutInventingARecoveryOutcome() = runTest {
        val f = SourceStoreTest.Fixture(); f.db.commands.prepare(f.db.child, f.db.uri)
        val canceled = CancellationException("synthetic resumption cancellation")
        val resumer = object : BoundSourceIngest { override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult = throw canceled }
        try { coordinator(f, resumer = resumer).reconcile(); fail("Canceled handoff became a successful recovery report") }
        catch (failure: CancellationException) { assertSame(canceled, failure) }
        assertEquals(0, f.opened); assertTrue(f.db.catalog.pages.isEmpty()); assertEquals("INTENT", f.db.files.values.single().state)
    }

    @Test fun repeatedPrivateFailureCannotRetryTheSameItemMoreThanOnceInOnePass() = runTest {
        val f = SourceStoreTest.Fixture(enableRetry = true); f.db.commands.prepare(f.db.child, f.db.uri)
        f.inspectionFailure = RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "synthetic_repeated_storage_failure", f.db.child.value))
        assertEquals(0L, coordinator(f, resumer = f.store).reconcile().completed); assertEquals(1, f.opened)
        assertEquals(0L, coordinator(f, resumer = f.store).reconcile().completed); assertEquals(2, f.opened)
        assertEquals(2, f.db.files.size); assertTrue(f.db.catalog.pages.isEmpty())
        assertEquals(1, f.db.files.values.count { it.state == "RETRYABLE" }); assertEquals(1, f.db.files.values.count { it.state == "QUARANTINED" })
    }

    private suspend fun assertHealthyItemNotStarved(expired: Boolean) {
        val f = SourceStoreTest.Fixture(); pendingChildren(f, 17)
        val ordered = f.db.files.values.sortedBy { it.transactionId }
        val healthy = ImportSessionId(ordered.last().ownerId)
        val unchanged = ordered.take(16)
        if (expired) for (row in unchanged) {
            val child = f.db.sessions[row.ownerId]!!
            f.db.sessions[row.ownerId] = child.copy(expiresAt = f.db.now)
        }
        var calls = 0
        val consumer = object : BoundSourceIngest {
            override suspend fun ingestBoundProvider(boundReference: String, session: ImportSessionId): IngestResult {
                calls++
                // Non-progressing adapters model an unavailable resource. Expired mode uses the real SourceStore rejection/preparation; both leave the old journal/input unchanged.
                return if (!expired && session != healthy) IngestResult.Pending(session, FileTransactionState.INTENT)
                else f.store.ingestBoundProvider(boundReference, session)
            }
        }
        val recovery = coordinator(f, resumer = consumer)
        assertEquals(0L, recovery.reconcile().completed); assertEquals(16, calls); assertEquals(0, f.opened)
        calls = 0
        val next = recovery.reconcile()
        assertEquals("An unchanged prefix consumed every later pass's handoff budget", 1L, next.completed)
        assertEquals(17L, next.inspected); assertEquals(16L, next.blocked); assertTrue(calls in 1..16)
        assertEquals(1, f.opened); assertEquals(listOf(healthy.value), f.db.catalog.pages.values.map { page -> f.db.sessions.values.single { it.plannedPageId == page.pageId }.sessionId })
        for (row in unchanged) { assertSame(row, f.db.files[row.transactionId]); assertEquals("PENDING", f.db.jobs.values.single { it.input.contentEquals(row.payload) }.state) }
        assertEquals(0L, recovery.reconcile().completed); assertEquals(1, f.opened)
    }

    @Test fun sixteenExpiredIntentsCannotStarveALaterHealthyItemAcrossPasses() = runTest {
        assertHealthyItemNotStarved(expired = true)
    }

    @Test fun sixteenUnchangedPendingHandoffsCannotStarveALaterHealthyItemAcrossPasses() = runTest {
        assertHealthyItemNotStarved(expired = false)
    }
}
