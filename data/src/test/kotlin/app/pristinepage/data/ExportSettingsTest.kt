package app.pristinepage.data

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Exercise real typed constructors with literal boundary vectors. Reflection turns absent contracts into intended RED assertions, not compiler errors; no settings declaration is runtime/codec/profile acceptance. */
class ExportSettingsTest {
    private val a = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val b = "180aab39-4036-4979-922e-e91a1ded30c4"
    private val render = "39e7bf93-1337-4a3a-8dd0-1b7c3a7b695b"
    private val normal = OutputSpec(PixelSize(3, 2), RenderPurpose.EXPORT)
    private val alternate = OutputSpec(PixelSize(2, 3), RenderPurpose.EXPORT)
    private fun type(name: String): Class<*> = try { Class.forName("app.pristinepage.data.model.$name") }
    catch (_: ClassNotFoundException) { throw AssertionError("Typed export settings contract is absent: $name") }
    private fun value(name: String, vararg args: Any?): Any {
        val constructor = type(name).declaredConstructors.single { !it.isSynthetic && it.parameterCount == args.size }
        constructor.isAccessible = true
        return try { constructor.newInstance(*args) } catch (failure: InvocationTargetException) { throw failure.targetException }
    }
    private fun enum(name: String, value: String): Any = type(name).enumConstants.single { (it as Enum<*>).name == value }
    private fun page(id: String = a, output: OutputSpec = normal, policy: FallbackPolicy = FallbackPolicy.FAIL, requested: OutputSpec = output) = value("PageOutputSettings", id, output, policy, requested)
    private fun pdf(dpi: Double = 300.0, margin: String = "NONE", points: Double = 0.0, writer: String = "android_pdfdocument", version: Int = 1, profile: Int = 1) = value("PdfSettings", enum("PdfPageRule", "IMAGE_ASPECT"), dpi, enum("PdfMarginRule", margin), points, writer, version, profile)
    private fun jpeg(quality: Int = 92, policy: String = "PLATFORM_VERIFIED", sampling: String = "YUV420", encoder: String = "android_bitmap", version: Int = 1) = value("JpegSettings", quality, encoder, version, enum("JpegChromaPolicy", policy), enum("JpegSampling", sampling))
    private fun accepted(id: String = a, revision: Long = 1, execution: String = "a".repeat(64), renderId: String = render, actual: PixelSize = alternate.size, raster: String = "b".repeat(64)) = value("AcceptedExecution", id, revision, execution, renderId, actual, raster)
    private fun settings(format: String = "PNG", pages: List<Any> = listOf(page()), pdf: Any? = null, jpeg: Any? = null, accepted: List<Any> = emptyList()) = value("ExportSettings", enum("ExportFormat", format), pages, pdf, jpeg, accepted)
    private fun get(target: Any, name: String): Any? = target.javaClass.getMethod(name).invoke(target)

    @Test fun mixedAspectOutputOrderIsPreservedRatherThanSortedOrResized() {
        val pages = listOf(page(b, alternate), page(a, normal))
        val requested = settings(pages = pages)
        assertEquals(pages, get(requested, "getPages")); assertEquals("PNG", (get(requested, "getFormat") as Enum<*>).name)
        assertNull(get(requested, "getPdf")); assertNull(get(requested, "getJpeg"))
    }

