package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID

/** Debug instrumentation bridge. It does not validate a grant, hold topology stable, or authorize mutation. */
object LibraryDescriptorAncestryWitness {
    fun requireDisjoint(anchor: ParcelFileDescriptor, left: ParcelFileDescriptor, right: ParcelFileDescriptor) {
        AndroidLibraryDescriptorAncestry.admit(left, right, anchor)
    }

    suspend fun withRecheckedSiblings(anchor: ParcelFileDescriptor, left: ParcelFileDescriptor, right: ParcelFileDescriptor,
        rebind: () -> Unit, read: suspend () -> Unit,
    ) {
        val proof = AndroidLibraryDescriptorAncestry.admit(left, right, anchor)
        rebind()
        proof.requireCurrentSiblings()
        read()
        rebind()
        proof.requireCurrentSiblings()
    }

    class Catalog internal constructor(internal val input: AndroidLibraryTreeRead.PairInput, val libraryId: String,
        val generationId: String, val albums: Int, val scans: Int,
    ) {
        val tree: Uri get() = input.tree
    }

    suspend fun verifyPairWithSharedOwner(context: Context, scratch: File, left: Uri, right: Uri,
        requireAuthority: () -> Unit,
    ): List<Catalog> =
        AndroidLibraryTreeRead(context, scratch).withVerifiedPair(AndroidLibraryTreeRead.PairInput(left),
            AndroidLibraryTreeRead.PairInput(right), requireAuthority) { a, b ->
            listOf(a, b).map { (tree, generation, snapshot) ->
                Catalog(AndroidLibraryTreeRead.PairInput(tree, generation), generation.libraryId, generation.id,
                    snapshot.albums.size, snapshot.albums.sumOf { it.pages.size })
            }
        }

    data class MergePreview(val albums: Int, val names: List<String>, val matchedOriginals: Int, val missingBytes: Long,
        val closedCatalogBytes: Long, val requiredDestinationBytes: Long, val retainedRevisions: Int)

    suspend fun estimateMerge(context: Context, scratch: File, destination: Catalog, incoming: Catalog, requireAuthority: () -> Unit): MergePreview {
        val preflight = RoomLibraryTreeMergePreflight(context, scratch).verify(UUID.randomUUID().toString(),
            destination.input, incoming.input, requireAuthority)
        val estimate = preflight.estimate
        val albums = preflight.plan.candidate.catalog.albums
        return MergePreview(albums.size, albums.flatMap { album -> album.pages.map { it.filename } },
            preflight.plan.candidate.requiredMatches.size, estimate.missingBytes, preflight.closedCatalog.byteCount,
            estimate.requiredBytes, albums.sumOf { album -> album.pages.sumOf { it.revisionCount } })
    }

    fun requireSameIdentity(held: ParcelFileDescriptor, reopened: ParcelFileDescriptor) {
        check(AndroidLibraryDescriptorAncestry.observe(held) == AndroidLibraryDescriptorAncestry.observe(reopened)) {
            "The reopened directory descriptor does not match its held identity and mount"
        }
    }
}
