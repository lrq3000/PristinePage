package app.pristinepage.data.repository

import app.pristinepage.data.db.*
import app.pristinepage.data.model.*

/** Same-database participants: every call is inside the supplied MetadataAccess transaction, including unique-key lookup and insertion. */
interface ExportSnapshotQueries {
    fun snapshotMetadataIsBounded(snapshotId: String): Boolean?
    fun exportPagesAreBounded(snapshotId: String): Boolean
    fun snapshot(id: String): ExportSnapshotRow?
    fun exportPages(snapshotId: String): List<ExportPageRow>
    fun insertSnapshot(row: ExportSnapshotRow)
    fun insertExportPage(row: ExportPageRow)
    /** Null is an exact key miss; an invalid matched ID is returned as a non-null sentinel. */
    fun renderJobIdByKey(key: String): String?
    fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int): Boolean?
    /** Only after the exact by-ID guard admits the row in this transaction. */
    fun renderJobIdentity(jobId: String): FrozenRenderJobIdentityRow?
    fun boundedRenderJobPinSourceIds(jobId: String): List<String>
    fun insertExportJob(row: JobRow)
}

/** Closed immutable original's recorded identity. Proof verifies exactly this private path/length/hash outside Room; it does not decode or reassign the source. */
data class ExportSourceBinding(val source: SourceRow, val blob: SourceBlobRow)

fun interface ExportSourceProof {
    suspend fun verify(binding: ExportSourceBinding)
}

/** Renderer/codec/geometry/rights and whole-document resource preflight, never default-success. A null error admits this exact immutable request, not future changed dimensions/profile/assets. Runs outside Room and must not modify its snapshot. */
fun interface ExportAdmission {
    suspend fun check(snapshot: ExportSnapshot): OperationError?
}

/** Borrows the authenticated snapshot and a detached, unmodifiable list of its proved original bindings, one per frozen ordinal even for repeats. Runs outside Room after closed-byte proof; these are the snapshot's originals, not any reused render job's pin. */
internal fun interface ExportBindingAdmission {
    suspend fun check(snapshot: ExportSnapshot, originalsInFrozenOrder: List<ExportSourceBinding>): OperationError?
}

interface ExportSnapshots {
    suspend fun freezeExport(documentId: DocumentId, expectedDocumentRevision: Long, settings: ExportSettings): ExportSnapshot
    suspend fun load(snapshotId: ExportSnapshotId): ExportSnapshot
}
