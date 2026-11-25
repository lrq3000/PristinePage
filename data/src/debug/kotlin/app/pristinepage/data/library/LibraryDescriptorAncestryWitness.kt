package app.pristinepage.data.library

import android.os.ParcelFileDescriptor

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

    fun withDerivedAnchor(left: ParcelFileDescriptor, right: ParcelFileDescriptor, leftId: String, rightId: String,
        rebind: () -> Unit, read: () -> Unit,
    ) = AndroidLibraryDescriptorAncestry.withDerivedAnchor(left, right, LibraryTreeAncestryHint.parentHops(leftId, rightId)) { current ->
        rebind()
        current()
        read()
        rebind()
        current()
    }

    fun requireSameIdentity(held: ParcelFileDescriptor, reopened: ParcelFileDescriptor) {
        check(AndroidLibraryDescriptorAncestry.observe(held) == AndroidLibraryDescriptorAncestry.observe(reopened)) {
            "The reopened directory descriptor does not match its held identity and mount"
        }
    }
}
