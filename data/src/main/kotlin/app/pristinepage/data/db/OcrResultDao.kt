package app.pristinepage.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.OcrSearchTerms
import app.pristinepage.data.repository.canonicalOcrSearchText

/** The immutable source/recipe binding keeps a result private to its exact saved page revision. */
@Entity(tableName = "OcrResult", foreignKeys = [
    ForeignKey(entity = PageRow::class, parentColumns = ["pageId"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["pageId"], unique = true)])
internal class OcrResultRow(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Long,
    val pageId: String,
    val recipeRevision: Long,
    val sourcePixelSha256: String,
    val engineId: String,
    val engineVersion: Int,
    val layout: String,
    val indexedText: String,
    val verbatimText: String?,
) {
    fun recognizedText(): String = verbatimText ?: indexedText

    companion object {
        fun fromRecognition(input: OcrInput, configuration: OcrConfiguration, text: OcrText): OcrResultRow {
            val indexed = canonicalOcrSearchText(text.value)
            require(indexed.length <= 262_144) // NFC can expand some inputs; do not truncate the index silently.
            return OcrResultRow(0, input.pageId, input.recipeRevision, input.sourcePixelSha256, configuration.engineId,
                configuration.engineVersion, configuration.layout.name, indexed, text.value.takeUnless { it == indexed })
        }
    }
}

@Fts4(contentEntity = OcrResultRow::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "OcrResultFts")
internal class OcrResultFtsRow(val indexedText: String)

internal data class OcrMatchRow(val documentId: String, val pageId: String)

@Dao
internal abstract class OcrResultDao {
    fun matchPages(terms: OcrSearchTerms, afterPageId: PageId?, limit: Int): List<OcrMatchRow> =
        selectMatches(terms.expression, afterPageId?.value, limit)

    // This narrow prefilter is not navigation authority. A consuming repository must also decode the bounded Atelier receipt and use the existing guarded page/document reads.
    @Query("""SELECT Document.documentId, Page.pageId
        FROM OcrResultFts JOIN OcrResult ON OcrResult.rowid=OcrResultFts.rowid
        JOIN Page ON Page.pageId=OcrResult.pageId AND Page.currentRecipeRevision=OcrResult.recipeRevision
        JOIN RecipeRevision ON RecipeRevision.pageId=Page.pageId
          AND RecipeRevision.recipeRevision=Page.currentRecipeRevision AND RecipeRevision.sourceId=Page.sourceId
        JOIN Document ON Document.documentId=Page.documentId
        JOIN Source ON Source.sourceId=Page.sourceId AND Source.pixelSha256=OcrResult.sourcePixelSha256
        JOIN SourceBlob ON SourceBlob.sourceBlobId=Source.sourceBlobId
        WHERE OcrResultFts MATCH :expression AND (:afterPageId IS NULL OR Page.pageId>:afterPageId)
          AND Page.state='ACTIVE' AND Page.removedAt IS NULL AND typeof(Page.ordinal)='integer' AND Page.ordinal BETWEEN 0 AND 99
          AND Document.lifecycle='ACTIVE' AND Document.deletedAt IS NULL AND NOT $HIDDEN_ATELIER_DOCUMENT
          AND Source.state='READY' AND SourceBlob.state='READY'
          AND typeof(Page.pageId)='text' AND length(CAST(Page.pageId AS BLOB))=36
          AND typeof(Document.documentId)='text' AND length(CAST(Document.documentId AS BLOB))=36
        ORDER BY Page.pageId LIMIT CASE WHEN :limit BETWEEN 1 AND 50 THEN :limit ELSE 0 END""")
    protected abstract fun selectMatches(expression: String, afterPageId: String?, limit: Int): List<OcrMatchRow>
}
