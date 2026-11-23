package app.pristinepage.ocr

import androidx.work.Data
import androidx.work.ListenableWorker
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
    @Test fun inputContainsOnlyTwoOpaqueIdsAndUsesTheDurableJobForUniqueness() {
        val input = OcrPageDelivery.encode(target)
        assertEquals(mapOf("page" to target.page.value, "job" to target.job.value), input.keyValueMap)
        assertEquals(target, OcrPageDelivery.decode(input))
        assertEquals("saved-ocr-page-v1/${target.job.value}", OcrPageDelivery.uniqueName(target))
        assertEquals(OcrPageDelivery.uniqueName(target), OcrPageDelivery.uniqueName(target.copy(page = otherPage)))
        assertNotEquals(OcrPageDelivery.uniqueName(target), OcrPageDelivery.uniqueName(target.copy(job = replacementJob)))
    }

    @Test fun malformedDeliveryNeverOpensTheStore() = runBlocking {
        val valid = OcrPageDelivery.encode(target)
        val invalid = listOf(
            Data.EMPTY,
            Data.Builder().putString("page", target.page.value).build(),
            Data.Builder().putString("page", "not-a-page").putString("job", target.job.value).build(),
            Data.Builder().putString("page", "AAAAAAAA-AAAA-4AAA-8AAA-000000000001").putString("job", target.job.value).build(),
            Data.Builder().putString("page", target.page.value).putLong("job", 1).build(),
            Data.Builder().putAll(valid).putString("text", "synthetic query").build(),
        )
        val delivery = OcrPageDelivery { throw AssertionError("Invalid work hints cannot open the store") }
        for (input in invalid) assertEquals(OcrPageDelivery.Result.REJECT, delivery.deliver(input))
    }

    @Test fun originalJobOwnsAttemptAccountingAcrossRepeatedDeliveries() = runBlocking {
        val input = OcrPageDelivery.encode(target)
        val cases = listOf(
            Outcome.WAITING_FOR_OWNER to OcrPageDelivery.Result.RETRY,
            Outcome.BUSY to OcrPageDelivery.Result.RETRY,
            Outcome.FAILURE_RECORDED to OcrPageDelivery.Result.RETRY,
            Outcome.PUBLISHED to OcrPageDelivery.Result.DONE,
            Outcome.OBSOLETE to OcrPageDelivery.Result.DONE,
            Outcome.TERMINAL to OcrPageDelivery.Result.DONE,
        )
        for ((outcome, expected) in cases) {
            val delivery = OcrPageDelivery { resolved -> assertEquals(target, resolved); outcome }
            assertEquals(expected, delivery.deliver(input))
        }
    }

    @Test fun retryableStorageCanRedeliverButUnavailableRuntimeAndPermanentRefusalsStopTheWake() = runBlocking {
        val input = OcrPageDelivery.encode(target)
        assertEquals(OcrPageDelivery.Result.DONE, OcrPageDelivery { null }.deliver(input))
        val retries = listOf(
            Triple(OperationErrorCode.STORAGE_FAILED, true, OcrPageDelivery.Result.RETRY),
            Triple(OperationErrorCode.RESOURCE_LIMIT, true, OcrPageDelivery.Result.RETRY),
            Triple(OperationErrorCode.RESOURCE_LIMIT, false, OcrPageDelivery.Result.REJECT),
            Triple(OperationErrorCode.INTEGRITY_FAILED, false, OcrPageDelivery.Result.REJECT),
            Triple(OperationErrorCode.PROCESSING_FAILED, false, OcrPageDelivery.Result.REJECT),
            Triple(OperationErrorCode.PROVIDER_UNAVAILABLE, true, OcrPageDelivery.Result.DONE),
        )
        for ((code, retryable, expected) in retries) {
            val delivery = OcrPageDelivery { throw RepositoryFailure(OperationError(code, retryable, "ocr_fixture_refusal", null)) }
            assertEquals(expected, delivery.deliver(input))
        }
    }

    @Test fun retryableRecognitionFaultRedeliversSameJobForRoomRecovery() = runBlocking {
        var deliveries = 0
        val delivery = OcrPageDelivery { resolved ->
            assertEquals(target, resolved)
            when (deliveries++) {
                // The real facade wraps an unexpected recognition exception this way and leaves the started job RUNNING.
                0 -> throw RepositoryFailure(OperationError(OperationErrorCode.PROCESSING_FAILED, true, "ocr_recognition_fault", resolved.page.value))
                1 -> Outcome.WAITING_FOR_OWNER
                2 -> Outcome.PUBLISHED
                else -> throw AssertionError("Unexpected transport invocation")
            }
        }
        val input = OcrPageDelivery.encode(target)
        assertEquals(ListenableWorker.Result.retry(), delivery.deliver(input).asWorkerResult())
        assertEquals(ListenableWorker.Result.retry(), delivery.deliver(input).asWorkerResult())
        assertEquals(ListenableWorker.Result.success(), delivery.deliver(input).asWorkerResult())
        assertEquals(3, deliveries)
    }

    @Test fun coroutineCancellationIsNotAWorkResult() = runBlocking {
        val cancellations = listOf(CancellationException("Fixture cancellation"),
            RepositoryFailure(OperationError(OperationErrorCode.CANCELED, false, "ocr_canceled", null)))
        for (failure in cancellations) {
            val delivery = OcrPageDelivery { throw failure }
            val result = runCatching { delivery.deliver(OcrPageDelivery.encode(target)) }
            assertTrue(result.exceptionOrNull() is CancellationException)
        }
    }

    @Test fun unexpectedAdapterProblemsReturnNoPrivateWorkManagerOutput() = runBlocking {
        val delivery = OcrPageDelivery { error("Synthetic private path and OCR output") }
        val decision = delivery.deliver(OcrPageDelivery.encode(target))
        assertEquals(ListenableWorker.Result.failure(), decision.asWorkerResult())
        assertEquals(ListenableWorker.Result.success(), OcrPageDelivery.Result.DONE.asWorkerResult())
        assertEquals(ListenableWorker.Result.retry(), OcrPageDelivery.Result.RETRY.asWorkerResult())
    }

    private companion object {
        val target = SavedOcrWork.Target(PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001"), JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000002"))
        val otherPage = PageId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
        val replacementJob = JobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000004")
    }
}
