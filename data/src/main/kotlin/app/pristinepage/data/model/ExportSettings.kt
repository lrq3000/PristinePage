package app.pristinepage.data.model

import app.pristinepage.core.pipeline.FallbackPolicy
import app.pristinepage.core.pipeline.OutputSpec
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.RenderPurpose
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import java.util.Collections
import kotlinx.serialization.Serializable

@Serializable enum class ExportFormat { PDF, JPEG, PNG }
@Serializable enum class PdfPageRule { IMAGE_ASPECT, A4_PORTRAIT, A4_LANDSCAPE }
@Serializable enum class PdfMarginRule { NONE, UNIFORM_POINTS }
@Serializable enum class JpegChromaPolicy { YUV444, PLATFORM_VERIFIED }
@Serializable enum class JpegSampling { YUV444, YUV422, YUV420 }

/** Requested physical placement, not a measured writer admission profile. Freeze/export must resolve and validate the exact named profile before allocating or claiming this request is supported. Margins are PostScript points; DPI is nominal placement, not added optical detail. */
@Serializable data class PdfSettings(val pageRule: PdfPageRule, val nominalDpi: Double, val marginRule: PdfMarginRule, val marginPoints: Double, val writerId: String, val writerVersion: Int, val admissionProfileVersion: Int) {
    init {
        require(nominalDpi.isFinite() && nominalDpi > 0)
        require(marginPoints.isFinite() && marginPoints >= 0 && (marginRule != PdfMarginRule.NONE || marginPoints == 0.0))
        exportIdentity(writerId, writerVersion); require(admissionProfileVersion > 0)
    }
}

/** Expected sampling is an explicit claim to be checked against actual encoder evidence/readback, not inferred from a quality number or from PLATFORM_VERIFIED's label. PNG never consumes this record. */
@Serializable data class JpegSettings(val quality: Int, val encoderId: String, val encoderVersion: Int, val chromaPolicy: JpegChromaPolicy, val expectedSampling: JpegSampling) {
    init {
        require(quality in 0..100); exportIdentity(encoderId, encoderVersion)
        require(chromaPolicy != JpegChromaPolicy.YUV444 || expectedSampling == JpegSampling.YUV444)
    }
}

@Serializable data class PageOutputSettings(val pageId: PageId, val output: OutputSpec, val fallbackPolicy: FallbackPolicy = FallbackPolicy.FAIL, val requestedOutput: OutputSpec = output) {
    init {
        requireOpaqueId(pageId.value)
        require(output.purpose == RenderPurpose.EXPORT && requestedOutput.purpose == RenderPurpose.EXPORT)
        require(fallbackPolicy != FallbackPolicy.FAIL || output == requestedOutput)
    }
}

/** Exact prior lossless degraded raster shown to the user, never a wildcard permission to accept a future failure. Freeze must independently verify the retained render/review lease and every request/source/recipe binding. */
@Serializable data class AcceptedExecution(val pageId: PageId, val recipeRevision: Long, val executionHash: String, val renderId: RenderId, val actualSize: PixelSize, val rasterPixelHash: String) {
    init {
        requireOpaqueId(pageId.value); requireOpaqueId(renderId.value); require(recipeRevision > 0)
        requireSha256(executionHash); requireSha256(rasterPixelHash)
    }
}

/** Own immutable reviewed lists before suspension or persistence. Structural validity does not establish renderer/provider/rights/codec/resource support or admit100pages to a PDF writer. Canonical persistence encoding is separate from this in-memory contract. */
class ExportSettings(val format: ExportFormat, pages: List<PageOutputSettings>, val pdf: PdfSettings?, val jpeg: JpegSettings?, acceptedDegraded: List<AcceptedExecution>) {
    val pages: List<PageOutputSettings> = Collections.unmodifiableList(ArrayList(pages))
    val acceptedDegraded: List<AcceptedExecution> = Collections.unmodifiableList(ArrayList(acceptedDegraded))

    init {
        require(this.pages.size in 1..100 && this.pages.map { it.pageId }.toSet().size == this.pages.size)
        require(when (format) {
            ExportFormat.PDF -> pdf != null && jpeg == null
            ExportFormat.JPEG -> jpeg != null && pdf == null
            ExportFormat.PNG -> pdf == null && jpeg == null
        })
        require(this.acceptedDegraded.map { it.pageId }.toSet().size == this.acceptedDegraded.size)
        require(this.acceptedDegraded.map { it.renderId }.toSet().size == this.acceptedDegraded.size)
        val accepted = this.acceptedDegraded.associateBy { it.pageId }
        val fallbackPages = this.pages.filter { it.fallbackPolicy == FallbackPolicy.KEEP_STAGE_INPUT }
        require(accepted.keys == fallbackPages.map { it.pageId }.toSet())
        for (page in fallbackPages) require(accepted.getValue(page.pageId).actualSize == page.output.size)
    }
}

private fun exportIdentity(id: String, version: Int) {
    require(id.isNotBlank() && id.length <= 128 && version > 0)
    requireWellFormedUnicode(id)
}
