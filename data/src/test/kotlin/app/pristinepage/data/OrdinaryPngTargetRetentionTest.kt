package app.pristinepage.data

import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.core.recipe.SourceBinding
import app.pristinepage.data.db.*
import app.pristinepage.data.files.DerivedFileTarget
import app.pristinepage.data.files.DerivedFiles
import app.pristinepage.data.files.DerivedKind
import app.pristinepage.data.files.DirectorySync
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

private typealias BorrowFinal = RoomTerminalPngReads.RetainedFinal
private typealias ConsumeFinal = (BorrowFinal) -> Unit

class OrdinaryPngTargetRetentionTest {
    @Test fun productionParticipantsCannotBeConstructedFromIndependentDatabaseAndFileComponents() {
        for (type in listOf(RoomRenderFileExecution::class.java, RoomRenderCompletion::class.java,
            RoomTerminalPngReads.Retained::class.java)) {
            assertFalse("${type.simpleName} accepts independent database and file components",
                type.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.any { it == PristineDatabase::class.java } &&
                        constructor.parameterTypes.any { it == DerivedFiles::class.java }
                })
        }
    }

    @Test fun privateStoreIsTheProductionIssuerOfTheExportLifetime() {
        assertTrue("PrivateImportStore does not own an ordinary export lifetime",
            PrivateImportStore::class.java.declaredFields.any { it.type.simpleName == "OrdinaryExportLifetime" })
    }

    @Test fun ordinaryExportBindingDoesNotExposeRawParticipants() {
        val rawTypes = listOf(
            PristineDatabase::class.java, ExportSnapshotDao::class.java, CatalogDao::class.java, JobLeaseDao::class.java,
            MetadataAccess::class.java, ExportSnapshotQueries::class.java, CatalogQueries::class.java,
            JobLeaseQueries::class.java, JobLeaseCommands::class.java, RenderIntentQueries::class.java, RenderCompletionQueries::class.java,
            RoomRenderIntents::class.java, RoomTerminalRenderReads::class.java, FileOperationOwners::class.java,
            OrdinaryExportLifetime::class.java, DerivedFiles::class.java, File::class.java, Path::class.java,
            PrivateImportStore.ExportComposition::class.java,
        )
        fun isRaw(type: Class<*>) = rawTypes.any { it.isAssignableFrom(type) }
        val binding = PrivateImportStore.OrdinaryExport::class.java
        // Inspect only the consumer surface, including JVM-visible Kotlin internal members. Do not instantiate a store or invoke any member.
        val methods = binding.declaredMethods.filterNot { method ->
            Modifier.isPrivate(method.modifiers) || (method.isSynthetic &&
                (method.name.startsWith("access\$") || method.name.endsWith("\$default")))
        }.filter { method -> isRaw(method.returnType) || method.parameterTypes.any(::isRaw) }
        val fields = binding.declaredFields.filter { !Modifier.isPrivate(it.modifiers) && isRaw(it.type) }
        val exposed = (methods.map { it.name.substringBefore('$') } + fields.map { it.name }).distinct().sorted()
        assertEquals("Ordinary export binding exposes raw participants", emptyList<String>(), exposed)
    }

    @Test fun privateStoreOffersGuardedOperationsWithoutReleasingTheCompositionInput() {
        val store = PrivateImportStore::class.java
        val binding = PrivateImportStore.OrdinaryExport::class.java
        val composition = PrivateImportStore.ExportComposition::class.java
        val guardedOperations = setOf(RoomFreshExportAssembly::class.java, RoomTerminalPngReads.Retained::class.java,
            RoomRenderFileExecution::class.java, RoomRenderCompletion::class.java)
        fun callableMethods(type: Class<*>) = type.declaredMethods.filter { !Modifier.isPrivate(it.modifiers) && !it.isSynthetic }
        assertTrue(callableMethods(store).any { it.returnType == binding })
        assertEquals(guardedOperations, callableMethods(binding).map { it.returnType }.toSet())
        assertTrue(composition.declaredConstructors.all { Modifier.isPrivate(it.modifiers) || it.isSynthetic })
        for (type in listOf(store, store.declaredClasses.single { it.simpleName == "Companion" }, binding,
            binding.declaredClasses.single { it.simpleName == "Companion" },
            composition.declaredClasses.single { it.simpleName == "Companion" })) {
            assertFalse("${type.simpleName} returns a raw composition input", callableMethods(type).any { it.returnType == composition })
        }
    }

    @Test fun retainedReadAndConsumeAreSynchronousAtTheBorrowBoundary() {
        val read = BorrowFinal::class.java.declaredMethods.single { it.name.substringBefore('$') == "read" }
        assertFalse("Borrow exposes a suspend read rather than a synchronous callback",
            read.parameterTypes.contains(Continuation::class.java))
        val retain = RoomTerminalPngReads.Retained::class.java.declaredMethods.single { it.name.substringBefore('$') == "withRetainedFinal" }
        assertTrue("Only metadata preparation may suspend, not the consuming callback", retain.parameterTypes.contains(Function1::class.java))
    }

    @Test fun unguardedTerminalAndRenderPrerequisitesWorkOnOwnedFiles() = runTest {
        val writer = writer()
        val terminal = terminalStore(writer.root)
        for (slot in 0..1) terminal.assertReceipt(slot, terminal.load(slot))
        writer.assertMoves()
        terminal.assertUnchanged()
    }

    @Test fun completionRetainsTheLaneThroughTheCommitNotJustTheMove() = runTest {
        val completing = writer()
        val contender = RenderFileExecutionTest.RenderFileFixture(completing.root, TransactionId(id(70))).apply { initialize() }
        assertEquals(completing.intent.finalPath, contender.intent.finalPath)
        val before = contender.row
        val beforeReads = contender.jobReads
        completing.onRecordInsert = {
            assertTrue(completing.inTransaction)
            assertFalse(contender.inTransaction)
            assertEquals("MOVED", completing.row.state)
            // A separate synthetic store attempts real execution during the published-row insert, not from the completing store's transaction participant.
            runBlocking { contender.assertBusyBeforeFilePhase() }
        }
        assertEquals(completing.intent.renderId, completing.complete())
        assertEquals("COMMITTED", completing.row.state)
        assertEquals("SUCCEEDED", completing.job.state)
        assertEquals(1, completing.records.size)
        assertSame(before, contender.row)
        assertEquals(beforeReads, contender.jobReads)
        assertArrayEquals(payload, completing.final.readBytes())
    }

    @Test fun invalidCoordinationFileRefusesWithoutStartingFilePhase() = runTest {
        val writer = writer()
        val marker = File(writer.root, "ownership/ordinary-derived-v1.lock")
        assertTrue(marker.mkdirs())
        val before = writer.row
        val beforeReads = writer.jobReads
        refuses(OperationErrorCode.STORAGE_FAILED, "ordinary_derived_store_lock_failed") {
            writer.execute(writer = { throw AssertionError("Invalid marker allowed staging") })
        }
        assertTrue(marker.isDirectory)
        assertSame(before, writer.row)
        assertEquals(beforeReads, writer.jobReads)
        assertFalse(checkNotNull(writer.stage.parentFile).exists())
    }

    @Test fun retainedFinalBlocksSameTargetWriterDuringHandoffAndNotAnIndependentRoot() = runTest {
        val contender = writer()
        // The independent metadata fixtures deliberately name the same physical target. This is not a valid duplicate Room row.
        val terminal = terminalStore(File(contender.root, "."), firstTarget = contender.intent.renderId)
        val independent = writer()
        assertEquals(contender.intent.finalPath, independent.intent.finalPath)
        assertNotEquals(contender.root, independent.root)

        terminal.retain(slot = 0) { borrow ->
            terminal.assertReceipt(0, terminal.use(0, borrow))
            // The scan has returned and the reader's DerivedFiles monitor is no longer held. This is the future copy/commit window.
            runBlocking {
                contender.assertBusyBeforeFilePhase()
                independent.assertMoves()
            }
            terminal.assertReceipt(0, terminal.use(0, borrow))
        }
        terminal.assertUnchanged()
    }

    @Test fun foreignRootOrIssuerCannotAuthorizeTheBorrowEvenWhenItsTerminalIdentitiesMatch() = runTest {
        val home = terminalStore()
        val foreign = terminalStore()
        // Matching logical identity and byte proof in two roots do not let the foreign owner protect the home file.
        val a = home.load(0)
        val b = foreign.load(0)
        assertEquals(a.renderId, b.renderId)
        assertEquals(a.transactionId, b.transactionId)
        assertEquals(a.sealSha256, b.sealSha256)
        assertEquals(a.observed, b.observed)
        assertEquals(home.prepared.snapshot.snapshotHash, foreign.prepared.snapshot.snapshotHash)
        val before = home.reads
        for (foreignProof in listOf(home.issue(foreign.lifetime), foreign.issue(home.lifetime), foreign.prepared, home.issue(null))) {
            refuses(OperationErrorCode.INTEGRITY_FAILED, "terminal_png_retention_binding_invalid") {
                home.retain(slot = 0, assemblyProof = foreignProof) { throw AssertionError("Foreign authority issued a home borrow") }
            }
        }
        assertEquals(before, home.reads)
        home.retain(0) { borrow -> home.assertReceipt(0, home.use(0, borrow)) }
        home.assertUnchanged()
        foreign.assertUnchanged()
    }

    @Test fun borrowReadsOnlyItsAuthenticatedOrdinalEvenWhenOtherTerminalBytesAreIdentical() = runTest {
        val terminal = terminalStore()
        val a = terminal.load(0)
        val b = terminal.load(1)
        assertEquals(a.size, b.size)
        assertEquals(a.observed, b.observed)
        assertNotEquals(a.renderId, b.renderId)
        assertNotEquals(terminal.prepared.orderedJobIds[0], terminal.prepared.orderedJobIds[1])

        val before = terminal.reads
        for (ordinal in listOf(-1, 2)) {
            refuses(OperationErrorCode.INTEGRITY_FAILED, "terminal_png_retention_binding_invalid") {
                terminal.retain(ordinal) { throw AssertionError("Invalid ordinal obtained a borrow") }
            }
        }
        refuses(OperationErrorCode.INVALID_INPUT, "terminal_png_invalid_cap") {
            terminal.retain(0, maxBytes = 0) { throw AssertionError("Invalid cap obtained a borrow") }
        }
        assertEquals(before, terminal.reads)
        terminal.retain(0) { borrow ->
            terminal.assertReceipt(0, terminal.use(0, borrow))
            // A valid but differently bound codec cannot redirect the borrowed file to its own slot.
            borrow.read { file, _, _, _, _ ->
                assertEquals(terminal.final(0), file)
                assertNotEquals(terminal.final(1), file)
                val bytes = file.readBytes()
                assertArrayEquals(payload, bytes)
                RoomTerminalPngReads.ObservedIdentity(sha256(bytes), bytes.size.toLong(), rasterHash)
            }
        }
        // Slot one is itself valid, not a malformed candidate that any reader would refuse.
        terminal.retain(1) { borrow -> terminal.assertReceipt(1, terminal.use(1, borrow)) }
        terminal.assertUnchanged()
    }

    @Test fun cancellationAndConsumerFailureReleaseTheLaneWithoutRevivingAnOldBorrow() = runTest {
        val nextWriter = writer()
        val terminal = terminalStore(nextWriter.root)
        val entered = CompletableDeferred<BorrowFinal>()
        val finish = CountDownLatch(1)
        val holder = launch(Dispatchers.IO) {
            terminal.retain(0) { borrow ->
                terminal.use(0, borrow) {
                    entered.complete(borrow)
                    assertTrue("Timed out waiting for the legitimate read to unwind", finish.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val canceledBorrow = try {
            val borrow = entered.await()
            // While the legitimate synchronous read is still active, another thread must refuse before readback.
            assertExpired(terminal, borrow)
            holder.cancel()
            // Cancellation is not evidence that the blocked readback has actually returned.
            assertRefuses(OperationErrorCode.REVISION_CONFLICT, "ordinary_export_store_in_use") { terminal.lifetime.close() }
            assertEquals(0, terminal.closeCalls)
            nextWriter.assertBusyBeforeFilePhase()
            borrow
        } finally {
            finish.countDown()
        }
        holder.join()
        assertTrue(holder.isCancelled)

        var failedBorrow: BorrowFinal? = null
        val consumerFailure = IllegalStateException("synthetic failure after successful readback")
        try {
            terminal.retain(0) { borrow ->
                assertExpired(terminal, canceledBorrow)
                terminal.assertReceipt(0, terminal.use(0, borrow))
                failedBorrow = borrow
                throw consumerFailure
            }
            fail("Consumer exception must propagate")
        } catch (failure: IllegalStateException) {
            // Coroutine stack-trace recovery may rethrow a copy whose cause is the exact callback exception.
            assertTrue(failure === consumerFailure || failure.cause === consumerFailure)
            assertEquals(consumerFailure.message, failure.message)
        }

        // A legitimate distinct writer is now able to move its own target, without touching the retained final files.
        nextWriter.assertMoves()
        terminal.retain(0) { borrow ->
            assertExpired(terminal, canceledBorrow)
            assertExpired(terminal, checkNotNull(failedBorrow))
            terminal.assertReceipt(0, terminal.use(0, borrow))
        }
        terminal.assertUnchanged()
        terminal.lifetime.close()
        assertEquals(1, terminal.closeCalls)
        val before = terminal.reads
        refuses(OperationErrorCode.STORAGE_FAILED, "ordinary_export_store_closed") {
            terminal.retain(0) { throw AssertionError("A closed store issued another borrow") }
        }
        assertEquals(before, terminal.reads)
    }

    @Test fun closeClaimRefusesNewScopeWhileDatabaseCloseIsStillOutsideTheMonitor() = runTest {
        val closing = CompletableDeferred<Unit>()
        val allowClose = CountDownLatch(1)
        val terminal = terminalStore()
        terminal.onClose = {
            closing.complete(Unit)
            assertTrue("Repeated admission or close waited on database close", allowClose.await(10, TimeUnit.SECONDS))
        }
        val closer = launch(Dispatchers.IO) { terminal.lifetime.close() }
        try {
            closing.await()
            val before = terminal.reads
            refuses(OperationErrorCode.STORAGE_FAILED, "ordinary_export_store_closed") {
                terminal.retain(0) { throw AssertionError("Closing store issued a borrow") }
            }
            assertEquals(before, terminal.reads)
            terminal.lifetime.close()
            assertEquals(1, terminal.closeCalls)
        } finally {
            allowClose.countDown()
        }
        closer.join()
    }

    private fun assertExpired(terminal: TerminalStore, borrow: BorrowFinal) {
        val before = terminal.reads
        assertRefuses(OperationErrorCode.INTEGRITY_FAILED, "terminal_png_retention_ended") {
            borrow.read { _, _, _, _, _ -> throw AssertionError("Ended borrow reached readback") }
        }
        assertEquals(before, terminal.reads)
    }

    private suspend fun writer(root: File? = null) =
        (root?.let { RenderFileExecutionTest.RenderFileFixture(it) } ?: RenderFileExecutionTest.RenderFileFixture()).apply { initialize() }

    private suspend fun terminalStore(
        root: File = Files.createTempDirectory(File("build").toPath(), "png-retention-test-").toFile().canonicalFile,
        firstTarget: RenderId = RenderId(id(60)),
    ) = TerminalStore(root).apply { initialize(firstTarget) }

    private suspend fun RenderFileExecutionTest.RenderFileFixture.assertBusyBeforeFilePhase() {
        val before = row
        val beforeJob = job
        val beforeReads = jobReads
        val failure = withTimeout(1_000L) {
            refuses(OperationErrorCode.REVISION_CONFLICT, "ordinary_derived_store_busy") {
                execute(writer = { throw AssertionError("Busy writer opened the staging sink") },
                    readback = { _, _ -> throw AssertionError("Busy writer reached readback") })
            }
        }
        assertTrue(failure.retryable)
        assertEquals("INTENT", row.state)
        assertSame(before, row)
        assertSame(beforeJob, job)
        assertEquals("The existing lease/phase fence precedes target I/O; a busy attempt must not reach it", beforeReads, jobReads)
        assertFalse(checkNotNull(stage.parentFile).exists())
    }

    private suspend fun RenderFileExecutionTest.RenderFileFixture.assertMoves() {
        execute()
        assertEquals("MOVED", row.state)
        assertFalse(stage.exists())
        assertTrue(seal.isFile)
        assertArrayEquals(payload, final.readBytes())
    }

    private suspend fun RenderFileExecutionTest.RenderFileFixture.complete(): RenderId {
        val request = OrdinaryRenderExecution.requestFor(proof.page, proof.page.sourceId)
        val size = request.output.size
        val stages = listOf(
            StageRecord("source_decode_v1", 1, null, "OK", proof.page.sourceSize, proof.page.sourceSize, emptyList()),
            StageRecord("geometry_none_v1", 1, null, "EXACT_PERMUTATION", proof.page.sourceSize, size, emptyList()),
            StageRecord("none_v1", 1, null, "EXACT_IDENTITY", size, size, emptyList()),
        )
        return RenderResult(request, RenderStatus.OK, RgbImage(3, 2, 9, ByteArray(18) { (it + 1).toByte() }), stages, emptyList()).use {
            RoomRenderCompletion(executor, Dispatchers.Unconfined).complete(proof, lease, intent, it, cap,
                writer = { sink -> sink.write(payload) },
                readback = { file, checkpoint -> checkpoint(); rasterHash.takeIf { file.readBytes().contentEquals(payload) } })
        }
    }

    private class TerminalStore(private val root: File) {
        private val owners = FileOperationOwners(root, dispatcher = Dispatchers.Unconfined)
        var closeCalls = 0
            private set
        var onClose: () -> Unit = {}
        val lifetime = OrdinaryExportLifetime { closeCalls++; onClose() }
        private val catalog = MemoryMetadata()
        private val source = SourceRow(id(10), id(11), "a".repeat(64), 3, 2, 1, "srgb-v1", null, 1, "READY", 0)
        private val blob = SourceBlobRow(id(11), "b".repeat(64), 4, "image/png", 3, 2,
            "sources/${id(11)}/original.bin", "READY", 1, id(12), 0)
        private val recipe = Recipe(geometry = GeometryRecipe.None(SourceBinding(source.pixelSha256, size, source.conversionFingerprint)))
        private val setup = DerivedFiles(root, DirectorySync { false })
        private lateinit var slots: List<Slot>
        private val snapshotId = ExportSnapshotId(id(30))
        private val assembly = JobId(id(31))
        private lateinit var header: ExportSnapshotRow
        private lateinit var assemblyInput: ByteArray
        lateinit var prepared: RoomFreshExportAssembly.Prepared
            private set
        private val store = object : RoomFreshExportAssembly.SyntheticStore(), MetadataAccess by catalog {
            private val key = ExportAssemblyInput.key(snapshotId)

            override fun snapshotMetadataIsBounded(snapshotId: String) = query { true.takeIf { snapshotId == header.snapshotId } }
            override fun snapshot(id: String) = query { header.takeIf { id == it.snapshotId } }
            override fun exportPagesAreBounded(snapshotId: String) = query { snapshotId == header.snapshotId }
            override fun exportPages(snapshotId: String) = query { slots.map { it.frozen }.filter { it.snapshotId == snapshotId } }
            override fun renderJobIdByKey(key: String) = query { assembly.value.takeIf { key == this.key } }
            override fun assemblyBounds(id: String) = query { true.takeIf { id == assembly.value } }
            override fun assemblyIdentity(id: String) = query {
                AssemblyJobIdentityRow(assembly.value, "EXPORT_ASSEMBLY", key, ExportAssemblyInput.digest(assemblyInput),
                    snapshotId.value, assemblyInput).takeIf { id == assembly.value }
            }

            override fun renderJobReadIsBounded(jobId: String, expectedInputBytes: Int) = error("No freeze or job admission")
            override fun renderJobIdentity(jobId: String) = error("No freeze or job admission")
            override fun boundedRenderJobPinSourceIds(jobId: String) = error("No freeze or job admission")
            override fun insertSnapshot(row: ExportSnapshotRow) = error("Read-only fixture")
            override fun insertExportPage(row: ExportPageRow) = error("Read-only fixture")
            override fun insertExportJob(row: JobRow) = error("Read-only fixture")
            override fun exactInputMatches(id: String, snapshot: String, key: String, digest: String, input: ByteArray) = error("No assembly lease")
            override fun leaseBounds(id: String) = error("No assembly lease")
            override fun phaseMatches(id: String, state: String, attempt: Long, owner: String?, until: Long?) = error("No assembly lease")
            override fun admitPending(id: String, snapshot: String, key: String, digest: String, input: ByteArray, previousUpdatedAt: Long, updatedAt: Long) =
                error("No assembly lease")
            override fun job(id: String) = error("No assembly lease")
            override fun updateLease(row: JobRow, expectedGeneration: Long) = error("No assembly lease")
        }

        suspend fun initialize(firstTarget: RenderId) {
            catalog.sources[source.sourceId] = source
            catalog.blobs[blob.sourceBlobId] = blob
            slots = listOf(seed(0, firstTarget), seed(1, RenderId(id(61))))
            val document = DocumentId(id(32))
            val settings = ExportSettings(ExportFormat.PNG, slots.map { PageOutputSettings(PageId(it.frozen.originalPageId), output) },
                null, null, emptyList())
            val snapshotHash = ExportSnapshotIdentity.create(document, 1, settings, slots.map { it.frozen }).snapshotHash
            header = ExportSnapshotRow(snapshotId.value, document.value, 1, snapshotHash, ExportSettingsCodec.encode(settings), "FROZEN", 1, 100)
            assemblyInput = ExportAssemblyInput.encode(snapshotId, snapshotHash, slots.map { it.intent.jobId })
            // Use the real bounded snapshot/assembly reader. Only metadata rows are supplied by the test; there is no forged preparation or lease.
            prepared = issue(lifetime)
        }

        suspend fun issue(issuingLifetime: OrdinaryExportLifetime?) =
            RoomFreshExportAssembly.forSyntheticTests(store, owners, { _, _ -> error("No assembly admission") },
                { 10 }, { 20 }, Dispatchers.Unconfined, issuingLifetime).prepare(snapshotId)

        var reads = 0
            private set
        private fun <T> query(action: () -> T): T {
            check(catalog.inTransaction)
            return action()
        }

        private fun job(key: String) = slots.singleOrNull { it.identity.jobId == key }
        private val metadata = RoomTerminalRenderReads(store, profile, Dispatchers.Unconfined,
            { key, extent -> query { reads++; job(key)?.let { it.identity.input.size == extent } } },
            { key -> query { job(key)?.identity } },
            { key -> query { listOfNotNull(job(key)?.frozen?.sourceId) } },
            catalog::sourceReadIsBounded,
            { key -> query { job(key)?.journal?.attempt } },
            { key -> query { listOfNotNull(job(key)?.row?.renderId) } },
            { render, key, page, rev, width, height, cap -> query {
                job(key)?.row?.let { it.renderId == render && it.pageId == page && it.recipeRevision == rev &&
                    it.width == width && it.height == height && it.execution.size in 1..cap }
            } },
            { render -> query { slots.singleOrNull { it.row.renderId == render }?.row } },
            { key, attempt, extent -> query { job(key)?.journal?.takeIf { it.attempt == attempt }?.let { it.payload.size == extent } } },
            { key, attempt -> query { job(key)?.journal?.takeIf { it.attempt == attempt } } },
        )
        // Own reader instance independent of the writer instance. No force/sync on a terminal read.
        private val reader = RoomTerminalPngReads(metadata, DerivedFiles(root, DirectorySync { error("Read path attempted directory sync") }),
            Dispatchers.Unconfined)
        private val retained = RoomTerminalPngReads.Retained.forSyntheticTests(reader, owners, store, lifetime)

        private data class Slot(
            val frozen: ExportPageRow, val identity: FrozenRenderJobIdentityRow, val intent: RenderFileIntent,
            val row: RenderedPageRow, val journal: FileTransactionRow,
            val final: File, val stage: File, val seal: File, val originalSealBytes: ByteArray,
        )

        private suspend fun seed(ordinal: Int, renderId: RenderId): Slot {
            val pageId = PageId(id(20 + ordinal))
            val frozen = ExportPageRow(snapshotId.value, ordinal, pageId.value, source.sourceId, blob.sourceBlobId,
                blob.byteSha256, source.pixelSha256, 3, 2, source.conversionFingerprint, 1, 1, RecipeCodec.encode(recipe),
                RecipeCodec.hash(recipe), "[]".encodeToByteArray(), profile.providerDescriptor(), OrdinaryExportProfile.emptyAssetSetHash,
                profile.rendererVersion, 3, 2, 3, 2, "FAIL", null, null)
            val page = ExportPageSnapshot(frozen)
            val jobId = JobId(id(40 + ordinal))
            val request = OrdinaryRenderExecution.requestFor(page, page.sourceId)
            val job = RenderJobIdentity.create(request, emptyMap(), page.assetSetHash, page.conversionFingerprint,
                profile.rendererVersion, profile.encodingVersion, profile.backend, profile.precision)
            val identity = FrozenRenderJobIdentityRow(jobId.value, "PAGE_RENDER", job.idempotenceKey, job.canonicalInputSha256, job.canonicalInput)
            val intent = RenderFileIntent(TransactionId(id(50 + ordinal)), renderId, jobId, id(55), 1, 1, job.canonicalInputSha256,
                pageId, page.sourceId, page.sourceBlobId, page.sourceId, page.byteHash, page.pixelHash, 1, page.recipeHash, output)
            val target = DerivedFileTarget(DerivedKind.RENDER, renderId.value)
            val stage = File(root, intent.stagingPath)
            val seal = File(stage.parentFile, "derived-seal.json")
            val stages = listOf(
                StageRecord("source_decode_v1", 1, null, "OK", size, size, emptyList()),
                StageRecord("geometry_none_v1", 1, null, "EXACT_PERMUTATION", size, size, emptyList()),
                StageRecord("none_v1", 1, null, "EXACT_IDENTITY", size, size, emptyList()),
            )
            val proof = RoomFrozenRenderReads(store, { _, extent -> query { extent == identity.input.size } }, { query { identity } },
                { query { listOf(page.sourceId.value) } }, catalog::sourceReadIsBounded, profile, Dispatchers.Unconfined)
            // The real writer grammar creates the synthetic terminal fixture. The readback oracle below uses independent literals.
            val verified = proof.verified(jobId, page)
            val execution = RenderResult(request, RenderStatus.OK, RgbImage(3, 2, 9, ByteArray(18) { (it + 1).toByte() }),
                stages, emptyList()).use { OrdinaryRenderExecution.capture(verified, it) {} }
            assertEquals(rasterHash, execution.rasterSha256)
            val staged = setup.stage(intent.transactionId.value, target, cap, { it.write(payload) })
            val sealed = setup.seal(staged, OrdinaryRenderSealV1(intent.journalPayload()).encode(execution.rasterSha256))
            val final = setup.move(sealed)
            val ready = RenderedPageRow(renderId.value, jobId.value, page.sourceId.value, pageId.value, 1, execution.cacheKey, execution.bytes(),
                execution.sha256, intent.finalPath, staged.byteSha256, execution.rasterSha256, staged.byteCount, 3, 2, "OK", "READY")
            val committed = FileTransactionRow(intent.transactionId.value, "RENDER", jobId.value, "COMMITTED", intent.stagingPath,
                intent.finalPath, sealed.sealSha256, staged.byteSha256, staged.byteCount, intent.journalPayload(), 1, null, 2)
            return Slot(frozen, identity, intent, ready, committed, final, stage, seal, seal.readBytes())
        }

        private fun readback(slot: Int, beforeReturn: () -> Unit = {}) = RoomTerminalPngReads.Readback { file, dimensions, expectedRaster, byteCap, checkpoint ->
            assertFalse(catalog.inTransaction)
            assertEquals(slots[slot].final, file)
            assertEquals(size, dimensions)
            assertEquals(rasterHash, expectedRaster)
            assertEquals(cap, byteCap)
            checkpoint()
            val bytes = file.readBytes()
            assertArrayEquals(payload, bytes)
            beforeReturn()
            RoomTerminalPngReads.ObservedIdentity(sha256(bytes), bytes.size.toLong(), rasterHash)
        }

        fun final(slot: Int): File = slots[slot].final
        suspend fun load(slot: Int) = reader.load(prepared.orderedJobIds[slot], prepared.snapshot.pages[slot], cap, readback(slot))

        suspend fun retain(
            slot: Int, assemblyProof: RoomFreshExportAssembly.Prepared = prepared, maxBytes: Long = cap, consume: ConsumeFinal,
        ) = retained.withRetainedFinal(assemblyProof, slot, maxBytes, consume)

        fun use(slot: Int, borrow: BorrowFinal, beforeReturn: () -> Unit = {}) = borrow.read(readback(slot, beforeReturn))

        fun assertReceipt(slot: Int, receipt: RoomTerminalPngReads.Receipt) {
            val expected = slots[slot]
            assertEquals(expected.intent.renderId, receipt.renderId)
            assertEquals(expected.intent.transactionId, receipt.transactionId)
            assertEquals(expected.journal.sealSha256, receipt.sealSha256)
            assertEquals(size, receipt.size)
            assertEquals(RoomTerminalPngReads.ObservedIdentity(sha256(payload), payload.size.toLong(), rasterHash), receipt.observed)
        }

        fun assertUnchanged() {
            for (slot in slots) {
                assertFalse(slot.stage.exists())
                assertArrayEquals(slot.originalSealBytes, slot.seal.readBytes())
                assertArrayEquals(payload, slot.final.readBytes())
            }
        }
    }

    private companion object {
        val payload = byteArrayOf(1, 2, 3, 4) // This literal token selects synthetic RGB samples 1..18; it is not PNG.
        val rasterHash = "69383a3ea68c8e12c19b9f0b604a888357ef9a8c3f180f6e50c60faea74df7c4"
        const val cap = 6L
        val size = PixelSize(3, 2)
        val output = OutputSpec(size, RenderPurpose.EXPORT)
        val profile = OrdinaryExportProfile("renderer-v1", "rgb8-v1", "reference", "binary64")

        fun id(n: Int) = UUID(0, n.toLong()).toString()
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        suspend inline fun refuses(code: OperationErrorCode, key: String, action: () -> Unit): OperationError =
            assertRefuses(code, key, action)

        inline fun assertRefuses(code: OperationErrorCode, key: String, action: () -> Unit): OperationError {
            val failure = try {
                action()
                throw AssertionError("Expected $code/$key")
            } catch (failure: RepositoryFailure) { failure }
            assertEquals(code, failure.error.code)
            assertEquals(key, failure.error.safeMessageKey)
            return failure.error
        }
    }
}
