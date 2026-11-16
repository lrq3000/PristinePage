package app.pristinepage

import app.pristinepage.image.decode.ImageAllocationBudget

/** Application bootstrap selects one allowance; consumers receive that instance rather than independently recalculating it. The image module remains the single owner of byte arithmetic and admission policy. */
object ApplicationImageBudget {
    fun create(memoryClassMiB: Int): ImageAllocationBudget = ImageAllocationBudget.forMemoryClass(memoryClassMiB)
}
