package io.github.glandais.engine

import kotlin.math.PI

/**
 * Bike parameters for virtual cycling simulations.
 *
 * @param crr Rolling resistance coefficient (dimensionless)
 * @param inertiaFront Front wheel rotational inertia (kg·m²)
 * @param inertiaRear Rear wheel rotational inertia (kg·m²)
 * @param wheelRadiusM Wheel radius in meters (default 0.7 = 700c with 25mm tire)
 * @param efficiency Drivetrain efficiency (0..1, dimensionless)
 */
data class Bike(
    val crr: Double = EngineConstants.DEFAULT_CRR,
    val inertiaFront: Double = EngineConstants.DEFAULT_INERTIA_FRONT,
    val inertiaRear: Double = EngineConstants.DEFAULT_INERTIA_REAR,
    val wheelRadiusM: Double = EngineConstants.DEFAULT_WHEEL_RADIUS_M,
    val efficiency: Double = EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY,
) {
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
