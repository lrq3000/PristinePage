package app.pristinepage.design

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.pristinepage.R
import app.pristinepage.core.ocr.OcrLayout
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
internal enum class OcrLanguage(val packCode: String, private val label: Int, private val englishName: String? = null) {
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
    VIETNAMESE("vie", R.string.ocr_language_vietnamese),
    AFRIKAANS("afr", "Afrikaans"),
    AMHARIC("amh", "Amharic"),
    ASSAMESE("asm", "Assamese"),
    AZERBAIJANI("aze", "Azerbaijani"),
    AZERBAIJANI_CYRILLIC("aze_cyrl", "Azerbaijani (Cyrillic)"),
    BELARUSIAN("bel", "Belarusian"),
    BENGALI("ben", "Bengali"),
    TIBETAN("bod", "Tibetan"),
    BOSNIAN("bos", "Bosnian"),
    BRETON("bre", "Breton"),
    BULGARIAN("bul", "Bulgarian"),
    CATALAN("cat", "Catalan"),
    CEBUANO("ceb", "Cebuano"),
    CZECH("ces", "Czech"),
    CHINESE_SIMPLIFIED_VERTICAL("chi_sim_vert", "Chinese, Simplified (vertical)"),
    CHINESE_TRADITIONAL("chi_tra", "Chinese, Traditional"),
    CHINESE_TRADITIONAL_VERTICAL("chi_tra_vert", "Chinese, Traditional (vertical)"),
    CHEROKEE("chr", "Cherokee"),
    CORSICAN("cos", "Corsican"),
    WELSH("cym", "Welsh"),
    DANISH("dan", "Danish"),
    DIVEHI("div", "Divehi"),
    DZONGKHA("dzo", "Dzongkha"),
    ENGLISH_MIDDLE("enm", "English, Middle"),
    ESPERANTO("epo", "Esperanto"),
    EQUATIONS("equ", "Mathematical equations"),
    ESTONIAN("est", "Estonian"),
    BASQUE("eus", "Basque"),
    FAROESE("fao", "Faroese"),
    PERSIAN("fas", "Persian"),
    FILIPINO("fil", "Filipino"),
    FINNISH("fin", "Finnish"),
    GERMAN_FRAKTUR("frk", "German (Fraktur)"),
    FRENCH_MIDDLE("frm", "French, Middle"),
    WESTERN_FRISIAN("fry", "Western Frisian"),
    SCOTTISH_GAELIC("gla", "Scottish Gaelic"),
    IRISH("gle", "Irish"),
    GALICIAN("glg", "Galician"),
    GREEK_ANCIENT("grc", "Greek, Ancient"),
    GUJARATI("guj", "Gujarati"),
    HAITIAN_CREOLE("hat", "Haitian Creole"),
    CROATIAN("hrv", "Croatian"),
    HUNGARIAN("hun", "Hungarian"),
    ARMENIAN("hye", "Armenian"),
    INUKTITUT("iku", "Inuktitut"),
    ICELANDIC("isl", "Icelandic"),
    ITALIAN_OLD("ita_old", "Italian, Old"),
    JAVANESE("jav", "Javanese"),
    JAPANESE_VERTICAL("jpn_vert", "Japanese (vertical)"),
    KANNADA("kan", "Kannada"),
    GEORGIAN("kat", "Georgian"),
    GEORGIAN_OLD("kat_old", "Georgian, Old"),
    KAZAKH("kaz", "Kazakh"),
    KHMER("khm", "Khmer"),
    KYRGYZ("kir", "Kyrgyz"),
    KURMANJI("kmr", "Northern Kurdish (Kurmanji)"),
    KOREAN_VERTICAL("kor_vert", "Korean (vertical)"),
    LAO("lao", "Lao"),
    LATIN("lat", "Latin"),
    LATVIAN("lav", "Latvian"),
    LITHUANIAN("lit", "Lithuanian"),
    LUXEMBOURGISH("ltz", "Luxembourgish"),
    MALAYALAM("mal", "Malayalam"),
    MARATHI("mar", "Marathi"),
    MACEDONIAN("mkd", "Macedonian"),
    MALTESE("mlt", "Maltese"),
    MONGOLIAN("mon", "Mongolian"),
    MAORI("mri", "Māori"),
    MALAY("msa", "Malay"),
    BURMESE("mya", "Burmese"),
    NEPALI("nep", "Nepali"),
    NORWEGIAN("nor", "Norwegian"),
    OCCITAN("oci", "Occitan"),
    ODIA("ori", "Odia"),
    PUNJABI("pan", "Punjabi"),
    PASHTO("pus", "Pashto"),
    QUECHUA("que", "Quechua"),
    ROMANIAN("ron", "Romanian"),
    SANSKRIT("san", "Sanskrit"),
    SINHALA("sin", "Sinhala"),
    SLOVAK("slk", "Slovak"),
    SLOVENIAN("slv", "Slovenian"),
    SINDHI("snd", "Sindhi"),
    SPANISH_OLD("spa_old", "Spanish, Old"),
    ALBANIAN("sqi", "Albanian"),
    SERBIAN("srp", "Serbian (Cyrillic)"),
    SERBIAN_LATIN("srp_latn", "Serbian (Latin)"),
    SUNDANESE("sun", "Sundanese"),
    SWAHILI("swa", "Swahili"),
    SWEDISH("swe", "Swedish"),
    SYRIAC("syr", "Syriac"),
    TAMIL("tam", "Tamil"),
    TATAR("tat", "Tatar"),
    TELUGU("tel", "Telugu"),
    TAJIK("tgk", "Tajik"),
    THAI("tha", "Thai"),
    TIGRINYA("tir", "Tigrinya"),
    TONGAN("ton", "Tongan"),
    UYGHUR("uig", "Uyghur"),
    URDU("urd", "Urdu"),
    UZBEK("uzb", "Uzbek (Latin)"),
    UZBEK_CYRILLIC("uzb_cyrl", "Uzbek (Cyrillic)"),
    YIDDISH("yid", "Yiddish"),
    YORUBA("yor", "Yoruba");

