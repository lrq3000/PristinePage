package app.pristinepage.ocr

import androidx.work.Data
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.SavedOcrWork

/** Only opaque values travel through WorkManager. The receiver must still borrow the exact participating graph. */
internal data class ActivationBoundOcrWake(val activation: LibraryActivation, val target: SavedOcrWork.Target) {
    fun encode(): Data = Data.Builder().putString(PAGE, target.page.value).putString(JOB, target.job.value)
        .putString(LIBRARY, activation.libraryId).putLong(ACTIVATION, activation.number).build()

    fun uniqueName(): String = "saved-ocr-page-v2/${activation.libraryId}/${activation.number}/${target.job.value}"

    companion object {
        private const val LIBRARY = "library"
        private const val ACTIVATION = "activation"
        private const val PAGE = "page"
        private const val JOB = "job"

        fun isLegacy(input: Data): Boolean = input.keyValueMap.let { it.size == 2 && target(it) != null }

        fun decode(input: Data): ActivationBoundOcrWake? {
            val fields = input.keyValueMap
            if (fields.size != 4) return null
            val library = fields[LIBRARY] as? String ?: return null
            val number = fields[ACTIVATION] as? Long ?: return null
            if (library.length != 36 || number <= 0) return null
            val target = target(fields) ?: return null
            return try { ActivationBoundOcrWake(LibraryActivation(library, number), target) }
            catch (_: IllegalArgumentException) { null }
        }

        private fun target(fields: Map<String, Any?>): SavedOcrWork.Target? {
            val page = fields[PAGE] as? String ?: return null
            val job = fields[JOB] as? String ?: return null
            if (page.length != 36 || job.length != 36) return null
            return try { SavedOcrWork.Target(PageId(page), JobId(job)) }
            catch (_: IllegalArgumentException) { null }
        }
    }
}
