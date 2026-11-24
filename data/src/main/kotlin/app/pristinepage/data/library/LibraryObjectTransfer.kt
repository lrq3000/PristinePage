package app.pristinepage.data.library

import app.pristinepage.core.io.transferExactBytes
import app.pristinepage.core.pipeline.requireSha256
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Copies only immutable objects. Catalog verification, destination ownership and publication belong to the calling transaction. */
internal class LibraryObjectTransfer(private val destination: Store, private val checkpoint: () -> Unit = {}) {
    data class ObjectId(val sha256: String, val byteCount: Long) {
        init {
            requireSha256(sha256)
            require(byteCount > 0)
        }
    }

    /** A target-only reference has no incoming stream. Other streams must reopen the same source snapshot. */
    data class Requirement(val id: ObjectId, val openIncoming: (() -> InputStream)?)

    interface Store {
        /** Return null only for known absence, not for denied access, unmounted storage or ambiguous lookup. */
        fun open(id: ObjectId): InputStream?

        /** Stage exclusively and expose only a closed object after [write] returns. Never overwrite a prior object. A failed callback must not expose the stage as this ID. */
        fun create(id: ObjectId, write: (OutputStream) -> Unit)
    }

    internal class Prepared(val missingBytes: Long, val requiredBytes: Long, private val transfer: () -> Unit) {
        fun copyAndVerify() = transfer()
    }

    /** Catalog and journal space is budgeted separately. A caller must provide known capacity and retain exclusive destination ownership through publication. */
    fun prepare(requirements: List<Requirement>, catalogAndJournalBytes: Long, availableBytes: Long,
        requiredExisting: Set<ObjectId> = emptySet(),
    ): Prepared {
        require(catalogAndJournalBytes >= 0 && availableBytes >= 0)
        if (catalogAndJournalBytes > availableBytes) throw IOException("Not enough space for library catalog")
        val objects = LinkedHashMap<String, Candidate>()
        for ((id, openIncoming) in requirements) {
            checkpoint()
            val candidate = objects.getOrPut(id.sha256) { Candidate(id) }
            if (candidate.id != id) throw IOException("Conflicting library object lengths")
            if (openIncoming != null) candidate.incoming += openIncoming
        }
        if (requiredExisting.any { objects[it.sha256]?.id != it }) throw IOException("Missing destination original requirement")
        var missingBytes = 0L
        for (candidate in objects.values) {
            candidate.needsCopy = !verifyExisting(candidate)
            if (candidate.needsCopy) {
                if (candidate.id in requiredExisting) throw IOException("The destination's existing library original is missing")
                val incoming = candidate.incoming.firstOrNull() ?: throw IOException("Missing required library object")
                val id = candidate.id
                require(id.byteCount <= Long.MAX_VALUE - catalogAndJournalBytes - missingBytes)
                missingBytes += id.byteCount
                if (catalogAndJournalBytes + missingBytes > availableBytes) throw IOException("Not enough space for library transfer")
                incoming().use { verify(it, id) }
                // Authenticate duplicates against each other, not just against the shared digest.
                for (index in 1 until candidate.incoming.size) {
                    candidate.incoming[index]().use { copy -> incoming().use { compare(copy, it, id) } }
                }
            }
        }
        val requiredBytes = catalogAndJournalBytes + missingBytes
        checkpoint()
        return Prepared(missingBytes, requiredBytes) {
            for (candidate in objects.values) copyAndVerify(candidate)
            checkpoint()
        }
    }

    private class Candidate(val id: ObjectId) {
        val incoming = ArrayList<() -> InputStream>()
        var needsCopy = false
    }

    private fun verifyExisting(candidate: Candidate): Boolean {
        val id = candidate.id
        val incoming = candidate.incoming
        checkpoint()
        val current = destination.open(id) ?: return false
        current.use { stored ->
            if (incoming.isEmpty()) verify(stored, id) else incoming[0]().use { compare(it, stored, id) }
        }
        for (index in 1 until incoming.size) {
            val stored = destination.open(id) ?: throw IOException("Library object disappeared during verification")
            stored.use { incoming[index]().use { copy -> compare(copy, stored, id) } }
        }
        return true
    }

    private fun copyAndVerify(candidate: Candidate) {
        if (verifyExisting(candidate)) return // Also handles a stale Prepared replay without rewriting a completed object.
        if (!candidate.needsCopy) throw IOException("Previously verified library object is missing")
        val id = candidate.id
        checkpoint()
        destination.create(id) { sink -> candidate.incoming[0]().use { verify(it, id, sink) } }
        if (!verifyExisting(candidate)) throw IOException("New library object is not readable")
    }

    private fun verify(input: InputStream, id: ObjectId, output: OutputStream? = null) =
        transferExactBytes(input, output, id.byteCount, id.sha256, checkpoint)

    private fun compare(input: InputStream, other: InputStream, id: ObjectId) {
        val comparator = object : OutputStream() {
            private val buffer = ByteArray(65_536)

            override fun write(value: Int) {
                checkpoint()
                val expected = other.read()
                checkpoint()
                if (expected != value.and(255)) throw IOException("Library objects disagree")
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var compared = 0
                while (compared < length) {
                    val requested = minOf(length - compared, buffer.size)
                    checkpoint()
                    val n = other.read(buffer, 0, requested)
                    checkpoint()
                    if (n !in 1..requested) throw IOException("Invalid library object read size")
                    for (i in 0 until n) if (buffer[i] != bytes[offset + compared + i]) throw IOException("Library objects disagree")
                    compared += n
                }
            }
        }
        verify(input, id, comparator)
        checkpoint()
        val eof = other.read() == -1
        checkpoint()
        if (!eof) throw IOException("Invalid library object length")
    }
}
