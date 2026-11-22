package app.pristinepage.image

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrLanguagePack
import app.pristinepage.core.ocr.OcrLanguagePacks
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.RenderRequest
import app.pristinepage.core.pipeline.RenderStatus
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.PrivateImportStore
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SourceInput
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.image.preview.SrgbPreviewBitmap
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
                var score: Score? = null
                val outcome = store.savedOcr.execute(target) { page, config, handoff ->
                    assertEquals(saved.sourceId, page.sourceId)
                    assertEquals(configuration, config)
                    val output = OutputSpec(GeometrySizing.target(page.recipe.geometry), RenderPurpose.EXPORT)
                    val request = RenderRequest(page.sourceId.value, page.recipe.geometry.source.sourcePixelHash, page.pageId.value,
                        page.recipeRevision, page.recipe, output, 0, null, FallbackPolicy.FAIL)
                    renderer.render(request).use { rendered ->
                        assertTrue("Saved raster unavailable: $id (${rendered.status})", rendered.status == RenderStatus.OK)
                        assertEquals(request, rendered.request)
                        val active = currentCoroutineContext()
                        val bitmap = SrgbPreviewBitmap.convert(checkNotNull(rendered.image), budget) { active.ensureActive() }
                        var engine: TessBaseAPI? = null
                        var transferred = false
                        try {
                            val local = TessBaseAPI().also { engine = it }
                            assertEquals("5.5.1", local.version)
                            val effective = checkNotNull(config.languagePacks).ordered.joinToString("+") { it.code }
                            assertTrue(local.init(packRoot.absolutePath, effective, TessBaseAPI.OEM_LSTM_ONLY))
                            assertTrue(local.setVariable("user_defined_dpi", "300"))
                            local.setPageSegMode(if (config.layout == OcrLayout.SPARSE_TEXT) TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                                else TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                            local.setImage(bitmap.bitmap)
                            handoff.retain(object : SavedOcrWork.Prepared {
                                override suspend fun recognize(): OcrText {
                                    val raw = checkNotNull(local.getUTF8Text())
                                    val text = OcrText(raw)
                                    score = grade(id, row.getString("expected"), raw)
                                    return text
                                }
                                override fun close() {
                                    try { local.recycle() } finally { bitmap.discardUndelivered() }
                                }
                            })
                            transferred = true
                        } finally {
                            if (!transferred) try { engine?.recycle() } finally { bitmap.discardUndelivered() }
                        }
                    }
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
        verifyPersistedBlank(databaseFile, observed.getValue("blank"))
        assertTrue("Saved OCR controls not accepted: ${failures.joinToString()}", failures.isEmpty())
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

    private fun verifyPersistedBlank(file: File, blank: SavedOcrWork.Target) {
        // TERMINAL alone cannot distinguish a retained successful empty string from a failed or canceled job. Read no OCR text.
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
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
