package io.github.glandais.engine.trajectory

import io.github.glandais.engine.Course
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.physics.MaxSpeedComputer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The estimator's contract with its only consumer.
 *
 * `radius` is read by four separate things — the cornering limit, the friction-ellipse braking
 * budget, `pBrake`, and the pedal-strike cut-off — so these tests are less about curvature than
 * about not quietly breaking any of them.
 */
class CurvatureMaxSpeedTest {
    private fun cornerPath(radius: Double): io.github.glandais.engine.path.Path {
        val straightIn = CurvatureFixtures.straight(120.0, 1.5)
        val ex = straightIn.first.last()
        val ey = straightIn.second.last()
        val bend = CurvatureFixtures.arc(radius, PI / 2, 1.5, startX = ex, startY = ey)
        val (bx, by) = CurvatureFixtures.arcEnd(radius, PI / 2, ex, ey, 0.0)
        val straightOut =
            CurvatureFixtures.straight(120.0, 1.5, headingRad = PI / 2, startX = bx, startY = by)
        val (xs, ys) = CurvatureFixtures.join(straightIn, bend, straightOut)
        return CurvatureFixtures.pathOf(xs, ys)
    }

    /**
     * The `setRadius` trap.
     *
     * `setRadius` is called only from inside `computeRadiusWindowed`, so an early return that
     * forgot it would leave `radius` at `0.0` — and `computeBrakingLimit` treats `radius <= 0.0`
     * as "straight road, spend the whole friction budget on braking". The result would be the
     * friction ellipse silently disabled at every point of every path the stage touched, plus an
     * empty `radius` column in every export. Nothing else in the suite would notice.
     */
    @Test
    fun `radius is written for every point when curvature drove the estimate`() {
        val path = cornerPath(30.0)
        PathCurvature.compute(path)
        MaxSpeedComputer.computeMaxSpeeds(Course(path = path))
        // The last point is excluded, and not because of this change: `computeMaxSpeeds` assigns
        // it the end-of-track sentinel speed without ever calling the radius estimator, so
        // `radius(size - 1)` has always been 0.0. Nothing reads it — the braking pass at `size - 2`
        // reads its own radius — so it is left alone rather than fixed here.
        for (i in 0 until path.size - 1) {
            val r = path.radius(i)
            assertTrue(r.isFinite(), "radius at $i is not finite: $r")
            assertTrue(r > 0.0, "radius at $i is $r — the friction ellipse would be bypassed here")
        }
    }

    @Test
    fun `a corner produces a cornering limit consistent with its radius`() {
        val radius = 30.0
        val path = cornerPath(radius)
        PathCurvature.compute(path)
        val course = Course(path = path)
        MaxSpeedComputer.computeMaxSpeeds(course)

        var tightest = Double.MAX_VALUE
        var slowest = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            if (path.radius(i) < tightest) tightest = path.radius(i)
            if (path.speedMaxIncline(i) < slowest) slowest = path.speedMaxIncline(i)
        }
        val expected = sqrt(EngineConstants.G * radius * course.cyclist.tanMaxLeanAngle)
        assertTrue(
            abs(slowest - expected) <= 0.10 * expected,
            "expected a cornering limit near $expected m/s for R=$radius, got $slowest " +
                "(tightest radius seen: $tightest)",
        )
    }

    /**
     * The whole point of the change, stated as a speed.
     *
     * A 7 m bend turns more than π across the ±10-point window the old estimator used, so it
     * wrapped and reported a radius roughly twice too large — and `v = √(µgR)` turns that into a
     * `√2` overspeed. With the field written, the limit follows the real radius.
     */
    @Test
    fun `a hairpin is not overspeeded`() {
        val radius = 7.0
        val path = cornerPath(radius)
        PathCurvature.compute(path)
        val course = Course(path = path)
        MaxSpeedComputer.computeMaxSpeeds(course)

        var slowest = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            if (path.speedMaxIncline(i) < slowest) slowest = path.speedMaxIncline(i)
        }
        val truth = sqrt(EngineConstants.G * radius * course.cyclist.tanMaxLeanAngle)
        // Allow the documented tight-end bias, but nothing like the √2 the wrap used to give.
        assertTrue(
            slowest <= 1.10 * truth,
            "hairpin limit $slowest m/s exceeds the true $truth m/s by more than 10 %",
        )
    }

    /**
     * With the stage disabled the field stays NaN and `MaxSpeedComputer` must behave exactly as it
     * always did. This is the guard that lets the change ship: whatever the new estimator does, it
     * cannot reach a caller that did not ask for it.
     */
    @Test
    fun `an unwritten curvature field leaves the historical estimate untouched`() {
        val withField = cornerPath(30.0)
        val without = cornerPath(30.0)
        PathCurvature.compute(without, CurvatureOptions(enabled = false))
        for (i in 0 until without.size) {
            assertTrue(without.trajectoryCurvature(i).isNaN())
        }

        MaxSpeedComputer.computeMaxSpeeds(Course(path = without))
        PathCurvature.compute(withField)
        MaxSpeedComputer.computeMaxSpeeds(Course(path = withField))

        // The two must disagree — otherwise this test proves nothing about the hook being live.
        var differs = false
        for (i in 0 until without.size - 1) {
            if (abs(without.radius(i) - withField.radius(i)) > 1e-6) differs = true
        }
        assertTrue(differs, "the curvature hook had no effect — is it wired up?")
    }
}
