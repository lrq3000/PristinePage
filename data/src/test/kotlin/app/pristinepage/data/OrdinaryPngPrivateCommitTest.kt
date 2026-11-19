package app.pristinepage.data

import app.pristinepage.data.OrdinaryPngTargetRetentionTest.TerminalStore
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.TransactionId
import app.pristinepage.data.repository.RoomFreshExportAssembly
import app.pristinepage.data.repository.RoomTerminalPngReads
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrdinaryPngPrivateCommitTest {
    @Test fun releasesExactlyTwoOrderedItemsOnlyAtCommitAndKeepsLaneThroughFinalRead() = runTest {
        val f = fixture()
        val peer = RenderFileExecutionTest.RenderFileFixture(f.root, TransactionId(UUID(0, 70).toString())).apply { initialize() }
        var fenced = false
        f.owners.run { owner ->
            val claim = f.acquire(owner)
            f.afterExportWrite = { row ->
                // The first MOVED audit is 10; the second must preserve its independently assigned 12 before co-commit.
                if (row.state == "MOVED" && row.ownerId.endsWith(":0")) f.wall = 12
            }
            f.afterAllMoves = {
                assertEquals(listOf(10L, 12L), f.observeCompletion().journals.map { it.updatedAt })
                f.wall = 17
            }
            f.duringFinalJobRead = {
                assertTrue(f.inTransaction)
                assertNull(f.published)
                assertEquals("PRIVATE_READY", f.header.state)
                assertEquals(listOf("COMMITTED", "COMMITTED"), f.exportRows.values.map { it.state })
                assertEquals(listOf(0, 1), f.artifacts.keys.toList())
                assertEquals(OperationErrorCode.REVISION_CONFLICT, refuse { f.lifetime.close() }.error.code)
                val reads = peer.jobReads
                val failure = runBlocking(Dispatchers.IO) {
                    refuse { peer.execute(writer = { error("Peer opened a sink") }, readback = { _, _ -> error("Peer decoded") }) }
                }
                assertEquals("ordinary_derived_store_busy", failure.error.safeMessageKey)
                assertEquals(reads, peer.jobReads)
                fenced = true
            }
            val running = f.assemblyRow
            val frozen = f.header
            f.complete(claim)
            assertTrue(fenced)
            f.duringFinalJobRead = {}
            val saved = f.observeCompletion()
            assertEquals(saved, f.published)
            assertEquals(frozen.copy(state = "PRIVATE_READY"), saved.header)
            assertSucceeded(running, saved.job, audit = 17)
            assertComplete(f, saved, audit = 17)
        }
        f.assertUnchanged()
        peer.execute()
        f.lifetime.close()
    }

    @Test fun exactSecondJournalCountAndHeaderCountRollbackAllCompletionWrites() = runTest {
        for (bad in listOf(0, 2)) for (journal in listOf(true, false)) {
            rollback("png_private_completion_${if (journal) "journal" else "header"}_${if (bad == 0) "conflict" else "count"}") { f ->
                fun priorWork() {
                    assertTrue(f.inTransaction)
                    assertNull(f.published)
                    assertEquals("COMMITTED", f.exportRows.values.first().state)
                    assertTrue(0 in f.artifacts)
                }
                if (journal) f.onJournalCommit = { if (it == 1) { priorWork(); bad } else 1 }
                else f.onHeaderCommit = { priorWork(); assertEquals(2, f.artifacts.size); bad }
            }
        }
    }

    @Test fun firstIntentSetRollsBackWhenSecondExactPostcheckRefuses() = runTest {
        val f = fixture()
        f.owners.run { owner ->
            val claim = f.acquire(owner)
            val original = f.observeCompletion()
            var firstWasWritten = false
            f.afterExportWrite = { row ->
                if (row.state == "INTENT" && row.ownerId.endsWith(":1")) {
                    assertTrue(f.inTransaction)
                    firstWasWritten = f.exportRows.values.first().state == "INTENT"
                    f.beforeRawExportRead = { it != row.ownerId }
                }
            }
            assertEquals(OperationErrorCode.INTEGRITY_FAILED, refuse { f.complete(claim) }.error.code)
            assertTrue(firstWasWritten)
            assertEquals(original, f.observeCompletion())
            assertFalse(File(f.root, "exports").exists())
        }
    }

    @Test fun refusesAuditAndTerminalExecutionDriftAfterExactCopies() = runTest {
        rollback("terminal_render_metadata_invalid") { f -> f.afterAllMoves = { f.terminalExecutionDrift = true } }
        rollback("png_export_item_phase_invalid") { f -> f.afterAllMoves = {
            val key = f.exportRows.keys.last()
            f.exportRows[key] = f.exportRows.getValue(key).copy(updatedAt = 77)
        } }
    }

    @Test fun exactArtifactPostcheckRejectsScalarMismatchAndAnExtraInventorySentinel() = runTest {
        rollback("png_private_completion_artifact") { f -> f.onArtifactInsert = { if (it.ordinal == 1) it.copy(pageCount = 2) else it } }
        rollback("png_private_completion_inventory") { f -> f.onHeaderCommit = { f.artifactIds += ""; 1 } }
    }

    @Test fun equalityAtTrailingWallOrElapsedDeadlineRollsBackAfterTheSuccessWrite() = runTest {
        for (wall in listOf(true, false)) rollback(if (wall) "export_snapshot_expired" else "job_lease_lost") { f ->
            f.duringFinalJobRead = {
                assertWritesInFlight(f)
                if (wall) f.wall = 100 else f.elapsed = 50
            }
        }
    }

    @Test fun cancellationInFinalReadRollsBackButCancellationAfterCommitLeavesDiscoverableSuccess() = runTest {
        for (afterCommit in listOf(false, true)) {
            val f = fixture()
            f.owners.run { owner ->
                val claim = f.acquire(owner)
                val frozen = f.header
                val running = f.assemblyRow
                var canceled = false
                var delivered = false
                val caller = launch {
                    val contextJob = coroutineContext[Job]!!
                    if (afterCommit) f.afterFinalCommit = {
                        assertFalse(f.inTransaction)
                        assertNotNull(f.published)
                        contextJob.cancel()
                    } else f.duringFinalJobRead = {
                        assertWritesInFlight(f)
                        contextJob.cancel()
                    }
                    try {
                        f.complete(claim)
                        delivered = true
                    } catch (_: CancellationException) {
                        canceled = true
                    }
                }
                caller.join()
                assertTrue(canceled)
                assertFalse(delivered)
                f.duringFinalJobRead = {}
                val saved = f.observeCompletion()
                if (afterCommit) {
                    assertEquals(saved, f.published)
                    assertEquals(frozen.copy(state = "PRIVATE_READY"), saved.header)
                    assertSucceeded(running, saved.job, audit = 10)
                    assertComplete(f, saved)
                } else {
                    assertNull(f.published)
                    assertEquals(frozen, saved.header)
                    assertEquals(running, saved.job)
                    assertTrue(saved.artifacts.isEmpty())
                    assertEquals(listOf("MOVED", "MOVED"), saved.journals.map { it.state })
                    assertFiles(f, saved.journals)
                }
            }
        }
    }

    private suspend fun rollback(reason: String, inject: (TerminalStore) -> Unit) {
        val f = fixture()
        f.owners.run { owner ->
            val claim = f.acquire(owner)
            lateinit var before: TerminalStore.SavedCompletion
            f.afterAllMoves = { before = f.observeCompletion() }
            inject(f)
            val change = f.afterAllMoves
            f.afterAllMoves = { change(); before = f.observeCompletion(); f.wall = 17 }
            assertEquals(reason, refuse { f.complete(claim) }.error.safeMessageKey)
            assertEquals(before, f.observeCompletion())
            assertNull(f.published)
            assertEquals(listOf("MOVED", "MOVED"), before.journals.map { it.state })
            assertTrue(before.artifacts.isEmpty())
            assertFiles(f, before.journals)
        }
        f.assertUnchanged()
    }

    private fun assertWritesInFlight(f: TerminalStore) {
        assertTrue(f.inTransaction)
        assertNull(f.published)
        assertEquals("PRIVATE_READY", f.header.state)
        assertEquals("SUCCEEDED", f.assemblyRow.state)
        assertEquals(listOf("COMMITTED", "COMMITTED"), f.exportRows.values.map { it.state })
        assertEquals(listOf(0, 1), f.artifacts.keys.toList())
    }

    private fun assertSucceeded(running: JobRow, saved: JobRow, audit: Long) {
        assertArrayEquals(running.input, saved.input)
        assertEquals(running.copy(state = "SUCCEEDED", leaseOwner = null, leaseUntil = null, updatedAt = audit),
            saved.copy(input = running.input))
    }

    private fun assertComplete(f: TerminalStore, saved: TerminalStore.SavedCompletion, audit: Long = 10) {
        assertEquals(listOf("COMMITTED", "COMMITTED"), saved.journals.map { it.state })
        assertEquals(listOf(0, 1), saved.artifacts.map { it.ordinal })
        for ((ordinal, artifact) in saved.artifacts.withIndex()) {
            val row = saved.journals[ordinal]
            assertEquals(saved.header.snapshotId, artifact.snapshotId)
            assertEquals("exports/${saved.header.snapshotId}/${artifact.artifactId}.bin", artifact.relativePath)
            assertEquals(artifact.relativePath, row.finalPath)
            assertEquals("image/png", artifact.mime)
            assertEquals("READY", artifact.state)
            assertEquals("ORDINARY_PNG_ENCODED_AND_RGB_V1", artifact.verification)
            assertEquals(1, artifact.pageCount)
            assertEquals(4L, artifact.byteCount)
            assertEquals(listOf("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",
                "24afbf37c041e0439370a02db757117038dfe40bf0c4c446aad908da95c4aa31")[ordinal], artifact.byteSha256)
            assertEquals(audit, artifact.createdAt)
            assertEquals(audit, row.updatedAt)
        }
        assertFiles(f, saved.journals)
    }

    private fun assertFiles(f: TerminalStore, rows: List<app.pristinepage.data.db.FileTransactionRow>) {
        assertFalse(f.inTransaction)
        for ((ordinal, row) in rows.withIndex()) {
            val bytes = if (ordinal == 0) byteArrayOf(1, 2, 3, 4) else byteArrayOf(19, 20, 21, 22)
            assertArrayEquals(bytes, File(f.root, checkNotNull(row.finalPath)).readBytes())
            val stage = File(f.root, checkNotNull(row.stagingPath))
            assertFalse(stage.exists())
            assertTrue(File(stage.parentFile, "derived-seal.json").isFile)
            assertNotEquals(f.renderJournal(ordinal).sealSha256, row.sealSha256)
        }
    }

    private suspend fun fixture(): TerminalStore {
        val root = Files.createTempDirectory(File("build").toPath(), "png-private-commit-test-").toFile().canonicalFile
        return TerminalStore(root, distinct = true, copyEnabled = true, verification = {
            RoomTerminalPngReads.AcquiredSetVerification({ 6L }) { file, size, raster, cap, check ->
                assertFalse(inTransaction)
                readback(if (file == final(0)) 0 else 1).verify(file, size, raster, cap, check)
            }
        }).apply { initialize() }
    }

    // Run the same test source on the unchanged base without a compile failure or calling the copy-only checkpoint.
    private suspend fun TerminalStore.complete(claim: RoomFreshExportAssembly.Acquired): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = retained.javaClass.declaredMethods.singleOrNull {
                it.name.substringBefore('$') == "completeAcquiredSet" &&
                    it.parameterTypes.toList() == listOf(RoomFreshExportAssembly.Acquired::class.java, Continuation::class.java)
            } ?: throw AssertionError("Missing retained private completion for the real acquired set")
            try { method.invoke(retained, claim, continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }

    private inline fun refuse(action: () -> Unit): RepositoryFailure {
        try { action() } catch (failure: RepositoryFailure) { return failure }
        throw AssertionError("Expected completion refusal")
    }
}
