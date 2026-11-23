package app.pristinepage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** The current job and the job that produced retained text can differ during a rerun. */
@Entity(
    tableName = "OcrPageWork",
    foreignKeys = [
        ForeignKey(entity = PageRow::class, parentColumns = ["pageId"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = JobRow::class, parentColumns = ["jobId"], childColumns = ["jobId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = JobRow::class, parentColumns = ["jobId"], childColumns = ["resultJobId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["jobId"], unique = true), Index(value = ["resultJobId"], unique = true)],
)
internal data class OcrPageWorkRow(
    @PrimaryKey val pageId: String,
    val jobId: String,
    val generation: Long,
    val inputSha256: String,
    val inputBytes: Int,
    val resultJobId: String?,
    val projectionVersion: Int?,
)
