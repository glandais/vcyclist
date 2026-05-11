package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PathSimplifier
import io.github.glandais.engine.path.PointPerSecond
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.MaxSpeedComputer
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.VirtualizeService
import io.github.glandais.engine.physics.WindProviderNone

/**
 * Top-level enhancement pipeline : transforms a raw GPS [Path] into a physics-aware
 * virtualized ride. Ordering matches the TS `Enhancer.enhanceCourse` minus `PointPerDistance`
 * (not ported — see task 25 notes).
 *
 * Steps (each optional via [EnhanceOptions]) :
 * 1. fix elevation (Terrarium tiles via [ElevationProvider]) + 150 m smoother
 * 2. compute max speeds (cornering + braking)
 * 3. virtualize track (time-stepping simulation)
 * 4. resample to 1 Hz
 * 5. simplify with Douglas-Peucker 3D
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

        // Step 1 : elevation fix + smooth. Smoother always runs (TS parity).
        if (options.fixElevation && elevationProvider != null) {
            path = ElevationStep.fixElevation(path, elevationProvider)
        }
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
