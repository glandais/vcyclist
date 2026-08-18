package io.github.glandais.engine.trajectory

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
 * @property lateralOffsetM the solved line, metres from the reference, positive left
 * @property trajectoryCurvature the **exact** curvature of that line, m⁻¹, not the linearisation
 *   the solver minimised
 * @property newtonIterations projected-Newton iterations taken
 * @property relativeGradient final `‖g_F‖_∞` relative to the initial gradient — scale-free, so it
 *   means the same on a 5 m fixture and a 500 km route
 * @property converged whether the residual test was met within the iteration cap
 * @property activeConstraints stations finishing against a corridor bound
 */
class RacingLineReport(
    val size: Int,
    val corners: List<CornerSpan>,
    val centerlineCurvature: DoubleArray,
    val corridorLo: DoubleArray,
    val corridorHi: DoubleArray,
    val roadHalfWidthM: DoubleArray,
    val lateralOffsetM: DoubleArray,
    val trajectoryCurvature: DoubleArray,
    val newtonIterations: Int,
    val relativeGradient: Double,
    val converged: Boolean,
    val activeConstraints: Int,
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
 * Racing-line analysis and construction.
 *
 * [analyze] reports the geometry — corners, corridor, curvature, and the solved offset — without
 * touching the input. [compute] applies it, returning a new `Path` whose coordinates follow the
 * optimised line.
 *
 * Note what is *not* here: no friction coefficient, no rider. The corridor is a statement about
 * the road, and grip in this project belongs to `Cyclist.maxLeanAngleDeg` (which is µ in disguise).
 * The solver will need the rider once time weighting lands, and will take it then.
 */
object RacingLine {
    /**
     * Analyse [path]'s geometry and solve for the racing line, without modifying anything.
     *
     * Reads `roadWidth` where present and substitutes [RacingLineOptions.defaultRoadWidthM]
     * elsewhere. Does **not** read `trajectoryCurvature`: curvature is recomputed here from the
     * geometry, because the corridor must be built on the smoothed reference rather than on
     * whatever the pipeline last wrote — jitter must never reach the constraint set.
     *
     * Returns `null` when the path cannot be projected (too short, non-finite coordinates, or too
     * near a pole), matching [PathCurvature.compute]'s contract of declining rather than guessing.
     */
    fun analyze(
        path: Path,
        options: RacingLineOptions = RacingLineOptions.DEFAULT,
    ): RacingLineReport? {
        val frame = project(path, options) ?: return null
        return analyzeFrame(path, frame, options)
    }

    /**
     * Apply the racing line: a new [Path], same size, whose coordinates follow the solved offset.
     *
     * Returns `source.copy()` unchanged when the geometry cannot be analysed, so a caller never
     * has to special-case a degenerate input.
     *
     * ## What moves, and what does not
     *
     * Every coordinate is replaced by *smoothed reference + offset*. Even at zero offset that is a
     * small move, because the reference is smoothed — which is why the original position is
     * preserved in `sourceLatitude`/`sourceLongitude` rather than merely documented as lost. The
     * physics wants the smooth line; map-matching and segment detection want the recorded one.
     *
     * Elevation is copied index-aligned, and that is correct rather than an approximation to
     * apologise for: station `i` is the same road cross-section before and after, and the
     * cross-slope over a metre or two of lateral offset is centimetres — an order below the DEM's
     * own tolerance. `computeDerivedData` then rebuilds distance and grade from the new
     * coordinates, so a genuinely shorter inside line correctly reads as a slightly steeper one.
     */
    fun compute(
        source: Path,
        options: RacingLineOptions = RacingLineOptions.DEFAULT,
    ): Path {
        val frame = project(source, options) ?: return source.copy()
        val report = analyzeFrame(source, frame, options)

        val out = Path(source.size)
        for (i in 0 until source.size) {
            for (field in PointField.entries) out.set(i, field, source.get(i, field))
        }
        for (i in 0 until source.size) {
            val n = report.lateralOffsetM[i]
            // Left normal of the heading: (−sin θ, cos θ).
            val nx = -kotlin.math.sin(frame.theta[i])
            val ny = kotlin.math.cos(frame.theta[i])
            val x = frame.x[i] + n * nx
            val y = frame.y[i] + n * ny

            out.setSourceLatitude(i, source.latitude(i))
            out.setSourceLongitude(i, source.longitude(i))
            val latLon = LocalFrame.unproject(frame, x, y)
            out.setLatitude(i, latLon[0])
            out.setLongitude(i, latLon[1])
            out.setLateralOffset(i, n)
            out.setTrajectoryCurvature(i, report.trajectoryCurvature[i])
            out.setRoadWidth(i, source.roadWidth(i))
        }
        out.computeDerivedData()

        // Re-measure the curvature of the line we actually built, rather than trusting the
        // analytic offset formula.
        //
        // The analytic form is exact for a smooth `n`, but it reads `n''` off a finite second
        // difference at 1–2 m spacing, where a 0.1 m wiggle between adjacent stations already looks
        // like a 23 m bend. The solved offset has exactly such wiggles — a box-constrained solution
        // is only C¹ where it meets a bound — so the analytic curvature spikes at every one of
        // them, and `MaxSpeedComputer` reads those spikes as hairpins. Measured on the fixtures,
        // that alone made rides 16–27 % *slower* than the centreline they were supposed to improve.
        //
        // Re-running the estimator fixes both halves of the problem. It applies the same
        // multi-scale, noise-aware treatment the centreline gets, so the two are finally measured
        // the same way and can be compared at all; and it reports the curvature of the geometry
        // that was actually written, which is what the speed limits should follow.
        // Forced on regardless of the caller's curvature flag: this stage owns the field when it
        // runs, and leaving the analytic values behind for a caller who disabled the annotation
        // pass would mean the field means different things depending on an unrelated option.
        PathCurvature.compute(out, options.curvature.copy(enabled = true))
        return out
    }

    private fun project(
        path: Path,
        options: RacingLineOptions,
    ): PlanarFrame? {
        val frame = LocalFrame.project(path, options.curvature.geometrySmoothWindowM) ?: return null
        CurvatureEstimator.computeHeadings(frame)
        CurvatureEstimator.computeCurvature(
            frame,
            options.curvature.curvatureWindowsM.toDoubleArray(),
            options.curvature.headingNoiseRad,
            options.curvature.curvatureSmoothWindowM,
        )
        return frame
    }

    private fun analyzeFrame(
        path: Path,
        frame: PlanarFrame,
        options: RacingLineOptions,
    ): RacingLineReport {
        val width = Corridor.resolveWidth(path, frame, options)
        val corners = CornerDetector.detect(frame, options, width)
        val bounds = Corridor.build(frame, width, options)

        // The centring target is the projection of zero onto the corridor — "stay where you are if
        // you can, and hug the nearest legal position if you cannot".
        val center = DoubleArray(frame.size) { 0.0.coerceIn(bounds.lo[it], bounds.hi[it]) }
        // Optimise only where cornering can actually bind. Beyond `objectiveRadiusM` the speed
        // limit is not set by the corner, so straightening it buys nothing — and curvature that
        // gentle is mostly noise, which the objective would answer by integrating twice and
        // wandering off. See the option's KDoc.
        val objectiveKappa = 1.0 / options.objectiveRadiusM
        val rho = DoubleArray(frame.size) { if (abs(frame.kappa[it]) > objectiveKappa) 1.0 else 0.0 }

        val energy =
            OffsetEnergy.assemble(
                frame = frame,
                center = center,
                rho = rho,
                steeringLengthM = options.steeringLengthM,
                centeringLengthM = options.centeringLengthM,
            )
        // Start from the projection of zero — the rider's own line where that is feasible.
        //
        // The design specifies an analytic out–in–out seed here, on the grounds that it halves the
        // iteration count. Measured, it does the opposite: it is slower on every fixture (60
        // iterations against 43 on a 90° corner, 16 against 12 on a four-corner route) and faster
        // on none. The reason is visible in the active-set count — a seed that saturates the
        // corridor puts every station on a bound, and projected Newton then has to release them a
        // few at a time, whereas the solution itself has only a handful active. Since the design
        // is explicit that the seed carries no correctness burden, the fastest correct seed wins.
        val seed = center
        val solved =
            OffsetQp.solve(
                energy = energy,
                lo = bounds.lo,
                hi = bounds.hi,
                seed = seed,
                maxIterations = options.maxNewtonIterations,
                gradientTolerance = options.gradientTolerance,
                boundEpsilonM = options.boundEpsilonM,
            )
        val trajectoryCurvature = measureOffsetCurvature(frame, solved.offset, options)

        return RacingLineReport(
            size = frame.size,
            corners = corners,
            centerlineCurvature = frame.kappa.copyOf(),
            corridorLo = bounds.lo,
            corridorHi = bounds.hi,
            roadHalfWidthM = bounds.halfWidthM,
            lateralOffsetM = solved.offset,
            trajectoryCurvature = trajectoryCurvature,
            newtonIterations = solved.iterations,
            relativeGradient = solved.gradientInfNorm,
            converged = solved.converged,
            activeConstraints = solved.activeConstraints,
        )
    }

    /**
     * Curvature of the offset line, **measured** on the geometry the offset describes rather than
     * evaluated from the analytic offset formula.
     *
     * The analytic form is exact for a smooth `n` and useless for this one. It reads `n''` off a
     * finite second difference at 1-2 m spacing, where a 0.1 m wiggle already looks like a 23 m
     * bend, and a box-constrained solution is only C1 where it meets a bound, so it wiggles at
     * every corridor contact. Reported through it, a textbook apex-cutting line whose offset runs a
     * clean `+1.4 +2.0 +2.3 +2.5 +2.3 +1.7` reads as a 4 m hairpin.
     *
     * `compute` already sidesteps this by re-measuring the materialised path; doing the same here
     * is what makes [analyze] agree with it. Reporting a number the stage itself does not act on is
     * how a set of perfectly good corners came to be recorded as regressions.
     */
    private fun measureOffsetCurvature(
        frame: PlanarFrame,
        offset: DoubleArray,
        options: RacingLineOptions,
    ): DoubleArray {
        val size = frame.size
        val x = DoubleArray(size)
        val y = DoubleArray(size)
        for (i in 0 until size) {
            x[i] = frame.x[i] - offset[i] * sin(frame.theta[i])
            y[i] = frame.y[i] + offset[i] * cos(frame.theta[i])
        }
        val s = DoubleArray(size)
        LocalFrame.arclengthOf(x, y, s)
        val line =
            PlanarFrame(
                x = x,
                y = y,
                s = s,
                theta = DoubleArray(size),
                kappa = DoubleArray(size),
                lat0 = frame.lat0,
                lon0 = frame.lon0,
                k = frame.k,
            )
        CurvatureEstimator.computeHeadings(line)
        CurvatureEstimator.computeCurvature(
            line,
            options.curvature.curvatureWindowsM.toDoubleArray(),
            options.curvature.headingNoiseRad,
            options.curvature.curvatureSmoothWindowM,
        )
        return line.kappa
    }
}
