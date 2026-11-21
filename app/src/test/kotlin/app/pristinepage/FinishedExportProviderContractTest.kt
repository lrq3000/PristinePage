package app.pristinepage

import android.database.Cursor
import android.net.Uri
import app.pristinepage.export.FinishedExportProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class FinishedExportProviderContractTest {
    @Test fun theLeaseProviderOwnsTheStandardMetadataEntryPointInsteadOfInheritingIt() {
        // The host cannot execute Android FileProvider. The device journey exercises the actual lease and returned cursor.
        val query = FinishedExportProvider::class.java.getMethod(
            "query", Uri::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java,
        )
        assertEquals(Cursor::class.java, query.returnType)
        assertEquals(FinishedExportProvider::class.java, query.declaringClass)
    }
}
