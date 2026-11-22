package app.pristinepage.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object SourceCaptureDayMigration : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Older blobs have no authenticated day projection. Never synthesize dates from file or album timestamps during migration.
        db.execSQL("""CREATE TABLE IF NOT EXISTS `SourceCaptureDay` (`sourceBlobId` TEXT NOT NULL, `byteSha256` TEXT NOT NULL, `epochDay` INTEGER, PRIMARY KEY(`sourceBlobId`), FOREIGN KEY(`sourceBlobId`) REFERENCES `SourceBlob`(`sourceBlobId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_SourceCaptureDay_epochDay_sourceBlobId` ON `SourceCaptureDay` (`epochDay`, `sourceBlobId`)""")
    }
}
