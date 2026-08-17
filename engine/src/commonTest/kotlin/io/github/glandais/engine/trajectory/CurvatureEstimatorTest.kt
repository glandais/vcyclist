package io.github.glandais.engine.trajectory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the three defects the estimator exists to remove, one test each, plus the noise behaviour.
 *
 * Each assertion is written so it *fails* against the windowed bearing-difference estimate it
 * replaces — a test that both implementations pass would prove nothing about the change.
 */
class CurvatureEstimatorTest {
    private fun curvatureOf(
        xs: DoubleArray,
        ys: DoubleArray,
    ): DoubleArray {
        val path = CurvatureFixtures.pathOf(xs, ys)
        assertTrue(PathCurvature.compute(path), "curvature stage declined to run")
        return DoubleArray(path.size) { path.trajectoryCurvature(it) }
    }

    /** Curvature over the middle half of the path, away from the one-sided end stencils. */
    private fun interior(k: DoubleArray): DoubleArray {
        val from = k.size / 4
        val until = k.size - k.size / 4
        return k.copyOfRange(from, until)
    }

    /**
     * Tolerances are per-radius and deliberately not uniform.
     *
     * From 15 m up the estimator is accurate to a couple of percent. Below that the bend gets
     * short — a 90° turn of radius `R` is only `πR/2` of arc, 7.9 m at `R = 5` — and the smoothing
     * kernels, which cannot shrink indefinitely without reporting noise as corners, take a real
     * bite out of the peak. The bias is toward a *larger* radius, which is the unsafe direction, so
     * it is pinned here rather than averaged away: if it ever grows past these bands that is a
     * regression, and if it shrinks the bands should be tightened deliberately.
     *
     * `MIN_RADIUS_M` clamps consumers at 5 m regardless, which is what makes the tight-end
     * residual tolerable rather than merely tolerated.
     */
    @Test
    fun `recovers the radius of a plain arc across three orders of magnitude`() {
        // 200 m is the radius beyond which consumers stop applying a cornering limit at all, so
        // it is the widest bend worth resolving; 5 m is the tightest they will represent.
        val bands =
            listOf(
                5.0 to 0.15,
                8.0 to 0.12,
                15.0 to 0.04,
                30.0 to 0.03,
                100.0 to 0.03,
                200.0 to 0.03,
            )
        for ((radius, tolerance) in bands) {
            // Spacing is scaled to the bend so even the tightest arc carries enough stations to
            // fit a window; a fixed 1.5 m would leave a 5 m radius with six points.
            val spacing = minOf(1.5, radius * PI / 2.0 / 30.0)
            val (xs, ys) = CurvatureFixtures.arc(radius, PI / 2, spacingM = spacing)
            val k = interior(curvatureOf(xs, ys))
            val mean = k.average()
            val expected = 1.0 / radius
            assertTrue(
                abs(mean - expected) <= tolerance * expected,
                "R=$radius: expected kappa≈$expected ±${tolerance * 100}%, " +
                    "got $mean (R=${1.0 / mean})",
            )
            assertTrue(mean > 0.0, "R=$radius: a left turn must give positive curvature")
        }
    }

    /**
     * A short bend between long straights — much harder than a bare arc, and the case that
     * actually appears on a road.
     *
     * A bare arc lets every window sit entirely inside the bend. Here the wide windows straddle
     * the entry and exit, so the scale selection has to narrow, and both smoothing kernels are
     * fighting a feature only metres long. This configuration is what caught an 8 m curvature
     * kernel reporting a 6 m corner as a 13 m one — a 47 % overspeed at the tightest point of a
     * route, which is the least forgiving place to be optimistic.
     */
    @Test
    fun `resolves a short bend between long straights`() {
        val bands = listOf(6.0 to 0.15, 10.0 to 0.10, 15.0 to 0.05, 30.0 to 0.03)
        for ((radius, tolerance) in bands) {
            val straightIn = CurvatureFixtures.straight(80.0, 1.5)
            val ex = straightIn.first.last()
            val ey = straightIn.second.last()
            val bend = CurvatureFixtures.arc(radius, PI / 2, 1.5, startX = ex, startY = ey)
            val (bx, by) = CurvatureFixtures.arcEnd(radius, PI / 2, ex, ey, 0.0)
            val straightOut =
                CurvatureFixtures.straight(80.0, 1.5, headingRad = PI / 2, startX = bx, startY = by)
            val (xs, ys) = CurvatureFixtures.join(straightIn, bend, straightOut)

            val k = curvatureOf(xs, ys)
            val peak = k.maxOf { abs(it) }
            val recovered = 1.0 / peak
            assertTrue(
                abs(recovered - radius) <= tolerance * radius,
                "R=$radius bend between straights read as R=$recovered",
            )
            // The straights on either side must stay straight — a bend must not bleed into them.
            val onStraight = k.take(30).maxOf { abs(it) }
            assertTrue(onStraight < 1.0 / 150.0, "approach straight showed R=${1.0 / onStraight}")
        }
    }

