package app.pristinepage.data

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.AndroidLibraryDescriptorAncestry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android directories and descriptors, but no Files provider grant or mutation. */
@RunWith(AndroidJUnit4::class)
class LibraryDescriptorAncestryTest {
    @Test fun borrowedDescriptorsSeparateSiblingsButRefuseAliasesAndNestedRoots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val anchor = File(context.cacheDir, "library-ancestry-${UUID.randomUUID()}").apply { check(mkdir()) }
        val left = File(anchor, "left").apply { check(mkdir()) }
        val nested = File(left, "nested").apply { check(mkdir()) }
        val right = File(anchor, "right").apply { check(mkdir()) }
        val third = File(anchor, "third").apply { check(mkdir()) }
        fun <T> open(file: File, block: (ParcelFileDescriptor) -> T): T =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(block)
        open(anchor) { top ->
            open(left) { a ->
                open(right) { b -> AndroidLibraryDescriptorAncestry.admit(a, b, top).requireCurrent() }
                open(left) { alias ->
                    assertThrows(IOException::class.java) { AndroidLibraryDescriptorAncestry.admit(a, alias, top) }
                }
                open(nested) { child ->
                    assertThrows(IOException::class.java) { AndroidLibraryDescriptorAncestry.admit(child, a, top) }
                    assertThrows(IOException::class.java) { AndroidLibraryDescriptorAncestry.admit(a, child, top) }
                }
                open(right) { b ->
                    val proof = AndroidLibraryDescriptorAncestry.admit(a, b, top)
                    check(right.renameTo(File(third, "moved")))
                    assertThrows(IOException::class.java) { proof.requireCurrent() }
                    AndroidLibraryDescriptorAncestry.admit(a, b, top).requireCurrent()
                }
            }
        }
    }
}
