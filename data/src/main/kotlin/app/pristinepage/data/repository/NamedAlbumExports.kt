package app.pristinepage.data.repository

import app.pristinepage.data.db.ExportArtifactRow
import app.pristinepage.data.db.FileTransactionRow
import app.pristinepage.data.db.PublicationRow
import app.pristinepage.data.db.ShareLeaseRow
import app.pristinepage.data.files.DerivedByteLimitExceeded
import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.files.SealedDerived
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.model.*
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*

/** First foreground production of one named artifact. Retains the existing snapshot, assembly lease and private file barriers. */
class NamedAlbumExports internal constructor(private val store: PrivateImportStore.ExportComposition) {
    interface Encoder {
        suspend fun check(snapshot: ExportSnapshot): OperationError?
        suspend fun prepare(snapshot: ExportSnapshot): PreparedArtifact
    }

    interface PreparedArtifact : AutoCloseable {
        fun write(sink: OutputStream, checkpoint: () -> Unit)
        fun verify(closed: File, checkpoint: () -> Unit)
    }

    /** Frozen display filename, but no exposed filesystem path. Only the issuing store may copy this exact artifact. */
    class Ready internal constructor(
        internal val issuer: NamedAlbumExports,
        internal val artifact: ExportArtifactRow,
        internal val journal: FileTransactionRow,
        val filename: String,
    ) {
        val mime: String get() = artifact.mime
        val byteCount: Long get() = artifact.byteCount
        val sha256: String get() = artifact.byteSha256
        val artifactId: String get() = artifact.artifactId
    }

    private val dao = store.database.exportSnapshots()
    private val metadata = RoomMetadataAccess(store.database)

    suspend fun export(documentId: DocumentId, revision: Long, settings: ExportSettings, profile: OrdinaryExportProfile, encoder: Encoder): Ready =
        withContext(Dispatchers.IO) {
            storage(documentId.value) {
                if (settings.albumScope == null || settings.format == ExportFormat.PNG) refuse(documentId.value, OperationErrorCode.INVALID_INPUT, "named_export_scope_required")
                store.lifetime.retain(documentId.value) {
                    // Serialize under the same cross-process lane as existing derived-file actors, from freeze through completion.
                    store.owners.withOrdinaryDerivedLane(JobId(documentId.value)) {
                        val snapshots = RoomExportSnapshots(store.database, FileExportSourceProof(SourceFiles(store.files.canonicalRoot)),
                            ExportAdmission(encoder::check), profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision)
                        val frozen = snapshots.freezeExport(documentId, revision, settings)
                        val assembly = RoomFreshExportAssembly.forNamedAlbum(store) { snapshot, _ ->
                            if (snapshot.snapshotHash != frozen.snapshotHash) OperationError(OperationErrorCode.INTEGRITY_FAILED, false, "named_export_snapshot_mismatch", documentId.value)
                            else null
                        }
                        val admitted = assembly.admit(assembly.prepare(frozen.snapshotId))
                        store.owners.run { owner ->
                            val acquired = assembly.acquire(admitted, owner, 600_000)
                            produce(frozen, acquired, encoder)
                        }
                    }
                }
            }
        }

