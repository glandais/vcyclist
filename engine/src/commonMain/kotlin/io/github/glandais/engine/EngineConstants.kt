package io.github.glandais.engine

import kotlin.math.PI

/**
 * Physics constants and default cyclist/bike parameters. Ported from the TS
 * `constants.ts`. Values are validated against academic cycling research — do not change
 * lightly without updating the parity tests (task 26).
 */
object EngineConstants {
    // ---- Fundamental physics --------------------------------------------------

    /** Standard gravitational acceleration (m/s²). */
    const val G: Double = 9.8

    /** Below this speed (m/s ~= 0.555 → 2 km/h), some physics calculations become unstable. */
    const val MINIMAL_SPEED: Double = 2.0 / 3.6

    // ---- Bike defaults --------------------------------------------------------

    const val DEFAULT_CRR: Double = 0.004
    const val DEFAULT_INERTIA_FRONT: Double = 0.05
    const val DEFAULT_INERTIA_REAR: Double = 0.07
    const val DEFAULT_WHEEL_RADIUS_M: Double = 0.7
    const val DEFAULT_DRIVETRAIN_EFFICIENCY: Double = 0.976

    // ---- Cyclist defaults -----------------------------------------------------

    const val DEFAULT_CYCLIST_MASS_KG: Double = 80.0
    const val DEFAULT_CYCLIST_POWER_W: Double = 280.0
    const val DEFAULT_MAX_BRAKE_G: Double = 0.6
    const val DEFAULT_MAX_LEAN_ANGLE_DEG: Double = 35.0
    val DEFAULT_MAX_LEAN_ANGLE_RAD: Double = DEFAULT_MAX_LEAN_ANGLE_DEG * PI / 180.0
    const val DEFAULT_MAX_SPEED_KMH: Double = 100.0

    // ---- Aerodynamics ---------------------------------------------------------

    const val DEFAULT_DRAG_COEFFICIENT: Double = 0.7
    const val DEFAULT_FRONTAL_AREA_M2: Double = 0.5
    const val DEFAULT_AIR_DENSITY: Double = 1.225
}
