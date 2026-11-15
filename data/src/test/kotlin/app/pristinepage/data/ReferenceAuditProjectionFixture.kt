package app.pristinepage.data

import app.pristinepage.data.repository.ReferenceAuditQueries
import app.pristinepage.data.repository.ReferenceCountRow

/** Only Android SQL is substituted. Both audit-unit and composed-recovery tests use these complete metadata projections; real Room tests separately verify emitted SQL. */
internal fun MemoryMetadata.referenceAuditValues(query: String): List<Triple<String, Long, Long>> = when (query) {
    "sourcesAfter" -> sources.values.map { Triple(it.sourceId, it.refCount, countSourceReferences(it.sourceId)) }
    "sourceBlobsAfter" -> blobs.values.map { blob -> Triple(blob.sourceBlobId, blob.sourceCount, sources.values.count { it.sourceBlobId == blob.sourceBlobId }.toLong()) }
    "masksAfter" -> masks.values.map { Triple(it.maskId, it.refCount, countMaskReferences(it.maskId)) }
    "pageSourcePinsAfter" -> pages.values.map { page -> Triple(page.pageId, 1L, if (references.containsKey(Triple("PAGE", page.pageId, page.sourceId))) 1L else 0L) }
    "recipeSourcePinsAfter" -> pages.values.map { page ->
        val history = recipes.values.filter { it.pageId == page.pageId }
        Triple(page.pageId, history.size.toLong(), history.count { references.containsKey(Triple("RECIPE_HISTORY", "recipe-v1/${page.pageId}/${it.recipeRevision}", it.sourceId)) }.toLong())
    }
    "maskSourcePinsAfter" -> masks.values.map { mask -> Triple(mask.maskId, 1L, if (references.containsKey(Triple("MASK", mask.maskId, mask.sourceId))) 1L else 0L) }
    "directSourceOwnersAfter" -> sources.values.map { source ->
        val direct = references.values.filter { it.sourceId == source.sourceId && it.ownerType in setOf("PAGE", "RECIPE_HISTORY", "MASK") }
        val matched = direct.count { ref ->
            when (ref.ownerType) {
                "PAGE" -> pages[ref.ownerId]?.sourceId == ref.sourceId
                "MASK" -> masks[ref.ownerId]?.sourceId == ref.sourceId
                else -> recipes.values.any { "recipe-v1/${it.pageId}/${it.recipeRevision}" == ref.ownerId && it.sourceId == ref.sourceId }
            }
        }
        Triple(source.sourceId, direct.size.toLong(), matched.toLong())
    }
    else -> error("Unexpected reference-audit query")
}

internal class MemoryReferenceAuditQueries(private val metadata: MemoryMetadata) : ReferenceAuditQueries {
    private fun rows(query: String, after: String?, limit: Int): List<ReferenceCountRow> {
        check(metadata.inTransaction); check(limit in 1..64)
        return metadata.referenceAuditValues(query).filter { after == null || it.first > after }.sortedBy { it.first }.take(limit).map { ReferenceCountRow(it.first, it.second, it.third) }
    }
    override fun sourcesAfter(objectId: String?, limit: Int) = rows("sourcesAfter", objectId, limit)
    override fun sourceBlobsAfter(objectId: String?, limit: Int) = rows("sourceBlobsAfter", objectId, limit)
    override fun masksAfter(objectId: String?, limit: Int) = rows("masksAfter", objectId, limit)
    override fun pageSourcePinsAfter(objectId: String?, limit: Int) = rows("pageSourcePinsAfter", objectId, limit)
    override fun recipeSourcePinsAfter(objectId: String?, limit: Int) = rows("recipeSourcePinsAfter", objectId, limit)
    override fun maskSourcePinsAfter(objectId: String?, limit: Int) = rows("maskSourcePinsAfter", objectId, limit)
    override fun directSourceOwnersAfter(objectId: String?, limit: Int) = rows("directSourceOwnersAfter", objectId, limit)
}
