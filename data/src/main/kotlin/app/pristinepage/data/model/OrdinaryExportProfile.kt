package app.pristinepage.data.model

import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Data owns the ordinary empty-provider descriptor already frozen into v1 snapshots. Sharing this encoder preserves its exact bytes across freeze and consumption without another JSON dependency or handwritten escaping in image-android. This is an execution identity, not codec/rights/resource acceptance. */
class OrdinaryExportProfile(val rendererVersion: String, val encodingVersion: String, val backend: String, val precision: String) {
    init {
        for (value in listOf(rendererVersion,encodingVersion,backend,precision)) { require(value.isNotBlank() && value.length <= 128); requireWellFormedUnicode(value) }
    }
    fun providerDescriptor(): ByteArray = buildJsonObject { put("backend",backend); put("encodingVersion",encodingVersion); put("precision",precision); put("providers",JsonArray(emptyList())); put("version",1) }.toString().toByteArray(Charsets.UTF_8)
    companion object {
        val emptyAssetSetHash: String = MessageDigest.getInstance("SHA-256").digest("pristinepage-export-assets-v1\u0000".toByteArray() + byteArrayOf(0,0,0,0)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
