package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.system.Os
import java.util.UUID

/** Tests the real provider's second capacity gate without writing a fixture to fill the volume. */
object LibraryBackupCapacityWitness {
    suspend fun verifyRealProviderRefusal(context: Context, tree: Uri) {
        val destination = AndroidLibraryWritableFolder(context)
        destination.useTree(tree, AndroidLibraryWritableFolder.Access.LIVE_FILES_RESULT) { provider ->
            val required = provider.openDirectory(provider.root).use { anchor ->
                val volume = Os.fstatvfs(anchor.fileDescriptor)
                check(volume.f_frsize > 0 && volume.f_blocks > 0)
                val total = Math.multiplyExact(volume.f_frsize, volume.f_blocks)
                check(provider.capacity(provider.root, anchor) in 1..total)
                Math.addExact(total, 1L)
            }
            val before = provider.children(provider.root, 16).toSet()
            val publicationName = "PristinePage-backup-${UUID.randomUUID()}"
            check(before.none { it.name == publicationName })
            try {
                AndroidNewLibraryBackup.create(provider, publicationName, required, emptySet(),
                    LocalLibraryMoveAuthorization(context.filesDir.canonicalFile),
                    recordIntent = { error("Insufficient capacity reached the private intent boundary") }) { _, _ ->
                    error("Insufficient capacity reached provider publication")
                }
            } catch (_: BackupCapacityBeforePublication) {
                check(provider.children(provider.root, 16).toSet() == before)
                return@useTree
            }
            error("The provider accepted a requirement larger than the whole mounted volume")
        }
    }
}
