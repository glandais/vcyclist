package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PathSimplifier
import io.github.glandais.engine.path.PointPerDistance
import io.github.glandais.engine.path.PointPerSecond
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.MaxSpeedComputer
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.VirtualizeService
import io.github.glandais.engine.physics.WindProviderNone

/**
 * Top-level enhancement pipeline : transforms a raw GPS [Path] into a physics-aware
 * virtualized ride. Ordering matches the TS `Enhancer.enhanceCourse`.
 *
 * Steps (each optional via [EnhanceOptions], except `PointPerDistance` which is always run) :
 * 1. **Pre-fix densify** : `PointPerDistance.compute(path, -1.0, 30.0)` — densifies sparse
 *    paths to at most 30 m between points so that `fixElevation` has enough sample sites at
 *    the DEM tile resolution (~30 m). `minDist=-1` disables the lower bound so no source
 *    waypoint is dropped.
 * 2. fix elevation (Terrarium tiles via [ElevationProvider]) — optional.
 * 3. **Post-fix refine** : `PointPerDistance.compute(path, 1.0, 2.0)` — refines to 1-2 m
 *    spacing so downstream physics (`MaxSpeedComputer`, `VirtualizeService`) operates on a
 *    dense, regular trace.
 * 4. smooth elevations (always runs — TS parity).
 * 5. compute max speeds (cornering + braking).
 * 6. virtualize track (time-stepping simulation).
 * 7. resample to 1 Hz.
 * 8. simplify with Douglas-Peucker 3D.
 *
 * Stateless ; safe for concurrent calls.
 */
object Enhancer {
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
     * thread-safety has not been audited, and the JS/Wasm targets are single-threaded anyway,
     * so there is nothing to gain and a data race to lose.
     */
    suspend fun enhanceCourses(
        paths: List<Path>,
        elevationProvider: ElevationProvider? = null,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
    ): List<Path> = paths.map { enhanceCourseDefault(it, elevationProvider, options) }

    /** Convenience : enhance [path] with all defaults and an optional [elevationProvider]. */
    suspend fun enhanceCourseDefault(
        path: Path,
        elevationProvider: ElevationProvider? = null,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
    ): Path = enhanceCourse(getDefaultCourse(path), options, elevationProvider)

    /**
     * Run the enhancement pipeline.
     *
     * - If [elevationProvider] is `null`, [EnhanceOptions.fixElevation] is skipped (no
     *   provider → can't pull elevations). The smoother runs regardless.
     * - If [EnhanceOptions.virtualizeTrack] is `true`, max-speed computation is always run
     *   (the simulation needs `speedMax`).
     */
    suspend fun enhanceCourse(
        course: CoursePhysics,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
        elevationProvider: ElevationProvider? = null,
    ): Path {
        var path = course.path

        // Step 1a : densify before fixElevation so DEM lookups have ~30 m granularity.
        path = PointPerDistance.compute(path, minDistanceM = -1.0, maxDistanceM = 30.0)

        // Step 1b : fix elevation (optional).
        if (options.fixElevation && elevationProvider != null) {
            path = ElevationStep.fixElevation(path, elevationProvider)
        }

        // Step 1c : refine to 1-2 m spacing before downstream physics.
        path = PointPerDistance.compute(path, minDistanceM = 1.0, maxDistanceM = 2.0)

        // Step 1d : smooth elevations (always runs — TS parity).
        path = ElevationStep.smoothElevation(path)

        // Wrap the updated path into a fresh CoursePhysics carrying the new path.
        var working = course.copy(course = course.course.copy(path = path))

        // Step 2 : max speeds (always if virtualize, otherwise optional).
        if (options.computeMaxSpeeds || options.virtualizeTrack) {
            MaxSpeedComputer.computeMaxSpeeds(working.course)
        }

        // Step 3 : virtualize.
        if (options.virtualizeTrack) {
            path = VirtualizeService.virtualizeTrack(working)
            working = working.copy(course = working.course.copy(path = path))
        }

        // Step 4 : 1 Hz resample.
        if (options.computeOnePointPerSecond) {
            path = PointPerSecond.computeOnePointPerSecond(path)
        }

        // Step 5 : simplify.
        if (options.simplifyPath.enabled) {
            path =
                PathSimplifier.simplify(
                    path,
                    options.simplifyPath.toleranceM,
                    options.simplifyPath.zExaggeration,
                )
        }

        return path
    }
}
