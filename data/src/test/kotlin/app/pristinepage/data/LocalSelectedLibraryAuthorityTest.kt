package app.pristinepage.data

import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.library.ActiveLibraryFence.Identity
import app.pristinepage.data.library.ActiveLibraryFence.Location
import app.pristinepage.data.library.ActiveLibraryFence.Selection
import app.pristinepage.data.library.LibraryFolderGrantRecord
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import app.pristinepage.data.library.LocalSelectedLibraryAuthority
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class LocalSelectedLibraryAuthorityTest {
    @get:Rule val roots = TemporaryFolder()
    private val sync = DirectorySync { true }
    private val initial = Selection(Location.Default, Identity("130a8d5d-c9c8-4b02-b6ea-3ea03139983d", 1))
    private val generation = LibraryGeneration(
        "1c45277c-3f1d-42a2-9ad2-c3996a22ba7a", "25c49d9b-02d8-418f-b982-154980d89cfb", null, 8, ObjectId("a".repeat(64), 512),
    )
    private val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FScans"
    private val grant = LibraryFolderGrantRecord("34adcfe8-fc9e-46d0-bc74-da5355276c24", uri, 1)

    @Test fun durableSelectionReopensWithTheSameTreeAndExactFullGeneration() {
        val root = roots.newFolder().canonicalFile
        val writer = LocalSelectedLibraryAuthority(root, sync)
        writer.initializeDefault(initial)
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        val published = writer.chooseFolder(initial, next, grant, generation)
        val reopened = assertIs<LocalSelectedLibraryAuthority.Selected.Folder>(LocalSelectedLibraryAuthority(root, sync).read())
        assertEquals(published, reopened.selection)
        assertEquals(uri, reopened.grant.exactUri)
        assertEquals(generation, reopened.expectedGeneration)
        assertFailsWith<IOException> { reopened.requireExact(uri, generation.copy(catalog = ObjectId("b".repeat(64), 512))) }
        assertFailsWith<IOException> { reopened.requireExact(uri.replace("Scans", "Other"), generation) }
        reopened.requireExact(uri, generation)
    }

    @Test fun refusingANewerFolderBindingCannotReplaceTheAuthoritativeDefault() {
        val root = roots.newFolder().canonicalFile
        val writer = LocalSelectedLibraryAuthority(root, sync)
        writer.initializeDefault(initial)
        val wrongRoot = Selection(Location.Folder(grant.grantId, generation.id), initial.identity.copy(activation = 2))
        assertFailsWith<IOException> { writer.chooseFolder(initial, wrongRoot, grant, generation) }
        assertEquals(LocalSelectedLibraryAuthority.Selected.Default(initial), writer.read())
    }

    @Test fun missingGrantDoesNotFallBackToDefaultAfterReopen() {
        val root = roots.newFolder().canonicalFile
        val writer = LocalSelectedLibraryAuthority(root, sync)
        writer.initializeDefault(initial)
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        val published = writer.chooseFolder(initial, next, grant, generation)
        check(root.resolve("folder-grants").resolve(grant.grantId).delete())
        val refused = assertIs<LocalSelectedLibraryAuthority.Selected.Reconnect>(LocalSelectedLibraryAuthority(root, sync).read())
        assertEquals(published, refused.selection)
    }

    @Test fun missingGenerationDoesNotFallBackToDefaultAfterReopen() {
        val root = roots.newFolder().canonicalFile
        val writer = LocalSelectedLibraryAuthority(root, sync)
        writer.initializeDefault(initial)
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        val published = writer.chooseFolder(initial, next, grant, generation)
        check(root.resolve("folder-verifications").resolve("${grant.grantId}-${generation.id}").delete())
        val refused = assertIs<LocalSelectedLibraryAuthority.Selected.Reconnect>(LocalSelectedLibraryAuthority(root, sync).read())
        assertEquals(published, refused.selection)
    }

    @Test fun aCanonicalButChangedPrivateGenerationRecordCannotSupplyANewExpectedDigest() {
        val root = roots.newFolder().canonicalFile
        val writer = LocalSelectedLibraryAuthority(root, sync)
        writer.initializeDefault(initial)
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        writer.chooseFolder(initial, next, grant, generation)
        val marker = root.resolve("folder-verifications").resolve("${grant.grantId}-${generation.id}")
        marker.writeBytes(generation.copy(catalog = ObjectId("b" + "a".repeat(63), 512)).encode())
        val refused = assertIs<LocalSelectedLibraryAuthority.Selected.Reconnect>(LocalSelectedLibraryAuthority(root, sync).read())
        assertEquals(next.identity, refused.selection.identity)
    }

    @Test fun failedGenerationDirectorySyncDoesNotPublishTheFolderPointer() {
        val root = roots.newFolder().canonicalFile
        val good = LocalSelectedLibraryAuthority(root, sync)
        good.initializeDefault(initial)
        val support = root.resolve("folder-verifications").resolve("${grant.grantId}-${generation.id}")
        val failing = LocalSelectedLibraryAuthority(root, DirectorySync { !support.exists() })
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        assertFailsWith<IOException> { failing.chooseFolder(initial, next, grant, generation) }
        assertEquals(LocalSelectedLibraryAuthority.Selected.Default(initial), good.read())
    }

    @Test fun cancellationAfterSupportRecordsAreStagedDoesNotChangeThePointer() {
        val root = roots.newFolder().canonicalFile
        val good = LocalSelectedLibraryAuthority(root, sync)
        good.initializeDefault(initial)
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        val support = root.resolve("folder-verifications").resolve("${grant.grantId}-${generation.id}")
        assertFailsWith<CancellationException> {
            good.chooseFolder(initial, next, grant, generation) {
                if (support.exists()) throw CancellationException("Canceled before publication")
            }
        }
        assertEquals(LocalSelectedLibraryAuthority.Selected.Default(initial), LocalSelectedLibraryAuthority(root, sync).read())
    }

    @Test fun failedPointerAcknowledgementCanBeReconciledOnlyAsTheExactPublishedFolder() {
        val root = roots.newFolder().canonicalFile
        val good = LocalSelectedLibraryAuthority(root, sync)
        good.initializeDefault(initial)
        val pointer = root.resolve("active-library")
        val before = pointer.readBytes().toList()
        val failing = LocalSelectedLibraryAuthority(root, DirectorySync { pointer.readBytes().toList() == before })
        val next = Selection(Location.Folder(grant.grantId, generation.id), Identity(generation.libraryId, 2))
        assertFailsWith<IOException> { failing.chooseFolder(initial, next, grant, generation) }
        assertFailsWith<IOException> { failing.read() }
        val reopened = assertIs<LocalSelectedLibraryAuthority.Selected.Folder>(good.read())
        assertEquals(next.identity, reopened.selection.identity)
        assertEquals(grant, reopened.grant)
        assertEquals(generation, reopened.expectedGeneration)
        assertFailsWith<IOException> { good.chooseFolder(initial, next, grant, generation) }
        assertEquals(reopened, good.read())
    }
}
