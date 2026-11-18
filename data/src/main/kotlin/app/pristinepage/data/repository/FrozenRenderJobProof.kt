package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.SourceReferenceRow
import app.pristinepage.data.model.ExportPageSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.RenderJobIdentity
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.model.requireOpaqueId

/** Checks immutable render identity and the supplied JOB pin, not committed bytes, transitive ownership, current state, or authority to run. */
internal object FrozenRenderJobProof {
    private val emptyMasks = "[]".encodeToByteArray()

    /** The caller pairs the assembly's verified reference and page at the same frozen ordinal and supplies stable, already admitted rows. The pin list must be complete for this job, or include a second row to witness multiplicity (as with LIMIT 2); a truncated singleton is not sufficient. */
    fun verify(
        expectedJobId: JobId,
        page: ExportPageSnapshot,
        row: JobRow,
        pins: List<SourceReferenceRow>,
        pinnedSource: ExportSourceBinding?,
        profile: OrdinaryExportProfile,
    ) {
        try {
            require(page.fallbackPolicy == FallbackPolicy.FAIL && page.acceptedRenderId == null && page.acceptedExecutionHash == null)
            require(page.masks.contentEquals(emptyMasks) && page.assetSetHash == OrdinaryExportProfile.emptyAssetSetHash)
            require(page.rendererVersion == profile.rendererVersion && page.providers.contentEquals(profile.providerDescriptor()))
            val output = page.output
            require(output == page.requestedOutput && output.purpose == RenderPurpose.EXPORT)

            val bytes = page.canonicalRecipe
            val recipe = RecipeCodec.decode(bytes)
            require(page.recipeSchemaVersion == recipe.schemaVersion && bytes.contentEquals(RecipeCodec.encode(recipe)) && page.recipeHash == RecipeCodec.hash(recipe))
            require(recipe.geometry !is GeometryRecipe.RoughCropDewarp && recipe.appearance !is AppearanceRecipe.ProviderAppearanceV1)
            require(recipe.geometry.source == SourceBinding(page.pixelHash, page.sourceSize, page.conversionFingerprint))
            val request = RenderRequest(page.sourceId.value, page.pixelHash, page.pageId.value, page.recipeRevision, recipe, output, 0, null, FallbackPolicy.FAIL)
            val expected = RenderJobIdentity.create(request, emptyMap(), page.assetSetHash, page.conversionFingerprint, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision)

            // Comparing with the rebuilt identity, not with a hash of row.input alone, rejects an internally consistent job for the wrong page.
            require(row.jobId == expectedJobId.value && row.kind == "PAGE_RENDER" && row.snapshotId == null && row.pageOrdinal == null)
            require(row.idempotenceKey == expected.idempotenceKey && row.canonicalInputSha256 == expected.canonicalInputSha256 && row.input.contentEquals(expected.canonicalInput))
            verifyPin(page, row, pins, pinnedSource)
        } catch (_: IllegalArgumentException) {
            throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "frozen_render_job_invalid", expectedJobId.value))
        }
    }

    private fun verifyPin(page: ExportPageSnapshot, row: JobRow, pins: List<SourceReferenceRow>, binding: ExportSourceBinding?) {
        val pin = requireNotNull(pins.singleOrNull())
        val (source, blob) = requireNotNull(binding)
        require(pin.ownerType == "JOB" && pin.ownerId == row.jobId && pin.sourceId == source.sourceId)
        requireOpaqueId(source.sourceId)
        require(source.sourceBlobId == blob.sourceBlobId)
        requireOpaqueId(blob.sourceBlobId)
        requireOpaqueId(blob.ingestTransactionId)
        require(source.state == "READY" && blob.state == "READY" && source.refCount >= 1 && blob.sourceCount >= 1)
        require(blob.byteCount in 1..104857600L && blob.relativePath == "sources/${blob.sourceBlobId}/original.bin")
        requireSha256(blob.byteSha256)
        requireSha256(page.byteHash)
        val inspection = SourceInspection(blob.mime, PixelSize(blob.encodedWidth, blob.encodedHeight), PixelSize(source.width, source.height), source.exifOrientation, source.profileSha256, source.conversionFingerprint, source.pixelSha256)

        // Distinct immutable originals can normalize to the same pixels. Only an ID that is the same must describe the same immutable object.
        val frozenBlobId = page.sourceBlobId.value
        require(source.sourceId != page.sourceId.value || blob.sourceBlobId == frozenBlobId)
        require(blob.sourceBlobId != frozenBlobId || blob.byteSha256 == page.byteHash)
        require(inspection.sourcePixelHash == page.pixelHash && inspection.normalizedSize == page.sourceSize && inspection.conversionFingerprint == page.conversionFingerprint)
    }
}
