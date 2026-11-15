package app.pristinepage.data.files

import app.pristinepage.data.model.SourceInspection
import java.io.File

/** Data owns this port; image-android supplies the admitted full-source normalization/hash bridge. No provider URI or native image ownership crosses this boundary, and no data-to-image dependency is introduced. */
fun interface SourceInspector {
    suspend fun inspect(file: File, expectedByteSha256: String): SourceInspection
}
