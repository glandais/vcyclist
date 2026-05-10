package io.github.glandais.engine

/**
 * Options for [io.github.glandais.engine.path.Path] simplification via Douglas-Peucker 3D.
 *
 * @param enabled whether simplification is active (default `true`)
 * @param toleranceM maximum allowed perpendicular distance in meters (default 10)
 * @param zExaggeration elevation exaggeration factor for ECEF conversion (default 3)
 */
data class SimplifyPathOptions(
    val enabled: Boolean = true,
    val toleranceM: Double = 10.0,
    val zExaggeration: Double = 3.0,
)

/**
 * Options controlling the `Enhancer` pipeline (introduced in task 25). Defaults match the TS
 * library : every step is enabled and simplification is on with `tolerance=10`, `zExag=3`.
 *
 * @param fixElevation pull elevation from a tile provider (task 24)
 * @param computeMaxSpeeds compute cornering + braking max speeds (task 20)
 * @param virtualizeTrack run power-based virtualization (task 21) — implies [computeMaxSpeeds]
 * @param computeOnePointPerSecond resample to 1 Hz (task 22)
 * @param simplifyPath Douglas-Peucker simplification options (task 23)
 */
data class EnhanceOptions(
    val fixElevation: Boolean = true,
    val computeMaxSpeeds: Boolean = true,
    val virtualizeTrack: Boolean = true,
    val computeOnePointPerSecond: Boolean = true,
    val simplifyPath: SimplifyPathOptions = SimplifyPathOptions(),
) {
    companion object {
        /** All steps enabled with TS-compatible defaults. */
        val DEFAULT = EnhanceOptions()
    }
}
