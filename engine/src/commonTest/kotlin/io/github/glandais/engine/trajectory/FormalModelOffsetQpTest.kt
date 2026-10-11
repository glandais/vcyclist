package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
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

    /**
     * Deterministic route: twelve straight + arc pairs (radius 4–60 m, turn 0.3–3 rad either way)
     * sampled every [spacingM], plus uniform lateral GPS noise of ±[noiseM] (the fixtures' LCG).
     */
    private fun noisyRoute(
        seed: Int,
        noiseM: Double,
        spacingM: Double,
    ): Path {
        val rnd = Random(seed)
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        var x = 0.0
        var y = 0.0
        var h = 0.0
        xs.add(x)
        ys.add(y)
        repeat(12) {
            val straight = rnd.nextDouble(5.0, 80.0)
            var t = 0.0
            while (t + spacingM <= straight) {
                t += spacingM
                x += spacingM * cos(h)
                y += spacingM * sin(h)
                xs.add(x)
                ys.add(y)
            }
            val r = rnd.nextDouble(4.0, 60.0)
            val turn = (if (rnd.nextBoolean()) 1 else -1) * rnd.nextDouble(0.3, 3.0)
            val len = r * abs(turn)
            var a = 0.0
            while (a + spacingM <= len) {
                a += spacingM
                h += spacingM / r * (if (turn > 0) 1 else -1)
                x += spacingM * cos(h)
                y += spacingM * sin(h)
                xs.add(x)
                ys.add(y)
            }
        }
        val noise = CurvatureFixtures.lcg(seed)
        val n = xs.size
        val xa = DoubleArray(n) { xs[it] + noiseM * noise(2 * it) }
        val ya = DoubleArray(n) { ys[it] + noiseM * noise(2 * it + 1) }
        return CurvatureFixtures.pathOf(xa, ya)
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

    /**
     * Contract: the solve only stops short of the residual test at the iteration cap (guide;
     * [OffsetQp] "Why this terminates").
     *
     * Counterexample (TLA+ `StuckOnlyAtOptimum`, violated with exact rational arithmetic for
     * `MAX_BACKTRACKS ≤ 4` on 2×2 Hessians of condition ~100, held at 5): a free station sitting
     * within micrometres of its bound, whose Newton component points out of the box, is clipped by
     * the projection at every trial step; the clipped step only satisfies Armijo for
     * `t ≲ 2·det(H_FF)/(h_ii·h_jj)`, which for the strongly coupled `n''` stencil is below `2⁻²⁰`.
     * After the 20 halvings the line search gives up and the loop `break`s.
     *
     * Seed 33 (FULL_ROAD, 1097 stations, ±1 m GPS noise, 1 m spacing): stations 506/507 sit 6e-8 m
     * and 1.8e-5 m from `lo`; the solve stops after 83 iterations (cap 200) at relative gradient
     * 0.034, 4 cm off the minimiser that the same iteration reaches with 80 halvings. Seed 1 is
     * worse: it stops after 8 iterations at relative gradient 7.3, and the offset it returns is
     * 3.5 m off that minimiser inside a 5 m corridor. Both are cured by more halvings alone, and not
     * by an exact energy difference, so this is the clipping cap rather than rounding.
     */
    @Test
    fun clippedLineSearchDoesNotEndTheSolveBeforeTheIterationCap() {
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        val stalls =
            listOf(33, 1).mapNotNull { seed ->
                val report = RacingLine.analyze(noisyRoute(seed = seed, noiseM = 1.0, spacingM = 1.0), options)!!
                if (report.converged || report.newtonIterations >= options.maxNewtonIterations) {
                    null
                } else {
                    "seed $seed: converged=false after ${report.newtonIterations} of " +
                        "${options.maxNewtonIterations} iterations, relative gradient ${report.relativeGradient}"
                }
            }
        assertTrue(stalls.isEmpty(), stalls.joinToString("; "))
    }

    /**
     * Same contract, different root cause: floating-point cancellation in the Armijo test.
     *
     * [OffsetEnergy.value] returns `c + Σ(½·(Hn)_i·n_i + b_i·n_i)`; the constant and the two sums
     * are each O(1) and nearly cancel, so `E` carries ~1e-9 of rounding noise. Near the optimum the
     * Newton decrement `½|gᵀd|` is ~1e-16, so `trial <= current + c1·step·gᵀd` is decided by
     * rounding and fails at all 20 step sizes, although the step is unclipped (an exact Newton step
     * on a quadratic, which the TLA+ property `UnclippedNewtonAccepted` shows Armijo always accepts
     * in exact arithmetic). The solve stops after 67 iterations at relative gradient 7.9e-8, above
     * the 1e-8 tolerance. Evaluating the change as `gᵀΔ + ½ΔᵀHΔ` instead of `E(x+Δ) − E(x)` (the
     * replica `replica_fixed.py`) converges to 4.9e-10.
     */
    @Test
    fun energyRoundingDoesNotEndTheSolveBeforeTheIterationCap() {
        val options = RacingLineOptions(corridor = CorridorMode.LANE)
        val report = RacingLine.analyze(noisyRoute(seed = 1, noiseM = 1.0, spacingM = 1.5), options)!!
        assertTrue(
            report.converged || report.newtonIterations >= options.maxNewtonIterations,
            "converged=false after ${report.newtonIterations} of ${options.maxNewtonIterations} iterations, " +
                "relative gradient ${report.relativeGradient}",
        )
    }
}
