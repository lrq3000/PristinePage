package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.RenderResult
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.RenderedPageRow
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.RoomJobLeases.FrozenRenderAcquisition.Acquired
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Same-database writes plus scalar collision and exact readback checks; never materialize a competing RenderedPage. */
internal interface RenderCompletionQueries {
    fun renderCompletionOccupied(renderId: String, jobId: String, cacheKey: String, relativePath: String): Boolean
    fun insertReadyRender(row: RenderedPageRow)
    fun readyRenderMatches(row: RenderedPageRow): Boolean
    fun commitMovedRender(previous: FileTransactionRow, updatedAt: Long): Int
}

/** One ordinary FAIL/EXPORT attempt per instance. Retain this instance and the exclusive stable store/transaction/target and live lease across retries; abandon the instance if that ownership ends. The caller retains the whole result during each call. There is no terminal replay or app-facing service here. */
internal class RoomRenderCompletion(
    executor: RoomRenderFileExecution,
    rows: RenderCompletionQueries,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(database: PristineDatabase, liveness: LeaseOwnerLiveness, files: DerivedFiles) :
        this(RoomRenderFileExecution(database, liveness, files), database.exportSnapshots().renderCompletionQueries())

    private val attempt = executor.completionAttempt(rows)

    /** Readback must verify the complete lossless container from the executor's exact read-only File borrow and hash its decoded RGB, not echo the expected digest. The caller owns codec/runtime work and its memory admission. MOVED retry re-verifies bytes and never writes them again. */
    suspend fun complete(
        proof: RoomFrozenRenderReads.Verified, lease: JobLease, intent: RenderFileIntent, result: RenderResult,
        maxBytes: Long, writer: (OutputStream) -> Unit, readback: (File, () -> Unit) -> String?,
    ): RenderId = complete(RenderAttemptAuthority.Legacy(proof, lease), intent, result, maxBytes, writer, readback)

    suspend fun completeForProducer(
        acquired: Acquired, intent: RenderFileIntent, result: RenderResult,
        maxBytes: Long, writer: (OutputStream) -> Unit, readback: (File, () -> Unit) -> String?,
    ): RenderId = complete(acquired, intent, result, maxBytes, writer, readback)

    private suspend fun complete(
        authority: RenderAttemptAuthority, intent: RenderFileIntent, result: RenderResult,
        maxBytes: Long, writer: (OutputStream) -> Unit, readback: (File, () -> Unit) -> String?,
    ): RenderId = withContext(dispatcher) {
        coroutineContext.ensureActive()
        val lease = authority.lease
        val execution = try {
            OrdinaryRenderExecution.capture(authority, result) { coroutineContext.ensureActive() }
        } catch (canceled: CancellationException) {
            throw canceled
        } catch (_: IllegalArgumentException) {
            invalid(lease)
        } catch (_: IllegalStateException) {
            invalid(lease)
        }
        attempt.complete(authority, intent, execution, maxBytes, writer, readback)
    }

    private fun invalid(lease: JobLease): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "render_execution_invalid", lease.jobId.value))
}
