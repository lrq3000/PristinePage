package app.pristinepage.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.db.AtelierDraftMigration
import app.pristinepage.data.db.OcrIndexMigration
import app.pristinepage.data.db.OcrWorkMigration
import app.pristinepage.data.db.SourceCaptureDayMigration
import app.pristinepage.data.db.OcrTextPublicationMigration
import app.pristinepage.data.db.OcrTextPublicationRetryMigration
import app.pristinepage.data.db.LibraryScanNameMigration
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.library.LibraryFolderExportReceipt
import app.pristinepage.data.library.AndroidLibraryBackupHistory
import app.pristinepage.data.library.RoomLibraryFolderExport
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryFolderExportRequest
import app.pristinepage.data.library.LibraryFolderExportState
import app.pristinepage.data.model.ExportSnapshot
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.OrdinaryExportProfile
import app.pristinepage.data.model.RepositoryFailure
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns one private database and its import participants. The application retains this scope; Activities receive ports, never construct Room or close a store still used by another consumer. Closing this database does not release process-lifetime ownership locks or delete retained files. */
class PrivateImportStore private constructor(
    private val context: Context,
    private val database: PristineDatabase,
    private val root: File,
    private val owners: FileOperationOwners,
    private val terminalPngVerification: RoomTerminalPngReads.AcquiredSetVerification?,
    val documents: DocumentRepository,
    val imports: ImportSessionCommands,
    val selections: ImportSelections,
    val lifecycle: ImportSessionLifecycle,
    val sources: BoundSourceIngest,
    val committedSources: CommittedSources,
    private val documentCatalog: RoomDocumentCatalog,
    val editablePages: EditablePages,
    internal val atelierDrafts: RoomAtelierDrafts,
    val atelier: AtelierAcquisition,
) : AutoCloseable {
    val catalog: DocumentCatalog get() = documentCatalog
    val albums: AlbumCatalog get() = documentCatalog
    val albumSearch: SearchableAlbumCatalog get() = documentCatalog
    private val exportLifetime = OrdinaryExportLifetime(database::close)
    val savedOcr: SavedOcrWork by lazy { SavedOcrWork.bind(OcrComposition.from(this)) }
    val sourceMetadata: RoomSourceMetadataSearch by lazy { SourceMetadataComposition.bind(this) }

    override fun close() = exportLifetime.close()

    /** Caller must have obtained the exact writable Files capability for an explicit backup. Does not change the active location. */
    suspend fun exportLibraryTo(request: LibraryFolderExportRequest): LibraryFolderExportReceipt =
        exportLibraryTo(request, null)

    /** Internal per-call observation; ordinary exports cannot select a checkpoint from their URI, journal, or preferences. */
    @JvmSynthetic
    internal suspend fun exportLibraryTo(request: LibraryFolderExportRequest,
        beforeMarker: ((String, String) -> Nothing)?,
    ): LibraryFolderExportReceipt = exportLifetime.retainLibraryBackup {
        RoomLibraryFolderExport(context, database, root).write(request, beforeMarker?.let { observer ->
            { generation -> observer(generation.libraryId, generation.id) }
        })
    }

    /** Recovery observes only the private result of the exact operation; never reopens the provider for a write. */
    suspend fun inspectLibraryExport(operationId: String, issuedAt: LibraryActivation): LibraryFolderExportState =
        AndroidLibraryBackupHistory(context).inspect(operationId, issuedAt)

    suspend fun acknowledgeLibraryExport(receipt: LibraryFolderExportReceipt, issuedAt: LibraryActivation): Unit =
        AndroidLibraryBackupHistory(context).acknowledge(receipt, issuedAt)

    /** Pair the readonly metadata reader with the issuing store's exact database and retirement boundary. */
    internal class SourceMetadataComposition private constructor(store: PrivateImportStore) {
        internal val database = store.database
        internal val lifetime = store.exportLifetime

        companion object {
            @JvmSynthetic
            internal fun bind(store: PrivateImportStore) = RoomSourceMetadataSearch.bind(SourceMetadataComposition(store))
        }
    }

    // Internal only and lazy: the import-only path does not construct derived-file or export participants.
    internal val ordinaryExport: OrdinaryExport by lazy { ExportComposition.bind(this) }
    val albumExports: NamedAlbumExports by lazy { ExportComposition.bindNamed(this) }

    /** Only guarded services cross this boundary. In particular, no caller can retrieve or supply this store's write ports. */
    internal class OrdinaryExport private constructor(private val composition: ExportComposition) {
        internal fun assembly(preflight: suspend (ExportSnapshot, List<JobId>) -> OperationError?): RoomFreshExportAssembly =
            RoomFreshExportAssembly.forOrdinaryExport(composition, preflight)

        internal fun terminalReads(profile: OrdinaryExportProfile): RoomTerminalPngReads.Retained =
            RoomTerminalPngReads.Retained(composition, profile)

        internal fun renderFiles(): RoomRenderFileExecution = RoomRenderFileExecution(composition)

        internal fun renderCompletion(): RoomRenderCompletion = RoomRenderCompletion(renderFiles())

        companion object {
            internal fun from(composition: ExportComposition): OrdinaryExport = OrdinaryExport(composition)
        }
    }

    /** Construction input for the guarded implementations, not a returned capability. Private creation takes only an already opened store, never a database/root/owner tuple. */
    internal class ExportComposition private constructor(store: PrivateImportStore) {
        internal val database = store.database
        internal val owners = store.owners
        internal val files = DerivedFiles(store.root)
        internal val lifetime = store.exportLifetime
        internal val terminalPngVerification = store.terminalPngVerification

        companion object {
            internal fun bind(store: PrivateImportStore): OrdinaryExport = OrdinaryExport.from(ExportComposition(store))
            internal fun bindNamed(store: PrivateImportStore): NamedAlbumExports = NamedAlbumExports(ExportComposition(store))
        }
    }

    /** Only an opened store can supply this paired database, lifetime and ownership registry. */
    internal class OcrComposition private constructor(store: PrivateImportStore) {
        internal val database = store.database
        internal val owners = store.owners
        internal val lifetime = store.exportLifetime
        private val root = store.root
        internal fun derivedFiles() = DerivedFiles(root)

        companion object {
            @JvmSynthetic
            internal fun from(store: PrivateImportStore): OcrComposition = OcrComposition(store)
        }
    }

    companion object {
        /** No destructive migration fallback, provider resumption or optimistic renderer admission. Opening verifies Room on IO; cancellation during result dispatch closes the database even if the caller never receives the constructed owner. */
        @JvmStatic @JvmOverloads suspend fun open(context: Context, provider: SourceInput, inspector: SourceInspector, admission: RecipeAdmission = RecipeAdmission { _, _ -> OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "renderer_unavailable", null) }): PrivateImportStore =
            openStore(context, provider, inspector, admission, null)

        /** Trusted construction only; neither the opened store nor its export facade can replace this pair. No verifier is wired by the import-only entry. */
        @JvmSynthetic
        internal suspend fun openWithTerminalPngVerification(
            context: Context, provider: SourceInput, inspector: SourceInspector, admission: RecipeAdmission,
            verification: RoomTerminalPngReads.AcquiredSetVerification,
        ): PrivateImportStore = openStore(context, provider, inspector, admission, verification)

        private suspend fun openStore(
            context: Context, provider: SourceInput, inspector: SourceInspector, admission: RecipeAdmission,
            verification: RoomTerminalPngReads.AcquiredSetVerification?,
        ): PrivateImportStore {
            var opened: PristineDatabase? = null
            try {
                return withContext(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                val database = Room.databaseBuilder(context.applicationContext, PristineDatabase::class.java, "pristinepage.db")
                     .addMigrations(AtelierDraftMigration, OcrIndexMigration, OcrWorkMigration, SourceCaptureDayMigration,
                          OcrTextPublicationMigration, OcrTextPublicationRetryMigration, LibraryScanNameMigration).build()
                    opened = database
                    database.openHelper.writableDatabase
                    val root = File(context.applicationContext.filesDir, "store").canonicalFile
                    val files = SourceFiles(root)
                    val owners = FileOperationOwners(root)
                    val metadata = RoomMetadataAccess(database)
                    val sessions = RoomImportSessions(database)
                    val completion = RoomIngestCompletion(database, files)
                    val sources = RoomSourceStore(files, provider, inspector, RoomIngestPreparation(database), RoomIngestLookup(database), completion, completion, RoomIngestPhases(database), RoomJobLeases(database, owners), owners, restarts = RoomIngestRestarts(database, files, owners))
                    // A temporary provider reference is not a retained read grant. Reconcile private proof and metadata only; foreground result routing must explicitly establish any future provider handoff.
                    val recovery = RoomStoreRecovery(database, RoomRecoveryCoordinator(database, files, owners))
                    // Direct/test callers remain fail-closed by default. Application supplies the image-owned structural policy explicitly; data never imports rendering or infers capability from a stored recipe name.
                    val documents = RoomDocumentRepository(metadata, admission, recovery = recovery)
                    coroutineContext.ensureActive()
                    val drafts = database.atelierDrafts()
                    val draftOwner = RoomAtelierDrafts(metadata, drafts, sessions)
                    val editable = RoomEditablePages(metadata, database.catalog())
                     PrivateImportStore(context.applicationContext, database, root, owners, verification, documents, sessions, sessions, sessions, sources,
                        RoomCommittedSources(metadata, database.catalog(), files),
                        RoomDocumentCatalog(metadata, database.catalog()),
                        editable, draftOwner, AtelierAcquisition(draftOwner, drafts, editable, documents))
                }
            } catch (failure: Throwable) {
                // Preserve the initiating structured failure/cancellation even if close encounters another storage fault. No reset, schema rewrite or file cleanup is attempted.
                ImportInitializationCleanup.close(opened?.let { database -> AutoCloseable { database.close() } })
                when (failure) {
                    is CancellationException -> throw failure
                    is RepositoryFailure -> throw failure
                    is Exception -> throw RepositoryFailure(OperationError(OperationErrorCode.STORAGE_FAILED, true, "private_store_open_failed", null))
                    else -> throw failure
                }
            }
        }
    }
}
