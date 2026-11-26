package app.pristinepage.data

import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceCaptureDayRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.db.SourceReferenceRow
import app.pristinepage.data.db.UndoEntryRow
import app.pristinepage.data.library.LibraryCatalogMerge
import app.pristinepage.data.library.LibrarySavedOcr
import app.pristinepage.data.library.PortableUndoEntry
import app.pristinepage.data.library.RoomLibrarySavedCatalog
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.model.UndoId
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.LibraryUndoCodec
import app.pristinepage.data.repository.LibraryUndoCodec.RecipePointer
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.RoomDocumentRepository
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LibraryCatalogMergeTest {
    @Test fun matchingBytesPlanRetainsLocalHistoryAndRemapsIncomingAcceptedHistoryAndOcr() {
        val target = Fixture(1, "Receipts", Scan(1, "one.jpg", 'a', ocrJob = 8), Scan(2, "local.jpg", 'b'))
        val source = Fixture(2, "RECEIPTS", Scan(3, "ONE.JPG", 'a', ocrJob = 9, secondRevision = true), Scan(4, "incoming.jpg", 'c'))
        val merged = merge(target, source)
        val album = merged.catalog.albums.single()
        assertEquals(id(1), album.row.documentId)
        assertEquals(5L, album.row.documentRevision)
        assertEquals(listOf("one.jpg", "local.jpg", "incoming.jpg"), album.pages.map { it.filename })
        assertEquals(listOf(0, 1, 2), album.pages.map { it.row.ordinal })
        val match = album.pages.first()
        assertEquals(id(101), match.row.pageId)
        assertEquals(id(201), match.row.sourceId)
        assertEquals(3L, match.row.currentRecipeRevision)
        assertEquals(listOf(1L to null, 2L to null, 3L to 2L), match.revisions.map { it.recipeRevision to it.parentRevision })
        assertTrue(match.revisions.all { it.pageId == id(101) && it.sourceId == id(201) })
        assertEquals(listOf(0, 0, 1), match.revisions.map { RecipeCodec.decode(it.canonicalRecipe).geometry.outputQuarterTurns })
        val saved = checkNotNull(match.savedOcr)
        assertEquals(id(409), saved.producerId.value)
        assertEquals(OcrInput(id(101), 3, "a".repeat(64)), saved.intent.input)
        assertEquals(id(1), saved.intent.documentId.value)
        assertEquals(id(201), saved.intent.sourceId.value)
        assertEquals("  exact job 9\n", saved.text.value)
        assertEquals(listOf(id(301) to id(303)), merged.requiredMatches.map { it.destinationBlobId to it.incomingBlobId })
        assertEquals(setOf(id(304)), merged.incomingBlobByCandidate.keys)
        assertEquals(1L, target.catalog.albums.single().pages.first().row.currentRecipeRevision)
        assertEquals(2L, source.catalog.albums.single().pages.first().row.currentRecipeRevision)
    }

    @Test fun conflictingAlbumRemapsAllCollidingOwnershipWithoutChangingTheDestinationAlbum() {
        val target = Fixture(1, "Receipts", Scan(1, "same.jpg", 'a', ocrJob = 7))
        val source = Fixture(1, "Receipts", Scan(1, "same.jpg", 'b', ocrJob = 7), Scan(2, "extra.jpg", 'c', removed = true))
        val merged = merge(target, source)
        val (original, added) = merged.catalog.albums
        assertSame(target.catalog.albums.single(), original)
        assertEquals("Receipts (imported)", added.row.title)
        assertNotEquals(id(1), added.row.documentId)
        val (conflict, removed) = added.pages
        assertNotEquals(id(101), conflict.row.pageId)
        assertNotEquals(id(201), conflict.row.sourceId)
        assertEquals(added.row.documentId, conflict.row.documentId)
        assertEquals(conflict.row.pageId, conflict.revisions.single().pageId)
        assertEquals(conflict.row.sourceId, conflict.revisions.single().sourceId)
        val saved = checkNotNull(conflict.savedOcr)
        assertNotEquals(id(407), saved.producerId.value)
        assertEquals(conflict.row.pageId, saved.intent.input.pageId)
        assertEquals(added.row.documentId, saved.intent.documentId.value)
        val blobId = merged.catalog.sources.single { it.sourceId == conflict.row.sourceId }.sourceBlobId
        assertNotEquals(id(301), blobId)
        assertEquals("sources/$blobId/original.bin", merged.catalog.originals.single { it.sourceBlobId == blobId }.relativePath)
        assertEquals(19_001L, merged.catalog.days.single { it.sourceBlobId == blobId }.epochDay)
        assertEquals(id(301), merged.incomingBlobByCandidate.getValue(blobId))
        assertEquals("REMOVED", removed.row.state)
        assertNull(removed.row.ordinal)
        assertTrue(merged.requiredMatches.isEmpty())
        val replay = merge(target, source)
        assertEquals(added.row, replay.catalog.albums[1].row)
        assertEquals(added.pages.map { it.row }, replay.catalog.albums[1].pages.map { it.row })
        assertEquals(saved.producerId, replay.catalog.albums[1].pages.first().savedOcr?.producerId)
    }

    @Test fun aRemovedNameOrDifferentNormalizedDimensionsCannotHideAnIncomingActiveScan() {
        for (local in listOf(Scan(1, "one.jpg", 'a', removed = true), Scan(1, "one.jpg", 'a', width = 3))) {
            val target = Fixture(1, "Receipts", local)
            val source = Fixture(2, "Receipts", Scan(2, "one.jpg", 'a'))
            val merged = merge(target, source)
            assertEquals(listOf("Receipts", "Receipts (imported)"), merged.catalog.albums.map { it.row.title })
            assertEquals("ACTIVE", merged.catalog.albums.last().pages.single().row.state)
            assertTrue(merged.requiredMatches.isEmpty())
        }
    }

    @Test fun refusesAnAddendumAboveThePortableAlbumPageLimit() {
        val target = Fixture(1, "Receipts", *Array(100) { Scan(it + 1, "local $it.jpg", 'a') })
        val source = Fixture(2, "Receipts", Scan(101, "append.jpg", 'b'))
        assertThrows(IOException::class.java) { merge(target, source) }
        assertEquals(100, target.catalog.albums.single().pages.size)
    }

    @Test fun aMissingIncomingResultDoesNotEraseSavedOcrForTheSameAcceptedRecipe() {
        val target = Fixture(1, "Receipts", Scan(1, "one.jpg", 'a', ocrJob = 8))
        val source = Fixture(2, "Receipts", Scan(2, "one.jpg", 'a'))
        val page = merge(target, source).catalog.albums.single().pages.single()
        val saved = checkNotNull(page.savedOcr)
        val producer = checkNotNull(target.catalog.albums.single().pages.single().savedOcr)
        assertEquals(id(408), saved.producerId.value)
        assertEquals(1L, saved.intent.input.recipeRevision)
        assertEquals(1L, page.row.currentRecipeRevision)
        assertEquals(producer.intent, saved.intent)
        assertEquals(producer.intent.digest, saved.intent.digest)
        assertEquals("  exact job 8\n", saved.text.value)
    }

    @Test fun anIncomingRecipeWithoutOcrKeepsAnOtherwiseLostLocalResultInItsOwnAlbum() {
        val target = Fixture(1, "Receipts", Scan(1, "one.jpg", 'a', ocrJob = 8))
        val source = Fixture(2, "Receipts", Scan(2, "one.jpg", 'a', initialTurns = 3))
        val albums = merge(target, source).catalog.albums
        assertEquals(listOf("Receipts", "Receipts (imported)"), albums.map { it.row.title })
        assertEquals(id(408), albums.first().pages.single().savedOcr?.producerId?.value)
        assertNull(albums.last().pages.single().savedOcr)
    }

    @Test fun incompatibleIncomingCaptureDaysForOneSharedOriginalSeparateEveryAffectedAlbum() {
        val a = Fixture(1, "Receipts", Scan(1, "one.jpg", 'a'))
        val b = Fixture(2, "Statements", Scan(2, "two.jpg", 'a'))
        val x = Fixture(3, "Receipts", Scan(3, "one.jpg", 'a', day = 19_001L))
        val y = Fixture(4, "Statements", Scan(4, "two.jpg", 'a', day = null))
        val merged = LibraryCatalogMerge.plan(id(99), sharedOriginal(listOf(a, b)), combine(x, y))
        assertEquals(listOf("Receipts", "Statements", "Receipts (imported)", "Statements (imported)"), merged.catalog.albums.map { it.row.title })
        assertTrue(merged.requiredMatches.isEmpty())
        assertEquals(listOf(19_001L, null), merged.catalog.days.map { it.epochDay })
    }

    @Test fun incompatibleCaptureDaysWithinOneAlbumDoNotApplyEvenItsNonconflictingPages() {
        val a = Fixture(1, "Receipts", Scan(1, "one.jpg", 'a'), Scan(2, "two.jpg", 'a'))
        val x = Fixture(2, "Receipts", Scan(3, "one.jpg", 'a', day = 19_001L),
            Scan(4, "two.jpg", 'a', day = 19_002L), Scan(5, "extra.jpg", 'b'))
        val merged = LibraryCatalogMerge.plan(id(99), sharedOriginal(listOf(a)), x.catalog)
        assertEquals(listOf(listOf("one.jpg", "two.jpg"), listOf("one.jpg", "two.jpg", "extra.jpg")),
            merged.catalog.albums.map { album -> album.pages.map { it.filename } })
        assertEquals("Receipts (imported)", merged.catalog.albums.last().row.title)
        assertTrue(merged.requiredMatches.isEmpty())
    }

    @Test fun candidateUsesTheSameAlbumOrderAsTheClosedCatalogReader() {
        val target = Fixture(2, "Receipts", Scan(1, "one.jpg", 'a'))
        val source = Fixture(1, "Statements", Scan(2, "two.jpg", 'b'))
        val albums = merge(target, source).catalog.albums
        assertEquals(listOf(id(1), id(2)), albums.map { it.row.documentId })
    }

    @Test fun incomingRecipeUndoFollowsTheMatchedSlotAndRemappedHistory() = runTest {
        val target = Fixture(1, "Receipts", Scan(1, "one.jpg", 'a'))
        val source = Fixture(1, "Receipts", Scan(2, "one.jpg", 'a', secondRevision = true))
        val candidate = LibraryCatalogMerge.plan(id(99), target.catalog, withRecipeUndo(source, documentRevision = 7, appliedRevision = 5))
        val undo = candidate.catalog.albums.single().undo.single()
        assertEquals(id(1), undo.row.documentId)
        assertEquals(listOf(RecipePointer(id(101), id(201), 2)), LibraryUndoCodec.decodeRecipe(undo.row.beforeState).pages)
        assertEquals(listOf(RecipePointer(id(101), id(201), 3)), LibraryUndoCodec.decodeRecipe(undo.row.afterState).pages)
        assertEquals(setOf(id(201)), undo.pinnedSources)
        assertEquals(5L, undo.row.appliedDocumentRevision)
        assertEquals(86_400_042L, undo.row.expiresAt)

        // Use the ordinary repository to consume the candidate on the host. This exercises
        // remapped pointer semantics, not Android SQLite writes or backup reopen.
        val album = candidate.catalog.albums.single()
        val page = album.pages.single()
        val metadata = MemoryMetadata().apply {
            insertDocument(album.row)
            insertPage(page.row)
            for (revision in page.revisions) insertRecipe(revision)
            val blob = candidate.catalog.originals.single().copy(sourceCount = 1)
            blobs[blob.sourceBlobId] = blob
            val stored = candidate.catalog.sources.single().copy(refCount = 5)
            sources[stored.sourceId] = stored
            insertReference(SourceReferenceRow("PAGE", id(101), id(201), 0))
            for (revision in 1..3) insertReference(SourceReferenceRow("RECIPE_HISTORY", "recipe-v1/${id(101)}/$revision", id(201), 0))
            insertReference(SourceReferenceRow("UNDO", undo.id, id(201), 0))
            insertUndo(undo.row)
        }
        val normalUndo = RoomDocumentRepository(metadata, RecipeAdmission { _, _ -> null }, { 43 }, Dispatchers.Unconfined)
        assertEquals(1, RecipeCodec.decode(metadata.recipes.getValue(id(101) to 3L).canonicalRecipe).geometry.outputQuarterTurns)
        // Revision 6 was once current on the incoming album but is already stale there.
        val stale = normalUndo.undoDocumentEdit(UndoId(undo.id), 6)
        assertEquals(DocumentMutationResult.Conflict(8), stale)
        assertEquals("AVAILABLE", metadata.undos.getValue(undo.id).state)
        val reverted = normalUndo.undoDocumentEdit(UndoId(undo.id), 8) as DocumentMutationResult.Applied
        assertEquals(9L, reverted.documentRevision)
        assertEquals(4L, metadata.pages.getValue(id(101)).currentRecipeRevision)
        assertEquals(0, RecipeCodec.decode(metadata.recipes.getValue(id(101) to 4L).canonicalRecipe).geometry.outputQuarterTurns)
        assertEquals("APPLIED", metadata.undos.getValue(undo.id).state)
    }

    @Test fun conflictingUpdatesKeepBothIndependentUndoHistoriesAndResolveTheirCollidingOwners() {
        val local = withRecipeUndo(Fixture(1, "Receipts", Scan(1, "one.jpg", 'a', secondRevision = true)))
        val incoming = withRecipeUndo(Fixture(1, "Receipts", Scan(1, "one.jpg", 'a', secondRevision = true, initialTurns = 1)))
        val result = LibraryCatalogMerge.plan(id(99), local, incoming)
        val (target, added) = result.catalog.albums
        assertEquals("Receipts (imported)", added.row.title)
        val preserved = target.undo.single()
        val remapped = added.undo.single()
        val copied = added.pages.single().row
        assertNotEquals(preserved.row.undoId, remapped.row.undoId)
        assertEquals(added.row.documentId, remapped.row.documentId)
        assertEquals(setOf(copied.sourceId), remapped.pinnedSources)
        assertEquals(listOf(RecipePointer(copied.pageId, copied.sourceId, 2)), LibraryUndoCodec.decodeRecipe(remapped.row.afterState).pages)
    }

    private fun withRecipeUndo(fixture: Fixture, documentRevision: Long = 4, appliedRevision: Long = 4): RoomLibrarySavedCatalog.Snapshot {
        val snapshot = fixture.catalog
        val album = snapshot.albums.single()
        val page = album.pages.single()
        val row = page.row
        fun encode(rev: Long) = LibraryUndoCodec.encodeRecipe(listOf(RecipePointer(row.pageId, row.sourceId, rev)))
        val document = album.row.copy(documentRevision = documentRevision)
        val undo = PortableUndoEntry.admit(UndoEntryRow(id(999), document.documentId, encode(1), encode(2), "RECIPE_EDIT", appliedRevision, 86_400_042, "AVAILABLE"),
            document, { row.takeIf { p -> p.pageId == it } }, { key, rev -> page.revisions.singleOrNull { it.pageId == key && it.recipeRevision == rev } })
        return RoomLibrarySavedCatalog.Snapshot(listOf(RoomLibrarySavedCatalog.Album(document, album.pages, listOf(undo))),
            snapshot.sources, snapshot.originals, snapshot.days)
    }

    private fun merge(target: Fixture, source: Fixture) = LibraryCatalogMerge.plan(id(99), target.catalog, source.catalog)

    private fun combine(vararg fixtures: Fixture) = RoomLibrarySavedCatalog.Snapshot(fixtures.flatMap { it.catalog.albums },
        fixtures.flatMap { it.catalog.sources }, fixtures.flatMap { it.catalog.originals }, fixtures.flatMap { it.catalog.days })

    private fun sharedOriginal(fixtures: List<Fixture>): RoomLibrarySavedCatalog.Snapshot {
        val combined = combine(*fixtures.toTypedArray())
        val source = combined.sources.first()
        val albums = combined.albums.map { album ->
            RoomLibrarySavedCatalog.Album(album.row, album.pages.map { page ->
                RoomLibrarySavedCatalog.Page(page.row.copy(sourceId = source.sourceId), page.filename,
                    page.revisions.map { it.copy(sourceId = source.sourceId) }, null)
            })
        }
        return RoomLibrarySavedCatalog.Snapshot(albums, listOf(source), listOf(combined.originals.first()), emptyList())
    }

    private class Scan(val key: Int, val name: String, val hash: Char, val ocrJob: Int? = null,
        val secondRevision: Boolean = false, val removed: Boolean = false, val width: Int = 2, val initialTurns: Int = 0,
        val day: Long? = 19_001L)

    private class Fixture(document: Int, title: String, vararg scans: Scan) {
        val catalog: RoomLibrarySavedCatalog.Snapshot

        init {
            val originals = scans.map {
                val blob = id(300 + it.key)
                SourceBlobRow(blob, it.hash.toString().repeat(64), 5, "image/jpeg", it.width, 2, "sources/$blob/original.bin", "READY", 0, id(800 + it.key), 10)
            }
            val sources = scans.zip(originals).map { (scan, blob) ->
                SourceRow(id(200 + scan.key), blob.sourceBlobId, blob.byteSha256, scan.width, 2, 1, "fixture-v1", null, 0, "READY", 11)
            }
            var ordinal = 0
            val pages = scans.zip(sources).map { (scan, source) ->
                val page = id(100 + scan.key)
                val current = if (scan.secondRevision) 2L else 1L
                val row = PageRow(page, id(document), source.sourceId, current, if (scan.removed) "REMOVED" else "ACTIVE",
                    if (scan.removed) null else ordinal++, if (scan.removed) 20L else null, 12)
                val binding = SourceBinding(source.pixelSha256, PixelSize(source.width, source.height), source.conversionFingerprint)
                val revisions = (1..current).map { revision ->
                    val recipe = Recipe(geometry = GeometryRecipe.None(binding, outputQuarterTurns = scan.initialTurns + (revision - 1).toInt()))
                    RecipeRevisionRow(page, revision, source.sourceId, 1, RecipeCodec.encode(recipe), RecipeCodec.hash(recipe),
                        (revision - 1).takeIf { it > 0 }, 12 + revision)
                }
                val saved = scan.ocrJob?.let {
                    val input = OcrJobInput(DocumentId(id(document)), OcrInput(page, current, source.pixelSha256), SourceId(source.sourceId),
                        revisions.last().recipeSha256, 1, CONFIG)
                    LibrarySavedOcr(JobId(id(400 + it)), input, OcrText("  exact job $it\n"), 1, 21, 22)
                }
                RoomLibrarySavedCatalog.Page(row, scan.name, revisions, saved)
            }
            val album = RoomLibrarySavedCatalog.Album(DocumentRow(id(document), title, 4, "ACTIVE", 10, 30, null), pages)
            val dates = originals.zip(scans).map { (blob, scan) -> SourceCaptureDayRow(blob.sourceBlobId, blob.byteSha256, scan.day) }
            catalog = RoomLibrarySavedCatalog.Snapshot(listOf(album), sources, originals, dates)
        }
    }

    private companion object {
        fun id(i: Int) = UUID(0, i.toLong()).toString()
        val CONFIG = OcrConfiguration("fixture", 1, OcrLayout.SPARSE_TEXT, OcrLanguagePacks(listOf(OcrLanguagePack("eng", "b".repeat(64)))))
    }
}