    @Test
    fun `sign follows the turn direction`() {
        val (lx, ly) = CurvatureFixtures.arc(40.0, PI / 2, spacingM = 1.5)
        val (rx, ry) = CurvatureFixtures.arc(40.0, -PI / 2, spacingM = 1.5)
        assertTrue(interior(curvatureOf(lx, ly)).average() > 0.0, "left turn must be positive")
        assertTrue(interior(curvatureOf(rx, ry)).average() < 0.0, "right turn must be negative")
    }

    /**
     * **Bug 1 — the ±π wrap.**
     *
     * A bearing difference normalised into `(-π, π]` cannot represent more than half a circle.
     * Across a ±10-point window at 1.5 m spacing (~30 m) a bend below ~9.5 m radius turns further
     * than that, wraps, and reports a radius roughly twice too large — an overspeed of `√2` at the
     * tightest point of the route. A hairpin is exactly where that must not happen.
     */
    @Test
    fun `resolves a hairpin tighter than the wrap threshold`() {
        val radius = 7.0
        val (xs, ys) = CurvatureFixtures.arc(radius, PI, spacingM = 1.0)
        val k = interior(curvatureOf(xs, ys))
        val recovered = 1.0 / k.average()
        assertTrue(
            abs(recovered - radius) <= 0.05 * radius,
            "expected R≈$radius through the hairpin, got $recovered",
        )
    }

    /**
     * **Bug 2 — spacing dependence.**
     *
     * A window measured in *points* makes every radius a function of the resampler's choice. The
     * same road sampled twice as densely must not become a different corner.
     */
    @Test
    fun `radius is independent of sampling density`() {
        val radius = 25.0
        val coarse = CurvatureFixtures.arc(radius, PI / 2, spacingM = 2.0)
        val fine = CurvatureFixtures.arc(radius, PI / 2, spacingM = 0.5)
        val kCoarse = interior(curvatureOf(coarse.first, coarse.second)).average()
        val kFine = interior(curvatureOf(fine.first, fine.second)).average()
        assertTrue(
            abs(kCoarse - kFine) <= 0.05 * abs(kFine),
            "2.0 m sampling gave R=${1.0 / kCoarse}, 0.5 m gave R=${1.0 / kFine}",
        )
    }

    /**
     * **Bug 3 — projection shear.**
     *
     * `Path.computeBearing` projects with `x = lon·cos(lat)` using *absolute* longitude, so
     * `∂x/∂lat = −lon·sin(lat)`: at 6°E / 45°N a due-north straight is sheared by 4.2°, and the
     * error grows with longitude. A straight road must read as straight at any longitude.
     */
    @Test
    fun `a due-north straight is straight`() {
        val (xs, ys) = CurvatureFixtures.straight(lengthM = 500.0, spacingM = 1.5, headingRad = PI / 2)
        val k = interior(curvatureOf(xs, ys))
        val worst = k.maxOf { abs(it) }
        assertTrue(worst < 1.0 / 1000.0, "due-north straight showed curvature $worst (R=${1.0 / worst})")
    }

    @Test
    fun `a due-east straight is straight`() {
        val (xs, ys) = CurvatureFixtures.straight(lengthM = 500.0, spacingM = 1.5, headingRad = 0.0)
        val worst = interior(curvatureOf(xs, ys)).maxOf { abs(it) }
        assertTrue(worst < 1.0 / 1000.0, "due-east straight showed curvature $worst")
    }

    /**
     * Under noise the estimator must fail *quiet*, not loud: report no corner rather than a
     * confident tight one. A spurious 20 m radius on a straight would cap the rider at 40 km/h.
     */
    @Test
    fun `white lateral noise on a straight does not fabricate a corner`() {
        val noise = CurvatureFixtures.lcg(seed = 12345)
        val (xs, ys) =
            CurvatureFixtures.straight(
                lengthM = 1000.0,
                spacingM = 1.5,
                lateralNoise = { i -> 1.5 * noise(i) },
            )
        val k = interior(curvatureOf(xs, ys))
        val worst = k.maxOf { abs(it) }
        assertTrue(
            worst < 1.0 / 200.0,
            "1.5 m of jitter produced a corner of R=${1.0 / worst} m",
        )
    }

