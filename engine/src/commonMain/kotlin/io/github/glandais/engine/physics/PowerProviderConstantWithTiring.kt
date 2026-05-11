package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.max
import kotlin.random.Random

/**
 * Cyclist power provider with linear fatigue : power decays from `base × 1.0` at `elapsed=0`
 * down to `base × 0.5` at `elapsed >= durationSeconds`.
 *
 * Fatigue coefficient : `c = max(0.5, 1 - 0.6 × elapsed / duration)`.
 *
 * @param power baseline power in watts
 * @param useHarmonics enable harmonic variation
 * @param durationSeconds total ride duration in seconds (must be > 0)
 * @param random RNG used for harmonic generation
 */
class PowerProviderConstantWithTiring(
    val power: Double,
    useHarmonics: Boolean = false,
    val durationSeconds: Double,
    random: Random = Random.Default,
) : CyclistPowerProviderBase(useHarmonics, random) {
    init {
        require(durationSeconds > 0.0) {
            "durationSeconds must be > 0, got $durationSeconds"
        }
    }

    override fun optimalPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val elapsedS = path.elapsed(pointIndex) / 1000.0
        val c = max(0.5, 1.0 - 0.6 * elapsedS / durationSeconds)
        return power * c
    }
}
