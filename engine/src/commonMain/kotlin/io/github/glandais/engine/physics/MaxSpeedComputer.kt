package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Computes maximum-safe speeds on a [Path] from cornering geometry and braking limits.
 *
 * Single backward pass : for each point `i` from last → first,
 * `speedMax[i] = min(corneringLimit(i), brakeFrom(i, i+1, speedMax[i+1]))`.
 *
 * - **Cornering** : `v_max = √(g × radius × tan(θ_lean))`. Radius is estimated by accumulating
 *   bearing changes over a window of ±10 points and dividing total distance by total angle.
 *   Clamped to `[5 m, MAX_RADIUS=200 m]`.
 * - **Braking** : kinematic `v₀ = √(v_f² + 2 × a × d)`. Always satisfiable since `a > 0`.
 * - **Last point** is set to `2 m/s` (sentinel speed at end-of-track).
 *
 * Side-effects on the path : `speedMax`, `speedMaxIncline`, `radius`.
 *
 * Port of `MaxSpeedComputer.ts`.
 */
object MaxSpeedComputer {
    private const val MAX_RADIUS_M = 200.0
    private const val MIN_RADIUS_M = 5.0
    private const val END_SPEED_MS = 2.0
    private const val BEARING_THRESHOLD = 0.001
    private const val DEFAULT_WINDOW = 10

    /** Compute `speedMax` for every point on `course.path`. */
    fun computeMaxSpeeds(course: Course) {
        val path = course.path
        val n = path.size

        for (i in n - 1 downTo 0) {
            if (i == n - 1) {
                path.setSpeedMax(i, END_SPEED_MS)
            } else {
                val cornering = computeCorneringLimit(course, i, DEFAULT_WINDOW)
                val braking = computeBrakingLimit(course, i, i + 1)
                path.setSpeedMax(i, min(cornering, braking))
            }
        }
        path.computeDerivedData()
    }

    private fun computeCorneringLimit(
        course: Course,
        currentIndex: Int,
        window: Int,
    ): Double {
        val path = course.path
        val radius = computeRadiusWindowed(path, currentIndex, window)
        val vMax = sqrt(EngineConstants.G * radius * course.cyclist.tanMaxLeanAngle)
        val result = min(course.cyclist.maxSpeedMS, vMax)
        path.setSpeedMaxIncline(currentIndex, result)
        return result
    }

    private fun computeRadiusWindowed(
        path: Path,
        i: Int,
        k: Int,
    ): Double {
        val mini = max(0, i - k)
        val maxi = min(path.size - 1, i + k)
        val totalBearingChange = normalizeAngleDiff(path.bearing(maxi) - path.bearing(mini))
        val totalDistance = path.distance(maxi) - path.distance(mini)

        if (abs(totalBearingChange) < BEARING_THRESHOLD) {
            path.setRadius(i, MAX_RADIUS_M)
            return MAX_RADIUS_M
        }
        val raw = totalDistance / abs(totalBearingChange)
        val clamped = max(MIN_RADIUS_M, min(MAX_RADIUS_M, raw))
        path.setRadius(i, clamped)
        return clamped
    }

    internal fun normalizeAngleDiff(angleIn: Double): Double {
        var a = angleIn
        while (a > PI) a -= 2.0 * PI
        while (a < -PI) a += 2.0 * PI
        return a
    }

    private fun computeBrakingLimit(
        course: Course,
        currentIndex: Int,
        nextIndex: Int,
    ): Double {
        val path = course.path
        val vf = path.speedMax(nextIndex)
        val a = course.cyclist.maxBrakeMS2
        val d = path.distance(nextIndex) - path.distance(currentIndex)
        return sqrt(vf * vf + 2.0 * a * d)
    }
}
