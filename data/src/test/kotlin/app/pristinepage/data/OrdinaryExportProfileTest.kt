package app.pristinepage.data

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

/** Cross-module frozen descriptor bytes are the actual production identity contract, not private implementation text. The golden is independent of the serializer and retained snapshots must remain compatible. */
class OrdinaryExportProfileTest {
    private fun profile(renderer: String = "classical-v1", encoding: String = "lossless-rgb-v1", backend: String = "cpu-reference", precision: String = "RGB8"): Any {
        val type = try { Class.forName("app.pristinepage.data.model.OrdinaryExportProfile") } catch (_: ClassNotFoundException) { throw AssertionError("Shared ordinary export descriptor is absent") }
        return try { type.getConstructor(String::class.java,String::class.java,String::class.java,String::class.java).newInstance(renderer,encoding,backend,precision) } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun descriptor(value: Any) = value.javaClass.getMethod("providerDescriptor").invoke(value) as ByteArray

    @Test fun descriptorKeepsTheExistingFreezeBytesAndReturnsDetachedBuffers() {
        val value = profile()
        val expected = "{\"backend\":\"cpu-reference\",\"encodingVersion\":\"lossless-rgb-v1\",\"precision\":\"RGB8\",\"providers\":[],\"version\":1}".toByteArray()
        assertArrayEquals(expected,descriptor(value)); descriptor(value).fill(0); assertArrayEquals(expected,descriptor(value))
    }

    @Test fun escapedBackendRemainsUnambiguousDataRatherThanNewJsonFields() {
        val value = profile(backend = "CPU\"\\\n")
        assertArrayEquals("{\"backend\":\"CPU\\\"\\\\\\n\",\"encodingVersion\":\"lossless-rgb-v1\",\"precision\":\"RGB8\",\"providers\":[],\"version\":1}".toByteArray(),descriptor(value))
    }

    @Test fun invalidProfileVersionsRefuseInsteadOfChangingAFrozenRuntimeClaim() {
        profile() // The initially missing type is an intended assertion, not a swallowed error inside assertThrows.
        for (bad in listOf(""," ","x".repeat(129),"\uD800")) assertThrows(IllegalArgumentException::class.java) { profile(encoding = bad) }
    }
}
