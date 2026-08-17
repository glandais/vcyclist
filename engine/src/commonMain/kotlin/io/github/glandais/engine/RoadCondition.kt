package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan

/**
 * Road surface condition — the single largest lever on descent realism (ledger R9).
 *
 * ## What it actually sets
 *
 * Two rider limits move together, because a wet road takes grip away from cornering *and* from
 * braking. Swapping only the lean angle would model a rider who cannot corner but can still brake
 * like it is dry.
 *
 * | | [DRY] | [WET] |
 * |---|---|---|
 * | µ (cornering) | 0.70 | 0.28 |
 * | equivalent lean angle | 35.0° | 15.6° |
 * | braking | 0.40 g | 0.23 g |
 *
 * ## Where the numbers come from
 *
 * Zignoli (2020) measures the **physical** limits: µ = [EngineConstants.DRY_ROAD_MU] dry and
 * [EngineConstants.WET_ROAD_MU] wet. Real riders leave margin below them, and vcyclist's shipped
 * 35° default *is* that margin: `tan 35° = 0.700`, i.e. **77.8 % of dry grip**.
 *
 * [WET] keeps the rider's behaviour constant and changes only the road, so the same fraction is
 * applied to the wet limit — `0.778 × 0.36 = 0.280`. Braking follows the same logic with one twist:
 * the dry ceiling is **pitch-over** ([EngineConstants.PITCH_OVER_BRAKE_G], a geometric limit the
 * road cannot change) and the default 0.40 g is 63 % of it, while in the wet the binding limit is
 * *grip* — so the wet figure is `0.635 × 0.36 g = 0.23 g`.
 *
 * The resulting cornering-speed ratio is `√(0.70 / 0.28) = 1.58`, which is exactly the
 * "wet cuts cornering speed by 1.58×" figure the research reports — the arithmetic is consistent
 * because both sides are ratios of the same two µ values.
 *
 * ## What it does not model
 *
 * Rain does not change [Bike.crr], air density or the rider's power here. The research is explicit
 * that road conditions shift performance time and peak power but **not** the pacing strategy, so
 * this is deliberately a limits-only knob.
 *
 * Expect a wet ride to cost **1.8–3.4 % over 40 km on a course with technical sections, and
 * 0–0.5 % without** — if a route with no tight corners moves by more than that, something else is
 * binding.
 *
 * @property mu tyre friction coefficient the rider is willing to use
 * @property maxBrakeG braking deceleration the rider is willing to use, in g
 */
enum class RoadCondition(
    val mu: Double,
    val maxBrakeG: Double,
) {
    /** Dry asphalt. Reproduces the shipped [Cyclist] defaults exactly — 35°, 0.4 g. */
    DRY(
        mu = tan(EngineConstants.DEFAULT_MAX_LEAN_ANGLE_RAD),
        maxBrakeG = EngineConstants.DEFAULT_MAX_BRAKE_G,
    ),

    /** Wet asphalt. Same rider margin, 40 % of the grip. */
    WET(
        mu = EngineConstants.RIDER_GRIP_FRACTION * EngineConstants.WET_ROAD_MU,
        maxBrakeG = EngineConstants.RIDER_BRAKE_FRACTION * EngineConstants.WET_ROAD_MU,
    ),
    ;

    /**
     * [mu] expressed as the lean angle [Cyclist] stores, in degrees —
     * `v_max = √(g·R·tan θ)` is `√(µ·g·R)`, so the two are the same parameter.
     */
    val leanAngleDeg: Double get() = atan(mu) * 180.0 / PI
}
