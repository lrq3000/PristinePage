package app.pristinepage

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.library.LibraryDescriptorAncestryWitness
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Headless recovery of an exact completed attempt without Room, a provider, or an Activity. */
@RunWith(AndroidJUnit4::class)
class LibrarySelectedAppendDeathRecoveryTest {
    @Test fun coldProcessInspectsCompletedSelectedAppendWithoutReplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val app = instrumentation.targetContext.applicationContext
        val operation = requireNotNull(args.getString("librarySelectedAppendOperation"))
        require(UUID.fromString(operation).toString() == operation)
        val dead = requireNotNull(args.getString("librarySelectedAppendPreviousPid")?.toIntOrNull())
        assertTrue(dead > 0 && Process.myPid() != dead)
        val folder = requireNotNull(args.getString("libraryFixtureFolder"))
        val grants = LibraryIndependentRootGrants
        val exactTrees = grants.paths(folder).flatMap(grants::spellings)
        val expectedInventory = requireNotNull(args.getString("librarySelectedAppendGrantInventory"))
        require(expectedInventory.matches(Regex("[a-f0-9]{64}")))
        fun requireUnchangedGrants() {
            val inventory = grants.inventory(app)
            assertEquals(expectedInventory, LibraryAncestryGrantWitness.fingerprint(inventory))
            assertTrue(exactTrees.none { it.toString() in inventory })
            grants.refuseEffectiveWrite(app, exactTrees)
        }
        requireUnchangedGrants()
        val files = File(app.filesDir.canonicalFile, "library-selected-death-$operation")
        assertTrue(files.isDirectory && files.canonicalFile == files)
        val cold = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getContentResolver(): ContentResolver = error("Cold append inspection accessed a provider")
            override fun getDatabasePath(name: String): File = error("Cold append inspection opened Room")
        }
        val witness = LibraryDescriptorAncestryWitness
        runBlocking { assertEquals(operation, witness.completedMerge(cold, operation)) }
        assertTrue(File(files, "library-selected-merge-v1.lock").isFile)
        val (left, right) = grants.paths(folder).map { grants.spellings(it).first() }
        val refused = assertThrows(IOException::class.java) {
            runBlocking { witness.attemptRetainedMerge(cold, File(files, "read-scratch"), operation, left, right) }
        }
        assertEquals("The selected merge operation cannot be repeated", refused.message)
        requireUnchangedGrants()
    }
}
