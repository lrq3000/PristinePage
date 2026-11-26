package app.pristinepage.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object LibraryScanNameMigration : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Preserve app-local originals and pages during development upgrades. This does not recognize an older portable format or invent historical filenames.
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_Page_pageId_documentId` ON `Page` (`pageId`, `documentId`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `LibraryScanName` (`pageId` TEXT NOT NULL, `documentId` TEXT NOT NULL, `filename` TEXT NOT NULL, `comparisonKey` TEXT NOT NULL, PRIMARY KEY(`pageId`), FOREIGN KEY(`pageId`, `documentId`) REFERENCES `Page`(`pageId`, `documentId`) ON UPDATE NO ACTION ON DELETE CASCADE )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_LibraryScanName_pageId_documentId` ON `LibraryScanName` (`pageId`, `documentId`)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_LibraryScanName_documentId_comparisonKey` ON `LibraryScanName` (`documentId`, `comparisonKey`)""")
    }
}
