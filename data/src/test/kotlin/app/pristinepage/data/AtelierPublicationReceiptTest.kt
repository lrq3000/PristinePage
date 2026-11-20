package app.pristinepage.data

import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId
import app.pristinepage.data.repository.AtelierPageWitness
import app.pristinepage.data.repository.AtelierPublicationReceipt
import org.junit.Assert.*
import org.junit.Test

class AtelierPublicationReceiptTest {
    @Test fun receiptReturnsOnlyTheExactOrderedRequestAfterReopen() {
        val receipt = AtelierPublicationReceipt.decode("atelier-publish-v1\n4\n$album\n$stage1,2,$hash1,$new1\n$stage2,11,$hash2,$new2")
        val one = AtelierPageWitness(PageId(stage1), 2, hash1)
        val two = AtelierPageWitness(PageId(stage2), 11, hash2)
        val exact = listOf(one, two)
        assertEquals(listOf(PageId(new1), PageId(new2)), receipt.replay(DocumentId(album), 4, exact))
        for (different in listOf(emptyList(), exact.reversed(), listOf(one.copy(revision = 3), two),
            listOf(one.copy(recipeHash = hash2), two), listOf(one), listOf(one, one))) {
            assertNull(receipt.replay(DocumentId(album), 4, different))
        }
        assertNull(receipt.replay(DocumentId(album), 5, exact))
        assertNull(receipt.replay(DocumentId(stage1), 4, exact))
        assertEquals("atelier-publish-v1\n4\n$album\n$stage1,2,$hash1,$new1\n$stage2,11,$hash2,$new2",
            AtelierPublicationReceipt(4, DocumentId(album), exact, listOf(PageId(new1), PageId(new2))).encode())
    }

    @Test fun malformedReceiptCannotReturnAnAmbiguousOrNoncanonicalMapping() {
        val prefix = "atelier-publish-v1\n4\n$album\n"
        for (bad in listOf(prefix + "$stage1,2,$hash1,$new1\n$stage2,11,$hash2,$new1",
            prefix + "$stage1,2,$hash1,$new1\n$stage1,11,$hash2,$new2",
            prefix + "$stage1,02,$hash1,$new1\n$stage2,11,$hash2,$new2", prefix + "$stage1,2,$hash1,$new1\n",
            prefix + "a".repeat(4096))) {
            assertThrows(IllegalArgumentException::class.java) { AtelierPublicationReceipt.decode(bad) }
        }
    }

    private companion object {
        const val album = "00000000-0000-0000-0000-000000000001"
        const val stage1 = "00000000-0000-0000-0000-000000000002"
        const val stage2 = "00000000-0000-0000-0000-000000000003"
        const val new1 = "00000000-0000-0000-0000-000000000004"
        const val new2 = "00000000-0000-0000-0000-000000000005"
        val hash1 = "a".repeat(64)
        val hash2 = "b".repeat(64)
    }
}
