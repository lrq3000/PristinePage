package app.pristinepage.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pristinepage.data.db.PristineDatabase
import app.pristinepage.data.library.RoomLibraryCatalogReader
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomLibraryCatalogReaderTest {
    @Test fun verifiesExactRoomSchemaAndReadsTheClosedCatalogWithoutChangingItsBytes() = withFixture { reader, file ->
        val original = file.readBytes()
        val title = reader.read(file) { catalog ->
            catalog.rawQuery("SELECT title FROM Document", null).use {
                assertTrue(it.moveToFirst())
                it.getString(0)
            }
        }
        assertEquals("Synthetic portable album", title)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun aMatchingRoomIdentityDoesNotAuthorizeAnUnexpectedTrigger() = withFixture(
        seed = { it.execSQL("CREATE TRIGGER unexpected_portable_trigger AFTER UPDATE ON Document BEGIN SELECT 1; END") },
    ) { reader, file ->
        assertThrows(IOException::class.java) { reader.read(file) { fail("Wrong schema reached the reader") } }
    }

    @Test fun aFutureCatalogIsRejectedWithoutRunningMigrationOrDroppingContent() = withFixture(
        seed = { it.version = it.version + 1 },
    ) { reader, file ->
        val original = file.readBytes()
        assertThrows(IOException::class.java) { reader.read(file) { fail("Wrong version reached the reader") } }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun aPortableCatalogCannotDependOnASidecar() = withFixture { reader, file ->
        File(file.parentFile, "${file.name}-wal").writeText("synthetic sidecar")
        assertThrows(IOException::class.java) { reader.read(file) { fail("Sidecar reached the reader") } }
    }

    @Test fun unreadableBtreeDoesNotChangeOrDeleteTheVerificationFile() = withFixture { reader, file ->
        val corrupted = file.readBytes().apply { this[100] = 0 }
        file.writeBytes(corrupted)
        assertThrows(IOException::class.java) { reader.read(file) { fail("Corrupt catalog reached the reader") } }
        assertArrayEquals(corrupted, file.readBytes())
    }

    @Test fun validCatalogWithTrailingPagesIsRejectedWithoutReadingRows() = withFixture { reader, file ->
        file.appendBytes(ByteArray(4096))
        assertThrows(IOException::class.java) { reader.read(file) { fail("Wrong physical extent reached the reader") } }
    }

    private fun withFixture(
        seed: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit = {},
        test: (RoomLibraryCatalogReader, File) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().context
        val name = "library-catalog-${UUID.randomUUID()}.db"
        val owned = Room.databaseBuilder(context, PristineDatabase::class.java, name).setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val disk = owned.openHelper.writableDatabase
            disk.query("PRAGMA journal_mode=DELETE").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("delete", cursor.getString(0))
            }
            disk.execSQL("INSERT INTO Document(documentId,title,documentRevision,lifecycle,createdAt,updatedAt,deletedAt) VALUES (?,?,1,'ACTIVE',0,0,NULL)",
                arrayOf<Any>(UUID.randomUUID().toString(), "Synthetic portable album"))
            seed(disk)
        } finally {
            owned.close()
        }
        test(RoomLibraryCatalogReader(context, maximumBytes = 4 * 1024 * 1024L), context.getDatabasePath(name))
    }
}
