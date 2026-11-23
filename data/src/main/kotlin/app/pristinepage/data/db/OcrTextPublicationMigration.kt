package app.pristinepage.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object OcrTextPublicationMigration : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // A previously frozen private artifact is not evidence the user ever picked a destination.
        db.execSQL("""CREATE TABLE IF NOT EXISTS `OcrTextPublication` (`consentId` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `byteSha256` TEXT NOT NULL, `byteCount` INTEGER NOT NULL, `state` TEXT NOT NULL, `targetUri` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`consentId`), FOREIGN KEY(`transactionId`) REFERENCES `FileTransaction`(`transactionId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_OcrTextPublication_transactionId` ON `OcrTextPublication` (`transactionId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_OcrTextPublication_state_consentId` ON `OcrTextPublication` (`state`, `consentId`)""")
    }
}

internal object OcrTextPublicationRetryMigration : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Preserve any destination attempt made by the local v6 preview. Retry inserts a fresh consent, not a new text file.
        db.execSQL("""DROP INDEX `index_OcrTextPublication_transactionId`""")
        db.execSQL("""CREATE INDEX `index_OcrTextPublication_transactionId` ON `OcrTextPublication` (`transactionId`)""")
    }
}
