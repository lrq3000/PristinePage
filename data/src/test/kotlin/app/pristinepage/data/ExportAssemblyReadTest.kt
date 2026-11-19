package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.AssemblyJobIdentityRow
import app.pristinepage.data.repository.CatalogQueries
import app.pristinepage.data.repository.ExportSnapshotIdentity
import app.pristinepage.data.repository.MetadataAccess
import app.pristinepage.data.repository.RoomExportAssemblyReads
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExportAssemblyReadTest {
    @Test fun missingJobDoesNotProject() = runTest {
        val access = ReadAccess()
        assertFailure(OperationErrorCode.SOURCE_UNAVAILABLE, "export_assembly_job_unavailable") {
            load(access, { null })
        }
        assertEquals(listOf("begin", "guard", "end"), access.events)
    }

    @Test fun refusedStoredRowDoesNotProject() = runTest {
        val access = ReadAccess()
        assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid") {
            load(access, { false })
        }
        assertEquals(listOf("begin", "guard", "end"), access.events)
    }

    @Test fun admittedRowIsProjectedInTheGuardTransactionAndDecodesTheExactOrder() = runTest {
        val access = ReadAccess()
        val frozen = snapshot()
        val bytes = wireBytes(frozen)
        val result = load(access, { true }, frozen) { projection(frozen, bytes) }
        bytes.fill(0)
        assertEquals(references, result)
        assertEquals(listOf("begin", "guard", "projection", "end"), access.events)
    }

    @Test fun missingProjectionAfterAdmissionIsIntegrityFailureNotAbsence() = runTest {
        val access = ReadAccess()
        assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid") {
            load(access, { true }) { null }
        }
        assertEquals(listOf("begin", "guard", "projection", "end"), access.events)
    }

    @Test fun admittedProjectionMustBindItsJobIdToTheRequestedJob() = runTest {
        val frozen = snapshot()
        assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_input_invalid") {
            load(ReadAccess(), { true }, frozen) { projection(frozen, wireBytes(frozen), references.first().value) }
        }
    }

    @Test fun validWireAndDigestMustStillBindToTheAuthenticatedSnapshotHash() = runTest {
        val frozen = snapshot()
        assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_input_invalid") {
            load(ReadAccess(), { true }, frozen) { projection(frozen, wireBytes(frozen, "0".repeat(64))) }
        }
    }

    @Test fun cancellationFromTheGuardPropagatesWithoutProjection() = runTest {
        val access = ReadAccess()
        val cancellation = CancellationException("fixture cancellation")
        try {
            load(access, { throw cancellation })
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertEquals(listOf("begin", "guard", "end"), access.events)
    }

    @Test fun databaseExceptionIsRetryableAndDoesNotExposeItsMessage() = runTest {
        val access = ReadAccess()
        assertFailure(OperationErrorCode.STORAGE_FAILED, "export_assembly_job_read_failed", retryable = true) {
            load(access, { true }) { error("private SQLite details") }
        }
        assertEquals(listOf("begin", "guard", "projection", "end"), access.events)
    }

    @Test fun snapshotKeyReadReturnsAssemblyIdAndImmutableReferencesInFrozenOrder() = runTest {
        val access = ReadAccess()
        val frozen = snapshot()
        val bytes = wireBytes(frozen)
        val (assemblyId, ordered) = loadForSnapshot(access, frozen, matchedId = job.value, guard = { true }) {
            projection(frozen, bytes)
        }

        assertEquals(listOf("begin", "key", "guard", "projection", "end"), access.events)
        assertEquals(job, assemblyId)
        bytes.fill(0)
        assertEquals(references, ordered)
        assertThrows(UnsupportedOperationException::class.java) { (ordered as MutableList<JobId>).clear() }
    }

    @Test fun snapshotKeyMissAndMalformedMatchedIdStopBeforeAnyByIdRead() = runTest {
        val frozen = snapshot()
        val missing = ReadAccess()
        assertFailure(OperationErrorCode.SOURCE_UNAVAILABLE, "export_assembly_job_unavailable", affectedId = frozen.snapshotId.value) {
            loadForSnapshot(missing, frozen, matchedId = null)
        }
        assertEquals(listOf("begin", "key", "end"), missing.events)

        // The scalar returns "" for an unbounded ID; a 36-byte non-UUID can also pass its extent check.
        for (malformedId in listOf("", "g".repeat(36))) {
            val malformed = ReadAccess()
            assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid", affectedId = frozen.snapshotId.value) {
                loadForSnapshot(malformed, frozen, matchedId = malformedId)
            }
            assertEquals(listOf("begin", "key", "end"), malformed.events)
        }
    }

    @Test fun snapshotKeyMatchCannotAdoptForeignSnapshotKeyOrNonNullOrdinal() = runTest {
        val frozen = snapshot()
        val bytes = wireBytes(frozen)
        val foreign = "01234567-89ab-cdef-0123-456789abcdee"
        // Keep the digest, wire payload, selected JobId and bounded extents valid; change only one projected binding at a time.
        for (row in listOf(
            projection(frozen, bytes, rowSnapshotId = foreign),
            projection(frozen, bytes, key = "export-assembly-v1/$foreign"),
        )) {
            val access = ReadAccess()
            assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_input_invalid") {
                loadForSnapshot(access, frozen, matchedId = job.value, guard = { true }) { row }
            }
            assertEquals(listOf("begin", "key", "guard", "projection", "end"), access.events)
        }

        // The existing raw guard sees the non-NULL pageOrdinal, which the narrow projection intentionally does not expose.
        val ordinalRefused = ReadAccess()
        assertFailure(OperationErrorCode.INTEGRITY_FAILED, "export_assembly_job_metadata_invalid") {
            loadForSnapshot(ordinalRefused, frozen, matchedId = job.value, guard = { false })
        }
        assertEquals(listOf("begin", "key", "guard", "end"), ordinalRefused.events)
    }

    private class ReadAccess : MetadataAccess {
        val events = mutableListOf<String>()
        private var active = false
        override val queries: CatalogQueries get() = error("Assembly read must not use the catalog")
        override fun <T> transaction(action: () -> T): T {
            check(!active)
            active = true
            events += "begin"
            return try { action() } finally { active = false; events += "end" }
        }

        fun record(event: String, actual: String, expected: String) {
            check(active) { "$event ran outside the transaction" }
            assertEquals(event, expected, actual)
            events += event
        }
    }

    private suspend fun assertFailure(
        code: OperationErrorCode,
        key: String,
        retryable: Boolean = false,
        affectedId: String = job.value,
        action: suspend () -> Unit,
    ) {
        try {
            action()
            fail("Expected $code")
        } catch (failure: RepositoryFailure) {
            assertEquals(OperationError(code, retryable, key, affectedId), failure.error)
        }
    }

    private suspend fun load(
        access: ReadAccess,
        guard: () -> Boolean?,
        frozen: ExportSnapshot = snapshot(),
        project: () -> AssemblyJobIdentityRow? = { throw AssertionError("Unexpected projection") },
    ): List<JobId> = RoomExportAssemblyReads(
        access,
        { access.record("guard", it, job.value); guard() },
        { access.record("projection", it, job.value); project() },
        Dispatchers.Unconfined,
    ).load(job, frozen)

    private suspend fun loadForSnapshot(
        access: ReadAccess,
        frozen: ExportSnapshot,
        matchedId: String?,
        guard: () -> Boolean? = { throw AssertionError("Unexpected by-ID guard") },
        project: () -> AssemblyJobIdentityRow? = { throw AssertionError("Unexpected projection") },
    ): Pair<JobId, List<JobId>> = RoomExportAssemblyReads(
        access,
        { access.record("guard", it, job.value); guard() },
        { access.record("projection", it, job.value); project() },
        Dispatchers.Unconfined,
        jobIdByKey = { access.record("key", it, exactKey); matchedId },
    ).loadForSnapshot(frozen)

    private fun projection(
        frozen: ExportSnapshot,
        bytes: ByteArray,
        jobId: String = job.value,
        key: String = "export-assembly-v1/${frozen.snapshotId.value}",
        rowSnapshotId: String = frozen.snapshotId.value,
    ) = AssemblyJobIdentityRow(jobId, "EXPORT_ASSEMBLY", key,
        MessageDigest.getInstance("SHA-256").digest(bytes).toHexString(), rowSnapshotId, bytes)

    private fun snapshot(): ExportSnapshot {
        val f = RepositoryFixture()
        val source = f.metadata.sources.getValue(f.source.value)
        val blob = f.metadata.blobs.getValue(source.sourceBlobId)
        val settings = ExportSettings(ExportFormat.PNG, f.pageIds.map {
            PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT))
        }, null, null, emptyList())
        val rows = f.pageIds.mapIndexed { i, page ->
            ExportPageRow(snapshotId.value, i, page.value, source.sourceId, blob.sourceBlobId, blob.byteSha256,
                source.pixelSha256, 3, 2, source.conversionFingerprint, 1, 1, RecipeCodec.encode(f.initial),
                RecipeCodec.hash(f.initial), "[]".encodeToByteArray(), "{}".encodeToByteArray(),
                "0".repeat(64), "renderer-v1", 3, 2, 3, 2, "FAIL", null, null)
        }
        val identity = ExportSnapshotIdentity.create(f.document, 1, settings, rows)
        return ExportSnapshot(snapshotId, f.document, 1, identity.snapshotHash, settings, rows.map(::ExportPageSnapshot), 1000)
    }

    private fun wireBytes(frozen: ExportSnapshot, hash: String = frozen.snapshotHash): ByteArray =
        ByteBuffer.allocate(224).apply {
            put("pristinepage-export-assembly-v1\u0000".encodeToByteArray())
            putInt(36); put(frozen.snapshotId.value.encodeToByteArray())
            putInt(64); put(hash.encodeToByteArray())
            putInt(2)
            // Deliberately descending, so sorting or dropping a reference cannot satisfy the oracle.
            putInt(36); put("fedcba98-7654-3210-fedc-ba9876543210".encodeToByteArray())
            putInt(36); put("00112233-4455-6677-8899-aabbccddeeff".encodeToByteArray())
        }.array()

    private val snapshotId = ExportSnapshotId("01234567-89ab-cdef-0123-456789abcdef")
    private val exactKey = "export-assembly-v1/01234567-89ab-cdef-0123-456789abcdef"
    private val job = JobId("abcdef01-2345-6789-abcd-ef0123456789")
    private val references = listOf(JobId("fedcba98-7654-3210-fedc-ba9876543210"), JobId("00112233-4455-6677-8899-aabbccddeeff"))
}
