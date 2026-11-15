package app.pristinepage.data.repository

import android.net.Uri
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import app.pristinepage.data.db.ImportSessionRow
import app.pristinepage.data.db.PageRow
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reserves identities/order before external activity IO. A batch is not a Page and cannot expose an uncommitted source. Import sessions and catalog queries must share the injected metadata transaction. */
class RoomImportSessions @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val clock: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ImportSessionCommands, ImportItemBindings {
    /** Native construction binds both DAOs to the same writer transaction; host tests inject only the unavailable Android database boundary. */
    constructor(database: PristineDatabase) : this(RoomMetadataAccess(database), database.importSessions())

    override suspend fun beginImport(documentId: DocumentId, expectedDocumentRevision: Long, anchorPageId: PageId?): ImportBatch = withContext(dispatcher) {
        guarded {
            coroutineContext.ensureActive()
            metadata.transaction {
                if (expectedDocumentRevision < 1) reject(OperationErrorCode.INVALID_INPUT, "invalid_document_revision", documentId.value)
                val pages = activePages(documentId.value)
                val doc = checkNotNull(metadata.queries.document(documentId.value))
                if (doc.documentRevision != expectedDocumentRevision) reject(OperationErrorCode.REVISION_CONFLICT, "document_revision_changed", documentId.value)
                if (sessions.activeBatches(documentId.value).isNotEmpty()) reject(OperationErrorCode.REVISION_CONFLICT, "finish_import_before_new_batch", documentId.value)
                if (anchorPageId != null && pages.none { it.pageId == anchorPageId.value }) reject(OperationErrorCode.INVALID_INPUT, "import_anchor_unavailable", anchorPageId.value)
                if (pages.size >= 100) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", documentId.value)
                val id = ImportSessionId(UUID.randomUUID().toString()); val now = clock()
                val expires = try { Math.addExact(now, 86_400_000L) } catch (_: ArithmeticException) { reject(OperationErrorCode.RESOURCE_LIMIT, "retention_clock_overflow", id.value) }
                coroutineContext.ensureActive()
                sessions.insertSession(ImportSessionRow(id.value, null, "BATCH", documentId.value, anchorPageId?.value, null, null, null, "WAITING_SELECTION", ImportMembership.hash(pages), null, now, expires, null))
                ImportBatch(id, emptyList())
            }
        }
    }

