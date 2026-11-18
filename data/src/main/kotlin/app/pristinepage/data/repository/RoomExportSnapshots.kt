package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.AppearanceRecipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
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
                val jobIds = ArrayList<JobId>()
                for (page in prepared.rows) {
                    queries.insertExportPage(page)
                    pin("EXPORT_PAGE", "export-page-v1/${snapshotId.value}/${page.ordinal}", page.sourceId, now)
                    val recipe = RecipeCodec.decode(page.recipe)
                    val request = RenderRequest(page.sourceId, page.pixelSha256, page.originalPageId, page.recipeRevision, recipe, settings.pages[page.ordinal].output, 0, null, FallbackPolicy.FAIL)
                    val identity = RenderJobIdentity.create(request, emptyMap(), page.assetSetSha256, page.conversionFingerprint, rendererVersion, encodingVersion, backend, precision)
                    val input = identity.canonicalInput
                    val existing = queries.renderJobIdByKey(identity.idempotenceKey)
                    val jobId = if (existing == null) {
                        val id = uuid()
                        queries.insertExportJob(job(id, "PAGE_RENDER", identity.idempotenceKey, identity.canonicalInputSha256, input, null, now))
                        pin("JOB", id, page.sourceId, now); id
                    } else {
                        verifyReusedJob(existing, identity, input, snapshot.pages[page.ordinal])
                        existing
                    }
                    jobIds += JobId(jobId)
                }
                val input = ExportAssemblyInput.encode(snapshotId, prepared.identity.snapshotHash, jobIds)
                queries.insertExportJob(job(uuid(), "EXPORT_ASSEMBLY", ExportAssemblyInput.key(snapshotId), ExportAssemblyInput.digest(input), input, snapshotId.value, now))
                snapshot
            }
        } catch (canceled: CancellationException) { throw canceled }
        catch (failure: RepositoryFailure) { throw failure }
        catch (_: Exception) { reject(OperationErrorCode.STORAGE_FAILED, "export_freeze_failed", retryable = true) }
    }

    private fun prepare(snapshotId: ExportSnapshotId, documentId: DocumentId, revision: Long, settings: ExportSettings): Prepared {
        val q = metadata.queries
        val bounds = q as? EditablePageQueries ?: reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "metadata_guards_unavailable", documentId.value)
        when (bounds.documentReadIsBounded(documentId.value)) {
            null -> reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", documentId.value)
            false -> reject(OperationErrorCode.INTEGRITY_FAILED, "export_document_metadata_invalid", documentId.value)
            true -> Unit
        }
        val document = q.document(documentId.value) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "export_document_metadata_invalid", documentId.value)
        if (document.documentId != documentId.value) reject(OperationErrorCode.INTEGRITY_FAILED, "export_document_metadata_invalid", documentId.value)
        if (document.lifecycle != "ACTIVE" || document.documentRevision != revision || q.hasPendingImport(documentId.value)) reject(OperationErrorCode.REVISION_CONFLICT, "export_document_changed", documentId.value)
        val count = bounds.activePageCount(documentId.value)
        if (count !in 1L..100L) reject(OperationErrorCode.INVALID_INPUT, "invalid_export_pages", documentId.value)
        if (!bounds.activePagesReadIsBounded(documentId.value)) reject(OperationErrorCode.INTEGRITY_FAILED, "export_page_metadata_invalid", documentId.value)
        if (count != settings.pages.size.toLong()) reject(OperationErrorCode.INVALID_INPUT, "invalid_export_pages", documentId.value)
        val pages = q.activePages(documentId.value)
        if (pages.size.toLong() != count || pages.map { it.pageId }.toSet().size != pages.size) reject(OperationErrorCode.INTEGRITY_FAILED, "export_page_metadata_invalid", documentId.value)

        val masks = "[]".toByteArray()
        val providers = profile.providerDescriptor()
        val budget = ExportSnapshotIdentity.MetadataBudget(ExportSettingsCodec.encode(settings).size)
        // Both preparations run this bounded pass inside their own transaction. Admit the entire exact reviewed set before the first recipe BLOB projection; indexed revision lookups neither scan nor count retained history.
        for ((ordinal, page) in pages.withIndex()) {
            if (page.documentId != documentId.value || page.state != "ACTIVE" || page.ordinal != ordinal || page.currentRecipeRevision < 1) reject(OperationErrorCode.INTEGRITY_FAILED, "export_page_metadata_invalid", documentId.value)
            if (page.pageId != settings.pages[ordinal].pageId.value) reject(OperationErrorCode.REVISION_CONFLICT, "export_pages_changed", documentId.value)
            try { requireOpaqueId(page.sourceId) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "export_page_metadata_invalid", documentId.value) }
            when (bounds.sourceReadIsBounded(page.sourceId)) {
                null -> reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", page.sourceId)
                false -> {
                    val reason = if (bounds.sourceBlobLinkIsMissing(page.sourceId) == true) "source_unavailable" else "export_source_metadata_invalid"
                    reject(OperationErrorCode.INTEGRITY_FAILED, reason, page.sourceId)
                }
                true -> Unit
            }
            when (bounds.recipeReadIsBounded(page.pageId, page.currentRecipeRevision)) {
                null -> reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId)
                false -> reject(OperationErrorCode.INTEGRITY_FAILED, "export_recipe_metadata_invalid", page.pageId)
                true -> Unit
            }
            val extent = bounds.recipeBlobByteCount(page.pageId, page.currentRecipeRevision)
                ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId)
            try {
                budget.addPage(extent, masks.size, providers.size)
            } catch (_: IllegalArgumentException) {
                reject(OperationErrorCode.INTEGRITY_FAILED, "export_metadata_invalid", documentId.value)
            }
        }

        val bindings = ArrayList<ExportSourceBinding>()
        val rows = pages.mapIndexed { ordinal, page ->
            val source = q.source(page.sourceId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", page.sourceId)
            val blob = q.sourceBlob(source.sourceBlobId) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", page.sourceId)
            if (source.state != "READY" || blob.state != "READY" || source.sourceId != page.sourceId || blob.sourceBlobId != source.sourceBlobId || source.refCount < 0 || blob.sourceCount < 1) reject(OperationErrorCode.INTEGRITY_FAILED, "source_not_ready", page.sourceId)
            if (blob.byteCount !in 1..104857600 || blob.relativePath != "sources/${blob.sourceBlobId}/original.bin") reject(OperationErrorCode.INTEGRITY_FAILED, "source_binding_invalid", page.sourceId)
            val stored = q.recipe(page.pageId, page.currentRecipeRevision) ?: reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_history_missing", page.pageId)
            if (stored.recipeSchemaVersion != 1) reject(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unreadable", page.pageId)
            val recipe = try { RecipeCodec.decode(stored.canonicalRecipe) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.UNSUPPORTED_VERSION, "stored_recipe_unreadable", page.pageId) }
            if (stored.pageId != page.pageId || stored.sourceId != source.sourceId || stored.recipeRevision != page.currentRecipeRevision) reject(OperationErrorCode.INTEGRITY_FAILED, "recipe_binding_invalid", page.pageId)
            // Do not omit masks/assets or rerun an accepted failure because their full proof/pin-transfer protocols are not wired in this first ordinary path.
            if (bounds.recipeHasMasks(page.pageId, page.currentRecipeRevision)) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_mask_freeze_unavailable", page.pageId)
            if (recipe.geometry is GeometryRecipe.RoughCropDewarp || recipe.appearance is AppearanceRecipe.ProviderAppearanceV1) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "export_provider_freeze_unavailable", page.pageId)
            val output = settings.pages[ordinal]
            if (output.fallbackPolicy != FallbackPolicy.FAIL) reject(OperationErrorCode.PROVIDER_UNAVAILABLE, "accepted_render_freeze_unavailable", page.pageId)
            bindings += ExportSourceBinding(source, blob)
            ExportPageRow(snapshotId.value, ordinal, page.pageId, source.sourceId, blob.sourceBlobId, blob.byteSha256, source.pixelSha256, source.width, source.height, source.conversionFingerprint, stored.recipeRevision, stored.recipeSchemaVersion, stored.canonicalRecipe.copyOf(), stored.recipeSha256, masks.copyOf(), providers.copyOf(), OrdinaryExportProfile.emptyAssetSetHash, rendererVersion, output.requestedOutput.size.width, output.requestedOutput.size.height, output.output.size.width, output.output.size.height, output.fallbackPolicy.name, null, null)
        }
        val identity = try { ExportSnapshotIdentity.create(documentId, revision, settings, rows) } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "export_metadata_invalid", documentId.value) }
        return Prepared(rows, bindings, identity)
    }

    private fun sameBindings(before: List<ExportSourceBinding>, after: List<ExportSourceBinding>): Boolean = before.size == after.size && before.zip(after).all { (a, b) -> a.source.copy(refCount = 0) == b.source.copy(refCount = 0) && a.blob.copy(sourceCount = 0) == b.blob.copy(sourceCount = 0) }
    private fun verifyReusedJob(jobId: String, identity: PersistentRenderIdentity, input: ByteArray, page: ExportPageSnapshot) {
        fun mismatch(id: String?): Nothing = reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_identity_mismatch", id)
        try { requireOpaqueId(jobId) } catch (_: IllegalArgumentException) { mismatch(null) }
        if (queries.renderJobReadIsBounded(jobId, input.size) != true) mismatch(jobId)
        val row = queries.renderJobIdentity(jobId) ?: mismatch(jobId)
        if (row.jobId != jobId || row.kind != "PAGE_RENDER" || row.idempotenceKey != identity.idempotenceKey ||
            row.canonicalInputSha256 != identity.canonicalInputSha256 || !row.input.contentEquals(input)) mismatch(jobId)

        fun missing(): Nothing = reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_source_missing", jobId)
        fun invalid(): Nothing = reject(OperationErrorCode.INTEGRITY_FAILED, "render_job_source_mismatch", jobId)
        val pins = queries.boundedRenderJobPinSourceIds(jobId)
        if (pins.isEmpty()) missing()
        val pinId = pins.singleOrNull() ?: invalid()
        try { requireOpaqueId(pinId) } catch (_: IllegalArgumentException) { invalid() }
        val bounds = q as? EditablePageQueries ?: invalid()
        // The joined scalar admits both rows before either typed projection. Only a definite missing row/link keeps the missing diagnostic.
        when (bounds.sourceReadIsBounded(pinId)) {
            null -> missing()
            false -> if (bounds.sourceBlobLinkIsMissing(pinId) == true) missing() else invalid()
            true -> Unit
        }
        val source = q.source(pinId) ?: invalid()
        if (source.sourceId != pinId) invalid()
        try { requireOpaqueId(source.sourceBlobId) } catch (_: IllegalArgumentException) { invalid() }
        val blob = q.sourceBlob(source.sourceBlobId) ?: invalid()
        try {
            // Equivalent normalized originals can share a render without transferring its original JOB pin.
            FrozenRenderJobProof.verifyPin(page, pinId, ExportSourceBinding(source, blob))
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }
    private fun value(id: ExportSnapshotId, document: DocumentId, revision: Long, settings: ExportSettings, prepared: Prepared, now: Long) = ExportSnapshot(id, document, revision, prepared.identity.snapshotHash, settings, prepared.rows.map(::ExportPageSnapshot), now)
    private fun pin(type: String, owner: String, source: String, now: Long) {
        if (q.insertReference(SourceReferenceRow(type, owner, source, now)) == -1L) reject(OperationErrorCode.INTEGRITY_FAILED, "export_pin_already_exists", source)
        val count = q.countSourceReferences(source)
        if (count < 1 || q.setSourceRefCount(source, count) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "source_unavailable", source)
    }
    private fun job(id: String, kind: String, key: String, hash: String, input: ByteArray, snapshot: String?, now: Long) = JobRow(id, kind, key, hash, snapshot, null, input, "PENDING", 0, null, null, 0, null, null, now, now)
    private fun uuid() = UUID.randomUUID().toString()
    private fun reject(code: OperationErrorCode, key: String, id: String? = null, retryable: Boolean = false): Nothing = throw RepositoryFailure(OperationError(code, retryable, key, id))
}
