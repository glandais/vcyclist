package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.atan
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

    /**
     * Tyre friction coefficient the rider is willing to use, **the same number** as
     * [tanMaxLeanAngle] : `v_max = √(g·R·tan θ)` is `v_max = √(µ·g·R)` with `µ ≡ tan θ`.
     *
     * Exposed under this name because every source in the literature states the parameter as µ
     * (0.90 dry, 0.36 wet — see [RoadCondition]), while [maxLeanAngleDeg] is the form vcyclist
     * inherited from the original tuning. At the 35° default, `µ = 0.70`.
     */
    val mu: Double get() = tanMaxLeanAngle

    /** Max lean angle in radians. */
    val maxLeanAngleRad: Double get() = maxLeanAngleDeg * PI / 180.0

    /** Max braking deceleration in m/s². */
    val maxBrakeMS2: Double get() = maxBrakeG * EngineConstants.G

    /** Max speed in m/s (km/h → m/s : ÷ 3.6). */
    val maxSpeedMS: Double get() = maxSpeedKmH / 3.6

    /** Aerodynamic drag area `CdA = cd × frontalArea` (m²). */
    val aerodynamicDragArea: Double get() = cd * frontalAreaM2

    /**
     * Copy with the grip-dependent limits — cornering µ **and** braking — set from [condition].
     *
     * `Cyclist().withRoadCondition(RoadCondition.DRY)` returns an equal cyclist : the shipped
     * defaults *are* the dry preset.
     */
    fun withRoadCondition(condition: RoadCondition): Cyclist =
        copy(
            maxLeanAngleDeg = condition.leanAngleDeg,
            maxBrakeG = condition.maxBrakeG,
        )

    /** Copy with the cornering limit set from a friction coefficient rather than an angle. */
    fun withMu(mu: Double): Cyclist {
        require(mu > 0.0) { "mu must be > 0, got $mu" }
        return copy(maxLeanAngleDeg = atan(mu) * 180.0 / PI)
    }

    companion object {
        /** Default cyclist : 80 kg system, recreational/intermediate parameters. */
        val DEFAULT = Cyclist()
    }
}
