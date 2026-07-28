package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * Which power a written GPX carries (task g30).
 *
 * A [Path] holds two: `pInputPower`, read from the source file's `<power>` extension, and
 * `pComputedPower`, the pedal power `PowerComputer` reconstructs from the simulation. Only one
 * can go into `<power>` on the way out, and the two reference implementations disagree on which:
 *
 * - `virtual-cyclist` (TypeScript) writes `pInputPower` — `GPXWriter.ts` guards on
 *   `!isNaN(trackPoint.pInputPower)`;
 * - gpx2web writes the **simulated** power, though not by choice: it has a single `power` slot,
 *   and `VirtualizeService.java:99` overwrites the value read from the file with the cyclist's
 *   simulated power.
 *
 * vcyclist keeps the TS behaviour as its default, and makes gpx2web's reachable. Writing
 * simulated data into a format the whole ecosystem reads as a recording is a decision that
 * belongs to the caller, not to a default.
 */
enum class GpxPowerSource {
    /** `pInputPower` — what the source file said. Nothing invented. The default. */
    INPUT,

    /** `pComputedPower` — what the simulation produced. Absent on a path that was never virtualized. */
    COMPUTED,

    /**
     * `pComputedPower` when the simulation produced one, `pInputPower` otherwise. For a trace
     * measured on part of the ride only, or an enhancement run with `virtualizeTrack = false` on
     * some paths and not others.
     */
    COMPUTED_OR_INPUT,
}

/**
 * Convert this [Path] back to a [GpxTrack]. Inverse of [GpxTrack.toPath]:
 *
 * - latitude/longitude are converted from internal **radians** back to degrees,
 * - elevation is copied through (preserving `0.0` as a valid value — only `NaN` is dropped),
 * - time:
 *   - a `NaN` `time(i)` (slot never written — `GeneratedPath` NaN-initialises every field) emits
 *     no `<time>` at all, whatever [startTime] is. Mirrors `GPXWriter.ts`, which guards on
 *     `!isNaN(trackPoint.time)`;
 *   - when [startTime] is `null` (the default), exposed as `epoch ms` **except** when
 *     `time(i) == 0` (treated as "absent" since the parser uses `0` as a sentinel for missing
 *     `<time>`, see [GpxTrack.toPath]) — this is the pre-g05 behaviour, unchanged;
 *   - when [startTime] is given, every point (including index 0) gets an **absolute** timestamp
 *     `startTime + time(i)` milliseconds, rounded to the nearest millisecond (see task g05).
 *     `time(i)` is the engine's relative simulation clock (`time(0) == 0`, see
 *     `VirtualizeService`), so this turns it into a wall-clock instant for consumption by
 *     devices/platforms (Garmin Connect, Strava) that require absolute timestamps.
 * - heart rate, cadence, temperature and power are emitted whenever the slot is not `NaN`. A
 *   genuine `0` (freewheeling cadence, 0 W, 0 °C) is a real reading and is written out; only an
 *   untouched slot is dropped. Mirrors `GPXWriter.ts`, which guards on `isNaN` alone.
 *
 * @param name optional value for the `<trk><name>` element.
 * @param type optional value for the `<trk><type>` element. Defaults to `"cycling"`.
 * @param startTime instant of point 0, used to compute absolute timestamps. `null` (the default)
 *   emits no `<time>` unless the raw `time(i)` field already looks like an epoch-ms value — see
 *   above.
 * @param powerSource which of the path's two power fields lands in `<power>`. See
 *   [GpxPowerSource]; the default writes the input power, as the TS reference does.
 */
