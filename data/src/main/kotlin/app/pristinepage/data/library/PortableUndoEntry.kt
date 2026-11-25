package app.pristinepage.data.library

import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.db.UndoEntryRow
import app.pristinepage.data.model.requireOpaqueId
import app.pristinepage.data.repository.LibraryUndoCodec
import java.util.Collections

/** A paired, catalog-owned Undo record, not authority to execute it against a current document. */
internal class PortableUndoEntry private constructor(private val verified: UndoEntryRow, sources: Set<String>) {
    val row: UndoEntryRow get() = verified.copy(beforeState = verified.beforeState.copyOf(), afterState = verified.afterState.copyOf())
    val pinnedSources: Set<String> = Collections.unmodifiableSet(LinkedHashSet(sources))
    val id get() = verified.undoId
    val isMembership get() = !isRecipe(verified.action)
    val metadataBytes get() = 128L + verified.beforeState.size + verified.afterState.size + 128L * pinnedSources.size
    val recipePageIds: Set<String> get() =
        if (isMembership) emptySet() else LibraryUndoCodec.decodeRecipe(verified.beforeState, requireCanonical = true).pages.mapTo(HashSet()) { it.pageId }

    fun sameRecord(other: PortableUndoEntry): Boolean =
        verified.copy(beforeState = other.verified.beforeState, afterState = other.verified.afterState) == other.verified &&
            verified.beforeState.contentEquals(other.verified.beforeState) && verified.afterState.contentEquals(other.verified.afterState) &&
            pinnedSources == other.pinnedSources

    fun remap(id: String, destinationDocumentId: String,
        translate: (String, String, Long) -> LibraryUndoCodec.RecipePointer?, membership: (String, String) -> Pair<String, String>?,
    ): UndoEntryRow {
        fun remap(bytes: ByteArray) = if (isMembership) {
            LibraryUndoCodec.remapMembership(bytes, verified.documentId, destinationDocumentId, membership)
        } else LibraryUndoCodec.remapRecipe(bytes, translate)
        return verified.copy(undoId = id, documentId = destinationDocumentId,
            beforeState = remap(verified.beforeState), afterState = remap(verified.afterState))
    }

    /** Index only the verified catalog, never IDs read from a claimed Undo payload. */
    class Owners(album: RoomLibrarySavedCatalog.Album) {
        private val document = album.row
        private val pages = album.pages.associate { it.row.pageId to it.row }
        private val revisions = album.pages.associate { page -> page.row.pageId to page.revisions.associateBy { it.recipeRevision } }
        fun admit(candidate: UndoEntryRow): PortableUndoEntry =
            admit(candidate, document, pages::get) { page, revision -> revisions[page]?.get(revision) }
    }

    companion object {
        const val MAX_STATE_BYTES = 524_288
        private fun isRecipe(action: String) = action == "RECIPE_EDIT" || action == "BATCH_RECIPE_EDIT"

        /** Both halves refer only to retained pages in the same album. Recipe revisions must be the actual before-to-after transition. */
        fun admit(candidate: UndoEntryRow, album: DocumentRow, page: (String) -> PageRow?,
            revision: (String, Long) -> RecipeRevisionRow?): PortableUndoEntry {
            requireOpaqueId(candidate.undoId)
            requireOpaqueId(candidate.documentId)
            require(candidate.documentId == album.documentId && album.lifecycle == "ACTIVE" && album.deletedAt == null)
            require(candidate.appliedDocumentRevision in 1..album.documentRevision)
            require(candidate.state in setOf("AVAILABLE", "APPLIED", "EXPIRED"))
            // Detach only bounded bytes; validation and eventual write must see the same two immutable states.
            require(candidate.beforeState.size in 1..MAX_STATE_BYTES && candidate.afterState.size in 1..MAX_STATE_BYTES)
            val exact = candidate.copy(beforeState = candidate.beforeState.copyOf(), afterState = candidate.afterState.copyOf())
            fun requireOwner(pageId: String, sourceId: String) {
                val row = requireNotNull(page(pageId))
                require(row.pageId == pageId && row.documentId == exact.documentId && row.sourceId == sourceId)
            }
            val sources = when (exact.action) {
                "RECIPE_EDIT", "BATCH_RECIPE_EDIT" -> {
                    val before = LibraryUndoCodec.decodeRecipe(exact.beforeState, requireCanonical = true).pages
                    val after = LibraryUndoCodec.decodeRecipe(exact.afterState, requireCanonical = true).pages
                    require(before.size == after.size && (exact.action != "RECIPE_EDIT" || before.size == 1))
                    for ((old, next) in before.zip(after)) {
                        require(old.pageId == next.pageId && old.sourceId == next.sourceId && next.recipeRevision > old.recipeRevision)
                        requireOwner(old.pageId, old.sourceId)
                        val prior = requireNotNull(revision(old.pageId, old.recipeRevision))
                        val following = requireNotNull(revision(next.pageId, next.recipeRevision))
                        require(prior.pageId == old.pageId && prior.sourceId == old.sourceId && prior.recipeRevision == old.recipeRevision)
                        require(following.pageId == next.pageId && following.sourceId == next.sourceId &&
                            following.recipeRevision == next.recipeRevision && following.parentRevision == prior.recipeRevision)
                        // Batch save records only changed pages; ordinary single-page save may append identical bytes.
                        if (exact.action == "BATCH_RECIPE_EDIT") require(!prior.canonicalRecipe.contentEquals(following.canonicalRecipe))
                    }
                    before.mapTo(LinkedHashSet()) { it.sourceId }
                }
                "PAGE_REORDER", "PAGE_REMOVE", "PAGE_DUPLICATE" -> {
                    val before = LibraryUndoCodec.decodeMembership(exact.beforeState)
                    val after = LibraryUndoCodec.decodeMembership(exact.afterState)
                    LibraryUndoCodec.validateMembershipEdit(exact.action, exact.documentId, before, after)
                    if (exact.action == "PAGE_REORDER") require(before != after)
                    for (claimed in before.pages + after.pages) requireOwner(claimed.pageId, claimed.sourceId)
                    before.pages.mapTo(LinkedHashSet()) { it.sourceId }
                }
                else -> throw IllegalArgumentException("Unsupported library Undo action")
            }
            return PortableUndoEntry(exact, sources)
        }
    }
}
