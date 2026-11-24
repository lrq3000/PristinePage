package app.pristinepage.data.library

import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LibraryObjectTransfer.Requirement
import java.io.IOException
import java.nio.file.Files

/** Checks the planned locators against stable local portable inventories. A successful estimate cannot write, publish, or activate anything. */
internal object LibraryMergePayloads {
    data class Estimate(val missingBytes: Long, val requiredBytes: Long)

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
        val targetRoot = destination.rootPath
        val sourceRoot = source.rootPath
        if (targetRoot.startsWith(sourceRoot) || sourceRoot.startsWith(targetRoot) || Files.isSameFile(targetRoot, sourceRoot)) {
            throw IOException("Merge locations overlap")
        }
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
            Requirement(id) { source.open(id) ?: throw IOException("Merge source original is missing") }
        }
        val prepared = LibraryObjectTransfer(destination, checkpoint).prepare(requirements,
            catalogAndJournalBytes, availableBytes, requiredExisting = existing)
        return Estimate(prepared.missingBytes, prepared.requiredBytes)
    }

    private fun inventory(rows: List<SourceBlobRow>): Map<String, ObjectId> {
        val byId = rows.associate { it.sourceBlobId to ObjectId(it.byteSha256, it.byteCount) }
        if (byId.size != rows.size) mismatch()
        return byId
    }

    private fun mismatch(): Nothing = throw IOException("Merge original references do not match the two admitted catalogs")
}