    override suspend fun reserveImportItems(sessionId: ImportSessionId, count: Int): ImportBatch = withContext(dispatcher) {
        guarded {
            if (count !in 1..100) reject(OperationErrorCode.INVALID_INPUT, "invalid_import_count", sessionId.value)
            coroutineContext.ensureActive()
            metadata.transaction {
                val parent = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.kind != "BATCH" || parent.parentSessionId != null || parent.state !in setOf("WAITING_SELECTION", "RESERVED") || parent.expiresAt <= clock()) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                val pages = activePages(parent.documentId)
                if (ImportMembership.hash(pages) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", sessionId.value)
                val children = sessions.children(parent.sessionId)
                if (children.isNotEmpty()) {
                    if (children.size != count) reject(OperationErrorCode.INVALID_INPUT, "import_selection_already_reserved", sessionId.value)
                    if (children.map { it.selectionOrdinal } != children.indices.toList() || children.any { it.documentId != parent.documentId || it.kind != "ITEM" || it.plannedPageId == null }) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                    return@transaction ImportBatch(sessionId, children.map { ImportSessionId(it.sessionId) })
                }
                if (count > 100 - pages.size) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", sessionId.value)
                val now = clock()
                val rows = List(count) { ordinal ->
                    ImportSessionRow(UUID.randomUUID().toString(), parent.sessionId, "ITEM", parent.documentId, parent.anchorPageId, ordinal, UUID.randomUUID().toString(), null, "RESERVED", parent.expectedMembershipToken, null, now, parent.expiresAt, null)
                }
                coroutineContext.ensureActive()
                rows.forEach(sessions::insertSession)
                if (sessions.updateSession(parent.copy(state = "RESERVED")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_session_unavailable", sessionId.value)
                ImportBatch(sessionId, rows.map { ImportSessionId(it.sessionId) })
            }
        }
    }

    override suspend fun bindImportItem(sessionId: ImportSessionId, ordinal: Int, uri: Uri): ImportSessionId = bindProviderItem(sessionId, ordinal, uri.toString())

    override suspend fun bindProviderItem(sessionId: ImportSessionId, ordinal: Int, uriText: String): ImportSessionId = withContext(dispatcher) {
        guarded {
            // Parsing never opens the URI or tests a provider grant. File/camera handoff is a later exact-transaction exception, not an arbitrary file URI admitted through this picker path.
            if (ordinal !in 0..99 || uriText.isEmpty() || uriText.length > 8192) reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value)
            try {
                requireWellFormedUnicode(uriText)
                val reference = URI(uriText)
                require(reference.scheme == "content" && !reference.rawAuthority.isNullOrBlank())
            } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value) }
            catch (_: java.net.URISyntaxException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value) }
            coroutineContext.ensureActive()
            metadata.transaction {
                val parent = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.kind != "BATCH" || parent.parentSessionId != null || parent.state != "RESERVED" || parent.expiresAt <= clock()) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (ImportMembership.hash(activePages(parent.documentId)) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", sessionId.value)
                val child = sessions.children(parent.sessionId).singleOrNull { it.selectionOrdinal == ordinal } ?: reject(OperationErrorCode.INVALID_INPUT, "import_item_not_reserved", sessionId.value)
                if (child.kind != "ITEM" || child.documentId != parent.documentId || child.plannedPageId == null || child.expiresAt <= clock()) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", child.sessionId)
                if (child.uriText != null) {
                    if (child.uriText != uriText) reject(OperationErrorCode.INVALID_INPUT, "import_reference_already_bound", child.sessionId)
                    if (child.state in setOf("CANCELED", "FAILED", "QUARANTINED")) reject(OperationErrorCode.SESSION_EXPIRED, "import_item_unavailable", child.sessionId)
                    return@transaction ImportSessionId(child.sessionId)
                }
                if (child.state != "RESERVED" || child.transactionId != null) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", child.sessionId)
                coroutineContext.ensureActive()
                if (sessions.updateSession(child.copy(uriText = uriText, state = "BOUND")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_item_not_reserved", child.sessionId)
                ImportSessionId(child.sessionId)
            }
        }
    }

    private fun activePages(documentId: String): List<PageRow> {
        val doc = metadata.queries.document(documentId) ?: reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_unavailable", documentId)
        if (doc.lifecycle != "ACTIVE") reject(OperationErrorCode.SOURCE_UNAVAILABLE, "document_not_active", documentId)
        val pages = metadata.queries.activePages(documentId)
        if (pages.size > 100 || pages.map { it.ordinal } != pages.indices.toList()) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_page_order", documentId)
        return pages
    }

    private fun reject(code: OperationErrorCode, message: String, id: String): Nothing = throw RepositoryFailure(OperationError(code, false, message, id))
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (canceled: CancellationException) { throw canceled }
    catch (failure: RepositoryFailure) { throw failure }
    catch (_: Exception) { throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "metadata_write_failed", null)) }
}

/** Versioned membership identity includes ordered PageIds only. Recipe/document revisions cannot invalidate an insertion anchor when membership itself has not changed. The ingestion commit will advance this token for its own insertions. */
internal object ImportMembership {
    fun hash(pages: List<PageRow>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.write("pristinepage-membership-v1\u0000".toByteArray(Charsets.UTF_8)); output.writeInt(pages.size)
            for (page in pages) {
                val id = PageId(page.pageId).value.toByteArray(Charsets.UTF_8)
                output.writeInt(id.size); output.write(id)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
