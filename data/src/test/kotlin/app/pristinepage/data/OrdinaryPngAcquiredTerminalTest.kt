package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.LeaseOwnerState
import app.pristinepage.data.repository.OrdinaryExportLifetime
import app.pristinepage.data.repository.RoomFreshExportAssembly
import app.pristinepage.data.repository.RoomTerminalPngReads
import app.pristinepage.data.OrdinaryPngTargetRetentionTest.TerminalStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private typealias FixtureReadback = TerminalStore.(File, PixelSize, String, Long, () -> Unit) -> RoomTerminalPngReads.ObservedIdentity?

class OrdinaryPngAcquiredTerminalTest {
    @Test fun acquiredSetInvocationAcceptsOnlyTheAcquiredClaim() {
        val entry = RoomTerminalPngReads.Retained::class.java.declaredMethods
            .filter { it.name.substringBefore('$') == "readAcquiredSet" && !it.isSynthetic }
        // Inspect the actual JVM signature only: do not call the baseline callback-bearing route or access a file.
        assertEquals("An acquired-set caller can select who receives the raw File",
            listOf(listOf(RoomFreshExportAssembly.Acquired::class.java, Continuation::class.java)),
            entry.map { it.parameterTypes.toList() })
    }

    @Test fun actualFirstAttemptReturnsOnlyCompleteDistinctEvidenceInFrozenOrder() = runTest {
        val calls = mutableListOf<String>()
        val f = fixture(cap = { size -> assertEquals(PixelSize(3, 2), size); calls += "cap"; 6L }) { file, size, raster, cap, checkpoint ->
            val index = if (file == final(0)) 0 else 1
            calls += "read $index"
            repeat(64) { checkpoint() }
            readback(index).verify(file, size, raster, cap, checkpoint)
        }
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val row = f.assemblyRow
            assertEquals("RUNNING", row.state)
            assertEquals(1L, row.attempt)
            assertEquals(1L, acquired.lease.generation)
            assertEquals(owner, row.leaseOwner)
            assertEquals(50L, row.leaseUntil)
            val baselineJobs = f.fullJobReads
            val baselinePages = f.pageReads
            val receipts = f.retained.readAcquiredSet(acquired)
            assertEquals(listOf("cap", "cap", "read 0", "read 1"), calls)
            assertEquals(listOf(id(60), id(61)), receipts.map { it.renderId.value })
            assertEquals(listOf(id(50), id(51)), receipts.map { it.transactionId.value })
            assertEquals(listOf(firstBytes, secondBytes), receipts.map { it.observed.byteSha256 })
            assertEquals(listOf(firstRaster, secondRaster), receipts.map { it.observed.rasterSha256 })
            assertEquals(listOf(4L, 4L), receipts.map { it.observed.byteCount })
            assertSame(row, f.assemblyRow)
            assertEquals("FROZEN", f.header.state)
            assertEquals(baselineJobs, f.fullJobReads)
            assertEquals(2, f.pageReads - baselinePages)
        }
        f.assertUnchanged()
    }

    @Test fun importOnlyConfigurationRefusesBeforeTerminalMetadataOrFileScope() = runTest {
        val f = fixture(readback = null)
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val before = f.pageReads
            val marker = File(f.root, "ownership/ordinary-derived-v1.lock")
            assertFalse(marker.exists())
            val failure = refuses(OperationErrorCode.PROVIDER_UNAVAILABLE) { f.retained.readAcquiredSet(acquired) }
            assertEquals("terminal_png_verifier_unavailable", failure.error.safeMessageKey)
            assertEquals(0, f.reads)
            assertEquals(before, f.pageReads)
            assertFalse(marker.exists())
        }
        f.assertUnchanged()
    }

    @Test fun swappedMissingAndNonordinaryBindingCannotReachItsVerifier() = runTest {
        for (mode in listOf("swapped", "missing", "descriptor")) {
            val seen = mutableListOf<File>()
            val f = fixture(swapped = mode == "swapped", nonordinary = mode == "descriptor") { file, size, raster, cap, checkpoint ->
                seen += file
                assertEquals(final(0), file)
                readback(0).verify(file, size, raster, cap, checkpoint)
            }
            f.owners.run { owner ->
                val acquired = f.acquire(owner)
                if (mode == "missing") f.missingOrdinal = 1
                // Keep the terminal reader's absent-job diagnostic distinct from an authenticated job with a mismatching binding.
                refuses(if (mode == "missing") OperationErrorCode.SOURCE_UNAVAILABLE else OperationErrorCode.INTEGRITY_FAILED) {
                    f.retained.readAcquiredSet(acquired)
                }
                assertEquals(if (mode == "missing") listOf(f.final(0)) else emptyList<File>(), seen)
            }
            f.assertUnchanged()
        }
    }

    @Test fun foreignLifetimeForeignStoreAndMetadataOnlyClaimsCannotEnterReadback() = runTest {
        val f = fixture()
        val foreign = fixture()
        f.owners.run { owner ->
            val acquired = f.acquire(owner, OrdinaryExportLifetime {})
            val baseline = f.reads
            refuses(OperationErrorCode.INTEGRITY_FAILED) { f.retained.readAcquiredSet(acquired) }
            assertEquals(baseline, f.reads)
        }
        foreign.owners.run { owner ->
            val acquired = foreign.acquire(owner, f.lifetime)
            refuses(OperationErrorCode.INTEGRITY_FAILED) { f.retained.readAcquiredSet(acquired) }
        }
        val unscoped = fixture()
        unscoped.owners.run { owner ->
            val acquired = unscoped.acquire(owner, null)
            refuses(OperationErrorCode.INTEGRITY_FAILED) { unscoped.retained.readAcquiredSet(acquired) }
            assertEquals(0, unscoped.reads)
        }
    }

    @Test fun endedRequesterCannotReadEvenWithExactRowAndUnexpiredClocks() = runTest {
        val f = fixture()
        val acquired = f.owners.run { owner -> f.acquire(owner) }
        assertEquals(LeaseOwnerState.ENDED, f.owners.state(acquired.lease.owner))
        f.owners.run { unrelated ->
            assertNotEquals(acquired.lease.owner, unrelated)
            refuses(OperationErrorCode.INVALID_INPUT) { f.retained.readAcquiredSet(acquired) }
        }
        assertEquals(0, f.reads)
    }

    @Test fun replacedRunningAttemptOrBoundInputRefusesWithoutFullProjection() = runTest {
        val f = fixture()
        f.owners.run { owner ->
            val acquired = f.acquire(owner)
            val row = f.assemblyRow
            val baseline = f.fullJobReads
            for (replacement in listOf(row.copy(attempt = 2), row.copy(leaseGeneration = 2), row.copy(leaseOwner = id(75)),
                row.copy(leaseUntil = 51), row.copy(state = "CHECKPOINTED"), row.copy(checkpoint = byteArrayOf(8)))) {
                f.assemblyRow = replacement
                refuses(OperationErrorCode.REVISION_CONFLICT) { f.retained.readAcquiredSet(acquired) }
            }
            // Mutate the in-store bytes in place: acquisition/preparation cannot borrow this array as authority.
            f.assemblyRow = row
            row.input[row.input.lastIndex] = 0
            refuses(OperationErrorCode.INTEGRITY_FAILED) { f.retained.readAcquiredSet(acquired) }
            assertEquals(baseline, f.fullJobReads)
            assertEquals(0, f.reads)
        }
    }

    @Test fun wallAndElapsedEqualityAreIndependentAtEntryAndAfterMetadata() = runTest {
        for (where in listOf("entry", "snapshot projection", "terminal journal")) for (wallClock in listOf(true, false)) {
            val f = fixture()
            f.owners.run { owner ->
                val acquired = f.acquire(owner)
                val expire = { if (wallClock) f.wall = 100L else f.elapsed = 50L }
                when (where) {
                    "entry" -> expire()
                    "snapshot projection" -> f.duringPages = expire
                    "terminal journal" -> f.duringTerminalJournal = expire
                }
                refuses(if (wallClock) OperationErrorCode.SESSION_EXPIRED else OperationErrorCode.REVISION_CONFLICT) {
                    f.retained.readAcquiredSet(acquired)
                }
                assertEquals(if (wallClock) 20L else 10L, if (wallClock) f.elapsed else f.wall)
                if (where != "terminal journal") assertEquals(0, f.reads)
            }
        }
    }

    @Test fun lateClockExpiryOrLeaseLossCannotDeliverAnySet() = runTest {
        for (what in listOf("wall", "elapsed", "running state", "last snapshot read")) {
            var calls = 0
            val f = fixture { file, size, raster, cap, checkpoint ->
                val ordinal = calls++
                readback(ordinal) {
                    if (ordinal == 1) when (what) {
                        "wall" -> wall = 100
                        "elapsed" -> elapsed = 50
                        "running state" -> assemblyRow = assemblyRow.copy(state = "CANCELED")
                        else -> duringPages = { wall = 100 }
                    }
                }.verify(file, size, raster, cap, checkpoint)
            }
            f.owners.run { owner ->
                val acquired = f.acquire(owner)
                val code = if (what == "wall" || what == "last snapshot read") OperationErrorCode.SESSION_EXPIRED
                    else OperationErrorCode.REVISION_CONFLICT
                refuses(code) { f.retained.readAcquiredSet(acquired) }
                assertEquals(2, calls)
                f.assertUnchanged()
            }
        }
    }

    @Test fun invalidOrTooSmallDimensionCapNeverBecomesAnUnboundedRead() = runTest {
        for ((cap, code) in listOf(0L to OperationErrorCode.INVALID_INPUT, 3L to OperationErrorCode.RESOURCE_LIMIT)) {
            val f = fixture(cap = { cap })
            f.owners.run { owner ->
                val acquired = f.acquire(owner)
                refuses(code) { f.retained.readAcquiredSet(acquired) }
                assertEquals(if (cap == 0L) 0 else 1, f.reads) // Only the positive limit reaches terminal metadata.
            }
        }
    }

    @Test fun oneLaneHoldsAcrossItemsAndCancellationUntilTheSynchronousReadUnwinds() = runTest {
        lateinit var cancelRead: () -> Unit
        var calls = 0
        val f = fixture { file, size, raster, cap, checkpoint ->
            val ordinal = calls++
            readback(ordinal) {
                if (ordinal == 1) cancelRead()
            }.verify(file, size, raster, cap, checkpoint)
        }
        val writer = RenderFileExecutionTest.RenderFileFixture(f.root).apply { initialize() }
        var delivered = false
        var canceled = false
        launch {
            val ownJob = checkNotNull(coroutineContext[Job])
            cancelRead = {
                ownJob.cancel()
                // The cancel flag cannot unlock the lane or let the issuing store close.
                runBlocking { writer.assertBusy() }
                assertEquals("ordinary_export_store_in_use",
                    assertThrows(RepositoryFailure::class.java) { f.lifetime.close() }.error.safeMessageKey)
            }
            try {
                f.owners.run { owner ->
                    val acquired = f.acquire(owner)
                    f.duringTerminalJournal = {
                        // On the second metadata projection, the first file inspection has already fully returned.
                        if (calls == 1) runBlocking { writer.assertBusy() }
                    }
                    f.retained.readAcquiredSet(acquired)
                    delivered = true
                }
            } catch (_: CancellationException) { canceled = true }
        }.join()
        assertEquals(2, calls)
        assertTrue(canceled)
        assertFalse(delivered)
        writer.execute()
        assertEquals("MOVED", writer.row.state)
        f.assertUnchanged()
        f.lifetime.close()
    }

    private suspend fun RenderFileExecutionTest.RenderFileFixture.assertBusy() {
        val before = row
        val reads = jobReads
        val failure = this@OrdinaryPngAcquiredTerminalTest.refuses(OperationErrorCode.REVISION_CONFLICT) {
            execute(writer = { throw AssertionError("Competing writer reached stage") },
                readback = { _, _ -> throw AssertionError("Competing writer reached readback") })
        }
        assertEquals("ordinary_derived_store_busy", failure.error.safeMessageKey)
        assertSame(before, row)
        assertEquals(reads, jobReads)
        assertFalse(checkNotNull(stage.parentFile).exists())
    }

    private suspend fun fixture(
        swapped: Boolean = false, nonordinary: Boolean = false, cap: (PixelSize) -> Long = { 6L },
        readback: FixtureReadback? = { _, _, _, _, _ -> throw AssertionError("Unauthorized verifier invocation") },
    ) = TerminalStore(
        Files.createTempDirectory(File("build").toPath(), "png-acquired-test-").toFile().canonicalFile,
        distinct = true, reverseReferences = swapped, nonordinary = nonordinary,
        verification = readback?.let { verify ->
            {
                RoomTerminalPngReads.AcquiredSetVerification(cap) { file, size, raster, limit, checkpoint ->
                    verify(this, file, size, raster, limit, checkpoint)
                }
            }
        },
    ).apply { initialize() }

    private suspend fun refuses(code: OperationErrorCode, block: suspend () -> Unit): RepositoryFailure {
        val failure = try { block(); throw AssertionError("Expected $code") } catch (e: RepositoryFailure) { e }
        assertEquals(failure.error.safeMessageKey, code, failure.error.code)
        return failure
    }

    private companion object {
        const val firstBytes = "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"
        const val secondBytes = "24afbf37c041e0439370a02db757117038dfe40bf0c4c446aad908da95c4aa31"
        const val firstRaster = "69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4"
        const val secondRaster = "8a372153929345d869029c667c187035a85324c0eaf23179290dab1b057c5f07"
        fun id(n: Int) = UUID(0, n.toLong()).toString()
    }
}