    private suspend fun produce(frozen: ExportSnapshot, acquired: RoomFreshExportAssembly.Acquired, encoder: Encoder): Ready {
        val context = currentCoroutineContext()
        val snapshot = frozen.snapshotId.value
        val tx = UUID.randomUUID().toString()
        val artifactId = UUID.randomUUID().toString()
        val target = DerivedFileTarget(DerivedKind.EXPORT, artifactId, snapshot)
        val owner = "named-album-v1:${acquired.lease.jobId.value}"
        val stage = "staging/$tx/derived.part"
        val final = "exports/$snapshot/$artifactId.bin"
        val payload = "pristinepage-named-album-v1\u0000${frozen.snapshotHash}\u0000$snapshot\u0000$artifactId".encodeToByteArray()
        val mime = when (frozen.settings.format) {
            ExportFormat.JPEG -> "image/jpeg"
            ExportFormat.IMAGE_ZIP -> "application/zip"
            ExportFormat.PDF -> "application/pdf"
            ExportFormat.PNG -> error("Unexpected named PNG")
        }
        return try {
            finish(frozen, acquired, encoder, context, tx, artifactId, owner, stage, final, payload, mime, target)
        } catch (failure: Throwable) {
            val code = when (failure) {
                is CancellationException -> OperationErrorCode.CANCELED
                is RepositoryFailure -> failure.error.code
                is DerivedByteLimitExceeded, is OutOfMemoryError -> OperationErrorCode.RESOURCE_LIMIT
                is IOException -> OperationErrorCode.STORAGE_FAILED
                else -> OperationErrorCode.PROCESSING_FAILED
            }
            // Files are retained unchanged for reconciliation. A late cancel cannot undo an already committed artifact.
            withContext(NonCancellable) {
                val unwind = currentCoroutineContext()
                runCatching {
                    acquired.failAlbum(unwind, code) { now ->
                        dao.failNamedAlbumIntent(tx, owner, stage, final, payload, now, code.name)
                        val terminal = if (code == OperationErrorCode.CANCELED) "CANCELED" else "FAILED"
                        if (dao.failNamedAlbumSnapshot(snapshot, frozen.snapshotHash, terminal) != 1) {
                            refuse(snapshot, OperationErrorCode.REVISION_CONFLICT, "named_export_snapshot_changed")
                        }
                    }
                }
            }
            throw failure
        }
    }

    private suspend fun finish(frozen: ExportSnapshot, acquired: RoomFreshExportAssembly.Acquired, encoder: Encoder,
        context: kotlin.coroutines.CoroutineContext, tx: String, artifactId: String, owner: String, stage: String, final: String,
        payload: ByteArray, mime: String, target: DerivedFileTarget): Ready {
        val active = { context.ensureActive() }
        val snapshot = frozen.snapshotId.value
        fun advance(previous: String, next: String, audit: Long, before: SealedDerived? = null, after: SealedDerived? = before) {
            if (dao.advanceNamedAlbumArtifact(tx, owner, stage, final, payload, previous, next, audit,
                    before?.sealSha256, before?.staged?.byteSha256, before?.staged?.byteCount,
                    after?.sealSha256, after?.staged?.byteSha256, after?.staged?.byteCount) != 1) {
                refuse(snapshot, OperationErrorCode.REVISION_CONFLICT, "named_export_journal_changed")
            }
        }
        acquired.albumTransaction(context) { now, fence ->
            dao.insertRenderIntent(FileTransactionRow(tx, "EXPORT", owner, "INTENT", stage, final,
                null, null, null, payload, 1, null, now))
            fence()
            advance("INTENT", "COPYING", now)
        }
        fun live() = acquired.albumTransaction(context) { _, _ -> }
        val sealed = encoder.prepare(frozen).use { prepared ->
            live()
            val staged = store.files.stage(tx, target, MAX_ARTIFACT_BYTES, { prepared.write(it, active) }, active)
            store.files.readStaged(staged, active) { prepared.verify(it, active) }
            store.files.sealWithFence(staged, payload, active, ::live)
        }
        acquired.albumTransaction(context) { now, _ -> advance("COPYING", "SEALED", now, after = sealed) }
        store.files.moveWithFence(sealed, active, allowVerifiedExisting = false, authorize = ::live)
        acquired.albumTransaction(context) { now, _ -> advance("SEALED", "MOVED", now, sealed) }
        val digest = sealed.staged.byteSha256
        val length = sealed.staged.byteCount
        lateinit var artifact: ExportArtifactRow
        acquired.completeAlbum(context, write = { header, now ->
            artifact = ExportArtifactRow(artifactId, snapshot, 0, mime, final, digest, length, frozen.pages.size,
                "NAMED_ALBUM_READBACK_V1", "READY", now)
            dao.insertNamedAlbumArtifact(artifact)
            advance("MOVED", "COMMITTED", now, sealed)
            if (dao.markOrdinaryPngSnapshotPrivateReady(snapshot, header.documentId, header.documentRevision, header.snapshotSha256,
                    header.settings, header.createdAt, header.retainedUntil, now) != 1) {
                refuse(snapshot, OperationErrorCode.REVISION_CONFLICT, "named_export_snapshot_changed")
            }
        }, verify = {
            if (!dao.namedAlbumJournalMatches(tx, owner, stage, final, payload, sealed.sealSha256, digest, length) ||
                !matches(artifact) || dao.exportArtifactIdsForSnapshot(snapshot, 2) != listOf(artifactId)) {
                refuse(snapshot, OperationErrorCode.INTEGRITY_FAILED, "named_export_commit_mismatch")
            }
        })
        active()
        return Ready(this, artifact, FileTransactionRow(tx, "EXPORT", owner, "COMMITTED", stage, final, sealed.sealSha256,
            digest, length, payload, 1, null, artifact.createdAt),
            AlbumExportNames(checkNotNull(frozen.settings.albumScope)).file(frozen.settings.format))
    }

