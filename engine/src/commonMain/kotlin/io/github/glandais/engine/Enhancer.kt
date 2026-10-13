package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.BridgeDeckResult
import io.github.glandais.engine.path.BridgeDeckStep
import io.github.glandais.engine.path.DeckDetectorOptions
import io.github.glandais.engine.path.ElevationGain
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PathSimplifier
import io.github.glandais.engine.path.PointPerDistance
import io.github.glandais.engine.path.PointPerSecond
import io.github.glandais.engine.path.TunnelDetectorOptions
import io.github.glandais.engine.path.TunnelStep
import io.github.glandais.engine.path.writtenRange
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.MaxSpeedComputer
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.VirtualizeService
import io.github.glandais.engine.physics.WindProviderNone
import io.github.glandais.engine.physiology.WPrimeBalanceComputer
import io.github.glandais.engine.trajectory.PathCurvature
import io.github.glandais.engine.trajectory.RacingLine
import kotlin.math.abs

/**
 * Top-level enhancement pipeline : transforms a raw GPS [Path] into a physics-aware
 * virtualized ride.
 *
 * Steps (each optional via [EnhanceOptions], except `PointPerDistance` which is always run),
 * numbered as in `CLAUDE.md` :
 * 1. **Pre-fix densify** : `PointPerDistance.compute(path, -1.0, 30.0)` — densifies sparse
 *    paths to at most 30 m between points so that `fixElevation` has enough sample sites at
 *    the DEM tile resolution (~30 m). `minDist=-1` disables the lower bound so no source
 *    waypoint is dropped.
 * 2. fix elevation (DEM tiles via [ElevationProvider]) — optional.
 * 3. **Post-fix refine** : `PointPerDistance.compute(path, 1.0, 2.0)` — refines to 1-2 m
 *    spacing so downstream physics (`MaxSpeedComputer`, `VirtualizeService`) operates on a
 *    dense, regular trace.
 *    - **3b. bridge decks** (`BridgeDeckStep`) — only after step 2, and only when the provider
 *      carries a [surface model][ElevationProvider.surfaceModel] (`ign-lidar-hd` at zoom 15): each
 *      deck the LiDAR shows replaces the valley floor the terrain model reads under it. On the dense
 *      path, so a 40 m bridge is not two points; before step 4, so `sourceElevation` carries the
 *      deck. A tile it cannot read leaves the path as step 3 made it; the status is discarded here
 *      (reporting it is `elevation-bridge-decks` E7).
 *    - **3c. tunnels** (`TunnelStep`) — only after step 2, on **any** source: the terrain model
 *      reads the mountain above a tunnel, so each tunnel the terrain betrays (a bump with
 *      impossible grades on both sides, between portals a road could join) is replaced by the line
 *      between its portals. After 3b, whose terrain samples it reuses; before step 4, so
 *      `sourceElevation` and the reported D+ lose the fictitious climb. Same failure rule as 3b.
 * 4. smooth elevations (always runs) — keeps the pre-smoothing profile in `sourceElevation`.
 * 5. **curvature**, *or* the **racing line** : both write `trajectoryCurvature`, which step 6
 *    prefers over its own windowed estimate. The curvature pass is an annotation and moves nothing;
 *    the racing line replaces every coordinate with an optimised trajectory and is off by default.
 * 6. compute max speeds (cornering + braking).
 * 7. virtualize track (time-stepping simulation).
 * 8. resample to 1 Hz.
 * 9. annotate W′ balance (Critical Power model) — writes one field, changes no other.
 * 10. simplify with Douglas-Peucker 3D.
 * 11. elevation gain (D+/D− with a dead band) — annotation, reads `sourceElevation`, moves no point.
 *
 * Stateless ; safe for concurrent calls.
 */
object Enhancer {
    /** Step 1's spacing: the widest gap between two points step 2 reads the terrain at. */
    private const val PRE_FIX_SPACING_M = 30.0

    /** Build a [CoursePhysics] from [path] using all default-physics providers (ISA rho, no wind). */
    fun getDefaultCourse(path: Path): CoursePhysics =
        CoursePhysics(
            course = Course(path = path),
            rhoProvider = RhoProviderEstimate,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = PowerProviderConstant(EngineConstants.DEFAULT_CYCLIST_POWER_W),
        )

