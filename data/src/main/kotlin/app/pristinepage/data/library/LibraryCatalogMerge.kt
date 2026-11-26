package app.pristinepage.data.library

import app.pristinepage.core.library.LibraryMergePlanner
import app.pristinepage.core.library.LibraryMergePlanner.Decision
import app.pristinepage.core.library.LibraryMergePlanner.Reason
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceCaptureDayRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.library.RoomLibrarySavedCatalog.Album
import app.pristinepage.data.library.RoomLibrarySavedCatalog.Page
import app.pristinepage.data.library.RoomLibrarySavedCatalog.Snapshot
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.model.TransactionId
import app.pristinepage.data.repository.LibraryUndoCodec.RecipePointer
import java.io.IOException
import java.util.Collections
import java.util.UUID

/** Dry run over two independently admitted saved catalogs. It makes neither the file proof nor the authorization to publish. */
internal object LibraryCatalogMerge {
    data class RequiredMatch(val destinationBlobId: String, val incomingBlobId: String)

    class Candidate(
        val catalog: Snapshot,
        requiredMatches: Collection<RequiredMatch>,
        incomingBlobByCandidate: Map<String, String>,
    ) {
        val requiredMatches: List<RequiredMatch> = Collections.unmodifiableList(ArrayList(requiredMatches))
        val incomingBlobByCandidate: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(incomingBlobByCandidate))
    }

    fun plan(operationId: String, destination: Snapshot, incoming: Snapshot, checkpoint: () -> Unit = {}): Candidate {
        TransactionId(operationId)
        val target = IndexedCatalog(destination)
        val source = IndexedCatalog(incoming)
        val targetAlbums = target.plannerAlbums()
        val incomingAlbums = source.plannerAlbums()
        fun decide(conflictingBlobs: Set<String>) = LibraryMergePlanner.plan(targetAlbums, incomingAlbums, { local, received ->
            checkpoint()
            if (target.blobForPage(local.key).sourceBlobId in conflictingBlobs) Reason.INCOMPATIBLE_SAVED_STATE
            else compatibility(target, source, local.key, received.key)
        }, { merge -> undoConflict(target, source, merge, checkpoint) })
        val firstPass = decide(emptySet())
        val conflictingBlobs = conflictingCaptureDays(firstPass, target, source, checkpoint)
        return Composition(operationId, target, source, checkpoint).apply(
            if (conflictingBlobs.isEmpty()) firstPass else decide(conflictingBlobs),
        )
    }

    private fun undoConflict(target: IndexedCatalog, incoming: IndexedCatalog, decision: Decision.Merge, checkpoint: () -> Unit): Reason? {
        val local = target.albums.getValue(decision.destinationAlbumKey)
        val received = incoming.albums.getValue(decision.incomingAlbumKey)
        // Independent histories share one rank window in Room. Separating albums preserves both windows without reviving or evicting any command.
        if (local.undo.isNotEmpty() && received.undo.isNotEmpty() || received.undo.any { it.isMembership }) return Reason.INCOMPATIBLE_SAVED_STATE
        if (local.undo.isEmpty()) return null
        if (local.undo.any { it.isMembership } && decision.appendPageKeys.any { incoming.pages.getValue(it).row.state == "ACTIVE" }) {
            return Reason.INCOMPATIBLE_SAVED_STATE
        }
        val changed = decision.useIncomingState.filter { (a, b) ->
            checkpoint()
            !sameAcceptedRecipe(target.pages.getValue(a), incoming.pages.getValue(b))
        }.mapTo(HashSet()) { it.destinationPageKey }
        // A changed current recipe could invalidate an actionable local Undo or make a previously stale after-state match again.
        return if (changed.isNotEmpty() && local.undo.any { entry -> checkpoint(); entry.recipePageIds.any(changed::contains) }) {
            Reason.INCOMPATIBLE_SAVED_STATE
        } else null
    }

    private fun conflictingCaptureDays(
        decisions: List<Decision>, target: IndexedCatalog, incoming: IndexedCatalog, checkpoint: () -> Unit,
    ): Set<String> {
        val projections = HashMap<String, SourceCaptureDayRow>()
        val conflicting = HashSet<String>()
        // Compare all proposed assignments before modifying any catalog row, including assignments from different albums to one shared blob.
        for (decision in decisions) if (decision is Decision.Merge) for ((destinationPage, incomingPage) in decision.useIncomingState) {
            checkpoint()
            val destination = target.blobForPage(destinationPage).sourceBlobId
            if (destination in target.days) continue // The first pass already compared every match against this authoritative row.
            val incomingDay = incoming.days[incoming.blobForPage(incomingPage).sourceBlobId] ?: continue
            val projection = incomingDay.copy(sourceBlobId = destination)
            val prior = projections.putIfAbsent(destination, projection)
            if (prior != null && prior != projection) conflicting += destination
        }
        return conflicting
    }

    private fun compatibility(target: IndexedCatalog, incoming: IndexedCatalog, localId: String, incomingId: String): Reason? {
        val local = target.pages.getValue(localId)
        val received = incoming.pages.getValue(incomingId)
        val left = target.sources.getValue(local.row.sourceId)
        val right = incoming.sources.getValue(received.row.sourceId)
        val leftBlob = target.originals.getValue(left.sourceBlobId)
        val rightBlob = incoming.originals.getValue(right.sourceBlobId)
        if (left.width != right.width || left.height != right.height || left.exifOrientation != right.exifOrientation ||
            left.profileSha256 != right.profileSha256 || leftBlob.mime != rightBlob.mime ||
            leftBlob.encodedWidth != rightBlob.encodedWidth || leftBlob.encodedHeight != rightBlob.encodedHeight
        ) return Reason.INCOMPATIBLE_SOURCE_BINDING
        val priorDay = target.days[left.sourceBlobId]
        val incomingDay = incoming.days[right.sourceBlobId]
        // Keep separate records rather than hide a live scan or overwrite contradictory capture metadata.
        if (local.row.state != received.row.state ||
            (priorDay != null && incomingDay != null && priorDay.epochDay != incomingDay.epochDay)
        ) return Reason.INCOMPATIBLE_SAVED_STATE
        // The format stores one saved OCR result per scan. Never silently discard the only saved result or bind it to a different recipe.
        if (local.savedOcr != null && received.savedOcr == null && !sameAcceptedRecipe(local, received)) {
            return Reason.INCOMPATIBLE_SAVED_STATE
        }
        return null
    }

    private fun sameAcceptedRecipe(left: Page, right: Page): Boolean {
        val a = left.revisions.single { it.recipeRevision == left.row.currentRecipeRevision }
        val b = right.revisions.single { it.recipeRevision == right.row.currentRecipeRevision }
        return a.recipeSha256 == b.recipeSha256 && a.canonicalRecipe.contentEquals(b.canonicalRecipe)
    }

    private class IndexedCatalog(val catalog: Snapshot) {
        val albums = catalog.albums.associateBy { it.row.documentId }
        val pages = catalog.albums.flatMap { it.pages }.associateBy { it.row.pageId }
        val sources = catalog.sources.associateBy(SourceRow::sourceId)
        val originals = catalog.originals.associateBy(SourceBlobRow::sourceBlobId)
        val days = catalog.days.associateBy(SourceCaptureDayRow::sourceBlobId)
        val jobs = pages.values.mapNotNull { it.savedOcr?.producerId?.value }
        val undo = catalog.albums.flatMap { album -> album.undo.map { it.id } }

        fun blobForPage(page: String) = originals.getValue(sources.getValue(pages.getValue(page).row.sourceId).sourceBlobId)

        fun plannerAlbums() = catalog.albums.map { album ->
            LibraryMergePlanner.Album(album.row.documentId, album.row.title, album.pages.map { page ->
                val source = sources.getValue(page.row.sourceId)
                val blob = originals.getValue(source.sourceBlobId)
                LibraryMergePlanner.Page(page.row.pageId, page.filename, LibraryMergePlanner.Original(blob.byteSha256, blob.byteCount),
                    source.pixelSha256, source.conversionFingerprint)
            })
        }
    }

    private class Composition(
        operation: String, private val target: IndexedCatalog, private val incoming: IndexedCatalog, private val checkpoint: () -> Unit,
    ) {
        private val documents = Ids(operation, "album", target.albums.keys, incoming.albums.keys)
        private val pages = Ids(operation, "page", target.pages.keys, incoming.pages.keys)
        private val sources = Ids(operation, "source", target.sources.keys, incoming.sources.keys)
        private val blobs = Ids(operation, "blob", target.originals.keys, incoming.originals.keys)
        private val jobs = Ids(operation, "ocr-job", target.jobs, incoming.jobs)
        private val undoIds = Ids(operation, "undo", target.undo, incoming.undo)
        private val albums = LinkedHashMap(target.albums)
        private val originalRows = LinkedHashMap(target.originals)
        private val sourceRows = LinkedHashMap(target.sources)
        private val days = LinkedHashMap(target.days)
        private val incomingBlobs = LinkedHashMap<String, String>()
        private val requiredMatches = LinkedHashSet<RequiredMatch>()

        fun apply(decisions: List<Decision>): Candidate {
            for (decision in decisions) {
                checkpoint()
                val source = incoming.albums.getValue(decision.incomingAlbumKey)
                when (decision) {
                    is Decision.Add -> {
                        val id = documents.resolve(source.row.documentId)
                        val mapping = source.pages.associate { it.row.pageId to copyPage(it, id, it.row.ordinal) }
                        val added = Album(source.row.copy(documentId = id, title = decision.title), inPortableOrder(mapping.values.toList()))
                        albums[id] = withIncomingUndo(added, source, mapping)
                    }
                    is Decision.Merge -> mergeAlbum(albums.getValue(decision.destinationAlbumKey), source, decision)
                }
            }
            val catalog = Snapshot(albums.values.sortedBy { it.row.documentId }, sourceRows.values.toList(), originalRows.values.toList(), days.values.toList())
            checkLimits(catalog)
            return Candidate(catalog, requiredMatches, incomingBlobs)
        }

        private fun mergeAlbum(local: Album, received: Album, decision: Decision.Merge) {
            if (decision.appendPageKeys.isEmpty() && decision.useIncomingState.isEmpty()) return
            val id = local.row.documentId
            val matches = decision.useIncomingState.associate { it.destinationPageKey to it.incomingPageKey }
            val mapping = HashMap<String, Page>()
            val retained = local.pages.map { page ->
                matches[page.row.pageId]?.let { key -> updatePage(page, incoming.pages.getValue(key)).also { mapping[key] = it } } ?: page
            }
            var ordinal = retained.count { it.row.ordinal != null }
            val appended = decision.appendPageKeys.map {
                val page = incoming.pages.getValue(it)
                copyPage(page, id, if (page.row.ordinal == null) null else ordinal++).also { copy -> mapping[it] = copy }
            }
            // Incoming callbacks can carry any previously issued document revision, not just the last Undo's revision.
            val base = if (received.undo.isEmpty()) local.row.documentRevision else maxOf(local.row.documentRevision, received.row.documentRevision)
            val merged = Album(local.row.copy(documentRevision = advance(base, 1),
                updatedAt = maxOf(local.row.updatedAt, received.row.updatedAt)), inPortableOrder(retained + appended), local.undo)
            albums[id] = withIncomingUndo(merged, received, mapping)
        }

        private fun withIncomingUndo(album: Album, source: Album, mappedPages: Map<String, Page>): Album {
            if (source.undo.isEmpty()) return album
            check(album.undo.isEmpty())
            val owners = PortableUndoEntry.Owners(album)
            val revisions by lazy {
                mappedPages.mapValues { (sourceId, destination) ->
                    val prior = incoming.pages.getValue(sourceId).revisions
                    prior.zip(destination.revisions.takeLast(prior.size)).associate { (old, new) -> old.recipeRevision to new.recipeRevision }
                }
            }
            fun owner(page: String, sourceId: String): Pair<String, String>? {
                if (incoming.pages[page]?.row?.sourceId != sourceId) return null
                val row = mappedPages[page]?.row ?: return null
                return row.pageId to row.sourceId
            }
            val undo = source.undo.map { entry ->
                checkpoint()
                val row = entry.remap(undoIds.resolve(entry.id), album.row.documentId, { page, sourceId, revision ->
                    val (newPage, newSource) = owner(page, sourceId) ?: return@remap null
                    revisions[page]?.get(revision)?.let { RecipePointer(newPage, newSource, it) }
                }, ::owner)
                owners.admit(row)
            }
            return Album(album.row, album.pages, undo)
        }

        private fun updatePage(local: Page, received: Page): Page {
            checkpoint()
            val row = local.row
            val targetBlob = target.sources.getValue(row.sourceId).sourceBlobId
            val incomingBlob = incoming.sources.getValue(received.row.sourceId).sourceBlobId
            requiredMatches += RequiredMatch(targetBlob, incomingBlob)
            incoming.days[incomingBlob]?.let { newDay ->
                val mapped = newDay.copy(sourceBlobId = targetBlob)
                val prior = days.putIfAbsent(targetBlob, mapped)
                // The planner already reconciled assignments to a shared original. Recheck the invariant before retaining either projection.
                if (prior != null && prior != mapped) throw IOException("Conflicting capture days for a shared original")
            }
            val history = local.revisions
            val imported = received.revisions
            val base = history.maxOf { it.recipeRevision }
            if (history.size + imported.size > 1_024) limit()
            val remap = imported.mapIndexed { i, revision -> revision.recipeRevision to advance(base, i + 1) }.toMap()
            // With no incoming result the planner allows retention only for an identical accepted recipe. Keep its real producing revision, not a fabricated re-run.
            val keepLocalOcr = received.savedOcr == null && local.savedOcr != null
            val updated = if (keepLocalOcr) row else row.copy(currentRecipeRevision = remap.getValue(received.row.currentRecipeRevision))
            val revisions = history + imported.map {
                it.copy(pageId = row.pageId, sourceId = row.sourceId, recipeRevision = remap.getValue(it.recipeRevision),
                    parentRevision = it.parentRevision?.let(remap::getValue))
            }
            val saved = if (keepLocalOcr) local.savedOcr else received.savedOcr?.let { rebindOcr(it, updated) }
            return Page(updated, local.filename, revisions, saved)
        }

        private fun copyPage(original: Page, album: String, ordinal: Int?): Page {
            checkpoint()
            val old = original.row
            val newSource = sources.resolve(old.sourceId)
            if (newSource !in sourceRows) {
                val source = incoming.sources.getValue(old.sourceId)
                val blob = incoming.originals.getValue(source.sourceBlobId)
                val newBlob = blobs.resolve(blob.sourceBlobId)
                if (newBlob !in originalRows) {
                    originalRows[newBlob] = blob.copy(sourceBlobId = newBlob, relativePath = "sources/$newBlob/original.bin")
                    incomingBlobs[newBlob] = blob.sourceBlobId
                    incoming.days[blob.sourceBlobId]?.let { days[newBlob] = it.copy(sourceBlobId = newBlob) }
                }
                sourceRows[newSource] = source.copy(sourceId = newSource, sourceBlobId = newBlob)
            }
            val row = old.copy(pageId = pages.resolve(old.pageId), documentId = album, sourceId = newSource, ordinal = ordinal)
            return Page(row, original.filename, original.revisions.map { it.copy(pageId = row.pageId, sourceId = row.sourceId) },
                original.savedOcr?.let { rebindOcr(it, row) })
        }

        private fun rebindOcr(saved: LibrarySavedOcr, row: PageRow): LibrarySavedOcr {
            val id = JobId(jobs.resolve(saved.producerId.value))
            val intent = saved.intent.copy(documentId = DocumentId(row.documentId), sourceId = SourceId(row.sourceId),
                input = saved.intent.input.copy(pageId = row.pageId, recipeRevision = row.currentRecipeRevision))
            return LibrarySavedOcr(id, intent, saved.text, saved.attempt, saved.createdAt, saved.updatedAt)
        }

        private fun checkLimits(catalog: Snapshot) {
            if (catalog.albums.size > 2_000 || catalog.sources.size > 4_096 || catalog.originals.size > 4_096) limit()
            var pages = 0
            var metadata = 0L
            for (album in catalog.albums) {
                checkpoint()
                metadata += 192L + 4L * album.row.title.length
                for (entry in album.undo) {
                    checkpoint()
                    metadata += entry.metadataBytes
                    if (metadata > 16L * 1024 * 1024) limit()
                }
                if (metadata > 16L * 1024 * 1024) limit()
                if (album.pages.count { it.row.ordinal != null } > 100) limit()
                pages += album.pages.size
                if (pages > 4_096) limit()
                for (page in album.pages) {
                    checkpoint()
                    if (page.revisionCount > 1_024) limit()
                    metadata += 256L + 4L * page.filename.length
                    for (revision in page.revisions) metadata += 128L + revision.canonicalRecipe.size
                    page.savedOcr?.let { metadata += 256L + 4L * it.text.value.length + it.intent.encode().size }
                    if (metadata > 16L * 1024 * 1024) limit()
                }
            }
        }
    }

    private class Ids(private val operation: String, private val kind: String, target: Collection<String>, incoming: Collection<String>) {
        private val existing = target.toHashSet()
        // Do not let a generated ID steal a real incoming ID that has not been visited yet.
        private val reserved = (target + incoming).toHashSet()
        private val mapped = HashMap<String, String>()

        fun resolve(id: String): String = mapped.getOrPut(id) {
            if (id !in existing) return@getOrPut id
            for (probe in 0 until 16) {
                val generated = UUID.nameUUIDFromBytes("pristinepage-merge-v1/$operation/$kind/$id/$probe".encodeToByteArray()).toString()
                if (reserved.add(generated)) return@getOrPut generated
            }
            throw IOException("No distinct catalog identity is available")
        }
    }

    private fun inPortableOrder(pages: List<Page>): List<Page> =
        pages.sortedWith(compareBy<Page> { it.row.ordinal ?: Int.MAX_VALUE }.thenBy { it.row.pageId })

    private fun advance(value: Long, by: Int): Long {
        if (value <= 0 || value > Long.MAX_VALUE - by) limit()
        return value + by
    }

    private fun limit(): Nothing = throw IOException("Merged library exceeds supported catalog limits")
}
