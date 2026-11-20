package app.pristinepage.data.repository

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.data.model.DocumentId
import app.pristinepage.data.model.PageId

internal data class AtelierPageWitness(val page: PageId, val revision: Long, val recipeHash: String) {
    init { require(revision > 0); requireSha256(recipeHash) }
}

/** Full ordered retry identity, not just the generation or a list of resulting page IDs. No recipe bytes, title or URI are recorded here. */
internal data class AtelierPublicationReceipt(
    val generation: Long,
    val document: DocumentId,
    val witnessed: List<AtelierPageWitness>,
    val saved: List<PageId>,
) {
    init {
        require(generation > 0)
        require(witnessed.size in 1..RoomAtelierDrafts.MAX_ITEMS_PER_DRAFT && witnessed.size == saved.size)
        require(witnessed.map { it.page }.toSet().size == witnessed.size && saved.toSet().size == saved.size)
    }

    fun encode(): String = buildString {
        append("atelier-publish-v1\n").append(generation).append('\n').append(document.value)
        for ((index, input) in witnessed.withIndex()) {
            append('\n').append(input.page.value).append(',').append(input.revision).append(',').append(input.recipeHash)
            append(',').append(saved[index].value)
        }
    }

    fun replay(document: DocumentId, generation: Long, witnessed: List<AtelierPageWitness>): List<PageId>? =
        saved.takeIf { this.document == document && this.generation == generation && this.witnessed == witnessed }

    companion object {
        const val MAX_ENCODED_BYTES = 1400

        fun decode(encoded: String): AtelierPublicationReceipt {
            require(encoded.length in 1..MAX_ENCODED_BYTES)
            val lines = encoded.split('\n', limit = RoomAtelierDrafts.MAX_ITEMS_PER_DRAFT + 4)
            require(lines.size in 4..(RoomAtelierDrafts.MAX_ITEMS_PER_DRAFT + 3) && lines[0] == "atelier-publish-v1")
            val generation = requireNotNull(lines[1].toLongOrNull())
            val document = DocumentId(lines[2])
            val inputs = ArrayList<AtelierPageWitness>(lines.size - 3)
            val outputs = ArrayList<PageId>(lines.size - 3)
            for (line in lines.drop(3)) {
                val cells = line.split(',', limit = 5)
                require(cells.size == 4)
                inputs.add(AtelierPageWitness(PageId(cells[0]), requireNotNull(cells[1].toLongOrNull()), cells[2]))
                outputs.add(PageId(cells[3]))
            }
            return AtelierPublicationReceipt(generation, document, inputs.toList(), outputs.toList()).also {
                // One representation per request prevents ambiguous positive decimal spellings and extra delimiters.
                require(it.encode() == encoded)
            }
        }
    }
}
