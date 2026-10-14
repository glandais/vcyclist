package io.github.glandais.api.service

import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.mapping.EnhanceSettings
import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate

/**
 * Runs the pipeline on one [path] with [settings] — the physics wiring of every door
 * (`EngineJsApi.enhanceWithCourse`, WASI's `vcEnhanceWithCourse`, the CLI's `enhance`).
 *
 * - the road-width override, when given, is written on **every** point first, like the CLI's
 *   `--road-width`: an explicit width beats the GPX's own and the OSM inference;
 * - the power provider is built **here, once per path** ([io.github.glandais.engine.physics.CyclistPowerSpec.toProvider]):
 *   the durability, critical-power and slew-limited providers keep state indexed by point, so
 *   one shared between two paths or two requests would carry the first ride into the second.
 *   `from_data` replays the path's `pInputPower`, as the CLI's `--gpx-power` does;
 * - the DEM is touched only when `fixElevation` is on, through [dem] — [demFor] gives it, `null`
 *   when the settings need none.
 *
 * Call it under the compute gate (`ComputeGate.run`): it is CPU-bound.
 */
suspend fun enhancePath(
    path: Path,
    settings: EnhanceSettings,
    dem: ElevationProvider?,
): Path {
    settings.roadWidthOverrideM?.let { width ->
        for (i in 0 until path.size) path.setRoadWidth(i, width)
    }
    val course =
        CoursePhysics(
            course = Course(path = path, cyclist = settings.cyclist, bike = settings.bike),
            rhoProvider = RhoProviderEstimate,
            aeroProvider = AeroProviderConstant,
            windProvider = settings.wind,
            cyclistPowerProvider = settings.power.toProvider(),
        )
    require(!settings.options.fixElevation || dem != null) { "fixElevation needs a DEM: use demFor(settings)" }
    return Enhancer.enhanceCourse(course, settings.options, if (settings.options.fixElevation) dem else null)
}

/** The DEM [settings] read, or `null` when `fixElevation` is off. */
fun ElevationGateway.demFor(settings: EnhanceSettings): ElevationProvider? =
    if (settings.options.fixElevation) provider(settings.demSource, settings.demZoom) else null

/**
 * 413 `too-many-points` when [paths] would densify past [maxSimulatedPoints]. The engine
 * densifies before anything else, so its cost — time and heap — follows the paths' length, which
 * the input point count does not bound: a two-point GPX spanning a continent is millions of
 * points. Checked after the parse, before the simulation; the 30 m DEM densification is below the
 * same bound, so `fixElevation` is covered too.
 */
fun checkSimulatedPoints(
    paths: List<Path>,
    maxSimulatedPoints: Int,
) {
    val bound = paths.sumOf { Enhancer.densifiedPointBound(it.size, it.totalDistance) }
    if (bound > maxSimulatedPoints) {
        val km = paths.sumOf { it.totalDistance } / 1000.0
        throw ApiProblem(
            ProblemKind.TOO_MANY_POINTS,
            "The selected paths (${km.toLong()} km) would be simulated on about ${bound.toLong()} points " +
                "once densified by the pipeline; the limit is $maxSimulatedPoints",
        )
    }
}
