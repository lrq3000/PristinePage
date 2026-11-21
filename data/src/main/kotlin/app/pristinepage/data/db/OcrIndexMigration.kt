package app.pristinepage.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object OcrIndexMigration : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Results start empty. Room installs the external-content sync triggers after migration; no preexisting page has recognized text to backfill here.
        db.execSQL("""CREATE TABLE IF NOT EXISTS `OcrResult` (`rowid` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pageId` TEXT NOT NULL, `recipeRevision` INTEGER NOT NULL, `sourcePixelSha256` TEXT NOT NULL, `engineId` TEXT NOT NULL, `engineVersion` INTEGER NOT NULL, `layout` TEXT NOT NULL, `indexedText` TEXT NOT NULL, `verbatimText` TEXT, FOREIGN KEY(`pageId`) REFERENCES `Page`(`pageId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_OcrResult_pageId` ON `OcrResult` (`pageId`)""")
        db.execSQL("""CREATE VIRTUAL TABLE IF NOT EXISTS `OcrResultFts` USING FTS4(`indexedText` TEXT NOT NULL, tokenize=unicode61, content=`OcrResult`)""")
    }
}
