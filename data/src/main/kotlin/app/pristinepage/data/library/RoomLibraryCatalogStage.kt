package app.pristinepage.data.library

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.RoomDatabase
import app.pristinepage.data.db.PristineDatabase
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

/** Fresh, bounded, closed catalog writer for an exclusively owned stage. Never opens the portable input with Room. */
internal class RoomLibraryCatalogStage(private val application: Context) {
    suspend fun write(snapshot: RoomLibrarySavedCatalog.Snapshot, file: File): Int {
        if (!Files.notExists(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Library staging catalog already exists")
        val stagingContext = object : ContextWrapper(application) {
            override fun getDatabasePath(name: String): File {
                check(name == file.name)
                return file
            }
        }
        val stage = Room.databaseBuilder(stagingContext, PristineDatabase::class.java, file.name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).build()
        try {
            val sql = stage.openHelper.writableDatabase
            sql.query("PRAGMA journal_mode=DELETE").use {
                if (!it.moveToFirst() || it.getString(0) != "delete") throw IOException("Library stage cannot be closed without a journal")
            }
            if (sql.path != file.path) throw IOException("Library stage path mismatch")
            val pageSize = sql.pageSize
            if (pageSize !in 512..65_536 || java.lang.Long.bitCount(pageSize) != 1) throw IOException("Invalid SQLite page size")
            val pageCap = RoomLibraryCatalogReader.MAX_PORTABLE_BYTES / pageSize
            sql.query("PRAGMA max_page_count=$pageCap").use {
                if (!it.moveToFirst() || it.getLong(0) !in 1..pageCap) throw IOException("Library stage capacity could not be set")
            }
            RoomLibrarySavedCatalog(stage).writeFresh(snapshot)
            return sql.version
        } finally { stage.close() }
    }
}

/** Extent and fingerprint of a closed regular catalog under the caller's stable local ownership. */
internal fun fingerprintClosedLibraryCatalog(file: File, checkpoint: () -> Unit): LibraryObjectTransfer.ObjectId {
    val path = file.toPath()
    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw IOException("Library catalog is not a regular file")
    val length = Files.size(path)
    if (length !in 1..RoomLibraryCatalogReader.MAX_PORTABLE_BYTES) throw IOException("Library catalog exceeds the size limit")
    val hash = MessageDigest.getInstance("SHA-256")
    var remaining = length
    Files.newInputStream(path, READ, NOFOLLOW_LINKS).use { stream ->
        val buffer = ByteArray(65536)
        while (remaining > 0) {
            checkpoint()
            val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) throw IOException("Library catalog extent changed")
            hash.update(buffer, 0, count)
            remaining -= count
        }
        checkpoint()
        if (stream.read() != -1) throw IOException("Library catalog extent changed")
    }
    checkpoint()
    return LibraryObjectTransfer.ObjectId(hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, length)
}
