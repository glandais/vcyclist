package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.math.roundToInt

/**
 * Convert this [Path] back to a [GpxTrack]. Inverse of [GpxTrack.toPath]:
 *
 * - latitude/longitude are converted from internal **radians** back to degrees,
 * - elevation is copied through (preserving `0.0` as a valid value — only `NaN` is dropped),
 * - time is exposed as `epoch ms`, **except** when `time(i) == 0` (treated as "absent" since the
 *   parser uses `0` as a sentinel for missing `<time>`, see [GpxTrack.toPath]),
 * - heart rate, cadence, temperature and power are emitted only when **non-zero** (and not `NaN`):
 *   `GeneratedPath` initialises every slot to `0.0`, so `0.0` is interpreted as "not set" rather
 *   than as a real zero. Documented compromise — see task 15 spec.
 *
 * @param name optional value for the `<trk><name>` element.
 * @param type optional value for the `<trk><type>` element. Defaults to `"cycling"`.
 */
fun Path.toGpxTrack(
    name: String? = null,
    type: String? = "cycling",
): GpxTrack {
    val points =
        List(size) { i ->
            GpxTrackPoint(
                latitudeDeg = latitude(i) * MathConstants.RAD_TO_DEG,
                longitudeDeg = longitude(i) * MathConstants.RAD_TO_DEG,
                elevationM = elevation(i).takeUnless { it.isNaN() },
                timeEpochMs = time(i).toLong().takeIf { it > 0L },
                heartRate = heartRate(i).takeUnless { it.isNaN() || it == 0.0 }?.roundToInt(),
                cadence = cadence(i).takeUnless { it.isNaN() || it == 0.0 }?.roundToInt(),
                temperatureC = temperature(i).takeUnless { it.isNaN() || it == 0.0 },
                powerW = pInputPower(i).takeUnless { it.isNaN() || it == 0.0 },
            )
        }
    return GpxTrack(name = name, type = type, points = points)
}

/**
 * Wrap this [Path] in a [GpxDocument] with a single track. Convenience for callers that just need
 * a serializable document; for multi-track documents, build the [GpxDocument] directly.
 */
fun Path.toGpxDocument(
    name: String = "noname",
    trackName: String? = null,
): GpxDocument = GpxDocument(name = name, tracks = listOf(toGpxTrack(name = trackName)))

/**
 * Build a multi-track [GpxDocument] — one `<trk>` (single `<trkseg>`) per [Path], in order.
 *
 * @param paths one track per entry. An empty list produces a document with no track.
 * @param name value for `<metadata><name>`.
 * @param trackNames optional per-track `<trk><name>`. Entries beyond the list's size — or a
 *   `null` list — leave the track unnamed. Extra names are ignored.
 * @param type value for `<trk><type>` on every track. Defaults to `"cycling"`.
 */
fun pathsToGpxDocument(
    paths: List<Path>,
    name: String = "noname",
    trackNames: List<String>? = null,
    type: String? = "cycling",
): GpxDocument =
    GpxDocument(
        name = name,
        tracks = paths.mapIndexed { i, p -> p.toGpxTrack(name = trackNames?.getOrNull(i), type = type) },
    )
