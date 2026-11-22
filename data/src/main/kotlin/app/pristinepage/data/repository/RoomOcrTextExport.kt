package app.pristinepage.data.repository

import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.model.ArtifactId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.TransactionId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** No provider, original-file, filename or publication authority. Owns a journaled, bounded private byte snapshot. */
internal class RoomOcrTextExport(
    private val store: PrivateImportStore.OcrComposition,
    private val search: RoomOcrSearch,
) {
    private val dao = store.database.exportSnapshots()
    private val metadata = RoomMetadataAccess(store.database)
    private val files by lazy { store.derivedFiles() }

    class Receipt(val intent: OcrTextExportIntent, val sealSha256: String)

    private enum class Phase { INTENT, COPYING, SEALED, MOVED, COMMITTED }

    private class Attempt(val intent: OcrTextExportIntent) {
        val tx = intent.transaction.value
        val artifact = intent.artifact.value
        val payload = intent.encode()
        val stage = "staging/$tx/derived.part"
        val final = "ocr-text-exports/$artifact/text.bin"
        val target = DerivedFileTarget(DerivedKind.OCR_TEXT, artifact)
    }

    /** The issuer retains the same database lifetime. Nothing except this attempt's seal/payload may be moved or committed. */
    suspend fun prepare(binding: RoomOcrSearch.TextBinding): Receipt? = withContext(Dispatchers.IO) {
        store.owners.withOrdinaryDerivedLane(binding.producer.producingJob) {
            val context = currentCoroutineContext()
            val active = { context.ensureActive() }
            // Copying the bounded blob and journaling its exact identity share one checkpoint. No file work holds the transaction.
            val frozen = metadata.transaction {
                val bytes = search.resolveTextExportInTransaction(binding, active) ?: return@transaction null
                val attempt = Attempt(OcrTextExportIntent(
                    TransactionId(UUID.randomUUID().toString()), ArtifactId(UUID.randomUUID().toString()),
                    binding.producer.producingJob, binding.inputDigest, ExportAssemblyInput.digest(bytes), bytes.size.toLong(),
                ))
                active()
                dao.insertRenderIntent(FileTransactionRow(attempt.tx, "OCR_TEXT", attempt.artifact, Phase.INTENT.name,
                    attempt.stage, attempt.final, null, attempt.intent.textSha256, attempt.intent.byteCount, attempt.payload,
                    1, null, System.currentTimeMillis()))
                advance(attempt, Phase.INTENT, Phase.COPYING, null, null, active)
                attempt to bytes
            } ?: return@withOrdinaryDerivedLane null
            val (attempt, bytes) = frozen
            val staged = try {
                files.stage(attempt.tx, attempt.target, attempt.intent.byteCount, { it.write(bytes) }, active)
            } finally {
                bytes.fill(0)
            }
            if (staged.byteSha256 != attempt.intent.textSha256 || staged.byteCount != attempt.intent.byteCount) invalid()
            fun prove(phase: Phase, seal: String?) = metadata.transaction { verify(attempt, phase, seal, active) }
            val sealed = files.sealWithFence(staged, attempt.payload, active) { prove(Phase.COPYING, null) }
            val seal = sealed.sealSha256
            metadata.transaction { advance(attempt, Phase.COPYING, Phase.SEALED, null, seal, active) }
            files.moveWithFence(sealed, active, allowVerifiedExisting = false) { prove(Phase.SEALED, seal) }
            metadata.transaction {
                advance(attempt, Phase.SEALED, Phase.MOVED, seal, seal, active)
            }
            metadata.transaction { advance(attempt, Phase.MOVED, Phase.COMMITTED, seal, seal, active) }
            active()
            Receipt(attempt.intent, seal)
        }
    }

    private fun verify(attempt: Attempt, phase: Phase, seal: String?, active: () -> Unit) {
        active()
        if (!dao.ocrTextJournalMatches(attempt.tx, attempt.artifact, attempt.stage, attempt.final, attempt.payload,
                phase.name, seal, attempt.intent.textSha256, attempt.intent.byteCount)) invalid()
        active()
    }

    private fun advance(attempt: Attempt, previous: Phase, next: Phase, previousSeal: String?, seal: String?, active: () -> Unit) {
        active()
        if (dao.advanceOcrTextJournal(attempt.tx, attempt.artifact, attempt.stage, attempt.final, attempt.payload,
                previous.name, next.name, System.currentTimeMillis(), previousSeal, seal,
                attempt.intent.textSha256, attempt.intent.byteCount) != 1) invalid()
        verify(attempt, next, seal, active)
    }

    private fun invalid(): Nothing =
        throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "ocr_text_export_journal_mismatch", null))
}
