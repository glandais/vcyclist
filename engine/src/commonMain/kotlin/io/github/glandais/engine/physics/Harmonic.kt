package io.github.glandais.engine.physics

/**
 * Harmonic oscillation component for power variation modeling.
 *
 * Applied as : `P' = P + amp × P × cos(freq × t − phase)`.
 *
 * @param freqRadS oscillation frequency in radians per second (typically 1.0–10.0)
 * @param phaseRad phase offset in radians (0 to π)
 * @param amp amplitude factor (dimensionless, typically 0–0.01 → up to 1 % variation)
 */
data class Harmonic(
    val freqRadS: Double,
    val phaseRad: Double,
    val amp: Double,
)
