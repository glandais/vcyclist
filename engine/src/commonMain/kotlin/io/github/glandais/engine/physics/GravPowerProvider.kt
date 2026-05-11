package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.atan
import kotlin.math.sin

/**
 * Gravitational power.
 *
 * `P_gravity = -m × g × v × sin(atan(grade))` (W).
 *
 * Sign convention :
 * - Negative power (resistive) when **climbing** (positive grade — work against gravity).
 * - Positive power (assistive) when **descending** (gravity supplies energy).
 *
 * Side-effect : writes [io.github.glandais.engine.path.GeneratedPath.setPGravity] at
 * `pointIndex`.
 *
 * @see Martin, J.C., et al. (1998). "Validation of a mathematical model for road cycling power."
 */
object GravPowerProvider : PowerProvider {
    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val m = course.cyclist.massKg
        val grade = path.grade(pointIndex)
        val speed = path.speed(pointIndex)
        val p = -m * EngineConstants.G * speed * sin(atan(grade))
        path.setPGravity(pointIndex, p)
        return p
    }
}
