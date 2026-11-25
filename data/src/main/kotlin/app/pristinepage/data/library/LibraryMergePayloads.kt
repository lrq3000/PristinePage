package app.pristinepage.data.library

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LibraryObjectTransfer.Requirement
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Checks the planned locators against admitted original inventories. A successful estimate cannot write, publish, or activate anything. */
internal object LibraryMergePayloads {
    data class Estimate(val missingBytes: Long, val requiredBytes: Long)

    interface Destination {
        /** Return null only for positively known absence under this exact root. */
        fun open(id: ObjectId): InputStream?

        /** Keep the stage under its admitted owner; call [requireCurrent] before staging and before making the closed object visible. Never replace a final name. */
        fun create(id: ObjectId, requireCurrent: () -> Unit, write: (OutputStream) -> Unit)
    }

    fun check(
        candidate: LibraryCatalogMerge.Candidate,
        destinationRows: List<SourceBlobRow>,
        incomingRows: List<SourceBlobRow>,
        destination: LocalLibraryDirectory,
        source: LocalLibraryDirectory,
        catalogAndJournalBytes: Long,
        availableBytes: Long,
        checkpoint: () -> Unit = {},
    ): Estimate {
        val prepared = prepare(candidate, destinationRows, incomingRows, destination, source::open,
            requireSeparate = { destination.requireIndependentScratch(source.rootPath) }, catalogAndJournalBytes, availableBytes, false, checkpoint)
        return Estimate(prepared.missingBytes, prepared.requiredBytes)
    }

    fun prepare(
        candidate: LibraryCatalogMerge.Candidate,
        destinationRows: List<SourceBlobRow>,
        incomingRows: List<SourceBlobRow>,
        destination: Destination,
        openSource: (ObjectId) -> InputStream?,
        requireSeparate: () -> Unit,
        catalogAndJournalBytes: Long,
        availableBytes: Long,
        published: Boolean,
        checkpoint: () -> Unit,
    ): LibraryObjectTransfer.Prepared {
        fun requireCurrent() {
            checkpoint()
            requireSeparate()
        }
        fun openCurrent(opener: () -> InputStream?): InputStream? {
            requireCurrent()
            val input = opener()
            try {
                requireCurrent()
            } catch (failure: Throwable) {
                if (input != null) runCatching { input.close() }.exceptionOrNull()?.let {
                    if (it !== failure) failure.addSuppressed(it)
                }
                throw failure
            }
            return input
        }
        requireCurrent()
        val target = inventory(destinationRows)
        val incoming = inventory(incomingRows)
        val staged = inventory(candidate.catalog.originals)
        val added = candidate.incomingBlobByCandidate
        if (added.keys.any { it in target } || target.keys + added.keys != staged.keys) mismatch()
        for ((id, objectId) in target) if (staged[id] != objectId) mismatch()

        val seenIncoming = HashSet<String>()
        fun incomingId(blob: String, expected: ObjectId?): ObjectId {
            checkpoint()
            val id = incoming[blob] ?: mismatch()
            if (id != expected) mismatch()
            seenIncoming += blob
            return id
        }

        // A portable locator derives only from the digest. Different logical blob rows referencing the same ObjectId in this one source share exactly one stream.
        val incomingObjects = LinkedHashSet<ObjectId>()
        for ((newBlob, sourceBlob) in added) incomingObjects += incomingId(sourceBlob, staged[newBlob])
        for ((localBlob, sourceBlob) in candidate.requiredMatches) incomingObjects += incomingId(sourceBlob, target[localBlob])
        if (seenIncoming != incoming.keys) mismatch()
        val existing = target.values.toSet()
        val requirements = existing.map { Requirement(it, null) } + incomingObjects.map { id ->
            Requirement(id) { openCurrent { openSource(id) } ?: throw IOException("Merge source original is missing") }
        }
        val guarded = object : LibraryObjectTransfer.Store {
            override fun open(id: ObjectId) = openCurrent { destination.open(id) }
            override fun create(id: ObjectId, write: (OutputStream) -> Unit) {
                requireCurrent()
                destination.create(id, ::requireCurrent, write)
            }
        }
        val prepared = LibraryObjectTransfer(guarded, checkpoint).prepare(requirements, catalogAndJournalBytes, availableBytes,
            requiredExisting = if (published) staged.values.toSet() else existing)
        requireCurrent()
        return LibraryObjectTransfer.Prepared(prepared.missingBytes, prepared.requiredBytes) {
            requireCurrent()
            prepared.copyAndVerify()
            requireCurrent()
        }
    }

    private fun inventory(rows: List<SourceBlobRow>): Map<String, ObjectId> {
        val byId = rows.associate { it.sourceBlobId to ObjectId(it.byteSha256, it.byteCount) }
        if (byId.size != rows.size) mismatch()
        return byId
    }

    private fun mismatch(): Nothing = throw IOException("Merge original references do not match the two admitted catalogs")
}
