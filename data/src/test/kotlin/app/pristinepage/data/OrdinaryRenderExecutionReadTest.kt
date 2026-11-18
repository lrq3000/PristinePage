package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.pipeline.StageRecord
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.OrdinaryRenderExecution
import app.pristinepage.data.model.OrdinaryRenderExecutionRead
import app.pristinepage.data.model.OrdinaryRenderExecutionRead.SourceRelation
import app.pristinepage.data.model.RenderJobIdentity
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.FrozenRenderJobIdentityRow
import app.pristinepage.data.repository.RoomFrozenRenderReads
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrdinaryRenderExecutionReadTest {
    @Test fun readsIndependentV1Vector() = runTest {
        val bytes = golden()
        assertEquals(791, bytes.size)
        assertEquals(EXECUTION, digest(bytes))
        assertEquals(CACHE, cacheKey(EXECUTION))
        val read = OrdinaryRenderExecutionRead.read(bytes, proof(), EXECUTION, CACHE, RASTER, size)
        bytes.fill(0) // No alias to the caller's materialized BLOB.
        assertEquals(SourceRelation.EXACT_FROZEN_TUPLE, read.sourceRelation)
        assertProducer(read)
        assertEquals(EXECUTION, read.sha256)
        assertEquals(CACHE, read.cacheKey)
        assertEquals(RASTER, read.rasterSha256)
        assertEquals(size, read.size)
        assertEquals(stages(), read.stages)
        assertEquals(listOf("W3", "W1"), read.warnings)
    }

    @Test fun readsUnchangedWriterBytes() = runTest {
        val proof = proof()
        val image = RgbImage(3, 2, 9, ByteArray(18) { (it + 1).toByte() })
        val record = RenderResult(request(12), RenderStatus.OK, image, stages(), listOf("W3", "W1")).use {
            OrdinaryRenderExecution.capture(proof, it) {}
        }
        assertArrayEquals(golden(), record.bytes())
        val read = OrdinaryRenderExecutionRead.read(record.bytes(), proof, record.sha256, record.cacheKey, record.rasterSha256, record.size)
        assertEquals(stages(), read.stages)
    }

    @Test fun preservesTheProducerAcrossNormalizedReuseWithoutSubstitutingTheJobPin() = runTest {
        val consumer = proof(source = 31, blob = 32, byteHash = "c".repeat(64))
        assertEquals(id(21), consumer.pinSourceId)
        assertEquals(id(31), consumer.page.sourceId.value)
        val read = read(golden(), consumer)
        assertEquals(SourceRelation.NORMALIZED_COMPATIBLE_REUSE, read.sourceRelation)
        assertProducer(read)
        // A separate comparison to an exact consumer also cannot require its JOB pin as producer.
        assertEquals(SourceRelation.EXACT_FROZEN_TUPLE, read(golden(), proof()).sourceRelation)
    }

    @Test fun refusesRehashedSameExtentChangesToImmutableRequestBindings() = runTest {
        val proof = proof()
        for ((name, bytes) in mapOf(
            "pre-execution key" to golden().textAt(46, JOB.dropLast(1) + "7"),
            "page" to golden().textAt(129, id(15)),
            "source pixel digest" to golden().textAt(317, "e".repeat(64)),
            "revision" to golden().apply { ByteBuffer.wrap(this).putLong(381, 5) },
            "recipe digest" to golden().textAt(393, "e".repeat(64)),
            "requested dimensions" to golden().intAt(457, 2).intAt(461, 3),
            "purpose" to golden().textAt(469, "DETAIL"),
            "fallback" to golden().textAt(479, "PASS"),
            "failed status" to golden().textAt(487, "NO"),
            "actual dimensions" to golden().intAt(489, 2).intAt(493, 3),
        )) {
            assertEquals(name, 791, bytes.size)
            val key = if (name == "pre-execution key") JOB.dropLast(1) + "7" else JOB
            refuses(name) { read(bytes, proof, jobKeyForCache = key) }
        }
    }

    @Test fun refusesConflictingImmutableSourceOrBlobAliases() = runTest {
        val proof = proof()
        refuses("same Source claims a different Blob") { read(golden().textAt(209, id(16)), proof) }
        refuses("same Blob claims a different byte digest") { read(golden().textAt(169, id(15)).textAt(249, "e".repeat(64)), proof) }
    }

    @Test fun independentlyRefusesEachStoredDigestKeyOrRasterDisagreement() = runTest {
        val bytes = golden()
        val proof = proof()
        val otherDigest = "e".repeat(64)
        refuses("execution digest with a matching cache key for the wrong digest") {
            OrdinaryRenderExecutionRead.read(bytes, proof, otherDigest, cacheKey(otherDigest), RASTER, size)
        }
        refuses("cache key alone") {
            OrdinaryRenderExecutionRead.read(bytes, proof, EXECUTION, cacheKey(otherDigest), RASTER, size)
        }
        refuses("stored raster digest alone") {
            OrdinaryRenderExecutionRead.read(bytes, proof, EXECUTION, CACHE, otherDigest, size)
        }
        refuses("stored raster dimensions alone") {
            OrdinaryRenderExecutionRead.read(bytes, proof, EXECUTION, CACHE, RASTER, PixelSize(2, 3))
        }
    }

    @Test fun refusesNoncanonicalIdsDigestsAndTokenBytesEvenWhenRehashed() = runTest {
        val proof = proof()
        for ((name, bytes) in mapOf(
            "tag" to golden().apply { this[41] = 1 },
            "source UUID uppercase" to golden().textAt(169, id(12).uppercase()),
            "blob UUID not opaque" to golden().textAt(209, "g".repeat(36)),
            "source digest uppercase" to golden().textAt(249, "B".repeat(64)),
            "raster digest uppercase" to golden().textAt(727, RASTER.uppercase()),
            "non-ASCII high octet" to golden().apply { this[505] = 0xff.toByte() },
            "embedded NUL" to golden().apply { this[699] = 0 },
            "non-ASCII UTF-8" to golden().apply { this[715] = 0xc3.toByte(); this[716] = 0xa9.toByte() },
        )) {
            // Make the stored raster field agree in the malformed-raster case so equality is not the refusal.
            refuses(name) { read(bytes, proof, raster = if (name == "raster digest uppercase") RASTER.uppercase() else RASTER) }
        }
    }

    @Test fun refusesInvalidLengthsCountsAndPhysicalEofWhenRehashed() = runTest {
        val proof = proof()
        for ((name, bytes) in mapOf(
            "zero token length" to golden().intAt(501, 0),
            "negative token length" to golden().intAt(501, -1),
            "token too long" to golden().intAt(501, 129),
            "large token length" to golden().intAt(501, Int.MAX_VALUE),
            "truncated token with in-range length" to golden().copyOf(510),
            "truncated numeric field" to golden().copyOf(385),
            "too few stages" to golden().intAt(497, 2),
            "too many stages" to golden().intAt(497, 17),
            "too many stage warnings" to golden().intAt(547, 17),
            "negative result warnings" to golden().intAt(707, -1),
            "too many result warnings" to golden().intAt(707, 17),
            "truncated raster hash" to golden().copyOf(790),
            "trailing bytes" to (golden() + byteArrayOf(0)),
        )) refuses(name) { read(bytes, proof) }
    }

    @Test fun refusesBadSuccessChainEvenWhenRehashed() = runTest {
        val proof = proof()
        val bytes = golden()
        for ((name, changed) in mapOf(
            "nonpositive encoded input" to golden().intAt(531, 0),
            "unexpected first stage output" to golden().intAt(539, 2).intAt(543, 3).intAt(622, 2).intAt(626, 3),
            "later input differs from previous output" to golden().intAt(622, 2),
            "unexpected last stage output" to golden().intAt(683, 2).intAt(687, 3),
            "out of encoded order" to (bytes.copyOfRange(0, 501) + bytes.copyOfRange(577, 642) + bytes.copyOfRange(501, 577) + bytes.copyOfRange(642, 791)),
            "nonpositive stage version" to golden().intAt(597, 0),
            "unsuccessful outcome" to golden().textAt(529, "NO"),
        )) refuses(name) { read(changed, proof) }
    }

    @Test fun preservesEncodedStageFactsWithoutDerivingThemFromTheRecipe() = runTest {
        val bytes = golden().textAt(505, "DecodeActual.v-2").intAt(521, 27).intAt(531, 23)
            .textAt(699, "Z1").textAt(715, "W1").textAt(721, "W3")
        val read = read(bytes, proof())
        assertEquals(StageRecord("DecodeActual.v-2", 27, null, "OK", PixelSize(23, 3), size, listOf("SOURCE_PIXELS_VERIFIED")), read.stages[0])
        assertEquals(listOf("Z1", "W1"), read.stages[2].warningCodes)
        assertEquals(listOf("W1", "W3"), read.warnings)
    }

    @Test fun readsWriterAtCombinedLimitsAndRefusesOneExtraByte() = runTest {
        val proof = proof()
        val stageId = "Z".repeat(128)
        val warnings = List(16) { ("w$it:").padEnd(128, 'X') }
        val stages = List(16) { StageRecord(stageId, Int.MAX_VALUE, null, "EXACT_PERMUTATION", size, size, warnings) }
        val image = RgbImage(3, 2, 9, ByteArray(18) { (it + 1).toByte() })
        val record = RenderResult(request(12), RenderStatus.OK, image, stages, warnings).use {
            OrdinaryRenderExecution.capture(proof, it) {}
        }
        val bytes = record.bytes()
        assertEquals(39309, bytes.size)
        assertEquals(stages, read(bytes, proof).stages)
        refuses("beyond the maximum v1 envelope") { read(bytes + byteArrayOf(0), proof) }
    }

    private fun assertProducer(read: OrdinaryRenderExecutionRead) {
        assertEquals(SourceId(id(12)), read.unprovenProducer.sourceId)
        assertEquals(SourceBlobId(id(13)), read.unprovenProducer.blobId)
        assertEquals("b".repeat(64), read.unprovenProducer.byteSha256)
    }

    private fun read(bytes: ByteArray, proof: RoomFrozenRenderReads.Verified, jobKeyForCache: String = JOB, raster: String = RASTER): OrdinaryRenderExecutionRead {
        val hash = digest(bytes)
        return OrdinaryRenderExecutionRead.read(bytes, proof, hash, cacheKey(hash, jobKeyForCache), raster, size)
    }

    private fun refuses(case: String, read: () -> Unit) {
        val failure = assertThrows(case, RepositoryFailure::class.java) { read() }
        assertEquals(case, OperationErrorCode.INTEGRITY_FAILED, failure.error.code)
    }

    private suspend fun proof(source: Int = 12, blob: Int = 13, byteHash: String = "b".repeat(64)): RoomFrozenRenderReads.Verified {
        val page = ExportPageSnapshot(ExportPageRow(id(1), 0, id(11), id(source), id(blob), byteHash, pixels,
            3, 2, "srgb-v1", 4, 1, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe), "[]".encodeToByteArray(),
            profile.providerDescriptor(), OrdinaryExportProfile.emptyAssetSetHash, "renderer-v1", 3, 2, 3, 2, "FAIL", null, null))
        val identity = RenderJobIdentity.create(request(source), emptyMap(), OrdinaryExportProfile.emptyAssetSetHash,
            "srgb-v1", "renderer-v1", "rgb8-v1", "reference", "binary64")
        assertEquals(JOB, identity.idempotenceKey)
        val job = FrozenRenderJobIdentityRow(id(14), "PAGE_RENDER", identity.idempotenceKey, identity.canonicalInputSha256, identity.canonicalInput)
        val metadata = MemoryMetadata().apply {
            // The original JOB pin can belong to neither the producer nor the consuming page.
            sources[id(21)] = SourceRow(id(21), id(22), pixels, 3, 2, 1, "srgb-v1", null, 1, "READY", 0)
            blobs[id(22)] = SourceBlobRow(id(22), "d".repeat(64), 9, "image/png", 3, 2, "sources/${id(22)}/original.bin", "READY", 1, id(23), 0)
        }
        return RoomFrozenRenderReads(metadata, { _, extent -> extent == job.input.size }, { job }, { listOf(id(21)) },
            metadata::sourceReadIsBounded, profile, Dispatchers.Unconfined).verified(JobId(job.jobId), page)
    }

    private fun request(source: Int) = RenderRequest(id(source), pixels, id(11), 4, recipe, OutputSpec(size, RenderPurpose.EXPORT), 0, null, FallbackPolicy.FAIL)

    private companion object {
        const val JOB = "page-render-v1:65d21cea1db18e39b36b97a22bd403fe3398135ae69f7defda437f5ca55abec6"
        const val EXECUTION = "6b5e82b5f7f7939fa67f57d0c9b1090a93807be035549904ad9c9464cf4bf065"
        const val CACHE = "render-cache-v1:2d64c0a4ac5078042f43e8f2ffa9cf70b5ddbb479f291fd465bf3127721f5742"
        const val RASTER = "69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4"
        val size = PixelSize(3, 2)
        val pixels = "a".repeat(64)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(pixels, size, "srgb-v1")))
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")
        fun id(n: Int) = UUID(0L, n.toLong()).toString()
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun cacheKey(hash: String, job: String = JOB) = "render-cache-v1:" +
            digest(("pristinepage-ordinary-render-cache-v1\u0000" + job + hash).encodeToByteArray())
        fun ByteArray.intAt(offset: Int, n: Int) = apply { ByteBuffer.wrap(this).putInt(offset, n) }
        fun ByteArray.textAt(offset: Int, text: String) = apply { text.encodeToByteArray().copyInto(this, offset) }
        fun stages() = listOf(
            StageRecord("source_decode_v1", 1, null, "OK", PixelSize(2, 3), size, listOf("SOURCE_PIXELS_VERIFIED")),
            StageRecord("geometry_none_v1", 1, null, "EXACT_PERMUTATION", size, size, emptyList()),
            StageRecord("none_v1", 1, null, "EXACT_IDENTITY", size, size, listOf("W2", "W1")),
        )

        // Python struct/hashlib expansion of the independent RenderCompletionTest oracle, not bytes emitted by the Kotlin writer.
        fun golden() = """
            7072697374696e65706167652d6f7264696e6172792d72656e6465722d657865637574696f6e2d7631000000004f7061
            67652d72656e6465722d76313a3635643231636561316462313865333962333662393761323262643430336665333339
            38313335616536396637646566646134333766356361353561626563360000002430303030303030302d303030302d30
            3030302d303030302d3030303030303030303030620000002430303030303030302d303030302d303030302d30303030
            2d3030303030303030303030630000002430303030303030302d303030302d303030302d303030302d30303030303030
            303030306400000040626262626262626262626262626262626262626262626262626262626262626262626262626262
            626262626262626262626262626262626262626262626262620000004061616161616161616161616161616161616161
            616161616161616161616161616161616161616161616161616161616161616161616161616161616161616161000000
            000000000400000040653963366332313838306133633838303265376635396264656238303330353935363135306333
            383062373666343235346463666165333435383932386365300000000300000002000000064558504f52540000000446
            41494c000000024f4b00000003000000020000000300000010736f757263655f6465636f64655f763100000001000000
            024f4b000000020000000300000003000000020000000100000016534f555243455f504958454c535f56455249464945
            440000001067656f6d657472795f6e6f6e655f7631000000010000001145584143545f5045524d55544154494f4e0000
            000300000002000000030000000200000000000000076e6f6e655f7631000000010000000e45584143545f4944454e54
            495459000000030000000200000003000000020000000200000002573200000002573100000002000000025733000000
            025731000000403639333833613365613638633865313263313962396630623630346138383833353765663961386333
            6631383066366535306336306661656137346466376334
        """.filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
