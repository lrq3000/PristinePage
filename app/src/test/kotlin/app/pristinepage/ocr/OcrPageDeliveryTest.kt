package app.pristinepage.ocr

import androidx.work.Data
import androidx.work.ListenableWorker
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.repository.SavedOcrWork
import app.pristinepage.data.repository.SavedOcrWork.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OcrPageDeliveryTest {
    @Test fun uniqueWorkDoesNotTreatASuccessorJobOnTheSamePageAsADuplicate() {
        val wake = ActivationBoundOcrWake(activation, target)
        assertEquals(wake.uniqueName(), wake.copy(target = target.copy(page = otherPage)).uniqueName())
        assertNotEquals(wake.uniqueName(), wake.copy(target = target.copy(job = replacementJob)).uniqueName())
    }

    @Test fun malformedDeliveryNeverOpensTheStore() = runBlocking {
        val valid = input
        val invalid = listOf(
            Data.EMPTY,
            Data.Builder().putString("page", target.page.value).build(),
            Data.Builder().putString("page", "not-a-page").putString("job", target.job.value).build(),
            Data.Builder().putString("page", "AAAAAAAA-AAAA-4AAA-8AAA-000000000001").putString("job", target.job.value).build(),
            Data.Builder().putString("page", target.page.value).putLong("job", 1).build(),
            Data.Builder().putAll(valid).putString("text", "synthetic query").build(),
        )
        for (input in invalid) assertEquals(OcrPageDelivery.Result.REJECT, deliver(input) { error("Invalid wake reached the host") })
    }

    @Test fun originalJobOwnsAttemptAccountingAcrossRepeatedDeliveries() = runBlocking {
        val cases = listOf(
            Outcome.WAITING_FOR_OWNER to OcrPageDelivery.Result.RETRY,
            Outcome.BUSY to OcrPageDelivery.Result.RETRY,
            Outcome.FAILURE_RECORDED to OcrPageDelivery.Result.RETRY,
            Outcome.PUBLISHED to OcrPageDelivery.Result.DONE,
            Outcome.OBSOLETE to OcrPageDelivery.Result.DONE,
            Outcome.TERMINAL to OcrPageDelivery.Result.DONE,
        )
        for ((outcome, expected) in cases) {
            assertEquals(expected, deliver { resolved -> assertEquals(target, resolved); outcome })
        }
    }

    @Test fun retryableStorageCanRedeliverButUnavailableRuntimeAndPermanentRefusalsStopTheWake() = runBlocking {
        assertEquals(OcrPageDelivery.Result.DONE, deliver { null })
        val retries = listOf(
            Triple(OperationErrorCode.STORAGE_FAILED, true, OcrPageDelivery.Result.RETRY),
            Triple(OperationErrorCode.RESOURCE_LIMIT, true, OcrPageDelivery.Result.RETRY),
            Triple(OperationErrorCode.RESOURCE_LIMIT, false, OcrPageDelivery.Result.REJECT),
            Triple(OperationErrorCode.INTEGRITY_FAILED, false, OcrPageDelivery.Result.REJECT),
            Triple(OperationErrorCode.PROCESSING_FAILED, false, OcrPageDelivery.Result.REJECT),
            Triple(OperationErrorCode.PROVIDER_UNAVAILABLE, true, OcrPageDelivery.Result.DONE),
        )
        for ((code, retryable, expected) in retries) {
            assertEquals(expected, deliver { throw RepositoryFailure(OperationError(code, retryable, "ocr_fixture_refusal", null)) })
        }
    }

    @Test fun retryableRecognitionFaultRedeliversSameJobForRoomRecovery() = runBlocking {
        var deliveries = 0
        suspend fun delivery() = deliver { resolved ->
            assertEquals(target, resolved)
            when (deliveries++) {
                // The real facade wraps an unexpected recognition exception this way and leaves the started job RUNNING.
                0 -> throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, true, "ocr_recognition_fault", resolved.page.value))
                1 -> Outcome.WAITING_FOR_OWNER
                2 -> Outcome.PUBLISHED
                else -> throw AssertionError("Unexpected transport invocation")
            }
        }
        assertEquals(ListenableWorker.Result.retry(), delivery().asWorkerResult())
        assertEquals(ListenableWorker.Result.retry(), delivery().asWorkerResult())
        assertEquals(ListenableWorker.Result.success(), delivery().asWorkerResult())
        assertEquals(3, deliveries)
    }

    @Test fun coroutineCancellationIsNotAWorkResult() = runBlocking {
        val cancellations = listOf(CancellationException("Fixture cancellation"),
            RepositoryFailure(OperationError(OperationErrorCode.CANCELED, false, "ocr_canceled", null)))
        for (failure in cancellations) {
            val result = runCatching { deliver { throw failure } }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun unexpectedAdapterProblemsReturnNoPrivateWorkManagerOutput() = runBlocking {
        val decision = deliver { error("Synthetic private path and OCR output") }
        assertEquals(ListenableWorker.Result.failure(), decision.asWorkerResult())
        assertEquals(ListenableWorker.Result.success(), OcrPageDelivery.Result.DONE.asWorkerResult())
        assertEquals(ListenableWorker.Result.retry(), OcrPageDelivery.Result.RETRY.asWorkerResult())
    }

    private suspend fun deliver(bytes: Data = input, execute: suspend (SavedOcrWork.Target) -> Outcome?) =
        OcrPageDelivery.deliverBound(bytes) { wake ->
            assertEquals(activation, wake.activation)
            execute(wake.target)
        }

    private companion object {
        val target = SavedOcrWork.Target(PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001"), JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002"))
        val otherPage = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
        val replacementJob = JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000004")
        val activation = LibraryActivation("aaaaaaaa-aaaa-4aaa-8aaa-000000000005", 1)
        val input = ActivationBoundOcrWake(activation, target).encode()
    }
}
