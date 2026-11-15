package app.pristinepage.data.model

import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.core.recipe.RecipeCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

class PersistentRenderIdentity internal constructor(input: ByteArray, val canonicalInputSha256: String) {
    private val ownedInput = input.copyOf()
    val canonicalInput: ByteArray get() = ownedInput.copyOf()
    val idempotenceKey: String get() = "page-render-v1:$canonicalInputSha256"
}

/** Pre-execution identity only. Execution hash joins the completed raster cache key later; attempts, leases, preview generation, snapshot/ordinal consumers and equivalent source-row owners must never create a second pixel job. */
object RenderJobIdentity {
    @JvmStatic fun create(request: RenderRequest, maskHashes: Map<String, String>, assetSetHash: String, conversionFingerprint: String, rendererVersion: String, encodingVersion: String, backend: String, precision: String): PersistentRenderIdentity {
        requireUuid(request.pageId); requireUuid(request.sourceId)
        require(request.recipeRevision >= 1)
        requireSha256(request.sourcePixelHash); requireSha256(assetSetHash)
        require(request.sourcePixelHash == request.recipe.geometry.source.sourcePixelHash) { "Recipe/source binding mismatch" }
        require(conversionFingerprint == request.recipe.geometry.source.orientationColorConversionVersion) { "Source conversion mismatch" }
        for (version in listOf(conversionFingerprint, rendererVersion, encodingVersion, backend, precision)) {
            require(version.isNotBlank() && version.length <= 128)
            requireWellFormedUnicode(version)
        }
        require(maskHashes.keys.all { it == "protection" || it == "coverage" }) { "Unknown mask role" }
        maskHashes.values.forEach(::requireSha256)
        require((request.protectionMaskId != null) == maskHashes.containsKey("protection")) { "Protection identity is incomplete" }
        request.protectionMaskId?.let(::requireUuid)
        val recipe = RecipeCodec.encode(request.recipe)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun field(value: ByteArray) { output.writeInt(value.size); output.write(value) }
            fun text(value: String) = field(value.toByteArray(Charsets.UTF_8))
            output.write("pristinepage-render-input-v1\u0000".toByteArray(Charsets.UTF_8))
            text(request.pageId); output.writeLong(request.recipeRevision)
            text(RecipeCodec.hash(request.recipe)); field(recipe)
            text(request.sourcePixelHash)
            output.writeInt(request.output.size.width); output.writeInt(request.output.size.height); text(request.output.purpose.name)
            text(request.fallback.name)
            output.writeInt(maskHashes.size)
            for ((role, hash) in maskHashes.toSortedMap()) { text(role); text(hash) }
            text(assetSetHash); text(conversionFingerprint); text(rendererVersion); text(encodingVersion); text(backend); text(precision)
        }
        val canonical = bytes.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { "%02x".format(it.toInt() and 255) }
        return PersistentRenderIdentity(canonical, hash)
    }

    private fun requireUuid(value: String) = requireOpaqueId(value)
}
