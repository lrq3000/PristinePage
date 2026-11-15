package app.pristinepage.data.model

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.db.ExportPageRow
import java.util.Collections

/** Immutable public snapshot values never expose the Room row's mutable BLOBs. A worker reads these frozen identities, not Page.currentRecipeRevision or a later album order. */
class ExportPageSnapshot internal constructor(row: ExportPageRow) {
    private val owned = row.copy(recipe = row.recipe.copyOf(), masks = row.masks.copyOf(), providers = row.providers.copyOf())
    val ordinal: Int get() = owned.ordinal
    val pageId: PageId get() = PageId(owned.originalPageId)
    val sourceId: SourceId get() = SourceId(owned.sourceId)
    val sourceBlobId: SourceBlobId get() = SourceBlobId(owned.sourceBlobId)
    val byteHash: String get() = owned.byteSha256
    val pixelHash: String get() = owned.pixelSha256
    val sourceSize: PixelSize get() = PixelSize(owned.width, owned.height)
    val conversionFingerprint: String get() = owned.conversionFingerprint
    val recipeRevision: Long get() = owned.recipeRevision
    val recipeSchemaVersion: Int get() = owned.recipeSchemaVersion
    val canonicalRecipe: ByteArray get() = owned.recipe.copyOf()
    val recipeHash: String get() = owned.recipeSha256
    val masks: ByteArray get() = owned.masks.copyOf()
    val providers: ByteArray get() = owned.providers.copyOf()
    val assetSetHash: String get() = owned.assetSetSha256
    val rendererVersion: String get() = owned.rendererVersion
    val requestedOutput: OutputSpec get() = OutputSpec(PixelSize(owned.requestedOutputWidth, owned.requestedOutputHeight), RenderPurpose.EXPORT)
    val output: OutputSpec get() = OutputSpec(PixelSize(owned.outputWidth, owned.outputHeight), RenderPurpose.EXPORT)
    val fallbackPolicy: FallbackPolicy get() = FallbackPolicy.valueOf(owned.fallbackPolicy)
    val acceptedRenderId: RenderId? get() = owned.acceptedRenderId?.let(::RenderId)
    val acceptedExecutionHash: String? get() = owned.acceptedExecutionSha256
}

class ExportSnapshot internal constructor(val snapshotId: ExportSnapshotId, val documentId: DocumentId, val documentRevision: Long, val snapshotHash: String, val settings: ExportSettings, pages: List<ExportPageSnapshot>, val createdAt: Long) {
    val pages: List<ExportPageSnapshot> = Collections.unmodifiableList(ArrayList(pages))
}
