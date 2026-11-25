package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentResolver
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.SourceBlobRow
import app.pristinepage.data.db.SourceRow
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.library.LibraryFolderExportReceipt
import app.pristinepage.data.library.AndroidLibraryBackupHistory
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.LibraryFolderExportRequest
import app.pristinepage.data.library.LibraryFolderExportState
import app.pristinepage.data.repository.ExportSourceBinding
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.library.LibraryFolderPickerContract
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.*

/** The ordinary ingest and edit services, a real Files grant, and an independent read of the completed backup. */
internal object LibraryDefaultFolderExportProbe {
    class Backup(
        val receipt: LibraryFolderExportReceipt, val album: DocumentId, val page: PageId, val source: SourceId, val blob: SourceBlobId,
        val original: ByteArray, val digest: String, val inspection: SourceInspection, val revision: Long,
        val producer: ExportSourceBinding,
        private val originalFile: File, private val pointer: File, private val pointerBefore: ByteArray,
    ) {
        fun assertSourceUnchanged() {
            assertArrayEquals(original, originalFile.readBytes())
            assertArrayEquals(pointerBefore, pointer.readBytes())
        }
    }

    suspend fun verify(app: Context, picked: LibraryFolderPickerContract.Picked, persistWrite: () -> Uri): Backup {
        val tree = picked.uri
        val isolated = File(app.filesDir.canonicalFile, "library-export-source-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = File(isolated, "files").apply { check(mkdir()) }
        val db = File(isolated, "databases").apply { check(mkdir()) }
        val forbidProvider = AtomicBoolean()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(db, name)
            override fun getContentResolver(): ContentResolver {
                check(!forbidProvider.get()) { "Refused or historical export accessed the provider" }
                return app.contentResolver
            }
        }
        val png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAGElEQVR4nGP4z8DAAME2NjYVFRX///8HAD+JCBdips5AAAAAAElFTkSuQmCC", Base64.NO_WRAP)
        val reference = "content://fixture.documents/library-export-original"
        var reads = 0
        val graph = ApplicationServices(context, ApplicationImageBudget.create(64), SourceInput {
            check(it == reference && ++reads == 1)
            ByteArrayInputStream(png)
        }).imports()
        graph.store.use { store ->
            val album = store.documents.createDocument("Synthetic default backup")
            val batch = store.imports.beginImport(album, 1, null)
            val ingest = graph.photoImports.importProviderReferences(listOf(reference), batch.parentId).single() as IngestResult.Committed
            val source = store.committedSources.load(ingest.sourceId)
            val inspection = source.inspection
            val recipe = Recipe(geometry = GeometryRecipe.None(
                SourceBinding(inspection.sourcePixelHash, inspection.normalizedSize, inspection.conversionFingerprint), outputQuarterTurns = 1,
            ))
            val saved = store.documents.saveRecipe(ingest.pageId, 1, recipe) as SaveRecipeResult.Saved
            val defaultPointer = File(files, "library-settings/active-library")
            val activeBefore = defaultPointer.readBytes()
            val operation = UUID.randomUUID().toString()
            val modes = if (picked.grant.returnedWrite) 3 else 1
            val attempt = files.resolve("library-out-$operation")
            val completion = attempt.resolve("export.complete")
            fun grants() = app.contentResolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }
            val parent = DocumentsContract.getTreeDocumentId(tree)
            fun destinationNames(): Set<String> = checkNotNull(app.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            fun noAttempt() = assertTrue(files.listFiles()!!.none { it.name.startsWith("library-out-") })
            val namesBefore = destinationNames()
            val readGrants = grants()
            val bindResult = { LibraryFolderExportRequest.bind(operation, graph.activation, tree, modes) }
            refused("The selected backup folder did not return writable local access") {
                LibraryFolderExportRequest.bind(operation, graph.activation, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            refused("The exact library folder is not writable") { store.exportLibraryTo(bindResult()) }
            assertEquals(readGrants, grants())
            assertEquals(namesBefore, destinationNames())
            noAttempt()
            assertEquals(tree, persistWrite())
            val writeGrants = grants()
            val stale = LibraryFolderExportRequest.bind(operation, graph.activation.copy(number = graph.activation.number + 1), tree, modes)
            forbidProvider.set(true)
            try { refused("The backup no longer belongs to the selected default library") { store.exportLibraryTo(stale) } }
            finally { forbidProvider.set(false) }
            assertEquals(writeGrants, grants())
            assertEquals(namesBefore, destinationNames())
            noAttempt()

            // Run the real Room/provider publication. Cancel only the dispatch back to the caller after the private completion is visible.
            val withheld = AtomicBoolean()
            val delivery = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    if (completion.isFile && withheld.compareAndSet(false, true)) checkNotNull(context[Job]).cancel()
                    Dispatchers.Default.dispatch(context, block)
                }
            }
            coroutineScope {
                val result = async(delivery, start = CoroutineStart.UNDISPATCHED) {
                    store.exportLibraryTo(bindResult())
                    fail("The canceled caller received a successful export")
                }
                try { result.await(); fail("The export delivery was not canceled") }
                catch (_: CancellationException) { assertTrue(withheld.get()) }
            }
            // First receipt lookup uses a new context that can read the durable pointer but cannot open Room or Files.
            val coldContext = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getDatabasePath(name: String): File = error("Cold backup discovery touched Room")
                override fun getContentResolver(): ContentResolver = error("Cold backup discovery touched Files")
            }
            val coldActivation = checkNotNull(AndroidLibraryStartup(coldContext).recordedDefaultActivation())
            assertEquals(graph.activation, coldActivation)
            val coldHistory = AndroidLibraryBackupHistory(coldContext)
            val discovered = coldHistory.discoverActive(coldActivation)
            assertEquals(0, discovered.unresolvedCount)
            val recovered = discovered.receipts.single() as LibraryFolderExportState.Completed
            val backup = recovered.receipt
            assertEquals(operation, backup.operationId)
            assertTrue(backup.folderName.matches(Regex("PristinePage-backup-[0-9a-f-]{36}")))
            val root = "${DocumentsContract.getTreeDocumentId(tree)}/${backup.folderName}"
            fun document(path: String) = DocumentsContract.buildDocumentUriUsingTree(tree, "$root/$path")
            fun read(path: String, max: Int): ByteArray = checkNotNull(app.contentResolver.openInputStream(document(path))).use {
                val bytes = ByteArray(max + 1)
                var count = 0
                while (count < bytes.size) {
                    val n = it.read(bytes, count, bytes.size - count)
                    if (n < 0) break
                    check(n > 0)
                    count += n
                }
                check(count <= max)
                bytes.copyOf(count)
            }

            assertArrayEquals(png, read("objects/${ingest.sourceByteHash}", png.size))
            val marker = read("generations/${backup.generationId}.complete", 256).toString(Charsets.US_ASCII).split('\n')
            val catalog = File(isolated, "readback.sqlite")
            catalog.writeBytes(read("catalogs/${backup.generationId}.sqlite", 2 * 1024 * 1024))
            assertEquals(listOf("pristinepage-library-v1", backup.libraryId, backup.generationId, "-"), marker.take(4))
            assertEquals(catalog.length(), marker[5].toLong())
            assertEquals(marker[6], MessageDigest.getInstance("SHA-256").digest(catalog.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
            val producer = SQLiteDatabase.openDatabase(catalog.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
                sql.rawQuery(
                    """SELECT r.recipeRevision, r.canonicalRecipe FROM RecipeRevision r
                        JOIN Page p ON p.pageId=r.pageId JOIN Document d ON d.documentId=p.documentId
                        WHERE d.title='Synthetic default backup' AND p.pageId=?
                        ORDER BY r.recipeRevision""",
                    arrayOf(ingest.pageId.value),
                ).use { rows ->
                    assertEquals(2, rows.count)
                    assertTrue(rows.moveToLast())
                    assertEquals(saved.recipeRevision, rows.getLong(0))
                    assertEquals(recipe, RecipeCodec.decode(rows.getBlob(1)))
                }
                sql.rawQuery(
                    "SELECT s.createdAt,b.ingestTransactionId,b.createdAt FROM Source s JOIN SourceBlob b ON b.sourceBlobId=s.sourceBlobId WHERE s.sourceId=?",
                    arrayOf(ingest.sourceId.value),
                ).use { rows ->
                    assertEquals(1, rows.count)
                    assertTrue(rows.moveToFirst())
                    ExportSourceBinding(
                        SourceRow(ingest.sourceId.value, source.sourceBlobId.value, inspection.sourcePixelHash,
                            inspection.normalizedSize.width, inspection.normalizedSize.height, inspection.exifOrientation,
                            inspection.conversionFingerprint, inspection.profileSha256, 1, "READY", rows.getLong(0)),
                        SourceBlobRow(source.sourceBlobId.value, ingest.sourceByteHash, png.size.toLong(), inspection.mime,
                            inspection.encodedSize.width, inspection.encodedSize.height, "sources/${source.sourceBlobId.value}/original.bin",
                            "READY", 1, rows.getString(1), rows.getLong(2)),
                    )
                }
            }
            assertArrayEquals(activeBefore, defaultPointer.readBytes())
            assertArrayEquals(png, source.file.readBytes())
            assertEquals(saved.recipeHash, store.editablePages.load(album, ingest.pageId).recipeHash)
            assertEquals(attempt, files.listFiles().orEmpty().single { it.name.startsWith("library-out-") })
            assertTrue(File(attempt, "export.intent").isFile)
            val completeBefore = completion.readBytes()
            assertFalse(File(attempt, "export.ack").exists())
            forbidProvider.set(true)
            try {
                refused("This backup operation cannot be repeated") { store.exportLibraryTo(bindResult()) }
            } finally { forbidProvider.set(false) }
            assertEquals(namesBefore + backup.folderName, destinationNames())
            assertEquals(writeGrants, grants())
            app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
            forbidProvider.set(true)
            try {
                assertEquals(discovered, coldHistory.discoverActive(coldActivation))
                assertEquals(recovered, store.inspectLibraryExport(operation, graph.activation))
                store.acknowledgeLibraryExport(backup, graph.activation)
                assertEquals(LibraryFolderExportState.Acknowledged(backup), store.inspectLibraryExport(operation, graph.activation))
                store.acknowledgeLibraryExport(backup, graph.activation)
                assertArrayEquals(completeBefore, completion.readBytes())
                val history = AndroidLibraryBackupHistory(context)
                assertTrue(history.archiveAcknowledged(operation, graph.activation))
                assertTrue(history.archiveAcknowledged(operation, graph.activation))
                assertEquals(LibraryFolderExportState.Acknowledged(backup), history.inspect(operation, graph.activation))
                val afterArchive = coldHistory.discoverActive(coldActivation)
                assertTrue(afterArchive.receipts.isEmpty())
                assertEquals(0, afterArchive.unresolvedCount)
                refused("This backup operation cannot be repeated") { store.exportLibraryTo(bindResult()) }
            } finally { forbidProvider.set(false) }
            assertFalse(attempt.exists())
            val archived = File(files, "library-backup-acknowledged/${attempt.name}")
            assertFalse(File(archived, "catalog.sqlite").exists())
            assertArrayEquals(completeBefore, File(archived, "export.complete").readBytes())
            assertEquals(namesBefore + backup.folderName, destinationNames())
            assertEquals(1, reads)
            return Backup(backup, album, ingest.pageId, ingest.sourceId, source.sourceBlobId, png, ingest.sourceByteHash, inspection,
                saved.recipeRevision, producer, source.file, defaultPointer, activeBefore)
        }
    }

    private suspend fun refused(expected: String, block: suspend () -> Unit) {
        try { block(); fail("The backup refusal did not run") }
        catch (failure: IOException) { assertEquals(expected, failure.message) }
    }
}
