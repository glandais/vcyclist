package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * Aerodynamic coefficient `aeroCoef = (Cd × A × ρ) / 2` (kg/m).
 *
 * Consumed by [AeroPowerProvider] to compute drag `P = -aeroCoef × v³` (or its
 * wind-aware Isvan variant when wind ≠ 0).
 */
fun interface AeroProvider {
    fun aeroCoef(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double
}

/**
 * Combines [Cyclist.cd][io.github.glandais.engine.Cyclist.cd] ×
 * [Cyclist.frontalAreaM2][io.github.glandais.engine.Cyclist.frontalAreaM2] × `ρ` (from
 * [CoursePhysics.rhoProvider]) at each point. Recomputed every call — `ρ` may vary with
 * altitude/temperature (see [RhoProviderEstimate]).
 *
 * Note : [RhoProvider.rho] takes [io.github.glandais.engine.Course] (not [CoursePhysics]),
 * so the inner [CoursePhysics.course] is unwrapped before delegating.
 */
object AeroProviderConstant : AeroProvider {
    override fun aeroCoef(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val rho = course.rhoProvider.rho(course.course, path, pointIndex)
        return (course.cyclist.cd * course.cyclist.frontalAreaM2 * rho) / 2.0
    }
}
