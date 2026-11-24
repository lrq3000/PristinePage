package app.pristinepage.data.library

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.room.Room
import app.pristinepage.data.db.PristineDatabase
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Reads one authenticated, closed SQLite copy in an owner-controlled directory. Never opens a provider file as Room, migrates it, or repairs it. */
internal class RoomLibraryCatalogReader(context: Context, private val maximumBytes: Long) {
    init { require(maximumBytes >= 512) }

    private val application = context.applicationContext
    private data class ExpectedSchema(val version: Int, val entries: List<List<String?>>, val identity: String)
    private val expected by lazy {
        // Raw sqlite_master SQL can differ between a valid migrated app database and a newly created catalog. Only fresh Room supplies the portable format.
        val reference = Room.inMemoryDatabaseBuilder(application, PristineDatabase::class.java).build()
        try {
            val database = reference.openHelper.readableDatabase
            ExpectedSchema(database.version, database.query(SCHEMA_QUERY).use(::readSchema), database.query(ROOM_IDENTITY).use(::readIdentity))
        } finally {
            reference.close()
        }
    }

    /** The installed Room schema supplies an independent allowlist. The callback is synchronous, read-only, and must separately validate bounded row semantics and file references. */
    fun <T> read(file: File, load: (SQLiteDatabase) -> T): T {
        validateClosedFile(file)
        val trusted = expected
        return try {
            // Android's default corruption handler may delete a corrupt database. A verification read must leave the owned evidence untouched.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS) {
                // Do not modify the candidate; SQLite still reports the corruption to the caller.
            }.use { candidate ->
                if (candidate.version != trusted.version) throw IOException("Unsupported library catalog schema")
                if (candidate.rawQuery(SCHEMA_QUERY, null).use(::readSchema) != trusted.entries) {
                    throw IOException("Library catalog schema differs")
                }
                if (candidate.rawQuery(ROOM_IDENTITY, null).use(::readIdentity) != trusted.identity) throw IOException("Library Room identity differs")
                candidate.rawQuery("PRAGMA quick_check(1)", null).use {
                    if (!it.moveToFirst() || it.getString(0) != "ok" || it.moveToNext()) throw IOException("Library catalog integrity check failed")
                }
                candidate.rawQuery("PRAGMA foreign_key_check", null).use {
                    if (it.moveToFirst()) throw IOException("Library catalog contains missing references")
                }
                load(candidate)
            }
        } catch (_: SQLiteException) {
            throw IOException("Library SQLite catalog could not be verified")
        }
    }

    private fun validateClosedFile(file: File) {
        val path = file.toPath()
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() !in 512..maximumBytes) throw IOException("Invalid library catalog extent")
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            // notExists is true only for known absence; inaccessible paths must not qualify as a standalone catalog.
            if (!Files.notExists(path.resolveSibling(file.name + suffix), NOFOLLOW_LINKS)) throw IOException("Library catalog sidecar is present or unavailable")
        }
        val header = ByteArray(StandaloneCatalogHeader.SIZE)
        DataInputStream(Files.newInputStream(path, READ, NOFOLLOW_LINKS)).use { it.readFully(header) }
        StandaloneCatalogHeader.verify(header, attributes.size())
    }

    private fun readSchema(cursor: Cursor): List<List<String?>> {
        val result = ArrayList<List<String?>>()
        while (cursor.moveToNext()) {
            if (result.size == 128 || cursor.getInt(0) != 1) throw IOException("Invalid library catalog schema entry")
            val entry = (1..4).map { cursor.getString(it) }
            // The platform maintains locale metadata independently of Room; no other unexpected table, view, index or trigger is ignored.
            if (entry == listOf("table", "android_metadata", "android_metadata", "CREATE TABLE android_metadata (locale TEXT)")) continue
            result += entry
        }
        return result
    }

    private fun readIdentity(cursor: Cursor): String {
        if (!cursor.moveToFirst()) throw IOException("Library Room identity missing")
        return cursor.getString(0).also { if (cursor.moveToNext()) throw IOException("Library Room identity is ambiguous") }
    }

    private companion object {
        // Use CASE to admit raw SQL extents before transferring schema text into an Android CursorWindow.
        const val SCHEMA_BOUNDS = "(typeof(type)='text' AND length(CAST(type AS BLOB))<=16 AND typeof(name)='text' AND length(CAST(name AS BLOB))<=160 AND typeof(tbl_name)='text' AND length(CAST(tbl_name AS BLOB))<=160 AND (sql IS NULL OR (typeof(sql)='text' AND length(CAST(sql AS BLOB))<=16384)))"
        const val SCHEMA_QUERY = "SELECT $SCHEMA_BOUNDS, CASE WHEN $SCHEMA_BOUNDS THEN type END, CASE WHEN $SCHEMA_BOUNDS THEN name END, CASE WHEN $SCHEMA_BOUNDS THEN tbl_name END, CASE WHEN $SCHEMA_BOUNDS THEN sql END FROM sqlite_master ORDER BY type,name LIMIT 129"
        const val ROOM_IDENTITY = "SELECT identity_hash FROM room_master_table WHERE id=42 AND typeof(identity_hash)='text' AND length(CAST(identity_hash AS BLOB))=32"
    }
}
