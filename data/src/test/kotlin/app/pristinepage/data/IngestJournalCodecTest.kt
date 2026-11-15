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
