package io.github.glandais.api.mapping

import io.github.glandais.elevation.ElevationSource
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.gpx.GpxPowerSource
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.physics.CyclistPowerSpec

/**
 * **The single default site of the HTTP door.** Every reader in `mapping/` binds to it
 * (`val d = HttpDefaults.enhance`) and `GET /api/v1/defaults` publishes it through the inverse
 * mappers, so what is published is what is applied. No engine default is written as a literal
 * anywhere else in `:api`.
 *
 * ## Why the JS / WASI defaults, and not the engine's or the CLI's
 *
 * The engine's [EnhanceOptions.DEFAULT] runs the whole pipeline; each wire door picks its own
 * pipeline defaults and writes them in one place. JS and WASI differ from the engine on exactly
 * three fields — no DEM fetch, no 1 Hz resample, no simplification — and the CLI on one (no DEM
 * fetch). This door takes the JS / WASI three:
 *
 * - a caller moving from the JS façade to the API must get the same trace for the same JSON;
 * - `fixElevation = false` keeps the answer deterministic and offline until the caller asks for
 *   the DEM;
 * - every nested value comes from the stage's own options object (`copy` of the engine default),
 *   so a tolerance changed in the engine moves this door with it.
 *
 * The choice is reversible in one place: here.
 */
object HttpDefaults {
    val enhance: EnhanceOptions =
        EnhanceOptions.DEFAULT.copy(
            fixElevation = false,
            computeOnePointPerSecond = false,
            simplifyPath = EnhanceOptions.DEFAULT.simplifyPath.copy(enabled = false),
        )
    val cyclist: Cyclist get() = Cyclist()
    val bike: Bike get() = Bike()
    val power: CyclistPowerSpec get() = CyclistPowerSpec()
    val climb: ClimbOptions get() = ClimbOptions.DEFAULT
    val csv: CsvOptions get() = CsvOptions()
    val json: JsonOptions get() = JsonOptions()
    val gpxPowerSource: GpxPowerSource get() = GpxPowerSource.DEFAULT
    val elevationSource: ElevationSource get() = ElevationSource.DEFAULT

    /** The one door default the engine does not carry: JS and WASI each write it as a literal. */
    const val TRACK_NAME: String = "virtualized"

    /**
     * `GpxWriter.write(document, writeExtensions = true)`: the engine spells this default in a
     * signature, where no caller can read it, and carries no constant. Written once here, like
     * [TRACK_NAME], and published by `GET /defaults`.
     */
    const val GPX_WRITE_EXTENSIONS: Boolean = true

    /**
     * `List<Path>.toFitBytes(…, interPathGap = Duration.ZERO)`: same situation as
     * [GPX_WRITE_EXTENSIONS] — a signature default, no constant. Paths run straight on.
     */
    const val FIT_INTER_PATH_GAP_MS: Long = 0L

    /**
     * Whether GPX output copies the input's `<wpt>`: a door choice with no engine counterpart
     * (`pathsToGpxDocument` takes the waypoints as data). On, so a round trip does not silently
     * drop the points of interest — the lesson of F2.
     */
    const val GPX_INCLUDE_WAYPOINTS: Boolean = true
}
