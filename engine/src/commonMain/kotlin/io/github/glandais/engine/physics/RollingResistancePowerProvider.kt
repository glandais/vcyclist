package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.atan
import kotlin.math.cos

/**
 * Rolling resistance power.
 *
 * `P_rolling = -cos(atan(grade)) × m × g × v × Crr` (W, always ≤ 0).
 *
 * The `cos(atan(grade))` term reduces the normal force on inclines/declines and is even
 * in `grade` (symmetric around 0). At `grade = 0` it equals 1 ; at ±10 % grade it is ≈ 0.995.
 *
 * Side-effect : writes
 * [io.github.glandais.engine.path.GeneratedPath.setPRollingResistance] at `pointIndex`.
 */
object RollingResistancePowerProvider : PowerProvider {
    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val m = course.cyclist.massKg
        val crr = course.bike.crr
        val grade = path.grade(pointIndex)
        val speed = path.speed(pointIndex)
        val coef = cos(atan(grade))
        val p = -coef * m * EngineConstants.G * speed * crr
        path.setPRollingResistance(pointIndex, p)
        return p
    }
}