fun Path.toGpxTrack(
    name: String? = null,
    type: String? = "cycling",
    startTime: Instant? = null,
    powerSource: GpxPowerSource = GpxPowerSource.INPUT,
): GpxTrack {
    val startTimeMs = startTime?.toEpochMilliseconds()
    val points =
        List(size) { i ->
            GpxTrackPoint(
                latitudeDeg = latitude(i) * MathConstants.RAD_TO_DEG,
                longitudeDeg = longitude(i) * MathConstants.RAD_TO_DEG,
                elevationM = elevation(i).takeUnless { it.isNaN() },
                timeEpochMs =
                    when {
                        time(i).isNaN() -> null
                        startTimeMs != null -> startTimeMs + time(i).roundToLong()
                        else -> time(i).toLong().takeIf { it > 0L }
                    },
                heartRate = heartRate(i).takeUnless { it.isNaN() }?.roundToInt(),
                cadence = cadence(i).takeUnless { it.isNaN() }?.roundToInt(),
                temperatureC = temperature(i).takeUnless { it.isNaN() },
                powerW = powerAt(i, powerSource),
            )
        }
    return GpxTrack(name = name, type = type, points = points)
}

/**
 * Wrap this [Path] in a [GpxDocument] with a single track. Convenience for callers that just need
 * a serializable document; for multi-track documents, build the [GpxDocument] directly.
 *
 * @param startTime see [Path.toGpxTrack].
 */
fun Path.toGpxDocument(
    name: String = "noname",
    trackName: String? = null,
    startTime: Instant? = null,
    powerSource: GpxPowerSource = GpxPowerSource.INPUT,
): GpxDocument =
    GpxDocument(
        name = name,
        tracks = listOf(toGpxTrack(name = trackName, startTime = startTime, powerSource = powerSource)),
    )

/**
 * Build a multi-track [GpxDocument] — one `<trk>` (single `<trkseg>`) per [Path], in order.
 *
 * @param paths one track per entry. An empty list produces a document with no track.
 * @param name value for `<metadata><name>`.
 * @param trackNames optional per-track `<trk><name>`. Entries beyond the list's size — or a
 *   `null` list — leave the track unnamed. Extra names are ignored.
 * @param waypoints carried through verbatim onto [GpxDocument.waypoints] — typically the source
 *   document's waypoints, so a caller re-attaching a track's enhancement output does not silently
 *   drop the points of interest. Not touched by `fixElevation` or any other pipeline step : see
 *   [GpxWaypoint].
 * @param type value for `<trk><type>` on every track. Defaults to `"cycling"`.
 * @param startTime instant of point 0, shared by every track — see [Path.toGpxTrack]. `null` (the
 *   default) preserves the pre-g05 behaviour strictly.
 * @param powerSource see [GpxPowerSource], shared by every track.
 */
fun pathsToGpxDocument(
    paths: List<Path>,
    name: String = "noname",
    trackNames: List<String>? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
    type: String? = "cycling",
    startTime: Instant? = null,
    powerSource: GpxPowerSource = GpxPowerSource.INPUT,
): GpxDocument =
    GpxDocument(
        name = name,
        tracks =
            paths.mapIndexed { i, p ->
                p.toGpxTrack(
                    name = trackNames?.getOrNull(i),
                    type = type,
                    startTime = startTime,
                    powerSource = powerSource,
                )
            },
        waypoints = waypoints,
    )

/**
 * The `<power>` value for point [i], or `null` to omit the element.
 *
 * `pComputedPower` needs a different absence test from `pInputPower`. The parser writes `NaN`
 * into `pInputPower` when the file carried no `<power>`, so `NaN` alone marks it absent. But
 * `pComputedPower` is written by the pipeline into a zero-initialised slot, and `PowerComputer`
 * legitimately stores `0.0` for point 0 and for any coasting point — so an un-simulated path is
 * all zeros and indistinguishable from a simulated descent by value alone. Treating `0.0` as
 * absent would drop real coasting points; treating it as present would emit a flat 0 W line for a
 * path that was never simulated. [GpxPowerSource.COMPUTED] takes the first reading, the same one
 * `PathToFit` documents and applies.
 */
private fun Path.powerAt(
    i: Int,
    source: GpxPowerSource,
): Double? {
    val input = pInputPower(i).takeUnless { it.isNaN() }
    val computed = pComputedPower(i).takeUnless { it.isNaN() || it == 0.0 }
    return when (source) {
        GpxPowerSource.INPUT -> input
        GpxPowerSource.COMPUTED -> computed
        GpxPowerSource.COMPUTED_OR_INPUT -> computed ?: input
    }
}
