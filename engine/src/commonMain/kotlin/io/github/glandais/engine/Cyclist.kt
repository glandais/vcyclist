package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.tan

/**
 * Cyclist parameters for virtual cycling simulations.
 *
 * @param massKg Total system mass (cyclist + bike) in kilograms
 * @param maxBrakeG Maximum braking deceleration in g-units (multiplied by [EngineConstants.G] to get m/s²)
 * @param cd Aerodynamic drag coefficient (dimensionless)
 * @param frontalAreaM2 Frontal area for aerodynamic calculations (m²)
 * @param maxLeanAngleDeg Maximum lean angle in degrees for cornering
 * @param maxSpeedKmH Maximum speed capability in km/h
 */
data class Cyclist(
    val massKg: Double = EngineConstants.DEFAULT_CYCLIST_MASS_KG,
    val maxBrakeG: Double = EngineConstants.DEFAULT_MAX_BRAKE_G,
    val cd: Double = EngineConstants.DEFAULT_DRAG_COEFFICIENT,
    val frontalAreaM2: Double = EngineConstants.DEFAULT_FRONTAL_AREA_M2,
    val maxLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG,
    val maxSpeedKmH: Double = EngineConstants.DEFAULT_MAX_SPEED_KMH,
) {
    /** Tangent of the max lean angle — used in cornering physics (`v_max² = g·R·tan(θ)`). */
    val tanMaxLeanAngle: Double get() = tan(maxLeanAngleDeg * PI / 180.0)

    /** Max lean angle in radians. */
    val maxLeanAngleRad: Double get() = maxLeanAngleDeg * PI / 180.0

    /** Max braking deceleration in m/s². */
    val maxBrakeMS2: Double get() = maxBrakeG * EngineConstants.G

    /** Max speed in m/s (km/h → m/s : ÷ 3.6). */
    val maxSpeedMS: Double get() = maxSpeedKmH / 3.6

    /** Aerodynamic drag area `CdA = cd × frontalArea` (m²). */
    val aerodynamicDragArea: Double get() = cd * frontalAreaM2

    companion object {
        /** Default cyclist : 80 kg system, recreational/intermediate parameters. */
        val DEFAULT = Cyclist()
    }
}
