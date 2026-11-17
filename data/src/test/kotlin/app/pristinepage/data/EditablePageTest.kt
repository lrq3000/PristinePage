package app.pristinepage.data

import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.recipe.GeometryRecipe
import app.pristinepage.core.recipe.RecipeCodec
import app.pristinepage.data.db.*
import app.pristinepage.data.model.*
import app.pristinepage.data.repository.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real command-created rows and codec; only the unavailable Room/scalar-query boundary is replaced. Every materialized row asserts that its guard already ran inside the same short snapshot. */
class EditablePageTest {
    private class Fixture {
        val base = RepositoryFixture(2)
        val memory = base.metadata
        var documentBound = true; var pagesBound = true; var recipeBound = true; var sourceBound = true
        var fail = false; var guards = 0; var recipeReads = 0
        private val admitted = HashSet<String>()
        private val queries = object : CatalogQueries by memory {
            override fun document(id: String): DocumentRow? { check(memory.inTransaction && "document" in admitted); return memory.document(id) }
            override fun activePages(documentId: String): List<PageRow> { check(memory.inTransaction && "pages" in admitted); return memory.activePages(documentId) }
            override fun source(id: String): SourceRow? { check(memory.inTransaction && "source" in admitted); return memory.source(id) }
            override fun recipe(pageId: String, revision: Long): RecipeRevisionRow? { check(memory.inTransaction && "recipe" in admitted); recipeReads++; return memory.recipe(pageId,revision) }
        }
        private val metadata = object : MetadataAccess {
            override val queries get() = this@Fixture.queries
            override fun <T> transaction(action: () -> T): T = memory.transaction { admitted.clear(); action() }
        }
        fun reader(): Any {
            val port = type("app.pristinepage.data.repository.EditablePageQueries")
            val bounds = Proxy.newProxyInstance(port.classLoader,arrayOf(port)) { _, method, args ->
                check(memory.inTransaction); guards++; if (fail) throw IOException("fixture-private/path")
                when (method.name) {
                    "documentReadIsBounded" -> if (!memory.documents.containsKey(args[0])) null else documentBound.also { if (it) admitted += "document" }
                    "activePageCount" -> memory.activePages(args[0] as String).size.toLong()
                    "activePagesReadIsBounded" -> pagesBound.also { if (it) admitted += "pages" }
                    "sourceReadIsBounded" -> sourceBound.also { if (it) admitted += "source" }
                    "recipeReadIsBounded" -> if (memory.recipe(args[0] as String,args[1] as Long)==null) null else recipeBound.also { if (it) admitted += "recipe" }
                    "recipeHasMasks" -> memory.recipeMasks(args[0] as String,args[1] as Long).isNotEmpty()
                    else -> error("Unexpected query ${method.name}")
                }
            }
            return type("app.pristinepage.data.repository.RoomEditablePages").getConstructor(MetadataAccess::class.java,port,CoroutineDispatcher::class.java).newInstance(metadata,bounds,Dispatchers.Unconfined)
        }
        suspend fun load(reader: Any = reader(), doc: DocumentId = base.document, page: PageId = base.pageIds[0]): Any = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
            val method = reader.javaClass.methods.single { it.name.startsWith("load-") && it.parameterCount==3 }
            try { method.invoke(reader,doc.value,page.value,continuation) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
        fun changeRow(change: (RecipeRevisionRow) -> RecipeRevisionRow) { val key = base.pageIds[0].value to 1L; memory.recipes[key] = change(memory.recipes.getValue(key)) }
    }

    @Test fun lookupReturnsTheCurrentSourceBoundRecipeAndAnIndependentRevisionSnapshot() = runBlocking {
        val f = Fixture(); val reader = f.reader(); val first = f.load(reader)
        assertEquals(f.base.initial,field<Recipe>(first,"Recipe")); assertEquals(1L,field<Long>(first,"RecipeRevision")); assertEquals(1L,field<Long>(first,"DocumentRevision"))
        val saved = f.base.repository.saveRecipe(f.base.pageIds[0],1,f.base.turned(1)) as SaveRecipeResult.Saved
        val later = f.load(reader)
        assertEquals(saved.recipeRevision,field<Long>(later,"RecipeRevision")); assertEquals(saved.documentRevision,field<Long>(later,"DocumentRevision")); assertEquals(saved.recipeHash,field<String>(later,"RecipeHash"))
        assertEquals(f.base.turned(1),field<Recipe>(later,"Recipe")); assertEquals(f.base.source.value,field<String>(later,"SourceId")); assertEquals(f.base.document.value,field<String>(later,"DocumentId")); assertEquals(f.base.pageIds[0].value,field<String>(later,"PageId"))
        assertEquals(1L,field<Long>(first,"RecipeRevision")); assertEquals(f.base.initial,field<Recipe>(first,"Recipe")); assertEquals(2,f.memory.pages.size)
    }

