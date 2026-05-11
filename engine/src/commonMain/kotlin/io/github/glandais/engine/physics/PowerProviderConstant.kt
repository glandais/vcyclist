package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.random.Random

/**
 * Cyclist power provider returning a fixed power throughout the route.
 *
 * The base value is subject to harmonic variation (if [useHarmonics] is true). Speed-based
 * adjustment is dormant — see [CyclistPowerProviderBase].
 */
class PowerProviderConstant(
    val power: Double,
    useHarmonics: Boolean = false,
    random: Random = Random.Default,
) : CyclistPowerProviderBase(useHarmonics, random) {
    override fun optimalPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double = power
}
