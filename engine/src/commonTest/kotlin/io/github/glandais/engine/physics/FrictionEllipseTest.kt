package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Course
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Braking and cornering share one friction budget (ledger R11). */
class FrictionEllipseTest {
    private val cyclist = Cyclist.DEFAULT
    private val aXMax = cyclist.maxBrakeMS2
    private val aYMax = EngineConstants.G * cyclist.tanMaxLeanAngle

    /** A circular arc of [radiusM], [n] points about 1 m apart, at 45° N. */
    private fun arcPath(
        n: Int,
        radiusM: Double,
    ): Path {
        val p = Path(n)
        val metrePerDegLat = 111_132.0
        val metrePerDegLon = 111_320.0 * cos(45.0 * PI / 180.0)
        val dTheta = 1.0 / radiusM // ~1 m of arc per step
        for (i in 0 until n) {
            val a = i * dTheta
            val northM = radiusM * sin(a)
            val eastM = radiusM * (1.0 - cos(a))
            p.setLatitude(i, (45.0 + northM / metrePerDegLat) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + eastM / metrePerDegLon) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    private fun straightPath(n: Int): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + i * 1.27e-5) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    // ---- 1. The invariant ------------------------------------------------------

    @Test
    fun `no braking point asks for more grip than cornering leaves it`() {
        // The invariant, point by point, against each point's *own* estimated radius — the
        // windowed estimator does not return the ideal arc radius near the ends of a path.
        val path = arcPath(200, 30.0)
        val course = Course(path = path, cyclist = cyclist)
        MaxSpeedComputer.computeMaxSpeeds(course)

        var sawBraking = false
        for (i in 0 until path.size - 1) {
            val d = path.distance(i + 1) - path.distance(i)
            if (d <= 0.0) continue
            val v = path.speedMax(i)
            val vNext = path.speedMax(i + 1)
            if (v <= vNext) continue // not braking here
            sawBraking = true

            val aX = (v * v - vNext * vNext) / (2.0 * d)
            val lateralFraction = ((v * v / path.radius(i)) / aYMax).coerceIn(0.0, 1.0)
            val available = aXMax * sqrt(1.0 - lateralFraction * lateralFraction)
            assertTrue(
                aX <= available + 0.02 * aXMax,
                "point $i brakes at $aX m/s² with only $available available " +
                    "(lateral use ${lateralFraction * 100} %)",
            )
        }
        assertTrue(sawBraking, "fixture never brakes, invariant untested")
    }

    // ---- 2. Straights are unaffected -------------------------------------------

    @Test
    fun `a straight keeps the whole braking budget`() {
        val path = straightPath(300)
        val course = Course(path = path, cyclist = cyclist)
        MaxSpeedComputer.computeMaxSpeeds(course)

        // Walking back from the end-of-track sentinel, each point is the pure kinematic bound.
        for (i in path.size - 40 until path.size - 1) {
            val d = path.distance(i + 1) - path.distance(i)
            val expected = sqrt(path.speedMax(i + 1) * path.speedMax(i + 1) + 2.0 * aXMax * d)
            assertEquals(
                minOf(cyclist.maxSpeedMS, expected),
                path.speedMax(i),
                1e-9,
                "straight-road braking was reduced at $i",
            )
        }
    }

    // ---- 3. In a corner, braking is measurably weaker ---------------------------

    @Test
    fun `braking into a corner is slower than the kinematics alone would allow`() {
        val radius = 20.0
        val path = arcPath(150, radius)
        val course = Course(path = path, cyclist = cyclist)
        MaxSpeedComputer.computeMaxSpeeds(course)

        var sawReduction = false
        for (i in 0 until path.size - 1) {
            val d = path.distance(i + 1) - path.distance(i)
            if (d <= 0.0) continue
            // Each point against its own estimated radius, not the ideal arc radius.
            val ownLimit = sqrt(aYMax * path.radius(i))
            val naive = sqrt(path.speedMax(i + 1) * path.speedMax(i + 1) + 2.0 * aXMax * d)
            val actual = path.speedMax(i)
            assertTrue(actual <= naive + 1e-9, "point $i braked harder than the tyres allow")
            assertTrue(actual <= ownLimit + 1e-9, "point $i exceeded its cornering limit")
            if (actual < naive - 1e-6 && actual < ownLimit - 1e-6) sawReduction = true
        }
        assertTrue(sawReduction, "the ellipse never bound on a 20 m arc")
    }

    @Test
    fun `at the cornering limit there is no braking left`() {
        // Directly on the constraint rather than through the estimator: a point using all its
        // lateral grip has no longitudinal budget at all.
        val radius = 15.0
        val path = arcPath(150, radius)
        val course = Course(path = path, cyclist = cyclist)
        MaxSpeedComputer.computeMaxSpeeds(course)

        for (i in 0 until path.size - 1) {
            val ownLimit = sqrt(aYMax * path.radius(i))
            if (path.speedMax(i) < ownLimit - 1e-6) continue // not at this point's own limit
            val d = path.distance(i + 1) - path.distance(i)
            if (d <= 0.0) continue
            val aX =
                (path.speedMax(i) * path.speedMax(i) - path.speedMax(i + 1) * path.speedMax(i + 1)) /
                    (2.0 * d)
            assertTrue(aX <= 0.02 * aXMax, "point $i is at full lean and still braking at $aX m/s²")
        }
    }

    // ---- 4. What it does to a hairpin ------------------------------------------

    @Test
    fun `the apex limit itself is unchanged`() {
        // 15 m hairpin — Zignoli's fixture. The ellipse changes the *approach*, never the apex:
        // a rider who has finished braking uses the whole budget for lateral grip.
        val path = arcPath(120, 15.0)
        val course = Course(path = path, cyclist = cyclist)
        MaxSpeedComputer.computeMaxSpeeds(course)

        val mid = path.size / 2
        assertEquals(36.5, sqrt(aYMax * 15.0) * 3.6, 0.5, "the dry 15 m apex limit is 36.5 km/h")
        assertEquals(
            sqrt(aYMax * path.radius(mid)),
            path.speedMaxIncline(mid),
            1e-9,
            "mid-arc cornering limit moved",
        )
    }
}
