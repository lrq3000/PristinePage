package app.pristinepage.fixtures

import app.pristinepage.core.image.ProtectionMask
import app.pristinepage.core.image.CoverageMask
import app.pristinepage.core.image.RgbImage
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** Compact semantic labels are separate from float protection planes and never become an appearance protection mask implicitly. */
class SemanticMask(val width: Int, val height: Int, bits: BitSet) {
    private val bits = bits.clone() as BitSet
    fun contains(x: Int, y: Int): Boolean {
        require(x in 0 until width && y in 0 until height)
        return bits[y * width + x]
    }

    /** Owned row-major, least-significant-bit-first export. Trailing zero bytes are omitted; dimensions remain part of the manifest identity. BitSet exports a fresh array, never its backing storage. */
    fun toPackedBits(): ByteArray = bits.toByteArray()
}

data class Fixture(
    val id: String,
    val clean: RgbImage,
    val corrupted: RgbImage,
    val masks: Map<String, SemanticMask> = emptyMap(),
    val parameters: Map<String, String> = emptyMap(),
    val coordinateSamples: Map<String, List<Double>> = emptyMap(),
    val rawRgba: ByteArray? = null,
    val illumination: DoubleArray? = null,
    val protection: ProtectionMask? = null,
    val pageCoverage: CoverageMask? = null,
    val vectors: Map<String, PrimitiveVector> = emptyMap(),
) : AutoCloseable {
    override fun close() { clean.close(); corrupted.close(); protection?.close(); pageCoverage?.close() }
}

/** Input/expected datasets, not implementations of the stages being tested. Symbolic states/units live in the bounded metadata. */
data class PrimitiveVector(val inputs: List<Double>, val expected: List<Double>, val metadata: Map<String, String> = emptyMap())

/** fixture-v1 is independent of Android fonts/codecs and production renderers. Generate fresh owned inputs on every call; encoded PNG/JPEG bytes are not reproducibility oracles. */
object DocumentFixtures {
    private const val WIDTH = 512
    private const val HEIGHT = 768
    private const val SEED = 20261114L
    private val parameters = mapOf("generator" to "fixture-v1", "seed" to SEED.toString())
    private val dimensions = setOf(1 to 1, 1 to 17, 17 to 1, 2 to 2, 31 to 47, 512 to 768)
    private val colors = mapOf("black" to intArrayOf(0, 0, 0), "gray80" to intArrayOf(80, 80, 80), "gray200" to intArrayOf(200, 200, 200), "white" to intArrayOf(255, 255, 255), "cream" to intArrayOf(240, 220, 180), "blue" to intArrayOf(40, 100, 220))

    fun generate(id: String): Fixture = when (id) {
        "F01-padding" -> {
            val clean = RgbImage(1, 2, 5, byteArrayOf(-1, 0, -128, 77, 88, 1, 2, 3))
            Fixture(id, clean, clean.retain(), parameters = parameters)
        }
        "F01-alpha" -> {
            val clean = RgbImage(2, 1, 6, byteArrayOf(-1, -1, -1, 127, 127, -1))
            Fixture(id, clean, clean.retain(), parameters = parameters + ("alphaPolicy" to "white-composite"), rawRgba = byteArrayOf(-6, 20, 100, 0, 0, 0, -1, -128))
        }
        "F01-checkerboard", "F01-center-channel" -> {
            val bytes = ByteArray(31 * 47 * 3)
            if (id.endsWith("checkerboard")) for (y in 0 until 47) for (x in 0 until 31) for (c in 0..2) bytes[(y * 31 + x) * 3 + c] = (if ((x + y) % 2 == 0) 0 else 255).toByte()
            else bytes[(23 * 31 + 15) * 3] = 200.toByte()
            val clean = RgbImage(31, 47, 93, bytes)
            Fixture(id, clean, clean.retain(), parameters = parameters)
        }
        "F02-white", "F02-cream" -> canvas(id, id.endsWith("cream"))
        "F03-smooth", "F03-color-noise", "F03-abrupt", "F03-glare", "F03-smooth-cream", "F03-color-noise-cream", "F03-abrupt-cream", "F03-glare-cream" -> lighting(id)
        "F04-curl", "F04-perspective" -> geometry(id)
        "F04-corners" -> corners(id)
        "F04-invalid-cell", "F04-overlap", "F04-manual-edge" -> diagnostic(id)
        "F05-primitives" -> {
            val clean = RgbImage(3, 2, 9, ByteArray(18) { it.toByte() })
            Fixture(id, clean, clean.retain(), parameters = parameters + mapOf("compose200_255_half" to "228", "compose3_8_point1" to "4"), protection = ProtectionMask(3, 2, floatArrayOf(0f, .25f, .5f, .75f, 1f, .125f)), vectors = primitiveVectors())
        }
        else -> constant(id)
    }

