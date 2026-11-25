package app.pristinepage.ocr

import androidx.work.Data
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ActivationBoundOcrWakeTest {
    @Test fun equalPageAndJobInTheSameLibraryDoNotShareAWakeAcrossActivations() {
        val old = ActivationBoundOcrWake(first, target)
        val reopened = ActivationBoundOcrWake(second, target)
        assertEquals(mapOf("library" to library, "activation" to 1L, "page" to target.page.value, "job" to target.job.value),
            old.encode().keyValueMap)
        assertEquals(old, ActivationBoundOcrWake.decode(old.encode()))
        assertEquals(reopened, ActivationBoundOcrWake.decode(reopened.encode()))
        assertNotEquals(old.uniqueName(), reopened.uniqueName())
        assertNotEquals(old.uniqueName(), old.copy(activation = LibraryActivation(page, 1)).uniqueName())
    }

    @Test fun theFormerTwoFieldWakeCannotAcquireAnyLibraryActivation() = runBlocking {
        val former = Data.Builder().putString("page", target.page.value).putString("job", target.job.value).build()
        assertNull(ActivationBoundOcrWake.decode(former))
        assertEquals(OcrPageDelivery.Result.DONE, OcrPageDelivery.deliverBound(former) { error("Legacy work cannot reach the host") })
    }

    @Test fun wrongTypedOrNonPositiveActivationIsRejectedBeforeResolvingAnything() = runBlocking {
        val encoded = ActivationBoundOcrWake(first, target).encode()
        val malformed = listOf(
            Data.Builder().putAll(encoded).putLong("activation", 0L).build(),
            Data.Builder().putAll(encoded).putInt("activation", 1).build(),
            Data.Builder().putAll(encoded).putString("library", library.uppercase()).build(),
            Data.Builder().putAll(encoded).putBoolean("extra", true).build(),
        )
        for (input in malformed) {
            assertNull(ActivationBoundOcrWake.decode(input))
            assertEquals(OcrPageDelivery.Result.REJECT, OcrPageDelivery.deliverBound(input) { error("Malformed work cannot reach the host") })
        }
    }

    private companion object {
        const val library = "aaaaaaaa-aaaa-4aaa-8aaa-000000000011"
        const val page = "aaaaaaaa-aaaa-4aaa-8aaa-000000000021"
        val target = SavedOcrWork.Target(PageId(page), JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000031"))
        val first = LibraryActivation(library, 1)
        val second = LibraryActivation(library, 2)
    }
}
