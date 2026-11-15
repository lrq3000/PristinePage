package app.pristinepage.data.repository

import kotlinx.coroutines.sync.Mutex

/** One process-wide copy/hash admission shared by new ingestion and recovery, not two independent budgets. Decoder/native allocation remains the inspector/renderer's separate admission responsibility. */
internal object SourceCopyAdmission {
    val mutex = Mutex()
}
