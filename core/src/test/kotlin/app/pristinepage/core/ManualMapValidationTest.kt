package app.pristinepage.core

import app.pristinepage.core.geometry.*
import app.pristinepage.core.pipeline.*
import app.pristinepage.core.recipe.*
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ManualMapValidationTest {
    private val binding = SourceBinding("d".repeat(64), PixelSize(101, 101), "literal-srgb-v1")
    private val full = QuadPx(PointPx(0.0, 0.0), PointPx(1.0, 0.0), PointPx(1.0, 1.0), PointPx(0.0, 1.0))
    private fun candidate(top: Double = .1, bottom: Double = .9) = ManualCurve.prepare(GeometryRecipe.ManualCurve(binding, full, PointPx(.5, top), PointPx(.5, bottom), .4), PixelSize(101, 101), {})
    private fun analyze(c: ManualCurve = candidate(), b: GeometryProofBudget = GeometryProofBudget(), check: () -> Unit = {}): Any {
        CoreApi.type("geometry.ManualMapValidator")
        return CoreApi.function("geometry.ManualMapValidator", "analyze", c, b, check)!!
    }

    @Test fun admissionRequiresAllCertificatesOnOneCandidateAndOneCumulativeBudget() {
        val c = candidate(); val b = GeometryProofBudget(); val r = analyze(c, b)
        CoreApi.call(r, "requireCandidate", c)
        assertEquals(true, CoreApi.call(r, "getInjectivityProven"))
        for (name in listOf("Local", "Boundary", "Overlap")) CoreApi.call(CoreApi.call(r, "get$name")!!, "requireCandidate", c)
        assertEquals(b.snapshot().processedBoxes, CoreApi.call(r, "getProcessedBoxes"))
        assertTrue(b.snapshot().processedBoxes in 2500..100000)
        assertThrows(IllegalArgumentException::class.java) { CoreApi.call(r, "requireCandidate", candidate()) }
    }

    @Test fun localBudgetSuccessDoesNotGiveGlobalStagesAFreshAllowance() {
        CoreApi.type("geometry.ManualMapValidator")
        val c = candidate(); val counts = GeometryProofBudget(); ManualStretchValidator.analyze(c, counts, {})
        val b = GeometryProofBudget(counts.snapshot().scheduledBoxes)
        assertThrows(GeometryWorkLimitException::class.java) { analyze(c, b) }
        assertEquals(counts.snapshot().processedBoxes, b.snapshot().processedBoxes)
    }

    @Test fun foldedCandidateAndCancellationCannotPublishAnAdmittedMap() {
        CoreApi.type("geometry.ManualMapValidator")
        assertThrows(InvalidInverseMapException::class.java) { analyze(candidate(.9, .1)) }
        var total = 0; analyze(check = { total++ })
        for (stop in listOf(1, 65, total)) {
            var count = 0; val signal = CancellationException("composite-$stop")
            assertSame(signal, assertThrows(CancellationException::class.java) { analyze(check = { if (++count == stop) throw signal }) })
        }
    }
}
