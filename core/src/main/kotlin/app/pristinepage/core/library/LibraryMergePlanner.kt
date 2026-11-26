package app.pristinepage.core.library

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.util.Collections

/** Plans catalog changes without opening databases or treating any display label as a filesystem path. */
object LibraryMergePlanner {
    private const val MAX_LABEL_LENGTH = 160

    data class Original(val sha256: String, val byteCount: Long)
    data class Page(val key: String, val filename: String, val original: Original, val sourcePixelSha256: String, val conversionFingerprint: String)
    data class Album(val key: String, val title: String, val pages: List<Page>)

    /** The destination slot and original are retained; its next accepted edit and revision-bound OCR come from the incoming slot. */
    data class IncomingState(val destinationPageKey: String, val incomingPageKey: String)

    enum class Reason { NEW_TITLE, AMBIGUOUS_ALBUM, AMBIGUOUS_PAGES, ORIGINAL_CONFLICT, INCOMPATIBLE_SOURCE_BINDING, INCOMPATIBLE_SAVED_STATE }

    sealed interface Decision {
        /** Incoming locators are not destination insert IDs. The catalog writer resolves/remaps all colliding IDs and references. */
        val incomingAlbumKey: String
        data class Add(override val incomingAlbumKey: String, val title: String, val reason: Reason) : Decision
        data class Merge(override val incomingAlbumKey: String, val destinationAlbumKey: String,
            val useIncomingState: List<IncomingState>, val appendPageKeys: List<String>) : Decision
    }

    /** Inputs are bounded, stable catalog snapshots for this call; storage admits actual IDs and source bindings before invoking the planner. */
    fun plan(destination: List<Album>, incoming: List<Album>,
        additionalConflict: (destination: Page, incoming: Page) -> Reason? = { _, _ -> null },
    ): List<Decision> = plan(destination, incoming, additionalConflict) { null }

    /** The storage layer can refuse a complete album proposal after all page matches are known. Name allocation is still centralized here. */
    fun plan(destination: List<Album>, incoming: List<Album>,
        additionalConflict: (destination: Page, incoming: Page) -> Reason?,
        mergeConflict: (Decision.Merge) -> Reason?,
    ): List<Decision> {
        validate(destination)
        validate(incoming)
        val targetTitles = destination.groupBy { canonical(it.title) }
        val targetAlbumTitles = destination.associate { it.key to canonical(it.title) }
        val targetPageOwners = HashMap<String, String>()
        for (album in destination) {
            for (page in album.pages) targetPageOwners[page.key] = album.key
        }
        val incomingTitleCounts = incoming.groupingBy { canonical(it.title) }.eachCount()
        // Reserve all literal incoming titles up front; an early rename cannot steal a subsequent genuine title.
        val reserved = (targetTitles.keys + incomingTitleCounts.keys).toMutableSet()
        val nextSuffix = mutableMapOf<String, Int>()
        return immutable(incoming.map { album ->
            val key = canonical(album.title)
            val matches = targetTitles[key].orEmpty()
            when {
                matches.isEmpty() && incomingTitleCounts.getValue(key) == 1 -> Decision.Add(album.key, album.title, Reason.NEW_TITLE)
                matches.size != 1 || incomingTitleCounts.getValue(key) != 1 ||
                    targetAlbumTitles[album.key]?.let { it != key } == true ->
                    Decision.Add(album.key, alternateTitle(album.title, reserved, nextSuffix), Reason.AMBIGUOUS_ALBUM)
                else -> {
                    val proposed = match(matches.single(), album, targetPageOwners, additionalConflict) { reason ->
                        Decision.Add(album.key, alternateTitle(album.title, reserved, nextSuffix), reason)
                    }
                    val refusal = (proposed as? Decision.Merge)?.let(mergeConflict)
                    if (refusal == null) proposed else Decision.Add(album.key, alternateTitle(album.title, reserved, nextSuffix), refusal)
                }
            }
        })
    }

    private inline fun match(destination: Album, incoming: Album, targetPageOwners: Map<String, String>,
        additionalConflict: (Page, Page) -> Reason?, conflict: (Reason) -> Decision.Add,
    ): Decision {
        val keys = destination.pages.associateBy { it.key }
        val names = destination.pages.associateBy { canonical(it.filename) }
        val matched = mutableSetOf<String>()
        val updates = ArrayList<IncomingState>()
        val additions = ArrayList<String>()
        for (page in incoming.pages) {
            if (targetPageOwners[page.key]?.let { it != destination.key } == true) return conflict(Reason.AMBIGUOUS_PAGES)
            val byKey = keys[page.key]
            val byName = names[canonical(page.filename)]
            if (byKey != null && byKey.key != byName?.key) {
                if (byName == null && byKey.original != page.original) return conflict(Reason.ORIGINAL_CONFLICT)
                return conflict(Reason.AMBIGUOUS_PAGES)
            }
            if (byName == null) {
                additions += page.key
                continue
            }
            if (!matched.add(byName.key)) return conflict(Reason.AMBIGUOUS_PAGES)
            if (byName.original != page.original) return conflict(Reason.ORIGINAL_CONFLICT)
            // The consumer must additionally compare the verified encoded bytes before final application; these IDs plan only.
            if (byName.sourcePixelSha256 != page.sourcePixelSha256 || byName.conversionFingerprint != page.conversionFingerprint) {
                return conflict(Reason.INCOMPATIBLE_SOURCE_BINDING)
            }
            additionalConflict(byName, page)?.let { return conflict(it) }
            updates += IncomingState(byName.key, page.key)
        }
        return Decision.Merge(incoming.key, destination.key, immutable(updates), immutable(additions))
    }

    private fun validate(albums: List<Album>) {
        val albumKeys = HashSet<String>()
        val pageKeys = HashSet<String>()
        for (album in albums) {
            require(album.key.isNotBlank() && albumKeys.add(album.key)) { "Missing or duplicate album identity" }
            validateLabel(album.title)
            val pageNames = HashSet<String>()
            for (page in album.pages) {
                require(page.key.isNotBlank() && pageKeys.add(page.key)) { "Missing or duplicate page identity" }
                require(LibraryScanName.isValid(page.filename))
                require(pageNames.add(canonical(page.filename))) { "Duplicate page filename" }
                require(page.original.byteCount > 0 && page.conversionFingerprint.isNotBlank())
                requireSha256(page.original.sha256)
                requireSha256(page.sourcePixelSha256)
            }
        }
    }

    private fun validateLabel(label: String) {
        require(label.length in 1..MAX_LABEL_LENGTH && label.isNotBlank() && label == label.trim())
        requireWellFormedUnicode(label)
    }

    private fun alternateTitle(title: String, reserved: MutableSet<String>, nextSuffix: MutableMap<String, Int>): String {
        val key = canonical(title)
        var index = nextSuffix[key] ?: 1
        while (true) {
            val suffix = if (index == 1) " (imported)" else " (imported $index)"
            var prefix = title.take(MAX_LABEL_LENGTH - suffix.length)
            if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix = prefix.dropLast(1)
            val candidate = prefix.trimEnd() + suffix
            index++
            if (reserved.add(canonical(candidate))) {
                nextSuffix[key] = index
                return candidate
            }
        }
    }

    private fun canonical(label: String): String = libraryComparisonKey(label)

    private fun <T> immutable(items: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(items))
}
