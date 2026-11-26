package app.pristinepage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal abstract class LibraryScanNameDao {
    // Removed pages keep reserving their exact names for Undo, without a scan or materializing any of their labels.
    @Query("""SELECT EXISTS(SELECT 1 FROM LibraryScanName WHERE documentId=:document AND comparisonKey=:key)""")
    abstract fun containsKey(document: String, key: String): Boolean

    @Query("""SELECT CASE WHEN typeof(filename)='text' AND length(CAST(filename AS BLOB)) BETWEEN 1 AND 640 THEN filename ELSE '' END
        FROM LibraryScanName WHERE pageId=:page AND documentId=:document""")
    abstract fun storedName(page: String, document: String): String?

    @Insert
    abstract fun insert(row: LibraryScanNameRow)

    @Query("""SELECT EXISTS(SELECT 1 FROM LibraryScanName WHERE pageId=:page AND documentId=:document
        AND typeof(filename)='text' AND typeof(comparisonKey)='text' AND filename=:name AND comparisonKey=:key)""")
    abstract fun matches(page: String, document: String, name: String, key: String): Boolean
}
