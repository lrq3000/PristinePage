package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.ArtifactId
import app.pristinepage.data.model.ExportSnapshotId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.PngExportItemIntent
import app.pristinepage.data.model.PngExportItemIntent.Assembly
import app.pristinepage.data.model.PngExportItemIntent.Item
import app.pristinepage.data.model.RenderId
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.data.model.TransactionId
import app.pristinepage.data.repository.CatalogQueries
import app.pristinepage.data.repository.ExportItemIntentRow as Row
import app.pristinepage.data.repository.MetadataAccess
import app.pristinepage.data.repository.RoomPngExportItemReads
import app.pristinepage.data.repository.RoomPngExportItemReads.ExpectedMove
import app.pristinepage.data.repository.RoomPngExportItemReads.Verdict
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomPngExportItemReadsTest {
    @Test fun fixedV1EnvelopeAndOwnerKeyMatchIndependentLiterals() {
        val f = Fixture()
        val item = f.plan[0].intent
        val payload = PngExportItemIntent(f.assembly, item).journalPayload()
        assertEquals(685, payload.size)
        assertEquals("42e3d385843cc496d33aece3186012acc60e3562440299b829cd6b6fd88be813",
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
        assertArrayEquals(f.wire(f.assembly, item), payload)
        assertEquals("export-item-v1:00000000-0000-0000-0000-00000000000a:0", PngExportItemIntent.ownerKey(f.assembly.jobId, 0))
        assertEquals("export-item-v1:00000000-0000-0000-0000-00000000000a:99", PngExportItemIntent.ownerKey(f.assembly.jobId, 99))
        assertThrows(IllegalArgumentException::class.java) { PngExportItemIntent.ownerKey(f.assembly.jobId, -1) }
        assertThrows(IllegalArgumentException::class.java) { PngExportItemIntent.ownerKey(f.assembly.jobId, 100) }
    }

    @Test fun freshUsesActualAttemptAndKnownFrozenCountWithoutProjectingAnyRows() {
        for (n in listOf(1, 2, 100)) {
            val f = Fixture()
            // None of these are within the 100 exact keys for this assembly and attempt.
            f.addMoved(0, atAttempt = 1)
            f.addMoved(1, atAttempt = 4)
            f.addMoved(0, atOwner = f.owner(0).uppercase())
            f.addMoved(0, atOwner = f.owner(-1))
            f.addMoved(0, atOwner = f.owner(100))
            assertEquals(Verdict.FRESH, fresh(f, f.assembly.copy(pageCount = n)))
            assertEquals(listOf(n + 1), f.limits)
            assertEquals((0..99).map { f.owner(it) to 3L }, f.seeks)
            assertTrue(f.projected.isEmpty())
            assertEquals(1, f.transactions)
        }
    }

    @Test fun freshDistinguishesBoundedOccupancyFromMalformedPresenceEvenAtOrdinal99() {
        for (ordinal in listOf(0, 99)) {
            for (bounded in listOf(true, false)) {
                val f = Fixture()
                if (bounded) f.addMoved(ordinal) else f.unbounded += f.owner(ordinal) to 3L
                f.reject(if (bounded) "png_export_item_occupied" else "png_export_item_unbounded") { fresh(f) }
                assertTrue(f.projected.isEmpty())
            }
        }
    }

    @Test fun twoDistinctMovedRowsProjectOnlyAfterAllGuardsAndInFrozenOrder() {
        val f = movedFixture()
        assertEquals(Verdict.MOVED, moved(f))
        assertEquals(listOf(3), f.limits)
        assertEquals((0..99).map { f.owner(it) to 3L }, f.seeks)
        assertEquals(listOf(f.owner(0), f.owner(1)), f.projected)
        assertEquals(1, f.transactions)
    }

    @Test fun missingWrongAttemptMalformedAndExtraMatchRefuseBeforeAnyProjection() {
        for (case in listOf("missing", "old", "unbounded", "extra", "extra unbounded")) {
            val f = Fixture()
            f.addMoved(0)
            if (case.startsWith("extra")) f.addMoved(1)
            val reason = when (case) {
                "old" -> { f.addMoved(1, atAttempt = 2); "png_export_item_missing" }
                "unbounded" -> { f.unbounded += f.owner(1) to 3L; "png_export_item_unbounded" }
                "extra" -> { f.addMoved(99); "png_export_item_extra" }
                "extra unbounded" -> { f.unbounded += f.owner(99) to 3L; "png_export_item_extra" }
                else -> "png_export_item_missing"
            }
            f.reject(reason) { moved(f) }
            assertTrue(case, f.projected.isEmpty())
        }
    }

    @Test fun canonicalButWrongRowsCannotBePromotedToMovedEvidence() {
        val mutations: List<(Row) -> Row> = listOf(
            { it.copy(transactionId = id(71)) }, { it.copy(kind = "RENDER") },
            { it.copy(ownerId = it.ownerId.uppercase()) }, { it.copy(attempt = 2) },
            { it.copy(state = "COMMITTED") }, { it.copy(state = "SEALED") },
            { it.copy(stagingPath = "staging/${id(62)}/derived.part") },
            { it.copy(finalPath = "exports/${id(11)}/${id(52)}.bin") },
            { it.copy(sealSha256 = "b".repeat(64)) }, // Substituting the renderer's seal must fail.
            { it.copy(expectedByteSha256 = "0".repeat(64)) }, { it.copy(expectedByteCount = 16) },
            { it.copy(expectedByteCount = 18) }, { it.copy(errorCode = "STORAGE_FAILED") },
            { it.copy(payload = it.payload.copyOf().apply { this[200] = (this[200] + 1).toByte() }) },
            { it.copy(payload = it.payload.copyOf(684)) },
        )
        for (mutate in mutations) {
            val f = movedFixture()
            val key = f.owner(0) to 3L
            f.rows[key] = mutate(f.rows.getValue(key))
            f.reject("png_export_item_mismatch") { moved(f) }
            assertEquals(listOf(f.owner(0)), f.projected)
        }
    }

    @Test fun swappedRowsOrAnImpossibleMissingTypedProjectionFailClosed() {
        val f = movedFixture()
        val first = f.owner(0) to 3L
        val second = f.owner(1) to 3L
        val one = f.rows.getValue(first)
        f.rows[first] = f.rows.getValue(second)
        f.rows[second] = one
        f.reject("png_export_item_mismatch") { moved(f) }
        assertEquals(listOf(f.owner(0)), f.projected)

        val absent = movedFixture()
        absent.hideProjection += absent.owner(0)
        absent.reject("png_export_item_mismatch") { moved(absent) }
        assertEquals(listOf(absent.owner(0)), absent.projected)
    }

    @Test fun journalCannotSupplyAReplacementForAnyExpectedPlanField() {
        val f = movedFixture()
        val first = f.plan[0]
        val second = f.plan[1]
        for (header in listOf(f.assembly.copy(snapshotId = ExportSnapshotId(id(90))),
            f.assembly.copy(snapshotSha256 = "0".repeat(64)), f.assembly.copy(canonicalInputSha256 = "1".repeat(64)))) {
            f.reject("png_export_item_invalid_plan", OperationErrorCode.INVALID_INPUT) { moved(f, header) }
        }
        val wrongPlans = listOf(
            f.plan.reversed(), listOf(first), listOf(first, second.copy(intent = second.intent.copy(
                renderJobId = JobId(id(92))))), listOf(first, second.copy(intent = second.intent.copy(
                artifactId = first.intent.artifactId))), listOf(first, second.copy(intent = second.intent.copy(
                exportTransactionId = first.intent.renderTransactionId))),
        )
        for (plan in wrongPlans) {
            f.reject("png_export_item_invalid_plan", OperationErrorCode.INVALID_INPUT) { moved(f, plan = plan) }
        }
        assertEquals(0, f.transactions)
        // These updated trusted values are individually well formed, but no longer match the persisted attempt.
        for (header in listOf(f.assembly.copy(leaseOwner = id(80)), f.assembly.copy(generation = 8))) {
            val changed = movedFixture()
            changed.reject("png_export_item_mismatch") { moved(changed, header) }
        }
    }

    @Test fun rejectsInvalidPlanAndOverCapBeforeAnyReadButAllowsCapEquality() {
        val f = movedFixture()
        for (header in listOf(f.assembly.copy(pageCount = 0), f.assembly.copy(pageCount = 101),
            f.assembly.copy(attempt = 0), f.assembly.copy(generation = 0),
            f.assembly.copy(leaseOwner = "invalid"), f.assembly.copy(snapshotSha256 = "A".repeat(64)))) {
            f.reject("png_export_item_invalid_plan", OperationErrorCode.INVALID_INPUT) { fresh(f, header) }
        }
        val item = f.plan[0]
        for (bad in listOf(item.copy(maxBytes = 0), item.copy(exportSealSha256 = "x".repeat(64)),
            item.copy(intent = item.intent.copy(renderSealSha256 = "B".repeat(64))),
            item.copy(intent = item.intent.copy(byteSha256 = "g".repeat(64))),
            item.copy(intent = item.intent.copy(rasterSha256 = "d".repeat(65))),
            item.copy(intent = item.intent.copy(byteCount = 0)))) {
            f.reject("png_export_item_invalid_plan", OperationErrorCode.INVALID_INPUT) {
                moved(f, plan = listOf(bad, f.plan[1]))
            }
        }
        f.reject("png_export_item_byte_cap", OperationErrorCode.RESOURCE_LIMIT) {
            moved(f, plan = listOf(item.copy(maxBytes = 16), f.plan[1]))
        }
        assertEquals(0, f.transactions)
        assertEquals(Verdict.MOVED, moved(f)) // First slot is exactly at its cap of 17.
    }

    @Test fun anyExistingArtifactIncludingTheMalformedIdSentinelRefusesBothReads() {
        for (occupied in listOf(id(51), "")) {
            for (precommit in listOf(false, true)) {
                val f = movedFixture()
                f.inventory = listOf(occupied)
                f.reject("png_export_item_artifacts_present") { if (precommit) moved(f) else fresh(f) }
                assertEquals(listOf(3), f.limits)
                assertTrue(f.seeks.isEmpty())
                assertTrue(f.projected.isEmpty())
            }
        }
    }

    @Test fun storageFailureIsRetryableAndDoesNotLookLikeAbsence() {
        val f = Fixture()
        f.diskFailure = true
        val failure = f.reject("png_export_item_read_failed", OperationErrorCode.STORAGE_FAILED) { fresh(f) }
        assertTrue(failure.error.retryable)
        assertTrue(f.projected.isEmpty())
    }

    private fun fresh(f: Fixture, assembly: Assembly = f.assembly): Verdict = f.reader.checkFresh(assembly)
    private fun moved(f: Fixture, assembly: Assembly = f.assembly, plan: List<ExpectedMove> = f.plan): Verdict =
        f.reader.checkMoved(assembly, plan)

    private fun movedFixture(): Fixture = Fixture().apply {
        addMoved(1) // Insertion order is intentionally not frozen order.
        addMoved(0)
    }

    private class Fixture : MetadataAccess {
        val assembly = Assembly(
            ExportSnapshotId(id(11)), "a".repeat(64), JobId(id(10)),
            "35f9818ea8a45aac528b8906e6a621cb4ec2af397e37dfc333d00600f608a1ca", id(12), 7, 3, 2,
        )
        val plan = List(2) { i ->
            ExpectedMove(
                Item(i, if (i == 0) PixelSize(3, 2) else PixelSize(2, 3), JobId(id(21 + i)), RenderId(id(31 + i)),
                    TransactionId(id(41 + i)), (if (i == 0) "b" else "1").repeat(64),
                    (if (i == 0) "c" else "2").repeat(64), 17 + i.toLong(), (if (i == 0) "d" else "3").repeat(64),
                    ArtifactId(id(51 + i)), TransactionId(id(61 + i))),
                (if (i == 0) "e" else "4").repeat(64), 17 + i.toLong(),
            )
        }
        val rows = mutableMapOf<Pair<String, Long>, Row>()
        val unbounded = mutableSetOf<Pair<String, Long>>()
        val hideProjection = mutableSetOf<String>()
        val seeks = mutableListOf<Pair<String, Long>>()
        val projected = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        var inventory = emptyList<String>()
        var transactions = 0
        var diskFailure = false
        private var inTransaction = false
        val reader = RoomPngExportItemReads(this, ::inventory, ::bounds, ::row)

        override val queries: CatalogQueries get() = error("No catalog or wider query is part of this boundary")

        override fun <T> transaction(action: () -> T): T {
            check(!inTransaction)
            transactions++
            inTransaction = true
            try {
                return action()
            } finally {
                inTransaction = false
            }
        }

        fun inventory(snapshot: String, limit: Int): List<String> {
            assertTrue(inTransaction)
            assertEquals(id(11), snapshot)
            limits += limit
            if (diskFailure) throw java.io.IOException("synthetic read failure")
            return inventory
        }

        fun bounds(key: String, attempt: Long, extent: Int): Boolean? {
            assertTrue(inTransaction)
            assertEquals(685, extent)
            seeks += key to attempt
            return if (key to attempt in unbounded) false else rows[key to attempt]?.let { true }
        }

        fun row(key: String, attempt: Long): Row? {
            assertTrue(inTransaction)
            assertEquals((0..99).map { owner(it) to 3L }, seeks)
            assertTrue(key == owner(0) || key == owner(1))
            projected += key
            return rows[key to attempt]?.takeUnless { key in hideProjection }
        }

        fun addMoved(ordinal: Int, atAttempt: Long = 3, atOwner: String = owner(ordinal)) {
            val expected = plan[ordinal % 2]
            val item = expected.intent
            rows[atOwner to atAttempt] = Row(item.exportTransactionId.value, "EXPORT", atOwner, atAttempt, "MOVED",
                "staging/${item.exportTransactionId.value}/derived.part", "exports/${id(11)}/${item.artifactId.value}.bin",
                expected.exportSealSha256, item.byteSha256, item.byteCount, wire(assembly, item), null)
        }

        fun owner(ordinal: Int) = "export-item-v1:00000000-0000-0000-0000-00000000000a:$ordinal"

        fun wire(header: Assembly, item: Item): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply {
                writeBytes("pristinepage-ordinary-png-export-item-v1\u0000")
                writeBytes(header.snapshotId.value + header.snapshotSha256 + header.jobId.value +
                    header.canonicalInputSha256 + header.leaseOwner)
                writeLong(header.generation)
                writeLong(header.attempt)
                writeInt(item.ordinal)
                writeInt(item.size.width)
                writeInt(item.size.height)
                writeBytes(item.renderJobId.value + item.renderId.value + item.renderTransactionId.value +
                    item.renderSealSha256 + item.byteSha256)
                writeLong(item.byteCount)
                writeBytes(item.rasterSha256 + item.artifactId.value + item.exportTransactionId.value)
            }
            return bytes.toByteArray()
        }

        fun reject(key: String, code: OperationErrorCode = OperationErrorCode.INTEGRITY_FAILED, action: () -> Verdict): RepositoryFailure {
            val failure = assertThrows(RepositoryFailure::class.java) { action() }
            assertEquals(code, failure.error.code)
            assertEquals(key, failure.error.safeMessageKey)
            assertEquals(assembly.jobId.value, failure.error.affectedId)
            return failure
        }
    }

    private companion object {
        fun id(n: Int): String = UUID(0, n.toLong()).toString()
    }
}
