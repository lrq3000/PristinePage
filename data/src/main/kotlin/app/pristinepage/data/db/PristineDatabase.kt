package app.pristinepage.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/** Initial complete relational shape. No destructive migration fallback or filesystem mutation lives here. Repository transactions and Android integration must enforce/reverify the remaining lifecycle/value invariants before T-03 acceptance. */
@Database(entities = [DocumentRow::class, SourceBlobRow::class, SourceRow::class, PageRow::class, RecipeRevisionRow::class, ImportSessionRow::class, UndoEntryRow::class, ConfirmationRow::class, MaskRow::class, RecipeMaskRow::class, SourceReferenceRow::class, MaskReferenceRow::class, AssetPinRow::class, RenderReferenceRow::class, ExportSnapshotRow::class, ExportPageRow::class, JobRow::class, RenderedPageRow::class, ExportArtifactRow::class, FileTransactionRow::class, PublicationRow::class, ShareLeaseRow::class], version = 1, exportSchema = true)
abstract class PristineDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun importSessions(): ImportSessionDao
    abstract fun jobLeases(): JobLeaseDao
    abstract fun ingestPreparation(): IngestPreparationDao
    abstract fun ingestCompletion(): IngestCompletionDao
    abstract fun ingestPhases(): IngestPhaseDao
    abstract fun ingestRecovery(): IngestRecoveryDao
    abstract fun referenceAudit(): ReferenceAuditDao
    abstract fun exportSnapshots(): ExportSnapshotDao
}
