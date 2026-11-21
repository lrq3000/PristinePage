package app.pristinepage.core.recipe

import app.pristinepage.core.pipeline.GeometryMode
import app.pristinepage.core.pipeline.PixelSize
import app.pristinepage.core.pipeline.PointPx
import app.pristinepage.core.pipeline.QuadPx
import app.pristinepage.core.pipeline.RectPx
import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.pipeline.requireWellFormedUnicode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class SourceBinding(val sourcePixelHash: String, val sourceSize: PixelSize, val orientationColorConversionVersion: String) {
    init { requireSha256(sourcePixelHash); require(orientationColorConversionVersion.isNotBlank() && orientationColorConversionVersion.length <= 128); requireWellFormedUnicode(orientationColorConversionVersion) }
}
@Serializable data class ProviderReference(val providerId: String, val contractVersion: Int, val assetSha256: String, val variantId: String, val profileSha256: String) {
    init { require(providerId.isNotBlank() && contractVersion > 0 && variantId.isNotBlank()); requireWellFormedUnicode(providerId); requireWellFormedUnicode(variantId); requireSha256(assetSha256); requireSha256(profileSha256) }
}

@Serializable sealed class TargetRule {
    @Serializable @SerialName("edge_extents_v1") data object EdgeExtents : TargetRule()
    @Serializable @SerialName("crop_aspect_v1") data object CropAspect : TargetRule()
    @Serializable @SerialName("fixed_ratio_v1") data class FixedRatio(val widthOverHeight: Double) : TargetRule() { init { require(widthOverHeight.isFinite() && widthOverHeight > 0) } }
}

private fun geometryValid(source: SourceBinding, schema: Int, turns: Int, quad: QuadPx? = null) {
    require(schema == 1 && turns in 0..3)
    if (quad != null) {
        require(source.sourceSize.width >= 2 && source.sourceSize.height >= 2)
        require(quad.points().all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }) { "Controls must be normalized source centers" }
    }
}

@Serializable sealed class GeometryRecipe {
    abstract val source: SourceBinding
    abstract val schemaVersion: Int
    abstract val outputQuarterTurns: Int
    abstract val mode: GeometryMode

