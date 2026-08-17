package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.cos
import kotlin.math.exp

/**
 * Redistributes a rider's power over the terrain: harder uphill and into a headwind, easier
 * downhill and with a tailwind (ledger R19).
 *
 * ## This is a heuristic, and deliberately not an optimiser
 *
 * [`04 §4.5`] is blunt about the alternative: the best optimal-control ITT model in the literature
 * matches real professional riders' velocity for **18–32 % of course duration**, every published
 * time saving is model-internal rather than a measured field gain, and the one real-world trial
 * returned 3 % and was invalidated by a programming error. Those models say what a rider *should*
 * do; vcyclist's job is what a ride *would look like*. So this implements the qualitative rules and
 * nothing more — no dynamic programming, no lookahead, no W′ scheduling.
 *
 * The gain being left on the table is small by the same literature's account: optimal pacing is
 * worth **1–3 %** on realistic courses, and a real professional already rides within **1.2 %** of
 * the optimum.
 *
 * ## The rules it does implement
 *
 * ```
 * raw = 1 + gradientGain × grade + headwindGainPerMS × headwind        clamped to [min, max]
 * ```
 *
 * with an **asymmetric response in distance**, which is the one shape the sources are specific
 * about (Bach et al., via [`04 §4.3`]): *"The increase in power output due to a positive change in
 * height gradient may be slow and dispersed over several hundred metres. A negative change in
 * height gradient can result in a quicker and relatively local drop in power output."* So a rise is
 * smoothed with a distance constant of [rampDistanceM], while a fall applies immediately.
 *
 * **Every magnitude here is ours.** Bach et al. quantify no watts and no metres; [gradientGain],
 * [headwindGainPerMS], the clamps and [rampDistanceM] are project-owned defaults chosen to be
 * plausible and are meant to be tuned. Only the *asymmetry* is sourced.
 *
 * ## The energy budget, and why it is not optional
 *
 * Redistributing power is only redistribution if the total stays put. Measured without a budget,
 * this rule made a rider **10 % faster while spending 11 % more power** — climbs are slow, so the
 * boosted multiplier applies for far more *time* than the descent discount does, and the "gain"
 * was simply a harder ride. A pacing heuristic that quietly raises average power is not a pacing
 * heuristic.
 *
 * So the rule carries a causal energy account: every joule spent above the delegate's target is
 * remembered, and the multiplier is pulled back in proportion to the debt, over a tolerance of
 * [energyBudgetSeconds] of riding. Nothing looks ahead — the rider simply notices it has been
 * overspending. Over any route this holds mean power to within a couple of percent of the
 * unmodulated rider, which is what makes a time comparison mean anything.
 *
 * ## What it deliberately omits
 *
 * - **Anticipation.** The research describes spending W′ *before* a descent because it cannot be
 *   spent usefully during one. Reading Bach's "dispersed over several hundred metres" as
 *   *anticipatory* is an interpretive step beyond the quoted text, and the 2 % / 8 % figures behind
 *   the anticipation result are single-source, single-subject. A rider here reacts to the road it
 *   is on, not to the one coming.
 * - **W′ scheduling.** Choosing to empty the reserve on a particular climb is a pacing plan; the
 *   reserve itself belongs to [PowerProviderCriticalPower], which this composes with.
 *
 * ## Composition and a known inaccuracy
 *
 * A decorator, like [PowerProviderSlewLimited] — wrap the fatigue model, then rate-limit the whole
 * thing: `PowerProviderSlewLimited(PowerProviderTerrainPacing(PowerProviderCriticalPower(…)))`.
 *
 * The delegate books its own state against what *it* returned, not against what this multiplier
 * turns that into, so a W′ reserve depletes as if the rider had ridden the unmodulated target. The
 * modulation is bounded and partly cancels over rolling terrain, so the error is small — but it is
 * systematic on a climb, and the fix is a delivered-power feedback contract that would also settle
 * the same nit in [MuscularPowerProvider]. Recorded rather than papered over.
 *
 * Stateful for the smoother, keyed on `pointIndex`: re-reading a point is idempotent, a backwards
 * index resets. One instance per simulation, no concurrent use.
 *
 * @param delegate the provider whose target is being redistributed
 * @param gradientGain multiplier change per unit of grade (`0.10` grade × `3.0` = +30 %)
 * @param headwindGainPerMS multiplier change per m/s of headwind component
 * @param minMultiplier floor, so a descent never zeroes the rider
 * @param maxMultiplier ceiling, so a wall never asks for a sprint
 * @param rampDistanceM distance constant for *increases* only
 * @param energyBudgetSeconds how many seconds of overspend it takes to pull the multiplier fully
 *   back — the tolerance of the energy account
 */
