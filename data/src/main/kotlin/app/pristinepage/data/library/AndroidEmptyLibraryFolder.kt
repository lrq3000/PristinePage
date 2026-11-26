package app.pristinepage.data.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.pristinepage.data.files.PlatformDirectorySync
import app.pristinepage.data.library.AndroidLibraryDescriptorAncestry.Observation
import app.pristinepage.data.library.RoomLibraryCatalogReader.Companion.MAX_PORTABLE_BYTES
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Creates the first marker in a confirmed empty Files tree, using the same leaf writer and catalog publication as export. */
internal class AndroidEmptyLibraryFolder(context: Context, private val scratchDirectory: File) {
    private val app = context.applicationContext
    private val folder = AndroidLibraryWritableFolder(app)

    suspend fun observeEmpty(tree: Uri): Observation? = folder.useTree(tree, AndroidLibraryWritableFolder.Access.PERSISTED) { provider ->
        provider.openDirectory(provider.root).use { root ->
            if (provider.children(provider.root, 2_048).isNotEmpty()) return@use null
            AndroidLibraryDescriptorAncestry.requirePhysicallyEmpty(root)
            provider.requireDirectory(provider.root, root)
            AndroidLibraryDescriptorAncestry.observe(root)
        }
    }

    suspend fun start(tree: Uri, expectedRoot: Observation, previous: Uri?, requireCurrent: () -> Unit): LibraryGeneration = withContext(Dispatchers.IO) {
        val checkpoint = { coroutineContext.ensureActive(); requireCurrent() }
        // Reject known nested/identical locators before consuming private staging space. A positive hint must still be proven with real descriptors.
        if (previous != null) LibraryTreeAncestryHint.parentHops(
            checkNotNull(AndroidLibraryTreeRead.localTreeDocumentId(previous)), checkNotNull(AndroidLibraryTreeRead.localTreeDocumentId(tree)),
        )
        val files = app.filesDir.toPath().toRealPath()
        val scratch = canonicalLibraryDirectory(scratchDirectory, files)
        if (scratch.toFile().usableSpace < 3 * MAX_PORTABLE_BYTES) throw IOException("Not enough space for a closed empty library")
        val attempts = scratch.resolve("files-empty-attempts")
        if (Files.notExists(attempts, NOFOLLOW_LINKS)) Files.createDirectory(attempts)
        if (canonicalLibraryDirectory(attempts.toFile(), files) != attempts) throw IOException("The empty library staging directory changed")
        Files.newDirectoryStream(attempts).use {
            if (it.asSequence().take(4).count() >= 4) throw IOException("Retained empty library attempts need verification")
        }
        checkpoint()
        val stage = Files.createTempDirectory(attempts, "empty-")
        val catalog = stage.resolve("catalog.sqlite")
        val empty = RoomLibrarySavedCatalog.Snapshot(emptyList(), emptyList(), emptyList(), emptyList())
        val reader = RoomLibraryCatalogReader(app, MAX_PORTABLE_BYTES)
        val schema = RoomLibraryCatalogStage(app).write(empty, catalog.toFile())
        reader.verifyStagedProjection(catalog.toFile(), empty, checkpoint)
        val generation = LibraryGeneration(UUID.randomUUID().toString(), UUID.randomUUID().toString(), null, schema,
            fingerprintClosedLibraryCatalog(catalog.toFile(), checkpoint))
        val required = 2 * generation.catalog.byteCount + 64 * 1024
        if (scratch.toFile().usableSpace < required) throw IOException("Not enough space to verify the empty library")
        val stageKey = localLibraryRootKey(stage)
        val spend = LocalLibraryMoveAuthorization(stage.toFile())
        fun requireStage() {
            checkpoint()
            if (localLibraryRootKey(stage) != stageKey) throw IOException("The empty library's private attempt changed")
        }
        folder.useTree(tree, AndroidLibraryWritableFolder.Access.PERSISTED) { provider ->
            fun publish(checkSeparation: () -> Unit) {
                fun requirePublication() {
                    requireStage()
                    checkSeparation()
                }
                AndroidNewLibraryBackup.create(provider, null, required, emptySet(), spend, recordIntent = {
                    requirePublication()
                    forceIntent(stage, attempts, scratch, tree, generation, expectedRoot)
                }, requireEmptyRoot = {
                    requirePublication()
                    requireRoot(it.descriptor, expectedRoot)
                }, requireOwner = ::requirePublication) { target, _ ->
                    LibraryCatalogPublication(target, stage, ::requirePublication).publish(generation, MAX_PORTABLE_BYTES,
                        { Files.newInputStream(catalog, READ, NOFOLLOW_LINKS) }, createOnly = true) { candidate ->
                        reader.verifyStagedProjection(candidate, empty, ::requirePublication)
                    }
                    target.requireCompleteLayout()
                }
            }
            if (previous == null) publish {}
            else provider.openDirectory(provider.root).use { held ->
                requireStage()
                requireRoot(held, expectedRoot)
                provider.withSeparatePreviousRoot(previous, held, ::publish)
            }
        }
        checkpoint()
        generation
    }

    private fun requireRoot(root: ParcelFileDescriptor, expected: Observation) {
        if (AndroidLibraryDescriptorAncestry.observe(root) != expected) throw IOException("The confirmed empty folder changed")
    }

    private fun forceIntent(stage: Path, attempts: Path, scratch: Path, tree: Uri, generation: LibraryGeneration, root: Observation) {
        val header = ("pristinepage-new-files-library-v1\n$tree\n$root\n").toByteArray(Charsets.UTF_8)
        FileChannel.open(stage.resolve("new-library.intent"), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val data = ByteBuffer.wrap(header + generation.encode())
            while (data.hasRemaining()) if (channel.write(data) <= 0) throw IOException("Cannot write the empty-library intent")
            channel.force(true)
        }
        if (listOf(stage, attempts, scratch).any { !PlatformDirectorySync.sync(it.toFile()) }) {
            throw IOException("The empty-library intent is not durable")
        }
    }
}
