package app.pristinepage.data

import app.pristinepage.data.repository.LibraryUndoCodec
import app.pristinepage.data.model.DocumentMutationResult
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LibraryUndoCodecTest {
    private val document = id(1)
    private val cover = id(2)
    private val other = id(3)
    private val source = id(4)

    @Test fun recipeUndoRemapsPageSourceAndRevisionTogetherWithoutChangingItsCanonicalForm() {
        val received = recipe(cover, source, 2)
        val moved = LibraryUndoCodec.remapRecipe(received) { page, blob, revision ->
            assertEquals(cover, page)
            assertEquals(source, blob)
            assertEquals(2L, revision)
            LibraryUndoCodec.RecipePointer(other, id(5), 7)
        }
        assertArrayEquals(recipe(other, id(5), 7), moved)
        assertThrows(IllegalArgumentException::class.java) { LibraryUndoCodec.remapRecipe(received) { _, _, _ -> null } }
    }

    @Test fun membershipUndoRemapsOpaqueOwnershipButDoesNotChangeOrderOrState() {
        val input = """{"documentId":"$document","pages":[{"ordinal":0,"pageId":"$cover","removedAt":null,"sourceId":"$source","state":"ACTIVE"},{"ordinal":null,"pageId":"$other","removedAt":9,"sourceId":"$source","state":"REMOVED"}],"version":1}"""
        val expected = """{"documentId":"${id(9)}","pages":[{"ordinal":null,"pageId":"$cover","removedAt":9,"sourceId":"${id(5)}","state":"REMOVED"},{"ordinal":0,"pageId":"$other","removedAt":null,"sourceId":"${id(5)}","state":"ACTIVE"}],"version":1}"""
        val moved = LibraryUndoCodec.remapMembership(input.encodeToByteArray(), document, id(9)) { page, blob ->
            assertEquals(source, blob)
            (if (page == cover) other else cover) to id(5)
        }
        assertEquals(expected, moved.decodeToString())
        assertThrows(IllegalArgumentException::class.java) {
            LibraryUndoCodec.remapMembership(input.encodeToByteArray(), id(8), id(9)) { page, blob -> page to blob }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibraryUndoCodec.remapMembership(input.encodeToByteArray(), document, id(9)) { _, blob -> cover to blob }
        }
    }

    @Test fun portableUndoParserRejectsNonCanonicalOrUnboundedRecipeInputBeforeResolvingIds() {
        val noncanonical = " ".encodeToByteArray() + recipe(cover, source, 2)
        val nested = "[".repeat(32).encodeToByteArray() + recipe(cover, source, 2) + "]".repeat(32).encodeToByteArray()
        for (bytes in listOf(noncanonical, nested, recipe(cover, source, 0), ByteArray(524_289))) {
            assertThrows(IllegalArgumentException::class.java) {
                LibraryUndoCodec.remapRecipe(bytes) { _, _, _ -> error("Unauthenticated pointer reached the resolver") }
            }
        }
    }

    @Test fun quotedDelimitersAndEscapedQuotesCannotDisguiseStructuralDepth() {
        fun nested(depth: Int) = """["\\\" ]",""" + "[".repeat(depth - 1) + "0" + "]".repeat(depth - 1) + ""","["]"""
        assertEquals(nested(8), LibraryUndoCodec.boundedText(nested(8).encodeToByteArray()))
        assertThrows(IllegalArgumentException::class.java) { LibraryUndoCodec.boundedText(nested(9).encodeToByteArray()) }
    }

    @Test fun ordinaryBatchUndoStillWritesAndExecutesTheExistingOrderedRecipeBytes() = runTest {
        val fixture = RepositoryFixture()
        val pages = fixture.pageIds.sortedByDescending { it.value }
        val applied = fixture.repository.applyBatchRecipes(fixture.document, 1, pages.map { fixture.change(it, 1) }) as DocumentMutationResult.Applied
        val saved = fixture.metadata.undos.getValue(checkNotNull(applied.undoId).value)
        fun golden(revision: Int): ByteArray {
            val pointers = pages.joinToString(",") {
                """{"pageId":"${it.value}","sourceId":"${fixture.source.value}","recipeRevision":$revision}"""
            }
            return """{"version":1,"pages":[$pointers]}""".encodeToByteArray()
        }
        assertArrayEquals(golden(1), saved.beforeState)
        assertArrayEquals(golden(2), saved.afterState)
        fixture.metadata.undos[saved.undoId] = saved.copy(beforeState = golden(1), afterState = golden(2))
        assertTrue(fixture.repository.undoDocumentEdit(checkNotNull(applied.undoId), 2) is DocumentMutationResult.Applied)
        assertEquals(listOf(0, 0), fixture.pageIds.map { fixture.current(it).geometry.outputQuarterTurns })
    }

    private fun recipe(page: String, source: String, version: Long) =
        """{"version":1,"pages":[{"pageId":"$page","sourceId":"$source","recipeRevision":$version}]}""".encodeToByteArray()

    private fun id(value: Int) = UUID(0, value.toLong()).toString()
}
