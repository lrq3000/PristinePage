package app.pristinepage.core.ocr

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.util.UUID

data class OcrInput(val pageId: String, val recipeRevision: Long, val sourcePixelSha256: String) {
    init {
        require(pageId.matches(UUID_TEXT) && recipeRevision > 0 && sourcePixelSha256.matches(SHA256))
    }

    private companion object {
        val UUID_TEXT = Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

enum class OcrLayout { AUTOMATIC, SINGLE_BLOCK, SPARSE_TEXT }

data class OcrConfiguration(
    val engineId: String,
    val engineVersion: Int,
    val layout: OcrLayout,
    // Null preserves the engine-neutral test configuration. A real worker must resolve exact pack identities.
    val languagePacks: OcrLanguagePacks? = null,
) {
    init { require(engineId.matches(ENGINE_ID) && engineVersion > 0) }

    private companion object { val ENGINE_ID = Regex("[a-z][a-z0-9-]{0,62}") }
}

/** A local engine borrows a verified, already admitted raster; it must not retain or publish that raster. */
fun interface OcrEngine {
    suspend fun recognize(image: RgbImage, configuration: OcrConfiguration): OcrText
}

/** No data-class toString: raw page text must not appear in incidental diagnostic logging. */
class OcrText(val value: String) {
    init {
        require(value.length <= 131_072 && '\u0000' !in value)
        requireWellFormedUnicode(value)
    }
}

class OcrResult internal constructor(val configuration: OcrConfiguration, val text: OcrText)
enum class OcrPhase { QUEUED, RUNNING, READY, FAILED }
enum class OcrError { ENGINE_FAILED, RESOURCE_LIMIT, INVALID_OUTPUT, INTERRUPTED }

data class OcrAttempt internal constructor(val input: OcrInput, val generation: Long, val number: Int, val ownerId: UUID)

/** Pure transitions. The durable owner must issue a fresh attempt ID for each claim, compare-and-swap snapshots and verify ownership before recovery. */
class OcrWork private constructor(
    val input: OcrInput,
    val configuration: OcrConfiguration,
    val generation: Long,
    val phase: OcrPhase,
    val attemptsStarted: Int,
    val result: OcrResult?,
    val lastError: OcrError?,
    private val activeAttempt: OcrAttempt? = null,
) {
    val processing: Boolean get() = phase == OcrPhase.QUEUED || phase == OcrPhase.RUNNING
    val token: OcrAttempt get() = checkNotNull(activeAttempt)

    fun start(ownerId: UUID): OcrWork? = if (phase == OcrPhase.QUEUED && attemptsStarted < MAX_STARTS) {
        val attempt = OcrAttempt(input, generation, attemptsStarted + 1, ownerId)
        OcrWork(input, configuration, generation, OcrPhase.RUNNING, attempt.number, result, lastError, attempt)
    } else null

    fun succeeded(attempt: OcrAttempt, text: OcrText): OcrWork? = if (owns(attempt)) {
        OcrWork(input, configuration, generation, OcrPhase.READY, attemptsStarted, OcrResult(configuration, text), null)
    } else null

    fun failed(attempt: OcrAttempt, error: OcrError): OcrWork? = if (owns(attempt)) {
        val next = if (attemptsStarted == MAX_STARTS) OcrPhase.FAILED else OcrPhase.QUEUED
        OcrWork(input, configuration, generation, next, attemptsStarted, result, error)
    } else null

    fun interrupted(attempt: OcrAttempt): OcrWork? = failed(attempt, OcrError.INTERRUPTED)

    fun rerun(configuration: OcrConfiguration): OcrWork =
        OcrWork(input, configuration, Math.incrementExact(generation), OcrPhase.QUEUED, 0, result, null)

    private fun owns(attempt: OcrAttempt) = activeAttempt == attempt

    companion object {
        private const val MAX_STARTS = 3

        fun queued(input: OcrInput, configuration: OcrConfiguration): OcrWork =
            OcrWork(input, configuration, 1, OcrPhase.QUEUED, 0, null, null)
    }
}
