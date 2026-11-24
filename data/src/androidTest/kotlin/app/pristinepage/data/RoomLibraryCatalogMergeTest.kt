package app.pristinepage.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.library.LibraryCatalogMerge
import app.pristinepage.data.library.LibraryPortableCatalogProjection
import app.pristinepage.data.library.LibrarySavedOcr
import app.pristinepage.data.library.RoomLibraryCatalogReader
import app.pristinepage.data.library.RoomLibraryCatalogStage
import app.pristinepage.data.library.RoomLibraryLocalExport
import app.pristinepage.data.library.RoomLibraryLocalMergePreflight
import app.pristinepage.data.library.RoomLibrarySavedCatalog
import app.pristinepage.data.library.RoomLibrarySavedOcr
import app.pristinepage.data.library.verifyStagedProjection
import app.pristinepage.data.model.DocumentMutationResult
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.JobId
import app.pristinepage.data.repository.OcrJobInput
import app.pristinepage.data.repository.RecipeAdmission
import app.pristinepage.data.repository.RoomDocumentRepository
import app.pristinepage.data.repository.RoomEditablePages
import app.pristinepage.data.repository.RoomMetadataAccess
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryCatalogMergeTest {
    @Test fun preflightStagesBothPortableCatalogsWithoutWritingEitherLibrary() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { local ->
            RoomSourceIngestIntegrationTest.Fixture().use { incoming ->
                local.sourceDisplayName = "Cover.png"
                local.store.ingest(local.reference, local.reserve().single()) as IngestResult.Committed
                val (first, second) = incoming.reserve(2)
                incoming.sourceDisplayName = "cover.png"
                incoming.store.ingest(incoming.reference, first) as IngestResult.Committed
                incoming.sourceDisplayName = "Second.png"
                incoming.store.ingest(incoming.reference, second) as IngestResult.Committed
                val context = InstrumentationRegistry.getInstrumentation().context
                val root = File(context.filesDir.canonicalFile, "library-preflight-${UUID.randomUUID()}").apply { check(mkdir()) }
                val scratch = File(root, "scratch").apply { check(mkdir()) }
                val destination = File(root, "destination")
                val backup = File(root, "backup")
                val destinationGeneration = RoomLibraryLocalExport(context, local.database, local.root.canonicalFile, scratch).writeNewFolder(destination)
                val incomingGeneration = RoomLibraryLocalExport(context, incoming.database, incoming.root.canonicalFile, scratch).writeNewFolder(backup)
                val before = listOf(destination, backup).map { library ->
                    library.walkTopDown().filter(File::isFile).associate { it.relativeTo(library).path to it.readBytes().toList() }
                }
                val preflight = RoomLibraryLocalMergePreflight(context, scratch).verify(UUID.randomUUID().toString(), destination, backup)
                assertEquals(destinationGeneration, preflight.destination)
                assertEquals(incomingGeneration, preflight.incoming)
                assertEquals(0L, preflight.payloads.missingBytes) // Both logical slots use the one existing byte object.
                assertTrue(preflight.catalog.byteCount > 0)
                assertEquals(before, listOf(destination, backup).map { library ->
                    library.walkTopDown().filter(File::isFile).associate { it.relativeTo(library).path to it.readBytes().toList() }
                })
                assertEquals(emptyList<String>(), scratch.list()?.toList())
            }
        }
    }

    @Test fun temporaryMergedCatalogReopensWithBothHistoriesAndTheIncomingSavedOcr() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { local ->
            RoomSourceIngestIntegrationTest.Fixture().use { incoming ->
                local.sourceDisplayName = "Cover.png"
                val original = local.store.ingest(local.reference, local.reserve().single()) as IngestResult.Committed
                val (first, second) = incoming.reserve(count = 2)
                incoming.sourceDisplayName = "cover.png"
                val matching = incoming.store.ingest(incoming.reference, first) as IngestResult.Committed
                incoming.sourceDisplayName = "Appendix.png"
                val appendix = incoming.store.ingest(incoming.reference, second) as IngestResult.Committed
                val io = RoomMetadataAccess(incoming.database)
                val commands = RoomDocumentRepository(io, RecipeAdmission { _, _ -> null })
                assertTrue(commands.rotatePage(matching.pageId, 1, 1) is DocumentMutationResult.Applied)
                val edit = RoomEditablePages(io, incoming.database.catalog()).load(incoming.document, matching.pageId)
                val text = "  Exact incoming café\r\n"
                val config = OcrConfiguration("fixture-ocr", 1, OcrLayout.SPARSE_TEXT,
                    OcrLanguagePacks(listOf(OcrLanguagePack("eng", "e".repeat(64)))))
                val producer = JobId(UUID.randomUUID().toString())
                val intent = OcrJobInput(incoming.document, OcrInput(matching.pageId.value, 2, matching.sourcePixelHash),
                    matching.sourceId, edit.recipeHash, 1, config)
                io.transaction { RoomLibrarySavedOcr(incoming.database).restoreInTransaction(LibrarySavedOcr(producer, intent, OcrText(text), 1, 11, 12)) }

                val targetCatalog = RoomLibrarySavedCatalog(local.database)
                val sourceCatalog = RoomLibrarySavedCatalog(incoming.database)
                val candidate = LibraryCatalogMerge.plan(UUID.randomUUID().toString(), targetCatalog.capture(), sourceCatalog.capture())
                val context = InstrumentationRegistry.getInstrumentation().context
                val root = File(context.filesDir.canonicalFile, "library-merge-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
                val stage = File(root, "catalog.pending.sqlite")
                RoomLibraryCatalogStage(context).write(candidate.catalog, stage)
                val reader = RoomLibraryCatalogReader(context, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)
                val closed = reader.read(stage) { LibraryPortableCatalogProjection(it) {}.snapshot() }
                val album = closed.albums.single()
                val page = album.pages.first()
                assertEquals(local.document.value, album.row.documentId)
                assertEquals(listOf("Cover.png", "Appendix.png"), album.pages.map { it.filename })
                assertEquals(listOf(original.pageId.value, appendix.pageId.value), album.pages.map { it.row.pageId })
                assertEquals(listOf(0, 0, 1), page.revisions.map { RecipeCodec.decode(it.canonicalRecipe).geometry.outputQuarterTurns })
                assertEquals(3L, page.row.currentRecipeRevision)
                val saved = checkNotNull(page.savedOcr)
                assertEquals(text, saved.text.value)
                assertEquals(config, saved.intent.configuration)
                assertEquals(producer, saved.producerId)
                assertEquals(original.pageId.value, saved.intent.input.pageId)
                assertEquals(3L, saved.intent.input.recipeRevision)
                assertEquals(listOf("catalog.pending.sqlite"), root.list()?.toList())
                assertEquals(1L, targetCatalog.capture().albums.single().pages.single().row.currentRecipeRevision)
                assertEquals(2L, sourceCatalog.capture().albums.single().pages.first().row.currentRecipeRevision)

                // Keep exact recognized text and its job but make the physical FTS index agree with deliberately wrong indexed text.
                SQLiteDatabase.openDatabase(stage.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sql ->
                    // API 28 can opt a writable connection into compatibility WAL on open. The test must exercise the OCR guard, not the standalone-file guard.
                    sql.rawQuery("PRAGMA journal_mode=DELETE", null).use { assertTrue(it.moveToFirst()); assertEquals("delete", it.getString(0)) }
                    sql.execSQL("UPDATE OcrResult SET indexedText='decoy searchable token'")
                    sql.rawQuery("SELECT rowid FROM OcrResultFts WHERE OcrResultFts MATCH 'decoy'", null).use { assertTrue(it.moveToFirst()) }
                }
                assertEquals(text, reader.read(stage) { LibraryPortableCatalogProjection(it) {}.snapshot() }
                    .albums.single().pages.first().savedOcr?.text?.value)
                assertTrue(runCatching { reader.verifyStagedProjection(stage, candidate.catalog) {} }.exceptionOrNull() is IOException)
            }
        }
    }
}
