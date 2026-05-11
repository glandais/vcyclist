package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProvider
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProvider
import io.github.glandais.engine.physics.RhoProviderDefault
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderNone

/**
 * Course augmented with the physics providers needed to compute resistive powers
 * (aerodynamic, gravity, rolling, bearings).
 *
 * The 4ᵗʰ provider (`cyclistPowerProvider` — input power from the rider) will be added
 * in task 18 when [CyclistPowerProvider][io.github.glandais.engine.physics] is ported.
 *
 * **Design** : aggregates [Course] rather than inherits from it — Kotlin `data class` does
 * not allow cross-`data class` inheritance. Delegate properties expose [path], [cyclist],
 * and [bike] directly for ergonomic access.
 */
data class CoursePhysics(
    val course: Course,
    val rhoProvider: RhoProvider = RhoProviderDefault,
    val aeroProvider: AeroProvider = AeroProviderConstant,
    val windProvider: WindProvider = WindProviderNone,
) {
    val path: Path get() = course.path
    val cyclist: Cyclist get() = course.cyclist
    val bike: Bike get() = course.bike
}
