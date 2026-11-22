package app.pristinepage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Derived metadata belongs to the original bytes, not one normalized conversion or folded page revision. Cascading this projection grants no authority to reclaim the blob. */
@Entity(
    tableName = "SourceCaptureDay",
    foreignKeys = [ForeignKey(entity = SourceBlobRow::class, parentColumns = ["sourceBlobId"], childColumns = ["sourceBlobId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["epochDay", "sourceBlobId"])],
)
internal data class SourceCaptureDayRow(@PrimaryKey val sourceBlobId: String, val byteSha256: String, val epochDay: Long?)
