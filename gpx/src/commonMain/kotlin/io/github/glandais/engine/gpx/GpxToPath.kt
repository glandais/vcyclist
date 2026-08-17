package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.time.Instant

/**
 * Instant of the **first** point that carries a `<time>` tag, in document order (all tracks and
 * routes, all segments — same order as [GpxTrack.points]). `null` if no point in the document is
 * timestamped.
 *
 * Since g24 this includes `<rtept>`: routes are usually not timestamped, but nothing in the
 * schema forbids it, and a planner that does stamp them should not be ignored.
 *
 * A parse → enhance → write round-trip loses this information today because [pointsToPath]
 * normalises `time` down to a `Path`-relative clock (`time(0) == 0`, see `VirtualizeService`).
 * Reading it here — from the raw, pre-conversion [GpxDocument] — lets a caller (CLI, JS façade)
 * reuse the source file's own start time as the `startTime` argument of [pathsToGpxDocument] /
 * [GpxWriter.write], instead of losing it or requiring the user to retype it (see task g05).
 */
val GpxDocument.startTime: Instant?
    get() =
        tracks
            .asSequence()
            .flatMap { it.points.asSequence() }
            .firstNotNullOfOrNull { it.timeEpochMs }
            ?.let { Instant.fromEpochMilliseconds(it) }

/**
 * Convert the **first** track of [this] document to a [Path], computing derived data.
 * Throws if the document has no track. Matches the TS `GPXParser.parse(...).tracks[0]` usage.
 *
 * Segments of that track are concatenated — see [GpxTrack.toPath] for what that implies on
 * distance. This is the pre-g02 behaviour, kept as the legitimate shortcut for the common
 * single-track case ; it is **not** deprecated.
 */
fun GpxDocument.firstTrackAsPath(): Path {
    val track = tracks.firstOrNull() ?: error("GpxDocument has no track")
    return track.toPath()
}

/**
 * One [Path] per `<trk>` **and per `<rte>`**, in document order. Segments of a same track are
 * **concatenated** (see [GpxTrack.toPath]).
 *
 * Tracks with no point at all are skipped, so the result never contains a parasitic `Path(0)`.
 * The result may therefore be shorter than [GpxDocument.tracks], and may be empty.
 *
 * @param kinds which containers to convert. The default takes both, which is what a caller
 *   asking for "the paths in this file" means — a file made only of `<rte>` used to come back
 *   empty, silently, before g24. Pass `setOf(GpxPathKind.TRACK)` to get the pre-g24 selection.
 *   A parameter rather than two more functions: the combinations are the point, not the names.
 */
fun GpxDocument.tracksAsPaths(kinds: Set<GpxPathKind> = ALL_KINDS): List<Path> =
    tracks
        .filter { it.kind in kinds && it.points.isNotEmpty() }
        .map { it.toPath() }

/**
 * One [Path] per `<trkseg>`, across **all** tracks, in document order. Empty segments are
 * skipped.
 *
 * A `<rte>` contributes exactly one [Path] here, since a route has no segments.
 *
 * Use this rather than [tracksAsPaths] when the inter-segment discontinuity matters : each
 * returned [Path] is continuous, so no phantom distance is introduced by the pause/teleport
 * between two segments. This is the shape gpx2web produces natively (one `GPXPath` per
 * `<trkseg>`).
 */
fun GpxDocument.segmentsAsPaths(kinds: Set<GpxPathKind> = ALL_KINDS): List<Path> =
    tracks
        .filter { it.kind in kinds }
        .flatMap { track -> track.segments.map { track.roadWidthM to it } }
        .filter { it.second.points.isNotEmpty() }
        .map { (trackWidth, segment) -> segment.toPath(trackWidth) }

/**
 * Both containers — the default selection of [tracksAsPaths] and [segmentsAsPaths].
 *
 * `internal` rather than private so `GpxToPathJvm` can name the same default: Java sees no default
 * argument, and a facade re-deriving `GpxPathKind.entries.toSet()` on its side would be a second
 * source of truth for it.
 */
internal val ALL_KINDS: Set<GpxPathKind> = GpxPathKind.entries.toSet()

/**
 * Materialise a [Path] from a [GpxTrack], concatenating its segments.
 *
 * **Distance across a segment boundary jumps.** The points of segment *n+1* follow those of
 * segment *n* with no marker, so [Path.computeDerivedData] measures the straight-line gap
 * between the last point of one segment and the first of the next, and folds it into
 * `totalDistance`. That is deliberate — it preserves the pre-g02 behaviour of
 * [GpxDocument.firstTrackAsPath] — but if the artefact is unacceptable, use
 * [GpxDocument.segmentsAsPaths] instead, which never crosses a boundary.
 *
 * Point-level conversion is described on [GpxSegment.toPath].
 */
fun GpxTrack.toPath(): Path = pointsToPath(points, roadWidthM)

/**
 * Materialise a [Path] from a single [GpxSegment]:
 * - latitude/longitude converted to radians (engine internal unit),
 * - elevation defaults to 0.0 if absent,
 * - time defaults to 0L if absent,
 * - heart rate / cadence / temperature / power copied through when present, **`NaN` when
 *   absent** — the backing array is zero-initialised, so an unwritten slot would be
 *   indistinguishable from a genuine `0` reading.
 *
 * [Path.computeDerivedData] is invoked before returning so downstream consumers can
 * immediately read `totalDistance`, `bearing`, `speed`, etc.
 */
fun GpxSegment.toPath(): Path = pointsToPath(points)

/** As [GpxSegment.toPath], with the enclosing track's default road width applied. */
internal fun GpxSegment.toPath(trackRoadWidthM: Double?): Path = pointsToPath(points, trackRoadWidthM)

/**
 * Widths outside this range are not roads. Anything narrower cannot be ridden two abreast and is
 * almost certainly a footpath value or a unit mix-up; anything wider is a runway or a typo.
 */
private val PLAUSIBLE_ROAD_WIDTH_M = 2.5..20.0

private fun pointsToPath(
    points: List<GpxTrackPoint>,
    trackRoadWidthM: Double? = null,
): Path {
    val path = Path(points.size)
    for ((i, p) in points.withIndex()) {
        path.setLatitude(i, p.latitudeDeg * MathConstants.DEG_TO_RAD)
        path.setLongitude(i, p.longitudeDeg * MathConstants.DEG_TO_RAD)
        path.setElevation(i, p.elevationM ?: 0.0)
        path.setTime(i, (p.timeEpochMs ?: 0L).toDouble())
        // An absent sensor is written as NaN, not left at the array's 0.0 : `0` is a legitimate
        // reading (0 °C, freewheeling at 0 rpm, 0 W) and must survive to the writers and to
        // `RhoProviderEstimate`. Mirrors the TS parser, which spreads the all-NaN `EMPTY_POINT`
        // into every point it creates.
        path.setPInputPower(i, p.powerW ?: Double.NaN)
        path.setHeartRate(i, p.heartRate?.toDouble() ?: Double.NaN)
        path.setCadence(i, p.cadence?.toDouble() ?: Double.NaN)
        path.setTemperature(i, p.temperatureC ?: Double.NaN)
        // A point's own width wins over the track default. Implausible values become NaN rather
        // than being clamped into range: a transcription error must not turn into a legal
        // corridor, because the racing-line gain is linear in the width it is given.
        val width = p.roadWidthM ?: trackRoadWidthM
        path.setRoadWidth(i, if (width != null && width in PLAUSIBLE_ROAD_WIDTH_M) width else Double.NaN)
    }
    path.computeDerivedData()
    return path
}
