package app.pristinepage.data.files

import app.pristinepage.data.model.SourceCaptureDate
import app.pristinepage.data.model.SourceInspection
import java.io.File

/** Data owns this port; image-android supplies the admitted full-source normalization/hash bridge. No provider URI or native image ownership crosses this boundary, and no data-to-image dependency is introduced. */
fun interface SourceInspector {
    suspend fun inspect(file: File, expectedByteSha256: String): SourceInspection

    /** The trusted byte inspector may add a projection without widening normalized-source identity. */
    suspend fun inspectForIngest(file: File, expectedByteSha256: String): InspectedSourceForIngest =
        InspectedSourceForIngest(inspect(file, expectedByteSha256), null)
}

data class InspectedSourceForIngest(val inspection: SourceInspection, val captureDate: SourceCaptureDate?)