    @Test fun missingForeignRemovedAndInactivePagesCannotBecomeAnEditableSelection() = runBlocking<Unit> {
        val f = Fixture(); val reader = f.reader()
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(reader,page = PageId(UUID.randomUUID().toString())) }
        val other = f.base.repository.createDocument("Other")
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(reader,doc = other) }
        f.base.repository.removePage(f.base.pageIds[0],1)
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(reader) }
        f.memory.documents[f.base.document.value] = f.memory.document(f.base.document.value)!!.copy(lifecycle = "TOMBSTONED",deletedAt = 1)
        rejected(OperationErrorCode.SOURCE_UNAVAILABLE) { f.load(reader,page = f.base.pageIds[1]) }
    }

    @Test fun everyRawGuardRefusesBeforeItsUnsafeTypedProjection() = runBlocking {
        for (kind in listOf("document","pages","source","recipe")) {
            val f = Fixture()
            when (kind) { "document" -> f.documentBound=false; "pages" -> f.pagesBound=false; "source" -> f.sourceBound=false; "recipe" -> f.recipeBound=false }
            rejected(OperationErrorCode.INTEGRITY_FAILED) { f.load() }
            if (kind in setOf("document","pages","recipe")) assertEquals(0,f.recipeReads)
        }
    }

    @Test fun changedHashNoncanonicalBytesAndSourceBindingAreNotRepairedOrRebound() = runBlocking {
        val changes: List<(RecipeRevisionRow) -> RecipeRevisionRow> = listOf(
            { it.copy(recipeSha256 = "b".repeat(64)) }, { it.copy(canonicalRecipe = it.canonicalRecipe + byteArrayOf(32)) },
            { it.copy(sourceId = UUID.randomUUID().toString()) },
            { val recipe = Recipe(geometry = GeometryRecipe.None(SourceBindingForTest.changed())); it.copy(canonicalRecipe = RecipeCodec.encode(recipe),recipeSha256 = RecipeCodec.hash(recipe)) },
        )
        for (change in changes) {
            val f = Fixture(); f.changeRow(change); val row = f.memory.recipe(f.base.pageIds[0].value,1)!!; val bytes = row.canonicalRecipe.copyOf()
            rejected(OperationErrorCode.INTEGRITY_FAILED) { f.load() }; assertArrayEquals(bytes,row.canonicalRecipe); assertEquals(1L,f.memory.page(f.base.pageIds[0].value)!!.currentRecipeRevision)
        }
    }

    @Test fun unknownRecipeVersionAndMaskAssociationRefuseWithoutDroppingRetainedMetadata() = runBlocking {
        val f = Fixture(); f.changeRow { it.copy(recipeSchemaVersion = 2,canonicalRecipe = byteArrayOf(123,125)) }
        rejected(OperationErrorCode.UNSUPPORTED_VERSION) { f.load() }; assertArrayEquals(byteArrayOf(123,125),f.memory.recipe(f.base.pageIds[0].value,1)!!.canonicalRecipe)
        val masked = Fixture(); masked.base.protectionMask(masked.base.pageIds[0],1); val before = masked.memory.associations.toMap()
        rejected(OperationErrorCode.PROVIDER_UNAVAILABLE) { masked.load() }; assertEquals(before,masked.memory.associations)
    }

    @Test fun knownCancellationAndStorageFailureCannotPublishAnEmptyOrSuccessfulPage() = runBlocking {
        val f = Fixture(); val reader = f.reader(); val stopped = Job().apply { cancel() }
        try { withContext(stopped) { f.load(reader) }; fail("Canceled read returned") } catch (_: CancellationException) { }
        assertEquals(0,f.guards); f.fail=true
        val error = rejected(OperationErrorCode.STORAGE_FAILED) { f.load(reader) }; assertTrue(error.retryable); assertFalse(error.safeMessageKey.contains("fixture-private"))
    }

    private object SourceBindingForTest { fun changed() = app.pristinepage.core.recipe.SourceBinding("b".repeat(64),app.pristinepage.core.pipeline.PixelSize(3,2),"srgb-v1") }
    private companion object {
        fun type(name: String) = try { Class.forName(name) } catch (_: ClassNotFoundException) { throw AssertionError("Bounded editable-page reader is absent") }
        @Suppress("UNCHECKED_CAST") fun <T> field(value: Any, name: String): T = value.javaClass.methods.single { it.name=="get$name" || it.name.startsWith("get$name-") }.invoke(value) as T
        suspend fun rejected(code: OperationErrorCode, action: suspend () -> Any?): OperationError {
            try { action(); fail("Editable-page read unexpectedly succeeded") } catch (failure: RepositoryFailure) { assertEquals(code,failure.error.code); return failure.error }
            throw AssertionError("Missing structured refusal")
        }
    }
}
