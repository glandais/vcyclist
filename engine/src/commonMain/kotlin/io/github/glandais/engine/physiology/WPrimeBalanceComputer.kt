package io.github.glandais.engine.physiology

import io.github.glandais.engine.WPrimeBalanceOptions
import io.github.glandais.engine.path.Path
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * W′ balance — the remaining anaerobic work capacity of the Critical Power model, integrated
 * along an already-simulated [Path].
 *
 * This is a **read-only pass over the power trace** : it consumes
 * [GeneratedPath.pComputedPower][io.github.glandais.engine.path.GeneratedPath.pComputedPower]
 * and writes [PointField.W_PRIME_BALANCE][io.github.glandais.engine.path.PointField.W_PRIME_BALANCE].
 * It never changes the trajectory, the timing or any power value : every other field is
 * bit-identical whether this pass runs or not. Making the *rider* react to a low W′ is a
 * separate, much larger change (see the ledger's R16).
 *
 * ## The model
 *
 * Differential form (W′BAL-ODE), per `docs/research/02-physiological-modeling.md` :
 *
 * ```
 * P ≥ CP :  dW′bal/dt = −(P − CP)
 * P < CP :  W′bal(t+dt) = W′ − (W′ − W′bal(t)) · exp(−(CP − P)·dt / W′)
 * ```
 *
 * The recovery expression is exact over an interval of constant `P`, which is precisely how it
 * is applied here — one interval per point. No fitted time constant is needed : the apparent τ
 * falls out as `W′ / (CP − P)`.
 *
 * **Why not the integral form**, despite it being the one usually called "validated" : Skiba &
 * Clarke (IJSPP 2021) — Skiba being the author of the integral form — call it *"theoretically
 * untenable"* for continuous severe-intensity work, and restrict it to short bursts above CP.
 * The two forms diverge by ~300 s in predicted time to exhaustion, so this is a fork in the
 * model, not a numerical detail.
 *
 * **Relation to GoldenCheetah.** Its shipped differential recursion is
 * `if (P < CP) W += (CP − P)·(W′ − W)/W′ else W += (CP − P)`, which is the first-order Euler
 * discretisation of the same ODE **at an implicit `dt` of 1 s**. This implementation uses the
 * closed-form exponential and multiplies by the actual `dt(i)` instead, so it stays correct if
 * the path is not on a 1 Hz grid — which happens whenever
 * [computeOnePointPerSecond][io.github.glandais.engine.EnhanceOptions.computeOnePointPerSecond]
 * is off. On a 1 Hz path the two agree to first order.
 *
 * ## What this does *not* model
 *
 * - **Exhaustion has no consequence.** `W′bal` is clamped at 0 and the rider keeps producing the
 *   same power ; nothing feeds back into [io.github.glandais.engine.physics.CyclistPowerProvider].
 *   A trace that flatlines at 0 for ten minutes is telling you the *simulated* rider is doing
 *   something a real one could not, which is exactly the diagnostic value of the field.
 * - **Durability** (the decline of CP itself with accumulated supra-CP work) — ledger R17.
 * - The field is an approximation of a mechanism under active revision : hydraulic
 *   (three-component) models outperform work-balance models on intermittent recovery kinetics.
 */
object WPrimeBalanceComputer {
    /**
     * Integrate W′bal over [path], writing one value per point.
     *
     * Point 0 starts at `W′` (a fresh rider). Points whose `dt` or power is absent or
     * non-finite carry the previous balance forward unchanged rather than poisoning the whole
     * trace with `NaN` — [Path] NaN-initialises nothing by default, but `PointPerSecond` and
     * GPX-sourced traces can both leave gaps.
     */
    fun compute(
        path: Path,
        options: WPrimeBalanceOptions = WPrimeBalanceOptions(),
    ) {
        if (path.size == 0) return
        val cp = options.criticalPowerW
        val wPrime = options.wPrimeJ

        var balance = wPrime
        path.setWPrimeBalance(0, balance)

        for (i in 1 until path.size) {
            val dtSeconds = path.dt(i) / 1000.0
            val power = path.pComputedPower(i)
            if (dtSeconds.isFinite() && dtSeconds > 0.0 && power.isFinite()) {
                balance = step(balance, power, cp, wPrime, dtSeconds)
            }
            path.setWPrimeBalance(i, balance)
        }
    }

    /**
     * One integration step. Exposed (internal) so the test suite can assert the two branches
     * without building a whole [Path].
     *
     * Clamped to `[0, W′]` : depletion cannot go negative (the reserve is empty, not owed), and
     * recovery approaches `W′` asymptotically without reaching it, so the upper clamp only
     * guards against a caller passing `balance > wPrime`.
     */
    internal fun step(
        balance: Double,
        power: Double,
        cp: Double,
        wPrime: Double,
        dtSeconds: Double,
    ): Double {
        val next =
            if (power >= cp) {
                balance - (power - cp) * dtSeconds
            } else {
                wPrime - (wPrime - balance) * exp(-(cp - power) * dtSeconds / wPrime)
            }
        return min(wPrime, max(0.0, next))
    }
}
