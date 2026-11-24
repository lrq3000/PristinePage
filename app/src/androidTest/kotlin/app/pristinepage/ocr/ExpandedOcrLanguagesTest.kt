package app.pristinepage.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.MainActivity
import app.pristinepage.PristinePageApplication
import app.pristinepage.core.ocr.OcrLayout
import app.pristinepage.design.AtelierPreferences
import app.pristinepage.design.AtelierSettingsScreen
import app.pristinepage.design.SettingsPage
import app.pristinepage.image.BundledOcrRuntime
import app.pristinepage.image.decode.ImageAllocationBudget
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpandedOcrLanguagesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectsARecentlyBundledLanguageAndRecognizesWithItsActualPack() = runBlocking<Unit> {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PristinePageApplication
        // The ordinary Activity can initialize the same immutable asset catalog during startup.
        compose.waitUntil(45_000) { app.ocrRuntimeReady.value }
        val name = "test-ocr-expanded-${UUID.randomUUID()}"
        val preferences = AtelierPreferences(app, name)
        compose.runOnUiThread {
            compose.activity.setContent { MaterialTheme { AtelierSettingsScreen(SettingsPage.OCR_LANGUAGES, preferences, true) {} } }
        }
        compose.onNodeWithTag("settings-ocr-language-search").performTextReplacement("Swedish")
        compose.onNodeWithTag("settings-ocr-swe").performScrollTo().performClick().assertIsOn()
        val ordered = AtelierPreferences(app, name).settings.value.ocrLanguages.map { it.packCode }
        assertEquals(listOf("eng", "fra", "swe"), ordered)
        val budget = ImageAllocationBudget(32L * 1024 * 1024)
        val start = SystemClock.elapsedRealtime()
        val runtime = checkNotNull(BundledOcrRuntime.open(app, budget))
        val opened = SystemClock.elapsedRealtime()
        assertEquals(ordered, checkNotNull(runtime.configurationFor(ordered, OcrLayout.AUTOMATIC)).languagePacks?.ordered?.map { it.code })
        val config = checkNotNull(runtime.configurationFor(listOf("swe"), OcrLayout.SINGLE_BLOCK))
        val bitmap = Bitmap.createBitmap(640, 160, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText("SVENSKT KVITTO 4862", 30f, 99f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 44f
                typeface = Typeface.DEFAULT_BOLD
            })
            val result = runtime.sessions.open(config, bitmap) {}.use { it.recognize().value.uppercase(Locale.ROOT) }
            assertTrue(result.contains("KVITTO") && result.contains("4862"))
        } finally { bitmap.recycle() }
        assertEquals(0L, budget.usedBytes)
        InstrumentationRegistry.getInstrumentation().addResults(android.os.Bundle().apply {
            putLong("expandedOcrOpenMillis", opened - start)
            putLong("expandedOcrTotalMillis", SystemClock.elapsedRealtime() - start)
        })
    }
}
