package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorridorTest {
    private fun analyze(
        path: Path,
        options: RacingLineOptions = RacingLineOptions.DEFAULT,
    ): RacingLineReport {
        val report = RacingLine.analyze(path, options)
        assertTrue(report != null, "analysis declined to run")
        return report
    }

    private fun straightPath(
        lengthM: Double = 400.0,
        widthM: Double? = null,
    ): Path {
        val (xs, ys) = CurvatureFixtures.straight(lengthM, 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        if (widthM != null) for (i in 0 until path.size) path.setRoadWidth(i, widthM)
        return path
    }

    /**
     * **The property that makes `LANE` safe to default.**
     *
     * The box is `[−h, 0]`, so zero offset is always feasible: on a straight the solver has no
     * reason to move, and the rider's own recorded line is preserved. A corridor centred on the
     * lane would displace every point of every ride by half a lane before any optimisation
     * happened.
     */
    @Test
    fun `LANE keeps zero offset feasible everywhere`() {
        val report = analyze(straightPath(), RacingLineOptions(corridor = CorridorMode.LANE))
        for (i in 0 until report.size) {
            assertTrue(report.corridorLo[i] <= 1e-12, "lo[$i] = ${report.corridorLo[i]} excludes zero")
            assertTrue(report.corridorHi[i] >= -1e-12, "hi[$i] = ${report.corridorHi[i]} excludes zero")
        }
    }

    @Test
    fun `LANE never allows crossing to the left of the centreline`() {
        val report = analyze(straightPath(), RacingLineOptions(corridor = CorridorMode.LANE))
        for (i in 0 until report.size) {
            assertTrue(report.corridorHi[i] <= 1e-12, "hi[$i] = ${report.corridorHi[i]} crosses into oncoming")
        }
    }

    @Test
    fun `LANE_LEFT mirrors LANE`() {
        val report = analyze(straightPath(), RacingLineOptions(corridor = CorridorMode.LANE_LEFT))
        for (i in 0 until report.size) {
            assertTrue(report.corridorLo[i] >= -1e-12, "lo[$i] = ${report.corridorLo[i]}")
        }
    }

    @Test
    fun `FULL_ROAD is symmetric and wider than LANE`() {
        val path = straightPath()
        val lane = analyze(path, RacingLineOptions(corridor = CorridorMode.LANE))
        val full = analyze(path, RacingLineOptions(corridor = CorridorMode.FULL_ROAD))
        assertTrue(
            full.maxCorridorWidthM > 1.9 * lane.maxCorridorWidthM,
            "FULL_ROAD (${full.maxCorridorWidthM}) should be about twice LANE (${lane.maxCorridorWidthM})",
        )
        val mid = full.size / 2
        assertTrue(abs(full.corridorLo[mid] + full.corridorHi[mid]) < 1e-9, "FULL_ROAD must be symmetric")
    }

    @Test
    fun `a road only as wide as its margins has no corridor at all`() {
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD, edgeMarginM = 0.5)
        val report = analyze(straightPath(widthM = 2.5), options)
        // 2.5 m wide, 0.5 m margin each side ⇒ h = 2.5/2 − 0.5 = 0.75 m, so a 1.5 m interval.
        assertTrue(report.maxCorridorWidthM <= 1.5 + 1e-9, "got ${report.maxCorridorWidthM}")
        val narrow = analyze(straightPath(widthM = 2.5), options.copy(edgeMarginM = 1.25))
        assertTrue(narrow.maxCorridorWidthM < 1e-9, "margins consuming the road must leave nothing")
    }

    @Test
    fun `the corridor is never empty`() {
        for (options in listOf(
            RacingLineOptions(),
            RacingLineOptions(corridor = CorridorMode.FULL_ROAD),
            RacingLineOptions(corridor = CorridorMode.LANE_LEFT),
            RacingLineOptions(corridor = CorridorMode.FULL_ROAD, defaultRoadWidthM = 2.6),
        )) {
            val (xs, ys) = CurvatureFixtures.arc(8.0, PI, spacingM = 1.0)
            val report = analyze(CurvatureFixtures.pathOf(xs, ys), options)
            for (i in 0 until report.size) {
                assertTrue(
                    report.corridorLo[i] <= report.corridorHi[i] + 1e-12,
                    "empty interval at $i: [${report.corridorLo[i]}, ${report.corridorHi[i]}] for $options",
                )
            }
        }
    }

    /**
     * The regularity clamp, on geometry that provokes it.
     *
     * Offsetting a curve scales its arclength by `1 − κn`; at `n = 1/κ` the offset curve folds to a
     * point. On a 3 m-radius kink a 6 m road would otherwise permit 2.5 m of offset, which is 83 %
     * of the way to the fold. The clamp holds it to `0.85/|κ|`.
     */
    @Test
    fun `the regularity clamp binds on a tight kink`() {
        val (xs, ys) = CurvatureFixtures.arc(3.0, PI / 2, spacingM = 0.3)
        val path = CurvatureFixtures.pathOf(xs, ys)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD)
        val report = analyze(path, options)
        for (i in 0 until report.size) {
            val limit = options.regularityFactor / maxOf(abs(report.centerlineCurvature[i]), 1e-9)
            assertTrue(-report.corridorLo[i] <= limit + 1e-9, "lo at $i exceeds the fold limit")
            assertTrue(report.corridorHi[i] <= limit + 1e-9, "hi at $i exceeds the fold limit")
            // And the offset map must stay regular for any offset the corridor permits.
            val worst = maxOf(-report.corridorLo[i], report.corridorHi[i])
            assertTrue(
                1.0 - abs(report.centerlineCurvature[i]) * worst > 0.0,
                "offset map folds at $i",
            )
        }
    }

    /**
     * The self-proximity clamp, on the geometry it exists for.
     *
     * A switchback stack puts two pieces of road metres apart in space and hundreds of metres
     * apart along the path. Nothing in the corridor or the energy knows they are neighbours, so
     * without this clamp a solver would widen one leg straight across the other.
     */
    @Test
    fun `the self-proximity clamp limits offset between adjacent switchback legs`() {
        val separation = 8.0
        val path = switchbackStack(separationM = separation)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD, defaultRoadWidthM = 12.0)
        val report = analyze(path, options)
        // Along the parallel legs the separation is exactly `separation`, so each station may use
        // separation/2 − 0.5 m to either side: an interval of `separation − 1`.
        val allowed = separation - 1.0
        for (i in 10 until 100) {
            val width = report.corridorHi[i] - report.corridorLo[i]
            assertTrue(
                width <= allowed + 1e-6,
                "station $i between legs $separation m apart got $width m of corridor, max $allowed",
            )
        }

        // Around the hairpin itself the legs bow apart slightly, so the exact bound is looser —
        // but the clamp must still bind everywhere. Without it these stations would get the whole
        // 11 m the road claims, which is the failure this test exists to catch: an along-path-only
        // test exempts precisely the stations beside the hairpin, where the legs are closest.
        for (i in 0 until report.size) {
            val width = report.corridorHi[i] - report.corridorLo[i]
            assertTrue(width <= separation, "station $i got $width m, more than the $separation m separation")
        }
    }

    @Test
    fun `legs far apart are not clamped by proximity`() {
        val path = switchbackStack(separationM = 40.0)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD, defaultRoadWidthM = 6.0)
        val report = analyze(path, options)
        // The road, not the proximity clamp, should be the binding constraint here.
        assertTrue(
            report.maxCorridorWidthM > 4.0,
            "well-separated legs should keep their road width, got ${report.maxCorridorWidthM}",
        )
    }

    /**
     * Two parallel legs joined by a hairpin, `separationM` apart — an out-and-back seen from
     * above, which is the same adjacency hazard as a switchback stack.
     */
    private fun switchbackStack(separationM: Double): Path {
        val radius = separationM / 2.0
        val out = CurvatureFixtures.straight(200.0, 1.5)
        val ex = out.first.last()
        val ey = out.second.last()
        val turn = CurvatureFixtures.arc(radius, PI, 1.5, startX = ex, startY = ey)
        val (bx, by) = CurvatureFixtures.arcEnd(radius, PI, ex, ey, 0.0)
        val back = CurvatureFixtures.straight(200.0, 1.5, headingRad = PI, startX = bx, startY = by)
        val (xs, ys) = CurvatureFixtures.join(out, turn, back)
        return CurvatureFixtures.pathOf(xs, ys)
    }

    @Test
    fun `both ends are pinned in adjacent pairs`() {
        val report = analyze(straightPath())
        // Two at each end, not one: the trajectory energy couples i±2, so a bandwidth-2 system
        // needs two consecutive fixed nodes to decouple across them.
        for (i in intArrayOf(0, 1, report.size - 2, report.size - 1)) {
            assertEquals(0.0, report.corridorLo[i], "station $i must be pinned")
            assertEquals(0.0, report.corridorHi[i], "station $i must be pinned")
        }
    }

    @Test
    fun `a long straight is pinned at its midpoint`() {
        val report = analyze(straightPath(lengthM = 400.0))
        var interiorPins = 0
        for (i in 4 until report.size - 4) {
            if (report.corridorLo[i] == 0.0 && report.corridorHi[i] == 0.0) interiorPins++
        }
        assertTrue(interiorPins >= 2, "a 400 m straight should carry an interior pin pair")
    }

    @Test
    fun `a per-point road width from the path is honoured over the default`() {
        val path = straightPath(widthM = 10.0)
        val options = RacingLineOptions(corridor = CorridorMode.FULL_ROAD, defaultRoadWidthM = 6.0)
        val report = analyze(path, options)
        // 10 m road, 0.5 m margins ⇒ h = 4.5 m, versus 2.5 m from the default.
        assertTrue(
            report.maxCorridorWidthM > 8.0,
            "the file's 10 m width should widen the corridor, got ${report.maxCorridorWidthM}",
        )
    }

    @Test
    fun `a width step is smoothed before it becomes a constraint`() {
        val (xs, ys) = CurvatureFixtures.straight(400.0, 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        val half = path.size / 2
        for (i in 0 until path.size) path.setRoadWidth(i, if (i < half) 4.0 else 10.0)
        val report = analyze(path, RacingLineOptions(corridor = CorridorMode.FULL_ROAD))

        // Across the step the half-width must ramp rather than jump: a step in the constraint set
        // is answered by a kink in the trajectory.
        var worstJump = 0.0
        for (i in 1 until report.size) {
            val jump = abs(report.roadHalfWidthM[i] - report.roadHalfWidthM[i - 1])
            if (jump > worstJump) worstJump = jump
        }
        assertTrue(worstJump < 0.5, "width steps by $worstJump m between adjacent stations — not smoothed")
        assertTrue(report.roadHalfWidthM[10] < report.roadHalfWidthM[report.size - 10], "the step is still there")
    }
}
