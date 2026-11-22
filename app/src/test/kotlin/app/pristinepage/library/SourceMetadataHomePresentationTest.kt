package app.pristinepage.library

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.DocumentOverview
import app.pristinepage.data.model.DocumentPage
import app.pristinepage.data.model.DocumentSummary
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.DocumentCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SourceMetadataHomePresentationTest {
    private val corrupt = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
    private val healthy = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002")
    private val page = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
    private val source = SourceId("aaaaaaaa-aaaa-4aaa-8aaa-000000000004")

    @Test fun aMalformedSiblingDoesNotHideHealthyMetadataHitsInTheSameBatch() = runTest {
        val catalog = catalogWithFailure(OperationErrorCode.INTEGRITY_FAILED)
        var live = true
        val result = presentPageHits(listOf(corrupt, healthy), { it }, { page }, catalog, { live }, skipMalformedAlbums = true)
        val hit = result.single()
        assertEquals(healthy, hit.document)
        assertEquals(page, hit.page)
        assertTrue(hit.stillMatches())
        live = false
        assertFalse(hit.stillMatches())
        val ocrFailure = runCatching { presentPageHits(listOf(corrupt, healthy), { it }, { page }, catalog, { live }) }.exceptionOrNull()
        assertEquals(OperationErrorCode.INTEGRITY_FAILED, (ocrFailure as? RepositoryFailure)?.error?.code)
    }

    @Test fun metadataPresentationDoesNotDiscardRetryableStorageFailure() = runTest {
        val failure = runCatching {
            presentPageHits(listOf(corrupt, healthy), { it }, { page }, catalogWithFailure(OperationErrorCode.STORAGE_FAILED),
                { true }, skipMalformedAlbums = true)
        }.exceptionOrNull()
        assertEquals(OperationErrorCode.STORAGE_FAILED, (failure as? RepositoryFailure)?.error?.code)
    }

    private fun catalogWithFailure(code: OperationErrorCode) = object : DocumentCatalog {
        override suspend fun load(documentId: DocumentId): DocumentOverview {
            if (documentId == corrupt) throw RepositoryFailure(OperationError(code, code == OperationErrorCode.STORAGE_FAILED, "fixture_failure", null))
            assertEquals(healthy, documentId)
            return DocumentOverview(DocumentSummary(healthy, "Synthetic", 1, 1, false), listOf(DocumentPage(page, source, 1, 0)))
        }
        override suspend fun list(after: DocumentId?, limit: Int): List<DocumentSummary> = error("Unexpected catalog scan")
        override suspend fun activeImport(documentId: DocumentId) = error("Unexpected import lookup")
    }
}
