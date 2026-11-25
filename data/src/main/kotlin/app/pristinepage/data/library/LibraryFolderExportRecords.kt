package app.pristinepage.data.library

import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import app.pristinepage.data.model.requireOpaqueId
import java.io.IOException
import java.io.InputStream

/** Private export records only. These bytes do not grant provider access, prove physical identity, or authorize replay. */
internal object LibraryFolderExportRecords {
    private const val INTENT = "pristinepage-library-export-intent-v2"
    private const val COMPLETE = "pristinepage-library-export-complete-v2"
    private const val ACKNOWLEDGED = "pristinepage-library-export-acknowledged-v2"
    private const val MAX_INTENT_BYTES = 5_000

    data class Intent(
        val operationId: String, val issuedAt: LibraryActivation, val destination: LibraryFolderGrantRecord,
        val parentDevice: Long, val parentInode: Long, val generation: LibraryGeneration,
    ) {
        init {
            requireOpaqueId(operationId)
            require(destination.grantId == operationId && destination.returnedModes == 3)
            require(parentDevice >= 0 && parentInode > 0)
            require(generation.predecessorId == null && generation.catalog.byteCount in 512..MAX_PORTABLE_BYTES &&
                generation.catalog.byteCount % 512L == 0L)
        }

        val backupName: String get() = "PristinePage-backup-${generation.id}"

        fun encode(): ByteArray {
            val envelope = "$INTENT\n$operationId\n${issuedAt.libraryId}\n${issuedAt.number}\n$parentDevice\n$parentInode\n"
                .toByteArray(Charsets.US_ASCII) + destination.encode() + generation.encode()
            return envelope + "${backupSha256(envelope)}\n".toByteArray(Charsets.US_ASCII)
        }

        fun completion(): ByteArray = terminal(COMPLETE)
        fun acknowledgment(): ByteArray = terminal(ACKNOWLEDGED)
        fun requireCompletion(input: InputStream) = requireExact(input, completion())
        fun requireAcknowledgment(input: InputStream) = requireExact(input, acknowledgment())

        private fun terminal(kind: String): ByteArray =
            "$kind\n$operationId\n${backupSha256(encode())}\n".toByteArray(Charsets.US_ASCII)
    }

    fun readIntent(input: InputStream, expectedOperationId: String): Intent {
        val bytes = ByteArray(MAX_INTENT_BYTES)
        var size = 0
        while (true) {
            val value = input.read()
            if (value == -1) break
            if (size == bytes.size || (value != 10 && value !in 32..126)) invalid()
            bytes[size++] = value.toByte()
        }
        val raw = String(bytes, 0, size, Charsets.US_ASCII)
        if (!raw.endsWith('\n')) invalid()
        val fields = raw.dropLast(1).split('\n', limit = 20)
        if (fields.size != 19 || fields[0] != INTENT || fields[1] != expectedOperationId) invalid()
        fun section(from: Int, until: Int) =
            fields.subList(from, until).joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
        val intent = try {
            Intent(
                fields[1], LibraryActivation(fields[2], fields[3].toLongOrNull() ?: invalid()),
                LibraryFolderGrantRecord.decode(section(6, 11), expectedOperationId),
                fields[4].toLongOrNull() ?: invalid(), fields[5].toLongOrNull() ?: invalid(),
                section(11, 18).inputStream().use(LibraryGeneration::read),
            )
        } catch (_: IllegalArgumentException) { invalid() }
        if (intent.encode().toString(Charsets.US_ASCII) != raw) invalid()
        return intent
    }

    private fun requireExact(input: InputStream, expected: ByteArray) {
        for (byte in expected) if (input.read() != (byte.toInt() and 255)) invalid()
        if (input.read() != -1) invalid()
    }

    private fun invalid(): Nothing = throw IOException("The private library backup record is invalid")
}
