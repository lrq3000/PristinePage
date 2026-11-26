package app.pristinepage.data

import app.pristinepage.data.library.LibrarySelectedAppendAdmission
import java.io.IOException
import kotlin.test.assertFailsWith
import org.junit.Test

class LibrarySelectedAppendAdmissionTest {
    @Test fun aFreshAppendCannotAdoptAnExistingCatalogOrStagingNamespace() {
        for ((stage, catalog) in listOf(true to false, false to true)) {
            assertFailsWith<IOException> {
                LibrarySelectedAppendAdmission.requireSpaceForAppend(stage, catalog, 3, 1, 2, 1)
            }
        }
    }

    @Test fun projectedNamespaceEntryCountsMustFitBeforePublication() {
        LibrarySelectedAppendAdmission.requireSpaceForAppend(false, false, 2_047, 2_047, 16_383, 1)
        for ((root, catalogs, objects, missing) in listOf(
            listOf(2_048, 1, 0, 0), listOf(3, 2_048, 0, 0), listOf(3, 1, 16_383, 2),
        )) {
            assertFailsWith<IOException> {
                LibrarySelectedAppendAdmission.requireSpaceForAppend(false, false, root, catalogs, objects, missing)
            }
        }
    }
}
