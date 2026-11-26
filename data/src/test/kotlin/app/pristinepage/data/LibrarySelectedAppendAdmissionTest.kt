package app.pristinepage.data

import app.pristinepage.data.library.LibrarySelectedAppendAdmission
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class LibrarySelectedAppendAdmissionTest {
    private val previous = "70000000-0000-4000-8000-000000000001"
    private val next = "70000000-0000-4000-8000-000000000002"

    @Test fun committedStageNamesCanBeInspectedButNeverReusedForASuccessor() {
        val retained = "merge-staging-$previous"
        assertEquals("merge-staging-$next", LibrarySelectedAppendAdmission.stagingName(next))
        assertEquals(setOf(retained), LibrarySelectedAppendAdmission.retainedStagingNames(
            listOf("objects", "unrelated", retained), next, setOf(previous),
        ))
    }

    @Test fun unboundLegacyAndOccupiedStageNamesRefuseBeforeAnyProviderWrite() {
        for (name in listOf("merge-staging", "merge-staging-unrecognized", "merge-staging-$next", "merge-staging-$previous")) {
            val known = if (name == "merge-staging-$previous") emptySet() else setOf(previous)
            assertFailsWith<IOException> {
                LibrarySelectedAppendAdmission.retainedStagingNames(listOf("objects", name), next, known)
            }
        }
    }

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
