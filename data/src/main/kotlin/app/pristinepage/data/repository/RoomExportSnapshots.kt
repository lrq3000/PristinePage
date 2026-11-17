package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Ordinary mask-free snapshot publication. Proof/admission suspend outside Room; the final transaction rechecks exact frozen inputs before its first write. Neither PENDING jobs nor FROZEN rows claim rendering, codec execution or publication. Unsupported retention paths fail closed instead of shedding dependencies. */
class RoomExportSnapshots @JvmOverloads constructor(private val metadata: MetadataAccess, private val queries: ExportSnapshotQueries, private val proof: ExportSourceProof, private val admission: ExportAdmission, private val rendererVersion: String, private val encodingVersion: String, private val backend: String, private val precision: String, private val clock: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ExportSnapshots {
    constructor(database: PristineDatabase, proof: ExportSourceProof, admission: ExportAdmission, rendererVersion: String, encodingVersion: String, backend: String, precision: String) : this(RoomMetadataAccess(database), database.exportSnapshots(), proof, admission, rendererVersion, encodingVersion, backend, precision)
    private val q get() = metadata.queries
    private val profile = OrdinaryExportProfile(rendererVersion,encodingVersion,backend,precision)
    private data class Prepared(val rows: List<ExportPageRow>, val bindings: List<ExportSourceBinding>, val identity: FrozenExportIdentity)

    /** Read-only metadata retrieval, not execution admission or file readiness. The transaction reads only immutable snapshot rows; later album deletion/edits never select substitute recipes. A consuming worker must still validate its job state, leases, files, provider/codec/resource capabilities before execution. */
    override suspend fun load(snapshotId: ExportSnapshotId): ExportSnapshot = withContext(dispatcher) {
        try {
            coroutineContext.ensureActive()
            val (record, pages) = metadata.transaction {
                val bounded = queries.snapshotMetadataIsBounded(snapshotId.value) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "export_snapshot_unavailable", snapshotId.value)
                if (!bounded) reject(OperationErrorCode.INTEGRITY_FAILED, "export_snapshot_metadata_invalid", snapshotId.value)
                val record = queries.snapshot(snapshotId.value) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "export_snapshot_unavailable", snapshotId.value)
                if (record.snapshotId != snapshotId.value) reject(OperationErrorCode.INTEGRITY_FAILED, "export_snapshot_identity_mismatch", snapshotId.value)
                verifyLoadable(record, snapshotId)
                if (!queries.exportPagesAreBounded(snapshotId.value)) reject(OperationErrorCode.INTEGRITY_FAILED, "export_snapshot_metadata_invalid", snapshotId.value)
                val pages = queries.exportPages(snapshotId.value)
                record to pages
            }
            coroutineContext.ensureActive()
            val settings = try { ExportSettingsCodec.decode(record.settings) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.UNSUPPORTED_VERSION, "export_snapshot_settings_unsupported", snapshotId.value) }
            if (!record.settings.contentEquals(ExportSettingsCodec.encode(settings))) reject(OperationErrorCode.INTEGRITY_FAILED, "export_snapshot_settings_noncanonical", snapshotId.value)
            if (pages.any { it.recipeSchemaVersion != 1 }) reject(OperationErrorCode.UNSUPPORTED_VERSION, "export_snapshot_recipe_unsupported", snapshotId.value)
            val identity = try {
                require(pages.all { it.snapshotId == snapshotId.value })
                ExportSnapshotIdentity.create(DocumentId(record.documentId), record.documentRevision, settings, pages)
            } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "export_snapshot_content_invalid", snapshotId.value) }
            if (identity.snapshotHash != record.snapshotSha256) reject(OperationErrorCode.INTEGRITY_FAILED, "export_snapshot_identity_mismatch", snapshotId.value)
            coroutineContext.ensureActive()
            ExportSnapshot(snapshotId, DocumentId(record.documentId), record.documentRevision, identity.snapshotHash, settings, pages.map(::ExportPageSnapshot), record.createdAt)
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { reject(OperationErrorCode.STORAGE_FAILED, "export_snapshot_read_failed", snapshotId.value, retryable = true) }
    }

    private fun verifyLoadable(record: ExportSnapshotRow, snapshotId: ExportSnapshotId) {
        when (record.state) {
            "FROZEN", "RENDERING", "ASSEMBLING", "PRIVATE_READY" -> Unit
            "CANCELED" -> reject(OperationErrorCode.CANCELED, "export_snapshot_canceled", snapshotId.value)
            "FAILED" -> reject(OperationErrorCode.PROCESSING_FAILED, "export_snapshot_failed", snapshotId.value)
            "EXPIRED" -> reject(OperationErrorCode.SESSION_EXPIRED, "export_snapshot_expired", snapshotId.value)
            else -> reject(OperationErrorCode.UNSUPPORTED_VERSION, "export_snapshot_state_unsupported", snapshotId.value)
        }
        if (record.retainedUntil <= clock()) reject(OperationErrorCode.SESSION_EXPIRED, "export_snapshot_expired", snapshotId.value)
    }

    override suspend fun freezeExport(documentId: DocumentId, expectedDocumentRevision: Long, settings: ExportSettings): ExportSnapshot = withContext(dispatcher) {
        try {
            requireOpaqueId(documentId.value)
            if (expectedDocumentRevision < 1) reject(OperationErrorCode.INVALID_INPUT, "invalid_document_revision", documentId.value)
            val snapshotId = ExportSnapshotId(uuid())
            coroutineContext.ensureActive()
            val prepared = metadata.transaction { prepare(snapshotId, documentId, expectedDocumentRevision, settings) }
            for (binding in prepared.bindings.distinctBy { it.source.sourceId }) {
                coroutineContext.ensureActive(); proof.verify(binding); coroutineContext.ensureActive()
            }
            val now = clock()
            val snapshot = value(snapshotId, documentId, expectedDocumentRevision, settings, prepared, now)
            admission.check(snapshot)?.let { throw RepositoryFailure(it) }
            coroutineContext.ensureActive()
            metadata.transaction {
                val live = prepare(snapshotId, documentId, expectedDocumentRevision, settings)
                if (!live.identity.canonicalInput.contentEquals(prepared.identity.canonicalInput) || !sameBindings(prepared.bindings, live.bindings)) reject(OperationErrorCode.INTEGRITY_FAILED, "export_inputs_changed", documentId.value)
                // Capture the owning coroutine context, not a newly-created Job. Known cancellation during preflight cannot cause an invisible snapshot publication.
                coroutineContext.ensureActive()
                queries.insertSnapshot(ExportSnapshotRow(snapshotId.value, documentId.value, expectedDocumentRevision, prepared.identity.snapshotHash, ExportSettingsCodec.encode(settings), "FROZEN", now, Long.MAX_VALUE))
                val jobIds = ArrayList<String>()
                for (page in prepared.rows) {
                    queries.insertExportPage(page)
                    pin("EXPORT_PAGE", "export-page-v1/${snapshotId.value}/${page.ordinal}", page.sourceId, now)
                    val recipe = RecipeCodec.decode(page.recipe)
                    val request = RenderRequest(page.sourceId, page.pixelSha256, page.originalPageId, page.recipeRevision, recipe, settings.pages[page.ordinal].output, 0, null, FallbackPolicy.FAIL)
                    val identity = RenderJobIdentity.create(request, emptyMap(), page.assetSetSha256, page.conversionFingerprint, rendererVersion, encodingVersion, backend, precision)
                    val existing = queries.renderJobByKey(identity.idempotenceKey)
                    val jobId = if (existing == null) {
                        val id = uuid()
                        queries.insertExportJob(job(id, "PAGE_RENDER", identity.idempotenceKey, identity.canonicalInputSha256, identity.canonicalInput, null, now))
                        pin("JOB", id, page.sourceId, now); id
                    } else {
                        if (existing.kind != "PAGE_RENDER" || existing.snapshotId != null || existing.pageOrdinal != null || existing.canonicalInputSha256 != identity.canonicalInputSha256 || !existing.input.contentEquals(identity.canonicalInput)) reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_identity_mismatch", existing.jobId)
                        requireOpaqueId(existing.jobId)
                        verifyJobSource(existing, page)
                        // A byte-identical render may be shared by equivalent source-row owners. Its immutable input source is retained by its original JOB pin; each export page independently pins its own source.
                        existing.jobId
                    }
                    jobIds += jobId
                }
                val input = assemblyInput(snapshotId, prepared.identity.snapshotHash, jobIds)
                queries.insertExportJob(job(uuid(), "EXPORT_ASSEMBLY", "export-assembly-v1/${snapshotId.value}", sha(input), input, snapshotId.value, now))
                snapshot
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { reject(OperationErrorCode.STORAGE_FAILED, "export_freeze_failed", retryable = true) }
    }

    private fun prepare(snapshotId: ExportSnapshotId, documentId: DocumentId, revision: Long, settings: ExportSettings): Prepared {
        val document = q.document(documentId.value) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", documentId.value)
        if (document.lifecycle != "ACTIVE" || document.documentRevision != revision || q.hasPendingImport(documentId.value)) reject(OperationErrorCode.REVISION_CONFLICT, "export_document_changed", documentId.value)
        val pages = q.activePages(documentId.value)
        if (pages.size !in 1..100 || pages.size != settings.pages.size || pages.map { it.pageId }.toSet().size != pages.size) reject(OperationErrorCode.INVALID_INPUT, "invalid_export_pages", documentId.value)
        val bindings = ArrayList<ExportSourceBinding>()
        val rows = pages.mapIndexed { ordinal, page ->
            if (page.documentId != documentId.value || page.state != "ACTIVE" || page.ordinal != ordinal || page.pageId != settings.pages[ordinal].pageId.value || page.currentRecipeRevision < 1) reject(OperationErrorCode.REVISION_CONFLICT, "export_pages_changed", documentId.value)
            val source = q.source(page.sourceId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", page.sourceId)
            val blob = q.sourceBlob(source.sourceBlobId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", page.sourceId)
            if (source.state != "READY" || blob.state != "READY" || source.sourceId != page.sourceId || blob.sourceBlobId != source.sourceBlobId || source.refCount < 0 || blob.sourceCount < 1) reject(OperationErrorCode.INTEGRITY_FAILED, "source_not_ready", page.sourceId)
            if (blob.byteCount !in 1..104857600 || blob.relativePath != "sources/${blob.sourceBlobId}/original.bin") reject(OperationErrorCode.INTEGRITY_FAILED, "source_binding_invalid", page.sourceId)
            val stored = q.recipe(page.pageId, page.currentRecipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId)
            if (stored.recipeSchemaVersion != 1) reject(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unreadable", page.pageId)
            val recipe = try { RecipeCodec.decode(stored.canonicalRecipe) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unreadable", page.pageId) }
            if (stored.pageId != page.pageId || stored.sourceId != source.sourceId || stored.recipeRevision != page.currentRecipeRevision) reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_binding_invalid", page.pageId)
            // Do not omit masks/assets or rerun an accepted failure because their full proof/pin-transfer protocols are not wired in this first ordinary path.
            if (q.recipeMasks(page.pageId, page.currentRecipeRevision).isNotEmpty()) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_mask_freeze_unavailable", page.pageId)
            if (recipe.geometry is GeometryRecipe.RoughCropDewarp || recipe.appearance is AppearanceRecipe.ProviderAppearanceV1) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_provider_freeze_unavailable", page.pageId)
            val output = settings.pages[ordinal]
            if (output.fallbackPolicy != FallbackPolicy.FAIL) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "accepted_render_freeze_unavailable", page.pageId)
            bindings += ExportSourceBinding(source, blob)
            ExportPageRow(snapshotId.value, ordinal, page.pageId, source.sourceId, blob.sourceBlobId, blob.byteSha256, source.pixelSha256, source.width, source.height, source.conversionFingerprint, stored.recipeRevision, stored.recipeSchemaVersion, stored.canonicalRecipe.copyOf(), stored.recipeSha256, "[]".toByteArray(), profile.providerDescriptor(), OrdinaryExportProfile.emptyAssetSetHash, rendererVersion, output.requestedOutput.size.width, output.requestedOutput.size.height, output.output.size.width, output.output.size.height, output.fallbackPolicy.name, null, null)
        }
        val identity = try { ExportSnapshotIdentity.create(documentId, revision, settings, rows) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "export_metadata_invalid", documentId.value) }
        return Prepared(rows, bindings, identity)
    }

    private fun sameBindings(before: List<ExportSourceBinding>, after: List<ExportSourceBinding>): Boolean = before.size == after.size && before.zip(after).all { (a, b) -> a.source.copy(refCount = 0) == b.source.copy(refCount = 0) && a.blob.copy(sourceCount = 0) == b.blob.copy(sourceCount = 0) }
    private fun verifyJobSource(job: JobRow, page: ExportPageRow) {
        val reference = queries.jobSourceReferences(job.jobId).singleOrNull() ?: reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_source_missing", job.jobId)
        val source = q.source(reference.sourceId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_source_missing", job.jobId)
        val blob = q.sourceBlob(source.sourceBlobId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_source_missing", job.jobId)
        if (reference.ownerType != "JOB" || reference.ownerId != job.jobId || source.sourceId != reference.sourceId || source.state != "READY" || blob.state != "READY" || source.pixelSha256 != page.pixelSha256 || source.width != page.width || source.height != page.height || source.conversionFingerprint != page.conversionFingerprint) reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_source_mismatch", job.jobId)
    }
    private fun value(id: ExportSnapshotId, document: DocumentId, revision: Long, settings: ExportSettings, prepared: Prepared, now: Long) = ExportSnapshot(id, document, revision, prepared.identity.snapshotHash, settings, prepared.rows.map(::ExportPageSnapshot), now)
    private fun pin(type: String, owner: String, source: String, now: Long) {
        if (q.insertReference(SourceReferenceRow(type, owner, source, now)) == -1L) reject(OperationErrorCode.INTEGRITY_FAILED, "export_pin_already_exists", source)
        val count = q.countSourceReferences(source)
        if (count < 1 || q.setSourceRefCount(source, count) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", source)
    }
    private fun assemblyInput(snapshot: ExportSnapshotId, hash: String, jobs: List<String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String) { val data = value.toByteArray(Charsets.UTF_8); output.writeInt(data.size); output.write(data) }
            output.write("pristinepage-export-assembly-v1\u0000".toByteArray()); text(snapshot.value); text(hash); output.writeInt(jobs.size); jobs.forEach(::text)
        }
        return bytes.toByteArray()
    }
    private fun job(id: String, kind: String, key: String, hash: String, input: ByteArray, snapshot: String?, now: Long) = JobRow(id, kind, key, hash, snapshot, null, input, "PENDING", 0, null, null, 0, null, null, now, now)
    private fun uuid() = UUID.randomUUID().toString()
    private fun reject(code: OperationErrorCode, key: String, id: String? = null, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, id))
    private companion object {
        fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