    private fun constant(id: String): Fixture {
        val match = Regex("F01-([a-z0-9]+)-(\\d+)x(\\d+)(?:-(padded-[ab]))?").matchEntire(id) ?: throw IllegalArgumentException("Unknown fixture identity")
        val color = colors[match.groupValues[1]] ?: throw IllegalArgumentException("Unknown fixture color")
        val w = match.groupValues[2].toInt(); val h = match.groupValues[3].toInt()
        require(w to h in dimensions) { "Unknown fixture dimensions" }
        val padded = match.groupValues[4]
        val stride = w * 3 + if (padded.isEmpty()) 0 else 5
        val padding = if (padded == "padded-b") 93 else 17
        val bytes = ByteArray(stride * h) { padding.toByte() }
        for (y in 0 until h) for (x in 0 until w) for (c in 0..2) bytes[y * stride + x * 3 + c] = color[c].toByte()
        val clean = RgbImage(w, h, stride, bytes)
        return Fixture(id, clean, clean.retain(), parameters = parameters + mapOf("rowStride" to stride.toString(), "paddingValue" to padding.toString()))
    }

    private class Canvas(background: IntArray) {
        val pixels = ByteArray(WIDTH * HEIGHT * 3) { background[it % 3].toByte() }
        private val labels = linkedMapOf<String, BitSet>()

        fun rectangle(name: String, x0: Int, y0: Int, x1: Int, y1: Int, color: IntArray) {
            require(x0 >= 0 && y0 >= 0 && x1 <= WIDTH && y1 <= HEIGHT)
            val label = labels.getOrPut(name) { BitSet(WIDTH * HEIGHT) }
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = y * WIDTH + x
                label.set(p)
                for (c in 0..2) pixels[p * 3 + c] = color[c].toByte()
            }
        }