    /** Reauthenticate the immutable artifact immediately before a single bounded, read-only source borrow. */
    suspend fun copy(ready: Ready, write: (InputStream, () -> Unit) -> Unit) = withContext(Dispatchers.IO) {
        require(ready.issuer === this@NamedAlbumExports)
        val artifact = ready.artifact
        val journal = ready.journal
        val context = currentCoroutineContext()
        val active = { context.ensureActive() }
        val target = DerivedFileTarget(DerivedKind.EXPORT, artifact.artifactId, artifact.snapshotId)
        storage(artifact.artifactId) {
            store.lifetime.retain(artifact.snapshotId) {
                store.owners.withOrdinaryDerivedLane(JobId(artifact.snapshotId)) {
                    val seal = checkNotNull(journal.sealSha256)
                    if (!metadata.transaction {
                        matches(artifact) && dao.namedAlbumJournalMatches(journal.transactionId, journal.ownerId,
                            checkNotNull(journal.stagingPath), artifact.relativePath, journal.payload, seal, artifact.byteSha256, artifact.byteCount)
                    }) refuse(artifact.artifactId, OperationErrorCode.INTEGRITY_FAILED, "named_export_commit_mismatch")
                    val observed = store.files.inspectBoundFinal(journal.transactionId, seal, target, MAX_ARTIFACT_BYTES, active,
                        authenticate = { sha, count, payload ->
                            require(sha == artifact.byteSha256 && count == artifact.byteCount && payload.contentEquals(journal.payload))
                        }, readback = { file, _ -> file.inputStream().buffered().use { write(it, active) } })
                    if (observed == null) refuse(artifact.artifactId, OperationErrorCode.INTEGRITY_FAILED, "named_export_missing")
                }
            }
        }
    }

    private fun matches(row: ExportArtifactRow): Boolean =
        dao.namedAlbumArtifactMatches(row.artifactId, row.snapshotId, row.relativePath, row.mime, row.byteCount, row.byteSha256, row.pageCount, System.currentTimeMillis())

    class Publication internal constructor(internal val ready: Ready, val id: String)

    suspend fun prepareSaveAs(ready: Ready): Publication = withContext(Dispatchers.IO) {
        require(ready.issuer === this@NamedAlbumExports)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        metadata.transaction {
            if (!matches(ready.artifact)) refuse(ready.artifactId, OperationErrorCode.INTEGRITY_FAILED, "named_export_commit_mismatch")
            dao.insertNamedPublication(PublicationRow(id, ready.artifactId, null, "WAITING_DESTINATION", id, false,
                ready.sha256, ready.byteCount, 0, null, null, now, now))
        }
        Publication(ready, id)
    }

