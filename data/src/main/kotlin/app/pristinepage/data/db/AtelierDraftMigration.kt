package app.pristinepage.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object AtelierDraftMigration : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Staging is an additive classification. No existing document, recipe, source or journal is rewritten.
        db.execSQL("""CREATE TABLE IF NOT EXISTS `AtelierDraft` (`draftId` TEXT NOT NULL, `stagingDocumentId` TEXT NOT NULL, `targetDocumentId` TEXT, `targetRevision` INTEGER, `state` TEXT NOT NULL, `generation` INTEGER NOT NULL, `step` INTEGER NOT NULL, `selectedPageId` TEXT, `committedDocumentId` TEXT, `createdAt` INTEGER NOT NULL, `activeBatchId` TEXT, `receiptPageIds` TEXT, PRIMARY KEY(`draftId`), FOREIGN KEY(`stagingDocumentId`) REFERENCES `Document`(`documentId`) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("""CREATE UNIQUE INDEX IF NOT EXISTS `index_AtelierDraft_stagingDocumentId` ON `AtelierDraft` (`stagingDocumentId`)""")
    }
}
