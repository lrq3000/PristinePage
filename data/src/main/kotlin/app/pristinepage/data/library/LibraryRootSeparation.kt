package app.pristinepage.data.library

import java.io.IOException

/** Compares complete, descriptor-backed chains on one admitted filesystem; it does not resolve URIs, open files, or grant write access. */
internal class LibraryRootSeparation private constructor(
    private val left: () -> List<Identity>, private val right: () -> List<Identity>,
    private val leftBefore: List<Identity>, private val rightBefore: List<Identity>,
) {
    data class Identity(val device: Long, val inode: Long)

    /** Re-observe both chains while their original root descriptors are still held. An equal leaf by itself is not enough. */
    fun requireCurrent() {
        if (chain(left) != leftBefore || chain(right) != rightBefore) throw IOException("Library folder ancestry changed")
    }

    /** Stronger observation for callers that know the two selected roots must be immediate children of the anchor. */
    fun requireCurrentSiblings() {
        if (leftBefore.size != 2 || rightBefore.size != 2) throw IOException("Library folders are not direct children of the selected anchor")
        requireCurrent()
    }

    companion object {
        private const val MAX_DEPTH = 128

        /** An observation is leaf first through the independently pinned filesystem anchor. The adapter must prove the same mount, including where device IDs alone cannot distinguish bind mounts. */
        fun admit(left: () -> List<Identity>, right: () -> List<Identity>): LibraryRootSeparation {
            val a = chain(left)
            val b = chain(right)
            if (a.last() != b.last() || a.first() in b || b.first() in a) {
                throw IOException("Library folders overlap or have no proven shared storage anchor")
            }
            val common = a.indexOfFirst(b.toHashSet()::contains)
            if (a.subList(common, a.size) != b.subList(b.indexOf(a[common]), b.size)) {
                throw IOException("Library folder ancestry is inconsistent")
            }
            return LibraryRootSeparation(left, right, a, b).also { it.requireCurrent() }
        }

        private fun chain(observe: () -> List<Identity>): List<Identity> {
            val seen = observe()
            if (seen.size !in 2..MAX_DEPTH) throw IOException("Library folder ancestry is incomplete")
            val owned = seen.toList()
            val device = owned.first().device
            if (owned.any { it.inode <= 0L || it.device != device } || owned.toSet().size != owned.size) {
                throw IOException("Library folder ancestry is ambiguous")
            }
            return owned
        }
    }
}
