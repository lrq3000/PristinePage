package app.pristinepage.data

import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.ExportSnapshotQueries
import app.pristinepage.data.repository.RoomExportSnapshots
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExportAssemblyInputTest {
    @Test fun freezeStoresV1BytesInFrozenPageOrder() = runTest {
        val f = RepositoryFixture()
        val jobs = mutableListOf<JobRow>()
        val queries = Proxy.newProxyInstance(ExportSnapshotQueries::class.java.classLoader, arrayOf(ExportSnapshotQueries::class.java)) { _, method, args ->
            check(f.metadata.inTransaction)
            when (method.name) {
                "insertSnapshot", "insertExportPage" -> Unit
                "renderJobIdByKey" -> jobs.singleOrNull { it.idempotenceKey == args[0] }?.jobId
                "insertExportJob" -> { jobs += args.single() as JobRow; Unit }
                else -> error("Unexpected query: ${method.name}")
            }
        } as ExportSnapshotQueries
        val service = RoomExportSnapshots(f.metadata, queries, {}, { null }, "renderer-v1", "rgb8-v1", "reference", "binary64", { 1000L }, Dispatchers.Unconfined)
        val frozen = service.freezeExport(f.document, 1, settings(f.pageIds))
        val renderJobs = jobs.filter { it.kind == "PAGE_RENDER" }
        // The first render input field is the original page UUID. Use it to recover frozen order independently of render-job insertion order.
        val renderIdByPage = renderJobs.associate { job -> job.input.decodeToString(33, 69) to job.jobId }
        val orderedIds = f.pageIds.map { renderIdByPage.getValue(it.value) }
        assertNotEquals(orderedIds[0], orderedIds[1])
        assertEquals(f.pageIds, frozen.pages.map { it.pageId })
        val row = jobs.single { it.kind == "EXPORT_ASSEMBLY" }
        val expected = golden().also {
            it.overwriteText(36, frozen.snapshotId.value)
            it.overwriteText(76, frozen.snapshotHash)
            it.overwriteText(148, orderedIds[0])
            it.overwriteText(188, orderedIds[1])
        }
        assertArrayEquals(expected, row.input)
        assertEquals(sha(expected), row.canonicalInputSha256)
        assertEquals("export-assembly-v1/${frozen.snapshotId.value}", row.idempotenceKey)
        assertEquals(frozen.snapshotId.value, row.snapshotId)
        assertNull(row.pageOrdinal)
    }

    @Test fun encodeMatchesIndependentV1GoldenIncludingNulAndOrder() {
        val expected = golden()
        assertEquals("c6a4f09f42cd054c8effb96b444a76c81151995545411bea0263dcfb4cc31408", sha(expected))
        assertArrayEquals(expected, encode(ids))
    }

    @Test fun roundTripReturnsDetachedReferencesWithoutSortingOrInterpretingJobState() {
        val input = encode(ids)
        val row = row(input).copy(state = "CANCELED", errorCode = "CANCELED")
        val references = verify(row, snapshot())
        input.fill(0)
        assertEquals(ids, references)
    }

    @Test fun acceptsExactMinimumAndMaximumAndRefusesAnEncoderOutsideThoseBounds() {
        val last = List(100) { JobId("00000000-0000-0000-0000-${it.toString().padStart(12, '0')}") }.reversed()
        for (ordered in listOf(ids.take(1), last)) {
            val bytes = encode(ordered)
            assertEquals(144 + 40 * ordered.size, bytes.size)
            assertEquals(ordered, verify(row(bytes), snapshot(ordered.size)))
        }
        for (invalid in listOf(emptyList(), last + ids.first(), listOf(ids.first(), ids.first()))) {
            assertThrows(IllegalArgumentException::class.java) { encode(invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) { encode(ids, "A".repeat(64)) }
    }

    @Test fun rejectsWrongMaterializedRowBindingsAndStoredDigest() {
        val row = row(golden())
        val frozen = snapshot()
        val cases = mapOf(
            "job ID" to row.copy(jobId = alternateId),
            "kind" to row.copy(kind = "PAGE_RENDER"),
            "key" to row.copy(idempotenceKey = "export-assembly-v1/$alternateId"),
            "snapshot column" to row.copy(snapshotId = alternateId),
            "missing snapshot column" to row.copy(snapshotId = null),
            "ordinal" to row.copy(pageOrdinal = 0),
            "digest" to row.copy(canonicalInputSha256 = "f".repeat(64)),
            "noncanonical digest" to row.copy(canonicalInputSha256 = row.canonicalInputSha256.uppercase()),
        )
        for ((name, corrupt) in cases) assertIntegrity(name, corrupt, frozen)
    }

    @Test fun rejectsWrongPayloadSnapshotHashAndCountEvenWithMatchingStoredDigest() {
        val frozen = snapshot()
        assertIntegrity("payload snapshot ID", row(golden().also { it.overwriteText(36, alternateId) }), frozen)
        assertIntegrity("payload snapshot hash", row(golden().also { it.overwriteText(76, "b".repeat(64)) }), frozen)
        val validOneJobPayload = golden().copyOf(184).withInt(140, 1)
        assertIntegrity("valid one-job payload for two pages", row(validOneJobPayload), frozen)
    }

    @Test fun rejectsMalformedExtentsTagsAndNoncanonicalIdentitiesEvenWithMatchingStoredDigest() {
        val bytes = golden()
        val cases = mapOf(
            "empty BLOB" to byteArrayOf(),
            "oversized BLOB" to ByteArray(4145),
            "wrong tag terminator" to bytes.copyOf().also { it[31] = 1 },
            "negative UUID length" to bytes.withInt(32, -1),
            "huge hash length" to bytes.withInt(72, Int.MAX_VALUE),
            "wrong fixed job length" to bytes.withInt(184, 35),
            "zero count" to bytes.copyOf(144).withInt(140, 0),
            "count just over 100" to bytes.withInt(140, 101),
            "huge count" to bytes.withInt(140, Int.MAX_VALUE),
            "truncated" to bytes.copyOf(bytes.size - 1),
            "trailing byte" to bytes + byteArrayOf(0),
            "uppercase snapshot UUID" to bytes.copyOf().also { it.overwriteText(36, snapshotId.value.uppercase()) },
            "uppercase snapshot hash" to bytes.copyOf().also { it.overwriteText(76, snapshotHash.uppercase()) },
            "uppercase render UUID" to bytes.copyOf().also { it.overwriteText(188, ids[1].value.uppercase()) },
            "non-UUID" to bytes.copyOf().also { it[148] = 'g'.code.toByte() },
            "malformed UTF-8 in UUID" to bytes.copyOf().also { it[148] = 0xff.toByte() },
            "duplicate render job" to bytes.copyOf().also { it.overwriteText(188, ids[0].value) },
        )
        val frozen = snapshot()
        for ((name, corrupt) in cases) assertIntegrity(name, row(corrupt), frozen)
    }

    private fun assertIntegrity(name: String, row: JobRow, frozen: ExportSnapshot) {
        val failure = assertThrows(name, RepositoryFailure::class.java) { verify(row, frozen) }
        assertEquals(name, OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "export_assembly_input_invalid", assemblyId.value), failure.error)
    }

    private fun row(input: ByteArray) = JobRow(assemblyId.value, "EXPORT_ASSEMBLY", "export-assembly-v1/${snapshotId.value}", sha(input), snapshotId.value, null, input, "PENDING", 0, null, null, 0, null, null, 1000, 1000)

    private fun snapshot(count: Int = 2): ExportSnapshot {
        val f = RepositoryFixture(count)
        val source = f.metadata.sources.getValue(f.source.value)
        val blob = f.metadata.blobs.getValue(source.sourceBlobId)
        val recipe = RecipeCodec.encode(f.initial)
        val recipeHash = RecipeCodec.hash(f.initial)
        val pages = f.pageIds.mapIndexed { i, page ->
            ExportPageSnapshot(ExportPageRow(snapshotId.value, i, page.value, source.sourceId, blob.sourceBlobId, blob.byteSha256, source.pixelSha256, 3, 2, "srgb-v1", 1, 1, recipe, recipeHash, byteArrayOf(), byteArrayOf(), "0".repeat(64), "renderer-v1", 3, 2, 3, 2, "FAIL", null, null))
        }
        // The fixed digest is the expected snapshot identity at this materialized-row boundary; the producer case separately uses an actual frozen digest.
        return ExportSnapshot(snapshotId, f.document, 1, snapshotHash, settings(f.pageIds), pages, 1000)
    }

    private fun settings(pages: List<PageId>) = ExportSettings(ExportFormat.PNG, pages.map { PageOutputSettings(it, OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)) }, null, null, emptyList())

    private fun encode(jobs: List<JobId>, hash: String = snapshotHash): ByteArray = call("encode", snapshotId.value, hash, jobs) as ByteArray

    @Suppress("UNCHECKED_CAST")
    private fun verify(row: JobRow, frozen: ExportSnapshot): List<JobId> = call("verify", assemblyId.value, frozen, row) as List<JobId>

    private fun call(method: String, vararg args: Any): Any {
        val type = try { Class.forName("app.pristinepage.data.repository.ExportAssemblyInput") }
        catch (_: ClassNotFoundException) { throw AssertionError("Bounded export assembly input verification is absent") }
        return try {
            type.declaredMethods.single { it.name.substringBefore('-') == method }.invoke(type.getField("INSTANCE").get(null), *args)
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    private fun ByteArray.overwriteText(offset: Int, value: String) { value.encodeToByteArray().copyInto(this, offset) }
    private fun ByteArray.withInt(offset: Int, value: Int) = copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) }

    private val snapshotId = ExportSnapshotId("01234567-89ab-cdef-0123-456789abcdef")
    private val snapshotHash = "0123456789abcdef".repeat(4)
    private val assemblyId = JobId("abcdef01-2345-6789-abcd-ef0123456789")
    private val alternateId = "98765432-1000-0000-0000-000000000001"
    private val ids = listOf(JobId("fedcba98-7654-3210-fedc-ba9876543210"), JobId("00112233-4455-6677-8899-aabbccddeeff"))

    // Literal segments derived independently from the prior layout (not the helper): tag+NUL, 36/UUID, 64/hash, count=2, then 36/UUID twice.
    private fun golden() = (
        "7072697374696e65706167652d6578706f72742d617373656d626c792d763100" +
            "0000002430313233343536372d383961622d636465662d303132332d343536373839616263646566" +
            "0000004030313233343536373839616263646566303132333435363738396162636465663031323334353637383961626364656630313233343536373839616263646566" +
            "00000002" +
            "0000002466656463626139382d373635342d333231302d666564632d626139383736353433323130" +
            "0000002430303131323233332d343435352d363637372d383839392d616162626363646465656666"
        ).hexToByteArray()
}
