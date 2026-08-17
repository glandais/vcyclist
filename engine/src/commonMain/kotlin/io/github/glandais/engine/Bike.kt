package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.tan

/**
 * Bike parameters for virtual cycling simulations.
 *
 * @param crr Rolling resistance coefficient (dimensionless)
 * @param inertiaFront Front wheel rotational inertia (kg·m²)
 * @param inertiaRear Rear wheel rotational inertia (kg·m²)
 * @param wheelRadiusM Wheel radius in meters (default 0.35 = 700c with 25mm tire, i.e. a 0.7 m diameter)
 * @param efficiency Drivetrain efficiency (0..1, dimensionless)
 * @param maxPedalingLeanAngleDeg Lean angle (°) past which the rider stops pedalling for pedal
 *   clearance — see [EngineConstants.DEFAULT_MAX_PEDALING_LEAN_ANGLE_DEG]. 90 disables it.
 */
data class Bike(
    val crr: Double = EngineConstants.DEFAULT_CRR,
    val inertiaFront: Double = EngineConstants.DEFAULT_INERTIA_FRONT,
    val inertiaRear: Double = EngineConstants.DEFAULT_INERTIA_REAR,
    val wheelRadiusM: Double = EngineConstants.DEFAULT_WHEEL_RADIUS_M,
    val efficiency: Double = EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY,
    val maxPedalingLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_PEDALING_LEAN_ANGLE_DEG,
) {
    /**
     * `tan` of [maxPedalingLeanAngleDeg] — the form the check actually uses, since the lean angle
     * of a point is `atan(v² / (g·R))` and comparing tangents avoids an `atan` per point.
     *
     * `>= 90°` disables the cut-off : `tan` is not finite there, so no lean can exceed it.
     */
    val tanMaxPedalingLeanAngle: Double
        get() = if (maxPedalingLeanAngleDeg >= 90.0) Double.POSITIVE_INFINITY else tan(maxPedalingLeanAngleDeg * PI / 180.0)

    /** Sum of front and rear wheel rotational inertias (kg·m²). */
    val totalInertia: Double get() = inertiaFront + inertiaRear

    /** Wheel diameter (m). */
    val wheelDiameterM: Double get() = 2.0 * wheelRadiusM

    /** Wheel circumference `2πr` (m). */
    val wheelCircumferenceM: Double get() = 2.0 * PI * wheelRadiusM

    /** Equivalent linear mass from rotating wheels : `I_total / r²` (kg). */
    val equivalentMass: Double get() = totalInertia / (wheelRadiusM * wheelRadiusM)

    /** `1 - efficiency` — fraction of input power lost in the drivetrain. */
    val powerLossFactor: Double get() = 1.0 - efficiency

    /** Power delivered to the rear wheel for a given input power : `inputPower × efficiency`. */
    fun wheelPower(inputPower: Double): Double = inputPower * efficiency

    /** Rolling resistance force `F = crr × N` for a given normal force `N` (N). */
    fun rollingResistanceForce(normalForce: Double): Double = crr * normalForce

    companion object {
        /** Default bike : modern road bike with high-performance tires (Crr=0.004). */
        val DEFAULT = Bike()
    }
}
