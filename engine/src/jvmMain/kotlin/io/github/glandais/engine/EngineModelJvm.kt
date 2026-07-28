@file:JvmName("EngineModelJvm")

package io.github.glandais.engine

/**
 * Java-callable factories for the physics configuration objects (task g27).
 *
 * [Bike], [Cyclist] and [EnhanceOptions] carry nothing but defaults — that is their point, and it
 * is exactly what makes them unusable from Java, where `new Bike()` does not compile and all five
 * (or six) parameters become mandatory. Their values come from `EngineConstants` and are never
 * duplicated here: the factory repeats the *parameter list*, not the numbers.
 *
 * `copy()` remains Kotlin-only. A Java caller changing one field of an existing instance still has
 * to pass the others; if that turns out to hurt in practice, the answer is a Builder, and it
 * should be written then — not now, on speculation.
 */
@JvmOverloads
fun bike(
    crr: Double = EngineConstants.DEFAULT_CRR,
    inertiaFront: Double = EngineConstants.DEFAULT_INERTIA_FRONT,
    inertiaRear: Double = EngineConstants.DEFAULT_INERTIA_REAR,
    wheelRadiusM: Double = EngineConstants.DEFAULT_WHEEL_RADIUS_M,
    efficiency: Double = EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY,
): Bike = Bike(crr, inertiaFront, inertiaRear, wheelRadiusM, efficiency)

@JvmOverloads
fun cyclist(
    massKg: Double = EngineConstants.DEFAULT_CYCLIST_MASS_KG,
    maxBrakeG: Double = EngineConstants.DEFAULT_MAX_BRAKE_G,
    cd: Double = EngineConstants.DEFAULT_DRAG_COEFFICIENT,
    frontalAreaM2: Double = EngineConstants.DEFAULT_FRONTAL_AREA_M2,
    maxLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG,
    maxSpeedKmH: Double = EngineConstants.DEFAULT_MAX_SPEED_KMH,
): Cyclist = Cyclist(massKg, maxBrakeG, cd, frontalAreaM2, maxLeanAngleDeg, maxSpeedKmH)

@JvmOverloads
fun enhanceOptions(
    fixElevation: Boolean = true,
    computeMaxSpeeds: Boolean = true,
    virtualizeTrack: Boolean = true,
    computeOnePointPerSecond: Boolean = true,
    simplifyPath: SimplifyPathOptions = SimplifyPathOptions(),
): EnhanceOptions = EnhanceOptions(fixElevation, computeMaxSpeeds, virtualizeTrack, computeOnePointPerSecond, simplifyPath)

@JvmOverloads
fun simplifyPathOptions(
    enabled: Boolean = true,
    toleranceM: Double = 10.0,
    zExaggeration: Double = 3.0,
): SimplifyPathOptions = SimplifyPathOptions(enabled, toleranceM, zExaggeration)