    /**
     * Enhance several [paths] — typically the tracks or segments of one multi-track GPX (see
     * `GpxDocument.tracksAsPaths()` / `segmentsAsPaths()`) — and return one result per input,
     * in the same order.
     *
     * Each path goes through [enhanceCourseDefault] **independently and sequentially**. The
     * sequential part is deliberate : [ElevationProvider] carries a shared tile cache whose
     * thread-safety has not been audited, and the JS target is single-threaded anyway,
     * so there is nothing to gain and a data race to lose.
     */
    suspend fun enhanceCourses(
        paths: List<Path>,
        elevationProvider: ElevationProvider? = null,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
    ): List<Path> = paths.map { enhanceCourseDefault(it, elevationProvider, options) }

    /**
     * Convenience : enhance [path] with all defaults and an optional [elevationProvider].
     *
     * Here — and only here — a `null` [elevationProvider] **means** "no elevation correction" :
     * `fixElevation` is resolved against the provider's presence before delegating, so the
     * one-argument call keeps working offline. [enhanceCourse], the explicit entry point, instead
     * treats `fixElevation = true` without a provider as a caller error (task g34).
     */
    suspend fun enhanceCourseDefault(
        path: Path,
        elevationProvider: ElevationProvider? = null,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
    ): Path =
        enhanceCourse(
            getDefaultCourse(path),
            options.copy(fixElevation = options.fixElevation && elevationProvider != null),
            elevationProvider,
        )

    /**
     * Run the enhancement pipeline.
     *
     * - [EnhanceOptions.fixElevation] requires an [elevationProvider] ; asking for the correction
     *   without providing one throws [IllegalArgumentException]. Until task g34 the step was
     *   silently skipped instead, which is how the CLI shipped a `--fix-elevation` that corrected
     *   nothing — a wrong output that *looks* right is worse than an exception. Callers who want
     *   "fix if you can" have [enhanceCourseDefault]. The smoother runs regardless.
     * - If [EnhanceOptions.virtualizeTrack] is `true`, max-speed computation is always run
     *   (the simulation needs `speedMax`).
     */
    suspend fun enhanceCourse(
        course: CoursePhysics,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
        elevationProvider: ElevationProvider? = null,
    ): Path = enhanceCourse(course, options, elevationProvider, tunnels = true)

