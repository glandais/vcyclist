package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * Empirical wheel-bearings friction.
 *
 * `P = -v × (91 + 8.7 × v) / 1000` (W).
 *
 * Always negative (resistive). At 10 m/s (~36 km/h) : ~1.78 W ; at 15 m/s : ~3.32 W. Small
 * compared to drag/rolling but non-negligible at low speed. Quadratic in `v` because the
 * speed-dependent term (`8.7 × v`) is itself multiplied by `v`.
 *
 * Side-effect : writes [io.github.glandais.engine.path.GeneratedPath.setPWheelBearings] at
 * `pointIndex`.
 */
object WheelBearingsPowerProvider : PowerProvider {
    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val speed = path.speed(pointIndex)
        val p = -speed * (91.0 + 8.7 * speed) / 1000.0
        path.setPWheelBearings(pointIndex, p)
        return p
    }
}