    @Test
    fun `noise handling is deterministic`() {
        val noise = CurvatureFixtures.lcg(seed = 12345)
        val (xs, ys) =
            CurvatureFixtures.straight(
                lengthM = 400.0,
                spacingM = 1.5,
                lateralNoise = { i -> 1.5 * noise(i) },
            )
        val first = curvatureOf(xs, ys)
        val second = curvatureOf(xs, ys)
        // Raw `==` on doubles is deliberate here: this is a reproducibility check, not a
        // numerical comparison. Two runs of the same code on the same input must agree bit for bit.
        for (i in first.indices) {
            assertTrue(first[i] == second[i], "run-to-run divergence at $i: ${first[i]} vs ${second[i]}")
        }
    }

    @Test
    fun `a real corner survives the noise that a straight does not fabricate`() {
        val noise = CurvatureFixtures.lcg(seed = 999)
        val radius = 30.0
        val (cleanX, cleanY) = CurvatureFixtures.arc(radius, PI / 2, spacingM = 1.5)
        // Displace each station radially — noise that cannot be confused with a real bend.
        val xs = DoubleArray(cleanX.size)
        val ys = DoubleArray(cleanY.size)
        for (i in cleanX.indices) {
            xs[i] = cleanX[i] + 0.8 * noise(2 * i)
            ys[i] = cleanY[i] + 0.8 * noise(2 * i + 1)
        }
        val recovered = 1.0 / interior(curvatureOf(xs, ys)).average()
        assertTrue(
            abs(recovered - radius) <= 0.20 * radius,
            "corner lost under noise: expected R≈$radius, got $recovered",
        )
    }

    @Test
    fun `too-short paths decline rather than guess`() {
        val (xs, ys) = CurvatureFixtures.straight(lengthM = 6.0, spacingM = 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        assertTrue(path.size < LocalFrame.MIN_POINTS)
        assertTrue(!PathCurvature.compute(path), "should decline a path too short to fit a window")
        for (i in 0 until path.size) {
            assertTrue(path.trajectoryCurvature(i).isNaN(), "declined path must leave the field NaN")
        }
    }

    @Test
    fun `disabled options leave the field untouched`() {
        val (xs, ys) = CurvatureFixtures.arc(30.0, PI / 2, spacingM = 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        assertTrue(!PathCurvature.compute(path, CurvatureOptions(enabled = false)))
        for (i in 0 until path.size) {
            assertTrue(path.trajectoryCurvature(i).isNaN())
        }
    }

    @Test
    fun `the stage writes curvature and moves nothing else`() {
        val (xs, ys) = CurvatureFixtures.arc(30.0, PI / 2, spacingM = 1.5)
        val path = CurvatureFixtures.pathOf(xs, ys)
        val lat = DoubleArray(path.size) { path.latitude(it) }
        val lon = DoubleArray(path.size) { path.longitude(it) }
        val ele = DoubleArray(path.size) { path.elevation(it) }
        val dist = DoubleArray(path.size) { path.distance(it) }
        PathCurvature.compute(path)
        for (i in 0 until path.size) {
            assertEquals(lat[i], path.latitude(i), "latitude moved at $i")
            assertEquals(lon[i], path.longitude(i), "longitude moved at $i")
            assertEquals(ele[i], path.elevation(i), "elevation moved at $i")
            assertEquals(dist[i], path.distance(i), "distance moved at $i")
        }
    }

    @Test
    fun `heading is unwrapped into a continuous function`() {
        // A full circle: the raw atan2 heading crosses ±π once, the unwrapped one must not jump.
        val (xs, ys) = CurvatureFixtures.arc(20.0, 2.0 * PI, spacingM = 1.0)
        val path = CurvatureFixtures.pathOf(xs, ys)
        val frame = LocalFrame.project(path, 5.0)!!
        CurvatureEstimator.computeHeadings(frame)
        for (i in 1 until frame.size) {
            val jump = abs(frame.theta[i] - frame.theta[i - 1])
            assertTrue(jump < 0.5, "heading jumped by $jump at $i — the branch cut leaked through")
        }
        // A closed left circle accumulates a full +2π of heading.
        val total = frame.theta[frame.size - 1] - frame.theta[0]
        assertTrue(abs(total - 2.0 * PI) < 0.3, "expected +2π of total turn, got $total")
    }
}