    /**
     * [enhanceCourse] with step 3c switchable, for the measurement bench only: there is no door
     * option for it (that is `elevation-bridge-decks` E7).
     */
    internal suspend fun enhanceCourse(
        course: CoursePhysics,
        options: EnhanceOptions,
        elevationProvider: ElevationProvider?,
        tunnels: Boolean,
    ): Path {
        require(!options.fixElevation || elevationProvider != null) {
            "fixElevation = true but elevationProvider is null. Pass a provider, or set " +
                "fixElevation = false (enhanceCourseDefault resolves this automatically)."
        }
        var path = course.path

        // Step 1 : densify before fixElevation so DEM lookups have ~30 m granularity.
        path = PointPerDistance.compute(path, minDistanceM = -1.0, maxDistanceM = PRE_FIX_SPACING_M)

        // Step 2 : fix elevation (optional). The provider is non-null here — see the require above.
        if (options.fixElevation && elevationProvider != null) {
            path = ElevationStep.fixElevation(path, elevationProvider)
        }

        // Step 3 : refine to 1-2 m spacing before downstream physics.
        path = PointPerDistance.compute(path, minDistanceM = 1.0, maxDistanceM = 2.0)

        // Step 3b : bridge decks — only after a DEM correction (a GPX's own <ele>, a barometer's,
        // is never overwritten), and only when the provider carries a surface model. On the dense
        // path, before the smoother, so `sourceElevation` and the reported D+ carry the deck.
        val surface = elevationProvider?.surfaceModel
        var decks: BridgeDeckResult? = null
        if (options.fixElevation && elevationProvider != null && surface != null) {
            decks = BridgeDeckStep.apply(path, elevationProvider, surface)
            path = decks.path
        }

        // Step 3c : tunnels — after a DEM correction only, on any source (no surface model needed).
        // The deck write moves no coordinate, so 3b's terrain samples are this step's stations when
        // both grids agree; otherwise it reads them itself. It never writes over a deck.
        if (tunnels && options.fixElevation && elevationProvider != null) {
            val tunnelOptions = TunnelDetectorOptions.DEFAULT
            val deckStep = DeckDetectorOptions.DEFAULT.stepM
            val sameGrid = abs(tunnelOptions.stepM - deckStep) < 1e-9
            val protected = decks?.replacements?.map { it.writtenRange(deckStep) } ?: emptyList()
            path =
                TunnelStep
                    .apply(
                        path,
                        elevationProvider,
                        tunnelOptions,
                        samples = decks?.terrain?.takeIf { sameGrid },
                        protected = protected,
                        refreshM = PRE_FIX_SPACING_M,
                    ).path
        }

        // Step 4 : smooth elevations (always runs).
        path = ElevationStep.smoothElevation(path, options.elevationSmoothWindowM)

        // Step 5 : curvature, or the racing line.
        //
        // These are alternatives, not a sequence. Both write `trajectoryCurvature`, and the racing
        // line's is the curvature of the line *actually ridden* — which is the one the speed limits
        // want. Running the annotation first would only compute the centreline's and have it
        // overwritten.
        //
        // Placed here because the path is dense and its geometry is final, while `radius` and
        // `speedMax` are still unwritten, so step 6 consumes whichever ran.
        val racingLineApplied = options.racingLine.enabled
        if (racingLineApplied) {
            path = RacingLine.compute(path, options.racingLine)
        } else if (options.curvature.enabled) {
            PathCurvature.compute(path, options.curvature)
        }

        // Wrap the updated path into a fresh CoursePhysics carrying the new path.
        var working = course.copy(course = course.course.copy(path = path))

        // Step 6 : max speeds (always if virtualize, otherwise optional).
        if (options.computeMaxSpeeds || options.virtualizeTrack) {
            MaxSpeedComputer.computeMaxSpeeds(working.course)
        }

        // Step 7 : virtualize.
        if (options.virtualizeTrack) {
            path = VirtualizeService.virtualizeTrack(working)
            working = working.copy(course = working.course.copy(path = path))
        }

        // Step 8 : 1 Hz resample.
        if (options.computeOnePointPerSecond) {
            path = PointPerSecond.computeOnePointPerSecond(path)
        }

        // Step 9 : W′ balance. Annotation only — reads `pComputedPower`, writes `wPrimeBalance`,
        // touches nothing else. Runs before simplification so it integrates the full-resolution
        // power trace ; Douglas-Peucker then carries the values it keeps.
        if (options.wPrimeBalance.enabled) {
            WPrimeBalanceComputer.compute(path, options.wPrimeBalance)
        }

        // Step 10 : simplify.
        if (options.simplifyPath.enabled) {
            // The racing line lives inside roughly 2.5 m of the centreline, so the default 10 m
            // Douglas-Peucker tolerance would discard the entire deliverable — computed, written,
            // and then simplified away. Cap it whenever the stage ran.
            val tolerance =
                if (racingLineApplied) {
                    minOf(options.simplifyPath.toleranceM, options.racingLine.simplifyToleranceCapM)
                } else {
                    options.simplifyPath.toleranceM
                }
            path =
                PathSimplifier.simplify(
                    path,
                    tolerance,
                    options.simplifyPath.zExaggeration,
                )
        }

        // Step 11 : cumulative ascent with a dead band. Annotation only — writes two cached scalars
        // on the path and touches no point.
        //
        // Last, after simplification, so the figure describes the file the caller receives:
        // Douglas-Peucker drops points, which moves the raw sum, and a summary that disagrees with
        // its own trace is worse than no summary. It reads `sourceElevation`, so simplifying does
        // not cost it the pre-smoothing profile either.
        if (options.elevationGain.enabled) {
            ElevationGain.annotate(path, options.elevationGain)
        }

        return path
    }
}
