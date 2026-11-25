package app.pristinepage.data.library

import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.io.InputStream

/** A format marker binds one immutable catalog. Decoding it does not admit the catalog schema, authenticate an external producer or select the active generation. */
internal data class LibraryGeneration(
    val libraryId: String,
    val id: String,
    val predecessorId: String?,
    val catalogSchema: Int,
    val catalog: LibraryObjectTransfer.ObjectId,
) {
    init {
        requireOpaqueId(libraryId)
        requireOpaqueId(id)
        predecessorId?.let(::requireOpaqueId)
        require(predecessorId != id && catalogSchema > 0)
    }

    val catalogPath: String get() = "catalogs/$id.sqlite"

    fun encode(): ByteArray = "$TAG\n$libraryId\n$id\n${predecessorId ?: "-"}\n$catalogSchema\n${catalog.byteCount}\n${catalog.sha256}\n"
        .toByteArray(Charsets.US_ASCII)

    /** The caller budgets and admits the declared extent before obtaining the stream; this check authenticates only the closed bytes. */
    fun verifyCatalog(input: InputStream, checkpoint: () -> Unit = {}) =
        transferExactBytes(input, null, catalog.byteCount, catalog.sha256, checkpoint)

    companion object {
        private const val TAG = "pristinepage-library-v1"
        private const val MAX_MARKER_BYTES = 256

        /** Borrows an already authorized marker stream. Never resolve a path from an unparsed string or read the catalog on format refusal. */
        fun read(input: InputStream): LibraryGeneration = read(input) {}

        fun read(input: InputStream, checkpoint: () -> Unit): LibraryGeneration {
            checkpoint()
            val bytes = ByteArray(MAX_MARKER_BYTES)
            var length = 0
            while (true) {
                val value = input.read()
                checkpoint()
                if (value == -1) break
                if (length == bytes.size || (value != 10 && value !in 32..126)) invalid()
                bytes[length++] = value.toByte()
            }
            val text = String(bytes, 0, length, Charsets.US_ASCII)
            if (!text.endsWith('\n')) invalid()
            val fields = text.dropLast(1).split('\n')
            if (fields.size != 7 || fields[0] != TAG) invalid()
            val manifest = try {
                LibraryGeneration(
                    fields[1], fields[2], fields[3].takeUnless { it == "-" },
                    fields[4].toIntOrNull() ?: invalid(),
                    LibraryObjectTransfer.ObjectId(fields[6], fields[5].toLongOrNull() ?: invalid()),
                )
            } catch (_: IllegalArgumentException) {
                invalid()
            }
            if (manifest.encode().toString(Charsets.US_ASCII) != text) invalid()
            return manifest
        }

        private fun invalid(): Nothing = throw IOException("Unrecognized or incomplete library generation marker")
    }
}
