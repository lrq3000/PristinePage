package app.pristinepage.core.geometry

/** Composite DIRECT-map certificate. Continuous piecewise-smooth positive local Jacobian plus injective oriented boundary supplies global one-to-one geometry; the additional sampled overlap test is only an implementation regression gate, not that theorem's substitute. */
class ManualMapValidation private constructor(private val candidate: ManualCurve, val local: ManualStretchValidation, val boundary: ManualBoundaryValidation, val overlap: SampledGridOverlapValidation, val scheduledBoxes: Int, val processedBoxes: Int) {
    val injectivityProven: Boolean get() = true
    fun requireCandidate(actual: ManualCurve) {
        require(actual === candidate) { "Composite certificate belongs to a different prepared candidate" }
        local.requireCandidate(actual); boundary.requireCandidate(actual); overlap.requireCandidate(actual)
    }
    companion object {
        internal fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, checkpoint: () -> Unit): ManualMapValidation {
            checkpoint()
            val local = ManualStretchValidator.analyze(candidate, budget, checkpoint)
            val boundary = ManualBoundaryValidator.analyze(candidate, budget, checkpoint)
            val overlap = SampledGridOverlapValidator.analyze(candidate, budget, 1_000_000, checkpoint)
            checkpoint()
            val counts = budget.snapshot()
            return ManualMapValidation(candidate, local, boundary, overlap, counts.scheduledBoxes, counts.processedBoxes)
        }
    }
}

object ManualMapValidator {
    /** Conservative sum, not scratch reuse: callers reserve it in addition to candidate/source/RGB/masks before preparing or proving their actual map. */
    @JvmStatic fun workspaceBytes(): Long = Math.addExact(Math.addExact(ManualStretchValidator.workspaceBytes(), ManualBoundaryValidator.workspaceBytes()), SampledGridOverlapValidator.workspaceBytes())
    @JvmStatic fun analyze(candidate: ManualCurve, budget: GeometryProofBudget, checkpoint: () -> Unit): ManualMapValidation = ManualMapValidation.analyze(candidate, budget, checkpoint)
}
