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
import app.pristinepage.data.model.PersistentRenderIdentity
import app.pristinepage.data.model.RenderJobIdentity
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.model.requireOpaqueId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Restricted metadata projection used after raw admission; this is not a full job or a lease. */
data class FrozenRenderJobIdentityRow(
    val jobId: String,
    val kind: String,
    val idempotenceKey: String,
    val canonicalInputSha256: String,
    val input: ByteArray,
)

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
        if (row.snapshotId != null || row.pageOrdinal != null) invalid(expectedJobId)
        val expected = prepare(expectedJobId, page, profile, acceptHistoricalIdentity = false)
        val pin = pins.singleOrNull() ?: invalid(expectedJobId)
        if (pin.ownerType != "JOB" || pin.ownerId != row.jobId) invalid(expectedJobId)
        expected.verify(FrozenRenderJobIdentityRow(row.jobId, row.kind, row.idempotenceKey, row.canonicalInputSha256, row.input), pin.sourceId, pinnedSource)
    }

    /** Prepare before entering Room so the raw guard binds the actual canonical byte extent. A retained profile is used only to verify identity, never to select a runtime. */
    fun prepare(
        expectedJobId: JobId,
        page: ExportPageSnapshot,
        runtime: OrdinaryExportProfile,
        acceptHistoricalIdentity: Boolean = true,
        parseDescriptor: (String) -> JsonElement = Json.Default::parseToJsonElement,
    ): Expected {
        try {
            require(page.fallbackPolicy == FallbackPolicy.FAIL && page.acceptedRenderId == null && page.acceptedExecutionHash == null)
            require(page.masks.contentEquals(emptyMasks) && page.assetSetHash == OrdinaryExportProfile.emptyAssetSetHash)
            val descriptor = page.providers
            val historical = page.rendererVersion != runtime.rendererVersion || !descriptor.contentEquals(runtime.providerDescriptor())
            val profile = if (historical) {
                require(acceptHistoricalIdentity)
                retainedProfile(page.rendererVersion, descriptor, parseDescriptor)
            } else runtime
            val output = page.output
            require(output == page.requestedOutput && output.purpose == RenderPurpose.EXPORT)

            val bytes = page.canonicalRecipe
            val recipe = RecipeCodec.decode(bytes)
            require(page.recipeSchemaVersion == recipe.schemaVersion && bytes.contentEquals(RecipeCodec.encode(recipe)) && page.recipeHash == RecipeCodec.hash(recipe))
            require(recipe.geometry !is GeometryRecipe.RoughCropDewarp && recipe.appearance !is AppearanceRecipe.ProviderAppearanceV1)
            require(recipe.geometry.source == SourceBinding(page.pixelHash, page.sourceSize, page.conversionFingerprint))
            val request = RenderRequest(page.sourceId.value, page.pixelHash, page.pageId.value, page.recipeRevision, recipe, output, 0, null, FallbackPolicy.FAIL)
            val expected = RenderJobIdentity.create(request, emptyMap(), page.assetSetHash, page.conversionFingerprint, profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision)
            return Expected(expectedJobId, page, expected, historical)
        } catch (_: IllegalArgumentException) {
            invalid(expectedJobId)
        }
    }

    private fun retainedProfile(rendererVersion: String, descriptor: ByteArray, parseDescriptor: (String) -> JsonElement): OrdinaryExportProfile {
        // The canonical encoder can emit at most six ASCII bytes per UTF-16 unit of each version token plus a small fixed JSON envelope.
        require(descriptor.size in 1..(3 * 128 * 6 + 128))
        val text = descriptor.decodeToString()
        requireShallowDescriptor(text)
        val json = parseDescriptor(text) as? JsonObject ?: throw IllegalArgumentException()
        fun text(name: String): String {
            val value = json[name] as? JsonPrimitive ?: throw IllegalArgumentException()
            require(value.isString)
            return value.content
        }
        val profile = OrdinaryExportProfile(rendererVersion, text("encodingVersion"), text("backend"), text("precision"))
        // Exact re-encoding also rejects malformed UTF-8, duplicate or unknown fields, nonempty providers, and any other descriptor version.
        require(descriptor.contentEquals(profile.providerDescriptor()))
        return profile
    }

    private fun requireShallowDescriptor(text: String) {
        // A byte cap cannot bound parser recursion. Outside quoted strings, allow only the root object and literal empty arrays; the parser and exact re-encoding still enforce grammar and the single providers field.
        require(text.startsWith('{') && text.endsWith('}'))
        var quoted = false
        var escaped = false
        var index = 1
        while (index < text.lastIndex) {
            val char = text[index++]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '[' -> {
                        require(index < text.lastIndex && text[index] == ']')
                        index++
                    }
                    '{', '}', ']' -> throw IllegalArgumentException()
                }
            }
        }
        require(!quoted)
    }

    class Expected internal constructor(
        private val jobId: JobId,
        private val page: ExportPageSnapshot,
        identity: PersistentRenderIdentity,
        val historical: Boolean,
    ) {
        private val input = identity.canonicalInput
        private val key = identity.idempotenceKey
        private val digest = identity.canonicalInputSha256
        val inputBytes: Int get() = input.size

        /** Both entry points use the same exact proof; compare against the rebuilt bytes, not the row's self-reported digest. */
        fun verify(row: FrozenRenderJobIdentityRow, pinSourceId: String, pinnedSource: ExportSourceBinding?) {
            try {
                require(row.jobId == jobId.value && row.kind == "PAGE_RENDER")
                require(row.idempotenceKey == key && row.canonicalInputSha256 == digest && row.input.contentEquals(input))
                verifyPin(page, pinSourceId, pinnedSource)
            } catch (_: IllegalArgumentException) {
                invalid(jobId)
            }
        }
    }

    private fun invalid(jobId: JobId): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "frozen_render_job_invalid", jobId.value))

    fun verifyPin(page: ExportPageSnapshot, pinSourceId: String, binding: ExportSourceBinding?) {
        val (source, blob) = requireNotNull(binding)
        require(pinSourceId == source.sourceId)
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
