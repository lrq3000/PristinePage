package app.pristinepage.data.repository

import android.content.Context
import androidx.room.Room
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.files.SourceFiles
import app.pristinepage.data.files.SourceInspector
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns one private database and its import participants. The application retains this scope; Activities receive ports, never construct Room or close a store still used by another consumer. Closing this database does not release process-lifetime ownership locks or delete retained files. */
class PrivateImportStore private constructor(
    private val database: PristineDatabase,
    val documents: DocumentRepository,
    val imports: ImportSessionCommands,
    val selections: ImportSelections,
    val lifecycle: ImportSessionLifecycle,
    val sources: BoundSourceIngest,
    val committedSources: CommittedSources,
    val catalog: DocumentCatalog,
    val editablePages: EditablePages,
) : AutoCloseable {
    override fun close() = database.close()

    companion object {
        /** No destructive migration fallback, provider resumption or optimistic renderer admission. Opening verifies Room on IO; cancellation during result dispatch closes the database even if the caller never receives the constructed owner. */
        @JvmStatic @JvmOverloads suspend fun open(context: Context, provider: SourceInput, inspector: SourceInspector, admission: RecipeAdmission = RecipeAdmission { _, _ -> OperationError(OperationErrorCode.PROVIDER_UNAVAILABLE, false, "renderer_unavailable", null) }): PrivateImportStore {
            var opened: PristineDatabase? = null
            try {
                return withContext(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    val database = Room.databaseBuilder(context.applicationContext, PristineDatabase::class.java, "pristinepage.db").build()
                    opened = database
                    database.openHelper.writableDatabase
                    val root = File(context.applicationContext.filesDir, "store")
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
                    PrivateImportStore(database, documents, sessions, sessions, sessions, sources, RoomCommittedSources(metadata, database.catalog(), files), RoomDocumentCatalog(metadata, database.catalog()), RoomEditablePages(metadata, database.catalog()))
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
