package app.pristinepage.fixtures

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

/** Pins repeatability of the complete datasets rather than comparing only two RGB arrays. Fixed semantic/scalar tests remain the independent correctness oracles. */
class FixtureManifestTest {
    @Test fun completeManifestsRepeatAcrossAllNamedFixtureVariants() {
        val ids = mutableListOf("F01-padding", "F01-alpha", "F01-checkerboard", "F01-center-channel", "F02-white", "F02-cream", "F04-curl", "F04-perspective", "F04-corners", "F04-invalid-cell", "F04-overlap", "F04-manual-edge", "F05-primitives")
        for (color in listOf("black", "gray80", "gray200", "white", "cream", "blue")) for (size in listOf("1x1", "1x17", "17x1", "2x2", "31x47", "512x768")) for (suffix in listOf("", "-padded-a", "-padded-b")) ids += "F01-$color-$size$suffix"
        for (variant in listOf("smooth", "color-noise", "abrupt", "glare")) for (suffix in listOf("", "-cream")) ids += "F03-$variant$suffix"
        assertEquals(129, ids.size)
        for (id in ids) {
            val first = DocumentFixtures.generate(id).use(::manifest)
            val second = DocumentFixtures.generate(id).use(::manifest)
            assertEquals("Complete fixture manifest differs for$id", first, second)
        }
    }

    private fun manifest(fixture: Fixture): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val sink = object : OutputStream() { override fun write(value: Int) = Unit; override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit }
        DataOutputStream(DigestOutputStream(sink, digest)).use { out ->
            fun bytes(bytes: ByteArray) { out.writeInt(bytes.size); out.write(bytes) }
            fun text(text: String) = bytes(text.toByteArray(Charsets.UTF_8))
            fun parameters(values: Map<String, String>) { out.writeInt(values.size); for ((k, v) in values.toSortedMap()) { text(k); text(v) } }
            text("fixture-manifest-v1"); text(fixture.id)
            for (image in listOf(fixture.clean, fixture.corrupted)) { out.writeInt(image.width); out.writeInt(image.height); bytes(image.toPackedByteArray()) }
            parameters(fixture.parameters)
            out.writeInt(fixture.masks.size)
            for ((name, mask) in fixture.masks.toSortedMap()) {
                text(name); out.writeInt(mask.width); out.writeInt(mask.height)
                val packed = try { mask.javaClass.getMethod("toPackedBits").invoke(mask) as ByteArray } catch (error: NoSuchMethodException) { throw AssertionError("Fixture semantic mask needs an owned packed-bit dataset", error) }
                bytes(packed)
            }
            for (mask in listOf(fixture.protection, fixture.pageCoverage)) {
                out.writeBoolean(mask != null)
                if (mask != null) { out.writeInt(mask.width); out.writeInt(mask.height); for (sample in mask.samples) out.writeInt(java.lang.Float.floatToRawIntBits(sample)) }
            }
            out.writeInt(fixture.coordinateSamples.size)
            for ((name, point) in fixture.coordinateSamples.toSortedMap()) { text(name); out.writeInt(point.size); for (v in point) out.writeLong(java.lang.Double.doubleToRawLongBits(v)) }
            bytes(fixture.rawRgba ?: byteArrayOf())
            val illumination = fixture.illumination; out.writeInt(illumination?.size ?: 0); illumination?.forEach { out.writeLong(java.lang.Double.doubleToRawLongBits(it)) }
            out.writeInt(fixture.vectors.size)
            for ((name, vector) in fixture.vectors.toSortedMap()) {
                text(name)
                for (values in listOf(vector.inputs, vector.expected)) { out.writeInt(values.size); for (v in values) out.writeLong(java.lang.Double.doubleToRawLongBits(v)) }
                parameters(vector.metadata)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
