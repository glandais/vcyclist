package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path

/**
 * Options for the curvature stage.
 *
 * @property enabled when false the stage is a no-op and `trajectoryCurvature` stays `NaN`, which
 *   makes every consumer fall back to its own estimate. Exists so the previous behaviour can be
 *   restored for A/B measurement and for pinning byte-identical regression baselines — not
 *   because the old estimator is worth keeping.
 * @property geometrySmoothWindowM triangular kernel half-width applied to the projected
 *   coordinates before heading is measured, in metres. Like [curvatureSmoothWindowM] this is
 *   bounded above by the tightest bend worth resolving, not by how much noise there is.
 * @property curvatureWindowsM regression half-widths, ascending, in metres. The narrowest sets
 *   the tightest resolvable bend — a 5 m-radius hairpin is only 7.9 m of arc through 90°, so
 *   nothing wider than ~3 m can see it — and the widest sets the noise floor. Both ends earn
 *   their place; the middle two only smooth the transition between them.
 * @property headingNoiseRad floor on the scale-selection allowance, in radians; the trace's own
 *   measured heading noise is used when it is larger
 * @property curvatureSmoothWindowM triangular kernel half-width applied to the fitted curvature,
 *   in metres. Kept small on purpose: it must stay well below the arclength of the tightest bend
 *   worth resolving, or it attenuates that bend's peak. A 90° corner of 6 m radius is only 9.4 m
 *   long, so an 8 m kernel — the value the design proposed — reports it as a 13 m bend and hands
 *   the rider a 47 % overspeed at the one place on the route that least forgives it.
 */
data class CurvatureOptions(
    val enabled: Boolean = true,
    val geometrySmoothWindowM: Double = 3.0,
    val curvatureWindowsM: List<Double> = listOf(3.0, 6.0, 12.0, 25.0),
    val headingNoiseRad: Double = 0.05,
    val curvatureSmoothWindowM: Double = 3.0,
) {
    init {
        require(geometrySmoothWindowM >= 0.0) { "geometrySmoothWindowM must be >= 0" }
        require(curvatureWindowsM.isNotEmpty()) { "at least one curvature window is required" }
        require(curvatureWindowsM.all { it > 0.0 }) { "curvature windows must be positive" }
        require(curvatureWindowsM.zipWithNext().all { (a, b) -> a < b }) {
            "curvature windows must be strictly ascending: $curvatureWindowsM"
        }
        require(headingNoiseRad > 0.0) { "headingNoiseRad must be positive" }
        require(curvatureSmoothWindowM >= 0.0) { "curvatureSmoothWindowM must be >= 0" }
    }

    companion object {
        val DEFAULT = CurvatureOptions()
    }
}

/**
 * Writes the `trajectoryCurvature` field of a [Path] — signed curvature in m⁻¹, positive turning
 * left — estimated by heading regression in an anchored local planar frame.
 *
 * This is the geometry half of the racing-line work with the lateral offset pinned to zero: it
 * measures the line the rider is already on, and moves nothing. Coordinates, elevation and every
 * other field are left exactly as they were.
 *
 * Its value is in what reads the field. `MaxSpeedComputer` derives `radius` from it, and `radius`
 * in turn feeds the cornering limit, the friction-ellipse braking budget, `pBrake`, and the
 * pedal-strike cut-off — so a curvature estimate that is wrong on tight bends is wrong in four
 * places at once. See [CurvatureEstimator] for the three defects this addresses.
 *
 * Failure is silent and safe: on a path too short to fit a window, or one carrying non-finite
 * coordinates, the field keeps its `NaN` default and consumers fall back to their own estimates.
 */
object PathCurvature {
    /**
     * Estimate curvature for every point of [path] and write it into `trajectoryCurvature`.
     *
     * @return true if the field was written, false if the path could not be projected
     */
    fun compute(
        path: Path,
        options: CurvatureOptions = CurvatureOptions.DEFAULT,
    ): Boolean {
        if (!options.enabled) return false
        val frame = LocalFrame.project(path, options.geometrySmoothWindowM) ?: return false
        CurvatureEstimator.computeHeadings(frame)
        CurvatureEstimator.computeCurvature(
            frame,
            options.curvatureWindowsM.toDoubleArray(),
            options.headingNoiseRad,
            options.curvatureSmoothWindowM,
        )
        for (i in 0 until path.size) {
            path.setTrajectoryCurvature(i, frame.kappa[i])
        }
        return true
    }
}
