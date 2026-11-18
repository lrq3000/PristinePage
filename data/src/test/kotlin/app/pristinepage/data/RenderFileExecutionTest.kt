package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.db.*
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RenderFileExecutionTest {
    @Test fun committedCopyingPrecedesStreamingAndClosedReadbackThenMovesOnly() = runTest {
        val f = fixture()
        lateinit var borrowed: OutputStream
        f.execute(writer = {
            assertFalse(f.inTransaction)
            assertEquals("COPYING", f.row.state)
            borrowed = it
            it.write(bytes)
        }, readback = { file, _ ->
            assertFalse(f.inTransaction)
            assertEquals(f.stage, file)
            assertThrows(IOException::class.java) { borrowed.write(0) }
            assertArrayEquals(bytes, file.readBytes())
            assertFalse(f.seal.exists())
            pixels
        })
        assertEquals("MOVED", f.row.state)
        assertEquals(4L, f.row.expectedByteCount)
        assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", f.row.expectedByteSha256)
        assertArrayEquals(bytes, f.final.readBytes())
        assertFalse(f.stage.exists())
        assertEquals("RUNNING", f.job.state)
        assertEquals(f.lease.owner, f.job.leaseOwner)
    }

    @Test fun semanticRefusalDoesNotSealAndUnsealedCopyingCannotWriteAgain() = runTest {
        val f = fixture()
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.execute(readback = { _, _ -> null }) }
        f.cleanCopying()
        assertEquals(0, f.executeCountingRefusal(OperationErrorCode.INTEGRITY_FAILED))
        assertArrayEquals(bytes, f.stage.readBytes())
    }

    @Test fun verifiesTheSameClosedBytesOnBothSidesOfReadback() = runTest {
        val f = fixture()
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) {
            f.execute(readback = { file, _ ->
                // Deliberately violate the trusted read-only contract to exercise the post-readback byte fence.
                file.writeBytes(byteArrayOf(9, 2, 3, 4))
                pixels
            })
        }
        f.cleanCopying()
        assertArrayEquals(byteArrayOf(9, 2, 3, 4), f.stage.readBytes())
    }

    @Test fun explicitCapStopsWritesEvenWhenEncoderSwallowsTheFailure() = runTest {
        val f = fixture()
        f.refuses(OperationErrorCode.INVALID_INPUT) { f.execute(cap = 0) }
        assertEquals("INTENT", f.row.state)
        f.refuses(OperationErrorCode.RESOURCE_LIMIT) {
            f.execute(cap = 3, writer = {
                it.write(bytes, 0, 3)
                assertThrows(IOException::class.java) { it.write(4) }
            }, readback = { _, _ -> error("Unsealable prefix must not be decoded") })
        }
        f.cleanCopying()
        assertArrayEquals(bytes.copyOf(3), f.stage.readBytes())
    }

    @Test fun leaseLostWhileSealIsForcedDoesNotAdvanceCopying() = runTest {
        val f = fixture()
        f.onForce = { f.elapsed = 150 }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.execute() }
        assertEquals("COPYING", f.row.state)
        assertNull(f.row.sealSha256)
        assertTrue(f.seal.isFile)
        assertArrayEquals(bytes, f.stage.readBytes())
        assertFalse(f.final.exists())
    }

    @Test fun databaseLeaseLostDuringIOIsCaughtEvenWhenLocalLeaseSnapshotStaysLive() = runTest {
        val f = fixture()
        f.onForce = { f.job = f.job.copy(leaseGeneration = 2, attempt = 2, leaseOwner = id(99)) }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.execute() }
        assertEquals("COPYING", f.row.state)
        assertNull(f.row.sealSha256)
        assertTrue(f.seal.isFile)
        assertArrayEquals(bytes, f.stage.readBytes())
        assertEquals(2L, f.job.leaseGeneration)
    }

    @Test fun databaseLeaseLostDuringMoveSealForceCannotCreateDestinationDirectories() = runTest {
        val f = fixture()
        val targetParent = checkNotNull(f.final.parentFile)
        val renders = checkNotNull(targetParent.parentFile)
        val originalExpiry = f.job.leaseUntil
        var sealedRow: FileTransactionRow? = null
        var sealedBytes: ByteArray? = null
        f.onForce = {
            // The fresh seal is first forced in COPYING; invalidate only during the SEALED move re-verification.
            if (f.row.state == "SEALED") {
                assertNull(sealedRow)
                assertFalse(targetParent.exists())
                assertFalse(renders.exists())
                sealedRow = f.row
                sealedBytes = f.seal.readBytes()
                assertArrayEquals(bytes, f.stage.readBytes())
                f.replaceDatabaseLease()
            }
        }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.execute() }
        assertNotNull("Move-side seal force must reach the lease replacement", sealedRow)
        assertEquals(100L, f.elapsed)
        assertEquals(originalExpiry, f.job.leaseUntil)
        assertSame(sealedRow, f.row)
        assertEquals("SEALED", f.row.state)
        assertArrayEquals(bytes, f.stage.readBytes())
        assertArrayEquals(sealedBytes, f.seal.readBytes())
        assertFalse(f.final.exists())
        assertFalse("Stale attempt created the destination leaf directory", targetParent.exists())
        assertFalse("Stale attempt created the renders directory", renders.exists())
    }

    @Test fun databaseLeaseLostDuringDestinationSetupStillCannotRename() = runTest {
        val f = fixture()
        val targetParent = checkNotNull(f.final.parentFile)
        val renders = checkNotNull(targetParent.parentFile)
        var sealedRow: FileTransactionRow? = null
        var sealedBytes: ByteArray? = null
        f.onDirectorySync = { directory ->
            if (directory == renders) {
                assertTrue(targetParent.isDirectory)
                assertFalse(f.final.exists())
                assertEquals("SEALED", f.row.state)
                sealedRow = f.row
                sealedBytes = f.seal.readBytes()
                f.replaceDatabaseLease()
            }
        }
        f.refuses(OperationErrorCode.REVISION_CONFLICT) { f.execute() }
        assertNotNull("Authorized destination setup must reach directory sync", sealedRow)
        assertTrue(targetParent.isDirectory)
        assertSame(sealedRow, f.row)
        assertArrayEquals(bytes, f.stage.readBytes())
        assertArrayEquals(sealedBytes, f.seal.readBytes())
        assertFalse("Lease lost during directory setup must prevent rename", f.final.exists())
    }

    @Test fun runningCoroutineCanceledAfterMoveKeepsFinalWithoutPublishingMoved() = runTest {
        val f = fixture()
        var returned = false
        val worker = launch(start = CoroutineStart.LAZY, context = Dispatchers.Unconfined) {
            f.onFinalSync = { cancel() }
            f.execute()
            returned = true
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertFalse(returned)
        assertEquals("SEALED", f.row.state)
        assertArrayEquals(bytes, f.final.readBytes())
        assertFalse(f.stage.exists())
    }

    @Test fun laggingCopyingReplayForcesSealAndMovesWithoutReencoding() = runTest {
        val f = fixture()
        f.onForce = { throw IOException("failed seal force") }
        f.refuses(OperationErrorCode.STORAGE_FAILED) { f.execute() }
        assertEquals("COPYING", f.row.state)
        val seal = f.seal.readBytes()
        f.onForce = {}
        f.execute(writer = { error("No second writer") })
        assertEquals("MOVED", f.row.state)
        assertArrayEquals(seal, f.seal.readBytes())
        assertArrayEquals(bytes, f.final.readBytes())
        assertFalse(f.stage.exists())
    }

    @Test fun finalOnlyReplayRechecksSameSealAndIsIdempotentWithoutRewriting() = runTest {
        val f = fixture()
        f.leaveFinalOnly()
        val seal = f.seal.readBytes()
        f.execute(writer = { error("No second writer") })
        val moved = f.row
        assertEquals("MOVED", moved.state)
        f.execute(writer = { error("No second writer on MOVED replay") })
        assertSame(moved, f.row)
        assertArrayEquals(seal, f.seal.readBytes())
        assertArrayEquals(bytes, f.final.readBytes())
        assertFalse(f.stage.exists())
    }

    @Test fun losingExactPinAfterMovePreservesFinalButWithholdsMoved() = runTest {
        val f = fixture()
        f.onFinalSync = { f.pinOverride = emptyList() }
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.execute() }
        assertEquals("SEALED", f.row.state)
        assertArrayEquals(bytes, f.final.readBytes())
        assertFalse(f.stage.exists())
    }

    @Test fun postMoveCasReadbackMismatchRollsBackToSealedAndKeepsBytes() = runTest {
        val f = fixture()
        f.afterTransition = { if (f.row.state == "MOVED") f.row = f.row.copy(finalPath = "renders/${id(33)}/page.bin") }
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.execute() }
        assertEquals("SEALED", f.row.state)
        assertEquals(f.intent.finalPath, f.row.finalPath)
        assertArrayEquals(bytes, f.final.readBytes())
    }

    @Test fun postMoveCasAuditOnlyMismatchRollsBackToSealed() = runTest {
        val f = fixture()
        var observed: FileTransactionRow? = null
        f.afterTransition = {
            if (f.row.state == "MOVED") {
                observed = f.row
                f.row = f.row.copy(updatedAt = f.row.updatedAt + 1)
            }
        }
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) { f.execute() }
        assertNotNull(observed)
        assertEquals("SEALED", f.row.state)
        assertEquals(observed!!.sealSha256, f.row.sealSha256)
        assertArrayEquals(bytes, f.final.readBytes())
    }

    @Test fun movedReplayRefusesChangedSemanticHashEvenWithExactEncodedBytes() = runTest {
        val f = fixture()
        f.execute()
        val moved = f.row
        val seal = f.seal.readBytes()
        var decoded = false
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) {
            f.execute(writer = { error("No second writer") }, readback = { file, _ ->
                assertEquals(f.final, file)
                assertArrayEquals(bytes, file.readBytes())
                decoded = true
                "ac".repeat(32)
            })
        }
        assertTrue(decoded)
        assertSame(moved, f.row)
        assertArrayEquals(seal, f.seal.readBytes())
        assertArrayEquals(bytes, f.final.readBytes())
    }

    @Test fun recoveredCheckpointStopsAfterSealAuthenticationBeforeBadPayloadExtent() = runTest {
        val f = fixture()
        f.execute()
        f.final.writeBytes(bytes.copyOf(3))
        var authenticated = false
        val stop = kotlinx.coroutines.CancellationException("stop before opening recovered payload")
        val failure = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            f.files.recoverBound(f.intent.transactionId.value, f.row.sealSha256,
                app.pristinepage.data.files.DerivedFileTarget(app.pristinepage.data.files.DerivedKind.RENDER, f.intent.renderId.value), 4,
                requireFinal = true, checkpoint = { if (authenticated) throw stop },
                authenticate = { _, _, _ -> authenticated = true }, readback = { _, _ -> error("Must not decode") })
        }
        assertSame(stop, failure)
        assertTrue(authenticated)
        assertEquals("MOVED", f.row.state)
        assertArrayEquals(bytes.copyOf(3), f.final.readBytes())
    }

    @Test fun recoveryRejectsPersistedTargetAndProvenanceSubstitutionBeforeSemanticRead() = runTest {
        val f = fixture()
        f.onForce = { throw IOException("failed seal force") }
        f.refuses(OperationErrorCode.STORAGE_FAILED) { f.execute() }
        f.onForce = {}
        val seal = f.seal.readText()
        // A same-length valid UUID in a canonical seal is not this attempt's target.
        f.seal.writeText(seal.replace(f.intent.renderId.value, id(99)))
        assertEquals(0, f.executeCountingRefusal(OperationErrorCode.INTEGRITY_FAILED))
        val other = fixture()
        other.onForce = { throw IOException("failed seal force") }
        other.refuses(OperationErrorCode.STORAGE_FAILED) { other.execute() }
        val foreignMetadata = Regex("\"metadataBase64\":\"([^\"]+)\"").find(other.seal.readText())!!.groupValues[1]
        f.seal.writeText(seal.replace(Regex("(?<=\"metadataBase64\":\")[^\"]+"), foreignMetadata))
        assertEquals(0, f.executeCountingRefusal(OperationErrorCode.INTEGRITY_FAILED))
        assertEquals("COPYING", f.row.state)
        assertArrayEquals(bytes, f.stage.readBytes())
        assertFalse(f.final.exists())
    }

    @Test fun recoveredCapAndExactJournalProofAreRequiredBeforeReadback() = runTest {
        val f = fixture()
        f.leaveFinalOnly()
        val row = f.row
        assertEquals(0, f.executeCountingRefusal(OperationErrorCode.RESOURCE_LIMIT, cap = 3))
        for (bad in listOf(row.copy(expectedByteCount = 3), row.copy(expectedByteSha256 = pixels),
            row.copy(sealSha256 = pixels), row.copy(stagingPath = "staging/${id(99)}/derived.part"),
            row.copy(payload = row.payload.copyOf().apply { this[lastIndex] = 9 }))) {
            f.row = bad
            assertEquals(0, f.executeCountingRefusal(OperationErrorCode.INTEGRITY_FAILED))
            assertSame(bad, f.row)
        }
        assertArrayEquals(bytes, f.final.readBytes())
    }

    @Test fun laggingSealCapFailsBeforeInspectingWrongExtentPayload() = runTest {
        val f = fixture()
        f.onForce = { throw IOException("failed seal force") }
        f.refuses(OperationErrorCode.STORAGE_FAILED) { f.execute() }
        f.stage.writeBytes(bytes.copyOf(3))
        f.onForce = { error("Refused seal must not be forced") }
        assertEquals(0, f.executeCountingRefusal(OperationErrorCode.RESOURCE_LIMIT, cap = 3))
        assertEquals("COPYING", f.row.state)
        assertArrayEquals(bytes.copyOf(3), f.stage.readBytes())
    }

    @Test fun movedReplayRejectsAConflictingCopyAppearingDuringReadback() = runTest {
        val f = fixture()
        f.execute()
        val before = f.row
        val drift = byteArrayOf(4, 3, 2, 1)
        f.refuses(OperationErrorCode.INTEGRITY_FAILED) {
            f.execute(writer = { error("No second writer") }, readback = { file, _ ->
                assertEquals(f.final, file)
                f.stage.writeBytes(drift)
                pixels
            })
        }
        assertSame(before, f.row)
        assertArrayEquals(drift, f.stage.readBytes())
        assertArrayEquals(bytes, f.final.readBytes())
    }

    @Test fun movedReplayDoesNotRepairMissingFinalFromAnExactStage() = runTest {
        val f = fixture()
        f.execute()
        val before = f.row
        Files.move(f.final.toPath(), f.stage.toPath())
        assertEquals(0, f.executeCountingRefusal(OperationErrorCode.INTEGRITY_FAILED))
        assertSame(before, f.row)
        assertFalse(f.final.exists())
        assertArrayEquals(bytes, f.stage.readBytes())
    }

    @Test fun identicalCopiesAreKeptButNeitherHashNorLengthConflictCanProgress() = runTest {
        val f = fixture()
        f.leaveFinalOnly()
        for (conflict in listOf(byteArrayOf(9, 2, 3, 4), bytes.copyOf(3))) {
            f.stage.writeBytes(conflict)
            assertEquals(0, f.executeCountingRefusal(OperationErrorCode.INTEGRITY_FAILED))
            assertEquals("SEALED", f.row.state)
            assertArrayEquals(conflict, f.stage.readBytes())
            assertArrayEquals(bytes, f.final.readBytes())
        }
        f.stage.writeBytes(bytes)
        f.execute(writer = { error("No second writer") })
        assertEquals("MOVED", f.row.state)
        assertArrayEquals(bytes, f.stage.readBytes())
        assertArrayEquals(bytes, f.final.readBytes())
    }

    private suspend fun fixture() = RenderFileFixture().apply { initialize() }

    internal class RenderFileFixture {
        // Keep real .part/seal/final files for each fault witness without performing any external-store cleanup.
        private val root = Files.createTempDirectory(File("build").toPath(), "render-file-test-").toFile().canonicalFile
        private val repo = RepositoryFixture()
        val inTransaction get() = repo.metadata.inTransaction
        lateinit var proof: RoomFrozenRenderReads.Verified
        lateinit var job: JobRow
        lateinit var lease: JobLease
        lateinit var intent: RenderFileIntent
        private var stored: FileTransactionRow? = null
        var row: FileTransactionRow
            get() = checkNotNull(stored)
            set(value) { stored = value }
        var elapsed = 100L
        var ownerState = LeaseOwnerState.ALIVE
        var jobIsBounded = true
        var jobReads = 0
        var onPinRead: () -> Unit = {}
        var onLeaseWrite: (JobRow) -> Boolean = { true }
        var phaseCasAllowed: (FileTransactionRow) -> Boolean = { true }
        var pinOverride: List<String>? = null
        var afterTransition: () -> Unit = {}
        var onForce: () -> Unit = {}
        var onDirectorySync: (File) -> Unit = {}
        var onFinalSync: () -> Unit = {}
        val stage get() = File(root, intent.stagingPath)
        val seal get() = File(stage.parentFile, "derived-seal.json")
        val final get() = File(root, intent.finalPath)

        val records = mutableListOf<RenderedPageRow>()
        var onRecordInsert: () -> Unit = {}
        fun retainedPins() = repo.metadata.references.values.toList()
        fun clearJobPins() { repo.metadata.references.entries.removeAll { it.value.ownerType == "JOB" && it.value.ownerId == job.jobId } }
        private val metadata = object : MetadataAccess {
            override val queries: CatalogQueries = repo.metadata
            override fun <T> transaction(action: () -> T): T {
                val before = stored
                val beforeJob = if (::job.isInitialized) job else null
                val beforeRecords = records.toList()
                return try { repo.metadata.transaction(action) }
                catch (e: Throwable) {
                    stored = before
                    beforeJob?.let { job = it }
                    records.clear()
                    records.addAll(beforeRecords)
                    throw e
                }
            }
        }
        private val leases = object : JobLeaseQueries {
            override fun job(id: String): JobRow? { check(inTransaction); jobReads++; return job.takeIf { it.jobId == id } }
            override fun updateLease(row: JobRow, expectedGeneration: Long): Int {
                check(inTransaction)
                if (row.jobId != job.jobId || job.leaseGeneration != expectedGeneration) return 0
                job = row
                return if (onLeaseWrite(row)) 1 else 0
            }
        }
        private fun pins(id: String): List<String> {
            check(inTransaction)
            onPinRead()
            return pinOverride ?: repo.metadata.references.values.filter { it.ownerType == "JOB" && it.ownerId == id }.map { it.sourceId }
        }
        private fun current(id: String, attempt: Long) = stored?.takeIf { it.kind == "RENDER" && it.ownerId == id && it.attempt == attempt }
        private val port = object : RenderIntentQueries {
            override fun renderJobLeaseReadIsBounded(jobId: String): Boolean? { check(inTransaction); return jobIsBounded.takeIf { jobId == job.jobId } }
            override fun renderIntentReadIsBounded(jobId: String, attempt: Long, expectedPayloadBytes: Int): Boolean? {
                check(inTransaction)
                return current(jobId, attempt)?.let { it.payload.size == expectedPayloadBytes }
            }
            override fun renderIntentHasMalformedAttempts(jobId: String): Boolean { check(inTransaction); return false }
            override fun renderIntentRow(jobId: String, attempt: Long): FileTransactionRow? { check(inTransaction); return current(jobId, attempt) }
            override fun insertRenderIntent(row: FileTransactionRow) { check(inTransaction && stored == null); stored = row }
            override fun markRenderIntentCopying(transactionId: String, jobId: String, attempt: Long, updatedAt: Long): Int {
                check(inTransaction)
                val old = current(jobId, attempt) ?: return 0
                if (old.transactionId != transactionId || old.state != "INTENT") return 0
                row = old.copy(state = "COPYING", updatedAt = updatedAt)
                return 1
            }
            override fun advanceRenderFilePhase(previous: FileTransactionRow, next: FileTransactionRow): Int {
                check(inTransaction)
                val old = current(previous.ownerId, previous.attempt) ?: return 0
                if (!old.payload.contentEquals(previous.payload) || old.copy(payload = previous.payload) != previous) return 0
                if (!phaseCasAllowed(next)) return 0
                row = next
                afterTransition()
                return 1
            }
        }
        private val alive = LeaseOwnerLiveness { if (it == owner) ownerState else LeaseOwnerState.UNKNOWN }
        private var nextAudit = 1000L
        private val audit = { nextAudit++ }
        private val ids = listOf(id(20), id(21)).iterator()
        private val intents = RoomRenderIntents(metadata, leases, port,
            { id, size -> check(inTransaction); job.takeIf { it.jobId == id }?.let { it.input.size == size } },
            ::pins, alive, audit, { elapsed }, ids::next, Dispatchers.Unconfined)
        val files = DerivedFiles(root, DirectorySync { directory ->
            check(!inTransaction)
            onDirectorySync(directory)
            if (::intent.isInitialized && final.isFile) onFinalSync()
            true
        }, { channel -> check(!inTransaction); onForce(); channel.force(true) })
        val executor = RoomRenderFileExecution(intents, files, Dispatchers.Unconfined)
        val completionRows: RenderCompletionQueries = object : RenderCompletionQueries {
            override fun renderCompletionOccupied(renderId: String, jobId: String, cacheKey: String, relativePath: String): Boolean {
                check(inTransaction)
                return records.any { it.renderId == renderId || it.jobId == jobId || it.cacheKey == cacheKey || it.relativePath == relativePath }
            }

            override fun insertReadyRender(row: RenderedPageRow) {
                check(inTransaction && !renderCompletionOccupied(row.renderId, row.jobId, row.cacheKey, row.relativePath))
                records += row.copy(execution = row.execution.copyOf())
                onRecordInsert()
            }

            override fun readyRenderMatches(row: RenderedPageRow): Boolean {
                check(inTransaction)
                val stored = records.singleOrNull { it.jobId == row.jobId } ?: return false
                return stored.execution.contentEquals(row.execution) && stored.copy(execution = row.execution) == row
            }

            override fun commitMovedRender(previous: FileTransactionRow, updatedAt: Long): Int {
                check(previous.state == "MOVED")
                return port.advanceRenderFilePhase(previous, previous.copy(state = "COMMITTED", updatedAt = updatedAt))
            }
        }

        suspend fun initialize() {
            val jobs = mutableListOf<JobRow>()
            val snapshots = object : ExportSnapshotQueries {
                override fun insertSnapshot(row: ExportSnapshotRow) = Unit
                override fun insertExportPage(row: ExportPageRow) = Unit
                override fun renderJobByKey(key: String) = jobs.singleOrNull { it.idempotenceKey == key }
                override fun jobSourceReferences(jobId: String) = repo.metadata.references.values.filter { it.ownerType == "JOB" && it.ownerId == jobId }
                override fun insertExportJob(row: JobRow) { jobs += row }
                override fun snapshotMetadataIsBounded(snapshotId: String): Boolean? = error("Not a snapshot reader")
                override fun exportPagesAreBounded(snapshotId: String): Boolean = error("Not a snapshot reader")
                override fun snapshot(id: String): ExportSnapshotRow? = error("Not a snapshot reader")
                override fun exportPages(snapshotId: String): List<ExportPageRow> = error("Not a snapshot reader")
            }
            val freezer = RoomExportSnapshots(repo.metadata, snapshots, {}, { null }, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision, audit, Dispatchers.Unconfined)
            val frozen = freezer.freezeExport(repo.document, 1, ExportSettings(ExportFormat.PNG,
                repo.pageIds.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList()))
            val assembly = jobs.single { it.kind == "EXPORT_ASSEMBLY" }
            val id = ExportAssemblyInput.verify(JobId(assembly.jobId), frozen, assembly).first()
            job = jobs.single { it.jobId == id.value }.copy(state = "ADMITTED")
            proof = RoomFrozenRenderReads(metadata, { _, _ -> true },
                { FrozenRenderJobIdentityRow(job.jobId, job.kind, job.idempotenceKey, job.canonicalInputSha256, job.input) },
                ::pins, { true }, profile, Dispatchers.Unconfined).verified(id, frozen.pages.first())
            lease = (RoomJobLeases(metadata, leases, alive, audit, { elapsed }, Dispatchers.Unconfined)
                .acquireAdmitted(id, owner, 50) as AcquireLeaseResult.Granted).lease
            intent = intents.prepare(proof, lease)
        }

        suspend fun execute(cap: Long = 4, writer: (OutputStream) -> Unit = { it.write(bytes) },
            readback: (File, () -> Unit) -> String? = { file, checkpoint ->
                assertFalse(inTransaction)
                checkpoint()
                pixels.takeIf { file.readBytes().contentEquals(bytes) }
            }) {
            executor.execute(proof, lease, intent, cap, writer, readback)
        }

        suspend fun refuses(code: OperationErrorCode, action: suspend () -> Unit) {
            try { action(); fail("Expected $code") }
            catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code) }
        }

        suspend fun executeCountingRefusal(code: OperationErrorCode, cap: Long = 4): Int {
            var callbacks = 0
            refuses(code) { execute(cap, { callbacks++ }, { _, _ -> callbacks++; pixels }) }
            return callbacks
        }

        suspend fun leaveFinalOnly() {
            onFinalSync = { throw IOException("interrupted after atomic move") }
            refuses(OperationErrorCode.STORAGE_FAILED) { execute() }
            onFinalSync = {}
            assertEquals("SEALED", row.state)
            assertArrayEquals(bytes, final.readBytes())
            assertFalse(stage.exists())
        }

        fun cleanCopying() {
            assertEquals("COPYING", row.state)
            assertNull(row.sealSha256)
            assertFalse(seal.exists())
            assertFalse(final.exists())
        }

        fun replaceDatabaseLease() {
            // The original runner's local liveness and elapsed time are unchanged; only a fresh metadata fence notices this replacement.
            job = job.copy(leaseGeneration = 2, attempt = 2, leaseOwner = id(99))
        }
    }

    private companion object {
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        val bytes = byteArrayOf(1, 2, 3, 4)
        val pixels = "ab".repeat(32)
        fun id(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
        val owner = id(10)
    }
}
