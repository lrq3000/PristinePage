package app.pristinepage.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object OcrWorkMigration : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No migration may invent an active job or attribute unbound historical text to an engine or pack.
        db.execSQL("""CREATE TABLE IF NOT EXISTS `OcrPageWork` (`pageId` TEXT NOT NULL, `jobId` TEXT NOT NULL, `generation` INTEGER NOT NULL, `inputSha256` TEXT NOT NULL, `inputBytes` INTEGER NOT NULL, `resultJobId` TEXT, `projectionVersion` INTEGER, PRIMARY KEY(`pageId`), FOREIGN KEY(`pageId`) REFERENCES `Page`(`pageId`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`jobId`) REFERENCES `Job`(`jobId`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`resultJobId`) REFERENCES `Job`(`jobId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_OcrPageWork_jobId` ON `OcrPageWork` (`jobId`)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_OcrPageWork_resultJobId` ON `OcrPageWork` (`resultJobId`)""")
    }
}
