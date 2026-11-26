package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

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

    data class Catalog(val tree: Uri, val libraryId: String, val generationId: String, val albums: Int, val scans: Int)

    suspend fun verifyPairWithSharedOwner(context: Context, scratch: File, left: Uri, right: Uri,
        requireAuthority: () -> Unit,
    ): List<Catalog> =
        AndroidLibraryTreeRead(context, scratch).withVerifiedPair(AndroidLibraryTreeRead.PairInput(left),
            AndroidLibraryTreeRead.PairInput(right), requireAuthority) { a, b ->
            listOf(a, b).map { (tree, generation, snapshot) ->
                Catalog(tree, generation.libraryId, generation.id, snapshot.albums.size, snapshot.albums.sumOf { it.pages.size })
            }
        }

    fun requireSameIdentity(held: ParcelFileDescriptor, reopened: ParcelFileDescriptor) {
        check(AndroidLibraryDescriptorAncestry.observe(held) == AndroidLibraryDescriptorAncestry.observe(reopened)) {
            "The reopened directory descriptor does not match its held identity and mount"
        }
    }
}
