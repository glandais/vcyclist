package io.github.glandais.engine.trajectory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CornerDetectorTest {
    private fun analyze(
        xs: DoubleArray,
        ys: DoubleArray,
        options: RacingLineOptions = RacingLineOptions.DEFAULT,
    ): RacingLineReport {
        val path = CurvatureFixtures.pathOf(xs, ys)
        val report = RacingLine.analyze(path, options)
        assertTrue(report != null, "analysis declined to run")
        return report
    }

    /** straight → bend → straight, the shape every assertion here is built from. */
    private fun bend(
        radiusM: Double,
        turnRad: Double,
        straightM: Double = 120.0,
        spacingM: Double = 1.5,
    ): Pair<DoubleArray, DoubleArray> {
        val inbound = CurvatureFixtures.straight(straightM, spacingM)
        val ex = inbound.first.last()
        val ey = inbound.second.last()
        val arc = CurvatureFixtures.arc(radiusM, turnRad, spacingM, startX = ex, startY = ey)
        val (bx, by) = CurvatureFixtures.arcEnd(radiusM, turnRad, ex, ey, 0.0)
        val outbound =
            CurvatureFixtures.straight(straightM, spacingM, headingRad = turnRad, startX = bx, startY = by)
        return CurvatureFixtures.join(inbound, arc, outbound)
    }

    @Test
    fun `a single bend is detected once, with the right radius and turn`() {
        val (xs, ys) = bend(radiusM = 40.0, turnRad = PI / 2)
        val report = analyze(xs, ys)
        assertEquals(1, report.corners.size, "expected exactly one corner, got ${report.corners}")
        val c = report.corners[0]
        assertEquals(1, c.direction, "a left turn must report direction +1")
        assertTrue(abs(c.turnRad - PI / 2) < 0.05, "turn was ${c.turnRad}")
        assertTrue(
            abs(c.radiusQ20M - 40.0) <= 0.10 * 40.0,
            "R_q20 was ${c.radiusQ20M}, expected ~40 m",
        )
        assertTrue(c.apexIndex in c.fromIndex until c.untilIndex, "apex outside its own span")
        assertEquals(CornerKind.CORNER, c.kind)
    }

    @Test
    fun `a right bend reports the opposite direction`() {
        val (xs, ys) = bend(radiusM = 40.0, turnRad = -PI / 2)
        val c = analyze(xs, ys).corners.single()
        assertEquals(-1, c.direction)
        assertTrue(c.turnRad < 0.0)
    }

    @Test
    fun `a near-reversal is classified as a hairpin`() {
        val (xs, ys) = bend(radiusM = 12.0, turnRad = PI, straightM = 80.0)
        val c = analyze(xs, ys).corners.single()
        assertEquals(CornerKind.HAIRPIN, c.kind, "180 degrees at 12 m is a hairpin")
        assertTrue(abs(c.turnRad - PI) < 0.10, "turn was ${c.turnRad}")
    }

    @Test
    fun `an open sweeper is classified as gentle`() {
        val (xs, ys) = bend(radiusM = 150.0, turnRad = PI / 3, straightM = 200.0)
        val corners = analyze(xs, ys).corners
        // It may or may not clear the enter threshold at 150 m; if it does, it must be GENTLE.
        for (c in corners) assertEquals(CornerKind.GENTLE, c.kind, "R=150 m is not a real corner")
    }

    /**
     * A chicane must stay two corners.
     *
     * The merge step joins same-sign spans across short gaps, and the failure mode worth guarding
     * is that it merges *opposite*-sign ones — which would report a left-right flick as a single
     * bend with a near-zero net turn, and hand the solver one apex where the road has two.
     */
    @Test
    fun `a chicane is two corners of opposite sign, not one`() {
        val radius = 45.0
        val turn = PI / 4
        val inbound = CurvatureFixtures.straight(120.0, 1.5)
        var x = inbound.first.last()
        var y = inbound.second.last()
        val first = CurvatureFixtures.arc(radius, turn, 1.5, startX = x, startY = y)
        val (ax, ay) = CurvatureFixtures.arcEnd(radius, turn, x, y, 0.0)
        x = ax
        y = ay
        val second = CurvatureFixtures.arc(radius, -turn, 1.5, startX = x, startY = y, startHeadingRad = turn)
        val (bx, by) = CurvatureFixtures.arcEnd(radius, -turn, x, y, turn)
        val outbound = CurvatureFixtures.straight(120.0, 1.5, startX = bx, startY = by)
        val (xs, ys) = CurvatureFixtures.join(inbound, first, second, outbound)

        val corners = analyze(xs, ys).corners
        assertEquals(2, corners.size, "expected two corners, got ${corners.map { it.direction }}")
        assertEquals(1, corners[0].direction)
        assertEquals(-1, corners[1].direction)
        assertTrue(corners[0].untilIndex <= corners[1].fromIndex, "spans must not overlap")
    }

    /**
     * Noise must not open a corner. A spurious corner is worse than a missed one here: it seeds an
     * apex, and later stages will happily displace the rider toward it.
     */
    @Test
    fun `white jitter on a straight opens no corner`() {
        val noise = CurvatureFixtures.lcg(seed = 12345)
        val (xs, ys) =
            CurvatureFixtures.straight(1000.0, 1.5, lateralNoise = { i -> 1.5 * noise(i) })
        val corners = analyze(xs, ys).corners
        assertTrue(corners.isEmpty(), "jitter fabricated ${corners.size} corners: $corners")
    }

    @Test
    fun `a bend split by a brief straightening is merged into one corner`() {
        // Two same-sign arcs 6 m apart — well inside `max(15 m, 3w)`.
        val radius = 30.0
        val turn = PI / 5
        val inbound = CurvatureFixtures.straight(100.0, 1.5)
        var x = inbound.first.last()
        var y = inbound.second.last()
        val a1 = CurvatureFixtures.arc(radius, turn, 1.5, startX = x, startY = y)
        val (p1x, p1y) = CurvatureFixtures.arcEnd(radius, turn, x, y, 0.0)
        val gap = CurvatureFixtures.straight(6.0, 1.5, headingRad = turn, startX = p1x, startY = p1y)
        x = gap.first.last()
        y = gap.second.last()
        val a2 = CurvatureFixtures.arc(radius, turn, 1.5, startX = x, startY = y, startHeadingRad = turn)
        val (p2x, p2y) = CurvatureFixtures.arcEnd(radius, turn, x, y, turn)
        val outbound =
            CurvatureFixtures.straight(100.0, 1.5, headingRad = 2 * turn, startX = p2x, startY = p2y)
        val (xs, ys) = CurvatureFixtures.join(inbound, a1, gap, a2, outbound)

        val corners = analyze(xs, ys).corners
        assertEquals(1, corners.size, "a 6 m straightening should not split a bend: $corners")
        assertTrue(abs(corners[0].turnRad - 2 * turn) < 0.10, "merged turn was ${corners[0].turnRad}")
    }

    @Test
    fun `a path too short to project is declined rather than guessed`() {
        val (xs, ys) = CurvatureFixtures.straight(6.0, 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        assertTrue(RacingLine.analyze(path) == null)
    }
}
