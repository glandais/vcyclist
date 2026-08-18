package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Abstract base for cyclist power providers : applies optional harmonic variations on top of
 * a subclass-defined optimal power.
 *
 * Speed-based adjustment ([getRealOptimalPower]) is implemented but **not called** at this
 * stage — [powerAt] returns `optimalPower` directly. Will be reactivated in task 19 once
 * `PowerComputer` is available.
 *
 * Harmonics : when [useHarmonics] is true, 20 random harmonics are generated at construction
 * with frequencies 1–10 rad/s, phases 0–π, amplitudes 0–0.01.
 *
 * Side-effects written on the path :
 * - `pCyclistProvidedOptimalPower(i)` : raw subclass output (before harmonics).
 * - `pCyclistProvidedOptimalPowerWithHarmonics(i)` : after harmonic application.
 *
 * The `pCyclistPowerNeeded(i)` slot is filled by negating the total resistive power
 * (`-getNewPower(..., withCyclist=false)`) — that's the cyclist input that would exactly
 * balance the resistive forces. The 4 sub-providers also write their own slots as a
 * side-effect.
 *
 * @param useHarmonics enable harmonic variations
 * @param random RNG used for harmonic generation (injectable for deterministic tests)
 */
abstract class CyclistPowerProviderBase(
    val useHarmonics: Boolean,
    random: Random = Random.Default,
) : CyclistPowerProvider {
    internal val harmonics: List<Harmonic> =
        if (useHarmonics) {
            List(20) {
                Harmonic(
                    freqRadS = 1.0 + random.nextDouble() * 9.0,
                    phaseRad = random.nextDouble() * PI,
                    amp = random.nextDouble() * 0.01,
                )
            }
        } else {
            emptyList()
        }

    /** Baseline power before harmonics. Subclasses choose : constant, tiring, from-data, etc. */
    protected abstract fun optimalPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double

    final override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        var power = optimalPower(course, path, pointIndex)
        path.setPCyclistProvidedOptimalPower(pointIndex, power)

        if (useHarmonics) {
            val x = path.time(pointIndex) / 10_000.0
            for (h in harmonics) {
                power += h.amp * power * cos(h.freqRadS * x - h.phaseRad)
            }
        }
        path.setPCyclistProvidedOptimalPowerWithHarmonics(pointIndex, power)

        // Note: speed-based adjustment (getRealOptimalPower) is intentionally skipped here.
        // Will be re-enabled in task 19 with PowerComputer access for `powerNeeded`.
        val powerNeeded = -PowerComputer.getNewPower(course, path, pointIndex, withCyclist = false)
        path.setPCyclistPowerNeeded(pointIndex, powerNeeded)
        return power
    }

    /**
     * Speed-based adjustment (not currently active) :
     * within ±5 % of optimal → use as-is ; too slow → up to 3× boost (linear) ; too fast →
     * decrease to 0 (linear).
     *
     * Will be called from [powerAt] in task 19 once `PowerComputer.getNewPower` exists.
     */
    protected fun getRealOptimalPower(
        optimalPower: Double,
        powerNeeded: Double,
    ): Double {
        val min = optimalPower * (1.0 - TOLERANCE)
        val max = optimalPower * (1.0 + TOLERANCE)
        return when {
            powerNeeded in min..max -> optimalPower
            powerNeeded < min ->
                optimalPower * MAX_MULTIPLIER -
                    (powerNeeded / min) * optimalPower * (MAX_MULTIPLIER - 1.0)
            else -> {
                val diff = powerNeeded - max
                val coef = (diff / max).coerceIn(0.0, 1.0)
                optimalPower - coef * optimalPower
            }
        }
    }

    companion object {
        private const val TOLERANCE = 0.05
        private const val MAX_MULTIPLIER = 3.0
    }
}
