package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.model.SaveRecipeResult
import app.pristinepage.data.repository.RecipeAdmission
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug instrumentation bridge. It does not validate a grant, hold topology stable, or authorize mutation. */
object LibraryDescriptorAncestryWitness {
    fun requireDisjoint(anchor: ParcelFileDescriptor, left: ParcelFileDescriptor, right: ParcelFileDescriptor) {
        AndroidLibraryDescriptorAncestry.admit(left, right, anchor)
    }

    suspend fun withRecheckedSiblings(anchor: ParcelFileDescriptor, left: ParcelFileDescriptor, right: ParcelFileDescriptor,
        rebind: () -> Unit, read: suspend () -> Unit,
    ) {
        val proof = AndroidLibraryDescriptorAncestry.admit(left, right, anchor)
        rebind()
        proof.requireCurrentSiblings()
        read()
        rebind()
        proof.requireCurrentSiblings()
    }

    class Catalog internal constructor(internal val input: AndroidLibraryTreeRead.PairInput, val libraryId: String,
        val generationId: String, val albums: Int, val scans: Int,
    ) {
        val tree: Uri get() = input.tree
    }

    suspend fun verifyPairWithSharedOwner(context: Context, scratch: File, left: Uri, right: Uri,
        requireAuthority: () -> Unit,
    ): List<Catalog> =
        AndroidLibraryTreeRead(context, scratch).withVerifiedPair(AndroidLibraryTreeRead.PairInput(left),
            AndroidLibraryTreeRead.PairInput(right), requireAuthority) { a, b ->
            listOf(a, b).map { (tree, generation, snapshot) ->
                Catalog(AndroidLibraryTreeRead.PairInput(tree, generation), generation.libraryId, generation.id,
                    snapshot.albums.size, snapshot.albums.sumOf { it.pages.size })
            }
        }

    data class MergePreview(val albums: Int, val names: List<String>, val matchedOriginals: Int, val missingBytes: Long,
        val closedCatalogBytes: Long, val requiredDestinationBytes: Long, val retainedRevisions: Int)

    suspend fun estimateMerge(context: Context, scratch: File, destination: Catalog, incoming: Catalog, requireAuthority: () -> Unit): MergePreview {
        val preflight = RoomLibraryTreeMergePreflight(context, scratch).verify(UUID.randomUUID().toString(),
            destination.input, incoming.input, requireAuthority)
        val estimate = preflight.estimate
        val albums = preflight.plan.candidate.catalog.albums
        return MergePreview(albums.size, albums.flatMap { album -> album.pages.map { it.filename } },
            preflight.plan.candidate.requiredMatches.size, estimate.missingBytes, preflight.closedCatalog.byteCount,
            estimate.requiredBytes, albums.sumOf { album -> album.pages.sumOf { it.revisionCount } })
    }

    suspend fun publishMerge(context: Context, scratch: File, operation: String, destination: Catalog, incoming: Catalog,
        requireAuthority: () -> Unit,
    ): String = RoomSelectedLibraryMerge(context, scratch).append(operation, destination.input, incoming.input, requireAuthority).id

    suspend fun publishMergeBeforeDelivery(context: Context, scratch: File, operation: String, destination: Catalog, incoming: Catalog,
        requireAuthority: () -> Unit, beforeDelivery: suspend (String) -> Nothing,
    ): Nothing {
        RoomSelectedLibraryMerge(context, scratch).append(operation, destination.input, incoming.input, requireAuthority,
            beforeDelivery = { beforeDelivery(it.id) })
        error("The armed selected append returned normally")
    }

    suspend fun attemptRetainedMerge(context: Context, scratch: File, operation: String, destination: Uri, incoming: Uri) {
        val state = requireNotNull(RoomSelectedLibraryMerge.inspect(context, operation))
        check(state.completed && state.intent.candidate.id == operation)
        val intent = state.intent
        // Deliberately supply the original exact generations to the real write entry; a test context traps any provider access.
        RoomSelectedLibraryMerge(context, scratch).append(operation, AndroidLibraryTreeRead.PairInput(destination, intent.destination),
            AndroidLibraryTreeRead.PairInput(incoming, intent.incoming), requireAuthority = {})
    }

    suspend fun completedMerge(context: Context, operation: String): String? =
        RoomSelectedLibraryMerge.inspect(context, operation)?.takeIf { it.completed }?.intent?.candidate?.id

    suspend fun verifyRecipeEditTarget(reader: AndroidLibraryTreeRead, tree: Uri): AndroidLibraryTreeRead.Verified =
        reader.verifyRecipeEditTarget(tree)

