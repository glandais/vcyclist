package io.github.glandais.engine

import io.github.glandais.engine.path.Path

/**
 * A cycling course : a [Path] simulated with a given [Cyclist] on a given [Bike].
 *
 * `CoursePhysics` (to be introduced in task 19/20) extends this with physics providers
 * (rho, aero, wind, cyclistPower) once those are ported.
 */
data class Course(
    val path: Path,
    val cyclist: Cyclist = Cyclist.DEFAULT,
    val bike: Bike = Bike.DEFAULT,
)
