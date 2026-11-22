package app.pristinepage.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.core.ocr.OcrError
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.IngestResult
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.library.ApplicationLibraryActions
import app.pristinepage.library.LibraryPhotoResult
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real app database, default WorkManager and Tesseract. Only an observed native call allows this instrumentation process to end itself. */
@RunWith(AndroidJUnit4::class)
class LocalOcrWorkDeathTest {
    @Test
    fun interruptRealNativeRecognition(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as PristinePageApplication
        val arguments = InstrumentationRegistry.getArguments()
        val title = arguments.getString("recoveryAlbum")
        val ordinal = arguments.getString("recoveryOrdinal")?.toIntOrNull()
        assumeTrue("Requires an explicit synthetic recovery attempt on the private candidate",
            app.hasBundledOcr && title != null && ordinal != null && ordinal in 1..3)
        val name = checkNotNull(title)
        check(name.matches(Regex("OCR recovery [a-f0-9]{12}")))
        val graph = app.services.imports()
        val work = graph.store.savedOcr
        val manager = WorkManager.getInstance(context)
        val document = if (ordinal == 1) createExistingPage(app, name) else
            graph.store.catalog.list(null, 50).single { it.title == name }.documentId
        val savedPage = graph.store.catalog.load(document).pages.single()
        val page = savedPage.pageId
        if (ordinal == 1) assertNull(work.readStatus(document, page))
        else {
            val prior = checkNotNull(work.readStatus(document, page))
            assertEquals(SavedOcrWork.PagePhase.RUNNING, prior.phase)
            assertEquals(checkNotNull(ordinal) - 1, prior.attemptsStarted)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(135_000) {
                var sample = 0
                while (true) {
                    val status = work.readStatus(document, page)
                    assertTrue("Recognition completed or became terminal before the intended interruption",
                        status?.phase !in setOf(SavedOcrWork.PagePhase.READY, SavedOcrWork.PagePhase.FAILED))
                    if (status?.phase == SavedOcrWork.PagePhase.RUNNING && status.attemptsStarted == ordinal && nativeRecognizerOnStack()) {
                        // Do not write scan text, original paths, or any stack content into instrumentation evidence.
                        instrumentation.sendStatus(64, Bundle().apply {
                            putString("ocrRecoveryCheckpoint", "$ordinal")
                            putString("ocrRecoveryPid", "${Process.myPid()}")
                        })
                        Process.killProcess(Process.myPid())
                        error("The exact instrumentation process did not stop")
                    }
                    if (ordinal == 1 && sample++ % 64 == 0) {
                        val target = work.observePendingTarget(document, page, savedPage.recipeRevision).first()
                        if (target != null) {
                            val wakes = withContext(Dispatchers.IO) {
                                manager.getWorkInfosForUniqueWork(OcrPageDelivery.uniqueName(target)).get(5, TimeUnit.SECONDS)
                            }
                            assertFalse("The exact test wake failed before the intended native interruption",
                                wakes.isNotEmpty() && wakes.all { it.state == WorkInfo.State.FAILED })
                        }
                    }
                    delay(16)
                }
            }
        }
    }

    @Test
    fun thirdInterruptionIsTerminalAfterOrdinaryLaunch(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as PristinePageApplication
        val title = InstrumentationRegistry.getArguments().getString("recoveryAlbum")
        assumeTrue("Requires the exact three already interrupted starts", app.hasBundledOcr && title != null)
        val graph = app.services.imports()
        val document = graph.store.catalog.list(null, 50).single { it.title == title }.documentId
        val page = graph.store.catalog.load(document).pages.single().pageId
        val work = graph.store.savedOcr
        val interrupted = checkNotNull(work.readStatus(document, page))
        assertEquals(SavedOcrWork.PagePhase.RUNNING, interrupted.phase)
        assertEquals(3, interrupted.attemptsStarted)
        ActivityScenario.launch(MainActivity::class.java).use {
            val failed = withTimeout(135_000) {
                var status = checkNotNull(work.readStatus(document, page))
                while (status.phase != SavedOcrWork.PagePhase.FAILED) {
                    assertEquals(3, status.attemptsStarted)
                    delay(150)
                    status = checkNotNull(work.readStatus(document, page))
                }
                status
            }
            assertEquals(3, failed.attemptsStarted)
            assertEquals(OcrError.INTERRUPTED, failed.lastError)
            assertNull(work.readText(document, page))
            assertFalse(work.find("MAGNOLIA", null, 20).hits.any { it.document == document })
        }
    }

    private fun nativeRecognizerOnStack(): Boolean = Thread.getAllStackTraces().values.any { stack ->
        stack.any { it.isNativeMethod && it.className == "com.googlecode.tesseract.android.TessBaseAPI" &&
            it.methodName == "nativeGetUTF8Text" }
    }

    private suspend fun createExistingPage(app: PristinePageApplication, title: String): DocumentId {
        val graph = app.services.imports()
        val document = graph.store.documents.createDocument(title)
        val directory = File(app.cacheDir, "editor-journey-fixtures")
        check(directory.isDirectory || directory.mkdirs())
        val id = UUID.randomUUID().toString()
        val fixture = File(directory, "$id.png")
        check(fixture.createNewFile())
        val bitmap = Bitmap.createBitmap(1024, 1400, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 18f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            for (line in 1..52) {
                canvas.drawText("MAGNOLIA  $line  The quick brown fox jumps over the lazy dog.  Receipt reference 908176.", 24f, 26f * line, paint)
            }
            fixture.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val actions = ApplicationLibraryActions(app, app.services::imports)
        val request = actions.begin(graph.store.catalog.load(document).document)
        check(actions.deliver(request, LibraryPhotoResult.Selected(
            listOf("content://${app.packageName}.editor-fixtures/$id"), 0,
        )).single() is IngestResult.Committed)
        return document
    }
}