    suspend fun seedRecipeEditBinding(context: Context, scratch: File, tree: Uri): LibraryActivation = withContext(Dispatchers.IO) {
        val reader = AndroidLibraryTreeRead(context, scratch)
        val generation = reader.confirmedRecipePredecessor(reader.verifyRecipeEditTarget(tree))
        val grant = LibraryFolderGrantRecord(UUID.randomUUID().toString(), tree.toString(), 3)
        val authority = LocalLibrarySettingsBootstrap(context.filesDir.canonicalFile).open()
        val prior = (authority.read() as LocalSelectedLibraryAuthority.Selected.Default).selection
        val identity = ActiveLibraryFence.Identity(generation.libraryId, prior.identity.activation + 1)
        val selected = ActiveLibraryFence.Selection(ActiveLibraryFence.Location.Folder(grant.grantId, generation.id), identity)
        authority.chooseFolder(prior, selected, grant, generation)
        LibraryActivation(identity.libraryId, identity.activation)
    }

    class BoundRecipeReceipt internal constructor(
        val generationId: String, val issuedAt: LibraryActivation, val page: PageId, val revision: Long,
        private val intent: LocalLibraryRecipeEditJournal.Intent,
    ) {
        suspend fun reopenWithoutProvider(context: Context): Boolean = withContext(Dispatchers.IO) {
            val attempt = File(context.filesDir.canonicalFile, "catalog-recipe-attempts-v1/$generationId")
            androidRecipeEditJournal(attempt).inspect(intent) == LocalLibraryRecipeEditJournal.Phase.COMPLETED
        }
    }

    suspend fun publishBoundRecipeEdit(
        context: Context, scratch: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, turns: Int,
    ): BoundRecipeReceipt {
        val result = AndroidLibraryStartup(context).publishSelectedRecipeEdit(scratch, issuedAt, operation, document, page,
            expectedRevision, RecipeAdmission { _, _ -> null }) { previous ->
            previous.copy(geometry = previous.geometry.withOutputQuarterTurns(turns))
        } as AndroidLibraryTreeRead.SelectedRecipeEdit.Completed
        val intent = result.receipt.intent
        val identity = intent.binding.selection.identity
        return BoundRecipeReceipt(intent.successor.id, LibraryActivation(identity.libraryId, identity.activation),
            result.projection.pageId, result.projection.recipeRevision, intent)
    }

    /** The witness comes back through the durable issuing binding, not the live writer's returned object. */
    suspend fun observeInterruptedRecipeSuccessor(context: Context, scratch: File, issuedAt: LibraryActivation): String? =
        AndroidLibraryTreeRead(context, scratch).observeInterruptedRecipeSuccessor(issuedAt)?.id

    suspend fun recoverInterruptedRecipeSuccessor(context: Context, scratch: File, issuedAt: LibraryActivation): LibraryActivation? =
        AndroidLibraryTreeRead(context, scratch).recoverInterruptedRecipeSuccessor(issuedAt)

    suspend fun saveAndAdvanceSelectedRecipe(
        context: Context, scratch: File, issuedAt: LibraryActivation, operation: String, document: DocumentId, page: PageId,
        expectedRevision: Long, turns: Int,
    ): Pair<LibraryActivation, SaveRecipeResult.Saved> {
        val result = AndroidLibraryStartup(context).advanceSelectedRecipeEdit(scratch, issuedAt, operation, document, page, expectedRevision,
            RecipeAdmission { _, _ -> null }) { previous ->
            previous.copy(geometry = previous.geometry.withOutputQuarterTurns(turns))
        } as AndroidLibraryStartup.SelectedRecipeSave.Activated
        return result.activation to result.saved
    }

    suspend fun appendSelectedRecipeEdit(
        context: Context, scratch: File, operation: String, verified: AndroidLibraryTreeRead.Verified,
        document: DocumentId, page: PageId,
        expectedRevision: Long, turns: Int, requireAuthority: () -> Unit,
    ): String {
        val reader = AndroidLibraryTreeRead(context, scratch)
        val result = reader.appendSelectedRecipeEdit(verified, operation, document, page, expectedRevision,
            RecipeAdmission { _, _ -> null }, requireAuthority) { previous ->
            previous.copy(geometry = previous.geometry.withOutputQuarterTurns(turns))
        }
        return (result as AndroidLibraryTreeRead.SelectedRecipeEdit.Published).generation.id
    }

    fun requireSameIdentity(held: ParcelFileDescriptor, reopened: ParcelFileDescriptor) {
        check(AndroidLibraryDescriptorAncestry.observe(held) == AndroidLibraryDescriptorAncestry.observe(reopened)) {
            "The reopened directory descriptor does not match its held identity and mount"
        }
    }
}
