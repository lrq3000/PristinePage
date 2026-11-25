package app.pristinepage

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.model.SourceInspection
import app.pristinepage.data.library.LibraryFolderExportReceipt
import app.pristinepage.data.repository.SourceInput
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*

/** The ordinary ingest and edit services, a real Files grant, and an independent read of the completed backup. */
internal object LibraryDefaultFolderExportProbe {
    class Backup(
        val receipt: LibraryFolderExportReceipt, val album: DocumentId, val page: PageId, val blob: SourceBlobId,
        val original: ByteArray, val digest: String, val inspection: SourceInspection, val revision: Long,
        private val originalFile: File, private val pointer: File, private val pointerBefore: ByteArray,
    ) {
        fun assertSourceUnchanged() {
            assertArrayEquals(original, originalFile.readBytes())
            assertArrayEquals(pointerBefore, pointer.readBytes())
        }
    }

    suspend fun verify(app: Context, tree: Uri, persistWrite: () -> Uri): Backup {
        val isolated = File(app.filesDir.canonicalFile, "library-export-source-${UUID.randomUUID()}").apply { check(mkdir()) }
        val files = File(isolated, "files").apply { check(mkdir()) }
        val db = File(isolated, "databases").apply { check(mkdir()) }
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File = File(db, name)
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
            try {
                store.exportLibraryTo(tree)
                fail("Export used a read-only grant")
            } catch (_: IOException) { /* Do not proceed until this fixture has the exact write grant. */ }
            assertEquals(tree, persistWrite())
            val backup = store.exportLibraryTo(tree)
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
            SQLiteDatabase.openDatabase(catalog.path, null, SQLiteDatabase.OPEN_READONLY).use { sql ->
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
            }
            assertArrayEquals(activeBefore, defaultPointer.readBytes())
            assertArrayEquals(png, source.file.readBytes())
            assertEquals(saved.recipeHash, store.editablePages.load(album, ingest.pageId).recipeHash)
            val attempt = files.listFiles().orEmpty().single { it.name.startsWith("library-out-") }
            assertTrue(File(attempt, "export.intent").isFile)
            assertEquals(
                "pristinepage-library-export-complete-v1\n${backup.folderName}\n${marker.joinToString("\n")}",
                File(attempt, "export.complete").readText(Charsets.US_ASCII),
            )
            assertEquals(1, reads)
            return Backup(backup, album, ingest.pageId, source.sourceBlobId, png, ingest.sourceByteHash, inspection,
                saved.recipeRevision, source.file, defaultPointer, activeBefore)
        }
    }
}
