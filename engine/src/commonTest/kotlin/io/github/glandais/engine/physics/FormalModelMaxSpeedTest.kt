package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexamples found by the executable Lean model of [MaxSpeedComputer] (property P8).
 *
 * The windowed radius fallback (used when `trajectoryCurvature` is NaN, i.e. the curvature stage
 * is disabled) is documented to "accumulate bearing changes over a window of ±10 points". It only
 * diffs the two window endpoints and wraps that to (-π, π], so a turn of more than 180° inside the
 * 20-step window reads as a *gentler* one — or as dead straight.
 */
class FormalModelMaxSpeedTest {
    /** A circle of radius [r] sampled every [step] m, bearings wrapped to [0, 2π) as GPS bearings are. */
    private fun circle(
        r: Double,
        n: Int = 41,
        step: Double = 2.0,
    ): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setDistance(i, i * step)
            p.setBearing(i, (i * step / r) % (2.0 * PI))
        }
        return p
    }

    @Test
    fun windowed_radius_of_8m_circle_is_not_read_as_31m() {
        val path = circle(8.0)
        MaxSpeedComputer.computeMaxSpeeds(Course(path, cyclist = Cyclist()))
        val r = path.radius(20)
        assertTrue(
            r <= 8.0 * 1.25,
            "8 m circle (2 m steps): windowed radius at middle point should be ~8 m, got $r m",
        )
    }

    @Test
    fun tight_6_5m_circle_still_gets_a_cornering_limit() {
        val cyclist = Cyclist()
        val path = circle(6.5)
        MaxSpeedComputer.computeMaxSpeeds(Course(path, cyclist = cyclist))
        val physical = sqrt(EngineConstants.G * 6.5 * cyclist.tanMaxLeanAngle)
        val incline = path.speedMaxIncline(20)
        assertTrue(
            incline <= physical * 1.25,
            "6.5 m circle: cornering limit should be ~$physical m/s, got $incline m/s " +
                "(radius read as ${path.radius(20)} m, i.e. dead straight)",
        )
    }
}
