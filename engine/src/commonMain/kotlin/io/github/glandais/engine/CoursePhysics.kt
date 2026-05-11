package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProvider
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.RhoProvider
import io.github.glandais.engine.physics.RhoProviderDefault
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderNone

/**
 * Course augmented with the physics providers needed to compute resistive powers
 * (aerodynamic, gravity, rolling, bearings) and the cyclist input power.
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
    val cyclistPowerProvider: CyclistPowerProvider = DEFAULT_CYCLIST_POWER_PROVIDER,
) {
    val path: Path get() = course.path
    val cyclist: Cyclist get() = course.cyclist
    val bike: Bike get() = course.bike

    companion object {
        /**
         * Shared singleton default cyclist power provider (constant 280 W, no harmonics).
         * Hoisted to a `val` so two [CoursePhysics] instances built with default arguments
         * compare equal under data-class semantics.
         */
        val DEFAULT_CYCLIST_POWER_PROVIDER: CyclistPowerProvider =
            PowerProviderConstant(EngineConstants.DEFAULT_CYCLIST_POWER_W)
    }
}
