package app.pristinepage.image.render

import app.pristinepage.core.image.*
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.Recipe
import app.pristinepage.core.pipeline.StageRecord
import app.pristinepage.core.pipeline.StageRecords
import app.pristinepage.core.recipe.*
import app.pristinepage.data.model.OperationError
import app.pristinepage.data.model.OperationErrorCode
import app.pristinepage.data.model.RepositoryFailure
import app.pristinepage.image.decode.ImageAllocationBudget
import java.util.concurrent.atomic.AtomicBoolean

/** The final packed image does not borrow the caller's possibly padded source storage. Its conservative peak reservation stays live until the whole output closes, not just until its algorithm returns. */
class AppearanceRaster internal constructor(val image: RgbImage, stages: List<StageRecord>, private val reservation: AutoCloseable) : AutoCloseable {
    val stages: List<StageRecord> = StageRecords.snapshot(stages)
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) try { image.close() } finally { reservation.close() }
    }
}

/** Synchronous off-UI-thread consumer of an ALREADY geometrically completed raster. Existing adapters own candidate clipping and the single blend; this layer preflights, orders, accounts, snapshots and detaches, never rotates or blends their output again. */
class AppearanceEngine(private val budget: ImageAllocationBudget) {
    fun render(input: RgbImage, recipe: Recipe, protection: ProtectionMask?, checkpoint: () -> Unit): AppearanceRaster {
        checkpoint()
        val size = PixelSize(input.width, input.height)
        try {
            require(protection == null || (protection.width == input.width && protection.height == input.height))
            input.getUnsigned(0, 0, 0); protection?.sample(0, 0)
        } catch (_: IllegalArgumentException) { refuse(OperationErrorCode.INVALID_INPUT, "invalid_appearance_graph") }
        catch (_: IllegalStateException) { refuse(OperationErrorCode.INVALID_INPUT, "closed_appearance_input") }
        val n = size.pixelCount
        if (size.width > 12000 || size.height > 12000 || n > 48_000_000 || n > (Int.MAX_VALUE - 8L) / 3) refuse(OperationErrorCode.RESOURCE_LIMIT, "appearance_raster_extent")
        preflight(recipe, protection != null)
        val steps = steps(recipe, size)
        var peak = 6L * n
        var priorOutput = false
        for (step in steps) if (step.kind != Kind.NONE) {
            peak = maxOf(peak, step.workspace + if (priorOutput) 3L * n else 0L)
            priorOutput = true
        }
        // Each core workspace excludes its stage input and includes working+constructor output copies. Between stages only one packed output survives; final detachment needs that3N plus its own6N copies. Retained pure off/flat leases are still covered by the caller's source allowance during this call; none escapes the final detachment.
        peak = maxOf(peak, 6L * n + if (priorOutput) 3L * n else 0L)
        val reservation = budget.reserve(Math.addExact(65536L, peak))
        var current = input
        var intermediate: RgbImage? = null
        var detached: RgbImage? = null
        var delivered = false
        try {
            val records = ArrayList<StageRecord>(steps.size)
            for (step in steps) {
                checkpoint()
                if (step.kind != Kind.NONE) {
                    val next = step.apply(current, recipe, protection, checkpoint)
                    val old = intermediate
                    intermediate = next
                    current = next
                    old?.close()
                }
                checkpoint()
                records.add(StageRecord(step.id, 1, null, step.outcome, size, size, step.warnings))
            }
            // Core exact-off/flat paths may legally retain their input. Always detach the final packed image so an AppearanceRaster cannot outlive an untransferred decoder/geometry permit or retain arbitrary row padding behind a3N charge.
            val packed = ByteArray((3L * n).toInt())
            for (y in 0 until size.height) {
                checkpoint()
                for (x in 0 until size.width) for (c in 0..2) packed[3 * (y * size.width + x) + c] = current.getUnsigned(x, y, c).toByte()
            }
            detached = RgbImage(size.width, size.height, size.width * 3, packed)
            checkpoint()
            return AppearanceRaster(detached, records, reservation).also { delivered = true }
        } finally {
            try { intermediate?.close(); if (!delivered) detached?.close() } finally { if (!delivered) reservation.close() }
        }
    }

