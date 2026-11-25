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

    private inner class Started(private val intent: Intent) : LiveCompletion {
        private val spent = AtomicBoolean()

        override fun recordCompletion() {
            if (!spent.compareAndSet(false, true)) throw IOException("Backup completion was already attempted")
            complete(intent)
        }
    }

    /** The caller must have allocated a fresh directory, prepared this catalog, and independently admitted the exact destination. */
    fun start(intent: Intent): LiveCompletion {
        requireAttempt(intent.operationId)
        if (observe(intent.operationId) != null) throw IOException("The backup was already started")
        directory.requireAndForceExportStage(intent.generation, checkpoint)
        publish(ExportRecord.INTENT, intent, intent.encode(), Phase.INTENT_ONLY)
        return Started(intent)
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

    /** A positively absent private record is an observation, never permission to begin or retry a write. */
    fun reopen(operationId: String): Observation? {
        requireAttempt(operationId)
        val observed = observe(operationId) ?: return null
        directory.forceExportJournal()
        if (observe(operationId) != observed) throw IOException("The backup record changed during readback")
        return observed
    }

    private fun publish(record: ExportRecord, intent: Intent, bytes: ByteArray, phase: Phase) {
        checkpoint()
        val requiresStage = record != ExportRecord.ACKNOWLEDGED
        directory.createExportRecord(record, bytes) {
            // After admitting the private acknowledgment, do not interrupt its bounded exclusive-link write between the forced stage and directory sync. Cancellation is still observed on readback.
            if (requiresStage) {
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
        val intent = directory.openExportRecord(ExportRecord.INTENT)?.use { LibraryFolderExportRecords.readIntent(it, operationId) }
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

    private fun requireAttempt(operationId: String) {
        requireOpaqueId(operationId)
        if (directory.rootPath.fileName.toString() != "library-out-$operationId") {
            throw IOException("The private backup belongs to another operation")
        }
    }

    private fun outOfOrder(): Nothing = throw IOException("Private backup records are out of order")
}
