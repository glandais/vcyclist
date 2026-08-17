package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path

/**
 * What the racing-line analysis found.
 *
 * A class rather than a `data class`: it holds `DoubleArray` members, whose `equals` is identity,
 * so a generated `equals`/`hashCode` would be quietly wrong.
 *
 * @property size number of stations, equal to the analysed path's size
 * @property corners detected bends, in path order
 * @property centerlineCurvature signed curvature of the reference line, m⁻¹, positive left
 * @property corridorLo lower bound of the feasible lateral offset, metres, positive left
 * @property corridorHi upper bound of the same
 * @property roadHalfWidthM the half-width the corridor was derived from, before the clamps. A
 *   collapsed corridor is far easier to diagnose when you can see whether the road collapsed it or
 *   a clamp did.
 */
class RacingLineReport(
    val size: Int,
    val corners: List<CornerSpan>,
    val centerlineCurvature: DoubleArray,
    val corridorLo: DoubleArray,
    val corridorHi: DoubleArray,
    val roadHalfWidthM: DoubleArray,
) {
    /**
     * Widest the corridor gets anywhere, `hi − lo`, in metres.
     *
     * The full interval rather than a half-width: `LANE` is one-sided, so a half-width would
     * report it as the same size as a `FULL_ROAD` corridor twice as large.
     */
    val maxCorridorWidthM: Double
        get() {
            var m = 0.0
            for (i in 0 until size) {
                val w = corridorHi[i] - corridorLo[i]
                if (w > m) m = w
            }
            return m
        }
}

/**
 * Racing-line analysis: where the corners are, and how much room the line has.
 *
 * This computes **no trajectory**. It is the geometry the solver will consume, exposed on its own
 * so it can be tested and inspected before anything moves a coordinate — and so that the corridor,
 * which is the part with real safety content, is not buried inside an optimiser.
 *
 * Note what is *not* here: no friction coefficient, no rider. The corridor is a statement about the
 * road, and grip in this project belongs to `Cyclist.maxLeanAngleDeg` (which is µ in disguise).
 * The solver will need the rider — for time weighting it has to know where cornering stops binding
 * — and will take it then.
 */
object RacingLine {
    /**
     * Analyse [path]'s geometry.
     *
     * Reads `roadWidth` where present and substitutes [RacingLineOptions.defaultRoadWidthM]
     * elsewhere. Does not read `trajectoryCurvature`: curvature is recomputed here from the
     * geometry, because the corridor must be built on the *smoothed reference*, not on whatever
     * the pipeline last wrote — jitter must never reach the constraint set.
     *
     * Returns `null` when the path cannot be projected (too short, non-finite coordinates, or too
     * near a pole), matching [PathCurvature.compute]'s contract of declining rather than guessing.
     */
    fun analyze(
        path: Path,
        options: RacingLineOptions = RacingLineOptions.DEFAULT,
    ): RacingLineReport? {
        val frame = LocalFrame.project(path, options.curvature.geometrySmoothWindowM) ?: return null
        CurvatureEstimator.computeHeadings(frame)
        CurvatureEstimator.computeCurvature(
            frame,
            options.curvature.curvatureWindowsM.toDoubleArray(),
            options.curvature.headingNoiseRad,
            options.curvature.curvatureSmoothWindowM,
        )

        val width = Corridor.resolveWidth(path, frame, options)
        val corners = CornerDetector.detect(frame, options, width)
        val bounds = Corridor.build(frame, width, options)

        return RacingLineReport(
            size = frame.size,
            corners = corners,
            centerlineCurvature = frame.kappa.copyOf(),
            corridorLo = bounds.lo,
            corridorHi = bounds.hi,
            roadHalfWidthM = bounds.halfWidthM,
        )
    }
}
