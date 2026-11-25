package app.pristinepage.data.repository

import app.pristinepage.data.model.requireOpaqueId
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Persistence format used by ordinary Undo. Portable mapping never changes membership or creates a pointer without an exact mapped owner. */
internal object LibraryUndoCodec {
    @Serializable data class RecipePointer(val pageId: String, val sourceId: String, val recipeRevision: Long)
    @Serializable data class RecipeState(val version: Int = 1, val pages: List<RecipePointer>)
    @Serializable data class MembershipPointer(val pageId: String, val sourceId: String, val state: String, val ordinal: Int?, val removedAt: Long?)
    @Serializable data class MembershipState(val documentId: String, val pages: List<MembershipPointer>, val version: Int = 1)

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun encodeRecipe(pages: List<RecipePointer>): ByteArray =
        json.encodeToString(RecipeState.serializer(), RecipeState(pages = pages)).encodeToByteArray()

    fun decodeRecipe(bytes: ByteArray, requireCanonical: Boolean = false): RecipeState {
        val state = json.decodeFromString(RecipeState.serializer(), boundedText(bytes))
        require(state.version == 1)
        validateRecipe(state.pages)
        if (requireCanonical) require(bytes.contentEquals(encodeRecipe(state.pages)))
        return state
    }

    fun encodeMembership(document: String, pages: List<MembershipPointer>): ByteArray =
        canonical(json.encodeToJsonElement(MembershipState.serializer(), MembershipState(document, pages.sortedBy { it.pageId })))
            .toString().encodeToByteArray()

    fun decodeMembership(bytes: ByteArray): MembershipState {
        val state = json.decodeFromString(MembershipState.serializer(), boundedText(bytes))
        require(state.version == 1)
        validateMembership(state.documentId, state.pages)
        require(bytes.contentEquals(encodeMembership(state.documentId, state.pages)))
        return state
    }

    fun remapRecipe(bytes: ByteArray, translate: (String, String, Long) -> RecipePointer?): ByteArray {
        val mapped = decodeRecipe(bytes, requireCanonical = true).pages.map { old ->
            requireNotNull(translate(old.pageId, old.sourceId, old.recipeRevision))
        }
        validateRecipe(mapped)
        return encodeRecipe(mapped)
    }

    fun remapMembership(bytes: ByteArray, from: String, to: String, translate: (String, String) -> Pair<String, String>?): ByteArray {
        val state = decodeMembership(bytes)
        require(state.documentId == from)
        val mapped = state.pages.map { old ->
            val (page, source) = requireNotNull(translate(old.pageId, old.sourceId))
            old.copy(pageId = page, sourceId = source)
        }
        validateMembership(to, mapped)
        return encodeMembership(to, mapped)
    }

    /** A pair must describe the exact scope of the recorded edit, not just two independently canonical states. */
    fun validateMembershipEdit(action: String, document: String, before: MembershipState, after: MembershipState): MembershipPointer? {
        require(before.documentId == document && after.documentId == document && before.pages.all { it.state == "ACTIVE" })
        return when (action) {
            "PAGE_DUPLICATE" -> {
                val beforeIds = before.pages.mapTo(HashSet()) { it.pageId }
                val added = after.pages.filter { it.pageId !in beforeIds }
                require(after.pages.size == before.pages.size + 1 && added.size == 1 && after.pages.all { it.state == "ACTIVE" })
                val copy = added.single()
                val ordinal = requireNotNull(copy.ordinal)
                require(ordinal > 0 && before.pages.singleOrNull { it.ordinal == ordinal - 1 }?.sourceId == copy.sourceId)
                val expected = before.pages.map { page ->
                    val old = requireNotNull(page.ordinal)
                    page.copy(ordinal = if (old >= ordinal) old + 1 else old)
                } + copy
                require(expected.sortedBy { it.pageId } == after.pages)
                copy
            }
            "PAGE_REMOVE", "PAGE_REORDER" -> {
                require(before.pages.map { it.pageId to it.sourceId } == after.pages.map { it.pageId to it.sourceId })
                if (action == "PAGE_REORDER") {
                    require(after.pages.all { it.state == "ACTIVE" })
                } else {
                    val removed = requireNotNull(after.pages.singleOrNull { it.state == "REMOVED" })
                    val ordinal = requireNotNull(before.pages.single { it.pageId == removed.pageId }.ordinal)
                    val expected = before.pages.map { page ->
                        val old = requireNotNull(page.ordinal)
                        if (page.pageId == removed.pageId) page.copy(state = "REMOVED", ordinal = null, removedAt = removed.removedAt)
                        else page.copy(ordinal = if (old > ordinal) old - 1 else old)
                    }
                    require(expected == after.pages)
                }
                null
            }
            else -> throw IllegalArgumentException("Unsupported membership action")
        }
    }

    private fun validateRecipe(pages: List<RecipePointer>) {
        require(pages.size in 1..100 && pages.map { it.pageId }.toSet().size == pages.size)
        for (page in pages) {
            requireOpaqueId(page.pageId)
            requireOpaqueId(page.sourceId)
            require(page.recipeRevision >= 1)
        }
    }

    private fun validateMembership(document: String, pages: List<MembershipPointer>) {
        requireOpaqueId(document)
        require(pages.size in 1..100 && pages.map { it.pageId }.toSet().size == pages.size)
        for (page in pages) {
            requireOpaqueId(page.pageId)
            requireOpaqueId(page.sourceId)
            require((page.state == "ACTIVE" && page.ordinal in 0..99 && page.removedAt == null) ||
                (page.state == "REMOVED" && page.ordinal == null && page.removedAt != null))
        }
        val ordinals = pages.filter { it.state == "ACTIVE" }.map { checkNotNull(it.ordinal) }.sorted()
        require(ordinals == ordinals.indices.toList())
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    internal fun boundedText(bytes: ByteArray): String {
        require(bytes.size in 1..524288)
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) { throw IllegalArgumentException("Invalid Undo text", failure) }
        // Bound actual structural depth before the serializer recurses. Quoted delimiters, including those after an escaped quote, cannot hide nesting.
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
                continue
            }
            when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 8) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        return text
    }
}