    constructor(packCode: String, englishName: String) : this(packCode, 0, englishName)

    @Composable fun displayName(): String = englishName ?: stringResource(label)
    val selectable: Boolean get() = this != EQUATIONS

    companion object {
        const val MAX_SELECTED = 32
        val DEFAULT: List<OcrLanguage> = Collections.unmodifiableList(listOf(ENGLISH, FRENCH))
        private val byCode = entries.associateBy(OcrLanguage::packCode)

        fun fromStored(orderedCodes: Any?): List<OcrLanguage> {
            val codes = orderedCodes as? String ?: return DEFAULT
            if (codes.length > (MAX_SELECTED + 1) * 33) return DEFAULT
            val known = codes.split(',').mapNotNull(byCode::get).filter { it.selectable }.distinct().take(MAX_SELECTED)
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
    val ocrDiagnostics: Boolean = false,
    val ocrLayout: OcrLayout = OcrLayout.AUTOMATIC,
) {
    fun withOcrLanguage(language: OcrLanguage, enabled: Boolean): AtelierSettings {
        if (!language.selectable || (language in ocrLanguages) == enabled || (!enabled && ocrLanguages.size == 1) ||
            (enabled && ocrLanguages.size >= OcrLanguage.MAX_SELECTED)) return this
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
            values["ocrDiagnostics"] as? Boolean ?: false,
            OcrLayout.entries.firstOrNull { it.name == values["ocrLayout"] } ?: OcrLayout.AUTOMATIC,
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

    fun setOcrDiagnostics(enabled: Boolean) {
        if (current.value.ocrDiagnostics == enabled) return
        storage.edit { putBoolean("ocrDiagnostics", enabled) }
        current.value = current.value.copy(ocrDiagnostics = enabled)
    }

    fun setOcrLayout(layout: OcrLayout) {
        if (current.value.ocrLayout == layout) return
        storage.edit { putString("ocrLayout", layout.name) }
        current.value = current.value.copy(ocrLayout = layout)
    }
}
