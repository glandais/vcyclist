@file:JvmName("EngineModelJvm")

package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProvider
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.CyclistPowerSpec
import io.github.glandais.engine.physics.PowerModel
import io.github.glandais.engine.physics.RhoProvider
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.trajectory.CorridorMode
import io.github.glandais.engine.trajectory.CurvatureOptions
import io.github.glandais.engine.trajectory.RacingLineOptions

/**
 * Java-callable factories for the physics configuration objects (task g27).
 *
 * [Bike], [Cyclist] and [EnhanceOptions] carry nothing but defaults — that is their point, and it
 * is exactly what makes them unusable from Java, where `new Bike()` does not compile and all
 * parameters become mandatory. Their values come from `EngineConstants` and are never duplicated
 * here: the factory repeats the *parameter list*, not the numbers.
 *
 * `copy()` remains Kotlin-only. A Java caller changing one field of an existing instance still has
 * to pass the others; if that turns out to hurt in practice, the answer is a Builder, and it
 * should be written then — not now, on speculation.
 *
 * ## Keep these in step with the data classes
 *
 * A parameter appended to one of those classes does **not** break this file — the calls below are
 * positional and simply keep taking the new field's default. That resilience is deliberate (see
 * the note on [EnhanceOptions]'s declaration order) and it is also how this file silently fell
 * four fields behind: `wPrimeBalance`, `curvature` and `racingLine` on [EnhanceOptions], and
 * `maxPedalingLeanAngleDeg` on [Bike], were all unreachable from Java although every other door
 * exposed them. `EngineModelJvmCoverageTest` now fails the build when a factory falls behind its
 * data class, which is the only thing that catches this.
 */
@JvmOverloads
fun bike(
    crr: Double = EngineConstants.DEFAULT_CRR,
    inertiaFront: Double = EngineConstants.DEFAULT_INERTIA_FRONT,
    inertiaRear: Double = EngineConstants.DEFAULT_INERTIA_REAR,
    wheelRadiusM: Double = EngineConstants.DEFAULT_WHEEL_RADIUS_M,
    efficiency: Double = EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY,
    maxPedalingLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_PEDALING_LEAN_ANGLE_DEG,
): Bike = Bike(crr, inertiaFront, inertiaRear, wheelRadiusM, efficiency, maxPedalingLeanAngleDeg)

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
    wPrimeBalance: WPrimeBalanceOptions = WPrimeBalanceOptions(),
    curvature: CurvatureOptions = CurvatureOptions(),
    racingLine: RacingLineOptions = RacingLineOptions(),
): EnhanceOptions =
    EnhanceOptions(
        fixElevation,
        computeMaxSpeeds,
        virtualizeTrack,
        computeOnePointPerSecond,
        simplifyPath,
        wPrimeBalance,
        curvature,
        racingLine,
    )

@JvmOverloads
fun simplifyPathOptions(
    enabled: Boolean = true,
    toleranceM: Double = SimplifyPathOptions().toleranceM,
    zExaggeration: Double = SimplifyPathOptions().zExaggeration,
): SimplifyPathOptions = SimplifyPathOptions(enabled, toleranceM, zExaggeration)

@JvmOverloads
fun wPrimeBalanceOptions(
    enabled: Boolean = true,
    criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W,
    wPrimeJ: Double = EngineConstants.DEFAULT_W_PRIME_J,
): WPrimeBalanceOptions = WPrimeBalanceOptions(enabled, criticalPowerW, wPrimeJ)

@JvmOverloads
fun curvatureOptions(
    enabled: Boolean = true,
    geometrySmoothWindowM: Double = CurvatureOptions.DEFAULT.geometrySmoothWindowM,
    curvatureWindowsM: List<Double> = CurvatureOptions.DEFAULT.curvatureWindowsM,
    headingNoiseRad: Double = CurvatureOptions.DEFAULT.headingNoiseRad,
    curvatureSmoothWindowM: Double = CurvatureOptions.DEFAULT.curvatureSmoothWindowM,
): CurvatureOptions = CurvatureOptions(enabled, geometrySmoothWindowM, curvatureWindowsM, headingNoiseRad, curvatureSmoothWindowM)

