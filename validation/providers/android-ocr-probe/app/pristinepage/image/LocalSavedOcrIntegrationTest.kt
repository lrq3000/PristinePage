package app.pristinepage.image

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
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
import app.pristinepage.image.ocr.PrivateOcrPackOwner
import app.pristinepage.image.ocr.SavedOcrPreparer
import app.pristinepage.image.ocr.VerifiedOcrPackDirectory
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.ClassicalRecipeAdmission
import app.pristinepage.image.render.DefaultRenderCoordinator
import app.pristinepage.image.render.GeometryEngine
import com.googlecode.leptonica.android.Pix
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
    @Test fun classifiesMissingPackNativeInitializationSinks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("app.pristinepage.debug.test", context.packageName)
        assertEquals(instrumentation.context.packageName, context.packageName)
        assertFalse(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        val token = UUID.randomUUID().toString()
        val root = newDirectory(context.filesDir, "ocr-native-sink-$token")
        val data = newDirectory(root, "tessdata")
        assertTrue(data.list()?.isEmpty() == true)
        val tag = "PristineOcrInitSink"
        val engine = TessBaseAPI()
        assertEquals("5.5.1", engine.version)
        val descriptors = ParcelFileDescriptor.createPipe()
        val reader = FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var overflow = false
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (n > 32_768 - out.size()) overflow = true
                    if (!overflow) out.write(buffer, 0, n)
                }
                assertFalse("Native stderr exceeded the synthetic capture bound", overflow)
                out.toString(Charsets.UTF_8.name())
            }
        }
        val original = ParcelFileDescriptor.fromFd(2)
        Thread(reader, "ocr-synthetic-stderr").apply { isDaemon = true; start() }
        fun canary(pipe: FileDescriptor, suffix: String) {
            val bytes = "\n$token/$suffix\n".encodeToByteArray()
            assertEquals(bytes.size, Os.write(pipe, bytes, 0, bytes.size))
        }
        try {
            // Descriptor 2 is shared in the isolated self-target test process. Never redirect stdout/stderr in the app.
            val pipe = descriptors[1].fileDescriptor
            val redirected = Os.dup2(pipe, 2)
            val destination = Os.fstat(redirected)
            val expected = Os.fstat(pipe)
            assertTrue("Descriptor 2 is not attached to the expected pipe",
                destination.st_dev == expected.st_dev && destination.st_ino == expected.st_ino)
            canary(pipe, "start")
            Log.i(tag, "$token/start")
            assertFalse("A genuinely absent English pack must be refused by the native initializer",
                engine.init(root.absolutePath, "eng", TessBaseAPI.OEM_LSTM_ONLY))
            engine.recycle()
            Log.i(tag, "$token/end")
            canary(pipe, "end")
        } finally {
            try { Os.dup2(original.fileDescriptor, 2) } finally {
                try { original.close() } finally { descriptors[1].close() }
            }
        }
        val stderr = reader.get(10, TimeUnit.SECONDS).lineSequence().toList()
        val before = "$token/start"
        val after = "$token/end"
        // Other test-process threads can write around these canaries. Require exactly one ordered pair, then count only between them.
        val first = stderr.indexOf(before)
        val last = stderr.indexOf(after)
        assertTrue("Incomplete stderr window (lines=${stderr.size}, start=$first, end=$last)",
            first >= 0 && last > first && stderr.lastIndexOf(before) == first && stderr.lastIndexOf(after) == last)
        fun countPaths(lines: List<String>) = lines.count { it.contains(token) || it.contains(".traineddata") }
        val stdio = stderr.subList(first + 1, last).filter(String::isNotBlank)
        val dump = instrumentation.uiAutomation.executeShellCommand("logcat -d -t 800 -v brief --pid=${Process.myPid()} -b main -b system -b crash")
        val logs = ParcelFileDescriptor.AutoCloseInputStream(dump).bufferedReader().useLines { lines ->
            val window = mutableListOf<String>()
            var phase = 0
            lines.forEach { line ->
                when {
                    line.startsWith("I/$tag") && line.endsWith("$token/start") -> { check(phase == 0); phase = 1 }
                    line.startsWith("I/$tag") && line.endsWith("$token/end") -> { check(phase == 1); phase = 2 }
                    phase == 1 -> window.add(line)
                }
            }
            assertEquals("No complete Android log window", 2, phase)
            window
        }
        assertTrue(data.list()?.isEmpty() == true)
        instrumentation.addResults(Bundle().apply {
            putString("ocrNativeInitSinks", "stderrLines=${stdio.size};stderrPathLines=${countPaths(stdio)};" +
                "logcatLines=${logs.size};logcatPathLines=${countPaths(logs)}")
        })
    }

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
        val manifest = JSONObject(LocalTesseractProbeTest.load(context, "${ROOT}manifest.json", 32_768).toString(Charsets.UTF_8))
        val packs = manifest.getJSONArray("packs")
        val pins = (0 until packs.length()).associate { index ->
            val row = packs.getJSONObject(index)
            val code = row.getString("code")
            assertTrue(anchors.containsKey(code))
            code to VerifiedOcrPackDirectory.Pin(OcrLanguagePack(code, row.getString("sha256")), row.getLong("bytes"))
        }
        assertEquals(anchors.keys, pins.keys)
        val nativePins = pins.values.toList()
        val english = pins.getValue("eng")
        val englishOnly = OcrLanguagePacks(listOf(english.identity))
        val firstOwner = checkNotNull(PrivateOcrPackOwner.forApplication(context, nativePins))
        val alreadyInstalled = firstOwner.acquire(englishOnly) != null
        val opened = mutableListOf<String>()
        assertTrue(firstOwner.install { code ->
            opened.add(code)
            context.assets.open("${ROOT}tessdata/$code.traineddata")
        })
        assertEquals(if (alreadyInstalled) emptyList() else anchors.keys.sorted(), opened)
        val selectedPacks = checkNotNull(PrivateOcrPackOwner.forApplication(context, nativePins.reversed()))
        assertTrue(selectedPacks.install { throw AssertionError("Reopening verified packs must not copy them again") })
        val packRoot = checkNotNull(selectedPacks.acquire(englishOnly)).toFile()
        assertEquals(context.filesDir.canonicalFile, packRoot.parentFile.parentFile)
        Log.i(TAG, "packOwner=${if (alreadyInstalled) "retained" else "fresh"} copied=${opened.size} reopenCopied=0")
        val budget = ImageAllocationBudget.forMemoryClass(checkNotNull(context.getSystemService(ActivityManager::class.java)).memoryClass)
        val runtime = TesseractInstalledOcr(selectedPacks, budget)
        assertNull(runtime.configurationFor(emptyList()))
        assertNull(runtime.configurationFor(listOf("fra", "eng", "fra")))
        assertNull(runtime.configurationFor(listOf("unknown")))
        val reversed = checkNotNull(runtime.configurationFor(listOf("fra", "eng")))
        assertEquals(OcrLayout.AUTOMATIC, reversed.layout)
        assertEquals(listOf(pins.getValue("fra").identity, pins.getValue("eng").identity), reversed.languagePacks?.ordered)
        var packLeases = 0
        fun lease(directory: File): TesseractSavedOcrSessions.PackLease = object : TesseractSavedOcrSessions.PackLease {
            init { packLeases++ }
            override val root = directory
            override fun close() { packLeases-- }
        }
        val sessions = runtime.sessions
        val missingDataDirectory = newDirectory(directory, "missing-tessdata-control")
        val missingData = TesseractSavedOcrSessions(budget) { _, _ -> lease(missingDataDirectory) }
        val refusedPixAllocation = TesseractSavedOcrSessions(budget, allocatePix = { _, _ ->
            assertEquals(1, packLeases)
            Pix(0L) // The pinned dimension constructor can contain a zero pointer. Do not deliberately exhaust the device heap.
        }) { _, _ -> lease(packRoot) }
        val failedPackAllocation = TesseractSavedOcrSessions(budget) { requested, checkpoint ->
            selectedPacks.acquire(requested) {
                checkpoint()
                throw OutOfMemoryError("Synthetic OCR pack verification allocation failure")
            }?.toFile()?.let(::lease)
        }
        var canceledPix: Pix? = null
        val canceledDuringPixCopy = TesseractSavedOcrSessions(budget, allocatePix = { width, height ->
            assertEquals(1, packLeases)
            Pix(width, height, 32).also { canceledPix = it }
        }) { _, _ -> lease(packRoot) }
        suspend fun failBeforeStart(store: PrivateImportStore, target: SavedOcrWork.Target, renderer: RenderCoordinator,
            factory: SavedOcrPreparer.SessionFactory, expected: OperationErrorCode) {
            val failed = runCatching {
                store.savedOcr.execute(target, SavedOcrPreparer(renderer, budget, factory))
            }.exceptionOrNull() as? RepositoryFailure
            assertEquals(expected, failed?.error?.code)
            assertEquals(0, packLeases)
            assertEquals(0L, budget.usedBytes)
        }
        verifyPrivateBitmapLifecycle(OcrConfiguration(TesseractSavedOcrSessions.ENGINE_ID, TesseractSavedOcrSessions.CONTRACT_VERSION, OcrLayout.SINGLE_BLOCK,
            OcrLanguagePacks(listOf(english.identity))))
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
        val exactText = linkedMapOf<String, Pair<OcrConfiguration, OcrText>>()
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
                val layout = when (id) {
                    "eng" -> OcrLayout.AUTOMATIC
                    "jpn" -> OcrLayout.SPARSE_TEXT
                    else -> OcrLayout.SINGLE_BLOCK
                }
                val configuration = checkNotNull(runtime.configurationFor(selected, layout))
                assertEquals(layout, configuration.layout)
                assertEquals("tesseract-android", configuration.engineId)
                assertEquals(1, configuration.engineVersion)
                assertEquals(selected.map { pins.getValue(it).identity }, configuration.languagePacks?.ordered)
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
                if (id == "eng-fra") assertEquals(target, store.savedOcr.reconcilePage(document, saved.pageId, reversed))
                if (id == "eng") {
                    val changedOwnedCatalog = corruptFreshCatalog(context, budget, english, packRoot, configuration)
                    failBeforeStart(store, target, renderer, changedOwnedCatalog, OperationErrorCode.PROVIDER_UNAVAILABLE)
                    val afterCorruption = checkNotNull(store.savedOcr.readStatus(document, saved.pageId))
                    assertEquals(SavedOcrWork.PagePhase.QUEUED, afterCorruption.phase)
                    assertEquals(0, afterCorruption.attemptsStarted)
                    assertNull(store.savedOcr.readText(document, saved.pageId))
                    var opened = false
                    val unavailable = SavedOcrPreparer(renderer, budget) { config, bitmap, checkpoint ->
                        assertFalse(bitmap.isRecycled)
                        assertTrue(budget.usedBytes > 0)
                        opened = true
                        sessions.open(config.copy(languagePacks = OcrLanguagePacks(listOf(OcrLanguagePack("eng", "0".repeat(64))))), bitmap, checkpoint)
                    }
                    val failure = runCatching { store.savedOcr.execute(target, unavailable) }.exceptionOrNull()
                    assertTrue(opened)
                    assertEquals(OperationErrorCode.PROVIDER_UNAVAILABLE, (failure as? RepositoryFailure)?.error?.code)
                    assertEquals(0L, budget.usedBytes)
                    assertEquals(0, packLeases)
                    failBeforeStart(store, target, renderer, missingData, OperationErrorCode.PROVIDER_UNAVAILABLE)
                    failBeforeStart(store, target, renderer, refusedPixAllocation, OperationErrorCode.RESOURCE_LIMIT)
                    val packFailure = runCatching {
                        store.savedOcr.execute(target, SavedOcrPreparer(renderer, budget, failedPackAllocation))
                    }.exceptionOrNull() as? RepositoryFailure
                    assertEquals("Pack allocation must be a structured pre-start refusal", OperationErrorCode.RESOURCE_LIMIT, packFailure?.error?.code)
                    assertEquals(false, packFailure?.error?.retryable)
                    assertNull(packFailure?.cause)
                    assertEquals(0, packLeases)
                    assertEquals(0L, budget.usedBytes)
                    val pendingAfterAllocation = checkNotNull(store.savedOcr.readStatus(document, saved.pageId))
                    assertEquals(SavedOcrWork.PagePhase.QUEUED, pendingAfterAllocation.phase)
                    assertEquals(0, pendingAfterAllocation.attemptsStarted)
                    assertNull(pendingAfterAllocation.lastError)
                    var borrowed: Bitmap? = null
                    var openCheckpointsAfterAllocation = 0
                    var checkpointObservedCancellation = false
                    var sessionReturned = false
                    coroutineScope {
                        val child = launch {
                            val active = currentCoroutineContext()
                            store.savedOcr.execute(target, SavedOcrPreparer(renderer, budget) { config, bitmap, checkpoint ->
                                borrowed = bitmap
                                assertTrue(bitmap.height >= 2)
                                canceledDuringPixCopy.open(config, bitmap) {
                                    // Row checkpoints precede each copy: the second one follows a complete first row.
                                    if (canceledPix != null && ++openCheckpointsAfterAllocation == 2) active.cancel()
                                    try { checkpoint() } catch (canceled: CancellationException) {
                                        checkpointObservedCancellation = true
                                        throw canceled
                                    }
                                }.also { sessionReturned = true }
                            })
                        }
                        child.join()
                        assertTrue(child.isCancelled)
                    }
                    assertTrue("The preparer's checkpoint must propagate real cancellation", checkpointObservedCancellation)
                    assertFalse("Canceled preparation returned a native session", sessionReturned)
                    assertTrue(checkNotNull(borrowed).isRecycled)
                    val pix = checkNotNull(canceledPix)
                    assertTrue(runCatching { pix.nativePix }.exceptionOrNull() is IllegalStateException)
                    assertEquals(0, packLeases)
                    assertEquals(0L, budget.usedBytes)
                    assertEquals(target, store.savedOcr.reconcilePage(document, saved.pageId, null))
                }
                var score: Score? = null
                val prepare = SavedOcrPreparer(renderer, budget) { config, bitmap, checkpoint ->
                    assertEquals(configuration, config)
                    val native = sessions.open(config, bitmap, checkpoint)
                    object : SavedOcrWork.Prepared {
                        override suspend fun recognize(): OcrText = native.recognize().also { text ->
                            score = grade(id, row.getString("expected"), text.value)
                            exactText[id] = config to text
                        }
                        override fun close() = native.close()
                    }
                }
                val outcome = store.savedOcr.execute(target) { page, config, handoff ->
                    assertEquals(saved.sourceId, page.sourceId)
                    prepare.prepare(page, config, handoff)
                }
                assertEquals("Fenced publication: $id", SavedOcrWork.Outcome.PUBLISHED, outcome)
                assertEquals(0L, budget.usedBytes)
                assertEquals(0, packLeases)
                val result = checkNotNull(score)
                val readback = checkNotNull(store.savedOcr.readText(document, saved.pageId))
                assertEquals(exactText.getValue(id).second.value, readback.text.value)
                assertEquals(configuration, readback.configuration)
                assertFalse(readback.previousGeneration)
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
                val restoredText = checkNotNull(store.savedOcr.readText(document, target.page))
                assertEquals(exactText.getValue(id).first, restoredText.configuration)
                assertEquals(exactText.getValue(id).second.value, restoredText.text.value)
                assertFalse(restoredText.previousGeneration)
            }
        }
        verifyPersistedControls(databaseFile, observed)
        assertTrue("Saved OCR controls not accepted: ${failures.joinToString()}", failures.isEmpty())
    }

    private suspend fun corruptFreshCatalog(
        context: Context,
        budget: ImageAllocationBudget,
        english: VerifiedOcrPackDirectory.Pin,
        retainedRoot: File,
        savedConfiguration: OcrConfiguration,
    ): SavedOcrPreparer.SessionFactory {
        // The synthetic bytes are never selected by Tesseract. A per-invocation pin ensures this intentional fault cannot address an earlier catalog.
        val control = UUID.randomUUID().toString().encodeToByteArray()
        val digest = LocalTesseractProbeTest.hex(MessageDigest.getInstance("SHA-256").digest(control))
        val decoy = VerifiedOcrPackDirectory.Pin(OcrLanguagePack("fixture_decoy", digest), control.size.toLong())
        val owner = checkNotNull(PrivateOcrPackOwner.forApplication(context, listOf(english, decoy)))
        val ordered = OcrLanguagePacks(listOf(english.identity))
        assertNull(owner.acquire(ordered))
        val copied = mutableSetOf<String>()
        assertTrue(owner.install { code ->
            assertTrue(copied.add(code))
            when (code) {
                "eng" -> context.assets.open("${ROOT}tessdata/eng.traineddata")
                decoy.identity.code -> control.inputStream()
                else -> throw AssertionError("Unexpected OCR fixture pack")
            }
        })
        assertEquals(setOf("eng", decoy.identity.code), copied)
        val freshRoot = checkNotNull(owner.acquire(ordered)).toFile()
        assertEquals(retainedRoot.parentFile, freshRoot.parentFile)
        assertNotEquals(retainedRoot, freshRoot)
        val runtime = TesseractInstalledOcr(owner, budget)
        val admitted = checkNotNull(runtime.configurationFor(listOf("eng")))
        assertEquals(savedConfiguration, admitted)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            runtime.sessions.open(admitted, bitmap) {}.use { /* Positive open without a recognition start. */ }
        } finally { bitmap.recycle() }
        assertEquals(0L, budget.usedBytes)
        val leaf = File(freshRoot, "tessdata/fixture_decoy.traineddata")
        assertEquals(leaf, leaf.canonicalFile)
        assertEquals(control.size.toLong(), leaf.length())
        assertArrayEquals(control, leaf.readBytes())
        FileChannel.open(leaf.toPath(), setOf(WRITE, NOFOLLOW_LINKS)).use { file ->
            assertEquals(1, file.write(ByteBuffer.wrap(byteArrayOf((control[0].toInt() xor 1).toByte()))))
            file.force(true)
        }
        assertEquals(control.size.toLong(), leaf.length())
        assertNull(runtime.configurationFor(listOf("eng")))
        // Execute the normal saved-raster preparation with its previously admitted English identity, not the defaults resolver.
        return runtime.sessions
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
                val preparer = SavedOcrPreparer(renderer, budget) { _, input, checkpoint ->
                    assertTrue("Full render still owned during session open", renderReleased)
                    assertEquals(56L, budget.usedBytes) // 8 * (2 * 3) + 4 * 2: the bitmap-only reservation.
                    assertFalse(input.isRecycled)
                    assertEquals(0xff0080ff.toInt(), input.getPixel(0, 0))
                    checkpoint()
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