class PowerProviderTerrainPacing(
    val delegate: CyclistPowerProvider,
    val gradientGain: Double = DEFAULT_GRADIENT_GAIN,
    val headwindGainPerMS: Double = DEFAULT_HEADWIND_GAIN_PER_MS,
    val minMultiplier: Double = DEFAULT_MIN_MULTIPLIER,
    val maxMultiplier: Double = DEFAULT_MAX_MULTIPLIER,
    val rampDistanceM: Double = DEFAULT_RAMP_DISTANCE_M,
    val energyBudgetSeconds: Double = DEFAULT_ENERGY_BUDGET_SECONDS,
) : CyclistPowerProvider {
    init {
        require(gradientGain >= 0.0) { "gradientGain must be >= 0, got $gradientGain" }
        require(headwindGainPerMS >= 0.0) { "headwindGainPerMS must be >= 0, got $headwindGainPerMS" }
        require(minMultiplier > 0.0) { "minMultiplier must be > 0, got $minMultiplier" }
        require(maxMultiplier >= minMultiplier) {
            "maxMultiplier ($maxMultiplier) must be >= minMultiplier ($minMultiplier)"
        }
        require(rampDistanceM > 0.0) { "rampDistanceM must be > 0, got $rampDistanceM" }
        require(energyBudgetSeconds > 0.0) {
            "energyBudgetSeconds must be > 0, got $energyBudgetSeconds"
        }
    }

    private var lastIndex: Int = -1
    private var lastDistanceM: Double = 0.0
    private var lastElapsedS: Double = 0.0
    private var lastDeliveredW: Double = 0.0
    private var lastTargetW: Double = 0.0

    /** The smoothed terrain multiplier at the last point, before the energy correction. */
    var multiplier: Double = 1.0
        private set

    /** Joules spent above the delegate's target so far — negative means the rider is in credit. */
    var energyDebtJ: Double = 0.0
        private set

    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val target = delegate.powerAt(course, path, pointIndex)

        if (pointIndex < lastIndex) reset()
        if (pointIndex > lastIndex) {
            val distanceM = path.distance(pointIndex)
            val dx = distanceM - lastDistanceM
            multiplier = smooth(multiplier, rawMultiplier(path, pointIndex), dx)
            if (distanceM.isFinite()) lastDistanceM = distanceM

            // Settle the account for the interval that just closed, at the previous point's rates.
            val elapsedS = path.elapsed(pointIndex) / 1000.0
            val dtS = elapsedS - lastElapsedS
            if (dtS > 0.0 && dtS.isFinite()) {
                energyDebtJ += (lastDeliveredW - lastTargetW) * dtS
            }
            if (elapsedS.isFinite()) lastElapsedS = elapsedS
            lastIndex = pointIndex
        }

        val delivered = target * multiplier * energyCorrection(target)
        lastTargetW = target
        lastDeliveredW = delivered
        return delivered
    }

    /**
     * Pull-back factor from the running [energyDebtJ] : `1` while the account is square, less as
     * the rider overspends, more while it is in credit. Clamped so the account can never invert
     * the terrain rule outright.
     */
    internal fun energyCorrection(target: Double): Double {
        if (target <= 0.0) return 1.0
        val budgetJ = target * energyBudgetSeconds
        return (1.0 - energyDebtJ / budgetJ).coerceIn(minMultiplier, maxMultiplier)
    }

    /** Instantaneous demand from grade and headwind, before smoothing. */
    internal fun rawMultiplier(
        path: Path,
        pointIndex: Int,
    ): Double {
        val grade = path.grade(pointIndex).let { if (it.isFinite()) it else 0.0 }
        // `windAlpha` is written by AeroPowerProvider earlier in the same power sum; positive
        // `windSpeed × cos(alpha)` is the component the rider is riding into.
        val windSpeed = path.windSpeed(pointIndex).let { if (it.isFinite()) it else 0.0 }
        val alpha = path.windAlpha(pointIndex).let { if (it.isFinite()) it else 0.0 }
        val headwind = windSpeed * cos(alpha)

        val raw = 1.0 + gradientGain * grade + headwindGainPerMS * headwind
        return raw.coerceIn(minMultiplier, maxMultiplier)
    }

    /**
     * Asymmetric in distance : a **fall applies at once**, a **rise is dispersed** over
     * [rampDistanceM]. The exponential form makes the result independent of point spacing, which
     * matters because the pipeline resamples.
     */
    internal fun smooth(
        current: Double,
        raw: Double,
        dx: Double,
    ): Double {
        if (raw <= current) return raw
        if (!dx.isFinite() || dx <= 0.0) return current
        val blend = 1.0 - exp(-dx / rampDistanceM)
        return current + (raw - current) * blend
    }

    /** Forget the smoother and the account — call before reusing the instance on another course. */
    fun reset() {
        lastIndex = -1
        lastDistanceM = 0.0
        lastElapsedS = 0.0
        lastDeliveredW = 0.0
        lastTargetW = 0.0
        multiplier = 1.0
        energyDebtJ = 0.0
    }

    companion object {
        /** `0.10` of grade (a 10 % climb) asks for +30 % power. Ours, not sourced. */
        const val DEFAULT_GRADIENT_GAIN: Double = 3.0

        /** +2 % power per m/s of headwind. Ours, not sourced. */
        const val DEFAULT_HEADWIND_GAIN_PER_MS: Double = 0.02

        /** A descent never drops the rider below half the target. */
        const val DEFAULT_MIN_MULTIPLIER: Double = 0.5

        /** A wall never asks for more than +30 %. */
        const val DEFAULT_MAX_MULTIPLIER: Double = 1.3

        /** "Several hundred metres", the one shape the sources are specific about. */
        const val DEFAULT_RAMP_DISTANCE_M: Double = 300.0

        /**
         * Ten minutes of riding : the rider tolerates that much overspend before the account pulls
         * the target back hard. Short enough to keep mean power honest over a climb, long enough
         * not to fight the terrain rule within a single hill.
         */
        const val DEFAULT_ENERGY_BUDGET_SECONDS: Double = 600.0
    }
}
