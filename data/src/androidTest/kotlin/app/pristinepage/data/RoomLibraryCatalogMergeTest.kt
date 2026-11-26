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
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.LibraryCatalogMerge
import app.pristinepage.data.library.LibraryPortableCatalogProjection
import app.pristinepage.data.library.LibrarySavedOcr
import app.pristinepage.data.library.LocalLibraryDirectory
import app.pristinepage.data.library.RoomLibraryCatalogReader
import app.pristinepage.data.library.RoomLibraryCatalogStage
import app.pristinepage.data.library.RoomLibraryLocalExport
import app.pristinepage.data.library.RoomLibraryLocalMerge
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
    @Test fun closedPortableProjectionRefusesAnOtherwiseValidCatalogWithAnUnusableTitle() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-title-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
            val stage = File(root, "catalog.pending.sqlite")
            RoomLibraryCatalogStage(context).write(RoomLibrarySavedCatalog(source.database).capture(), stage)
            val reader = RoomLibraryCatalogReader(context, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)
            assertEquals(source.document.value, reader.read(stage) { LibraryPortableCatalogProjection(it) {}.snapshot() }.albums.single().row.documentId)
            SQLiteDatabase.openDatabase(stage.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { sql ->
                sql.rawQuery("PRAGMA journal_mode=DELETE", null).use { assertTrue(it.moveToFirst()); assertEquals("delete", it.getString(0)) }
                sql.execSQL("UPDATE Document SET title=?", arrayOf<Any>("Receipts\u2003"))
            }
            val before = stage.readBytes()
            val error = runCatching { reader.read(stage) { LibraryPortableCatalogProjection(it) {}.snapshot() } }.exceptionOrNull()
            assertTrue(error is IOException && error.message == "Library catalog rows do not match the portable format")
            assertArrayEquals(before, stage.readBytes())
        }
    }

    @Test fun repeatedFreshCatalogReopenPreservesExactUnicodeTitleBytes() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { source ->
            val context = InstrumentationRegistry.getInstrumentation().context
            val root = File(context.filesDir.canonicalFile, "library-title-unicode-${UUID.randomUUID()}").apply { check(mkdir()) }
            val title = "\ufeff\ufeff Cafe\u0301 \u0000 scan"
            source.database.openHelper.writableDatabase.execSQL("UPDATE Document SET title=CAST(? AS TEXT) WHERE documentId=?",
                arrayOf<Any>(title.encodeToByteArray(), source.document.value))
            val first = RoomLibrarySavedCatalog(source.database).capture()
            assertEquals(title, first.albums.single().row.title)
            val reader = RoomLibraryCatalogReader(context, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)
            var snapshot = first
            repeat(2) { n ->
                val stage = File(root, "catalog-$n.sqlite")
                RoomLibraryCatalogStage(context).write(snapshot, stage)
                snapshot = reader.read(stage) { LibraryPortableCatalogProjection(it) {}.snapshot() }
                assertEquals(title, snapshot.albums.single().row.title)
            }
        }
    }

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
                val text = "  Exact incoming cafe\u0301\r\n"
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
                    sql.rawQuery("SELECT verbatimText,indexedText FROM OcrResult", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals(text, it.getString(0))
                        assertEquals("  Exact incoming café\r\n", it.getString(1))
                    }
                    sql.execSQL("UPDATE OcrResult SET indexedText='decoy searchable token'")
                    sql.rawQuery("SELECT rowid FROM OcrResultFts WHERE OcrResultFts MATCH 'decoy'", null).use { assertTrue(it.moveToFirst()) }
                }
                assertEquals(text, reader.read(stage) { LibraryPortableCatalogProjection(it) {}.snapshot() }
                    .albums.single().pages.first().savedOcr?.text?.value)
                val error = runCatching { reader.verifyStagedProjection(stage, candidate.catalog) {} }.exceptionOrNull()
                assertTrue(error is IOException && error.message == "Library catalog rows do not match the portable format")
            }
        }
    }

    @Test fun exactRoomCopyAddsOnlyTheMissingOriginalAndReplaysWithoutWritingEitherLibrary() = runBlocking<Unit> {
        RoomSourceIngestIntegrationTest.Fixture().use { local ->
            RoomSourceIngestIntegrationTest.Fixture(bytes = byteArrayOf(5, 6, 7, 8, 9)).use { incoming ->
                local.sourceDisplayName = "Cover.png"
                val cover = local.store.ingest(local.reference, local.reserve().single()) as IngestResult.Committed
                incoming.sourceDisplayName = "Appendix.png"
                val appendix = incoming.store.ingest(incoming.reference, incoming.reserve().single()) as IngestResult.Committed
                val io = RoomMetadataAccess(incoming.database)
                val commands = RoomDocumentRepository(io, RecipeAdmission { _, _ -> null })
                assertTrue(commands.rotatePage(appendix.pageId, 1, 1) is DocumentMutationResult.Applied)
                val edit = RoomEditablePages(io, incoming.database.catalog()).load(incoming.document, appendix.pageId)
                val text = "  Saved cafe\u0301\r\n"
                val config = OcrConfiguration("fixture-ocr", 1, OcrLayout.SPARSE_TEXT,
                    OcrLanguagePacks(listOf(OcrLanguagePack("eng", "e".repeat(64)))))
                val producer = JobId(UUID.randomUUID().toString())
                val intent = OcrJobInput(incoming.document, OcrInput(appendix.pageId.value, 2, appendix.sourcePixelHash),
                    appendix.sourceId, edit.recipeHash, 1, config)
                io.transaction { RoomLibrarySavedOcr(incoming.database).restoreInTransaction(LibrarySavedOcr(producer, intent, OcrText(text), 1, 11, 12)) }

                val context = InstrumentationRegistry.getInstrumentation().context
                val root = File(context.filesDir.canonicalFile, "library-copy-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
                val scratch = File(root, "scratch").apply { check(mkdir()) }
                val destination = File(root, "destination")
                val backup = File(root, "backup")
                val baseline = RoomLibraryLocalExport(context, local.database, local.root.canonicalFile, scratch).writeNewFolder(destination)
                val source = RoomLibraryLocalExport(context, incoming.database, incoming.root.canonicalFile, scratch).writeNewFolder(backup)
                val sourceBefore = fileTree(backup)
                val destinationBefore = fileTree(destination)
                val operation = UUID.randomUUID().toString()
                val copier = RoomLibraryLocalMerge(context)
                val confirmed = copier.prepare(operation, destination, backup)
                assertEquals(baseline, confirmed.preview.destination)
                assertEquals(source, confirmed.preview.incoming)
                assertEquals(5L, confirmed.preview.payloads.missingBytes)
                val moved = File(root, "original-backup")
                check(backup.renameTo(moved))
                check(moved.copyRecursively(backup))
                assertEquals(sourceBefore, fileTree(backup))
                assertTrue(runCatching { copier.copy(confirmed) }.exceptionOrNull() is IOException)
                assertEquals(destinationBefore, fileTree(destination))
                val duplicate = File(root, "byte-identical-replacement")
                check(backup.renameTo(duplicate))
                check(moved.renameTo(backup))

                val interrupt = RoomLibraryLocalMerge(context, DirectorySync { folder ->
                    !(folder.name == "objects" && folder.listFiles().orEmpty().count { !it.name.startsWith(".") } == 2) &&
                        PlatformDirectorySync.sync(folder)
                })
                assertTrue(runCatching { interrupt.copy(confirmed) }.exceptionOrNull() is IOException)
                assertEquals(baseline, LocalLibraryDirectory(destination).readTip())
                assertEquals(2, fileTree(destination).keys.count { it.startsWith("objects/") && !it.substringAfterLast('/').startsWith(".") })
                val unchangedAfterInterruption = fileTree(destination)
                val differentSource = copier.prepare(operation, destination, duplicate)
                assertTrue(runCatching { copier.copy(differentSource) }.exceptionOrNull() is IOException)
                assertTrue(runCatching { copier.resume(operation, destination, duplicate) }.exceptionOrNull() is IOException)
                assertEquals(unchangedAfterInterruption, fileTree(destination))
                val child = RoomLibraryLocalMerge(context).resume(operation, destination, backup)
                assertEquals(operation, child.id)
                assertEquals(baseline.id, child.predecessorId)
                assertEquals(child, LocalLibraryDirectory(destination).readTip())
                val destinationAfter = fileTree(destination)
                assertTrue(destinationAfter.entries.containsAll(destinationBefore.entries))
                val originalKeys = destinationAfter.keys.filter { it.startsWith("objects/") && !it.substringAfterLast('/').startsWith(".") }
                assertEquals(1, (originalKeys.toSet() - destinationBefore.keys).size)
                val reader = RoomLibraryCatalogReader(context, RoomLibraryCatalogReader.MAX_PORTABLE_BYTES)
                val closed = reader.read(File(destination, child.catalogPath)) { sql ->
                    sql.rawQuery("SELECT rowid FROM OcrResultFts WHERE OcrResultFts MATCH 'café'", null).use {
                        assertTrue(it.moveToFirst()); assertFalse(it.moveToNext())
                    }
                    LibraryPortableCatalogProjection(sql, verifyDerivedOcr = true) {}.snapshot()
                }
                val album = closed.albums.single()
                assertEquals(listOf("Cover.png", "Appendix.png"), album.pages.map { it.filename })
                assertEquals(listOf(cover.pageId.value, appendix.pageId.value), album.pages.map { it.row.pageId })
                val edited = album.pages.last()
                assertEquals(listOf(0, 1), edited.revisions.map { RecipeCodec.decode(it.canonicalRecipe).geometry.outputQuarterTurns })
                val saved = checkNotNull(edited.savedOcr)
                assertEquals(text, saved.text.value)
                assertEquals(producer, saved.producerId)
                assertEquals(2L, saved.intent.input.recipeRevision)
                assertEquals(setOf(local.bytes.toList(), incoming.bytes.toList()),
                    originalKeys.map { destinationAfter.getValue(it) }.toSet())
                assertEquals(child, RoomLibraryLocalMerge(context).resume(operation, destination, backup))
                assertEquals(destinationAfter, fileTree(destination))
                assertEquals(sourceBefore, fileTree(backup))
            }
        }
    }

    private fun fileTree(root: File) = root.walkTopDown().filter(File::isFile)
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }
}