    suspend fun updateSaveAs(attempt: Publication, previous: String, next: String, uri: String? = null,
        sent: Long = 0, readback: Boolean = false, error: OperationErrorCode? = null) = withContext(Dispatchers.IO) {
        val ready = attempt.ready
        require(ready.issuer === this@NamedAlbumExports && sent in 0..ready.byteCount)
        require(uri == null || uri.length <= 4096 && uri.startsWith("content://"))
        require(when (previous) {
            "WAITING_DESTINATION" -> next == "CANCELED" && uri == null || next == "WRITING" && uri != null
            "WRITING" -> uri != null && next in setOf("OUTPUT_COMPLETED", "READBACK_VERIFIED", "PARTIAL_EXTERNAL", "NEEDS_VERIFICATION")
            else -> false
        })
        require(!readback || next == "READBACK_VERIFIED" && sent == ready.byteCount)
        require(next !in setOf("OUTPUT_COMPLETED", "READBACK_VERIFIED", "NEEDS_VERIFICATION") || sent == ready.byteCount)
        metadata.transaction {
            if (!matches(ready.artifact) || dao.updateNamedPublication(attempt.id, ready.artifactId, previous, next, uri, sent,
                    ready.sha256.takeIf { readback }, error?.name, ready.sha256, ready.byteCount, System.currentTimeMillis()) != 1) {
                refuse(ready.artifactId, OperationErrorCode.REVISION_CONFLICT, "named_export_publication_changed")
            }
        }
    }

    suspend fun recordShare(ready: Ready, shareId: String, filename: String): Long = withContext(Dispatchers.IO) {
        require(ready.issuer === this@NamedAlbumExports)
        requireOpaqueId(shareId)
        require(filename == ready.filename && filename.isNotBlank() && filename.encodeToByteArray().size <= 255 &&
            filename.none { it.isISOControl() || it in "/\\:" } && !filename.startsWith("."))
        val now = System.currentTimeMillis()
        val expires = Math.addExact(now, 86_400_000L)
        metadata.transaction {
            if (!matches(ready.artifact)) refuse(ready.artifactId, OperationErrorCode.INTEGRITY_FAILED, "named_export_commit_mismatch")
            dao.insertNamedShareLease(ShareLeaseRow(shareId, ready.artifactId, "shared-exports/$shareId/$filename",
                "ACTIVE", now, expires, null))
        }
        expires
    }

    suspend fun revokeShare(ready: Ready, shareId: String) = withContext(Dispatchers.IO) {
        require(ready.issuer === this@NamedAlbumExports)
        requireOpaqueId(shareId)
        metadata.transaction {
            if (dao.revokeNamedShareLease(shareId, ready.artifactId) != 1) {
                refuse(ready.artifactId, OperationErrorCode.REVISION_CONFLICT, "named_export_share_changed")
            }
        }
    }

    suspend fun canOpenShare(shareId: String, filename: String, size: Long): Boolean = withContext(Dispatchers.IO) {
        requireOpaqueId(shareId)
        require(filename.isNotBlank() && filename.encodeToByteArray().size <= 255 && filename.none { it.isISOControl() || it in "/\\:" })
        store.lifetime.retain(shareId) {
            dao.namedShareCanOpen(shareId, "shared-exports/$shareId/$filename", size, System.currentTimeMillis())
        }
    }

    private inline fun <T> storage(id: String, action: () -> T): T = try { action() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: DerivedByteLimitExceeded) { refuse(id, OperationErrorCode.RESOURCE_LIMIT, "named_export_byte_limit") }
    catch (_: OutOfMemoryError) { refuse(id, OperationErrorCode.RESOURCE_LIMIT, "named_export_allocation") }
    catch (_: SecurityException) { refuse(id, OperationErrorCode.PERMISSION_LOST, "named_export_permission") }
    catch (_: IOException) { refuse(id, OperationErrorCode.STORAGE_FAILED, "named_export_io") }
    catch (_: Exception) { refuse(id, OperationErrorCode.INTEGRITY_FAILED, "named_export_verification_failed") }

    companion object {
        const val MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L
        private fun refuse(id: String, code: OperationErrorCode, key: String): Nothing =
            throw RepositoryFailure(OperationError(code, false, key, id))
    }
}
