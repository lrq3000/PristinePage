package app.pristinepage

import android.accessibilityservice.AccessibilityService
import android.app.Instrumentation
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.data.library.AndroidLibraryTreeRead
import app.pristinepage.data.library.AndroidLibraryStartup
import app.pristinepage.data.library.AndroidLibraryWritableFolder
import app.pristinepage.data.library.LibraryActivation
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.SourceBlobId
import app.pristinepage.data.repository.ScopedCommittedOriginals
import app.pristinepage.image.decode.AndroidSourceInspector
import app.pristinepage.image.decode.SourceDecoder
import app.pristinepage.library.LibraryFolderGrantHandoff
import app.pristinepage.library.LibraryFolderPickerContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** System Files, the exact Android grant and an isolated synthetic library. The opt-in write probe touches only its newly created child. */
@RunWith(AndroidJUnit4::class)
class LibraryFolderFilesJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualFilesGrantReopensAfterActivityRecreationAndRevocationFailsClosed() = verifyJourney()

    @Test fun defaultAppGraphUsesItsDurableActivationBeforeAndAfterActivityRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PristinePageApplication
        val initial = runBlocking { app.services.imports().activation }
        assertEquals(1L, initial.number)
        assertEquals(initial, runBlocking { AndroidLibraryStartup(app).requireDefaultActivation() })
        compose.activityRule.scenario.recreate()
        val reopened = runBlocking { app.services.imports().activation }
        assertEquals(initial, reopened)
        assertEquals(initial, runBlocking { AndroidLibraryStartup(app).requireDefaultActivation() })
    }

    @Test fun exactFilesTreeExposesSyncableDirectoryDescriptors() = verifyJourney { resolver, tree, _, _, _ ->
        LibraryProviderDirectoryProbe.verify(resolver, tree)
    }

    @Test fun grantedTreeCanStageAndPublishOneSyntheticFileWithoutOverwriting() = verifyJourney(probeWrite = true)

    @Test fun defaultLibraryExportsItsOriginalAndAcceptedEditIntoANewFilesBackup() = verifyJourney(exportBackup = true)

    @Test fun durableFolderSelectionReopensExactCatalogAndRefusesRevocation() {
        lateinit var context: Context
        lateinit var spool: File
        lateinit var pointer: File
        verifyJourney(revokeAfterProbe = { tree ->
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val retained = pointer.readBytes()
            val late = assertThrows(RepositoryFailure::class.java) {
                runBlocking {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, catalog ->
                        assertEquals(1, catalog.list(null, 2).size)
                        app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        "Unpublishable after grant loss"
                    }
                }
            }
            assertEquals("library_reconnect_required", late.error.safeMessageKey)
            assertTrue(retained.contentEquals(pointer.readBytes()))
            assertTrue(spool.list().orEmpty().isEmpty())
        }) { resolver, tree, _, selected, scratch ->
            val files = File(scratch, "selection-fixture").apply { check(mkdir()) }
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            }
            spool = File(files, "verify").apply { check(mkdir()) }
            val notSelected = assertThrows(RepositoryFailure::class.java) {
                runBlocking { AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, _ -> error("Unexpected selection") } }
            }
            assertEquals("library_folder_not_selected", notSelected.error.safeMessageKey)
            assertEquals(listOf("verify"), files.list().orEmpty().toList())
            val settings = File(files, "library-settings").apply { check(mkdir()) }
            val markerDir = File(settings, "folder-verifications").apply { check(mkdir()) }
            val grantDir = File(settings, "folder-grants").apply { check(mkdir()) }
            val grantId = "aaaaaaaa-aaaa-4aaa-8aaa-000000000011"
            val generation = selected.generationId
            val grant = checksummed("pristinepage-library-grant-v1\n$grantId\n1\n$tree\n")
            File(grantDir, grantId).writeBytes(grant)
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val markerUri = DocumentsContract.buildDocumentUriUsingTree(tree, "$rootId/generations/$generation.complete")
            val marker = checkNotNull(resolver.openInputStream(markerUri)).use { input ->
                val result = ByteArrayOutputStream()
                repeat(256) {
                    val next = input.read()
                    if (next == -1) return@use result.toByteArray()
                    result.write(next)
                }
                error("Expected a bounded generation marker")
            }
            pointer = File(settings, "active-library")
            val support = File(markerDir, "$grantId-$generation")
            fun selectWith(markerBytes: ByteArray) {
                support.writeBytes(markerBytes)
                pointer.writeBytes(checksummed("pristinepage-active-library-v2\n${selected.libraryId}\n2\n$grantId\n$generation\n${sha256(grant + markerBytes)}\n"))
            }
            selectWith(marker)
            runBlocking {
                val album = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
                repeat(2) {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { activation, catalog ->
                        assertEquals(LibraryActivation(selected.libraryId, 2), activation)
                        assertEquals(2L, catalog.load(album).pages.single().recipeRevision)
                        assertFalse(files.walkTopDown().any { it.name == "original.bin" })
                    }
                }
                val stale = runCatching {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, catalog ->
                        pointer.writeBytes(checksummed("pristinepage-active-library-v1\n${selected.libraryId}\n3\n-\n-\n"))
                        try {
                            val obsolete = runCatching { catalog.load(album) }.exceptionOrNull() as RepositoryFailure
                            assertEquals("library_reconnect_required", obsolete.error.safeMessageKey)
                        } finally { selectWith(marker) }
                    }
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_reconnect_required", stale.error.safeMessageKey)
                val wrong = marker.copyOf()
                wrong[wrong.lastIndex - 1] = if (wrong[wrong.lastIndex - 1] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
                selectWith(wrong)
                val refused = runCatching {
                    AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, _ -> error("Changed recorded catalog crossed admission") }
                }.exceptionOrNull() as RepositoryFailure
                assertEquals("library_reconnect_required", refused.error.safeMessageKey)
                selectWith(marker)
            }
        }
        val retained = pointer.readBytes()
        val lost = assertThrows(RepositoryFailure::class.java) {
            runBlocking { AndroidLibraryStartup(context).useSelectedCatalog(spool) { _, _ -> error("Revoked folder returned a catalog") } }
        }
        assertEquals("library_reconnect_required", lost.error.safeMessageKey)
        assertTrue(retained.contentEquals(pointer.readBytes()))
    }

    private fun checksummed(body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.US_ASCII)
        return bytes + "${sha256(bytes)}\n".toByteArray(Charsets.US_ASCII)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun populatingAWorkingRoomCatalogDoesNotMaterializeTheOriginal() = verifyJourney { _, _, reader, selected, scratch ->
        runBlocking {
            reader.openWorkingCatalog(selected) { catalog ->
                val sourceAlbum = DocumentId("aaaaaaaa-aaaa-4aaa-8aaa-000000000001")
                val album = catalog.load(sourceAlbum)
                assertEquals("Synthetic folder test", album.document.title)
                assertEquals(2L, album.document.documentRevision)
                assertEquals(1, album.pages.size)
                assertEquals(2L, album.pages.single().recipeRevision)
                assertNotNull(scratch.walkTopDown().firstOrNull { it.name == "working.sqlite" })
                assertFalse(scratch.walkTopDown().any { it.name == "original.bin" || it.name == "objects" })
            }
            repeat(4) {
                val canceled = runCatching {
                    reader.openWorkingCatalog(selected) { throw CancellationException("Canceled after the catalog opened") }
                }
                assertTrue(canceled.exceptionOrNull() is CancellationException)
            }
            reader.openWorkingCatalog(selected) { catalog -> assertEquals(1, catalog.list(null, 2).size) }
        }
        assertTrue("Only this attempt's own working catalog is retired", scratch.list().orEmpty().isEmpty())
    }

    private fun verifyJourney(
        probeWrite: Boolean = false,
        exportBackup: Boolean = false,
        revokeAfterProbe: ((Uri) -> Unit)? = null,
        probe: ((ContentResolver, Uri, AndroidLibraryTreeRead, AndroidLibraryTreeRead.Verified, File) -> Unit)? = null,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val folder = InstrumentationRegistry.getArguments().getString("libraryFixtureFolder")
            ?.takeIf { it.matches(Regex("PristinePage-tree-[0-9a-f]{12}")) } ?: error("No exact synthetic folder was staged")
        val arguments = InstrumentationRegistry.getArguments()
        val originalDigest = arguments.getString("libraryFixtureOriginal")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("No populated synthetic folder was staged")
        val expected = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Documents/$folder")
        val home = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "home:$folder")
        val exactFixtureTrees = setOf(expected.toString(), home.toString())
        val existing = { app.contentResolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) } }
        val before = existing()
        assertFalse(before.keys.any { it in exactFixtureTrees })
        // API 28 may expose filesDir through /data/user/0 rather than the canonical app-owned storage path.
        val scratch = File(app.filesDir.canonicalFile, "tree-read-${UUID.randomUUID()}").apply { check(mkdir()) }
        val platform = LibraryFolderPickerContract()
        val result = AtomicReference<ActivityResult>()
        val receipt = CountDownLatch(1)
        val registration = compose.runOnUiThread {
            val activity = compose.activity
            val intent = platform.createIntent(activity, Unit).putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(requireNotNull(expected.authority), DocumentsContract.getTreeDocumentId(expected)))
            val launcher = activity.activityResultRegistry.register("library-files-native-witness", ActivityResultContracts.StartActivityForResult()) {
                result.set(it)
                receipt.countDown()
            }
            launcher.launch(intent)
            (requireNotNull(intent.component).packageName) to launcher
        }
        try {
            val picker = registration.first
            // Files may briefly show navigation controls before resolving the initial URI. Do not open the drawer during that transition.
            val labels = setOf("SELECT", "Select", "SÉLECTIONNER", "Sélectionner", "Use this folder", "USE THIS FOLDER", "Choose this folder")
            val choice = await(instrumentation, picker, "exact fixture and affirmative action") {
                it.hasText(folder) && it.hasText("catalogs") && it.hasText("generations") && labels.any { name -> it.find(name) != null }
            }
            val button = labels.firstNotNullOfOrNull { choice.find(it) } ?: error("Files did not expose a folder confirmation")
            tap(instrumentation, button)
            if (!receipt.await(4, TimeUnit.SECONDS)) {
                val confirm = await(instrumentation, picker, "optional Files confirmation") { it.allowButton() != null }
                tap(instrumentation, confirm.allowButton() ?: error("No Files confirmation"))
            }
            assertTrue("Files did not return a selection", receipt.await(10, TimeUnit.SECONDS))
            val raw = requireNotNull(result.get())
            val selection = platform.parseResult(raw.resultCode, raw.data)
            val picked = selection as? LibraryFolderPickerContract.Picked ?: error(
                "Files returned $selection; result=${raw.resultCode}; flags=${raw.data?.flags}; " +
                    "exactTree=${raw.data?.data?.toString() == expected.toString()}; clipItems=${raw.data?.clipData?.itemCount}; " +
                    "clipMatches=${raw.data?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri?.toString() == expected.toString()}; " +
                    resultShape(raw.data?.data, expected, folder),
            )
            assertTrue("Files returned a tree other than the exact synthetic directory", picked.uri.toString() in exactFixtureTrees)
            if (arguments.getString("libraryExpectHome") == "1") {
                assertTrue("Files did not return the exact Documents namespace", picked.uri.toString() == home.toString())
            }
            if (exportBackup) {
                val temporary = AndroidLibraryWritableFolder(app)
                val other = DocumentsContract.buildTreeDocumentUri(requireNotNull(picked.uri.authority), "home:${folder}-other")
                assertTrue(runBlocking { temporary.availableFromFilesResult(picked.uri) } > 0)
                val sibling = assertThrows(IOException::class.java) { runBlocking { temporary.availableFromFilesResult(other) } }
                assertEquals("The exact library folder is not writable", sibling.message)
                var checks = 0
                val drift = object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this
                    override fun checkUriPermission(uri: Uri, pid: Int, uid: Int, modeFlags: Int): Int {
                        assertEquals(picked.uri, uri)
                        // Checks 1–2 guard directory query; 3 guards open, and 4 must guard delivery of that descriptor.
                        return if (++checks >= 4) PackageManager.PERMISSION_DENIED else super.checkUriPermission(uri, pid, uid, modeFlags)
                    }
                }
                val revoked = assertThrows(IOException::class.java) {
                    runBlocking { AndroidLibraryWritableFolder(drift).availableFromFilesResult(picked.uri) }
                }
                assertEquals("The exact library folder is not writable", revoked.message)
                assertEquals(4, checks)
                assertEquals("Checking temporary write access must not persist any permission", before, existing())
            }
            val handoff = LibraryFolderGrantHandoff(app)
            val uri = handoff.persistRead(picked)
            assertEquals(before + (uri.toString() to (true to false)), existing())
            val first = runBlocking { AndroidLibraryTreeRead(app, scratch).verify(uri) }
            assertEquals("9e940d7b-76c7-45ea-b5e4-0ad86f26ea13", first.libraryId)
            assertEquals("25c49d9b-02d8-418f-b982-154980d89cfb", first.generationId)
            assertEquals(1, first.albums)
            assertEquals(1, first.scans)
            // Promote only this exact fixture grant while the original picker recipient still owns its transient write permission.
            val writeObservation = if (probeWrite) runCatching {
                val destination = AndroidLibraryWritableFolder(app)
                assertThrows(IOException::class.java) { runBlocking { destination.availableBytes(uri) } }
                LibraryProviderWriteProbe.verify(app.contentResolver, uri) {
                    handoff.persistWrite(picked).also {
                        assertTrue(runBlocking { destination.availableBytes(it) } > 0)
                        val alias = if (it == home) expected else home
                        assertThrows(IOException::class.java) { runBlocking { destination.availableBytes(alias) } }
                        assertTrue(runBlocking { destination.availableBytes(it) } > 0)
                    }
                }
                assertThrows(IOException::class.java) { runBlocking { destination.availableBytes(uri) } }
            } else if (exportBackup) runCatching {
                val flag = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                val backup = try { runBlocking { LibraryDefaultFolderExportProbe.verify(app, picked) } } finally {
                    if (existing()[uri.toString()]?.second == true) app.contentResolver.releasePersistableUriPermission(uri, flag)
                }
                try { reopenExportedChild(app, instrumentation, uri, backup, scratch) }
                finally { backup.assertSourceUnchanged() }
            } else null
            val different = DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), "primary:Documents/${folder}-different")
            val wrongRoot = assertThrows(IOException::class.java) {
                runBlocking { AndroidLibraryTreeRead(app, scratch).verify(different, first) }
            }
            assertEquals("The verified library belongs to a different selected folder", wrongRoot.message)
            compose.runOnUiThread { registration.second.unregister() }
            compose.activityRule.scenario.recreate()
            val again = runBlocking { AndroidLibraryTreeRead(app, scratch).verify(Uri.parse(uri.toString()), first) }
            assertEquals(first.generationId, again.generationId)
            val fixtureBlob = SourceBlobId("aaaaaaaa-aaaa-4aaa-8aaa-000000000003")
            val bytes = ByteArrayOutputStream()
            runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, bytes) }
            assertTrue(bytes.size() > 65_536)
            assertEquals(originalDigest, MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) })
            val unrelated = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, SourceBlobId(UUID.randomUUID().toString()), unrelated) }
            }
            assertEquals(0, unrelated.size())

            var canceledWrites = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    val owner = requireNotNull(currentCoroutineContext()[Job])
                    val canceledSink = onWrite {
                        if (++canceledWrites == 1) owner.cancel()
                    }
                    AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, canceledSink)
                }
            }
            assertEquals("Cancellation must prevent the next chunk from reaching the sink", 1, canceledWrites)
            val observation = probe?.let { runCatching { it(app.contentResolver, uri, AndroidLibraryTreeRead(app, scratch), again, scratch) } }
            if (revokeAfterProbe != null) {
                observation?.getOrThrow()
                revokeAfterProbe(uri)
            } else {
                var writes = 0
                val disposable = onWrite { length ->
                    if (++writes == 1) {
                        assertTrue("Revocation must occur before the entire original is copied", length in 1 until bytes.size())
                        app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                assertThrows(IOException::class.java) {
                    runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, disposable) }
                }
                assertTrue("The probe must revoke from an in-progress stream, not at admission", writes > 0)
            }
            assertEquals(before, existing())
            assertThrows(IOException::class.java) { runBlocking { AndroidLibraryTreeRead(app, scratch).verify(uri, first) } }
            val afterRevocation = ByteArrayOutputStream()
            assertThrows(IOException::class.java) {
                runBlocking { AndroidLibraryTreeRead(app, scratch).copyOriginal(again, fixtureBlob, afterRevocation) }
            }
            assertEquals(0, afterRevocation.size())
            writeObservation?.getOrThrow()
            observation?.getOrThrow()
        } finally {
            for (fixtureTree in exactFixtureTrees) {
                existing()[fixtureTree]?.let { (read, write) ->
                    val modes = (if (read) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if ((probeWrite || exportBackup) && write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    if (modes != 0) app.contentResolver.releasePersistableUriPermission(Uri.parse(fixtureTree), modes)
                }
            }
            val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (activePackage == registration.first) instrumentalBack(instrumentation)
        }
    }

    private fun reopenExportedChild(app: Context, instrumentation: Instrumentation, parent: Uri, backup: LibraryDefaultFolderExportProbe.Backup, scratch: File) {
        val authority = requireNotNull(parent.authority)
        val id = "${DocumentsContract.getTreeDocumentId(parent)}/${backup.receipt.folderName}"
        val expected = DocumentsContract.buildTreeDocumentUri(authority, id)
        val resolver = app.contentResolver
        val before = resolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) }
        check(expected.toString() !in before)
        val platform = LibraryFolderPickerContract()
        val result = AtomicReference<ActivityResult>()
        val received = CountDownLatch(1)
        val registration = compose.runOnUiThread {
            val activity = compose.activity
            val intent = platform.createIntent(activity, Unit)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(authority, id))
            val launcher = activity.activityResultRegistry.register("library-backup-child-witness", ActivityResultContracts.StartActivityForResult()) {
                result.set(it)
                received.countDown()
            }
            launcher.launch(intent)
            requireNotNull(intent.component).packageName to launcher
        }
        try {
            val labels = setOf("SELECT", "Select", "SÉLECTIONNER", "Sélectionner", "Use this folder", "USE THIS FOLDER", "Choose this folder")
            val choice = await(instrumentation, registration.first, "completed backup child") { root ->
                root.hasText(backup.receipt.folderName) && root.hasText("catalogs") && root.hasText("generations") &&
                    labels.any { root.find(it) != null }
            }
            tap(instrumentation, checkNotNull(labels.firstNotNullOfOrNull { choice.find(it) }))
            if (!received.await(4, TimeUnit.SECONDS)) {
                val confirmed = await(instrumentation, registration.first, "optional backup folder confirmation") { it.allowButton() != null }
                tap(instrumentation, checkNotNull(confirmed.allowButton()))
            }
            assertTrue(received.await(10, TimeUnit.SECONDS))
            val raw = checkNotNull(result.get())
            val picked = platform.parseResult(raw.resultCode, raw.data) as LibraryFolderPickerContract.Picked
            assertEquals(expected.toString(), picked.uri.toString())
            assertEquals(expected, LibraryFolderGrantHandoff(app).persistRead(picked))
            assertEquals(before + (expected.toString() to (true to false)),
                resolver.persistedUriPermissions.associate { it.uri.toString() to (it.isReadPermission to it.isWritePermission) })
            val reader = AndroidLibraryTreeRead(app, scratch)
            val verified = runBlocking { reader.verify(expected) }
            assertEquals(backup.receipt.libraryId, verified.libraryId)
            assertEquals(backup.receipt.generationId, verified.generationId)
            var file: File? = null
            val budget = ApplicationImageBudget.create(64)
            val decoder = AndroidSourceInspector(budget)
            val output = OutputSpec(backup.inspection.normalizedSize, RenderPurpose.PREVIEW)
            lateinit var escaped: ScopedCommittedOriginals
            val files = File(app.filesDir.canonicalFile, "backup-activation-witness-${UUID.randomUUID()}").apply { check(mkdir()) }
            val startup = AndroidLibraryStartup(object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = files
            })
            val loanDirectory = File(files, "verify").apply { check(mkdir()) }
            val settings = File(files, "library-settings").apply { check(mkdir()) }
            val grantDir = File(settings, "folder-grants").apply { check(mkdir()) }
            val markers = File(settings, "folder-verifications").apply { check(mkdir()) }
            val grantId = UUID.randomUUID().toString()
            val grantBytes = checksummed("pristinepage-library-grant-v1\n$grantId\n1\n$expected\n")
            val markerUri = DocumentsContract.buildDocumentUriUsingTree(expected, "$id/generations/${verified.generationId}.complete")
            val generationBytes = checkNotNull(resolver.openInputStream(markerUri)).use { input ->
                val out = ByteArrayOutputStream()
                repeat(257) {
                    val byte = input.read()
                    if (byte == -1) return@use out.toByteArray()
                    out.write(byte)
                }
                error("The marker exceeded its maximum extent")
            }
            File(grantDir, grantId).writeBytes(grantBytes)
            File(markers, "$grantId-${verified.generationId}").writeBytes(generationBytes)
            val pointer = File(settings, "active-library")
            val recorded = checksummed("pristinepage-active-library-v2\n${verified.libraryId}\n2\n$grantId\n${verified.generationId}\n${sha256(grantBytes + generationBytes)}\n")
            pointer.writeBytes(recorded)
            runBlocking {
                startup.useSelectedLibrary(loanDirectory) { activation, catalog, sources ->
                    assertEquals(LibraryActivation(verified.libraryId, 2), activation)
                    escaped = sources
                    val page = catalog.load(backup.album).pages.single()
                    assertEquals(backup.page, page.pageId)
                    assertEquals(backup.revision, page.recipeRevision)
                    assertEquals(backup.source, page.sourceId)
                    val producer = backup.producer
                    val wrong = producer.copy(source = producer.source.copy(createdAt = producer.source.createdAt + 1))
                    val refused = runCatching {
                        sources.withOriginal(page.sourceId, wrong) { _, _ -> error("The row-only mismatch reached the original") }
                    }.exceptionOrNull() as RepositoryFailure
                    assertEquals(OperationErrorCode.INTEGRITY_FAILED, refused.error.code)
                    val bound = ScopedCommittedOriginals { source, _, consume -> sources.withOriginal(source, producer, consume) }
                    SourceDecoder(bound, decoder).decode(page.sourceId.value, output).use {
                        assertEquals(backup.inspection, it.inspection)
                        assertTrue(budget.usedBytes > 0)
                        assertFalse(files.walkTopDown().any { original -> original.name == "original.bin" })
                    }
                }
            }
            assertEquals(0L, budget.usedBytes)
            assertTrue(checkNotNull(loanDirectory.list()).isEmpty())
            assertThrows(IOException::class.java) {
                runBlocking { escaped.withOriginal(backup.source, null) { _, _ -> error("Retired catalog offered a file") } }
            }
            val successor = checksummed("pristinepage-active-library-v1\n${verified.libraryId}\n3\n-\n-\n")
            var nativeBeforeSwitch = false
            val stale = assertThrows(RepositoryFailure::class.java) {
                runBlocking {
                    startup.useSelectedLibrary(loanDirectory) { _, catalog, sources ->
                        val page = catalog.load(backup.album).pages.single()
                        val switching = SourceDecoder(sources) { original, digest ->
                            val decoded = decoder.decodeFile(original, digest)
                            try {
                                pointer.writeBytes(successor)
                                nativeBeforeSwitch = true
                                decoded
                            } catch (failure: Throwable) {
                                decoded.close()
                                throw failure
                            }
                        }
                        switching.decode(page.sourceId.value, output).use { error("Stale activation delivered native pixels") }
                    }
                }
            }
            assertTrue(nativeBeforeSwitch)
            assertEquals("library_reconnect_required", stale.error.safeMessageKey)
            assertArrayEquals(successor, pointer.readBytes())
            assertEquals(0L, budget.usedBytes)
            assertTrue(checkNotNull(loanDirectory.list()).isEmpty())
            runBlocking {
                reader.withVerifiedOriginal(verified, backup.blob) {
                    file = it
                    assertArrayEquals(backup.original, it.readBytes())
                    assertEquals(backup.inspection, decoder.inspect(it, backup.digest))
                }
            }
            assertFalse(checkNotNull(file).exists())
            runBlocking {
                val entered = CompletableDeferred<File>()
                val loan = launch {
                    reader.withVerifiedOriginal(verified, backup.blob) {
                        entered.complete(it)
                        awaitCancellation()
                    }
                }
                file = entered.await()
                loan.cancelAndJoin()
                assertTrue(loan.isCancelled)
            }
            assertFalse(checkNotNull(file).exists())
            file = null
            var revokedInsideCodec = false
            assertThrows(IOException::class.java) {
                runBlocking {
                    reader.openWorkingOriginals(verified) { sources ->
                        val revoking = SourceDecoder(sources) { original, digest ->
                            file = original
                            val decoded = decoder.decodeFile(original, digest)
                            try {
                                assertEquals(backup.inspection, decoded.inspection)
                                resolver.releasePersistableUriPermission(expected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                revokedInsideCodec = true
                                decoded
                            } catch (failure: Throwable) {
                                decoded.close()
                                throw failure
                            }
                        }
                        revoking.decode(backup.source.value, output).use { error("Revoked check delivered native pixels") }
                    }
                }
            }
            assertTrue(revokedInsideCodec)
            assertFalse(checkNotNull(file).exists())
            assertEquals(0L, budget.usedBytes)
            assertThrows(IOException::class.java) {
                runBlocking { reader.withVerifiedOriginal(verified, backup.blob) { error("Revoked backup lent an original") } }
            }
            assertTrue(checkNotNull(scratch.list()).isEmpty())
        } finally {
            compose.runOnUiThread { registration.second.unregister() }
            if (resolver.persistedUriPermissions.any { it.uri.toString() == expected.toString() && it.isReadPermission }) {
                resolver.releasePersistableUriPermission(expected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private fun onWrite(receiveCount: (Int) -> Unit): OutputStream = object : OutputStream() {
        override fun write(value: Int) = error("Expected a bounded bulk write")
        override fun write(bytes: ByteArray, offset: Int, length: Int) = receiveCount(length)
    }

    /** Report only structural information about a returned URI; no raw path or non-fixture title. */
    private fun resultShape(actual: Uri?, expected: Uri, folder: String): String {
        if (actual == null) return "No data URI"
        val parts = actual.pathSegments
        val id = if (DocumentsContract.isTreeUri(actual)) DocumentsContract.getTreeDocumentId(actual) else null
        val expectedId = DocumentsContract.getTreeDocumentId(expected)
        val relative = id?.substringAfter(':', missingDelimiterValue = "")
        val exactAuthority = actual.encodedAuthority == expected.encodedAuthority
        val canonical = id != null && exactAuthority &&
            DocumentsContract.buildTreeDocumentUri(requireNotNull(expected.authority), id).toString() == actual.toString()
        return "content=${actual.scheme == "content"}; authorityMatches=$exactAuthority; tree=${id != null}; " +
            "segments=${parts.size}; documentSegment=${parts.getOrNull(2) == "document"}; extras=${actual.encodedQuery != null || actual.encodedFragment != null}; " +
            "canonical=$canonical; primary=${id?.startsWith("primary:")}; exactId=${id == expectedId}; " +
            "exactRelative=${relative == "Documents/$folder"}; fixtureLeaf=${relative?.trimEnd('/')?.endsWith("/$folder")}; " +
            "trailingSlash=${id?.endsWith('/')}; slashAfterVolume=${relative?.startsWith('/')}; relativeSegments=${relative?.split('/')?.size}"
    }

    private fun await(instrumentation: Instrumentation, picker: String, stage: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 12_000
        var last: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) last = root
            if (root?.packageName?.toString() == picker && predicate(root)) return root
            SystemClock.sleep(125)
        }
        val foreground = last?.packageName?.toString()
        val observed = if (foreground == picker) buildList { last.collectLabels(this) } else emptyList()
        error("Files did not reach $stage; foreground=$foreground; visible=$observed")
    }

    private fun AccessibilityNodeInfo.collectLabels(output: MutableList<String>) {
        if (output.size >= 32) return
        if (isVisibleToUser && (text != null || contentDescription != null)) {
            output += listOf(text, contentDescription, viewIdResourceName).filterNotNull()
                .joinToString(" / ") { value -> value.take(100).toString().filterNot(Char::isISOControl) }
        }
        for (i in 0 until childCount) getChild(i)?.collectLabels(output)
    }
    private fun AccessibilityNodeInfo.hasText(value: String) = find(value) != null
    private fun AccessibilityNodeInfo.allowButton() = find("Allow") ?: find("ALLOW") ?: find("Autoriser") ?: find("AUTORISER")

    private fun AccessibilityNodeInfo.find(value: String): AccessibilityNodeInfo? {
        if (isVisibleToUser && (text?.toString() == value || contentDescription?.toString() == value)) return this
        for (i in 0 until childCount) getChild(i)?.find(value)?.let { return it }
        return null
    }

    private fun tap(instrumentation: Instrumentation, node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        check(node.isEnabled && node.isVisibleToUser && !bounds.isEmpty)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally { event.recycle() }
        }
    }

    private fun instrumentalBack(instrumentation: Instrumentation) {
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
}