        fun masks(): Map<String, SemanticMask> {
            val union = BitSet(WIDTH * HEIGHT)
            labels.values.forEach(union::or)
            val background = union.clone() as BitSet
            background.flip(0, WIDTH * HEIGHT)
            val fill = labels["dark-fill"]?.clone() as BitSet?
            if (fill != null) {
                for (thickness in 1..3) labels["white-bars-$thickness"]?.let(fill::andNot)
                labels["dark-fill"] = fill
            }
            return (labels + ("background" to background)).mapValues { SemanticMask(WIDTH, HEIGHT, it.value) }
        }
    }

    private fun canvas(id: String, cream: Boolean): Fixture {
        val white = intArrayOf(255, 255, 255); val black = intArrayOf(0, 0, 0)
        val canvas = Canvas(if (cream) intArrayOf(240, 225, 190) else white)
        canvas.rectangle("frame", 16, 16, 496, 17, black)
        canvas.rectangle("frame", 16, 751, 496, 752, black)
        canvas.rectangle("frame", 16, 16, 17, 752, black)
        canvas.rectangle("frame", 495, 16, 496, 752, black)
        val gray = intArrayOf(0, 64, 128, 176, 208, 224, 240)
        for (j in gray.indices) for ((dy, thickness) in listOf(0 to 1, 8 to 2, 16 to 3)) {
            val y = 64 + 48 * j + dy
            canvas.rectangle("gray-${gray[j]}-$thickness", 32, y, 224, y + thickness, IntArray(3) { gray[j] })
        }
        val patches = listOf(intArrayOf(180, 40, 40), intArrayOf(40, 160, 40), intArrayOf(40, 70, 180), intArrayOf(210, 160, 30), intArrayOf(160, 50, 150), intArrayOf(40, 160, 170))
        for ((j, color) in patches.withIndex()) canvas.rectangle("patch-$j", 288, 64 + 64 * j, 352, 104 + 64 * j, color)
        canvas.rectangle("dark-fill", 32, 440, 224, 504, intArrayOf(80, 80, 80))
        for ((y, thickness) in listOf(452 to 1, 464 to 2, 480 to 3)) canvas.rectangle("white-bars-$thickness", 32, y, 224, y + thickness, white)
        val clean = RgbImage(WIDTH, HEIGHT, WIDTH * 3, canvas.pixels)
        return Fixture(id, clean, clean.retain(), canvas.masks(), parameters + ("background" to if (cream) "240,225,190" else "255,255,255"))
    }

    // Keep the generator's scalar rounding independent from production Numeric, so a production arithmetic mutation does not change both the image and its oracle.
    private fun fixtureByte(value: Double): Byte = floor(value.coerceIn(0.0, 255.0) + 0.5).toInt().toByte()

    private fun lighting(id: String): Fixture {
        val variant = id.removeSuffix("-cream")
        val base = canvas(id, id.endsWith("-cream"))
        val source = base.clean.toPackedByteArray()
        val pixels = ByteArray(source.size)
        val field = DoubleArray(WIDTH * HEIGHT)
        val glare = BitSet(WIDTH * HEIGHT)
        var state = SEED
        fun draw(): Int { state = (1664525L * state + 1013904223L) and 0xffffffffL; return ((state ushr 16) % 7).toInt() }
        val gains = doubleArrayOf(1.03, 1.0, .96)
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val p = y * WIDTH + x
            val dx = (x - .35 * 511) / (.18 * 512)
            val dy = (y - .55 * 767) / (.22 * 768)
            val smooth = (.55 + .35 * x / 511.0 - .25 * exp(-dx * dx - dy * dy)).coerceIn(.20, 1.0)
            val light = if (variant == "F03-abrupt") { if (x < 256) .55 else .9 } else smooth
            field[p] = light
            val clipped = variant == "F03-glare" && (x - 384) * (x - 384) + (y - 512) * (y - 512) <= 48 * 48
            if (clipped) glare.set(p)
            for (c in 0..2) {
                val input = source[p * 3 + c].toInt() and 255
                val gain = if (variant == "F03-color-noise") gains[c] else 1.0
                val noise = if (variant == "F03-color-noise") draw() - draw() else 0
                pixels[p * 3 + c] = if (clipped) 255.toByte() else fixtureByte(input * light * gain + noise)
            }
        }
        base.corrupted.close()
        val masks = if (variant == "F03-glare") base.masks + ("glare-lost" to SemanticMask(WIDTH, HEIGHT, glare)) else base.masks
        val glareParameters = if (variant == "F03-glare") mapOf("glareCircle" to "384,512,48") else emptyMap()
        return Fixture(id, base.clean, RgbImage(WIDTH, HEIGHT, WIDTH * 3, pixels), masks, base.parameters + glareParameters + ("variant" to variant), illumination = field)
    }

    private fun geometry(id: String): Fixture {
        val base = canvas(id, false)
        val source = base.clean.toPackedByteArray()
        val output = ByteArray(source.size)
        val coverage = FloatArray(WIDTH * HEIGHT)
        // Independently derived fixed fixture matrix, not the production solver. All four exact-rational correspondences were checked during fixture preparation.
        val inverse = doubleArrayOf(1.1780656958981979, .049086070662424916, -49.086070662424916, .08168551970144114, 1.19805428895447, -51.189592346236445, .000020634180548441628, .00015952490614727337, 1.0)
        fun coordinates(x: Double, y: Double): List<Double> = if (id == "F04-curl") listOf(x + 16 * sin(PI * x / 511) * sin(2 * PI * y / 767), y) else {
            val z = inverse[6] * x + inverse[7] * y + 1
            listOf((inverse[0] * x + inverse[1] * y + inverse[2]) / z, (inverse[3] * x + inverse[4] * y + inverse[5]) / z)
        }
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            // Coverage describes the whole projected clean page, not the printed frame/background classifier or RGB brightness.
            coverage[y * WIDTH + x] = if (id == "F04-curl" || insidePerspectivePage(x, y)) 1f else 0f
            val (sx, sy) = coordinates(x.toDouble(), y.toDouble())
            val x0 = floor(sx).toInt(); val y0 = floor(sy).toInt()
            val fx = sx - x0; val fy = sy - y0
            for (c in 0..2) {
                var sample = 0.0
                for (dy in 0..1) for (dx in 0..1) {
                    val nx = x0 + dx; val ny = y0 + dy
                    val value = if (nx in 0 until WIDTH && ny in 0 until HEIGHT) source[(ny * WIDTH + nx) * 3 + c].toInt() and 255 else 32
                    sample += value * (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
                }
                output[(y * WIDTH + x) * 3 + c] = fixtureByte(sample)
            }
        }
        base.corrupted.close()
        val transform = if (id == "F04-curl") mapOf("inverseFormula" to "sx=x+16*sin(pi*x/511)*sin(2*pi*y/767);sy=y") else {
            val forward = doubleArrayOf(.8570697623422232, -.04044155895901169, 40.0, -.058791477146246086, .8377844135088531, 40.0, -.000008306227344764918, -.00013281300150742756, 1.0)
            mapOf("forwardMatrix" to forward.joinToString(","), "inverseMatrix" to inverse.joinToString(","), "destinationCorners" to "40,40;480,10;500,730;10,760")
        }
        return Fixture(id, base.clean, RgbImage(WIDTH, HEIGHT, WIDTH * 3, output), base.masks, parameters + transform + mapOf("outside" to "32", "semanticMaskSpace" to "clean-canvas", "pageCoverageSpace" to "corrupted-destination"), mapOf("center" to coordinates(255.5, 383.5), "quarter" to coordinates(255.5, 191.75)), pageCoverage = CoverageMask(WIDTH, HEIGHT, coverage))
    }

    private fun insidePerspectivePage(x: Int, y: Int): Boolean {
        val vertices = arrayOf(40 to 40, 480 to 10, 500 to 730, 10 to 760)
        return vertices.indices.all { i ->
            val a = vertices[i]; val b = vertices[(i + 1) % 4]
            (b.first - a.first).toLong() * (y - a.second) - (b.second - a.second).toLong() * (x - a.first) >= 0
        }
    }

    private fun corners(id: String): Fixture {
        val canvas = Canvas(intArrayOf(255, 255, 255))
        canvas.rectangle("corner-tl", 0, 0, 8, 8, intArrayOf(255, 0, 0))
        canvas.rectangle("corner-tr", 504, 0, 512, 8, intArrayOf(0, 255, 0))
        canvas.rectangle("corner-br", 504, 760, 512, 768, intArrayOf(0, 0, 255))
        canvas.rectangle("corner-bl", 0, 760, 8, 768, intArrayOf(255, 255, 0))
        val clean = RgbImage(WIDTH, HEIGHT, WIDTH * 3, canvas.pixels)
        return Fixture(id, clean, clean.retain(), canvas.masks(), parameters + mapOf("markerOrder" to "TL,TR,BR,BL", "semanticMaskSpace" to "clean-canvas", "pageCoverageSpace" to "corrupted-destination"), pageCoverage = CoverageMask(WIDTH, HEIGHT, FloatArray(WIDTH * HEIGHT) { 1f }))
    }

    private fun diagnostic(id: String): Fixture {
        val clean = RgbImage(101, 101, 303, ByteArray(101 * 101 * 3) { 255.toByte() })
        val points: Map<String, List<Double>>
        val vectors: Map<String, PrimitiveVector>
        when (id) {
            "F04-invalid-cell" -> {
                points = mapOf("p00" to listOf(0.0, 0.0), "p10" to listOf(2.0, 0.0), "p01" to listOf(0.0, 2.0), "p11" to listOf(0.25, 1.25))
                vectors = mapOf("jacobian-center-and-min" to PrimitiveVector(listOf(0.5, 0.5, 1.0, 1.0), listOf(1.5, -1.0), mapOf("expectedStatus" to "INVALID_MAP", "formula" to "4-1.5*u-3.5*v")))
            }
            "F04-overlap" -> {
                points = mapOf("a00" to listOf(0.0, 0.0), "a10" to listOf(2.0, 0.0), "a01" to listOf(0.0, 2.0), "a11" to listOf(2.0, 2.0), "b00" to listOf(1.0, 1.0), "b10" to listOf(3.0, 1.0), "b01" to listOf(1.0, 3.0), "b11" to listOf(3.0, 3.0))
                vectors = mapOf("positive-jacobians" to PrimitiveVector(emptyList(), listOf(4.0, 4.0)), "overlap-area" to PrimitiveVector(emptyList(), listOf(1.0), mapOf("cellRelation" to "NONADJACENT", "expectedStatus" to "INVALID_MAP")))
            }
            else -> {
                points = mapOf("p0" to listOf(0.0, 0.0), "p2" to listOf(100.0, 0.0), "midpoint" to listOf(50.0, -10.0), "control" to listOf(50.0, -20.0))
                vectors = mapOf("depth-midpoint" to PrimitiveVector(listOf(0.5, 0.5), listOf(0.625)), "bezier-midpoint" to PrimitiveVector(listOf(0.5), listOf(50.0, -10.0)))
            }
        }
        return Fixture(id, clean, clean.retain(), parameters = parameters + mapOf("datasetKind" to "geometry-helper-oracle-not-rendered-output", "coordinateSpace" to "source-pixels"), coordinateSamples = points, vectors = vectors)
    }

    private fun primitiveVectors(): Map<String, PrimitiveVector> = linkedMapOf(
        "ALG-01" to PrimitiveVector(listOf(255.0, 0.0, 128.0, 7.0, 8.0, 1.0, 2.0, 3.0), listOf(255.0, 0.0, 128.0, 1.0, 2.0, 3.0), mapOf("width" to "1", "height" to "2", "rowStride" to "5")),
        "ALG-02" to PrimitiveVector(listOf(200.0, 195.0, 245.0), listOf(245.0, 239.0)),
        "ALG-03" to PrimitiveVector(listOf(200.0, 1.5, 0.5, 0.0), listOf(228.0), mapOf("order" to "clip-candidate-before-blend")),
        "ALG-04" to PrimitiveVector(listOf(10.0, 20.0, 30.0), listOf(10.0, 20.0, 30.0), mapOf("strength" to "0", "alternateProtection" to "1")),
        "ALG-05" to PrimitiveVector(listOf(1.0, 9.0), listOf(1.0986122886681098, 1.6094379124341003), mapOf("outputs" to "mean-log,log-mean")),
        "ALG-06" to PrimitiveVector(listOf(200.0, 180.0, 160.0), listOf(200.0, 180.0, 160.0), mapOf("method" to "natural_l_v1", "imageKind" to "constant")),
        "ALG-07" to PrimitiveVector(listOf(-21.0, 3.0, 0.4, 8.0), listOf(-7.2, -7.0), mapOf("outputs" to "unrounded-delta,signed-integer-delta")),
        "ALG-08" to PrimitiveVector(listOf(250.0, 100.0, 20.0, 8.0), listOf(255.0, 105.0, 25.0)),
        "ALG-09" to PrimitiveVector(listOf(40.0, 100.0, 220.0), listOf(40.0, 100.0, 220.0), mapOf("method" to "bilateral_luma_v1", "imageKind" to "constant")),
        "ALG-10" to PrimitiveVector(listOf(80.0, 0.2, 128.0), listOf(64.0, 255.0), mapOf("outputs" to "threshold,binary", "imageKind" to "constant")),
        "ALG-11" to PrimitiveVector(listOf(0.0, 0.2, 128.0), listOf(0.0, 0.0), mapOf("outputs" to "threshold,binary", "comparison" to "strict-greater-than")),
        "ALG-12" to PrimitiveVector(listOf(3.0, 8.0, 0.1, 0.0), listOf(4.0), mapOf("precision" to "ordered-binary64")),
        "GEO-01" to PrimitiveVector(listOf(0.0, 0.0, 511.0, 0.0, 511.0, 767.0, 0.0, 767.0), listOf(512.0, 768.0), mapOf("geometry" to "full-frame-perspective-identity")),
        "GEO-02" to PrimitiveVector(listOf(-1.0, 1.0, 4000.0), listOf(0.0, 3999.0)),
        "GEO-03" to PrimitiveVector(listOf(0.0, 0.0, 2.0, 0.0, 0.0, 2.0, 0.25, 1.25), listOf(1.5, -1.0), mapOf("expectedStatus" to "INVALID_MAP")),
        "GEO-04" to PrimitiveVector(listOf(100.0, 200.0, 401.0, 601.0, 0.0, 0.0), listOf(300.0, 500.0)),
        "GEO-05" to PrimitiveVector(listOf(1000.0, 500.0, 256.0, 128.0, 128.0), listOf(500.0, 250.0), mapOf("letterboxY" to "64", "scale" to "0.256")),
        "GEO-06" to PrimitiveVector(listOf(0.0, 0.25, 0.5, 0.75, 1.0, 0.125), listOf(0.75, 0.0, 1.0, 0.25, 0.125, 0.5), mapOf("sourceSize" to "3x2", "quarterTurns" to "1", "outsideProtection" to "1")),
        "GEO-07" to PrimitiveVector(listOf(0.0, 0.0, 1.0, 401.0, 301.0), listOf(0.0, 1.0, 301.0, 401.0)),
        "STATE-01" to PrimitiveVector(listOf(8.0, 9.0), listOf(9.0), mapOf("lateGeneration" to "8", "acceptedGeneration" to "9")),
        "STATE-02" to PrimitiveVector(emptyList(), emptyList(), mapOf("frozenOrder" to "B,A", "liveOrder" to "A,B", "expectedExportOrder" to "B,A")),
        "STATE-03" to PrimitiveVector(emptyList(), emptyList(), mapOf("policyA" to "FAIL", "policyB" to "KEEP_STAGE_INPUT", "expectedSameJobKey" to "false", "samePolicyRetryReusesJob" to "true")),
    )
}
