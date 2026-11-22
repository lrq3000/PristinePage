package app.pristinepage.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.pristinepage.core.ocr.OcrConfiguration
import app.pristinepage.core.ocr.OcrInput
import app.pristinepage.core.ocr.OcrText
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.OcrSearchTerms
import app.pristinepage.data.repository.canonicalOcrSearchText

/** Raised only by projection preparation, before any result transaction or SQL write. */
internal class OcrProjectionLimit : IllegalArgumentException("OCR search projection exceeds its size limit")

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
            if (indexed.length > 262_144) throw OcrProjectionLimit() // Do not truncate an expanded index.
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
    // Use UPDATE for an existing page. The external-content FTS triggers remove the old tokens before indexing the new text at the same rowid.
    fun writeResult(row: OcrResultRow): Boolean = if (hasResult(row.pageId)) {
        updateResult(row.pageId, row.recipeRevision, row.sourcePixelSha256, row.engineId, row.engineVersion, row.layout,
            row.indexedText, row.verbatimText) == 1
    } else insertResult(row) > 0

    fun matchesResult(row: OcrResultRow): Boolean =
        matchesResult(row.pageId, row.recipeRevision, row.sourcePixelSha256, row.engineId, row.engineVersion, row.layout,
            row.indexedText, row.verbatimText)

    @Query("SELECT EXISTS(SELECT 1 FROM OcrResult WHERE pageId=:page)")
    protected abstract fun hasResult(page: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract fun insertResult(row: OcrResultRow): Long

    @Query("""UPDATE OcrResult SET recipeRevision=:revision,sourcePixelSha256=:sourceSha,engineId=:engine,engineVersion=:engineVersion,
        layout=:layout,indexedText=:indexed,verbatimText=:verbatim WHERE pageId=:page""")
    protected abstract fun updateResult(
        page: String, revision: Long, sourceSha: String, engine: String, engineVersion: Int,
        layout: String, indexed: String, verbatim: String?,
    ): Int

    // Bounded expected text is bound to SQL; no untrusted result text is materialized in a readback CursorWindow.
    @Query("""SELECT EXISTS (SELECT 1 FROM OcrResult WHERE pageId=:page AND recipeRevision=:revision AND sourcePixelSha256=:sourceSha
        AND engineId=:engine AND engineVersion=:engineVersion AND layout=:layout AND indexedText=:indexed AND verbatimText IS :verbatim)""")
    protected abstract fun matchesResult(
        page: String, revision: Long, sourceSha: String, engine: String, engineVersion: Int,
        layout: String, indexed: String, verbatim: String?,
    ): Boolean

    // The UTF-8 byte ceilings conservatively follow the 262,144/131,072 UTF-16-unit writer limits. Return a scalar, never either text field.
    @Query("""SELECT EXISTS (SELECT 1 FROM OcrResult WHERE pageId=:page AND recipeRevision=:revision AND sourcePixelSha256=:sourceSha
        AND engineId=:engine AND engineVersion=:engineVersion AND layout=:layout
        AND (typeof(indexedText)='text' AND length(CAST(indexedText AS BLOB))<=786432 AND instr(indexedText,char(0))=0)
        AND (verbatimText IS NULL OR (typeof(verbatimText)='text' AND length(CAST(verbatimText AS BLOB))<=393216
            AND instr(verbatimText,char(0))=0)))""")
    abstract fun matchesProducerMetadata(page: String, revision: Long, sourceSha: String, engine: String, engineVersion: Int, layout: String): Boolean

    fun matchPages(terms: OcrSearchTerms, afterPageId: PageId?, limit: Int): List<OcrMatchRow> =
        selectMatches(terms.expression, afterPageId?.value, limit)

    // IDs must be representable as typed UUIDs before LIMIT, or one malformed candidate can prevent the batch from advancing. This prefilter does not validate the decoded Atelier receipt or grant navigation authority.
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
          AND (Page.pageId GLOB '????????-????-????-????-????????????' AND length(replace(Page.pageId,'-',''))=32
            AND Page.pageId NOT GLOB '*[^0-9a-f-]*')
          AND typeof(Document.documentId)='text' AND length(CAST(Document.documentId AS BLOB))=36
          AND (Document.documentId GLOB '????????-????-????-????-????????????' AND length(replace(Document.documentId,'-',''))=32
            AND Document.documentId NOT GLOB '*[^0-9a-f-]*')
        ORDER BY Page.pageId LIMIT CASE WHEN :limit BETWEEN 1 AND 50 THEN :limit ELSE 0 END""")
    protected abstract fun selectMatches(expression: String, afterPageId: String?, limit: Int): List<OcrMatchRow>
}
