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
 * - **Cornering** : `v_max = √(g × radius × tan(θ_lean))` — equivalently `√(µ·g·R)`, since
 *   `µ ≡ tan θ_lean` (see [io.github.glandais.engine.RoadCondition]). Radius comes from the
 *   `trajectoryCurvature` field when [io.github.glandais.engine.trajectory.PathCurvature] has
 *   written it, and otherwise from the historical estimate that accumulates bearing changes over
 *   a window of ±10 points and divides total distance by total angle. Either way it is
 *   clamped to `[5 m, MAX_RADIUS=200 m]`. **Saturating the upper clamp means "no measurable
 *   curvature", so no cornering limit is applied at all** — applying `√(µ·g·200)` there would turn
 *   the clamp into a speed cap on dead-straight road, which is invisible at the dry default
 *   (133 km/h, above `maxSpeedKmH`) but binding in the wet (84 km/h).
 * - **Braking** : kinematic `v₀ = √(v_f² + 2 × a_x × d)`, where `a_x` is what the **friction
 *   ellipse** leaves once cornering has taken its share — a rider at full lean has no braking
 *   left. Solved by fixed-point iteration, since the lateral demand depends on the speed being
 *   solved for. See [computeBrakingLimit].
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

    /** Bisection halvings for the friction ellipse — see [computeBrakingLimit]. */
    private const val ELLIPSE_ITERATIONS = 24

    /** Compute `speedMax` for every point on `course.path`. */
    fun computeMaxSpeeds(course: Course) {
        val path = course.path
        val n = path.size

        for (i in n - 1 downTo 0) {
            if (i == n - 1) {
                path.setSpeedMax(i, END_SPEED_MS)
            } else {
                val cornering = computeCorneringLimit(course, i, DEFAULT_WINDOW)
                path.setSpeedMax(i, computeBrakingLimit(course, i, i + 1, cornering))
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
        val vMax =
            if (radius >= MAX_RADIUS_M) {
                // Straight enough that the windowed estimate saturated : cornering does not bind.
                course.cyclist.maxSpeedMS
            } else {
                sqrt(EngineConstants.G * radius * course.cyclist.tanMaxLeanAngle)
            }
        val result = min(course.cyclist.maxSpeedMS, vMax)
        path.setSpeedMaxIncline(currentIndex, result)
        return result
    }

    /**
     * Turn radius at [i], in metres, clamped to `[MIN_RADIUS_M, MAX_RADIUS_M]`.
     *
     * Prefers the `trajectoryCurvature` field when
     * [io.github.glandais.engine.trajectory.PathCurvature] has written it, and otherwise falls
     * back to the historical windowed bearing-difference estimate below. The field defaults to
     * `NaN` (`PointField.nanDefault`), so the preference is a strict no-op when the curvature
     * stage did not run — a zero default would read as "present, dead straight" and silently
     * suppress every cornering limit on the route.
     *
     * **Writing `radius` is not optional.** [computeBrakingLimit] reads `path.radius(i)` back a
     * few lines later and treats `radius <= 0.0` as "straight road, full braking budget", so an
     * early return that skipped the write would hand every point the whole friction budget and
     * disable the ellipse (ledger R11) across the entire path — while leaving the `radius` field
     * empty in every export.
     */
    private fun computeRadiusWindowed(
        path: Path,
        i: Int,
        k: Int,
    ): Double {
        val kappa = path.trajectoryCurvature(i)
        if (!kappa.isNaN()) {
            val clamped = max(MIN_RADIUS_M, min(MAX_RADIUS_M, 1.0 / max(abs(kappa), 1.0 / MAX_RADIUS_M)))
            path.setRadius(i, clamped)
            return clamped
        }

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

    /**
     * Highest speed at [currentIndex] from which the rider can still be down to `speedMax` at
     * [nextIndex], **given how much grip cornering is already using** (ledger R11).
     *
     * Tyres have one friction budget, and Zignoli's appendix spends it on an ellipse:
     *
     * ```
     * (a_x / a_xmax)² + (a_y / a_ymax)² ≤ 1
     * ```
     *
     * so the deceleration still available is `a_x = a_xmax · √(1 − (a_y/a_ymax)²)`, with
     * `a_y = v²/R` the lateral acceleration the corner is already demanding. At full lean there is
     * **no** braking left — which is the physical statement that you cannot trail-brake at the
     * limit of grip, and the reason a rider brakes *before* the corner rather than in it.
     *
     * `a_xmax` and `a_ymax` are the rider's own limits (`maxBrakeG`, `tan θ_lean`), not the road's
     * physical maxima, so the ellipse inherits the margin those already encode.
     *
     * ## Why it bisects
     *
     * `a_y` depends on the speed being solved for, so the constraint is implicit:
     * `v² ≤ v_next² + 2·a_x(v)·d`. Iterating `v ← √(v_next² + 2·a_x(v)·d)` does *not* work: a
     * higher `v` leaves less braking, so the map is **decreasing** and successive iterates
     * oscillate around the answer rather than converging onto it — landing above it half the time,
     * which puts points outside the ellipse. But `g(v) = v² − v_next² − 2·a_x(v)·d` is strictly
     * increasing (both terms grow with `v`), so plain bisection on `[v_next, corneringLimit]` is
     * exact and always lands on the safe side. [ELLIPSE_ITERATIONS] halvings resolve a 20 m/s
     * bracket to well under a millimetre per second.
     *
     * A saturated radius means "no measurable curvature" (see [computeCorneringLimit]), so those
     * points demand no lateral grip and keep the full braking budget.
     */
    private fun computeBrakingLimit(
        course: Course,
        currentIndex: Int,
        nextIndex: Int,
        corneringLimit: Double,
    ): Double {
        val path = course.path
        val vf = path.speedMax(nextIndex)
        val aXMax = course.cyclist.maxBrakeMS2
        val d = path.distance(nextIndex) - path.distance(currentIndex)
        val radius = path.radius(currentIndex)

        if (radius >= MAX_RADIUS_M || !radius.isFinite() || radius <= 0.0) {
            // Straight: the whole friction budget is available for braking.
            return min(corneringLimit, sqrt(vf * vf + 2.0 * aXMax * d))
        }

        val aYMax = EngineConstants.G * course.cyclist.tanMaxLeanAngle

        /** Speed reachable at this point if it is ridden at [v] — negative means [v] is too fast. */
        fun slack(v: Double): Double {
            val lateralFraction = ((v * v) / radius / aYMax).coerceIn(0.0, 1.0)
            val aX = aXMax * sqrt(1.0 - lateralFraction * lateralFraction)
            return vf * vf + 2.0 * aX * d - v * v
        }

        if (slack(corneringLimit) >= 0.0) return corneringLimit
        var low = min(vf, corneringLimit) // always feasible: no braking needed at all
        var high = corneringLimit
        repeat(ELLIPSE_ITERATIONS) {
            val mid = (low + high) / 2.0
            if (slack(mid) >= 0.0) low = mid else high = mid
        }
        return low
    }
}
