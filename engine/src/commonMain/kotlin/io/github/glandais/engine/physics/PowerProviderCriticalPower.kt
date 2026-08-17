package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physiology.WPrimeBalanceComputer
import kotlin.random.Random

/**
 * A rider who spends a finite reserve and then settles at Critical Power (ledger R16).
 *
 * Where [PowerProviderConstant] holds a number and [PowerProviderDurability] fades it slowly, this
 * one *reacts*: it carries a running W′ balance and pulls its target back toward CP as the reserve
 * empties, then lets it recover when the effort drops below CP. That is the difference between a
 * controller holding watts and something that behaves like a rider.
 *
 * ## The state is the literature's ; the control law is ours
 *
 * `W′bal` is the W′BAL-ODE model — the same one [WPrimeBalanceComputer] applies after the pipeline,
 * and literally the same [WPrimeBalanceComputer.step] function, so the rider's own bookkeeping and
 * the `wPrimeBalance` field can never drift apart.
 *
 * What the literature does **not** supply is a mapping from a reserve level to a power target: CP
 * and W′ are descriptive state, not a control law. The taper below is a **project-owned heuristic**
 * and must be presented as one:
 *
 * ```
 * w = wPrimeBalanceJ / wPrimeJ                      the fraction remaining
 * shape = 1                        if w >= taperStartFraction
 *       = w / taperStartFraction   otherwise        linear to 0 at empty
 * power = CP + (target − CP) × shape               when target > CP
 *       = target                                    when target <= CP (nothing to ration)
 * ```
 *
 * So the rider holds [powerW] until half the reserve is gone, then bleeds back toward CP.
 *
 * The approach to CP is **asymptotic, not a cliff**, and that falls out of the taper rather than
 * being designed: below the taper point the depletion rate `(target − CP) × shape` is itself
 * proportional to what is left, so the reserve decays exponentially with a time constant of
 * `taperStartFraction × W′ / (target − CP)` — 100 s for a rider asking 100 W over CP with the
 * defaults. Power converges on CP without ever quite arriving, and never goes below it.
 *
 * Recovery is the ODE's: ride below CP and the reserve refills, which raises the ceiling again —
 * a climb after a descent is ridden harder than a climb at the end of an hour above threshold.
 *
 * What it deliberately does *not* do: drop below CP to force recovery, look ahead at the terrain,
 * or modulate with gradient and wind. Those are pacing decisions, not fatigue state — ledger R19,
 * and §4.5 is emphatic that optimal-pacing models are prescriptive rather than descriptive.
 *
 * ## Honest limits
 *
 * - Holding exactly CP forever is not physiological. CP is a *sustainable* power by definition, but
 *   real riders decline over hours ; that decline is [PowerProviderDurability]'s job, and the two
 *   compose (`PowerProviderSlewLimited(PowerProviderCriticalPower(…))` likewise).
 * - The reserve reaching zero has no consequence beyond the taper — no forced stop, no blow-up.
 * - W′BAL-ODE is itself an approximation the field is revising : hydraulic three-component models
 *   outperform work-balance models on intermittent recovery.
 *
 * Stateful, like [PowerProviderDurability] and [PowerProviderSlewLimited], and for the same reason.
 * Keyed on `pointIndex` — re-reading a point is idempotent, a backwards index resets. One instance
 * per simulation, no concurrent use.
 *
 * @param powerW the power the rider *wants* to hold, before rationing
 * @param criticalPowerW sustainable power ; the floor the taper converges to
 * @param wPrimeJ the finite reserve available above [criticalPowerW]
 * @param taperStartFraction reserve fraction below which the target starts bleeding toward CP
 */
class PowerProviderCriticalPower(
    val powerW: Double,
    val criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W,
    val wPrimeJ: Double = EngineConstants.DEFAULT_W_PRIME_J,
    val taperStartFraction: Double = DEFAULT_TAPER_START_FRACTION,
    useHarmonics: Boolean = false,
    random: Random = Random.Default,
) : CyclistPowerProviderBase(useHarmonics, random) {
    init {
        require(powerW > 0.0) { "powerW must be > 0, got $powerW" }
        require(criticalPowerW > 0.0) { "criticalPowerW must be > 0, got $criticalPowerW" }
        require(wPrimeJ > 0.0) { "wPrimeJ must be > 0, got $wPrimeJ" }
        require(taperStartFraction in 0.0..1.0) {
            "taperStartFraction must be in [0, 1], got $taperStartFraction"
        }
    }

    private var lastIndex: Int = -1
    private var lastElapsedS: Double = 0.0
    private var lastPowerW: Double = 0.0

    /** Remaining anaerobic work capacity, in joules. Starts full. */
    var wPrimeBalanceJ: Double = wPrimeJ
        private set

    /** [wPrimeBalanceJ] as a fraction of [wPrimeJ] : `1.0` fresh, `0.0` empty. */
    val reserveFraction: Double get() = wPrimeBalanceJ / wPrimeJ

    override fun optimalPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        if (pointIndex < lastIndex) reset()

        if (pointIndex > lastIndex) {
            val elapsedS = path.elapsed(pointIndex) / 1000.0
            val dtS = elapsedS - lastElapsedS
            if (dtS > 0.0 && dtS.isFinite()) {
                // The interval that just closed was ridden at the previous call's power.
                wPrimeBalanceJ =
                    WPrimeBalanceComputer.step(
                        balance = wPrimeBalanceJ,
                        power = lastPowerW,
                        cp = criticalPowerW,
                        wPrime = wPrimeJ,
                        dtSeconds = dtS,
                    )
            }
            if (elapsedS.isFinite()) lastElapsedS = elapsedS
            lastIndex = pointIndex
        }

        val rationed = ration(powerW)
        lastPowerW = rationed
        return rationed
    }

    /** The taper : full [target] while the reserve is comfortable, CP once it is empty. */
    internal fun ration(target: Double): Double {
        if (target <= criticalPowerW) return target
        val shape =
            if (taperStartFraction <= 0.0) {
                1.0
            } else {
                (reserveFraction / taperStartFraction).coerceIn(0.0, 1.0)
            }
        return criticalPowerW + (target - criticalPowerW) * shape
    }

    /** Refill the reserve — call before reusing the instance on another course. */
    fun reset() {
        lastIndex = -1
        lastElapsedS = 0.0
        lastPowerW = 0.0
        wPrimeBalanceJ = wPrimeJ
    }

    companion object {
        /**
         * Half the reserve. A project-owned number : the literature gives no taper point, and this
         * one is chosen so that the rider spends freely early and arrives at CP as the tank
         * empties rather than falling off a cliff.
         */
        const val DEFAULT_TAPER_START_FRACTION: Double = 0.5
    }
}
