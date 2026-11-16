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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reserves identities/order before external activity IO. A batch is not a Page and cannot expose an uncommitted source. Import sessions and catalog queries must share the injected metadata transaction. */
class RoomImportSessions @JvmOverloads constructor(private val metadata: MetadataAccess, private val sessions: ImportSessionQueries, private val clock: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ImportSessionCommands, ImportItemBindings, ImportSelections, ImportSessionLifecycle {
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
            metadata.transaction { reserve(sessionId, count, coroutineContext) }
        }
    }

    /** Shared short-transaction reservation for individual camera/item calls and the atomic photo-selection route. No provider IO, suspension or nested dispatcher boundary. */
    private fun reserve(sessionId: ImportSessionId, count: Int, context: CoroutineContext): ImportBatch {
                val parent = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.kind != "BATCH" || parent.parentSessionId != null || parent.state !in setOf("WAITING_SELECTION", "RESERVED") || parent.expiresAt <= clock()) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                val pages = activePages(parent.documentId)
                if (ImportMembership.hash(pages) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", sessionId.value)
                val children = sessions.children(parent.sessionId)
                if (children.isNotEmpty()) {
                    if (children.size != count) reject(OperationErrorCode.INVALID_INPUT, "import_selection_already_reserved", sessionId.value)
                    if (children.map { it.selectionOrdinal } != children.indices.toList() || children.any { it.documentId != parent.documentId || it.kind != "ITEM" || it.plannedPageId == null }) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                    return ImportBatch(sessionId, children.map { ImportSessionId(it.sessionId) })
                }
                if (count > 100 - pages.size) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", sessionId.value)
                val now = clock()
                val rows = List(count) { ordinal ->
                    ImportSessionRow(UUID.randomUUID().toString(), parent.sessionId, "ITEM", parent.documentId, parent.anchorPageId, ordinal, UUID.randomUUID().toString(), null, "RESERVED", parent.expectedMembershipToken, null, now, parent.expiresAt, null)
                }
                context.ensureActive()
                rows.forEach(sessions::insertSession)
                if (sessions.updateSession(parent.copy(state = "RESERVED")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_session_unavailable", sessionId.value)
                return ImportBatch(sessionId, rows.map { ImportSessionId(it.sessionId) })
    }

    override suspend fun bindImportItem(sessionId: ImportSessionId, ordinal: Int, uri: Uri): ImportSessionId = bindProviderItem(sessionId, ordinal, uri.toString())

    override suspend fun bindProviderItem(sessionId: ImportSessionId, ordinal: Int, uriText: String): ImportSessionId = withContext(dispatcher) {
        guarded {
            if (ordinal !in 0..99) reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value)
            validateReference(sessionId, uriText)
            coroutineContext.ensureActive()
            metadata.transaction { bind(sessionId, ordinal, uriText, coroutineContext) }
        }
    }

    private fun bind(sessionId: ImportSessionId, ordinal: Int, uriText: String, context: CoroutineContext): ImportSessionId {
                val parent = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.kind != "BATCH" || parent.parentSessionId != null || parent.state != "RESERVED" || parent.expiresAt <= clock()) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (ImportMembership.hash(activePages(parent.documentId)) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", sessionId.value)
                val child = sessions.children(parent.sessionId).singleOrNull { it.selectionOrdinal == ordinal } ?: reject(OperationErrorCode.INVALID_INPUT, "import_item_not_reserved", sessionId.value)
                if (child.kind != "ITEM" || child.documentId != parent.documentId || child.plannedPageId == null || child.expiresAt <= clock()) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", child.sessionId)
                if (child.uriText != null) {
                    if (child.uriText != uriText) reject(OperationErrorCode.INVALID_INPUT, "import_reference_already_bound", child.sessionId)
                    if (child.state in setOf("CANCELED", "FAILED", "QUARANTINED")) reject(OperationErrorCode.SESSION_EXPIRED, "import_item_unavailable", child.sessionId)
                    return ImportSessionId(child.sessionId)
                }
                if (child.state != "RESERVED" || child.transactionId != null) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", child.sessionId)
                context.ensureActive()
                if (sessions.updateSession(child.copy(uriText = uriText, state = "BOUND")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_item_not_reserved", child.sessionId)
                return ImportSessionId(child.sessionId)
    }

    override suspend fun prepareSelection(sessionId: ImportSessionId, references: List<String>): ImportBatch = withContext(dispatcher) {
        guarded {
            // Check count BEFORE copying callback-owned lists; never silently truncate fallback over-selection.
            if (references.size > 100) reject(OperationErrorCode.RESOURCE_LIMIT, "document_page_limit", sessionId.value)
            val selected = references.toList()
            selected.forEach { validateReference(sessionId, it) }
            val identity = selectionIdentity(selected)
            coroutineContext.ensureActive()
            metadata.transaction {
                val parent = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.kind != "BATCH" || parent.parentSessionId != null || parent.expiresAt <= clock()) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                val children = sessions.children(parent.sessionId)
                if (parent.uriText != null) {
                    if (!parent.uriText.matches(Regex("photo-selection-v1:[0-9a-f]{64}"))) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_selection_identity", sessionId.value)
                    if (parent.uriText != identity) reject(OperationErrorCode.INVALID_INPUT, "import_selection_already_bound", sessionId.value)
                    validateSelectionChildren(parent, children, selected)
                    if (selected.isEmpty()) {
                        if (parent.state != "CANCELED") reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_selection_state", sessionId.value)
                    } else if (parent.state == "RESERVED") {
                        if (ImportMembership.hash(activePages(parent.documentId)) != parent.expectedMembershipToken) reject(OperationErrorCode.REVISION_CONFLICT, "import_membership_changed", sessionId.value)
                    } else if (parent.state !in setOf("COMMITTED", "FAILED", "CANCELED") || children.any { it.state !in TERMINAL_ITEMS }) {
                        reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                    }
                    // Selection replay is read-only. SourceStore subsequently validates real receipts/files or resumes pending work; these IDs alone are not success.
                    return@transaction ImportBatch(sessionId, children.map { ImportSessionId(it.sessionId) })
                }
                if (parent.state !in setOf("WAITING_SELECTION", "RESERVED") || children.any { it.state !in setOf("RESERVED", "BOUND") || it.transactionId != null }) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (selected.isEmpty()) {
                    if (children.isNotEmpty()) reject(OperationErrorCode.INVALID_INPUT, "import_selection_already_reserved", sessionId.value)
                    coroutineContext.ensureActive()
                    if (sessions.updateSession(parent.copy(state = "CANCELED", errorCode = "CANCELED", uriText = identity)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_session_unavailable", sessionId.value)
                    coroutineContext.ensureActive()
                    return@transaction ImportBatch(sessionId, emptyList())
                }
                val reserved = reserve(sessionId, selected.size, coroutineContext)
                selected.forEachIndexed { ordinal, reference -> bind(sessionId, ordinal, reference, coroutineContext) }
                coroutineContext.ensureActive()
                if (sessions.updateSession(parent.copy(state = "RESERVED", uriText = identity)) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_session_unavailable", sessionId.value)
                coroutineContext.ensureActive()
                reserved
            }
        }
    }

    override suspend fun cancelBatch(sessionId: ImportSessionId): ImportBatch = withContext(dispatcher) {
        guarded {
            coroutineContext.ensureActive()
            metadata.transaction {
                val parent = sessions.session(sessionId.value) ?: reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                if (parent.sessionId != sessionId.value || parent.kind != "BATCH" || parent.parentSessionId != null) reject(OperationErrorCode.SESSION_EXPIRED, "import_session_unavailable", sessionId.value)
                val children = sessions.children(parent.sessionId)
                if (parent.state !in TERMINAL_ITEMS + setOf("WAITING_SELECTION", "RESERVED") || children.size > 100 || children.map { it.selectionOrdinal } != children.indices.toList() || children.map { it.sessionId }.toSet().size != children.size || children.map { it.plannedPageId }.toSet().size != children.size) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                children.forEach { child ->
                    try { ImportSessionId(child.sessionId); PageId(child.plannedPageId ?: throw IllegalArgumentException()) }
                    catch (_: IllegalArgumentException) { reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value) }
                    if (child.parentSessionId != parent.sessionId || child.documentId != parent.documentId || child.kind != "ITEM" || child.anchorPageId != parent.anchorPageId || child.state !in TERMINAL_ITEMS + setOf("RESERVED", "BOUND", "INGESTING")) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                }
                val result = ImportBatch(sessionId, children.map { ImportSessionId(it.sessionId) })
                if (parent.state in TERMINAL_ITEMS) {
                    if (children.any { it.state !in TERMINAL_ITEMS }) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                    return@transaction result
                }
                if (parent.state == "WAITING_SELECTION" && children.isNotEmpty() || parent.state == "RESERVED" && children.isEmpty()) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", sessionId.value)
                // Closing insertion does not depend on today's membership, expiry or anchor. No page/source/job/file/lease is touched, and completed children never change outcome.
                for (child in children) if (child.state !in TERMINAL_ITEMS) {
                    coroutineContext.ensureActive()
                    if (sessions.updateSession(child.copy(state = "CANCELED", uriText = null, errorCode = "CANCELED")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_item_unavailable", sessionId.value)
                }
                coroutineContext.ensureActive()
                if (sessions.updateSession(parent.copy(state = "CANCELED", errorCode = "CANCELED")) != 1) reject(OperationErrorCode.INTEGRITY_FAILED, "import_session_unavailable", sessionId.value)
                coroutineContext.ensureActive()
                result
            }
        }
    }

    private fun validateSelectionChildren(parent: ImportSessionRow, children: List<ImportSessionRow>, selected: List<String>) {
        if (children.size != selected.size || children.map { it.selectionOrdinal } != children.indices.toList() || children.map { it.sessionId }.toSet().size != children.size || children.map { it.plannedPageId }.toSet().size != children.size) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", parent.sessionId)
        children.forEachIndexed { ordinal, child ->
            ImportSessionId(child.sessionId); PageId(child.plannedPageId ?: reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", parent.sessionId))
            if (child.parentSessionId != parent.sessionId || child.kind != "ITEM" || child.documentId != parent.documentId || child.anchorPageId != parent.anchorPageId || child.expiresAt != parent.expiresAt || child.state !in TERMINAL_ITEMS + setOf("BOUND", "INGESTING")) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", parent.sessionId)
            if (child.state in TERMINAL_ITEMS) {
                if (child.uriText != null) reject(OperationErrorCode.INTEGRITY_FAILED, "invalid_import_reservation", child.sessionId)
            } else if (child.uriText != selected[ordinal]) reject(OperationErrorCode.INTEGRITY_FAILED, "import_selection_binding_changed", child.sessionId)
        }
    }

    /** A private kind-discriminated BATCH token, never a provider URI. ITEM references can expire/clear without losing repeated-callback identity. Count, ordinal and duplicate references all contribute. */
    private fun selectionIdentity(selected: List<String>): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.write("pristinepage-photo-selection-v1\u0000".toByteArray(Charsets.UTF_8)); out.writeInt(selected.size)
            selected.forEach { reference -> val encoded = reference.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
        }
        return "photo-selection-v1:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun validateReference(sessionId: ImportSessionId, uriText: String) {
        // Parsing never opens the URI or tests a grant. File/camera handoff is a later exact-transaction exception, not arbitrary file access through this photo route.
        if (uriText.isEmpty() || uriText.length > 8192) reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value)
        try {
            requireWellFormedUnicode(uriText)
            val reference = URI(uriText)
            require(reference.scheme == "content" && !reference.rawAuthority.isNullOrBlank())
        } catch (_: IllegalArgumentException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value) }
        catch (_: java.net.URISyntaxException) { reject(OperationErrorCode.INVALID_INPUT, "invalid_import_reference", sessionId.value) }
    }

    private companion object { val TERMINAL_ITEMS = setOf("COMMITTED", "CANCELED", "FAILED", "QUARANTINED") }

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
