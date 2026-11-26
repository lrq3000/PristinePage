package app.pristinepage.data

import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.library.LibraryFolderExportRecords
import app.pristinepage.data.library.LibraryFolderGrantRecord
import app.pristinepage.data.library.LibraryGeneration
import app.pristinepage.data.library.LibraryObjectTransfer.ObjectId
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryFolderExportRecordsTest {
    @Test fun aDurableIntentBindsTheExactFilesResultAndIssuingActivation() {
        val intent = fixture()
        val restored = LibraryFolderExportRecords.readIntent(intent.encode().inputStream(), operation)
        assertEquals(intent, restored)
        assertEquals("PristinePage-backup-$generation", restored.backupName)
        assertEquals(tree, restored.destination.exactUri)
        assertEquals(3, restored.destination.returnedModes)
        assertEquals(LibraryActivation(source, 2), restored.issuedAt)
        assertEquals(8L to 77L, restored.parentDevice to restored.parentInode)
        val bytes = intent.encode()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(571, bytes.size)
        assertEquals("a96e77ff6655c0ac2550ad58fac2c400d5ec30a5d9665b98eb391ee186374ce6", digest)
        assertFailsWith<IOException> {
            LibraryFolderExportRecords.readIntent(intent.encode().inputStream(), generation)
        }
    }

    @Test fun replacingTheConfirmedGrantOrAppendingBytesInvalidatesTheRetainedIntent() {
        val bytes = fixture().encode()
        val altered = bytes.toString(Charsets.US_ASCII).replace("Documents", "Documentx").toByteArray(Charsets.US_ASCII)
        for (corrupt in listOf(altered, bytes + 10.toByte(), bytes.copyOf(bytes.size - 1), ByteArray(5_100) { 65 })) {
            assertFailsWith<IOException> { LibraryFolderExportRecords.readIntent(corrupt.inputStream(), operation) }
        }
    }

    @Test fun aMaximumLengthExactFolderLocatorDoesNotOverflowThePrivateRecord() {
        val intent = fixture()
        val longest = tree + "x".repeat(4_096 - tree.length)
        val maximal = intent.copy(destination = intent.destination.copy(exactUri = longest))
        assertEquals(longest, LibraryFolderExportRecords.readIntent(maximal.encode().inputStream(), operation).destination.exactUri)
    }

    @Test fun completionAndAcknowledgmentCannotBeTransferredToAnotherActivationOrReusedAsEachOther() {
        val intent = fixture()
        val next = intent.copy(issuedAt = LibraryActivation(source, 3))
        val complete = intent.completion()
        val acknowledged = intent.acknowledgment()
        assertEquals(Unit, intent.requireCompletion(complete.inputStream()))
        assertEquals(Unit, intent.requireAcknowledgment(acknowledged.inputStream()))
        assertFailsWith<IOException> { next.requireCompletion(complete.inputStream()) }
        assertFailsWith<IOException> { next.requireAcknowledgment(acknowledged.inputStream()) }
        assertFailsWith<IOException> { intent.requireAcknowledgment(complete.inputStream()) }
        assertFailsWith<IOException> { intent.requireCompletion(acknowledged.inputStream()) }
        assertFailsWith<IOException> { intent.requireCompletion((complete + 10.toByte()).inputStream()) }
        assertFailsWith<IOException> {
            intent.copy(destination = intent.destination.copy(exactUri = tree.replace("primary", "home")))
                .requireCompletion(complete.inputStream())
        }
    }

    @Test fun readOnlyOrBorrowedGrantCannotDescribeAFreshAuthorizedWrite() {
        val intent = fixture()
        assertFailsWith<IllegalArgumentException> { intent.copy(destination = intent.destination.copy(returnedModes = 1)) }
        assertFailsWith<IllegalArgumentException> { intent.copy(destination = intent.destination.copy(grantId = source)) }
        assertFailsWith<IllegalArgumentException> { intent.copy(parentInode = 0) }
        assertFailsWith<IllegalArgumentException> { intent.copy(generation = intent.generation.copy(predecessorId = operation)) }
    }

    private fun fixture() = LibraryFolderExportRecords.Intent(
        operation, LibraryActivation(source, 2), LibraryFolderGrantRecord(operation, tree, 3), 8L, 77L,
        LibraryGeneration("10000000-0000-4000-8000-000000000004", generation, null, 8, ObjectId("a".repeat(64), 512)),
    )

    private companion object {
        const val operation = "10000000-0000-4000-8000-000000000001"
        const val source = "10000000-0000-4000-8000-000000000002"
        const val generation = "10000000-0000-4000-8000-000000000003"
        const val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBackup"
    }
}
