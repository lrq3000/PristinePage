package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.pristinepage.data.repository.ExportSnapshotQueries

@Dao abstract class ExportSnapshotDao : ExportSnapshotQueries {
    @Query("SELECT * FROM ExportSnapshot WHERE snapshotId=:id") abstract override fun snapshot(id: String): ExportSnapshotRow?
    @Query("SELECT * FROM ExportPage WHERE snapshotId=:snapshotId ORDER BY ordinal LIMIT 101") abstract override fun exportPages(snapshotId: String): List<ExportPageRow>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertSnapshot(row: ExportSnapshotRow)
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertExportPage(row: ExportPageRow)
    @Query("SELECT * FROM Job WHERE idempotenceKey=:key") abstract override fun renderJobByKey(key: String): JobRow?
    // Ordinary page jobs have exactly one source owner. A second row is sufficient to expose invalid cardinality without loading an unbounded corrupt owner set.
    @Query("SELECT * FROM SourceReference WHERE ownerType='JOB' AND ownerId=:jobId LIMIT 2") abstract override fun jobSourceReferences(jobId: String): List<SourceReferenceRow>
    @Insert(onConflict = OnConflictStrategy.ABORT) abstract override fun insertExportJob(row: JobRow)
}
