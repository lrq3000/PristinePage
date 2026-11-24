package app.pristinepage.data.library

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.IOException
import java.io.InputStream

/** Bounded private copy-and-merge intent. It carries no paths and authorizes no publication without exact roots, file checks and cooperative ownership. */
internal data class LibraryMergeIntent(
    val destinationRootKey: String,
    val incomingRootKey: String,
    val destination: LibraryGeneration,
    val incoming: LibraryGeneration,
    val candidate: LibraryGeneration,
) {
    init {
        requireSha256(destinationRootKey)
        requireSha256(incomingRootKey)
        require(destinationRootKey != incomingRootKey)
        require(candidate.libraryId == destination.libraryId && candidate.predecessorId == destination.id && candidate.id != destination.predecessorId)
        require(candidate.catalogSchema == destination.catalogSchema && candidate.catalogSchema == incoming.catalogSchema)
        require(listOf(destination, incoming, candidate).all { it.catalog.byteCount in 512..MAX_PORTABLE_BYTES && it.catalog.byteCount % 512 == 0L })
    }

    enum class Resume { WRITE_MISSING, VERIFY_PUBLISHED }

    fun resume(currentDestinationRoot: String, currentIncomingRoot: String, currentDestination: LibraryGeneration,
        currentIncoming: LibraryGeneration, supportedSchema: Int,
    ): Resume {
        if (candidate.catalogSchema != supportedSchema) throw IOException("Unsupported library merge catalog schema")
        if (currentDestinationRoot != destinationRootKey || currentIncomingRoot != incomingRootKey || currentIncoming != incoming) {
            throw IOException("Library merge input identity changed")
        }
        return when (currentDestination) {
            destination -> Resume.WRITE_MISSING
            candidate -> Resume.VERIFY_PUBLISHED
            else -> throw IOException("Library merge destination no longer matches the exact intent")
        }
    }

    fun encode(): ByteArray = "$TAG\n$destinationRootKey\n$incomingRootKey\n".toByteArray(Charsets.US_ASCII) +
        destination.encode() + incoming.encode() + candidate.encode()

    companion object {
        private const val TAG = "pristinepage-library-copy-intent-v1"
        private const val MAX_BYTES = 1_024

        fun read(input: InputStream): LibraryMergeIntent {
            val bytes = ByteArray(MAX_BYTES)
            var count = 0
            while (true) {
                val byte = input.read()
                if (byte == -1) break
                if (count == bytes.size || (byte != 10 && byte !in 32..126)) invalid()
                bytes[count++] = byte.toByte()
            }
            val raw = String(bytes, 0, count, Charsets.US_ASCII)
            if (!raw.endsWith('\n')) invalid()
            val fields = raw.dropLast(1).split('\n', limit = 25)
            if (fields.size != 24 || fields[0] != TAG) invalid()
            fun generation(start: Int) = fields.subList(start, start + 7).joinToString("\n", postfix = "\n")
                .byteInputStream(Charsets.US_ASCII).use(LibraryGeneration::read)
            val intent = try {
                LibraryMergeIntent(fields[1], fields[2], generation(3), generation(10), generation(17))
            } catch (_: IllegalArgumentException) { invalid() }
            if (intent.encode().toString(Charsets.US_ASCII) != raw) invalid()
            return intent
        }

        private fun invalid(): Nothing = throw IOException("Unrecognized or incomplete library merge intent")
    }
}