    @Test fun emptyOversizedOrDuplicatePageSelectionsCannotBecomeExports() {
        assertThrows(IllegalArgumentException::class.java) { settings(pages = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { settings(pages = List(101) { page() }) }
        assertThrows(IllegalArgumentException::class.java) { settings(pages = listOf(page(), page())) }
    }

    @Test fun previewAndCardRastersCannotMasqueradeAsExportSettings() {
        for (purpose in listOf(RenderPurpose.CARD, RenderPurpose.PREVIEW, RenderPurpose.DETAIL_CROP)) {
            assertThrows(IllegalArgumentException::class.java) { page(output = OutputSpec(normal.size, purpose)) }
            assertThrows(IllegalArgumentException::class.java) { page(policy = FallbackPolicy.KEEP_STAGE_INPUT, requested = OutputSpec(normal.size, purpose)) }
        }
    }

    @Test fun ordinaryFailPolicyCannotSilentlyChangeReviewedDimensions() {
        assertThrows(IllegalArgumentException::class.java) { page(output = alternate, requested = normal) }
        page(output = normal, requested = normal)
    }

    @Test fun pdfAndJpegRequireTheirOwnOptionsAndRejectEachOthers() {
        settings("PDF", pdf = pdf()); settings("JPEG", jpeg = jpeg())
        assertThrows(IllegalArgumentException::class.java) { settings("PDF") }
        assertThrows(IllegalArgumentException::class.java) { settings("JPEG") }
        assertThrows(IllegalArgumentException::class.java) { settings("PDF", pdf = pdf(), jpeg = jpeg()) }
        assertThrows(IllegalArgumentException::class.java) { settings("JPEG", pdf = pdf(), jpeg = jpeg()) }
    }

    @Test fun pngNeverAcceptsLossyQualityOrPdfPlacementOptions() {
        assertThrows(IllegalArgumentException::class.java) { settings(pdf = pdf()) }
        assertThrows(IllegalArgumentException::class.java) { settings(jpeg = jpeg()) }
    }

    @Test fun pdfRequiresFinitePhysicalValuesAndExplicitVersionedWriterProfile() {
        pdf(dpi = 72.0, margin = "UNIFORM_POINTS", points = 12.0)
        for (dpi in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { pdf(dpi = dpi) }
        for (points in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) { pdf(margin = "UNIFORM_POINTS", points = points) }
        assertThrows(IllegalArgumentException::class.java) { pdf(points = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { pdf(writer = "") }
        assertThrows(IllegalArgumentException::class.java) { pdf(writer = "\uD800") }
        assertThrows(IllegalArgumentException::class.java) { pdf(version = 0) }
        assertThrows(IllegalArgumentException::class.java) { pdf(profile = 0) }
    }

    @Test fun jpegQualityAndDeclared444PolicyCannotContradictTheirSampling() {
        jpeg(quality = 0); jpeg(quality = 100, policy = "YUV444", sampling = "YUV444")
        for (quality in listOf(-1, 101)) assertThrows(IllegalArgumentException::class.java) { jpeg(quality = quality) }
        assertThrows(IllegalArgumentException::class.java) { jpeg(policy = "YUV444", sampling = "YUV420") }
        assertThrows(IllegalArgumentException::class.java) { jpeg(encoder = "") }
        assertThrows(IllegalArgumentException::class.java) { jpeg(version = 0) }
    }

    @Test fun changedFallbackDimensionsRequireOneExactPageAndRasterAcceptance() {
        val fallback = page(output = alternate, policy = FallbackPolicy.KEEP_STAGE_INPUT, requested = normal)
        val approval = accepted()
        settings(pages = listOf(fallback), accepted = listOf(approval))
        assertThrows(IllegalArgumentException::class.java) { settings(pages = listOf(fallback)) }
        assertThrows(IllegalArgumentException::class.java) { settings(pages = listOf(fallback), accepted = listOf(accepted(id = b))) }
        assertThrows(IllegalArgumentException::class.java) { settings(pages = listOf(fallback), accepted = listOf(accepted(actual = normal.size))) }
    }

    @Test fun duplicateAndUnrequestedDegradedAcceptancesNeverActAsWildcards() {
        val fallback = page(output = alternate, policy = FallbackPolicy.KEEP_STAGE_INPUT, requested = normal)
        assertThrows(IllegalArgumentException::class.java) { settings(accepted = listOf(accepted(actual = normal.size))) }
        assertThrows(IllegalArgumentException::class.java) { settings(pages = listOf(fallback), accepted = listOf(accepted(), accepted())) }
        assertThrows(IllegalArgumentException::class.java) { settings(pages = listOf(fallback), accepted = listOf(accepted(), accepted(id = b))) }
    }

    @Test fun acceptedExecutionRequiresCanonicalIdsPositiveRevisionAndExactHashes() {
        for (revision in listOf(0L, -1L)) assertThrows(IllegalArgumentException::class.java) { accepted(revision = revision) }
        assertThrows(IllegalArgumentException::class.java) { accepted(execution = "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { accepted(raster = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { accepted(renderId = "../other") }
        assertThrows(IllegalArgumentException::class.java) { accepted(id = "content://private/title") }
    }

    @Test fun callerListChangesAndCastMutationCannotRewriteTheReviewedSettings() {
        val first = page(output = alternate, policy = FallbackPolicy.KEEP_STAGE_INPUT, requested = normal)
        val approval = accepted(); val pages = mutableListOf(first); val approvals = mutableListOf(approval)
        val reviewed = settings(pages = pages, accepted = approvals)
        pages.clear(); approvals.clear()
        assertEquals(listOf(first), get(reviewed, "getPages")); assertEquals(listOf(approval), get(reviewed, "getAcceptedDegraded"))
        @Suppress("UNCHECKED_CAST") val exported = get(reviewed, "getPages") as MutableList<Any>
        assertThrows(UnsupportedOperationException::class.java) { exported.clear() }
        assertEquals(listOf(first), get(reviewed, "getPages"))
    }
}
