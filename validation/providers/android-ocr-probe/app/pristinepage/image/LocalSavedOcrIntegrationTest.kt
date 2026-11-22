package app.pristinepage.image

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.RenderCoordinator
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.EditablePage
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceId
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.ocr.SavedOcrPreparer
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The explicit probe build alone admits this isolated, synthetic native recognition-to-search journey. */
@RunWith(AndroidJUnit4::class)
class LocalSavedOcrIntegrationTest {
    @Test fun savedSyntheticPagesAreRecognizedAndSearchableAfterReopen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.pristinepage.debug.test", instrumentation.context.packageName)
        assertEquals(instrumentation.context.packageName, context.packageName)
        assertFalse(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        val directory = newDirectory(context.filesDir, "ocr-saved-probe-${UUID.randomUUID()}")
        val scoped = isolatedContext(context, directory)
        val databaseFile = scoped.getDatabasePath("pristinepage.db")
        assertEquals(File(directory, "databases").canonicalFile, databaseFile.canonicalFile.parentFile)
        assertFalse(databaseFile.exists())
        val packRoot = newDirectory(directory, "packs")
        val tessdata = newDirectory(packRoot, "tessdata")
        val manifest = JSONObject(LocalTesseractProbeTest.load(context, "${ROOT}manifest.json", 32_768).toString(Charsets.UTF_8))
        val packs = manifest.getJSONArray("packs")
        val pins = (0 until packs.length()).associate { index ->
            val row = packs.getJSONObject(index)
            val code = row.getString("code")
            assertTrue(anchors.containsKey(code))
            LocalTesseractProbeTest.stagePack(context, tessdata, code, row)
            code to OcrLanguagePack(code, row.getString("sha256"))
        }
        assertEquals(anchors.keys, pins.keys)
        verifyPrivateBitmapLifecycle(OcrConfiguration("tesseract-local-probe", 1, OcrLayout.SINGLE_BLOCK,
            OcrLanguagePacks(listOf(pins.getValue("eng")))))
        val budget = ImageAllocationBudget.forMemoryClass(checkNotNull(context.getSystemService(ActivityManager::class.java)).memoryClass)
        val inspector = AndroidSourceInspector(budget)
        val activeInput = AtomicReference<Pair<String, ByteArray>?>()
        val provider = SourceInput { reference ->
            val current = checkNotNull(activeInput.get())
            check(current.first == reference)
            current.second.inputStream()
        }
        suspend fun open() = PrivateImportStore.open(scoped, provider, inspector, ClassicalRecipeAdmission())
        val failures = mutableListOf<String>()
        val observed = linkedMapOf<String, SavedOcrWork.Target>()
        val cases = manifest.getJSONArray("cases")
        val caseIds = anchors.keys.toList() + listOf("eng-fra", "jpn-eng", "ara-eng", "blank")
        assertEquals(caseIds.size, cases.length())
        var album: DocumentId? = null
        open().use { store ->
            val document = store.documents.createDocument("Synthetic native OCR controls")
            album = document
            assertTrue(databaseFile.isFile)
            val renderer = DefaultRenderCoordinator(SourceDecoder(store.committedSources, inspector), GeometryEngine(budget), AppearanceEngine(budget))
            for ((index, id) in caseIds.withIndex()) {
                val row = cases.getJSONObject(index)
                assertEquals(id, row.getString("id"))
                val selected = orderedLanguages(id)
                assertEquals(selected.joinToString("+"), row.getString("languages"))
                val configuration = OcrConfiguration("tesseract-local-probe", 1,
                    if (id == "jpn") OcrLayout.SPARSE_TEXT else OcrLayout.SINGLE_BLOCK,
                    OcrLanguagePacks(selected.map(pins::getValue)))
                val png = LocalTesseractProbeTest.load(context, "${ROOT}controls/$id.png", 1_048_576)
                assertEquals(row.getInt("imageBytes"), png.size)
                val actualHash = LocalTesseractProbeTest.hex(MessageDigest.getInstance("SHA-256").digest(png))
                assertEquals(row.getString("imageSha256"), actualHash)
                val ref = "content://app.pristinepage.ocr.integration/$id"
                val saved = try {
                    activeInput.set(ref to png)
                    val revision = store.catalog.load(document).document.documentRevision
                    val batch = store.imports.beginImport(document, revision, null)
                    val selection = store.selections.prepareSelection(batch.parentId, listOf(ref))
                    val result = store.sources.ingestBoundProvider(ref, selection.childIds.single())
                    assertTrue("Synthetic ingest did not commit: $id (${result.javaClass.simpleName})", result is IngestResult.Committed)
                    result as IngestResult.Committed
                } finally { activeInput.set(null) }
                assertEquals(actualHash, saved.sourceByteHash)
                assertTrue(expectedSearches().filter { id in it.pages }.none { probe ->
                    findAll(store, probe.query).any { it.page == saved.pageId }
                })
                val target = store.savedOcr.reconcile(configuration, null, 50).targets.single { it.page == saved.pageId }
                if (id == "eng") {
                    var opened = false
                    val unavailable = SavedOcrPreparer(renderer, budget) { _, bitmap ->
                        assertFalse(bitmap.isRecycled)
                        assertTrue(budget.usedBytes > 0)
                        opened = true
                        throw RepositoryFailure(OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "fixture_runtime_unavailable", null))
                    }
                    val failure = runCatching { store.savedOcr.execute(target, unavailable) }.exceptionOrNull()
                    assertTrue(opened)
                    assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, (failure as? RepositoryFailure)?.error?.code)
                    assertEquals(0L, budget.usedBytes)
                    assertEquals(target, store.savedOcr.reconcilePage(document, saved.pageId, null))
                }
                var score: Score? = null
                val prepare = SavedOcrPreparer(renderer, budget) { config, bitmap ->
                    assertEquals(configuration, config)
                    val local = TessBaseAPI()
                    var transferred = false
                    try {
                        assertEquals("5.5.1", local.version)
                        val effective = checkNotNull(config.languagePacks).ordered.joinToString("+") { it.code }
                        assertTrue(local.init(packRoot.absolutePath, effective, TessBaseAPI.OEM_LSTM_ONLY))
                        assertTrue(local.setVariable("user_defined_dpi", "300"))
                        local.setPageSegMode(if (config.layout == OcrLayout.SPARSE_TEXT) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                            else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                        local.setImage(bitmap)
                        object : SavedOcrWork.Prepared {
                            override suspend fun recognize(): OcrText {
                                val raw = checkNotNull(local.getUTF8Text())
                                val text = OcrText(raw)
                                score = grade(id, row.getString("expected"), raw)
                                return text
                            }
                            override fun close() = local.recycle()
                        }.also { transferred = true }
                    } finally {
                        if (!transferred) local.recycle()
                    }
                }
                val outcome = store.savedOcr.execute(target) { page, config, handoff ->
                    assertEquals(saved.sourceId, page.sourceId)
                    prepare.prepare(page, config, handoff)
                }
                assertEquals("Fenced publication: $id", SavedOcrWork.Outcome.PUBLISHED, outcome)
                assertEquals(0L, budget.usedBytes)
                val result = checkNotNull(score)
                if (!result.accepted) failures.add(id)
                Log.i(TAG, "case=$id edits=${result.edits} length=${result.length} exact=${result.edits == 0}")
                assertNull(observed.put(id, target))
            }
            verifySearch(store, document, observed, "published", failures)
        }
        open().use { store ->
            val document = checkNotNull(album)
            assertEquals(caseIds.size, store.catalog.load(document).pages.size)
            verifySearch(store, document, observed, "reopened", failures)
            for ((id, target) in observed) {
                val terminal = store.savedOcr.execute(target) { _, _, _ -> error("Reopen attempted to prepare completed OCR work") }
                if (terminal != SavedOcrWork.Outcome.TERMINAL) failures.add("$id:reopen")
            }
        }
        verifyPersistedControls(databaseFile, observed)
        assertTrue("Saved OCR controls not accepted: ${failures.joinToString()}", failures.isEmpty())
    }

    private suspend fun verifyPrivateBitmapLifecycle(config: OcrConfiguration) = coroutineScope {
        val sourceHash = "a".repeat(64)
        val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(sourceHash, PixelSize(2, 3), "ocr-probe-v1")))
        val page = EditablePage(DocumentId(UUID.randomUUID().toString()), 2, PageId(UUID.randomUUID().toString()),
            SourceId(UUID.randomUUID().toString()), 2, RecipeCodec.hash(recipe), recipe)
        for (cancelDuringOpen in listOf(false, true)) {
            val budget = ImageAllocationBudget(128)
            var renderReleased = false
            var sessionCloses = 0
            var borrowed: Bitmap? = null // Test-only observation of the borrowed pointer; this is never delivered to UI or used after recycle.
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest): RenderResult {
                    val rgb = ByteArray(18).apply { this[1] = 128.toByte(); this[2] = 255.toByte() }
                    val permit = budget.reserve(18)
                    return RenderResult(request, RenderStatus.OK, RgbImage(2, 3, 6, rgb), emptyList(), emptyList(),
                        AutoCloseable { renderReleased = true; permit.close() })
                }
            }
            val child = launch {
                val active = currentCoroutineContext()
                val preparer = SavedOcrPreparer(renderer, budget) { _, input ->
                    assertTrue("Full render still owned during session open", renderReleased)
                    assertEquals(56L, budget.usedBytes) // 8 * (2 * 3) + 4 * 2: the bitmap-only reservation.
                    assertFalse(input.isRecycled)
                    assertEquals(0xff0080ff.toInt(), input.getPixel(0, 0))
                    borrowed = input
                    if (cancelDuringOpen) active.cancel(CancellationException("Synthetic session-open cancellation"))
                    object : SavedOcrWork.Prepared {
                        override suspend fun recognize(): OcrText = throw AssertionError("Preparation or close attempted OCR")
                        override fun close() {
                            sessionCloses++
                            assertFalse("Bitmap recycled before engine close", input.isRecycled)
                            assertEquals(56L, budget.usedBytes)
                            assertEquals(0xff0080ff.toInt(), input.getPixel(0, 0))
                        }
                    }
                }
                var retained: SavedOcrWork.Prepared? = null
                try {
                    preparer.prepare(page, config) {
                        if (cancelDuringOpen) throw AssertionError("Canceled session reached handoff")
                        retained = it
                    }
                    assertFalse("Expected cancellation after session open", cancelDuringOpen)
                    assertEquals(0, sessionCloses)
                    assertFalse(checkNotNull(borrowed).isRecycled)
                    assertEquals(56L, budget.usedBytes)
                } catch (failure: CancellationException) {
                    if (!cancelDuringOpen) throw failure
                } finally { retained?.close() }
            }
            child.join()
            assertEquals(cancelDuringOpen, child.isCancelled)
            assertTrue(renderReleased)
            assertEquals(1, sessionCloses)
            assertTrue("Private bitmap was not recycled", checkNotNull(borrowed).isRecycled)
            assertEquals(0L, budget.usedBytes)
        }
        Log.i(TAG, "ownership=render-before-session-and-session-before-recycle")
    }

    private suspend fun findAll(store: PrivateImportStore, query: String): List<SavedOcrWork.Hit> {
        // The corpus has 24 pages; a continuation at 50 would not be a complete or bounded result for this test.
        val page = store.savedOcr.find(query, null, 50)
        assertNull(page.nextAfter)
        return page.hits
    }

    private suspend fun verifySearch(store: PrivateImportStore, document: DocumentId, observed: Map<String, SavedOcrWork.Target>,
        phase: String, failures: MutableList<String>) {
        for ((key, query, pages) in expectedSearches()) {
            val expected = pages.map { observed.getValue(it).page }.toSet()
            val hits = findAll(store, query)
            val exact = hits.size == expected.size && hits.map { it.page }.toSet() == expected &&
                hits.all { it.document == document && store.savedOcr.canOpen(it) }
            if (!exact) failures.add("$key:$phase")
            Log.i(TAG, "query=$key phase=$phase matches=${hits.size} expected=${expected.size} exact=$exact")
        }
    }

    private fun verifyPersistedControls(file: File, targets: Map<String, SavedOcrWork.Target>) {
        // The first failed factory open never spent a start. TERMINAL alone also cannot distinguish a durable empty result from failure.
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val english = targets.getValue("eng")
            database.rawQuery("""SELECT COUNT(*) FROM OcrPageWork w JOIN Job j ON j.jobId=w.resultJobId
                WHERE w.pageId=? AND w.jobId=? AND w.resultJobId=w.jobId AND j.state='SUCCEEDED' AND j.attempt=1""",
                arrayOf(english.page.value, english.job.value)).use {
                assertTrue(it.moveToFirst())
                assertEquals("Preparation refusal must not spend a recognition start", 1L, it.getLong(0))
            }
            val blank = targets.getValue("blank")
            database.rawQuery("""SELECT COUNT(*) FROM OcrPageWork w JOIN Job j ON j.jobId=w.resultJobId JOIN OcrResult r ON r.pageId=w.pageId
                WHERE w.pageId=? AND w.jobId=? AND w.resultJobId=w.jobId AND w.projectionVersion=1
                AND j.state='SUCCEEDED' AND typeof(r.indexedText)='text' AND r.indexedText='' AND r.verbatimText IS NULL""",
                arrayOf(blank.page.value, blank.job.value)).use {
                assertTrue(it.moveToFirst())
                assertEquals("Blank OCR must survive reopen as a successful empty result", 1L, it.getLong(0))
            }
        }
        Log.i(TAG, "blank=durable-empty-success")
    }

    private fun grade(id: String, expected: String, raw: String): Score {
        if (raw.length > 512) return Score(-1, 0, false)
        val actual = LocalTesseractProbeTest.normalize(raw)
        val edits = LocalTesseractProbeTest.distance(expected, actual)
        val length = maxOf(expected.length, actual.length)
        val mixedRtl = id == "ara-eng" && actual.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.ARABIC } &&
            actual.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.LATIN }
        val accepted = if (id == "blank") raw.isEmpty() else edits == 0 || (mixedRtl && edits * 20 <= length)
        return Score(edits, length, accepted)
    }

    private fun isolatedContext(base: Context, root: File): Context {
        val files = newDirectory(root, "files")
        val databases = newDirectory(root, "databases")
        return object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getDatabasePath(name: String): File {
                check(name == "pristinepage.db")
                return File(databases, name)
            }
        }
    }

    private fun newDirectory(parent: File, name: String): File = File(parent, name).also { check(it.mkdir()) }

    private data class Score(val edits: Int, val length: Int, val accepted: Boolean)
    private data class SearchControl(val key: String, val query: String, val pages: Set<String>)

    private companion object {
        const val ROOT = "ocr-probe/"
        const val TAG = "PristinePageSavedOcr"
        val anchors = linkedMapOf(
            "eng" to "customer", "fra" to "montant", "spa" to "factura", "deu" to "Rechnung", "ita" to "conto",
            "por" to "compra", "nld" to "totaalbedrag", "pol" to "paragonie", "tur" to "makbuzun", "rus" to "квитанции",
            "ukr" to "квитанції", "ell" to "απόδειξη", "ara" to "الإيصال", "heb" to "הקבלה", "hin" to "रसीद",
            "chi_sim" to "付款总额", "jpn" to "領収書", "kor" to "영수증", "vie" to "biên lai", "ind" to "tanda terima",
        )
        val mixed = linkedMapOf("eng-fra" to listOf("eng", "fra"), "jpn-eng" to listOf("jpn", "eng"), "ara-eng" to listOf("ara", "eng"))
        fun orderedLanguages(id: String): List<String> = mixed[id] ?: listOf(if (id == "blank") "eng" else id)
        fun expectedSearches(): List<SearchControl> =
            anchors.map { (id, text) -> SearchControl(id, text, setOf(id) + mixed.filterValues { id in it }.keys) } +
                mixed.map { (id, packs) -> SearchControl(id, packs.joinToString(" ") { anchors.getValue(it) }, setOf(id)) } +
                SearchControl("absent", "pristinepagesyntheticabsent", emptySet())
    }
}
