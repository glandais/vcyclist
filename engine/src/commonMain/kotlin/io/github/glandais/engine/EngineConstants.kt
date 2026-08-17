package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.tan

/**
 * Physics constants and default cyclist/bike parameters. Ported from the TS
 * `constants.ts`. Values are validated against academic cycling research — do not change
 * lightly without updating the parity tests (task 26).
 */
object EngineConstants {
    // ---- Fundamental physics --------------------------------------------------

    /**
     * Standard gravitational acceleration (m/s²) — SI standard gravity `g₀`.
     *
     * Was `9.8` (inherited from gpx2web and the TS reference); the exact value removes a
     * 0.07 % systematic bias on both the gravity and the rolling-resistance terms.
     */
    const val G: Double = 9.80665

    /** Below this speed (m/s ~= 0.555 → 2 km/h), some physics calculations become unstable. */
    const val MINIMAL_SPEED: Double = 2.0 / 3.6

    // ---- Bike defaults --------------------------------------------------------

    const val DEFAULT_CRR: Double = 0.004
    const val DEFAULT_INERTIA_FRONT: Double = 0.05
    const val DEFAULT_INERTIA_REAR: Double = 0.07

    /**
     * Wheel **radius** (m) — a 700c wheel with a 25 mm tyre is ~0.7 m across, so `r = 0.35`.
     * Martin et al. (1998) use `r = 0.311` m for a 20 mm tyre.
     *
     * Was `0.7` until the research review : that is the *diameter*. The bug understated the
     * rotating mass in [Bike.equivalentMass] (`I/r²`) by ~0.73 kg and made
     * [Bike.wheelCircumferenceM] wrong by a factor of 2.
     */
    const val DEFAULT_WHEEL_RADIUS_M: Double = 0.35
    const val DEFAULT_DRIVETRAIN_EFFICIENCY: Double = 0.976

    // ---- Cyclist defaults -----------------------------------------------------

    const val DEFAULT_CYCLIST_MASS_KG: Double = 80.0
    const val DEFAULT_CYCLIST_POWER_W: Double = 280.0

    /**
     * Braking deceleration (g). The pitch-over (stoppie) ceiling is 0.56–0.63 g, but measured
     * riders only use **0.41 ± 0.07 g** in combined braking — ~60–65 % of the limit.
     *
     * Was `0.6` (the physical ceiling). `0.4` models a *believable* rider ; set `maxBrakeG`
     * to 0.6 explicitly for an "expert descender".
     */
    const val DEFAULT_MAX_BRAKE_G: Double = 0.4

    /**
     * Cornering lean angle (°). `MaxSpeedComputer` uses `v_max = √(g·R·tan θ)`, which is
     * `√(µ·g·R)` with **µ ≡ tan θ** — so this parameter *is* a tyre friction coefficient.
     * At 35°, `µ = 0.70`.
     *
     * Zignoli (2020) measures `µ = 0.90` dry (42.0°) and `µ = 0.36` wet (19.8°) for road
     * tyres, so the default sits at 78 % of dry grip : a confident rider leaving margin,
     * consistent with real descenders riding below the physics-optimal line.
     */
    const val DEFAULT_MAX_LEAN_ANGLE_DEG: Double = 35.0

    /**
     * Tyre friction coefficient on **dry** asphalt — the physical limit, not a riding target.
     * Zignoli (2020), sourced to Muller, Uchanski & Hedrick (2003).
     */
    const val DRY_ROAD_MU: Double = 0.90

    /**
     * Tyre friction coefficient on **wet** asphalt — 40 % of [DRY_ROAD_MU], i.e. a 1.58× cut in
     * cornering speed. Same source.
     */
    const val WET_ROAD_MU: Double = 0.36

    /**
     * Pitch-over (stoppie) deceleration ceiling, in g. The binding limit when braking on dry
     * asphalt is the rider going over the bars, not the tyres letting go (§5.5 of the research).
     */
    const val PITCH_OVER_BRAKE_G: Double = 0.63

    /**
     * Fraction of the available grip the default rider uses when cornering : `tan 35° / 0.90`
     * = 0.778. Not a `const val` — `tan` is not a compile-time function.
     */
    val RIDER_GRIP_FRACTION: Double = tan(DEFAULT_MAX_LEAN_ANGLE_RAD) / DRY_ROAD_MU

    /** Fraction of the available deceleration the default rider uses : `0.40 g / 0.63 g` = 0.635. */
    const val RIDER_BRAKE_FRACTION: Double = DEFAULT_MAX_BRAKE_G / PITCH_OVER_BRAKE_G
    const val DEFAULT_MAX_LEAN_ANGLE_RAD: Double = DEFAULT_MAX_LEAN_ANGLE_DEG * PI / 180.0
    const val DEFAULT_MAX_SPEED_KMH: Double = 100.0

    // ---- Physiology (Critical Power model) ------------------------------------

    /**
     * Critical Power (W) — the sustainable power of the 2-parameter CP model `P = W′/t + CP`.
     *
     * GoldenCheetah's shipped fallback when no athlete zone data is available. A default, not
     * a claim about any particular rider : published "average professional" values range from
     * 386 to 440 W.
     */
    const val DEFAULT_CRITICAL_POWER_W: Double = 250.0

    /**
     * W′ (J) — the finite work capacity available above [DEFAULT_CRITICAL_POWER_W].
     *
     * GoldenCheetah's shipped fallback. Typical range 10 000–40 000 J.
     */
    const val DEFAULT_W_PRIME_J: Double = 20_000.0

    // ---- Aerodynamics ---------------------------------------------------------

    const val DEFAULT_DRAG_COEFFICIENT: Double = 0.7
    const val DEFAULT_FRONTAL_AREA_M2: Double = 0.5
    const val DEFAULT_AIR_DENSITY: Double = 1.225
}
