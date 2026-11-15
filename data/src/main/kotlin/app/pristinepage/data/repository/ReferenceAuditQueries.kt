package app.pristinepage.data.repository

/** One indexed ownership count observed with its stored counter in the same metadata snapshot. A matching count says nothing about polymorphic-owner legitimacy or file integrity. */
data class ReferenceCountRow(val objectId: String, val storedCount: Long, val actualCount: Long)

interface ReferenceAuditQueries {
    /** Null denotes the initial page, not an exclusive empty-ID sentinel. Initial enumeration includes corrupt empty TEXT IDs so validation can reject them instead of reporting a false-clean audit. */
    fun sourcesAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
    fun sourceBlobsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
    fun masksAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
    fun pageSourcePinsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
    fun recipeSourcePinsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
    fun maskSourcePinsAfter(objectId: String?, limit: Int): List<ReferenceCountRow>
}
