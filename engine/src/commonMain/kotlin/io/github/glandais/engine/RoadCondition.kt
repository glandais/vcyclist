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

    /** The spelling every door accepts. Case is the caller's business, not this enum's. */
    val wireName: String
        get() =
            when (this) {
                DRY -> "dry"
                WET -> "wet"
            }

    companion object {
        /** The value every door falls back to. Never restate it as a literal. */
        val DEFAULT = DRY

        /**
         * Parse the wire spelling, case-insensitively, or `null` when it names no condition.
         *
         * The single catalogue the CLI, the JS façade and the WASI door parse through — the same
         * shape as [io.github.glandais.engine.gpx.GpxPowerSource.fromWire], and for the same
         * reason: a constant added to this enum breaks `wireName` in `commonMain`, on every target
         * at once, instead of leaving one door unable to name it.
         *
         * Before this existed, `RoadCondition` was the one cross-door enum with no wire catalogue,
         * and the three doors spelled *and resolved* it separately. That is how their precedence
         * came to disagree — see [applyTo].
         */
        fun fromWire(value: String): RoadCondition? = entries.firstOrNull { it.wireName == value.lowercase() }

        /** The accepted spellings, for error messages and option validation. */
        val wireNames: List<String> get() = entries.map { it.wireName }
    }
}

/**
 * Apply a road-condition preset to [cyclist] — **the preset is the last word**.
 *
 * A `null` condition changes nothing, which is how a caller keeps raw grip values. A non-null one
 * overwrites both `maxLeanAngleDeg` and `maxBrakeG`, together, always.
 *
 * ## Why the preset wins, and why this function exists
 *
 * The three doors used to resolve this themselves and they did not agree. The CLI wrote
 * `maxBrakeG ?: roadCondition.maxBrakeG` — an explicit flag beat the preset — while JS and WASI
 * wrote `condition?.maxBrakeG ?: maxBrakeG`, so the preset beat an explicit value. The same
 * configuration produced two different cornering physics: `maxLeanAngleDeg = 42` with
 * `roadCondition = "wet"` gave 42° from the CLI and 15.6° from the other two.
 *
 * Preset-wins is the rule, decided rather than inherited. It is the one the demo's UI is built
 * around and says out loud ("a wet road overrides the lean angle and braking sliders below"), it
 * keeps the two limits moving together — which is the entire point of R9 — and it is expressible on
 * every door, whereas explicit-wins is not: `CyclistDto`'s fields are non-nullable, so a JS caller
 * always supplies all six and the façade cannot tell "absent" from "given".
 *
 * The cost is real and is paid on the CLI: `--cyclist-max-angle 42 --road-condition wet` no longer
 * gives 42°. `CyclistMixin` warns when both are passed rather than ignoring one in silence.
 */
fun RoadCondition?.applyTo(cyclist: Cyclist): Cyclist =
    if (this == null) {
        cyclist
    } else {
        cyclist.copy(maxLeanAngleDeg = leanAngleDeg, maxBrakeG = maxBrakeG)
    }
