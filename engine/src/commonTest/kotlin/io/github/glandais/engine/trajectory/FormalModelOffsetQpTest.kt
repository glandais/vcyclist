package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexamples found by modelling the racing-line QP solver ([OffsetQp], [BandedLdl],
 * [OffsetEnergy.assemble]) in TLA+ (exact-rational model of one projected-Newton iteration and its
 * capped Armijo line search) and Lean 4 (index lemmas for `buildFreeSystem`, executable Float
 * models of `assemble` / `BandedLdl`), then replayed bit-for-bit in a Python replica of the solver.
 *
 * Contracts under test:
 * - [BandedLdl] KDoc / [OffsetEnergy] KDoc: the centring term "contributes a positive multiple of
 *   the identity", so the Hessian is *strictly* positive definite and `factor` never fails "for
 *   this energy".
 * - [OffsetQp] KDoc, "Why this terminates": projected Newton identifies the active set in finitely
 *   many steps and then takes an exact Newton step to the minimiser.
 * - `docs/guides/racing-line.md`, "Reading the report": "`converged=false` means the solver hit
 *   its iteration cap", and [RacingLineReport.converged] "whether the residual test was met within
 *   the iteration cap".
 */
class FormalModelOffsetQpTest {
    private fun bendPath(
        radiusM: Double,
        turnRad: Double,
        straightM: Double = 150.0,
        spacingM: Double = 1.5,
    ): Path {
        val inbound = CurvatureFixtures.straight(straightM, spacingM)
        val ex = inbound.first.last()
        val ey = inbound.second.last()
        val arc = CurvatureFixtures.arc(radiusM, turnRad, spacingM, startX = ex, startY = ey)
        val (bx, by) = CurvatureFixtures.arcEnd(radiusM, turnRad, ex, ey, 0.0)
        val outbound =
            CurvatureFixtures.straight(straightM, spacingM, headingRad = turnRad, startX = bx, startY = by)
        val (xs, ys) = CurvatureFixtures.join(inbound, arc, outbound)
        return CurvatureFixtures.pathOf(xs, ys)
    }

    /** [base] with point [at] repeated so that it appears [copies] times in a row (a GPS pause). */
    private fun withRepeatedPoint(
        base: Path,
        at: Int,
        copies: Int,
    ): Path {
        val src = ArrayList<Int>()
        for (i in 0 until base.size) {
            src.add(i)
            if (i == at) repeat(copies - 1) { src.add(i) }
        }
        val p = Path(src.size)
        for ((j, i) in src.withIndex()) {
            p.setLatitude(j, base.latitude(i))
            p.setLongitude(j, base.longitude(i))
            p.setElevation(j, base.elevation(i))
        }
        p.computeDerivedData()
        return p
    }

    private fun maxAbsOffset(report: RacingLineReport): Double = report.lateralOffsetM.maxOf { abs(it) }

    /**
     * Contract: the energy's Hessian is strictly positive definite at every station, because the
     * centring term adds `2·w_i·L_C⁻⁴` to every diagonal entry ([OffsetEnergy] KDoc, [BandedLdl]
     * KDoc), so `BandedLdl.factor` cannot fail on it.
     *
     * Counterexample (Lean `factorOnDup 2`): the centring weight is `w_i = ½(Δs⁻ + Δs⁺)`, which is
     * **zero** for the middle one of three coincident stations — and its curvature stencil and both
     * neighbours' stencils are skipped too (`denom <= 0`). Row `i` of `H` is then identically zero.
     * The station is free (`x = 0` on `hi = 0`, `g = 0` is not "pushing out"), so the first
     * reduced factorisation hits a zero pivot, [OffsetQp.solve] breaks before its first iteration,
     * and the **whole route** comes back with offset 0 and `converged = false`.
     *
     * Three identical consecutive fixes are what a GPS logger records during a stop, and
     * [RacingLine.analyze] is called on the raw parsed track by `enhance --racing-line-report`
     * (CLI), `analyzeRacingLine` (JS) and `vcAnalyzeRacingLineJson` (WASI). With two copies the
     * same corner is straightened to the 2.5 m corridor edge, so this is not the corridor's doing.
     */
    @Test
    fun threeCoincidentFixesDoNotDisableTheWholeRacingLine() {
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        val base = bendPath(30.0, PI / 2)
        // Station 130 sits in the arc (the inbound straight is 101 stations long).
        val twoCopies = RacingLine.analyze(withRepeatedPoint(base, 130, 2), options)!!
        assertTrue(twoCopies.converged && maxAbsOffset(twoCopies) > 2.0, "control: two copies are solved")

        val threeCopies = RacingLine.analyze(withRepeatedPoint(base, 130, 3), options)!!
        assertTrue(
            threeCopies.converged,
            "solver gave up after ${threeCopies.newtonIterations} iterations " +
                "(relative gradient ${threeCopies.relativeGradient}) on a route with one GPS pause",
        )
        assertTrue(
            maxAbsOffset(threeCopies) > 2.0,
            "a 90° corner of radius 30 m was left un-straightened: max |offset| = ${maxAbsOffset(threeCopies)} m",
        )
    }
}
