package app.pristinepage

import app.pristinepage.core.geometry.GeometrySizing
import app.pristinepage.core.image.RgbImage
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.*
import app.pristinepage.editor.*
import app.pristinepage.fixtures.DocumentFixtures
import app.pristinepage.image.decode.ImageAllocationBudget
import app.pristinepage.image.render.AppearanceEngine
import app.pristinepage.image.render.GeometryEngine
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorLookCarouselTest {
    @Test fun discreteLookNavigationReachesBothEndsWithoutWrapping() {
        assertNull(adjacentLook(EditorLookCard.NONE, -1))
        assertEquals(EditorLookCard.PAPER, adjacentLook(EditorLookCard.NONE, 1))
        assertEquals(EditorLookCard.GRAY, adjacentLook(EditorLookCard.BINARY, -1))
        assertNull(adjacentLook(EditorLookCard.BINARY, 1))
        assertEquals(EditorLookCard.NONE, adjacentLook(null, 1))
    }

    @Test fun precomputedCardsMatchProductionRenderingOfTheSameSamplePage() {
        val app = Path.of(System.getProperty("pristinepage.policyResourceDir")).resolve("../../../..").normalize()
        val candidates = Files.createDirectories(app.resolve("build/looks-sample-candidates"))
        val resources = app.resolve("src/main/res/drawable-nodpi")
        val budget = ImageAllocationBudget(16L * 1024 * 1024)
        val png = HostPng()
        assertEquals(
            listOf(
                Triple("none", R.string.editor_look_none, R.drawable.editor_look_sample_none),
                Triple("paper", R.string.editor_look_paper, R.drawable.editor_look_sample_paper),
                Triple("natural", R.string.editor_look_natural, R.drawable.editor_look_sample_natural),
                Triple("gray", R.string.editor_look_gray, R.drawable.editor_look_sample_gray),
                Triple("binary", R.string.editor_look_binary, R.drawable.editor_look_sample_binary),
            ),
            EditorLookCard.entries.map { Triple(it.id, it.label, it.sample) },
        )
        val samples = DocumentFixtures.generate("F03-smooth").use { fixture ->
            val image = fixture.corrupted
            val base = Recipe(geometry = GeometryRecipe.None(binding(image)))
            val output = GeometrySizing.output(base.geometry, RenderPurpose.CARD, 216)
            assertEquals(PixelSize(144, 216), output.size)
            println("F03-smooth fixture-v1, 512x768 opaque RGB8 sha256=${base.geometry.source.sourcePixelHash}")
            EditorLookCard.entries.associate { card ->
                val id = card.id
                val name = "editor_look_sample_$id.png"
                val recipe = EditorLooks.select(base, id)
                println("$id recipe-json-v1 sha256=${RecipeCodec.hash(recipe)}")
                id to render(budget, image, recipe, output).use { result ->
                    val pixels = result.image.toPackedByteArray()
                    val rgb = IntArray(pixels.size / 3) { index ->
                        val i = index * 3
                        (0xff shl 24) or ((pixels[i].toInt() and 255) shl 16) or
                            ((pixels[i + 1].toInt() and 255) shl 8) or (pixels[i + 2].toInt() and 255)
                    }
                    png.write(output.size, rgb, candidates.resolve(name).toFile())
                    rgb
                }
            }
        }
        assertEquals(0L, budget.usedBytes)
        for ((id, expected) in samples) {
            assertEquals("$id must differ from the other samples", 1, samples.values.count { it.contentEquals(expected) })
            val path = resources.resolve("editor_look_sample_$id.png")
            assertTrue("Missing precomputed Look sample: $id", Files.isRegularFile(path))
            assertArrayEquals("$id must show its own production result", expected, png.read(PixelSize(144, 216), path.toFile()))
        }
    }

    @Test fun selectingGrayFromOriginalPublishesActualGrayPixelsWithTheExistingSourceAndTurns() = runTest {
        val input = byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1, 0, 0, 0, 80, 80, 80)
        RgbImage(3, 2, 9, input).use { original ->
            val binding = binding(original)
            val base = Recipe(geometry = GeometryRecipe.None(binding, outputQuarterTurns = 1))
            val document = DocumentId("00000000-0000-0000-0000-000000000001")
            val page = PageId("00000000-0000-0000-0000-000000000002")
            val source = SourceId("00000000-0000-0000-0000-000000000003")
            val actions = object : EditorActions {
                override suspend fun load(documentId: DocumentId, pageId: PageId): EditablePage =
                    EditablePage(document, 5, page, source, 7, RecipeCodec.hash(base), base)

                override suspend fun save(pageId: PageId, expectedRevision: Long, recipe: Recipe): SaveRecipeResult =
                    error("Choosing a Look must not save")
            }
            val budget = ImageAllocationBudget(16L * 1024 * 1024)
            val renderer = object : RenderCoordinator {
                override suspend fun render(request: RenderRequest): RenderResult {
                    val raster = render(budget, original, request.recipe, request.output)
                    return RenderResult(request, RenderStatus.OK, raster.image, raster.stages, emptyList(), raster)
                }
            }
            val session = EditorSession(document.value, page.value, actions, renderer, this)
            session.use {
                advanceUntilIdle()
                session.showView("original")
                advanceUntilIdle()
                val previous = checkNotNull(session.preview.value.preview)
                assertArrayEquals(input, previous.image!!.toPackedByteArray())
                session.chooseLook("gray")
                advanceUntilIdle()

                val selected = checkNotNull(session.preview.value.preview)
                assertEquals(EditorView.RESULT, session.state.value.view)
                assertEquals(selected.request, session.preview.value.request)
                assertFalse(session.preview.value.previous)
                assertTrue(selected.request.generation > previous.request.generation)
                assertEquals(source.value, selected.request.sourceId)
                assertEquals(page.value, selected.request.pageId)
                assertEquals(binding.sourcePixelHash, selected.request.sourcePixelHash)
                assertEquals(OutputSpec(PixelSize(2, 3), RenderPurpose.PREVIEW), selected.request.output)
                assertEquals(base.copy(binary = BinaryRecipe.GrayscaleV1), selected.request.recipe)
                assertArrayEquals(intArrayOf(255, 76, 0, 149, 80, 28).flatMap { n -> List(3) { n.toByte() } }.toByteArray(), selected.image!!.toPackedByteArray())
                assertEquals(base, session.state.value.page!!.recipe)
                assertTrue(session.state.value.dirty)
                assertArrayEquals(input, original.toPackedByteArray())
            }
            assertEquals(0L, budget.usedBytes)
        }
    }

    private fun binding(image: RgbImage) = SourceBinding(
        MessageDigest.getInstance("SHA-256").digest(image.toPackedByteArray()).toHexString(),
        PixelSize(image.width, image.height),
        "host-synthetic-rgb8-v1",
    )

    private fun render(budget: ImageAllocationBudget, source: RgbImage, recipe: Recipe, output: OutputSpec) =
        GeometryEngine(budget).render(source, recipe.geometry.source, recipe.geometry, output, null, null) {}.use { geometry ->
            AppearanceEngine(budget).render(geometry.image, recipe, geometry.protection) {}
        }

    /** Use the host JBR's standard codec without adding java.desktop to the Android compilation classpath or any app dependency. */
    private class HostPng {
        private val io = Class.forName("javax.imageio.ImageIO")
        private val buffer = Class.forName("java.awt.image.BufferedImage")
        private val int = Int::class.javaPrimitiveType
        private val rectPixels = arrayOf(int, int, int, int, IntArray::class.java, int, int)

        fun write(size: PixelSize, rgb: IntArray, file: File) {
            val image = buffer.getConstructor(int, int, int).newInstance(size.width, size.height, buffer.getField("TYPE_INT_RGB").getInt(null))
            buffer.getMethod("setRGB", *rectPixels).invoke(image, 0, 0, size.width, size.height, rgb, 0, size.width)
            check(io.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
                .invoke(null, image, "png", file) == true)
        }

        fun read(size: PixelSize, file: File): IntArray {
            val image = checkNotNull(io.getMethod("read", File::class.java).invoke(null, file))
            assertEquals(size.width, buffer.getMethod("getWidth").invoke(image))
            assertEquals(size.height, buffer.getMethod("getHeight").invoke(image))
            return buffer.getMethod("getRGB", *rectPixels).invoke(image, 0, 0, size.width, size.height, null, 0, size.width) as IntArray
        }
    }
}
