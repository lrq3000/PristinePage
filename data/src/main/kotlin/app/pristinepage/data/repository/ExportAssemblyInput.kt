package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.db.JobRow
import app.pristinepage.data.model.ExportSnapshot
import app.pristinepage.data.model.ExportSnapshotId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections

/** The persisted v1 assembly intent contains references in frozen ordinal order, not proof of the jobs they reference. */
internal object ExportAssemblyInput {
    private val tag = "pristinepage-export-assembly-v1\u0000".toByteArray(Charsets.UTF_8)
    private const val UUID_BYTES = 36
    private const val HASH_BYTES = 64
    private const val MAX_PAGES = 100
    private const val JOB_BYTES = Int.SIZE_BYTES + UUID_BYTES
    private val headerBytes = tag.size + Int.SIZE_BYTES + UUID_BYTES + Int.SIZE_BYTES + HASH_BYTES + Int.SIZE_BYTES

    fun key(snapshotId: ExportSnapshotId) = "export-assembly-v1/${snapshotId.value}"

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun encode(snapshotId: ExportSnapshotId, snapshotHash: String, jobs: List<JobId>): ByteArray {
        requireSha256(snapshotHash)
        require(jobs.size in 1..MAX_PAGES && jobs.toSet().size == jobs.size)
        val output = ByteBuffer.allocate(sizeFor(jobs.size))
        output.put(tag)
        output.putText(snapshotId.value)
        output.putText(snapshotHash)
        output.putInt(jobs.size)
        for (job in jobs) output.putText(job.value)
        return output.array()
    }

    /** Bind an already materialized row to a separately verified snapshot and expected job ID. This is not a raw storage guard, admission check, state transition or lease fence. */
    fun verify(expectedJobId: JobId, snapshot: ExportSnapshot, row: JobRow): List<JobId> {
        try {
            require(row.jobId == expectedJobId.value && row.kind == "EXPORT_ASSEMBLY")
            require(row.idempotenceKey == key(snapshot.snapshotId) && row.snapshotId == snapshot.snapshotId.value && row.pageOrdinal == null)
            require(row.input.size in sizeFor(1)..sizeFor(MAX_PAGES))
            // Verify and parse the same bounded copy of the materialized Room BLOB; do not retain it or allocate from a declared length.
            val bytes = row.input.copyOf()
            require(row.canonicalInputSha256 == digest(bytes))
            val input = ByteBuffer.wrap(bytes)
            require(tag.all { it == input.get() })
            require(ExportSnapshotId(input.fixedText(UUID_BYTES)) == snapshot.snapshotId)
            val hash = input.fixedText(HASH_BYTES)
            requireSha256(hash)
            require(hash == snapshot.snapshotHash)
            val count = input.int
            require(count in 1..MAX_PAGES && count == snapshot.pages.size && bytes.size == sizeFor(count))
            val jobs = List(count) { JobId(input.fixedText(UUID_BYTES)) }
            require(jobs.toSet().size == count)
            return Collections.unmodifiableList(jobs)
        } catch (_: IllegalArgumentException) {
            throw RepositoryFailure(OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "export_assembly_input_invalid", expectedJobId.value))
        }
    }

    private fun sizeFor(count: Int) = headerBytes + JOB_BYTES * count

    private fun ByteBuffer.putText(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        putInt(bytes.size)
        put(bytes)
    }

    private fun ByteBuffer.fixedText(size: Int): String {
        require(int == size)
        // The caller verifies the full header/body extent first. Canonical UUIDs and hashes are ASCII, so replacement/non-ASCII characters cannot pass validation.
        val bytes = ByteArray(size)
        get(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
