package io.github.glandais.api.service

import io.github.glandais.api.mapping.EnhanceSettings
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
 * - the DEM is touched only when `fixElevation` is on, through [elevation], whose lock is held
 *   for the whole pipeline of this path.
 *
 * Call it under the compute gate: it is CPU-bound.
 */
suspend fun enhancePath(
    path: Path,
    settings: EnhanceSettings,
    elevation: ElevationGateway,
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
    return if (settings.options.fixElevation) {
        elevation.withProvider(settings.demSource, settings.demZoom) { provider ->
            Enhancer.enhanceCourse(course, settings.options, provider)
        }
    } else {
        Enhancer.enhanceCourse(course, settings.options, null)
    }
}
