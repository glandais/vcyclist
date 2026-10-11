package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexamples found by modelling the curvature stage ([LocalFrame], [CurvatureEstimator],
 * [PathCurvature], [RacingLine]) in Lean 4 (index arithmetic, executable IEEE-754 models of
 * `unwrap` and of the heading/regression pipeline) and in TLA+ (the [CornerDetector] span state
 * machine, whose invariants all held).
 *
 * Each test asserts the contracted behaviour, so it fails on the current code.
 */
class FormalModelCurvatureTest {
    /** Straight road of [n] points, [spacingM] apart, heading [headingRad] in the 45°N/6°E frame. */
    private fun straight(
        n: Int,
        spacingM: Double,
        headingRad: Double,
    ): Pair<DoubleArray, DoubleArray> = CurvatureFixtures.straight((n - 1) * spacingM, spacingM, headingRad = headingRad)

    private fun withDuplicatedFirstPoint(p: Pair<DoubleArray, DoubleArray>): Pair<DoubleArray, DoubleArray> =
        (doubleArrayOf(p.first[0]) + p.first) to (doubleArrayOf(p.second[0]) + p.second)

    private fun curvature(path: Path): DoubleArray {
        assertTrue(PathCurvature.compute(path), "curvature stage declined to run")
        return DoubleArray(path.size) { path.trajectoryCurvature(it) }
    }

    /**
     * Contract ([CurvatureEstimator.computeHeadings] KDoc): "Coincident neighbours carry no
     * direction; hold the previous heading." A straight road must read as straight: consumers
     * apply no cornering limit beyond a 200 m radius (`MaxSpeedComputer.MAX_RADIUS_M`), so no
     * station of a dead-straight road may report `|κ| > 1/200`.
     *
     * Counterexample (Lean executable model, `CurvModel.lean`): when the *first* two points
     * coincide, station 0 has no previous heading to hold, so it is given `0.0` (due east) instead
     * of the road's direction. On a due-west road that is a spurious π step at `s = 0`; the
     * regression windows that contain station 0 turn it into a slope of ≈ 0.04 m⁻¹ (a ~25 m
     * "corner") over the first metres of a straight road. The last-point duplicate does not have
     * this problem because it holds the previous heading, so the defect is start-only.
     */
    @Test
    fun duplicatedFirstPointDoesNotInventACornerOnAStraightRoad() {
        val road = straight(60, 2.0, PI)

        // Control: same road without the duplicate reads straight.
        val clean = curvature(CurvatureFixtures.pathOf(road.first, road.second))
        assertTrue(clean.all { abs(it) < 1.0 / 200.0 }, "control failed: ${clean.maxOf { abs(it) }}")

        val dup = withDuplicatedFirstPoint(road)
        val k = curvature(CurvatureFixtures.pathOf(dup.first, dup.second))
        val worst = k.indices.maxBy { abs(k[it]) }
        assertTrue(
            abs(k[worst]) < 1.0 / 200.0,
            "straight due-west road with a duplicated first point reports κ=${k[worst]} " +
                "(radius ${1.0 / abs(k[worst])} m) at station $worst",
        )
    }

    /** Same defect on a due-north road: the false east heading at station 0 is a π/2 step. */
    @Test
    fun duplicatedFirstPointOnANorthboundRoadDoesNotInventACorner() {
        val dup = withDuplicatedFirstPoint(straight(60, 2.0, PI / 2))
        val k = curvature(CurvatureFixtures.pathOf(dup.first, dup.second))
        val worst = k.indices.maxBy { abs(k[it]) }
        assertTrue(
            abs(k[worst]) < 1.0 / 200.0,
            "straight northbound road with a duplicated first point reports κ=${k[worst]} " +
                "(radius ${1.0 / abs(k[worst])} m) at station $worst",
        )
    }
}
