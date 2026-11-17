package app.pristinepage.data

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.RecipeRevisionRow
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The real save command/CAS/pin logic; only raw SQLite projections are controlled. Actual Room schema alias/materialization witnesses are retained separately in the Application consumers. */
class RecipeSaveIntegrityTest {
    @Test fun currentRevisionCannotNameAnotherValidSourceWhileItsBytesRemainBoundToThePage() = runBlocking {
        val f = RepositoryFixture(); val foreign = foreignSource(f); val key = f.pageIds[0].value to 1L
        f.metadata.recipes[key] = f.metadata.recipes.getValue(key).copy(sourceId = foreign)
        rejected(f,f.repository.saveRecipe(f.pageIds[0],1,f.turned(1)))
        assertEquals(foreign,f.metadata.recipes.getValue(key).sourceId)
    }

    @Test fun currentCanonicalBindingCannotDisagreeWithThePageEvenWhenTheSubmittedRecipeMatches() = runBlocking {
        val f = RepositoryFixture(); val key = f.pageIds[0].value to 1L
        val unrelated = Recipe(geometry = GeometryRecipe.None(f.binding.copy(sourcePixelHash = "b".repeat(64))))
        f.metadata.recipes[key] = f.metadata.recipes.getValue(key).copy(canonicalRecipe = RecipeCodec.encode(unrelated),recipeSha256 = RecipeCodec.hash(unrelated))
        rejected(f,f.repository.saveRecipe(f.pageIds[0],1,f.turned(1)))
        assertArrayEquals(RecipeCodec.encode(unrelated),f.metadata.recipes.getValue(key).canonicalRecipe)
    }

    @Test fun currentRevisionSourceIsRecheckedAfterAdmissionBeforeTheFirstWrite() = runBlocking {
        lateinit var f: RepositoryFixture
        f = RepositoryFixture(admission = { _, _ -> val key = f.pageIds[0].value to 1L; f.metadata.recipes[key] = f.metadata.recipes.getValue(key).copy(sourceId = foreignSource(f)); null })
        rejected(f,f.repository.saveRecipe(f.pageIds[0],1,f.turned(1)))
    }

    @Test fun rejectedRawRecipeIsNeverMaterializedDuringSavePreparation() = runBlocking {
        val f = GuardedFixture(); f.bounded = false
        rejected(f.base,f.repository.saveRecipe(f.base.pageIds[0],1,f.base.turned(1)))
        assertEquals(0,f.recipeReads)
    }

    @Test fun rawRecipeGuardIsRepeatedAfterAdmissionBeforeAnySaveWrite() = runBlocking {
        val f = GuardedFixture(); f.afterAdmission = { f.bounded = false }
        rejected(f.base,f.repository.saveRecipe(f.base.pageIds[0],1,f.base.turned(1)))
        assertEquals(1,f.recipeReads)
    }

    private class GuardedFixture {
        val base = RepositoryFixture()
        var bounded = true; var admitted = false; var recipeReads = 0; var afterAdmission: () -> Unit = {}
        private val queries = Proxy.newProxyInstance(CatalogQueries::class.java.classLoader,arrayOf(CatalogQueries::class.java,EditablePageQueries::class.java)) { _, method, args ->
            val actual = args ?: emptyArray()
            when (method.name) {
                "recipeReadIsBounded" -> { check(base.metadata.inTransaction); admitted = bounded; bounded }
                "pageReadIsBounded", "documentReadIsBounded", "sourceReadIsBounded", "activePagesReadIsBounded" -> { check(base.metadata.inTransaction); true }
                "recipeHasMasks" -> false
                "activePageCount" -> base.metadata.activePages(actual[0] as String).size.toLong()
                "recipe" -> {
                    if (!admitted || !bounded) throw AssertionError("Save materialized a raw recipe before its scalar guard")
                    check(base.metadata.inTransaction); recipeReads++
                    base.metadata.recipe(actual[0] as String,actual[1] as Long)
                }
                else -> try { method.invoke(base.metadata,*actual) } catch (error: InvocationTargetException) { throw error.targetException }
            }
        } as CatalogQueries
        private val metadata = object : MetadataAccess {
            override val queries get() = this@GuardedFixture.queries
            override fun <T> transaction(action: () -> T): T = base.metadata.transaction { admitted = false; action() }
        }
        val repository = RoomDocumentRepository(metadata,RecipeAdmission { _, _ -> check(!base.metadata.inTransaction); afterAdmission(); null },{1000L},Dispatchers.Unconfined)
    }

    private companion object {
        fun foreignSource(f: RepositoryFixture): String = UUID.randomUUID().toString().also { id -> f.metadata.sources[id] = f.metadata.sources.getValue(f.source.value).copy(sourceId = id,pixelSha256 = "b".repeat(64)) }
        fun rejected(f: RepositoryFixture, result: SaveRecipeResult) {
            assertTrue("Corrupt current revision was saved: $result",result is SaveRecipeResult.Rejected)
            assertEquals(OperationErrorCode.INTEGRITY_FAILED,(result as SaveRecipeResult.Rejected).error.code)
            assertEquals(1L,f.metadata.pages.getValue(f.pageIds[0].value).currentRecipeRevision); assertEquals(1L,f.metadata.documents.getValue(f.document.value).documentRevision)
            assertEquals(2,f.metadata.recipes.size); assertEquals(4,f.metadata.references.size); assertTrue(f.metadata.undos.isEmpty())
        }
    }
}
