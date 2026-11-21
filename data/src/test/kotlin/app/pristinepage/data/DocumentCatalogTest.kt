package app.pristinepage.data

import app.pristinepage.data.db.DocumentRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Reuses real command-created metadata/recipes/source ownership, replacing only Room and its scalar projections. Actual DAO/SQLite guards and reopen are separate consuming tests; no filesystem or source decoder participates in metadata discovery. */
class DocumentCatalogTest {
    @Test fun albumSearchUsesAllPagesAndRequiresEveryTermAcrossTitleAndDates() = runBlocking {
        val f = Fixture()
        f.memory.documents.clear()
        f.memory.pages.clear()
        val ids = (0..62).map { DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-%012d".format(Locale.ROOT, it)) }
        val creation = Instant.parse("2026-11-18T23:30:00Z").toEpochMilli()
        val modified = Instant.parse("2026-11-20T19:42:00Z").toEpochMilli()
        ids.forEachIndexed { index, id ->
            val title = if (index in 60..61) "École receipt" else if (index == 62) "École invoice" else "Receipt"
            f.memory.documents[id.value] = DocumentRow(id.value, title, 1, "ACTIVE", creation, modified + index, null)
        }
        val catalog = f.catalog() as SearchableAlbumCatalog
        val query = AlbumSearchQuery("ecole receipt 2026-11-19 nov 20", ZoneId.of("Europe/Paris"))
        val first = catalog.searchAlbums(query, AlbumOrder.MODIFIED, Locale.ENGLISH, null, 1)
        assertEquals(listOf(ids[61]), first.albums.map { it.documentId })
        assertNotNull(first.next)
        val next = catalog.searchAlbums(query, AlbumOrder.MODIFIED, Locale.ENGLISH, first.next, 1)
        assertEquals(listOf(ids[60]), next.albums.map { it.documentId })
        assertNull(next.next)
    }

    @Test fun displayedEnglishDateMatchesAndMonthDayDoesNotMatchYearDigits() = runBlocking {
        val f = Fixture()
        val second = Instant.parse("2026-11-02T19:30:00Z").toEpochMilli()
        val nineteenth = Instant.parse("2026-11-19T19:30:00Z").toEpochMilli()
        for ((id, timestamp) in listOf(f.first to second, f.second to nineteenth)) {
            f.memory.documents[id.value] = f.memory.documents.getValue(id.value).copy(createdAt = timestamp, updatedAt = timestamp)
        }
        val catalog = f.catalog() as SearchableAlbumCatalog
        suspend fun matching(text: String) = catalog.searchAlbums(AlbumSearchQuery(text, ZoneId.of("UTC")), AlbumOrder.MODIFIED, Locale.ENGLISH, null, 10)
            .albums.map { it.documentId }
        assertEquals(listOf(f.second), matching("Nov 19, 2026"))
        assertEquals(listOf(f.first), matching("Nov 2"))
        assertEquals(listOf(f.first), matching("Nov 2, 2026 7:30 PM"))
    }

    @Test fun matchingHiddenAndTombstonedAlbumsAreNotSearchResults() = runBlocking {
        val f = Fixture()
        f.memory.documents.replaceAll { _, row -> row.copy(title = "Receipt") }
        f.memory.hiddenDraftDocuments.add(f.first.value)
        f.memory.documents[f.second.value] = f.memory.documents.getValue(f.second.value).copy(lifecycle = "TOMBSTONED", deletedAt = 1)
        val catalog = f.catalog() as SearchableAlbumCatalog
        val result = catalog.searchAlbums(AlbumSearchQuery("receipt", ZoneId.of("UTC")), AlbumOrder.MODIFIED, Locale.ENGLISH, null, 1)
        assertEquals(listOf(f.third), result.albums.map { it.documentId })
        assertNull(result.next)
    }

    @Test fun completeDateTimeMustMatchOneTimestamp() = runBlocking {
        val f = Fixture()
        f.memory.documents[f.first.value] = f.memory.documents.getValue(f.first.value).copy(
            createdAt = Instant.parse("2026-11-02T09:00:00Z").toEpochMilli(),
            updatedAt = Instant.parse("2026-11-03T19:30:00Z").toEpochMilli(),
        )
        val catalog = f.catalog() as SearchableAlbumCatalog
        suspend fun matching(text: String) = catalog.searchAlbums(AlbumSearchQuery(text, ZoneId.of("UTC")), AlbumOrder.MODIFIED, Locale.ENGLISH, null, 10)
            .albums.map { it.documentId }
        assertEquals(listOf(f.first), matching("Nov 3, 2026 7:30 PM"))
        assertEquals(listOf(f.first), matching("2026-11-03 19:30"))
        assertTrue(matching("Nov 2, 2026 7:30 PM").isEmpty())
        assertTrue(matching("2026-11-02 19:30").isEmpty())
    }

    @Test fun albumDatesAreStoredValuesNotPresentationDefaults() = runBlocking {
        val f = Fixture()
        f.memory.documents[f.first.value] = f.memory.documents.getValue(f.first.value).copy(createdAt = 10, updatedAt = 26)
        val summary = f.catalog().load(f.first).document
        assertEquals(10L, summary.createdAt)
        assertEquals(26L, summary.updatedAt)
    }

    @Test fun albumOrderIsGlobalAcrossPagesAndKeepsAscendingIdTies() = runBlocking {
        val f = Fixture()
        f.memory.documents.clear()
        f.memory.pages.clear()
        val ids = (0..61).map { DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-%012d".format(Locale.ROOT, it)) }
        ids.forEachIndexed { index, id ->
            val title = when (index) { 0 -> "École"; 1 -> "école"; 2 -> "Zoo"; else -> "Arbre" }
            f.memory.documents[id.value] = DocumentRow(id.value, title, 1, "ACTIVE", (61 - index).toLong(), index.toLong(), null)
        }
        val catalog = f.catalog() as AlbumCatalog
        val expected = mapOf(
            AlbumOrder.CREATED to ids,
            AlbumOrder.MODIFIED to ids.reversed(),
            AlbumOrder.TITLE_AZ to (ids.drop(3) + ids.take(3)),
            AlbumOrder.TITLE_ZA to (listOf(ids[2], ids[0], ids[1]) + ids.drop(3)),
        )
        for ((order, orderedIds) in expected) {
            for (pageSize in listOf(7, 50)) {
                val result = mutableListOf<DocumentId>()
                var cursor: AlbumPosition? = null
                do {
                    val page = catalog.listAlbums(order, Locale.FRENCH, cursor, pageSize)
                    result += page.albums.map { it.documentId }
                    cursor = page.next
                    assertTrue("Catalog cursor did not advance", result.size <= ids.size && (cursor == null || page.albums.isNotEmpty()))
                } while (cursor != null)
                assertEquals(order.name, orderedIds, result)
            }
        }
    }

    @Test fun albumOrderRetainsReadBoundsAndRejectsWrongLimits() = runBlocking<Unit> {
        val f = Fixture()
        val catalog = f.catalog() as AlbumCatalog
        rejected(OperationErrorCode.INVALID_INPUT) { catalog.listAlbums(AlbumOrder.TITLE_AZ, Locale.FRENCH, null, 51) }
        assertEquals(0, f.idReads)
        f.documentBound = false
        rejected(OperationErrorCode.INTEGRITY_FAILED) { catalog.listAlbums(AlbumOrder.CREATED, Locale.FRENCH, null, 2) }
        assertEquals(0, f.documentReads)
    }

    @Test fun malformedPageCanFailOverviewWithoutHidingTheAlbumCard() = runBlocking<Unit> {
        val f = Fixture()
        f.pagesBound = false
        val catalog = f.catalog()
        assertEquals(listOf(f.first, f.second, f.third), (catalog as AlbumCatalog)
            .listAlbums(AlbumOrder.CREATED, Locale.FRENCH, null, 10).albums.map { it.documentId })
        rejected(OperationErrorCode.INTEGRITY_FAILED) { catalog.load(f.first) }
        assertEquals(0, f.pageReads)
    }

    @Test fun paginatedSummariesAreIndependentOwnedSnapshots() = runBlocking {
        val f = Fixture(); val catalog = f.catalog()
        val first = catalog.list(null, 2)
        assertEquals(listOf(f.first, f.second), first.map { it.documentId })
        assertEquals(listOf(2, 0), first.map { it.pageCount })
        assertEquals(listOf(1L, 3L), first.map { it.documentRevision })
        assertEquals(listOf(false, true), first.map { it.hasPendingImport })
        assertEquals(listOf(f.third), catalog.list(f.second, 2).map { it.documentId })
        f.memory.documents[f.first.value] = f.memory.documents.getValue(f.first.value).copy(title = "Later title", documentRevision = 2)
        assertEquals("Synthetic", first[0].title); assertEquals(1L, first[0].documentRevision)
        assertEquals("Later title", catalog.load(f.first).document.title)
        try { (first as MutableList<DocumentSummary>).clear(); fail("Library snapshot was mutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(3, f.memory.documents.size); assertEquals(2, f.memory.pages.size)
    }

    @Test fun overviewKeepsExactPageOrderIdentityAndCurrentRevisionWithoutBorrowingRows() = runBlocking {
        val f = Fixture(); val catalog = f.catalog(); val overview = catalog.load(f.first)
        assertEquals(f.initial.pageIds, overview.pages.map { it.pageId })
        assertEquals(listOf(0, 1), overview.pages.map { it.ordinal })
        assertEquals(listOf(f.initial.source, f.initial.source), overview.pages.map { it.sourceId })
        assertEquals(listOf(1L, 1L), overview.pages.map { it.recipeRevision })
        assertEquals(overview.pages.size, overview.document.pageCount)
        val page = f.memory.pages.getValue(f.initial.pageIds[0].value)
        f.memory.pages[page.pageId] = page.copy(currentRecipeRevision = 7)
        assertEquals(1L, overview.pages[0].recipeRevision)
        try { (overview.pages as MutableList<DocumentPage>).clear(); fail("Overview borrowed a mutable page list") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun invalidPageLimitsRefuseBeforeReadingAnyMetadata() = runBlocking {
        val f = Fixture(); val catalog = f.catalog()
        for (limit in listOf(0, -1, 51, Int.MAX_VALUE)) rejected(OperationErrorCode.INVALID_INPUT) { catalog.list(null, limit) }
        assertEquals(0, f.idReads); assertEquals(0, f.documentReads); assertEquals(0, f.pageReads)
    }

    @Test fun malformedOrNonadvancingProjectedIdentityCannotLoadAnUnsafeTitle() = runBlocking {
        for (ids in listOf(listOf(""), listOf("not-an-id"), listOf("content://fixture.invalid/private"), listOf(SECOND, FIRST))) {
            val f = Fixture(); f.projectedIds = ids; val catalog = f.catalog()
            rejected(OperationErrorCode.INTEGRITY_FAILED) { catalog.list(null, 2) }
        }
        val f = Fixture(); f.projectedIds = listOf(FIRST, SECOND, THIRD)
        rejected(OperationErrorCode.INTEGRITY_FAILED) { f.catalog().list(null, 2) }
        assertEquals(0, f.documentReads)
    }

    @Test fun scalarDocumentRefusalPrecedesAnyTitleRowMaterialization() = runBlocking {
        val f = Fixture(); f.documentBound = false
        rejected(OperationErrorCode.INTEGRITY_FAILED) { f.catalog().load(f.first) }
        assertEquals(0, f.documentReads); assertEquals(0, f.pageReads)
    }

    @Test fun missingAndInactiveDocumentsStayDistinctFromMalformedStorage() = runBlocking {
        val f = Fixture(); val catalog = f.catalog()
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { catalog.load(DocumentId("44444444-4444-4444-8444-444444444444")) }
        assertEquals(0, f.documentReads)
        f.memory.documents[f.first.value] = f.memory.documents.getValue(f.first.value).copy(lifecycle = "TOMBSTONED", deletedAt = 1000)
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { catalog.load(f.first) }
        assertEquals(0, f.pageReads)
        assertEquals(listOf(f.second, f.third), catalog.list(null, 50).map { it.documentId })
    }

    @Test fun oversizedCountAndUnsafePageProjectionRefuseBeforePageRowsAreMaterialized() = runBlocking {
        for (count in listOf(-1L, 101L, Long.MAX_VALUE)) {
            val f = Fixture(); f.countOverride = count
            rejected(OperationErrorCode.INTEGRITY_FAILED) { f.catalog().load(f.first) }
            assertEquals(0, f.pageReads)
        }
        val f = Fixture(); f.pagesBound = false
        rejected(OperationErrorCode.INTEGRITY_FAILED) { f.catalog().load(f.first) }
        assertEquals(0, f.pageReads)
    }

    @Test fun typedPageValuesAndCompleteOrdinalMembershipAreValidatedAfterTheRawGuard() = runBlocking {
        val fixture = Fixture(); val good = fixture.memory.activePages(fixture.first.value)
        for (bad in listOf(good.take(1), listOf(good[0], good[0]), listOf(good[0].copy(sourceId = "invalid"), good[1]), listOf(good[0].copy(currentRecipeRevision = 0), good[1]), listOf(good[0].copy(documentId = SECOND), good[1]))) {
            val f = Fixture(); f.pageOverride = bad
            rejected(OperationErrorCode.INTEGRITY_FAILED) { f.catalog().load(f.first) }
        }
    }

    @Test fun knownCanceledReadNeverEntersMetadataOrPublishesASnapshot() = runBlocking {
        val f = Fixture(); val catalog = f.catalog(); val stopped = Job().apply { cancel() }
        try { withContext(stopped) { catalog.list(null, 2) }; fail("Canceled read returned a library snapshot") }
        catch (_: CancellationException) { }
        assertEquals(0, f.idReads); assertEquals(0, f.documentReads); assertEquals(0, f.pageReads)
    }

    @Test fun storageReadFailureKeepsASafeTypedErrorInsteadOfAnEmptyLibrary() = runBlocking {
        val f = Fixture(); f.failRead = true
        val error = rejected(OperationErrorCode.STORAGE_FAILED) { f.catalog().list(null, 2) }
        assertTrue(error.retryable); assertFalse(error.safeMessageKey.contains("fixture-private"))
    }

    @Test fun acceptedCreateAndRenameTitlesRemainReadableWithoutRewritingTheirContents() = runBlocking {
        val f = Fixture(); val catalog = f.catalog()
        for (title in listOf("\u0000Synthetic", "Syn\u0000thetic", "é".repeat(160), "😀".repeat(80))) {
            val id = f.initial.repository.createDocument(title)
            assertEquals(title, catalog.load(id).document.title)
            val renamed = "\u0000Renamed"
            assertTrue(f.initial.repository.renameDocument(id, 1, renamed) is DocumentMutationResult.Applied)
            assertEquals(renamed, catalog.load(id).document.title)
            assertEquals(renamed, catalog.list(null, 50).single { it.documentId == id }.title)
        }
    }

    @Test fun pendingBatchCanBeRediscoveredFromDurableDocumentMetadataWithoutUiSavedState() = runBlocking {
        val f = Fixture(); val catalog = f.catalog()
        assertEquals(ImportSessionId(BATCH), activeImport(catalog, f.second))
        assertEquals(0, f.pageReads); assertEquals(3, f.memory.documents.size); assertEquals(2, f.memory.pages.size)
    }

    @Test fun noPendingBatchReturnsNullRatherThanAllocatingANewReservation() = runBlocking {
        val f = Fixture(); val before = f.memory.pendingImports.toSet()
        assertNull(activeImport(f.catalog(), f.first))
        assertEquals(before, f.memory.pendingImports); assertEquals(0, f.pageReads)
    }

    @Test fun malformedAmbiguousOrFlagMismatchedPendingIdentityCannotBeUsedForCancellation() = runBlocking<Unit> {
        for (ids in listOf(listOf("invalid"), listOf(BATCH, BATCH), emptyList())) {
            val f = Fixture(); f.pendingIdsOverride = ids
            rejected(OperationErrorCode.INTEGRITY_FAILED) { activeImport(f.catalog(), f.second) }
        }
        val f = Fixture(); f.pendingIdsOverride = listOf(BATCH)
        rejected(OperationErrorCode.INTEGRITY_FAILED) { activeImport(f.catalog(), f.first) }
    }

    @Test fun pendingDiscoveryCannotExposeAMissingOrInactiveDocument() = runBlocking<Unit> {
        val f = Fixture(); val catalog = f.catalog()
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { activeImport(catalog, DocumentId("44444444-4444-4444-8444-444444444444")) }
        f.memory.documents[f.second.value] = f.memory.documents.getValue(f.second.value).copy(lifecycle = "TOMBSTONED", deletedAt = 1)
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { activeImport(catalog, f.second) }
    }

    @Test fun hiddenPromotionDoesNotConsumeThePageLimitOrBecomeReadableById() = runBlocking {
        val f = Fixture()
        f.memory.hiddenDraftDocuments.add(f.first.value)
        val catalog = f.catalog()
        assertEquals(listOf(f.second, f.third), catalog.list(null, 2).map { it.documentId })
        assertEquals(listOf(f.second, f.third), (catalog as AlbumCatalog).listAlbums(AlbumOrder.CREATED, Locale.ENGLISH, null, 2).albums.map { it.documentId })
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { catalog.load(f.first) }
        Unit
    }

    private class Fixture {
        val initial = RepositoryFixture(2)
        val memory = initial.metadata
        val first = DocumentId(FIRST); val second = DocumentId(SECOND); val third = DocumentId(THIRD)
        var projectedIds: List<String>? = null
        var pendingIdsOverride: List<String>? = null
        var documentBound = true
        var pagesBound = true
        var countOverride: Long? = null
        var pageOverride: List<PageRow>? = null
        var failRead = false
        var idReads = 0
        var documentReads = 0
        var pageReads = 0
        private val admittedDocuments = mutableSetOf<String>()
        private val admittedPages = mutableSetOf<String>()

        init {
            val doc = memory.documents.remove(initial.document.value)!!
            memory.documents[FIRST] = doc.copy(documentId = FIRST)
            memory.pages.replaceAll { _, page -> page.copy(documentId = FIRST) }
            memory.documents[SECOND] = DocumentRow(SECOND, "Second", 3, "ACTIVE", 0, 0, null)
            memory.documents[THIRD] = DocumentRow(THIRD, "Third", 9, "ACTIVE", 0, 0, null)
            memory.pendingImports += SECOND
        }

        private val guards = object : DocumentReadQueries {
            override fun activeDocumentIdsAfter(after: String?, limit: Int): List<String> {
                check(memory.inTransaction); check(limit in 1..50); idReads++
                if (failRead) throw IOException("fixture-private source label must not reach UI")
                return projectedIds ?: memory.documents.values.filter { it.lifecycle == "ACTIVE" && (after == null || it.documentId > after) }.map { it.documentId }.sorted().take(limit)
            }
            override fun documentReadIsBounded(documentId: String): Boolean? {
                check(memory.inTransaction)
                if (documentId !in memory.documents) return null
                if (documentBound) admittedDocuments += documentId
                return documentBound
            }
            override fun activePageCount(documentId: String): Long { check(memory.inTransaction); return countOverride ?: memory.activePages(documentId).size.toLong() }
            override fun activePagesReadIsBounded(documentId: String): Boolean { check(memory.inTransaction); if (pagesBound) admittedPages += documentId; return pagesBound }
            override fun activeImportIds(documentId: String): List<String> {
                check(memory.inTransaction)
                return pendingIdsOverride ?: if (documentId in memory.pendingImports) listOf(BATCH) else emptyList()
            }
        }
        private val queries = object : CatalogQueries by memory {
            override fun document(id: String): DocumentRow? { check(memory.inTransaction && id in admittedDocuments); documentReads++; return memory.document(id) }
            override fun activePages(documentId: String): List<PageRow> { check(memory.inTransaction && documentId in admittedPages); pageReads++; return pageOverride ?: memory.activePages(documentId) }
        }
        private val metadata = object : MetadataAccess {
            override val queries: CatalogQueries get() = this@Fixture.queries
            override fun <T> transaction(action: () -> T): T = memory.transaction(action)
            override fun isHiddenAtelierDraft(documentId: String): Boolean = memory.isHiddenAtelierDraft(documentId)
        }
        fun catalog(): DocumentCatalog {
            val type = try { Class.forName("app.pristinepage.data.repository.RoomDocumentCatalog") }
            catch (_: ClassNotFoundException) { throw AssertionError("Bounded document catalog reader is absent") }
            return try { type.getConstructor(MetadataAccess::class.java, DocumentReadQueries::class.java, kotlinx.coroutines.CoroutineDispatcher::class.java).newInstance(metadata, guards, Dispatchers.Unconfined) as DocumentCatalog }
            catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }

    private companion object {
        const val FIRST = "11111111-1111-4111-8111-111111111111"
        const val SECOND = "22222222-2222-4222-8222-222222222222"
        const val THIRD = "33333333-3333-4333-8333-333333333333"
        const val BATCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        suspend fun activeImport(catalog: DocumentCatalog, id: DocumentId): ImportSessionId? {
            val method = catalog.javaClass.methods.singleOrNull { it.name.startsWith("activeImport-") && it.parameterCount == 2 }
                ?: throw AssertionError("Durable active-import discovery is absent")
            val result: Any? = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
                try { method.invoke(catalog, id.value, continuation) }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
            return when (result) { null -> null; is ImportSessionId -> result; is String -> ImportSessionId(result); else -> throw AssertionError("Invalid discovery result") }
        }
        suspend fun rejected(code: OperationErrorCode, action: suspend () -> Any?): OperationError {
            try { action(); fail("Read unexpectedly succeeded") }
            catch (failure: RepositoryFailure) { assertEquals(code, failure.error.code); return failure.error }
            throw AssertionError("Expected structured read refusal")
        }
    }
}
