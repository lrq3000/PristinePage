package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AtelierDraftRecipeCheckpointTest {
    @Test fun exactCurrentRenderAllowsOnlyTheChosenHiddenPageToChange() = runBlocking {
        val f = Fixture()
        val saved = f.checkpoint() as SaveRecipeResult.Saved
        assertEquals(2L, saved.recipeRevision)
        assertEquals(f.base.turned(1), f.base.current(f.page))
        assertEquals(f.base.initial, f.base.current(f.base.pageIds[1]))
        assertEquals(1, f.released)
    }

    @Test fun unusableRenderCannotChangeAnyRecipeOrOwner() = runBlocking {
        for (mode in listOf("wrong request", "wrong size", "degraded", "invalid map", "closed")) {
            val f = Fixture()
            f.mode = mode
            val expected = when (mode) {
                "degraded" -> OperationErrorCode.PROCESSING_FAILED
                "invalid map" -> OperationErrorCode.INVALID_MAP
                else -> OperationErrorCode.INTEGRITY_FAILED
            }
            val before = f.state()
            val rejected = f.checkpoint() as SaveRecipeResult.Rejected
            assertEquals(mode, expected, rejected.error.code)
            assertEquals(mode, before, f.state())
            assertEquals(mode, 1, f.released)
        }
    }

    @Test fun changingGenerationDuringRenderCannotCommitAStaleCheckpoint() = runBlocking {
        val f = Fixture()
        f.mode = "thaw"
        val before = f.state()
        val rejected = f.checkpoint() as SaveRecipeResult.Rejected
        assertEquals(OperationErrorCode.REVISION_CONFLICT, rejected.error.code)
        assertEquals(before, f.state())
        assertEquals(1, f.released)
    }

    @Test fun cancellationAfterHistoryInsertRollsBackThePointerAndPins() = runBlocking {
        val f = Fixture()
        val before = f.state()
        val job = launch {
            val owner = checkNotNull(coroutineContext[Job])
            f.base.metadata.afterRecipeInsert = { owner.cancel() }
            f.checkpoint()
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(before, f.state())
        assertEquals(1, f.released)
    }

    @Test fun maskedRecipeRefusesBeforeTypedMaskRowsOrRenderer() = runBlocking {
        val f = Fixture()
        f.base.protectionMask(f.page, 1)
        val before = f.state()
        f.base.metadata.beforeRecipeMasksRead = { fail("Read typed mask rows for an unsupported draft") }
        val result = f.checkpoint() as SaveRecipeResult.Rejected
        assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, result.error.code)
        assertEquals(before, f.state())
        assertEquals(0, f.released)
    }

    private class Fixture {
        val base = RepositoryFixture()
        val page = base.pageIds.first()
        private val id = UUID.randomUUID().toString()
        private var generation = 2L
        var mode = "success"
        var released = 0
        private val access = object : MetadataAccess by base.metadata {
            override fun atelierEditingDocument(draftId: String, generation: Long): String? {
                check(base.metadata.inTransaction)
                return base.document.value.takeIf { draftId == id && generation == this@Fixture.generation }
            }
        }
        private val repository = RoomDocumentRepository(access, RecipeAdmission { _, _ -> null }, dispatcher = Dispatchers.Unconfined)
        init { base.metadata.hiddenDraftDocuments.add(base.document.value) }

        private val renderer = object : RenderCoordinator {
          override suspend fun render(request: RenderRequest): RenderResult {
            assertFalse(base.metadata.inTransaction)
            assertEquals(RenderRequest(base.source.value, base.binding.sourcePixelHash, page.value, 1L, base.turned(1),
                OutputSpec(PixelSize(2, 3), RenderPurpose.PREVIEW), 2L, null, FallbackPolicy.FAIL), request)
            if (mode == "thaw") generation++
            val status = when (mode) {
                "degraded" -> RenderStatus.DEGRADED
                "invalid map" -> RenderStatus.INVALID_MAP
                else -> RenderStatus.OK
            }
            val resultRequest = if (mode == "wrong request") request.copy(generation = 1) else request
            val image = if (mode == "invalid map") null else if (mode == "wrong size") RgbImage(3, 2, 9, ByteArray(18)) else RgbImage(2, 3, 6, ByteArray(18))
            if (mode == "closed") image?.close()
            return RenderResult(resultRequest, status, image, emptyList(), emptyList(), AutoCloseable { released++ })
          }
        }

        fun state() = listOf(base.metadata.documents.toMap(), base.metadata.pages.toMap(), base.metadata.recipes.toMap(),
            base.metadata.references.toMap(), base.metadata.undos.toMap())

        suspend fun checkpoint(): SaveRecipeResult = repository.checkpointDraftRecipe(id, 2L, page, 1L, base.turned(1), renderer)
    }
}
