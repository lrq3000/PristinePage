package app.pristinepage.data

import app.pristinepage.data.repository.SourceMetadataQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceMetadataQueryTest {
    @Test fun admitsOnlyExplicitSupportedSingleFieldQueries() {
        val formats = mapOf("format:jpeg" to "image/jpeg", "FORMAT:PNG" to "image/png", " format:webp " to "image/webp")
        for ((input, mime) in formats) assertEquals(SourceMetadataQuery.Format(mime), SourceMetadataQuery.parse(input))
        for (orientation in 1..8) {
            assertEquals(SourceMetadataQuery.Orientation(orientation), SourceMetadataQuery.parse("orientation:$orientation"))
        }
    }

    @Test fun rejectsAmbiguousTextNumericAliasesAndPrivateFieldSelectors() {
        for (query in listOf("", "JPEG", "6", "format:image/jpeg", "format:tiff", "orientation:0", "orientation:9",
            "orientation:06", "orientation:６", "orientation:+6", "orientation:6 format:jpeg", "orientation:6\u0000",
            "GPS:51", "serial:123", " ".repeat(161) + "format:jpeg")
        ) assertNull(query.take(32), SourceMetadataQuery.parse(query))
    }
}
