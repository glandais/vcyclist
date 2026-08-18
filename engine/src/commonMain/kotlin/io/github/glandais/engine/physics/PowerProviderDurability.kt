package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Cyclist power that fades with **work done above Critical Power**, not with elapsed time
 * (ledger R17).
 *
 * ## Why not elapsed time
 *
 * The provider this replaced (`PowerProviderConstantWithTiring`, removed) faded linearly with
 * `elapsed / durationSeconds`, which makes two rides of the same length equally tiring however
 * hard they were ridden — and its coefficients had no source at all. The durability
 * literature says the opposite, and says it explicitly: a systematic review of 21 studies finds
 * **10–20 % power decline after only 2.5–15 kJ/kg of work above CP**, versus **< 5 % after
 * comparable or larger volumes below it**. The dose is intensity-weighted — and *not* simply
 * kJ-weighted either, which is the other intuitive-but-wrong version.
 *
 * So this provider integrates `∫ max(0, P − CP) dt`, divides by mass, and fades from that.
 *
 * ## How much fade, and why the default is conservative
 *
 * [declinePerKjPerKg] defaults to reaching **10 % at 15 kJ/kg** of supra-CP work — the *bottom* of
 * the published band at the *top* of its work range. That is deliberate. The band describes
 * decrements measured mostly on **short, maximal efforts** (up to −53.8 % at 5 s), while Spragg et
 * al. found **no effect on a 12-minute time trial** after 2 000 kJ. A simulated rider holding a
 * sustained target is the 12-minute case, not the 5-second one, so applying the headline to
 * sustained power would overfit it. The parameter is exposed precisely because this scaling is a
 * judgement call, not a measurement.
 *
 * Two more honest limits: the population behind these numbers is **male professional cyclists**,
 * and `kJ/kg` here divides by [io.github.glandais.engine.Cyclist.massKg], which is *system* mass
 * (rider + bike) — about 12 % more than body mass, so the dose is understated by that much and
 * [declinePerKjPerKg] absorbs it.
 *
 * **Not implemented, and must not be**: the specific figures "CP declines −0.06 W/kg after
 * high-intensity vs −0.007 W/kg after moderate protocols; W′ declined 3.02 kJ after 2 000 kJ" were
 * refuted 0–3 by the research's own verification pass. The *direction* is well supported; those
 * numbers are not.
 *
 * ## Statefulness
 *
 * Unlike the other providers this one accumulates across calls, because the supra-CP dose is a
 * path integral and recomputing it per point would be O(n²). The accumulator is keyed on
 * `pointIndex`: calling twice for the same point counts the interval once, and a `pointIndex` that
 * moves *backwards* is read as a new simulation and resets it. **One instance per simulation, and no
 * concurrent use** — `Enhancer.enhanceCourses` is sequential by design, so the pipeline is safe.
 *
 * @param powerW baseline power in watts, before fade
 * @param criticalPowerW power above which work counts toward the fatigue dose
 * @param declinePerKjPerKg fractional power loss per kJ/kg of supra-CP work
 * @param maxDecline hard floor on the fade, as a fraction (0.20 = "never below 80 % of baseline")
 */
class PowerProviderDurability(
    val powerW: Double,
    val criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W,
    val declinePerKjPerKg: Double = DEFAULT_DECLINE_PER_KJ_PER_KG,
    val maxDecline: Double = DEFAULT_MAX_DECLINE,
    useHarmonics: Boolean = false,
    random: Random = Random.Default,
) : CyclistPowerProviderBase(useHarmonics, random) {
    init {
        require(powerW > 0.0) { "powerW must be > 0, got $powerW" }
        require(criticalPowerW > 0.0) { "criticalPowerW must be > 0, got $criticalPowerW" }
        require(declinePerKjPerKg >= 0.0) { "declinePerKjPerKg must be >= 0, got $declinePerKjPerKg" }
        require(maxDecline in 0.0..1.0) { "maxDecline must be in [0, 1], got $maxDecline" }
    }

    private var lastIndex: Int = -1
    private var lastElapsedS: Double = 0.0
    private var lastPowerW: Double = 0.0

    /** Work done above [criticalPowerW] so far, in joules. Reset when a new simulation starts. */
    var supraCriticalWorkJ: Double = 0.0
        private set

    /** Current fade, as a fraction of [powerW] : `0.0` fresh, [maxDecline] at worst. */
    val decline: Double
        get() = min(maxDecline, declinePerKjPerKg * supraCriticalWorkJ / 1000.0 / massKgForDose)

    private var massKgForDose: Double = EngineConstants.DEFAULT_CYCLIST_MASS_KG

    override fun optimalPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        massKgForDose = course.cyclist.massKg

        if (pointIndex < lastIndex) {
            // Going backwards means a new simulation over the same instance; the dose from the
            // previous one no longer applies. Re-reading the *same* point is not backwards — it
            // simply must not count that interval twice, which the guard below handles.
            reset()
        }
        if (pointIndex > lastIndex) {
            val elapsedS = path.elapsed(pointIndex)
            val dtS = elapsedS - lastElapsedS
            if (dtS > 0.0 && dtS.isFinite()) {
                // The interval that just closed was ridden at the previous call's power.
                supraCriticalWorkJ += max(0.0, lastPowerW - criticalPowerW) * dtS
            }
            if (elapsedS.isFinite()) lastElapsedS = elapsedS
            lastIndex = pointIndex
        }

        val faded = powerW * (1.0 - decline)
        lastPowerW = faded
        return faded
    }

    /** Forget the accumulated dose — call before reusing the instance on another course. */
    fun reset() {
        lastIndex = -1
        lastElapsedS = 0.0
        lastPowerW = 0.0
        supraCriticalWorkJ = 0.0
    }

    companion object {
        /**
         * 10 % decline at 15 kJ/kg of supra-CP work — the bottom of the published 10–20 % band at
         * the top of its 2.5–15 kJ/kg range. See the class KDoc for why the conservative end.
         */
        const val DEFAULT_DECLINE_PER_KJ_PER_KG: Double = 0.10 / 15.0

        /** Floor on the fade : the top of the published band. */
        const val DEFAULT_MAX_DECLINE: Double = 0.20
    }
}