    @Serializable @SerialName("none_v1") data class None(override val source: SourceBinding, override val schemaVersion: Int = 1, override val outputQuarterTurns: Int = 0) : GeometryRecipe() {
        init { geometryValid(source, schemaVersion, outputQuarterTurns) }
        override val mode: GeometryMode get() = GeometryMode.NONE
    }
    @Serializable @SerialName("perspective_v1") data class Perspective(override val source: SourceBinding, val quad: QuadPx, val targetRule: TargetRule = TargetRule.EdgeExtents, override val schemaVersion: Int = 1, override val outputQuarterTurns: Int = 0) : GeometryRecipe() {
        init { geometryValid(source, schemaVersion, outputQuarterTurns, quad); require(targetRule != TargetRule.CropAspect) }
        override val mode: GeometryMode get() = GeometryMode.PERSPECTIVE
    }
    @Serializable @SerialName("manual_ruled_v1") data class ManualCurve(override val source: SourceBinding, val quad: QuadPx, val topMidpoint: PointPx = midpoint(quad.tl, quad.tr), val bottomMidpoint: PointPx = midpoint(quad.bl, quad.br), val depth: Double = 0.0, val targetRule: TargetRule = TargetRule.EdgeExtents, val arcSegments: Int = 128, override val schemaVersion: Int = 1, override val outputQuarterTurns: Int = 0) : GeometryRecipe() {
        init {
            geometryValid(source, schemaVersion, outputQuarterTurns, quad)
            require(listOf(topMidpoint, bottomMidpoint).all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
            require(depth.isFinite() && depth in -0.95..0.95 && arcSegments == 128 && targetRule != TargetRule.CropAspect)
        }
        override val mode: GeometryMode get() = GeometryMode.MANUAL_CURVE
    }
    /** Four on-curve midpoints use the projective contour sampler, not the legacy top/bottom ruled surface. */
    @Serializable @SerialName("manual_four_sides_v1") data class ManualCurveFourSides(
        override val source: SourceBinding, val quad: QuadPx,
        val topMidpoint: PointPx = midpoint(quad.tl, quad.tr), val rightMidpoint: PointPx = midpoint(quad.tr, quad.br),
        val bottomMidpoint: PointPx = midpoint(quad.bl, quad.br), val leftMidpoint: PointPx = midpoint(quad.tl, quad.bl),
        val targetRule: TargetRule = TargetRule.EdgeExtents, override val schemaVersion: Int = 1, override val outputQuarterTurns: Int = 0,
    ) : GeometryRecipe() {
        init {
            geometryValid(source, schemaVersion, outputQuarterTurns, quad)
            require(listOf(topMidpoint, rightMidpoint, bottomMidpoint, leftMidpoint).all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
            require(targetRule != TargetRule.CropAspect)
        }
        val straight: Boolean get() = topMidpoint == midpoint(quad.tl, quad.tr) && rightMidpoint == midpoint(quad.tr, quad.br) &&
            bottomMidpoint == midpoint(quad.bl, quad.br) && leftMidpoint == midpoint(quad.tl, quad.bl)
        override val mode: GeometryMode get() = GeometryMode.MANUAL_CURVE
    }
    @Serializable @SerialName("rough_crop_dewarp_v1") data class RoughCropDewarp(override val source: SourceBinding, val crop: RectPx, val provider: ProviderReference, val geometryOnly: Boolean = true, val targetRule: TargetRule = TargetRule.CropAspect, override val schemaVersion: Int = 1, override val outputQuarterTurns: Int = 0) : GeometryRecipe() {
        init {
            geometryValid(source, schemaVersion, outputQuarterTurns)
            require(geometryOnly && crop.width >= 2 && crop.height >= 2 && crop.x.toLong() + crop.width <= source.sourceSize.width && crop.y.toLong() + crop.height <= source.sourceSize.height && targetRule != TargetRule.EdgeExtents)
        }
        override val mode: GeometryMode get() = GeometryMode.ROUGH_CROP_DEWARP
    }

    @Serializable @SerialName("atelier_bezier_v1") data class Bezier(override val source: SourceBinding, val contour: BezierContour,
        val targetRule: TargetRule = TargetRule.EdgeExtents, override val schemaVersion: Int = 1, override val outputQuarterTurns: Int = 0) : GeometryRecipe() {
        init { geometryValid(source, schemaVersion, outputQuarterTurns, contour.quad); require(targetRule != TargetRule.CropAspect) }
        override val mode: GeometryMode get() = GeometryMode.ATELIER_BEZIER
    }

    fun withOutputQuarterTurns(turns: Int): GeometryRecipe = when (this) {
        is None -> copy(outputQuarterTurns = turns)
        is Perspective -> copy(outputQuarterTurns = turns)
        is ManualCurve -> copy(outputQuarterTurns = turns)
        is ManualCurveFourSides -> copy(outputQuarterTurns = turns)
        is RoughCropDewarp -> copy(outputQuarterTurns = turns)
        is Bezier -> copy(outputQuarterTurns = turns)
    }
}

private fun midpoint(a: PointPx, b: PointPx) = PointPx((a.x + b.x) / 2, (a.y + b.y) / 2)
private fun validateStrength(value: Double) { require(value.isFinite() && value in 0.0..1.0) }
private fun gain(target: Int, maxGain: Double, strength: Double) { require(target in 240..255 && maxGain.isFinite() && maxGain in 1.0..3.0); validateStrength(strength) }

@Serializable sealed class AppearanceRecipe {
    @Serializable @SerialName("none_v1") data object NoneV1 : AppearanceRecipe()
    @Serializable @SerialName("paper_gain_v1") data class PaperGainV1(val target: Int = 245, val maxGain: Double = 3.0, val strength: Double = 1.0) : AppearanceRecipe() { init { gain(target, maxGain, strength) } }
    @Serializable @SerialName("natural_l_v1") data class NaturalLV1(val amplitudeL255: Double = 45.0, val lightnessBlend: Double = 0.30, val strength: Double = 1.0) : AppearanceRecipe() { init { require(amplitudeL255.isFinite() && amplitudeL255 in 0.0..60.0 && lightnessBlend.isFinite() && lightnessBlend in 0.0..0.60); validateStrength(strength) } }
    @Serializable @SerialName("document_color_v1") data class DocumentColorV1(val strength: Double = 1.0) : AppearanceRecipe() { init { validateStrength(strength) } }
    @Serializable @SerialName("local_max_gain_v1") data class LocalMaxGainV1(val radiusPx: Int = 15, val target: Int = 245, val maxGain: Double = 3.0, val strength: Double = 1.0) : AppearanceRecipe() { init { require(radiusPx in 1..127); gain(target, maxGain, strength) } }
    @Serializable @SerialName("provider_appearance_v1") data class ProviderAppearanceV1(val providerId: String, val contractVersion: Int, val assetSha256: String, val variantId: String, val profileSha256: String, val strength: Double = 1.0) : AppearanceRecipe() {
        init { ProviderReference(providerId, contractVersion, assetSha256, variantId, profileSha256); validateStrength(strength) }
    }
}

@Serializable sealed class DenoiseRecipe {
    @Serializable @SerialName("bilateral_luma_v1") data class BilateralLumaV1(val radiusPx: Int = 1, val sigmaRange: Double = 10.0, val sigmaSpatialPx: Double = 1.0, val strength: Double = 1.0) : DenoiseRecipe() { init { require(radiusPx in 1..2 && sigmaRange.isFinite() && sigmaRange in 5.0..15.0 && sigmaSpatialPx.isFinite() && sigmaSpatialPx in 1.0..3.0); validateStrength(strength) } }
}
@Serializable sealed class DetailRecipe {
    @Serializable @SerialName("luma_detail7_v1") data class LumaDetail7V1(val amount: Double = 0.40, val thresholdY: Int = 3, val maxDelta: Int = 8, val strength: Double = 1.0) : DetailRecipe() { init { require(amount.isFinite() && amount in 0.0..0.8 && thresholdY in 0..6 && maxDelta in 0..12); validateStrength(strength) } }
}
@Serializable sealed class WindowPolicy {
    @Serializable @SerialName("auto_short_side_v1") data object AutoShortSideV1 : WindowPolicy()
    @Serializable @SerialName("explicit_odd_v1") data class ExplicitOdd(val sidePx: Int) : WindowPolicy() { init { require(sidePx in 3..1001 && sidePx % 2 == 1) } }
}
@Serializable sealed class BinaryRecipe {
    @Serializable @SerialName("grayscale_v1") data object GrayscaleV1 : BinaryRecipe()
    @Serializable @SerialName("sauvola_v1") data class SauvolaV1(val window: WindowPolicy = WindowPolicy.AutoShortSideV1, val k: Double = 0.20, val rangeR: Double = 128.0) : BinaryRecipe() { init { require(k.isFinite() && k in 0.10..0.30 && rangeR == 128.0) } }
}
