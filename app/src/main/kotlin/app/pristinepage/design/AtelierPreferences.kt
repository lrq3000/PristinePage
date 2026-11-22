package app.pristinepage.design

import android.content.Context
import androidx.core.content.edit
import app.pristinepage.R
import app.pristinepage.data.model.AlbumOrder
import app.pristinepage.library.AlbumTitleTemplate
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class HomeThumbnailLayout {
    STACK, ROW;

    fun <T> pages(ordered: List<T>): List<T> = when (this) {
        STACK -> ordered.take(3)
        ROW -> ordered
    }
}

/** These are selectable default languages, not claims that recognition weights or a runtime are installed. */
internal enum class OcrLanguage(val packCode: String, val label: Int) {
    ARABIC("ara", R.string.ocr_language_arabic),
    CHINESE_SIMPLIFIED("chi_sim", R.string.ocr_language_chinese_simplified),
    DUTCH("nld", R.string.ocr_language_dutch),
    ENGLISH("eng", R.string.ocr_language_english),
    FRENCH("fra", R.string.ocr_language_french),
    GERMAN("deu", R.string.ocr_language_german),
    GREEK("ell", R.string.ocr_language_greek),
    HEBREW("heb", R.string.ocr_language_hebrew),
    HINDI("hin", R.string.ocr_language_hindi),
    INDONESIAN("ind", R.string.ocr_language_indonesian),
    ITALIAN("ita", R.string.ocr_language_italian),
    JAPANESE("jpn", R.string.ocr_language_japanese),
    KOREAN("kor", R.string.ocr_language_korean),
    POLISH("pol", R.string.ocr_language_polish),
    PORTUGUESE("por", R.string.ocr_language_portuguese),
    RUSSIAN("rus", R.string.ocr_language_russian),
    SPANISH("spa", R.string.ocr_language_spanish),
    TURKISH("tur", R.string.ocr_language_turkish),
    UKRAINIAN("ukr", R.string.ocr_language_ukrainian),
    VIETNAMESE("vie", R.string.ocr_language_vietnamese);

    companion object {
        val DEFAULT: List<OcrLanguage> = Collections.unmodifiableList(listOf(ENGLISH, FRENCH))
        private val byCode = entries.associateBy(OcrLanguage::packCode)

        fun fromStored(orderedCodes: Any?): List<OcrLanguage> {
            val codes = orderedCodes as? String ?: return DEFAULT
            if (codes.length > 160) return DEFAULT
            val known = codes.split(',').mapNotNull(byCode::get).distinct()
            return if (known.isEmpty()) DEFAULT else Collections.unmodifiableList(known)
        }
    }
}

internal data class AtelierSettings(
    val order: AlbumOrder = AlbumOrder.MODIFIED,
    val showCreationInNameSort: Boolean = false,
    val showCameraGrid: Boolean = true,
    val homeThumbnailLayout: HomeThumbnailLayout = HomeThumbnailLayout.STACK,
    val albumTitleTemplate: AlbumTitleTemplate = AlbumTitleTemplate.DEFAULT,
    val ocrLanguages: List<OcrLanguage> = OcrLanguage.DEFAULT,
) {
    fun withOcrLanguage(language: OcrLanguage, enabled: Boolean): AtelierSettings {
        if ((language in ocrLanguages) == enabled || (!enabled && ocrLanguages.size == 1)) return this
        val ordered = if (enabled) ocrLanguages + language else ocrLanguages - language
        return copy(ocrLanguages = Collections.unmodifiableList(ordered))
    }

    companion object {
        @JvmStatic fun fromStored(values: Map<String, *>): AtelierSettings = AtelierSettings(
            AlbumOrder.entries.firstOrNull { it.name == values["order"] } ?: AlbumOrder.MODIFIED,
            values["created"] as? Boolean ?: false,
            values["grid"] as? Boolean ?: true,
            HomeThumbnailLayout.entries.firstOrNull { it.name == values["homeThumbnailLayout"] } ?: HomeThumbnailLayout.STACK,
            (values["albumTitleTemplate"] as? String)?.let(AlbumTitleTemplate::parse) ?: AlbumTitleTemplate.DEFAULT,
            OcrLanguage.fromStored(values["ocrLanguages"]),
        )
    }
}

/** Process-owned, main-thread preference changes. The keys are independent of albums and never store photo or picker data. */
internal class AtelierPreferences(context: Context, name: String = "pristinepage.atelier.v1") {
    private val storage = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val current = MutableStateFlow(AtelierSettings.fromStored(storage.all))
    val settings = current.asStateFlow()

    fun setOrder(order: AlbumOrder) {
        storage.edit().putString("order", order.name).apply()
        current.value = current.value.copy(order = order)
    }

    fun setShowCreationInNameSort(show: Boolean) {
        storage.edit().putBoolean("created", show).apply()
        current.value = current.value.copy(showCreationInNameSort = show)
    }

    fun setShowCameraGrid(show: Boolean) {
        storage.edit().putBoolean("grid", show).apply()
        current.value = current.value.copy(showCameraGrid = show)
    }

    fun setHomeThumbnailLayout(layout: HomeThumbnailLayout) {
        storage.edit().putString("homeThumbnailLayout", layout.name).apply()
        current.value = current.value.copy(homeThumbnailLayout = layout)
    }

    fun setAlbumTitleTemplate(template: AlbumTitleTemplate) {
        storage.edit().putString("albumTitleTemplate", template.pattern).apply()
        current.value = current.value.copy(albumTitleTemplate = template)
    }

    fun setOcrLanguage(language: OcrLanguage, enabled: Boolean) {
        val previous = current.value
        val updated = previous.withOcrLanguage(language, enabled)
        if (updated == previous) return
        storage.edit { putString("ocrLanguages", updated.ocrLanguages.joinToString(",") { it.packCode }) }
        current.value = updated
    }
}
