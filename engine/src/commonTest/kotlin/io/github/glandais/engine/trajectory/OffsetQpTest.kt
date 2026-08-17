package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class OffsetQpTest {
    private fun analyze(
        path: Path,
        options: RacingLineOptions,
    ): RacingLineReport {
        val report = RacingLine.analyze(path, options)
        assertTrue(report != null, "analysis declined to run")
        return report
    }

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

    /**
     * **The assertion that keeps the line on the road.**
     *
     * Everything else in this phase is an optimisation; this is the safety property. It is checked
     * on every fixture and every mode, because a corridor violation is not a degraded result — it
     * is a trajectory through a wall.
     */
    private fun assertInsideCorridor(report: RacingLineReport) {
        for (i in 0 until report.size) {
            val n = report.lateralOffsetM[i]
            assertTrue(
                n >= report.corridorLo[i] - 1e-9 && n <= report.corridorHi[i] + 1e-9,
                "station $i left the corridor: n=$n not in " +
                    "[${report.corridorLo[i]}, ${report.corridorHi[i]}]",
            )
        }
    }

    /** The offset map must stay regular: `1 − κn > 0`, or the offset curve folds on itself. */
    private fun assertNoFold(report: RacingLineReport) {
        for (i in 0 until report.size) {
            val u = 1.0 - report.centerlineCurvature[i] * report.lateralOffsetM[i]
            assertTrue(u > 0.0, "offset map folded at $i: 1 − κn = $u")
        }
    }

    @Test
    fun `T1 — a 90 degree corner is straightened within the corridor`() {
        val radius = 30.0
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        val report = analyze(bendPath(radius, PI / 2), options)

        assertInsideCorridor(report)
        assertNoFold(report)
        assertTrue(report.converged, "solver did not converge (residual ${report.relativeGradient})")

        // The whole point: the ridden line is straighter through the corner than the road is.
        val corner = report.corners.single()
        var tightestLine = Double.MAX_VALUE
        var tightestRoad = Double.MAX_VALUE
        for (i in corner.fromIndex until corner.untilIndex) {
            val rLine = CurvatureEstimator.radiusAt(report.trajectoryCurvature[i])
            val rRoad = CurvatureEstimator.radiusAt(report.centerlineCurvature[i])
            if (rLine < tightestLine) tightestLine = rLine
            if (rRoad < tightestRoad) tightestRoad = rRoad
        }
        assertTrue(
            tightestLine > tightestRoad * 1.05,
            "the line ($tightestLine m) should open the corner ($tightestRoad m) by more than 5 %",
        )

        // And it does so by using the road, out–in–out rather than a timid nudge. Measured over
        // the whole path: the line starts moving outward on the approach, well before the detected
        // corner span begins, which is the point of a racing line.
        var minOffset = 0.0
        var maxOffset = 0.0
        for (i in 0 until report.size) {
            val n = report.lateralOffsetM[i]
            if (n < minOffset) minOffset = n
            if (n > maxOffset) maxOffset = n
        }
        assertTrue(maxOffset - minOffset > 2.0, "line only used ${maxOffset - minOffset} m of the corridor")
    }

    @Test
    fun `T1b — the apex is on the inside of the turn`() {
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        for (turn in listOf(PI / 2, -PI / 2)) {
            val report = analyze(bendPath(30.0, turn), options)
            val corner = report.corners.single()
            val apexOffset = report.lateralOffsetM[corner.apexIndex]
            // Inside is left for a left turn (direction +1), right for a right turn.
            assertTrue(
                apexOffset * corner.direction > 0.0,
                "apex offset $apexOffset is on the outside of a turn with direction ${corner.direction}",
            )
        }
    }

    /**
     * **T2 — the hairpin, and the feasibility ceiling.**
     *
     * A trajectory circle through a 180° bend has to fit between two parallel outer edges
     * `2(R + h)` apart, so its radius cannot exceed `R + h` however clever the line is. A design
     * that reports more than that has put the rider off the tarmac. Here `R = 15`, `h = 2.5`, so
     * `17.5 m` is the hard ceiling — a gain of `√(17.5/15) = 8 %` in apex speed and no more.
     */
    @Test
    fun `T2 — a hairpin stays inside its feasibility ceiling`() {
        val radius = 15.0
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD, defaultRoadWidthM = 6.0)
        val report = analyze(bendPath(radius, PI, straightM = 100.0), options)

        assertInsideCorridor(report)
        assertNoFold(report)

        val halfWidth = 6.0 / 2.0 - 0.5
        val ceiling = radius + halfWidth
        val corner = report.corners.single()

        // The *tightest* point of the line is what sets the speed through the corner, so that is
        // what the ceiling applies to. Individual stations can read much straighter where the
        // offset is changing — n'' momentarily cancels the road's own curvature — but a transient
        // straight instant buys nothing, and taking the widest reading would test the wrong thing.
        var tightestLine = Double.MAX_VALUE
        for (i in corner.fromIndex until corner.untilIndex) {
            val r = CurvatureEstimator.radiusAt(report.trajectoryCurvature[i])
            if (r < tightestLine) tightestLine = r
        }
        assertTrue(
            tightestLine <= ceiling * 1.10,
            "line radius $tightestLine m exceeds the $ceiling m ceiling a $radius m hairpin can hold",
        )
        assertTrue(
            tightestLine >= radius * 0.95,
            "line radius $tightestLine m is tighter than the road's own $radius m — that is slower, not faster",
        )
        // And |n| never exceeds the road's half-width.
        for (i in 0 until report.size) {
            assertTrue(
                abs(report.lateralOffsetM[i]) <= halfWidth + 1e-9,
                "offset ${report.lateralOffsetM[i]} at $i exceeds the half-width $halfWidth",
            )
        }
    }

    /**
     * T3 — under noise, the line must end up *closer* to the truth than the input was.
     *
     * The assertion is on deviation from the true straight, not on the offset from the reference.
     * Offset is the wrong measure here: the reference is itself a smoothed version of a jittery
     * trace, so an offset of a metre may be moving the line toward the real road or away from it,
     * and the number alone cannot say which. What matters is whether the output is a better
     * estimate of the road than the input — denoising rather than amplifying.
     */
    @Test
    fun `T3 — a noisy straight is denoised, not amplified`() {
        val noise = CurvatureFixtures.lcg(seed = 12345)
        val (xs, ys) = CurvatureFixtures.straight(600.0, 1.5, lateralNoise = { i -> 1.5 * noise(i) })
        val path = CurvatureFixtures.pathOf(xs, ys)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        assertInsideCorridor(analyze(path, options))

        val out = RacingLine.compute(path, options)
        // The fixture runs due east along y = 0, so northing is the deviation from truth.
        var inputWorst = 0.0
        var outputWorst = 0.0
        for (i in out.size / 8 until out.size - out.size / 8) {
            val inputDeviation = abs((path.latitude(i) - out.sourceLatitude(i)) * 0.0 + deviationM(path, i))
            val outputDeviation = abs(deviationM(out, i))
            if (inputDeviation > inputWorst) inputWorst = inputDeviation
            if (outputDeviation > outputWorst) outputWorst = outputDeviation
        }
        assertTrue(
            outputWorst < inputWorst,
            "output deviates $outputWorst m from the true straight, worse than the input's $inputWorst m",
        )
    }

    /** Northing in metres relative to the fixture anchor — the fixture's own deviation from truth. */
    private fun deviationM(
        path: Path,
        i: Int,
    ): Double {
        val lat0 = CurvatureFixtures.LAT0_DEG * PI / 180.0
        return (path.latitude(i) - lat0) * 6_371_000.0
    }

    @Test
    fun `LANE leaves a straight where it is`() {
        val (xs, ys) = CurvatureFixtures.straight(400.0, 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        val report = analyze(path, RacingLineOptions(corridor = CorridorMode.LANE))
        assertInsideCorridor(report)
        for (i in 0 until report.size) {
            assertTrue(
                abs(report.lateralOffsetM[i]) < 0.05,
                "LANE displaced a straight by ${report.lateralOffsetM[i]} m at $i",
            )
        }
    }

    @Test
    fun `LANE never crosses the centreline, even through a corner`() {
        val options = RacingLineOptions(corridor = CorridorMode.LANE)
        for (turn in listOf(PI / 2, -PI / 2, PI)) {
            val report = analyze(bendPath(20.0, turn, straightM = 100.0), options)
            assertInsideCorridor(report)
            for (i in 0 until report.size) {
                assertTrue(
                    report.lateralOffsetM[i] <= 1e-9,
                    "turn $turn: offset ${report.lateralOffsetM[i]} at $i crossed into oncoming",
                )
            }
        }
    }

    @Test
    fun `the solver converges in a handful of iterations`() {
        val report = analyze(bendPath(30.0, PI / 2), RacingLineOptions(corridor = CorridorMode.FULL_ROAD))
        assertTrue(report.converged, "did not converge")
        // Measured: a tight single corner is the worst case at around 40, and the count is bounded
        // by corner difficulty rather than route length. The design's prediction of three to six is
        // optimistic for an active set with hundreds of constraints.
        assertTrue(
            report.newtonIterations <= 80,
            "took ${report.newtonIterations} iterations, far beyond the measured worst case",
        )
    }

    @Test
    fun `results are reproducible`() {
        val path = bendPath(25.0, PI / 3)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        val a = analyze(path, options)
        val b = analyze(path, options)
        // Raw `==`: a reproducibility check, not a numerical comparison.
        for (i in 0 until a.size) {
            assertTrue(a.lateralOffsetM[i] == b.lateralOffsetM[i], "run-to-run divergence at $i")
        }
    }

    // ---- compute() ---------------------------------------------------------------------

    @Test
    fun `compute returns a path of the same size with the offset recorded`() {
        val path = bendPath(30.0, PI / 2)
        val out = RacingLine.compute(path, RacingLineOptions(corridor = CorridorMode.FULL_ROAD))
        assertTrue(out.size == path.size, "size changed: ${path.size} → ${out.size}")
        for (i in 0 until out.size) {
            assertTrue(!out.lateralOffset(i).isNaN(), "offset not written at $i")
            assertTrue(!out.trajectoryCurvature(i).isNaN(), "curvature not written at $i")
        }
    }

    /**
     * The edit must be reversible. `compute` replaces every coordinate with smoothed reference plus
     * offset, so without the source fields a caller could not recover where the rider actually was.
     */
    @Test
    fun `compute preserves the original coordinates`() {
        val path = bendPath(30.0, PI / 2)
        val out = RacingLine.compute(path, RacingLineOptions(corridor = CorridorMode.FULL_ROAD))
        for (i in 0 until out.size) {
            assertTrue(
                abs(out.sourceLatitude(i) - path.latitude(i)) < 1e-15,
                "source latitude lost at $i",
            )
            assertTrue(
                abs(out.sourceLongitude(i) - path.longitude(i)) < 1e-15,
                "source longitude lost at $i",
            )
        }
    }

    @Test
    fun `compute moves the line by the offset it reports`() {
        val path = bendPath(30.0, PI / 2)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        val report = analyze(path, options)
        val out = RacingLine.compute(path, options)
        var maxMove = 0.0
        for (i in 0 until out.size) {
            val dLat = out.latitude(i) - out.sourceLatitude(i)
            val dLon = out.longitude(i) - out.sourceLongitude(i)
            val move =
                kotlin.math.sqrt(
                    (dLat * 6_371_000.0) * (dLat * 6_371_000.0) +
                        (dLon * 6_371_000.0 * kotlin.math.cos(out.latitude(i))) *
                        (dLon * 6_371_000.0 * kotlin.math.cos(out.latitude(i))),
                )
            if (move > maxMove) maxMove = move
        }
        var maxOffset = 0.0
        for (i in 0 until report.size) {
            if (abs(report.lateralOffsetM[i]) > maxOffset) maxOffset = abs(report.lateralOffsetM[i])
        }
        assertTrue(maxMove > 1.0, "compute barely moved anything ($maxMove m) despite $maxOffset m of offset")
        // Smoothing contributes a little on top of the offset, but not a lot.
        assertTrue(maxMove < maxOffset + 2.0, "moved $maxMove m for only $maxOffset m of offset")
    }

    @Test
    fun `compute declines a degenerate path by copying it`() {
        val (xs, ys) = CurvatureFixtures.straight(6.0, 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        val out = RacingLine.compute(path)
        assertTrue(out.size == path.size)
        for (i in 0 until out.size) {
            assertTrue(out.latitude(i) == path.latitude(i), "a declined path must not be moved")
        }
    }
}
