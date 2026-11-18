package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.ExportPageRow
import app.pristinepage.data.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

internal class FrozenExportIdentity(input: ByteArray, val snapshotHash: String) {
    private val owned = input.copyOf()
    val canonicalInput: ByteArray get() = owned.copyOf()
}

/** Internal identity of fully prepared frozen content, not freeze admission or a render cache key. Descriptor bytes are hashed exactly; their canonical schema, rights/provider readiness and retained file/lease proofs belong to the consuming freeze preflight. Random snapshot IDs and wall dates are not content. */
internal object ExportSnapshotIdentity {
    private const val MAX_BLOB = 524288L
    private const val MAX_CONTENT = 8L * 1024 * 1024
    private const val FIELD_OVERHEAD = 4096L

    /** Same v1 charge for raw SQL extents and typed identity inputs. This bounds metadata content, not SQLite/Room allocations, total heap, raster or PDF memory. */
    class MetadataBudget(settingsBytes: Int) {
        private var charge = Math.addExact(settingsBytes.toLong(), FIELD_OVERHEAD)

        fun addPage(recipeBytes: Long, maskBytes: Int, providerBytes: Int) {
            require(recipeBytes in 1..MAX_BLOB && maskBytes.toLong() in 1..MAX_BLOB && providerBytes.toLong() in 1..MAX_BLOB)
            charge = Math.addExact(charge, recipeBytes + maskBytes + providerBytes + FIELD_OVERHEAD)
            require(charge <= MAX_CONTENT) { "Snapshot metadata exceeds limit" }
        }
    }

    fun create(documentId: DocumentId, documentRevision: Long, settings: ExportSettings, pages: List<ExportPageRow>): FrozenExportIdentity {
        requireOpaqueId(documentId.value); require(documentRevision > 0)
        require(pages.size in 1..100 && pages.size == settings.pages.size)
        val settingsBytes = ExportSettingsCodec.encode(settings)
        // Charge bounded field overhead as well as all three BLOBs before taking owned copies or growing the identity stream. This is a metadata cap, not measured image/PDF memory admission.
        val budget = MetadataBudget(settingsBytes.size)
        for (page in pages) budget.addPage(page.recipe.size.toLong(), page.masks.size, page.providers.size)
        val owned = pages.map { it.copy(recipe = it.recipe.copyOf(), masks = it.masks.copyOf(), providers = it.providers.copyOf()) }
        val accepted = settings.acceptedDegraded.associateBy { it.pageId.value }
        for ((ordinal, page) in owned.withIndex()) {
            requireOpaqueId(page.snapshotId); require(page.snapshotId == owned.first().snapshotId)
            requireOpaqueId(page.originalPageId); requireOpaqueId(page.sourceId); requireOpaqueId(page.sourceBlobId)
            val reviewed = settings.pages[ordinal]
            require(page.ordinal == ordinal && page.originalPageId == reviewed.pageId.value)
            require(page.recipeRevision > 0 && page.recipeSchemaVersion == 1)
            requireSha256(page.byteSha256); requireSha256(page.pixelSha256); requireSha256(page.recipeSha256); requireSha256(page.assetSetSha256)
            require(page.width > 0 && page.height > 0 && page.width.toLong() * page.height <= 48_000_000 && maxOf(page.width, page.height) <= 12000)
            for (version in listOf(page.conversionFingerprint, page.rendererVersion)) { require(version.isNotBlank() && version.length <= 128); requireWellFormedUnicode(version) }
            val recipe = RecipeCodec.decode(page.recipe)
            require(page.recipe.contentEquals(RecipeCodec.encode(recipe)) && page.recipeSha256 == RecipeCodec.hash(recipe))
            val binding = recipe.geometry.source
            require(binding.sourcePixelHash == page.pixelSha256 && binding.sourceSize.width == page.width && binding.sourceSize.height == page.height && binding.orientationColorConversionVersion == page.conversionFingerprint)
            require(page.requestedOutputWidth == reviewed.requestedOutput.size.width && page.requestedOutputHeight == reviewed.requestedOutput.size.height)
            require(page.outputWidth == reviewed.output.size.width && page.outputHeight == reviewed.output.size.height && page.fallbackPolicy == reviewed.fallbackPolicy.name)
            val approval = accepted[page.originalPageId]
            if (approval == null) require(page.acceptedRenderId == null && page.acceptedExecutionSha256 == null)
            else require(page.acceptedRenderId == approval.renderId.value && page.acceptedExecutionSha256 == approval.executionHash && page.recipeRevision == approval.recipeRevision)
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
            fun text(value: String) = field(value.toByteArray(Charsets.UTF_8))
            output.write("pristinepage-export-snapshot-v1\u0000".toByteArray(Charsets.UTF_8))
            text(documentId.value); output.writeLong(documentRevision); field(settingsBytes); output.writeInt(owned.size)
            for (page in owned) {
                output.writeInt(page.ordinal); text(page.originalPageId); text(page.sourceId); text(page.sourceBlobId)
                text(page.byteSha256); text(page.pixelSha256); output.writeInt(page.width); output.writeInt(page.height); text(page.conversionFingerprint)
                output.writeLong(page.recipeRevision); output.writeInt(page.recipeSchemaVersion); field(page.recipe); text(page.recipeSha256)
                field(page.masks); field(page.providers); text(page.assetSetSha256); text(page.rendererVersion)
                output.writeInt(page.requestedOutputWidth); output.writeInt(page.requestedOutputHeight); output.writeInt(page.outputWidth); output.writeInt(page.outputHeight); text(page.fallbackPolicy)
                output.writeBoolean(page.acceptedRenderId != null)
                if (page.acceptedRenderId != null) { text(page.acceptedRenderId); text(checkNotNull(page.acceptedExecutionSha256)) }
            }
        }
        require(bytes.size().toLong() <= MAX_CONTENT)
        val canonical = bytes.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it.toInt() and 255) }
        return FrozenExportIdentity(canonical, hash)
    }
}
