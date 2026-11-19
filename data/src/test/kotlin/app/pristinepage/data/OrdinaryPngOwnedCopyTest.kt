package app.pristinepage.data

import app.pristinepage.data.OrdinaryPngTargetRetentionTest.TerminalStore
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.TransactionId
import app.pristinepage.data.repository.LeaseOwnerState
import app.pristinepage.data.repository.RoomPngExportItemWrites
import app.pristinepage.data.repository.RoomTerminalPngReads
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(RoomPngExportItemWrites.SyntheticJournal::class)
class OrdinaryPngOwnedCopyTest {
    @Test fun copiesExactFrozenBytesToDistinctUnpublishedMovesInOneRetainedScope() = runTest {
        val semantics = mutableListOf<Int>()
        val contenderProbes = mutableListOf<String>()
        lateinit var contender: RenderFileExecutionTest.RenderFileFixture
        val f = fixture { ordinal ->
            semantics += ordinal
            if (ordinal == 0) {
                assertStoreRetained()
                runBlocking(Dispatchers.IO) { contender.assertBusy() }
                contenderProbes += "first verification"
            }
        }
        contender = RenderFileExecutionTest.RenderFileFixture(f.root, TransactionId(id(70))).apply { initialize() }
        // Use the active verifier, the first inventory read, and the MOVED postcheck as separate witnesses.
        var freshChecks = 0
        f.beforeFreshCheck = {
            f.assertStoreRetained()
            if (freshChecks++ == 0) {
                assertEquals(listOf(0, 1), semantics)
                assertEquals(listOf(id(40), id(41)), f.terminalOrder)
                assertTrue(f.exportRows.isEmpty())
                runBlocking(Dispatchers.IO) { contender.assertBusy() }
                contenderProbes += "before intent"
            }
        }
        val writes = mutableListOf<String>()
        f.afterExportWrite = { row ->
            assertTrue(f.inTransaction)
            f.assertStoreRetained()
            assertEquals(listOf(0, 1), semantics)
            writes += row.state
        }
        f.duringExportSync = { directory ->
            assertFalse(f.inTransaction)
            f.assertStoreRetained()
            if (directory.name in f.exportRows.values.map { it.transactionId } && "copy" !in contenderProbes) {
                runBlocking(Dispatchers.IO) { contender.assertBusy() }
                contenderProbes += "copy"
            }
        }
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val running = f.assemblyRow
            val frozen = f.header
            f.retained.copyAcquiredForSyntheticTests(acquired)
            assertEquals("Both exact EXPORT copies must reach MOVED", listOf("MOVED", "MOVED"), f.exportRows.values.map { it.state })
            val rows = f.exportRows.values.toList()
            assertEquals(listOf("first verification", "before intent", "copy"), contenderProbes)
            assertEquals(listOf(0, 1), semantics)
            assertEquals(listOf("INTENT", "INTENT", "COPYING", "SEALED", "MOVED", "COPYING", "SEALED", "MOVED"), writes)
            assertEquals(2, rows.map { it.finalPath }.toSet().size)
            assertEquals(2, rows.map { it.stagingPath }.toSet().size)
            assertEquals(4, (rows.map { it.transactionId } + listOf(id(50), id(51))).toSet().size)
            rows.forEachIndexed { ordinal, row -> f.assertExactMove(ordinal, row, owner) }
            assertSame(running, f.assemblyRow)
            assertSame(frozen, f.header)
            assertTrue(f.artifactIds.isEmpty())
            assertEquals(0, f.closeCalls)
        }
        f.assertUnchanged()
        contender.execute()
        assertEquals("MOVED", contender.row.state)
        f.lifetime.close()
        assertEquals(1, f.closeCalls)
    }

    @Test fun refusesForeignClaimBeforeEitherStoreTouchesTheLaneOrMetadata() = runTest {
        val home = fixture()
        val foreign = fixture()
        foreign.owners.run { owner ->
            val acquired = foreign.acquire(owner)
            val readCounts = listOf(home.snapshotReads, foreign.snapshotReads)
            val jobs = listOf(home.assemblyRow, foreign.assemblyRow)
            assertEquals(home.prepared.snapshot.snapshotHash, foreign.prepared.snapshot.snapshotHash)
            assertRefused(OperationErrorCode.INTEGRITY_FAILED) { home.retained.copyAcquiredForSyntheticTests(acquired) }
            assertEquals(readCounts, listOf(home.snapshotReads, foreign.snapshotReads))
            for ((index, f) in listOf(home, foreign).withIndex()) {
                assertFalse(File(f.root, "ownership/ordinary-derived-v1.lock").exists())
                assertBeforeIntent(f, jobs[index].state)
                assertSame(jobs[index], f.assemblyRow)
                f.assertUnchanged()
            }
        }
    }

    @Test fun rejectsWrongSecondTerminalProofOrSameLengthCorruptionBeforeIntent() = runTest {
        for (binding in listOf(true, false)) {
            val verified = mutableListOf<Int>()
            val f = fixture { verified += it }
            f.owners.run { owner ->
                val acquired = f.acquire(owner)
                val corrupt = byteArrayOf(19, 20, 21, 23)
                if (binding) f.substituteSecondTerminal = true else f.final(1).writeBytes(corrupt)
                assertRefused(OperationErrorCode.INTEGRITY_FAILED) { f.retained.copyAcquiredForSyntheticTests(acquired) }
                assertEquals(listOf(0), verified)
                assertBeforeIntent(f)
                assertArrayEquals(tokens[0], f.final(0).readBytes())
                assertArrayEquals(if (binding) tokens[1] else corrupt, f.final(1).readBytes())
            }
        }
    }

    @Test fun rejectsNativeStyleSecondRgbMismatchBeforeIntent() = runTest {
        val f = fixture(wrongSecondRaster = true)
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            assertRefused(OperationErrorCode.INTEGRITY_FAILED) { f.retained.copyAcquiredForSyntheticTests(acquired) }
            assertBeforeIntent(f)
            f.assertUnchanged()
        }
    }

    @Test fun persistedAttemptReplacementAfterCopyingCommitRefusesBeforeStage() = runTest {
        val f = fixture()
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val original = f.assemblyRow
            val replacement = original.copy(attempt = 2L, leaseGeneration = 2L)
            var replacedAt: FileTransactionRow? = null
            f.afterCopyingCommit = { copying ->
                assertNull(replacedAt)
                val stage = File(f.root, checkNotNull(copying.stagingPath))
                assertFalse(checkNotNull(stage.parentFile).exists())
                assertSame(original, f.assemblyRow)
                f.assemblyRow = replacement
                replacedAt = copying
            }

            val failure = assertRefused(OperationErrorCode.REVISION_CONFLICT) { f.retained.copyAcquiredForSyntheticTests(acquired) }
            assertEquals("export_assembly_first_attempt_unavailable", failure.error.safeMessageKey)
            val copying = checkNotNull(replacedAt)
            assertFalse("Replacing the persisted attempt must not allow creation of its staging directory",
                checkNotNull(File(f.root, checkNotNull(copying.stagingPath)).parentFile).exists())
            f.assertStoppedInCopying(copying)
            assertSame(replacement, f.assemblyRow)
            assertEquals("FROZEN", f.header.state)
            f.assertCachedAuthorityNominal(owner, deadline = checkNotNull(original.leaseUntil))
        }
        f.assertUnchanged()
    }

    @Test fun persistedCancellationAtClosedStageSyncLeavesBytesUnsealed() = runTest {
        val f = fixture()
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val running = f.assemblyRow
            val frozen = f.header
            var canceledAt: FileTransactionRow? = null
            f.duringExportSync = { directory ->
                assertFalse(f.inTransaction)
                val copying = f.exportRows.values.first()
                val stage = File(f.root, checkNotNull(copying.stagingPath))
                if (canceledAt == null && copying.state == "COPYING" && directory == stage.parentFile && stage.isFile) {
                    assertArrayEquals(tokens[0], stage.readBytes())
                    assertFalse(File(directory, "derived-seal.json").exists())
                    f.cancelSnapshotFromIndependentTransaction()
                    canceledAt = copying
                }
            }

            val failure = assertRefused(OperationErrorCode.CANCELED) { f.retained.copyAcquiredForSyntheticTests(acquired) }
            assertEquals("export_snapshot_canceled", failure.error.safeMessageKey)
            val copying = checkNotNull(canceledAt)
            val stage = File(f.root, checkNotNull(copying.stagingPath))
            assertArrayEquals(tokens[0], stage.readBytes())
            assertFalse("A closed stage is not permission to seal a canceled snapshot", File(stage.parentFile, "derived-seal.json").exists())
            f.assertStoppedInCopying(copying)
            assertSame(running, f.assemblyRow)
            assertEquals(frozen.copy(state = "CANCELED"), f.header)
            f.assertCachedAuthorityNominal(owner, deadline = checkNotNull(running.leaseUntil))
        }
        f.assertUnchanged()
    }

    @Test fun cancellationAfterSecondRenameKeepsMovedBytesButCannotAdvanceTheJournal() = runTest {
        val f = fixture()
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val caller = launch {
                val job = coroutineContext[Job]!!
                f.duringExportSync = {
                    assertFalse(f.inTransaction)
                    val rows = f.exportRows.values.toList()
                    if (rows.size == 2 && rows[0].state == "MOVED" && rows[1].state == "SEALED" &&
                        File(f.root, checkNotNull(rows[1].finalPath)).exists()) job.cancel()
                }
                try {
                    f.retained.copyAcquiredForSyntheticTests(acquired)
                    fail("Cancellation at the rename/checkpoint boundary was not propagated")
                } catch (expected: CancellationException) {
                    assertTrue(job.isCancelled)
                }
            }
            caller.join()
            assertTrue(caller.isCancelled)
            val rows = f.exportRows.values.toList()
            assertEquals(listOf("MOVED", "SEALED"), rows.map { it.state })
            rows.forEachIndexed { ordinal, row -> f.assertExactMove(ordinal, row, owner) }
            assertEquals("RUNNING", f.assemblyRow.state)
            assertEquals("FROZEN", f.header.state)
            assertTrue(f.artifactIds.isEmpty())
        }
        f.assertUnchanged()
    }

    @Test fun scalarRefusalBeforeMovedCasLeavesAnExactUnpublishedFinal() = runTest {
        val f = fixture()
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            f.duringExportSync = {
                assertFalse(f.inTransaction)
                val row = f.exportRows.values.firstOrNull()
                if (row?.state == "SEALED" && File(f.root, checkNotNull(row.finalPath)).exists()) {
                    f.beforeRawExportRead = { key -> key != row.ownerId }
                }
            }
            assertRefused(OperationErrorCode.INTEGRITY_FAILED) { f.retained.copyAcquiredForSyntheticTests(acquired) }
            val rows = f.exportRows.values.toList()
            assertEquals(listOf("SEALED", "INTENT"), rows.map { it.state })
            f.assertExactMove(0, rows[0], owner)
            assertEquals("RUNNING", f.assemblyRow.state)
            assertEquals("FROZEN", f.header.state)
            assertTrue(f.artifactIds.isEmpty())
        }
        f.assertUnchanged()
    }

    private suspend fun fixture(
        wrongSecondRaster: Boolean = false,
        afterVerify: TerminalStore.(Int) -> Unit = {},
    ): TerminalStore {
        val root = Files.createTempDirectory(File("build").toPath(), "png-owned-copy-test-").toFile().canonicalFile
        return TerminalStore(root, distinct = true, copyEnabled = true, verification = {
            RoomTerminalPngReads.AcquiredSetVerification({ 6L }) { file, size, raster, cap, checkpoint ->
                val ordinal = if (file == final(0)) 0 else 1
                val actual = readback(ordinal).verify(file, size, raster, cap, checkpoint)!!
                afterVerify(ordinal)
                if (wrongSecondRaster && ordinal == 1) actual.copy(rasterSha256 = rasterHashes[0]) else actual
            }
        }).apply { initialize() }
    }

    private fun TerminalStore.assertExactMove(ordinal: Int, row: FileTransactionRow, owner: String) {
        val path = checkNotNull(row.finalPath)
        val artifact = UUID.fromString(path.substringAfterLast('/').removeSuffix(".bin")).toString()
        val producer = renderJournal(ordinal)
        val final = File(root, path)
        val stage = File(root, checkNotNull(row.stagingPath))
        val exportSeal = File(stage.parentFile, "derived-seal.json").readBytes()
        assertEquals("exports/${header.snapshotId}/$artifact.bin", path)
        assertEquals("staging/${row.transactionId}/derived.part", row.stagingPath)
        assertEquals("export-item-v1:${assemblyRow.jobId}:$ordinal", row.ownerId)
        assertEquals("EXPORT", row.kind)
        assertEquals(1L, row.attempt)
        assertTrue(artifact !in setOf(row.transactionId, id(60), id(61)))
        assertArrayEquals(tokens[ordinal], final.readBytes())
        assertFalse(stage.exists())
        assertEquals(byteHashes[ordinal], sha256(final.readBytes()))
        assertEquals(byteHashes[ordinal], row.expectedByteSha256)
        assertEquals(4L, row.expectedByteCount)
        assertEquals(sha256(exportSeal), row.sealSha256)
        assertNotEquals(producer.sealSha256, row.sealSha256)

        // Independent v1 serialization and a raw seal metadata extraction; never re-use the writer's intent builder.
        val expected = ByteBuffer.allocate(685)
        fun text(value: String) { expected.put(value.encodeToByteArray()) }
        text("pristinepage-ordinary-png-export-item-v1\u0000")
        text(header.snapshotId)
        text(header.snapshotSha256)
        text(assemblyRow.jobId)
        text(assemblyRow.canonicalInputSha256)
        text(owner)
        expected.putLong(1L).putLong(1L).putInt(ordinal).putInt(3).putInt(2)
        text(id(40 + ordinal))
        text(id(60 + ordinal))
        text(id(50 + ordinal))
        text(checkNotNull(producer.sealSha256))
        text(byteHashes[ordinal])
        expected.putLong(4L)
        text(rasterHashes[ordinal])
        text(artifact)
        text(row.transactionId)
        assertEquals(685, expected.position())
        assertArrayEquals(expected.array(), row.payload)
        val metadata = Regex("\"metadataBase64\":\"([^\"]+)\"").find(exportSeal.decodeToString())!!.groupValues[1]
        assertArrayEquals(expected.array(), Base64.getDecoder().decode(metadata))
    }

    private fun TerminalStore.assertStoreRetained() {
        val failure = try { lifetime.close(); throw AssertionError("Store closed while copy was retained") }
        catch (failure: RepositoryFailure) { failure }
        assertEquals("ordinary_export_store_in_use", failure.error.safeMessageKey)
    }

    private suspend fun RenderFileExecutionTest.RenderFileFixture.assertBusy() {
        val reads = jobReads
        val before = row
        val failure = assertRefused(OperationErrorCode.REVISION_CONFLICT) {
            execute(writer = { error("Competing writer opened a sink") }, readback = { _, _ -> error("Competing writer decoded") })
        }
        assertEquals("ordinary_derived_store_busy", failure.error.safeMessageKey)
        assertEquals(reads, jobReads)
        assertSame(before, row)
    }

    private fun assertBeforeIntent(f: TerminalStore, expectedJobState: String = "RUNNING") {
        assertTrue(f.exportRows.isEmpty())
        assertTrue(f.artifactIds.isEmpty())
        assertFalse(File(f.root, "exports").exists())
        assertEquals(setOf(id(50), id(51)), File(f.root, "staging").list()!!.toSet())
        assertEquals("FROZEN", f.header.state)
        assertEquals(expectedJobState, f.assemblyRow.state)
    }

    private fun TerminalStore.assertStoppedInCopying(copying: FileTransactionRow) {
        assertEquals(listOf("COPYING", "INTENT"), exportRows.values.map { it.state })
        assertSame(copying, exportRows.values.first())
        assertNull(copying.sealSha256)
        assertFalse(File(root, "exports").exists())
        assertTrue(artifactIds.isEmpty())
    }

    private suspend fun TerminalStore.assertCachedAuthorityNominal(owner: String, deadline: Long) {
        assertEquals(20L, elapsed)
        assertEquals(50L, deadline)
        assertEquals(10L, wall)
        assertEquals(100L, header.retainedUntil)
        assertEquals(LeaseOwnerState.ALIVE, owners.state(owner))
        currentCoroutineContext().ensureActive()
    }

    private suspend fun assertRefused(code: OperationErrorCode, action: suspend () -> Unit): RepositoryFailure {
        val failure = try { action(); throw AssertionError("Expected real owned-copy refusal ($code)") }
        catch (failure: RepositoryFailure) { failure }
        assertEquals(code, failure.error.code)
        return failure
    }

    private companion object {
        val tokens = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(19, 20, 21, 22))
        val byteHashes = listOf("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
            "24afbf37c041e0439370a02db757117038dfe40bf0c4c446aad908da95c4aa31")
        val rasterHashes = listOf("69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4",
            "8a372153929345d869029c667c187035a85324c0eaf23179290dab1b057c5f07")
        fun id(n: Int) = UUID(0, n.toLong()).toString()
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
