package app.pristinepage.ocr

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.repository.SavedOcrWork.TextSaveOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the real closed descriptor and re-opened bytes using only newly allocated app-private files. */
@RunWith(AndroidJUnit4::class)
class OcrTextDocumentTest {
    @Test fun exactUtf8AndZeroBytesRequireASeparateReadback(): Unit = runBlocking {
        val text = "  École\r\n領収書 · إيصال  \n".encodeToByteArray()
        val empty = byteArrayOf()
        for ((bytes, digest) in listOf(
            text to "90b8051d4594a1f0bb3715981105bf56cf5854bd0602e4e483d0520e3f8025f8",
            empty to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )) {
            val file = newFile()
            var readCount = 0
            assertEquals(TextSaveOutcome.READBACK_VERIFIED, writeOcrTextDocument(bytes, digest,
                open = { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE) },
                reopen = { readCount++; file.inputStream() }))
            assertEquals(1, readCount)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun aNonemptyDestinationIsNotOverwrittenAndWrongReadbackIsNotSuccess(): Unit = runBlocking {
        val existing = newFile().also { it.writeText("preserve") }
        val empty = byteArrayOf()
        val digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertEquals(TextSaveOutcome.DESTINATION_REFUSED, writeOcrTextDocument(empty, digest,
            open = { ParcelFileDescriptor.open(existing, ParcelFileDescriptor.MODE_READ_WRITE) },
            reopen = { throw AssertionError("An unadmitted destination was reopened") }))
        assertEquals("preserve", existing.readText())
        val fresh = newFile()
        assertEquals(TextSaveOutcome.NEEDS_VERIFICATION, writeOcrTextDocument(empty, digest,
            open = { ParcelFileDescriptor.open(fresh, ParcelFileDescriptor.MODE_READ_WRITE) },
            reopen = { existing.inputStream() }))
    }

    @Test fun deniedDocumentOpenNeverReportsPossiblePartialTransfer(): Unit = runBlocking {
        assertEquals(TextSaveOutcome.PERMISSION_LOST, writeOcrTextDocument(byteArrayOf(),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            open = { throw SecurityException("Synthetic grant loss") },
            reopen = { throw AssertionError("Permission refusal must not reach readback") }))
    }

    private fun newFile(): File = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "ocr-text-control-${UUID.randomUUID()}.txt").also { check(it.createNewFile()) }
}
