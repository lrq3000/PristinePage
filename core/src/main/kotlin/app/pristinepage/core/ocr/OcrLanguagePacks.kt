package app.pristinepage.core.ocr

import app.pristinepage.core.pipeline.requireSha256
import java.util.Collections

/** Identifies one recognition pack by code and exact data bytes; not proof of installation. */
data class OcrLanguagePack(val code: String, val sha256: String) {
    init {
        require(code.matches(CODE)) { "Invalid OCR language pack code" }
        requireSha256(sha256)
    }

    private companion object {
        val CODE = Regex("[a-z][a-z0-9_]{0,31}")
    }
}

/** Recognition order is significant. Snapshot it so an admitted job cannot change with user preferences. */
class OcrLanguagePacks(packs: List<OcrLanguagePack>) {
    val ordered: List<OcrLanguagePack>

    init {
        // Bound the elements actually consumed; the caller's size can change before iteration.
        val snapshot = ArrayList<OcrLanguagePack>()
        val codes = HashSet<String>()
        val entries = packs.iterator()
        while (entries.hasNext()) {
            require(snapshot.size < MAX_PACKS) { "Invalid OCR language pack count" }
            val pack = entries.next()
            require(codes.add(pack.code)) { "Duplicate OCR language pack code" }
            snapshot.add(pack)
        }
        require(snapshot.isNotEmpty()) { "Invalid OCR language pack count" }
        ordered = Collections.unmodifiableList(snapshot)
    }

    override fun equals(other: Any?): Boolean = other is OcrLanguagePacks && ordered == other.ordered
    override fun hashCode(): Int = ordered.hashCode()

    private companion object {
        const val MAX_PACKS = 32
    }
}
