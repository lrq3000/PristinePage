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

    companion object {
        /** Both halves refer only to retained pages in the same album. Recipe revisions must be the actual before-to-after transition. */
        fun admit(candidate: UndoEntryRow, album: DocumentRow, page: (String) -> PageRow?,
            revision: (String, Long) -> RecipeRevisionRow?): PortableUndoEntry {
            requireOpaqueId(candidate.undoId)
            requireOpaqueId(candidate.documentId)
            require(candidate.documentId == album.documentId && album.lifecycle == "ACTIVE" && album.deletedAt == null)
            require(candidate.appliedDocumentRevision in 1..album.documentRevision)
            require(candidate.state in setOf("AVAILABLE", "APPLIED", "EXPIRED"))
            // Detach only bounded bytes; validation and eventual write must see the same two immutable states.
            require(candidate.beforeState.size in 1..524_288 && candidate.afterState.size in 1..524_288)
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
