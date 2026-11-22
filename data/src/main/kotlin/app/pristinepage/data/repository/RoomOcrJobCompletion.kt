package app.pristinepage.data.repository

import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.db.OcrResultRow
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Internal shared transaction participant. The owning store must supply same-database guarded reads and writes; none of these ports can perform inference, file IO, or open its own transaction. */
internal class RoomOcrJobCompletion(
    private val transactions: JobLeaseTransactions,
    private val ownerState: LeaseOwnerLiveness,
    private val currentBinding: (JobId) -> OcrJobInput?,
    private val boundedJob: (JobId, Int) -> JobRow?,
    private val persist: (OcrResultRow) -> Unit,
    private val storedResultMatches: (OcrResultRow) -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun complete(binding: OcrJobInput, lease: JobLease, text: OcrText) = withContext(dispatcher) {
        val context = coroutineContext
        context.ensureActive()
        // Prepare the bounded normalized text before taking the Room writer lock.
        val result = OcrResultRow.fromRecognition(binding.input, binding.configuration, text)
        val expectedBytes = binding.encode().size
        val jobId = lease.jobId
        transactions.completeGuarded(
            lease,
            readAdmitted = {
                requireCurrent(binding, jobId)
                val job = boundedJob(jobId, expectedBytes) ?: lost(jobId)
                binding.verify(jobId, job)
                job
            },
            live = { ownerState.state(lease.owner) == LeaseOwnerState.ALIVE },
            active = { context.ensureActive() },
            commitMetadata = { persist(result) },
            verifyMetadata = {
                requireCurrent(binding, jobId)
                // A successful UPDATE of the job alone must not hide a failed or no-op result writer.
                if (!storedResultMatches(result)) throw RepositoryFailure(
                    OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_result_write_unverified", jobId.value),
                )
            },
        )
    }

    private fun requireCurrent(expected: OcrJobInput, jobId: JobId) {
        if (currentBinding(jobId) != expected) lost(jobId)
    }

    private fun lost(jobId: JobId): Nothing = throw RepositoryFailure(
        OperationError(OperationErrorCode.REVISION_CONFLICT, false, "ocr_job_no_longer_current", jobId.value),
    )
}
