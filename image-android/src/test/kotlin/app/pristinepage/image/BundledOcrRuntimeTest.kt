package app.pristinepage.image

import org.junit.Assert.assertFalse
import org.junit.Test

class BundledOcrRuntimeTest {
    @Test fun hostWithoutAndroidNativeLibrariesCannotAdvertiseOcrReadiness() {
        assertFalse(BundledOcrRuntime.nativeLibrariesAvailable())
    }
}
