package app.pristinepage.data.library

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.LibraryFolderExportRecords.Intent
import app.pristinepage.data.library.LocalLibraryDirectory.ExportRecord
import app.pristinepage.data.model.requireOpaqueId
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Private journal for one operation directory held exclusively under an already durable app-files root. Nothing here opens the external tree or reenables publication. */
internal class LocalLibraryFolderExportJournal(
    attempt: File, sync: DirectorySync = PlatformDirectorySync, private val checkpoint: () -> Unit = {},
) {
    private val directory = LocalLibraryDirectory(attempt, sync)

    enum class Phase { INTENT_ONLY, COMPLETED, ACKNOWLEDGED }
    data class Observation(val intent: Intent, val phase: Phase)
    sealed interface LiveCompletion {
        /** Call only after the live writer has verified the exact external result and its durability. */
        fun recordCompletion()
    }
    sealed interface Prepared {
        fun start(intent: Intent): LiveCompletion
        fun discardCapacityRefusal()
    }

    private inner class Started(private val intent: Intent) : LiveCompletion {
        private val spent = AtomicBoolean()

        override fun recordCompletion() {
            if (!spent.compareAndSet(false, true)) throw IOException("Backup completion was already attempted")
            complete(intent)
        }
    }

    private inner class Anchored(
        private val operationId: String, private val generation: LibraryGeneration,
        private val requireOriginalAttempt: () -> Unit, private val requireOriginalStage: () -> Unit,
    ) : Prepared {
        private val consumed = AtomicBoolean()

        override fun start(intent: Intent): LiveCompletion {
            if (intent.operationId != operationId || intent.generation != generation || !consumed.compareAndSet(false, true)) {
                throw IOException("The closed backup stage does not match a fresh attempt")
            }
            requireOriginalAttempt()
            requireOriginalStage()
            directory.requireOnlyAnchoredExportStage()
            publish(ExportRecord.INTENT, intent, intent.encode(), Phase.INTENT_ONLY)
            return Started(intent)
        }

        override fun discardCapacityRefusal() {
            if (!consumed.compareAndSet(false, true)) throw IOException("The backup stage may have started publication")
            directory.discardUnpublishedExportStage(generation, checkpoint, requireOriginalAttempt, requireOriginalStage)
        }
    }

    /** The caller must have allocated a fresh directory, prepared this catalog, and independently admitted the exact destination. */
    fun start(intent: Intent): LiveCompletion = anchorClosedStage(intent.operationId, intent.generation).start(intent)

    /** Before capacity admission, create live evidence of which app-private file is disposable. Only this token can retire a definite refusal. */
    fun anchorClosedStage(operationId: String, generation: LibraryGeneration,
        requireOriginalAttempt: () -> Unit = {}, requireOriginalStage: () -> Unit = {},
    ): Prepared {
        requireAttempt(operationId)
        requireOriginalAttempt()
        requireOriginalStage()
        if (observe(operationId) != null) throw IOException("The backup was already started")
        directory.requireAndForceExportStage(generation, checkpoint)
        directory.anchorExportStage()
        requireOriginalAttempt()
        requireOriginalStage()
        return Anchored(operationId, generation, requireOriginalAttempt, requireOriginalStage)
    }

    private fun complete(intent: Intent) {
        if (reopen(intent.operationId) != Observation(intent, Phase.INTENT_ONLY)) {
            throw IOException("The backup intent is not awaiting a completion record")
        }
        directory.requireAndForceExportStage(intent.generation, checkpoint)
        publish(ExportRecord.COMPLETE, intent, intent.completion(), Phase.COMPLETED)
    }

    /** Idempotent private accounting only; even repeated acknowledgment never opens the provider or repeats a move. */
    fun acknowledge(intent: Intent) {
        val prior = reopen(intent.operationId)
        if (prior?.intent != intent || prior.phase == Phase.INTENT_ONLY) throw IOException("No exact completed backup to acknowledge")
        if (prior.phase == Phase.ACKNOWLEDGED) return
        publish(ExportRecord.ACKNOWLEDGED, intent, intent.acknowledgment(), Phase.ACKNOWLEDGED)
    }

    /** Only the catalog copy is reclaimable here; terminal records and every spend still fence repeat publication. */
    fun reclaimAcknowledgedCatalog(operationId: String): Boolean {
        val observed = reopen(operationId) ?: return false
        if (observed.phase != Phase.ACKNOWLEDGED) return false
        directory.reclaimExportStage(observed.intent.generation, checkpoint)
        if (reopen(operationId) != observed) throw IOException("The acknowledged backup record changed")
        return true
    }

    /** A positively absent private record is an observation, never permission to begin or retry a write. */
    fun reopen(operationId: String): Observation? {
        requireAttempt(operationId)
        val observed = observe(operationId) ?: return null
        directory.forceExportJournal()
        if (observe(operationId) != observed) throw IOException("The backup record changed during readback")
        return observed
    }

    /** An observed intent may rule out a foreign owner; only full reopen can admit a receipt. */
    fun observeIntentOwner(operationId: String): LibraryActivation? {
        requireAttempt(operationId)
        checkpoint()
        return readIntent(operationId)?.issuedAt
    }

    private fun publish(record: ExportRecord, intent: Intent, bytes: ByteArray, phase: Phase) {
        checkpoint()
        val requiresStage = record != ExportRecord.ACKNOWLEDGED
        directory.createExportRecord(record, bytes) {
            // After admitting the private acknowledgment, do not interrupt its bounded exclusive-link write between the forced stage and directory sync. Cancellation is still observed on readback.
            if (requiresStage) {
                directory.requireExportStageOwner()
                directory.verifyExportStage(intent.generation, checkpoint)
                checkpoint()
            }
        }
        if (reopen(intent.operationId) != Observation(intent, phase)) throw IOException("The private backup record was not acknowledged")
        if (requiresStage) directory.verifyExportStage(intent.generation, checkpoint)
        checkpoint()
    }

    private fun observe(operationId: String): Observation? {
        checkpoint()
        val intent = readIntent(operationId)
        val complete = directory.openExportRecord(ExportRecord.COMPLETE)?.use {
            (intent ?: outOfOrder()).requireCompletion(it)
            true
        } ?: false
        val ack = directory.openExportRecord(ExportRecord.ACKNOWLEDGED)?.use {
            if (intent == null || !complete) outOfOrder()
            intent.requireAcknowledgment(it)
            true
        } ?: false
        if (intent == null) directory.requireUnstartedExport()
        else directory.requireResolvedExportStages(buildSet {
            add(ExportRecord.INTENT)
            if (complete) add(ExportRecord.COMPLETE)
            if (ack) add(ExportRecord.ACKNOWLEDGED)
        })
        checkpoint()
        val phase = if (ack) Phase.ACKNOWLEDGED else if (complete) Phase.COMPLETED else Phase.INTENT_ONLY
        return intent?.let { Observation(it, phase) }
    }

    private fun readIntent(operationId: String) =
        directory.openExportRecord(ExportRecord.INTENT)?.use { LibraryFolderExportRecords.readIntent(it, operationId) }

    private fun requireAttempt(operationId: String) {
        requireOpaqueId(operationId)
        if (directory.rootPath.fileName.toString() != "library-out-$operationId") {
            throw IOException("The private backup belongs to another operation")
        }
    }

    private fun outOfOrder(): Nothing = throw IOException("Private backup records are out of order")
}

internal fun LocalLibraryFolderExportJournal.Observation?.backupState(): LibraryFolderExportState = when (this?.phase) {
    null, LocalLibraryFolderExportJournal.Phase.INTENT_ONLY -> LibraryFolderExportState.NeedsVerification
    LocalLibraryFolderExportJournal.Phase.COMPLETED -> LibraryFolderExportState.Completed(intent.backupReceipt())
    LocalLibraryFolderExportJournal.Phase.ACKNOWLEDGED -> LibraryFolderExportState.Acknowledged(intent.backupReceipt())
}
