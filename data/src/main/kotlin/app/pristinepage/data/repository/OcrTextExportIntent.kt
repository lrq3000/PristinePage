package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.files.MAX_OCR_TEXT_BYTES
import app.pristinepage.data.model.ArtifactId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.TransactionId

/** File and journal binding only. The producer input digest covers the saved identity and effective configuration; no result bytes or destination belong here. */
internal class OcrTextExportIntent(
    val transaction: TransactionId,
    val artifact: ArtifactId,
    val producer: JobId,
    val producerInputSha256: String,
    val textSha256: String,
    val byteCount: Long,
) {
    init {
        requireSha256(producerInputSha256)
        requireSha256(textSha256)
        require(byteCount in 0L..MAX_OCR_TEXT_BYTES)
        require(byteCount != 0L || textSha256 == EMPTY_SHA)
    }

    fun encode(): ByteArray =
        listOf(TAG, transaction.value, artifact.value, producer.value, producerInputSha256, textSha256, byteCount.toString())
            .joinToString("\u0000").encodeToByteArray()

    companion object {
        private const val TAG = "pristinepage-ocr-text-export-v1"
        private const val EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        // One to six ASCII digits for the bounded byte count; everything else has fixed canonical width.
        private const val MIN_BYTES = 274
        private const val MAX_BYTES = 279

        fun decode(bytes: ByteArray): OcrTextExportIntent {
            require(bytes.size in MIN_BYTES..MAX_BYTES)
            val snapshot = bytes.copyOf()
            require(snapshot.all { it >= 0 })
            val fields = snapshot.decodeToString().split('\u0000', limit = 8)
            require(fields.size == 7 && fields[0] == TAG)
            val decoded = OcrTextExportIntent(
                TransactionId(fields[1]), ArtifactId(fields[2]), JobId(fields[3]),
                fields[4], fields[5], requireNotNull(fields[6].toLongOrNull()),
            )
            require(snapshot.contentEquals(decoded.encode()))
            return decoded
        }
    }
}
