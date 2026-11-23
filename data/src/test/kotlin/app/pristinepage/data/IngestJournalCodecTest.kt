package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.data.model.*
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class IngestJournalCodecTest {
    private val id = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val intent = IngestJournalIntent(TransactionId(id), ImportSessionId(id), DocumentId(id), PageId(id), SourceBlobId(id), SourceId(id), JobId(id), 1)
    private val inspection = SourceInspection("image/png", PixelSize(3, 2), PixelSize(2, 3), 6, null, "srgb-v1", "a".repeat(64))
    private val seal = IngestJournalSeal(intent, inspection, "9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a", 4)
    private fun call(name: String, vararg args: Any): Any {
        val type = try { Class.forName("app.pristinepage.data.journal.IngestJournalCodec") }
        catch (error: ClassNotFoundException) { throw AssertionError("Canonical ingest ownership/seal codec is absent", error) }
        return try { type.methods.single { it.name == name && it.parameterCount == args.size }.invoke(null, *args)!! }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun encode() = call("encodeIntent", intent) as ByteArray

    @Test fun intentHasAnIndependentCanonicalGoldenAndNoProviderOrTitleFields() {
        val expected = """{"attempt":1,"documentId":"$id","jobId":"$id","pageId":"$id","schemaVersion":1,"sessionId":"$id","sourceBlobId":"$id","sourceId":"$id","transactionId":"$id"}""".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, encode())
        assertEquals(intent, call("decodeIntent", expected))
    }

    @Test fun sealRoundTripRetainsExactBytePixelOrientationAndOwnershipIdentities() {
        val bytes = call("encodeSeal", seal) as ByteArray
        val decoded = call("decodeSeal", bytes, intent) as IngestJournalSeal
        assertEquals(seal, decoded)
        assertEquals(4L, decoded.byteCount); assertEquals(6, decoded.inspection.exifOrientation)
        assertEquals(PixelSize(2, 3), decoded.inspection.normalizedSize)
        assertArrayEquals(bytes, call("encodeSeal", decoded) as ByteArray)
    }

    @Test fun captureProjectionDoesNotChangeHistoricalSealOrReceiptBytes() {
        val legacy = """{"byteCount":4,"inspection":{"conversionFingerprint":"srgb-v1","encodedSize":{"height":2,"width":3},"exifOrientation":6,"mime":"image/png","normalizedSize":{"height":3,"width":2},"profileSha256":null,"sourcePixelHash":"${"a".repeat(64)}"},"intent":${String(encode(), Charsets.UTF_8)},"schemaVersion":1,"sourceByteHash":"9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a"}"""
        assertArrayEquals(legacy.encodeToByteArray(), call("encodeSeal", seal) as ByteArray)
        assertNull((call("decodeSeal", legacy.encodeToByteArray(), intent) as IngestJournalSeal).captureDate)
        val receipt = """{"documentRevision":3,"schemaVersion":1,"seal":$legacy}""".encodeToByteArray()
        assertArrayEquals(receipt, call("encodeCommit", IngestJournalCommit(seal, 3)) as ByteArray)
        assertEquals(IngestJournalCommit(seal, 3), call("decodeCommit", receipt, intent))
    }

    @Test fun datedAndNoUsableDateV2RecordsRoundTripWithoutChangingNormalizedIdentity() {
        val dated = seal.copy(schemaVersion = 2, captureDate = SourceCaptureDate(19782))
        val empty = dated.copy(captureDate = SourceCaptureDate(null))
        for ((value, day) in listOf(dated to "19782", empty to "null")) {
            val legacy = String(call("encodeSeal", seal) as ByteArray, Charsets.UTF_8)
            val canonical = legacy.replaceFirst("\"inspection\":", "\"captureDate\":{\"epochDay\":$day},\"inspection\":")
                .replace("\"schemaVersion\":1,\"sourceByteHash\"", "\"schemaVersion\":2,\"sourceByteHash\"")
            assertArrayEquals(canonical.encodeToByteArray(), call("encodeSeal", value) as ByteArray)
            val decoded = call("decodeSeal", canonical.encodeToByteArray(), intent) as IngestJournalSeal
            assertEquals(value, decoded)
            assertEquals(inspection, decoded.inspection)
            val receipt = """{"documentRevision":4,"schemaVersion":2,"seal":$canonical}""".encodeToByteArray()
            val committed = IngestJournalCommit(value, 4)
            assertArrayEquals(receipt, call("encodeCommit", committed) as ByteArray)
            assertEquals(committed, call("decodeCommit", receipt, intent))
            assertThrows(IllegalArgumentException::class.java) { call("decodeCommit", receipt, intent.copy(attempt = 2)) }
        }
    }

    @Test fun projectedDateCannotBeInjectedIntoLegacyOrNoncanonicalRecords() {
        val projection = SourceCaptureDate(19782)
        assertThrows(IllegalArgumentException::class.java) { call("encodeSeal", seal.copy(captureDate = projection)) }
        assertThrows(IllegalArgumentException::class.java) { SourceCaptureDate(Long.MAX_VALUE) }
        val dated = seal.copy(schemaVersion = 2, captureDate = projection)
        val good = String(call("encodeSeal", dated) as ByteArray, Charsets.UTF_8)
        for (bad in listOf(good.replace("19782", "19782.0"), good.replace("19782", "2932897"),
            good.replace("\"epochDay\":19782", "\"epochDay\":19782,\"gps\":\"synthetic\""),
            good.replace("\"schemaVersion\":2", "\"schemaVersion\":3"))) {
            assertThrows(IllegalArgumentException::class.java) { call("decodeSeal", bad.encodeToByteArray(), intent) }
        }
        assertThrows(IllegalArgumentException::class.java) { call("encodeCommit", IngestJournalCommit(dated, 4, schemaVersion = 1)) }
    }

    @Test fun sealedBytesCannotBeReboundToAnotherReservedDocumentPageOrTransaction() {
        val bytes = call("encodeSeal", seal) as ByteArray
        val other = "180aab39-4036-4979-922e-e91a1ded30c4"
        for (expected in listOf(intent.copy(documentId = DocumentId(other)), intent.copy(pageId = PageId(other)), intent.copy(sessionId = ImportSessionId(other)), intent.copy(transactionId = TransactionId(other)), intent.copy(sourceBlobId = SourceBlobId(other)), intent.copy(sourceId = SourceId(other)), intent.copy(jobId = JobId(other)), intent.copy(attempt = 2))) {
            assertThrows(IllegalArgumentException::class.java) { call("decodeSeal", bytes, expected) }
        }
    }

    @Test fun ambiguousMalformedOrFutureIntentMetadataRejectsWithoutChangingItsBytes() {
        val good = String(encode(), Charsets.UTF_8)
        val cases = listOf(
            good.replace("\"attempt\":1", "\"attempt\":1,\"attempt\":2").toByteArray(),
            good.replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray(),
            good.dropLast(1).plus(",\"uriText\":\"content://fixture.documents/private\"}").toByteArray(),
            (" " + good).toByteArray(), byteArrayOf(0xc3.toByte(), 0x28), ByteArray(524289),
        )
        for (bytes in cases) {
            val before = bytes.copyOf()
            assertThrows(IllegalArgumentException::class.java) { call("decodeIntent", bytes) }
            assertArrayEquals(before, bytes)
        }
    }

    @Test fun unsupportedVersionsEmptySourcesAndInvalidHashesNeverProduceAnAcceptedSeal() {
        for (bad in listOf(seal.copy(byteCount = 0), seal.copy(byteCount = 104857601), seal.copy(sourceByteHash = "not-a-hash"), seal.copy(schemaVersion = 2), seal.copy(intent = intent.copy(attempt = 0)), seal.copy(intent = intent.copy(schemaVersion = 2)))) {
            assertThrows(IllegalArgumentException::class.java) { call("encodeSeal", bad) }
        }
    }
}
