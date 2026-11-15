package app.pristinepage.data.repository

import app.pristinepage.data.db.PristineDatabase
import java.util.concurrent.Callable

/** SQLite/Room owns rollback and writer serialization; this does not pretend that source-file moves are part of the same atomic transaction. */
class RoomMetadataAccess(private val database: PristineDatabase) : MetadataAccess {
    override val queries: CatalogQueries get() = database.catalog()
    override fun <T> transaction(action: () -> T): T = database.runInTransaction(Callable { action() })
}