/**
 * The three racing-line knobs the CLI, JS and WASI doors expose — deliberately **not** all 23 of
 * [RacingLineOptions].
 *
 * The other twenty were tuned by measurement and are not part of any door's public surface (see
 * `docs/guides/racing-line.md`). A Java caller who needs one of them builds this and reaches for
 * Kotlin, rather than being handed a 23-argument positional constructor here.
 */
@JvmOverloads
fun racingLineOptions(
    enabled: Boolean = false,
    corridor: CorridorMode = RacingLineOptions.DEFAULT.corridor,
    defaultRoadWidthM: Double = RacingLineOptions.DEFAULT.defaultRoadWidthM,
): RacingLineOptions =
    RacingLineOptions.DEFAULT.copy(
        enabled = enabled,
        corridor = corridor,
        defaultRoadWidthM = defaultRoadWidthM,
    )

// ── The classes the three wire doors parse into ──────────────────────────────────────────────

/**
 * The power configuration, as a value — the class the CLI, the JS façade and the WASI module all
 * parse into. It had no factory here at all, which made it the widest hole in the Java door: seven
 * positional arguments, with three of the defaults being `EngineConstants` names the caller had to
 * know and spell, because `copy()` is Kotlin-only.
 *
 * The model catalogue, the `base -> pacing -> slew` composition order and the defaults all live on
 * [CyclistPowerSpec] itself; this repeats the parameter list, never the values.
 */
@JvmOverloads
fun cyclistPowerSpec(
    model: PowerModel = CyclistPowerSpec().model,
    powerW: Double = CyclistPowerSpec().powerW,
    criticalPowerW: Double = CyclistPowerSpec().criticalPowerW,
    wPrimeJ: Double = CyclistPowerSpec().wPrimeJ,
    useHarmonics: Boolean = CyclistPowerSpec().useHarmonics,
    pacing: Boolean = CyclistPowerSpec().pacing,
    maxSlewWPerS: Double = CyclistPowerSpec().maxSlewWPerS,
): CyclistPowerSpec = CyclistPowerSpec(model, powerW, criticalPowerW, wPrimeJ, useHarmonics, pacing, maxSlewWPerS)

/** A [Course] — a path plus who is riding it on what. */
@JvmOverloads
fun course(
    path: Path,
    cyclist: Cyclist = Cyclist.DEFAULT,
    bike: Bike = Bike.DEFAULT,
): Course = Course(path, cyclist, bike)

/**
 * A [CoursePhysics], which is what [EnhancerJvm.enhanceCourseBlocking] actually takes.
 *
 * Without this the only reachable instance from Java was `Enhancer.getDefaultCourse(path)`, which
 * takes a `Path` and nothing else — so a Java caller could configure a rider and then had no way
 * to hand it to the enhancer.
 *
 * **The parameter order is not [CoursePhysics]'s.** `@JvmOverloads` truncates from the right, so
 * the order decides what a Java caller can actually omit: putting `cyclistPowerProvider` last, as
 * the data class does, would have made the one provider anybody overrides reachable only by also
 * naming the three nobody touches. Ordered by how often each is set instead. `EngineModelJvmCoverageTest`
 * checks arity, not order, precisely so a facade can make this call.
 */
@JvmOverloads
fun coursePhysics(
    course: Course,
    cyclistPowerProvider: CyclistPowerProvider = CoursePhysics.DEFAULT_CYCLIST_POWER_PROVIDER,
    windProvider: WindProvider = CoursePhysics(course).windProvider,
    rhoProvider: RhoProvider = CoursePhysics(course).rhoProvider,
    aeroProvider: AeroProvider = CoursePhysics(course).aeroProvider,
): CoursePhysics = CoursePhysics(course, rhoProvider, aeroProvider, windProvider, cyclistPowerProvider)
