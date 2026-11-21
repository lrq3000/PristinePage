package app.pristinepage.editor

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.model.*
import kotlinx.coroutines.CancellationException

internal enum class BatchLook(val recipeId: String) {
    NONE("none"), PAPER("paper"), NATURAL("natural"), GRAY("gray"), BINARY("binary"), DOCUMENT_COLOR("document-color")
}

internal data class BatchEditRejection(val page: PageId, val error: OperationError)

internal sealed interface BatchValue<out T> {
    data object Empty : BatchValue<Nothing>
    data object Mixed : BatchValue<Nothing>
    data class Shared<T>(val value: T) : BatchValue<T>
}

internal sealed interface BatchEditOutcome {
    data object Unchanged : BatchEditOutcome
    data object Busy : BatchEditOutcome
    data object NeedsRefresh : BatchEditOutcome
    data class Saved(val undo: UndoId) : BatchEditOutcome
    data class Conflict(val revision: Long) : BatchEditOutcome
    data class Failed(val error: OperationError) : BatchEditOutcome
}

/** Main-confined draft owner. Each history entry captures exact affected IDs; changing the selection never mutates those commands. */
internal class BatchEditSession(pages: List<EditablePage>,
    private val admit: (PageId, Recipe) -> OperationError?,
    private val save: suspend (DocumentId, Long, List<BatchRecipeChange>) -> DocumentMutationResult,
    initialSelection: Set<PageId> = emptySet(),
) {
    private data class Change(val before: Map<PageId, Recipe>, val after: Map<PageId, Recipe>)
    private val entries = pages.toList()
    private val document = entries.first().documentId
    private val revision = entries.first().documentRevision
    private val accepted = entries.associate { it.pageId to it.recipe }
    private val selected = LinkedHashSet(initialSelection)
    private val history = ArrayDeque<Change>()
    private val future = ArrayDeque<Change>()
    private val current = LinkedHashMap(accepted)
    private val generations = HashMap<PageId, Long>()
    private var closed = false
    private var uncertain = false
    var saving = false
        private set
    val selection: Set<PageId> get() = selected.toSet()
    val recipes: Map<PageId, Recipe> get() = current.toMap()
    val canUndo: Boolean get() = !saving && !closed && history.isNotEmpty()
    val canRedo: Boolean get() = !saving && !closed && future.isNotEmpty()
    val dirty: Boolean get() = current != accepted
    fun previewGeneration(page: PageId): Long = generations[page] ?: 1L

    init {
        require(entries.size in 1..100 && accepted.size == entries.size)
        require(entries.all { it.documentId == document && it.documentRevision == revision && RecipeCodec.hash(it.recipe) == it.recipeHash })
        require(selected.all { it in accepted })
    }

    fun toggle(page: PageId) {
        if (saving || closed || page !in accepted) return
        if (!selected.remove(page)) selected.add(page)
    }
    fun selectAll() { if (!saving && !closed) selected.addAll(accepted.keys) }
    fun clearSelection() { if (!saving && !closed) selected.clear() }

    fun rotateClockwise(): BatchEditRejection? = change { recipe ->
        recipe.copy(geometry = recipe.geometry.withOutputQuarterTurns((recipe.geometry.outputQuarterTurns + 1) % 4))
    }
    fun chooseLook(look: BatchLook): BatchEditRejection? = change { EditorLooks.select(it, look.recipeId) }

    fun rotation(): BatchValue<Int> = selectedValue { it.geometry.outputQuarterTurns }
    fun appearanceStrength(): BatchValue<Double?> = selectedValue { it.appearance.classicalStrength() }
    val canSetAppearanceStrength: Boolean get() {
        val first = selected.firstOrNull()?.let { current.getValue(it).appearance } ?: return false
        return first.classicalStrength() != null && selected.all { current.getValue(it).appearance::class == first::class }
    }

    private inline fun <T> selectedValue(read: (Recipe) -> T): BatchValue<T> {
        val first = selected.firstOrNull() ?: return BatchValue.Empty
        val value = read(current.getValue(first))
        return if (selected.all { read(current.getValue(it)) == value }) BatchValue.Shared(value) else BatchValue.Mixed
    }

    fun setAppearanceStrength(percent: Int): BatchEditRejection? {
        if (saving || closed || uncertain) return null
        val first = selected.firstOrNull() ?: return null
        val method = current.getValue(first).appearance
        val incompatible = selected.firstOrNull {
            val appearance = current.getValue(it).appearance
            appearance.classicalStrength() == null || appearance::class != method::class
        }
        if (incompatible != null || percent !in 0..100) {
            val id = incompatible ?: first
            return BatchEditRejection(id, OperationError(OperationErrorCode.INVALID_INPUT, false, "batch_strength_unavailable", id.value))
        }
        return change { recipe ->
            val strength = percent / 100.0
            val next = when (val appearance = recipe.appearance) {
                is AppearanceRecipe.PaperGainV1 -> appearance.copy(strength = strength)
                is AppearanceRecipe.NaturalLV1 -> appearance.copy(strength = strength)
                is AppearanceRecipe.DocumentColorV1 -> appearance.copy(strength = strength)
                else -> error("Strength command was not preflighted")
            }
            recipe.copy(appearance = next)
        }
    }

    private fun change(build: (Recipe) -> Recipe): BatchEditRejection? {
        if (saving || closed || selected.isEmpty()) return null
        val before = LinkedHashMap<PageId, Recipe>()
        val after = LinkedHashMap<PageId, Recipe>()
        for (id in selected) {
            val previous = current.getValue(id)
            val next = build(previous)
            admit(id, next)?.let { return BatchEditRejection(id, it) }
            if (next != previous) { before[id] = previous; after[id] = next }
        }
        if (after.isNotEmpty()) {
            replace(after)
            history.addLast(Change(before, after))
            if (history.size > 32) history.removeFirst()
            future.clear()
        }
        return null
    }

    private fun replace(changes: Map<PageId, Recipe>) {
        changes.keys.forEach { generations[it] = Math.addExact(previewGeneration(it), 1L) }
        current.putAll(changes)
    }

    fun undo() { if (canUndo) history.removeLast().let { replace(it.before); future.addLast(it) } }
    fun redo() { if (canRedo) future.removeLast().let { replace(it.after); history.addLast(it) } }

    fun cancel(): Boolean {
        if (saving || closed || uncertain) return false
        current.clear()
        current.putAll(accepted)
        closed = true
        return true
    }

    suspend fun done(): BatchEditOutcome {
        if (saving || closed) return BatchEditOutcome.Busy
        if (uncertain) return BatchEditOutcome.NeedsRefresh
        val changes = entries.mapNotNull { entry ->
            val recipe = current.getValue(entry.pageId)
            if (RecipeCodec.hash(recipe) == entry.recipeHash) null else BatchRecipeChange(entry.pageId, entry.recipeRevision, recipe)
        }
        if (changes.isEmpty()) { closed = true; return BatchEditOutcome.Unchanged }
        for (change in changes) admit(change.pageId, change.recipe)?.let { return BatchEditOutcome.Failed(it) }
        saving = true
        try {
            return when (val result = save(document, revision, changes)) {
                is DocumentMutationResult.Applied -> {
                    val undo = result.undoId
                    if (undo == null || result.documentRevision <= revision || result.documentRevision - revision != 1L ||
                        result.affectedPageIds != changes.map { it.pageId }) {
                        uncertain = true
                        BatchEditOutcome.NeedsRefresh
                    } else { closed = true; BatchEditOutcome.Saved(undo) }
                }
                is DocumentMutationResult.Conflict -> BatchEditOutcome.Conflict(result.actualRevision)
                is DocumentMutationResult.Rejected -> BatchEditOutcome.Failed(result.error)
            }
        } catch (canceled: CancellationException) {
            uncertain = true
            throw canceled
        } catch (_: Exception) {
            uncertain = true
            return BatchEditOutcome.NeedsRefresh
        } finally { saving = false }
    }
}

internal fun AppearanceRecipe.classicalStrength(): Double? = when (this) {
    is AppearanceRecipe.PaperGainV1 -> strength
    is AppearanceRecipe.NaturalLV1 -> strength
    is AppearanceRecipe.DocumentColorV1 -> strength
    else -> null
}
