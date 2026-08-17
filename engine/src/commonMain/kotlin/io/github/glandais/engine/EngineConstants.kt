package io.github.glandais.engine

import kotlin.math.PI

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
    const val DEFAULT_MAX_LEAN_ANGLE_RAD: Double = DEFAULT_MAX_LEAN_ANGLE_DEG * PI / 180.0
    const val DEFAULT_MAX_SPEED_KMH: Double = 100.0

    // ---- Aerodynamics ---------------------------------------------------------

    const val DEFAULT_DRAG_COEFFICIENT: Double = 0.7
    const val DEFAULT_FRONTAL_AREA_M2: Double = 0.5
    const val DEFAULT_AIR_DENSITY: Double = 1.225
}