    private enum class Kind { NONE, PAPER, NATURAL, BILATERAL, DETAIL, GRAYSCALE, SAUVOLA }
    private class Step(val kind: Kind, val id: String, val workspace: Long, val outcome: String = "OK", val warnings: List<String> = emptyList()) {
        fun apply(input: RgbImage, recipe: Recipe, protection: ProtectionMask?, checkpoint: () -> Unit): RgbImage = when (kind) {
            Kind.PAPER -> PaperGain.apply(input, recipe.appearance as AppearanceRecipe.PaperGainV1, protection, checkpoint)
            Kind.NATURAL -> NaturalLightness.apply(input, recipe.appearance as AppearanceRecipe.NaturalLV1, protection, checkpoint)
            Kind.BILATERAL -> LumaDenoise.apply(input, recipe.denoise as DenoiseRecipe.BilateralLumaV1, protection, checkpoint)
            Kind.DETAIL -> LumaDetail.apply(input, recipe.detail as DetailRecipe.LumaDetail7V1, protection, checkpoint)
            Kind.GRAYSCALE -> Grayscale.apply(input, protection, checkpoint)
            Kind.SAUVOLA -> Sauvola.apply(input, recipe.binary as BinaryRecipe.SauvolaV1, protection, checkpoint)
            Kind.NONE -> error("Identity is recorded without an intermediate allocation")
        }
    }

    private fun steps(recipe: Recipe, size: PixelSize): List<Step> {
        val w = size.width; val h = size.height
        fun outcome(off: Boolean) = if (off) "EXACT_IDENTITY" else "OK"
        val result = ArrayList<Step>(4)
        result.add(when (val appearance = recipe.appearance) {
            AppearanceRecipe.NoneV1 -> Step(Kind.NONE, "none_v1", 0, "EXACT_IDENTITY")
            is AppearanceRecipe.PaperGainV1 -> Step(Kind.PAPER, "paper_gain_v1", PaperGain.workspaceBytes(w, h), outcome(appearance.strength == 0.0))
            is AppearanceRecipe.NaturalLV1 -> Step(Kind.NATURAL, "natural_l_v1", NaturalLightness.workspaceBytes(w, h), outcome(appearance.strength == 0.0 || appearance.lightnessBlend == 0.0))
            else -> refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "appearance_method_not_implemented")
        })
        recipe.denoise?.let { result.add(Step(Kind.BILATERAL, "bilateral_luma_v1", LumaDenoise.workspaceBytes(w, h), outcome((it as DenoiseRecipe.BilateralLumaV1).strength == 0.0))) }
        recipe.detail?.let { val detail = it as DetailRecipe.LumaDetail7V1; result.add(Step(Kind.DETAIL, "luma_detail7_v1", LumaDetail.workspaceBytes(w, h), outcome(detail.amount == 0.0 || detail.maxDelta == 0 || detail.strength == 0.0))) }
        when (val binary = recipe.binary) {
            null -> Unit
            BinaryRecipe.GrayscaleV1 -> result.add(Step(Kind.GRAYSCALE, "grayscale_v1", Grayscale.workspaceBytes(w, h)))
            is BinaryRecipe.SauvolaV1 -> result.add(Step(Kind.SAUVOLA, "sauvola_v1", Sauvola.workspaceBytes(w, h), warnings = listOf("SAUVOLA_WINDOW_${Sauvola.resolvedWindow(w, h, binary.window)}")))
        }
        return result
    }

    companion object {
        /** Shared graph/capability check before expensive source decode, and again at direct adapter entry. Zero-strength settings do not make an incompatible or unavailable graph valid. Size/buffer admission remains the actual raster consumer's responsibility. */
        fun preflight(recipe: Recipe, protectionPresent: Boolean) {
            if (protectionPresent && (recipe.detail != null || recipe.binary != null)) refuse(OperationErrorCode.INVALID_INPUT, "invalid_appearance_graph")
            if (recipe.appearance is AppearanceRecipe.ProviderAppearanceV1 || recipe.appearance is AppearanceRecipe.LocalMaxGainV1) refuse(OperationErrorCode.PROVIDER_UNAVAILABLE, "appearance_method_not_implemented")
        }
        private fun refuse(code: OperationErrorCode, key: String): Nothing = throw RepositoryFailure(OperationError(code, false, key, null))
    }
}
