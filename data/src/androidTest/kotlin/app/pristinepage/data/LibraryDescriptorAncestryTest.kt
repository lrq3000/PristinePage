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
    @Test fun literalStageNameRejectsAnotherDirectChildAndSymlinkAliases() {
        val root = freshStageRoot()
        val prior = File(root, "prior").apply { check(mkdir()) }
        val next = File(root, "next").apply { check(mkdir()) }
        val alias = File(root, "alias")
        android.system.Os.symlink(prior.path, alias.path)
        openStage(root) { parent ->
            openStage(prior) { retained ->
                openStage(next) { candidate ->
                    AndroidLibraryDescriptorAncestry.requireNamedDirectory(parent, "next", candidate)
                    assertThrows(IOException::class.java) {
                        AndroidLibraryDescriptorAncestry.requireNamedDirectory(parent, "next", retained)
                    }
                    assertThrows(IOException::class.java) {
                        AndroidLibraryDescriptorAncestry.requireNamedDirectory(parent, "prior", candidate)
                    }
                    assertThrows(IOException::class.java) {
                        AndroidLibraryDescriptorAncestry.requireNamedDirectory(parent, "alias", retained)
                    }
                    AndroidLibraryDescriptorAncestry.requireVacantName(parent, "fresh")
                    assertThrows(IOException::class.java) { AndroidLibraryDescriptorAncestry.requireVacantName(parent, "alias") }
                }
            }
        }
    }

    @Test fun physicalStageListingCannotIgnoreAnOmittedDotFile() {
        val stage = File(freshStageRoot(), "stage").apply { check(mkdir()) }
        openStage(stage) { held ->
            AndroidLibraryDescriptorAncestry.requirePhysicallyEmpty(held)
            val leftover = File(stage, ".unreported").apply { writeText("preserve") }
            assertThrows(IOException::class.java) { AndroidLibraryDescriptorAncestry.requirePhysicallyEmpty(held) }
            check(leftover.readText() == "preserve")
        }
    }

    @Test fun exactRootDescriptorsAcquireTheirSharedAnchorWithoutAParentLocator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val anchor = File(context.cacheDir, "library-ancestry-${UUID.randomUUID()}").apply { check(mkdir()) }
        val left = File(anchor, "left").apply { check(mkdir()) }
        val branch = File(anchor, "branch").apply { check(mkdir()) }
        val right = File(branch, "right").apply { check(mkdir()) }
        val separate = File(anchor, "separate").apply { check(mkdir()) }
        ParcelFileDescriptor.open(left, ParcelFileDescriptor.MODE_READ_ONLY).use { a ->
            ParcelFileDescriptor.open(right, ParcelFileDescriptor.MODE_READ_ONLY).use { b ->
                assertThrows(IOException::class.java) {
                    AndroidLibraryDescriptorAncestry.withDerivedAnchor(a, b, 1 to 1) { error("Different parents were admitted") }
                }
                assertThrows(IOException::class.java) {
                    AndroidLibraryDescriptorAncestry.withDerivedAnchor(a, b, 2 to 2) { error("Only one of two different ancestors was used") }
                }
                var checker: (() -> Unit)? = null
                AndroidLibraryDescriptorAncestry.withDerivedAnchor(a, b, 1 to 2) { current ->
                    current()
                    checker = current
                }
                assertThrows(IOException::class.java) { checkNotNull(checker).invoke() }
                val moved = assertThrows(IOException::class.java) { AndroidLibraryDescriptorAncestry.withDerivedAnchor(a, b, 1 to 2) { checkCurrent ->
                    checkCurrent()
                    // Move an owned empty synthetic directory while the same leaf inode still exists.
                    check(right.renameTo(File(separate, "right")))
                    assertThrows(IOException::class.java) { checkCurrent() }
                } }
                check(moved.message == "Library folder ancestry changed")
            }
        }
    }

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

    private fun freshStageRoot(): File =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "library-stage-proof-${UUID.randomUUID()}").apply { check(mkdir()) }

    private fun <T> openStage(file: File, use: (ParcelFileDescriptor) -> T): T =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(use)
}
