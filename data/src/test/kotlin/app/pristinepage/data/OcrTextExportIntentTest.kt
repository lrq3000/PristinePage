package app.pristinepage.data

import app.pristinepage.data.model.ArtifactId
import app.pristinepage.data.model.JobId
import app.pristinepage.data.model.TransactionId
import app.pristinepage.data.repository.OcrTextExportIntent
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class OcrTextExportIntentTest {
    @Test fun exactBlankHasAnIndependentCanonicalEncodingWithNoPayload() {
        val encoded = intent().encode()
        assertArrayEquals(BLANK.encodeToByteArray(), encoded)
        val actualSha = MessageDigest.getInstance("SHA-256").digest(encoded).toHexString()
        assertEquals("a07dbd1e2515fa3ec2f1b05536706a159a9c81ec4c083d017e637113de59c577", actualSha)
        val decoded = OcrTextExportIntent.decode(encoded)
        encoded.fill(0)
        assertArrayEquals(BLANK.encodeToByteArray(), decoded.encode())
        assertEquals(0L, decoded.byteCount)
    }

    @Test fun refusesNoncanonicalOrContradictoryMetadata() {
        val prefix = BLANK.substringBeforeLast('\u0000')
        val invalid = listOf(
            "$prefix\u000000", "$prefix\u0000+0", "$prefix\u0000-0", "$prefix\u0000-1", "$prefix\u0000393217",
            BLANK.replace("export-v1", "export-v2"), "$BLANK\u0000",
            BLANK.replace(TX, TX.uppercase().replace("0001", "000A")),
            BLANK.replace(PRODUCER_DIGEST, PRODUCER_DIGEST.uppercase()),
            BLANK.replace(EMPTY_SHA, "0".repeat(64)), BLANK.replace("export", "expört"),
        )
        for (alias in invalid) {
            assertThrows(IllegalArgumentException::class.java) { OcrTextExportIntent.decode(alias.encodeToByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { OcrTextExportIntent.decode(ByteArray(280)) }
    }

    @Test fun bothFreshIdsAndTheProducerRemainDistinctFromTheContentIdentity() {
        val first = intent()
        val second = OcrTextExportIntent(TransactionId(ARTIFACT), ArtifactId(TX), JobId(JOB), PRODUCER_DIGEST, EMPTY_SHA, 0)
        assertFalse(first.encode().contentEquals(second.encode()))
        val packet = OcrTextExportIntent(TransactionId(TX), ArtifactId(ARTIFACT), JobId(JOB), PRODUCER_DIGEST, "0".repeat(64), 393_216)
        val decoded = OcrTextExportIntent.decode(packet.encode())
        assertEquals(TransactionId(TX), decoded.transaction)
        assertEquals(ArtifactId(ARTIFACT), decoded.artifact)
        assertEquals(JobId(JOB), decoded.producer)
        assertEquals(PRODUCER_DIGEST, decoded.producerInputSha256)
        assertEquals("0".repeat(64), decoded.textSha256)
        assertEquals(393_216L, decoded.byteCount)
    }

    private fun intent() =
        OcrTextExportIntent(TransactionId(TX), ArtifactId(ARTIFACT), JobId(JOB), PRODUCER_DIGEST, EMPTY_SHA, 0)

    private companion object {
        const val TX = "00000000-0000-0000-0000-000000000001"
        const val ARTIFACT = "00000000-0000-0000-0000-000000000002"
        const val JOB = "00000000-0000-0000-0000-000000000003"
        const val PRODUCER_DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val EMPTY_SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val BLANK = "pristinepage-ocr-text-export-v1\u0000$TX\u0000$ARTIFACT\u0000$JOB\u0000$PRODUCER_DIGEST\u0000$EMPTY_SHA\u00000"
    }
}
