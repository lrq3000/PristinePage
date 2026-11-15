package app.pristinepage.data

import app.pristinepage.core.pipeline.PixelSize
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class RepositoryContractTest {
    private val uuid = "9b58d249-0641-4cb7-bbe7-ff805098b21b"
    private val ids = listOf("DocumentId", "PageId", "SourceId", "SourceBlobId", "ImportSessionId", "ExportSnapshotId", "JobId", "RenderId", "TransactionId", "ArtifactId", "PublicationId", "ShareId", "UndoId", "MaskId", "ConfirmationId")
    private fun type(name: String): Class<*> = try { Class.forName("app.pristinepage.data.model.$name") }
    catch (error: ClassNotFoundException) { throw AssertionError("Required repository value contract is absent: $name", error) }
    private fun <T> unwrap(action: () -> T): T = try { action() } catch (error: InvocationTargetException) { throw error.targetException }

    @Test fun allRepositoryIdsRejectPathUriAndNoncanonicalUuidValues() {
        for (name in ids) {
            val ctor = type(name).getMethod("constructor-impl", String::class.java)
            assertEquals(uuid, unwrap { ctor.invoke(null, uuid) })
            for (bad in listOf("../original", "content://private/title", "", uuid.uppercase(), "1-1-1-1-1")) {
                assertThrows(IllegalArgumentException::class.java) { unwrap { ctor.invoke(null, bad) } }
            }
        }
    }

    private fun inspection(mime: String = "image/png", encoded: PixelSize = PixelSize(3, 2), normalized: PixelSize = PixelSize(2, 3), orientation: Int = 6, profile: String? = null, conversion: String = "srgb-v1", pixels: String = "a".repeat(64)): Any = unwrap {
        type("SourceInspection").constructors.single { it.parameterCount == 7 }.newInstance(mime, encoded, normalized, orientation, profile, conversion, pixels)
    }

    @Test fun normalizedInspectionMustMatchOrientationSizeAndBoundedSupportedMetadata() {
        inspection()
        inspection(mime = "image/jpeg", normalized = PixelSize(3, 2), orientation = 1, profile = "b".repeat(64))
        for (orientation in listOf(0, 9)) assertThrows(IllegalArgumentException::class.java) { inspection(orientation = orientation) }
        assertThrows(IllegalArgumentException::class.java) { inspection(normalized = PixelSize(3, 2)) }
        assertThrows(IllegalArgumentException::class.java) { inspection(encoded = PixelSize(12001, 2), normalized = PixelSize(2, 12001)) }
        assertThrows(IllegalArgumentException::class.java) { inspection(encoded = PixelSize(12000, 12000), normalized = PixelSize(12000, 12000)) }
        assertThrows(IllegalArgumentException::class.java) { inspection(mime = "application/pdf") }
        assertThrows(IllegalArgumentException::class.java) { inspection(profile = "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { inspection(pixels = "not-a-hash") }
        assertThrows(IllegalArgumentException::class.java) { inspection(conversion = "") }
        assertThrows(IllegalArgumentException::class.java) { inspection(conversion = "\uD800") }
    }

    @Test fun serializableRepositoryErrorsCannotCarryPrivateUriOrRawUserMessage() {
        val code = type("OperationErrorCode").enumConstants.single { (it as Enum<*>).name == "PERMISSION_LOST" }
        fun error(key: String, affected: String?): Any = unwrap { type("OperationError").constructors.single { it.parameterCount == 4 }.newInstance(code, true, key, affected) }
        error("source_permission_lost", uuid)
        error("source_permission_lost", null)
        for (key in listOf("content://private/user-title", "Failed reading secret file", "", "x".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { error(key, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { error("source_permission_lost", "content://private/title") }
    }
}
