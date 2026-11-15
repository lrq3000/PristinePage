package app.pristinepage.data

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.model.*
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Complete frozen row content, not mutable current pointers. Hashing prepared descriptors is not their rights/provider/file approval or a committed snapshot. */
class ExportSnapshotIdentityTest {
    private val document = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val page = "180aab39-4036-4979-922e-e91a1ded30c4"
    private val source = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b"
    private val blob = "7416d7de-4ae5-4b01-87db-c7beee52b81c"
    private val binding = SourceBinding("a".repeat(64), PixelSize(3, 2), "srgb-v1")
    private val recipe = Recipe(geometry = GeometryRecipe.None(binding))
    private val output = OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)
    private val settings = ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(PageId(page), output)), null, null, emptyList())
    private fun row() = ExportPageRow(UUID.randomUUID().toString(), 0, page, source, blob, "b".repeat(64), binding.sourcePixelHash, 3, 2, binding.orientationColorConversionVersion, 1, 1, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe), "[]".toByteArray(), "[]".toByteArray(), "c".repeat(64), "renderer-v1", 3, 2, 3, 2, "FAIL", null, null)
    private fun identity(rows: List<ExportPageRow> = listOf(row()), settings: ExportSettings = this.settings, document: String = this.document, revision: Long = 1): Any {
        val type = try { Class.forName("app.pristinepage.data.repository.ExportSnapshotIdentity") }
        catch (_: ClassNotFoundException) { throw AssertionError("Full frozen export snapshot identity is absent") }
        return try { type.methods.single { it.name.startsWith("create") }.invoke(type.getField("INSTANCE").get(null), document, revision, settings, rows)!! }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun hash(identity: Any) = identity.javaClass.getMethod("getSnapshotHash").invoke(identity) as String
    private fun bytes(identity: Any) = identity.javaClass.getMethod("getCanonicalInput").invoke(identity) as ByteArray

    @Test fun repeatedContentIgnoresOnlyTransientSnapshotId() {
        val row = row(); val first = identity(listOf(row)); val second = identity(listOf(row.copy(snapshotId = UUID.randomUUID().toString())))
        assertEquals(hash(first), hash(second)); assertArrayEquals(bytes(first), bytes(second)); assertTrue(hash(first).matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun everyFrozenSourceDescriptorBlobAndRendererFieldSeparatesIdentity() {
        val row = row()
        val variants = listOf(row, row.copy(sourceId = UUID.randomUUID().toString()), row.copy(sourceBlobId = UUID.randomUUID().toString()), row.copy(byteSha256 = "d".repeat(64)), row.copy(recipeRevision = 2), row.copy(masks = "[{\"role\":\"PROTECTION\"}]".toByteArray()), row.copy(providers = "[{\"id\":\"declared-provider\"}]".toByteArray()), row.copy(assetSetSha256 = "d".repeat(64)), row.copy(rendererVersion = "renderer-v2"))
        assertEquals(variants.size, variants.map { hash(identity(listOf(it))) }.toSet().size)
        val changed = recipe.copy(geometry = recipe.geometry.withOutputQuarterTurns(2))
        assertNotEquals(hash(identity(listOf(row))), hash(identity(listOf(row.copy(recipe = RecipeCodec.encode(changed), recipeSha256 = RecipeCodec.hash(changed))))))
    }

    @Test fun documentIdentityAndReviewedRevisionRemainBoundToTheContent() {
        val row = listOf(row()); val first = hash(identity(row))
        assertNotEquals(first, hash(identity(row, revision = 2))); assertNotEquals(first, hash(identity(row, document = UUID.randomUUID().toString())))
        assertThrows(IllegalArgumentException::class.java) { identity(row, revision = 0) }
        assertThrows(IllegalArgumentException::class.java) { identity(row, document = "../other") }
    }

    @Test fun ordinalOrPageOrderAndOutputPolicyMismatchCannotFreezeAnotherReview() {
        val row = row()
        for (bad in listOf(row.copy(ordinal = 1), row.copy(originalPageId = UUID.randomUUID().toString()), row.copy(outputWidth = 2), row.copy(requestedOutputHeight = 3), row.copy(fallbackPolicy = "KEEP_STAGE_INPUT"))) {
            assertThrows(IllegalArgumentException::class.java) { identity(listOf(bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { identity(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { identity(listOf(row, row.copy(ordinal = 1))) }
    }

    @Test fun invalidRecipeVersionHashOrSourceBindingRejectsRatherThanHashingGuessedContent() {
        val row = row()
        for (bad in listOf(row.copy(recipeSchemaVersion = 2), row.copy(recipeSha256 = "d".repeat(64)), row.copy(pixelSha256 = "d".repeat(64)), row.copy(width = 4), row.copy(conversionFingerprint = "other-v2"), row.copy(recipe = "{}".toByteArray()), row.copy(byteSha256 = "bad"))) {
            assertThrows(IllegalArgumentException::class.java) { identity(listOf(bad)) }
        }
    }

    @Test fun acceptedRasterBindingAndActualDimensionsMustMatchExactly() {
        val row = row(); val render = RenderId(UUID.randomUUID().toString()); val actual = OutputSpec(PixelSize(2, 3), RenderPurpose.EXPORT)
        val approval = AcceptedExecution(PageId(page), 1, "d".repeat(64), render, actual.size, "e".repeat(64))
        val reviewed = ExportSettings(ExportFormat.PNG, listOf(PageOutputSettings(PageId(page), actual, FallbackPolicy.KEEP_STAGE_INPUT, output)), null, null, listOf(approval))
        val fallback = row.copy(outputWidth = 2, outputHeight = 3, fallbackPolicy = "KEEP_STAGE_INPUT", acceptedRenderId = render.value, acceptedExecutionSha256 = approval.executionHash)
        identity(listOf(fallback), reviewed)
        for (bad in listOf(fallback.copy(acceptedRenderId = null), fallback.copy(acceptedExecutionSha256 = null), fallback.copy(acceptedRenderId = UUID.randomUUID().toString()), fallback.copy(recipeRevision = 2), fallback.copy(acceptedExecutionSha256 = "f".repeat(64)))) assertThrows(IllegalArgumentException::class.java) { identity(listOf(bad), reviewed) }
        assertThrows(IllegalArgumentException::class.java) { identity(listOf(row.copy(acceptedRenderId = render.value, acceptedExecutionSha256 = approval.executionHash))) }
        val changedApproval = approval.copy(rasterPixelHash = "f".repeat(64))
        val changedReview = ExportSettings(ExportFormat.PNG, reviewed.pages, null, null, listOf(changedApproval))
        assertNotEquals(hash(identity(listOf(fallback), reviewed)), hash(identity(listOf(fallback), changedReview)))
    }

    @Test fun blobAndWholeSnapshotMetadataBoundsRejectBeforeSerializingHugeInputs() {
        val row = row()
        assertThrows(IllegalArgumentException::class.java) { identity(listOf(row.copy(masks = ByteArray(524289)))) }
        val ids = List(17) { UUID.randomUUID().toString() }
        val pages = ids.map { PageOutputSettings(PageId(it), output) }
        val reviewed = ExportSettings(ExportFormat.PNG, pages, null, null, emptyList())
        val largeDescriptor = ByteArray(524288) { 32 }
        val rows = ids.mapIndexed { index, id -> row.copy(ordinal = index, originalPageId = id, masks = largeDescriptor) }
        assertThrows(IllegalArgumentException::class.java) { identity(rows, reviewed) }
    }

    @Test fun ownedCanonicalBytesSurviveCallerMutationsToRowsAndReturnedArrays() {
        val row = row(); val frozen = identity(listOf(row)); val original = bytes(frozen); val digest = hash(frozen)
        row.recipe.fill(0); row.masks.fill(0); row.providers.fill(0); bytes(frozen).fill(0)
        assertArrayEquals(original, bytes(frozen)); assertEquals(digest, hash(frozen))
    }
}
